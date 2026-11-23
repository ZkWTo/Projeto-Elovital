# ADR 0006: Arquitetura modular em camadas

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md)
- **Relacionado:** [ADR 0005](0005-stack-java-spring-boot.md), [ADR 0007](0007-persistencia-postgresql-jpa-flyway.md), [ADR 0011](0011-maquinas-de-estado-do-fluxo.md)
- **Substitui:** [ADR 0001](0001-mvt-com-camada-de-service.md)

## Contexto

As regras críticas do EloVital não podem ficar espalhadas entre controllers, templates, entidades e listeners: aprovação humana, reserva atômica, liberação progressiva de dados, dupla confirmação, sanção, contestação e auditoria. Elas precisam:

- ser testáveis sem HTTP e sem subir o servidor;
- ser reutilizadas pelo site, pelo painel de mediação e pelas rotinas agendadas;
- falhar de forma previsível, com mensagens de domínio que o usuário entenda (RNF007);
- garantir que nenhuma escrita parcial aconteça (RNF006);
- impedir que uma alteração de tela quebre uma regra de negócio.

O risco de uma arquitetura em camadas "de fachada" é a regra vazar para o controller (por conveniência) ou para a entidade (por preguiça), criando dois caminhos com comportamentos diferentes.

## Decisão

### Monólito modular com quatro camadas por módulo

Um único artefato implantável, dividido em módulos de domínio por pacote. Cada módulo tem quatro camadas:

```text
br.com.elovital.contas/
  domain/            # entidades, value objects, enums, exceções, regras invariantes
  application/       # casos de uso (@Service), comandos, resultados, portas
  infrastructure/    # adapters: JPA, Storage, e-mail, integrações
  web/               # controllers, forms, view models, mappers de tela
```

Regras de dependência:

```text
web  ──> application ──> domain
infrastructure ──> application ──> domain
```

- `domain` não importa Spring, JPA, HTTP ou qualquer camada externa.
- `application` pode usar anotações de transação (`@Transactional`) e portas, mas não conhece `HttpServletRequest`, `Model`, `HttpServletResponse` ou template.
- `infrastructure` implementa portas e é a única que conhece `EntityManager`, `JdbcTemplate`, SDK do Storage e clientes HTTP.
- `web` valida formato, chama o caso de uso e traduz o resultado em template ou JSON.

### Módulos

| Módulo | Responsabilidade | RF |
|---|---|---|
| `contas` | Usuário, endereço, termos, perfis, verificação de identidade, login | RF001, RF002, RF003, RF016 |
| `catalogo` | Especialidades, condições crônicas, parâmetros, municípios | RF001, RF005, RF015 |
| `agenda` | Horários, recorrência, disponibilidade | RF004, RF007 |
| `solicitacoes` | Solicitação, períodos possíveis, análise da mediação | RF005, RF006 |
| `reservas` | Reserva, ciência, cancelamento, remarcação, confirmação | RF008, RF009, RF012 |
| `comunicacao` | Chat, notificações, central interna | RF010, RF011 |
| `reputacao` | Avaliações, ocorrências | RF013, RF017 |
| `moderacao` | Denúncias, sanções, contestações, painel | RF014, RF015 |
| `auditoria` | Trilha de auditoria e acesso a dados pessoais | RNF012, RS012 |
| `shared` | Exceções base, cripto, relógio, segurança, paginação, utilidades | — |

### Contrato de caso de uso

Todo caso de uso segue a mesma forma:

```java
@Service
public class ReservarHorarioService {

    @Transactional
    public ReservaCriada executar(ReservarHorarioCommand comando) {
        // 1. carrega agregados
        // 2. valida estado e autorização com ator explícito
        // 3. aplica a transição de domínio
        // 4. persiste
        // 5. publica evento de domínio (após commit)
        return ReservaCriada.de(reserva);
    }
}
```

- O comando é um `record` com dados primitivos e o **ator explícito** (`atorId`, `atorPapel`). Nunca recebe `Authentication`, `Principal` ou `HttpServletRequest`.
- O retorno é um `record` de resultado ou um DTO de leitura. Nunca entidade JPA exposta para a camada web.
- `@Transactional` fica na camada `application`, nunca no controller.
- O caso de uso **não** formata mensagem de tela: lança exceção de domínio com código estável.
- Operações de leitura complexas têm **serviço de consulta** próprio (`ConsultarCatalogoService`), que devolve projeções e não entidades.

### Exceções de domínio

```text
EloVitalException
├── ValidacaoException(campo, codigo, mensagem)
├── PermissaoNegadaException
├── EstadoInvalidoException(estadoAtual, acao)
├── ConflitoException(recurso)
├── PrazoExpiradoException(prazo)
├── LimiteExcedidoException(parametro, limite)
├── IntegracaoException(servico)
└── DadoSensivelException(motivoObrigatorio)
```

Mapeamento na borda:

| Exceção | HTML | JSON |
|---|---|---|
| `ValidacaoException` | formulário com erro por campo | 422 `ProblemDetail` com `errors` |
| `PermissaoNegadaException` | 403 com orientação | 403 |
| `EstadoInvalidoException` | 409 com próxima ação | 409 |
| `ConflitoException` | 409 com recarga da tela | 409 |
| `PrazoExpiradoException` | 410 ou redirect informativo | 410 |
| `LimiteExcedidoException` | mensagem com o limite do parâmetro | 429/422 |
| `IntegracaoException` | mensagem de indisponibilidade, fluxo preservado | 503 |

### Eventos de domínio

Efeitos colaterais (notificar, auditar, agendar expiração) são publicados como evento e tratados **após o commit**:

```java
events.publishEvent(new ReservaCriadaEvent(reservaId));

@TransactionalEventListener(phase = AFTER_COMMIT)
void aoCriarReserva(ReservaCriadaEvent evento) { ... }
```

Regra: nenhum evento é usado para **decidir** estado. O evento apenas propaga o que já foi decidido dentro da transação. Se o handler falhar, a operação principal continua válida e a falha vai para log/alerta — notificação nunca derruba reserva.

### Portas e adapters

- Portas de saída ficam em `application/port` (ex.: `ArmazenamentoDocumentos`, `EnviadorEmail`, `ConsultaCep`, `Relogio`).
- Implementações ficam em `infrastructure/port`.
- Em teste, usa-se implementação em memória ou `@MockBean` da porta, sem subir serviço externo.
- Repositórios: a porta é uma interface de domínio/aplicação; o adapter é uma interface `JpaRepository` em `infrastructure`.

### Dependência entre módulos

- Um módulo não importa `domain` ou `infrastructure` de outro.
- A comunicação ocorre por **porta pública** (`contas.api.UsuarioConsulta`) ou por **evento**.
- Dependências circulares são proibidas e verificadas no build.

### Validação em três níveis

| Nível | O que valida | Onde |
|---|---|---|
| Formato | obrigatório, tamanho, máscara, e-mail, data | Bean Validation no form/DTO |
| Invariante de domínio | estado permitido, maioridade, unicidade local, transição | entidade/value object + service |
| Regra de negócio | prazo, limite configurável, autorização, efeitos | caso de uso |

A mesma regra não é duplicada: o formulário valida formato; o caso de uso valida o que depende de banco, estado ou parâmetro.

## Fluxo de usuário afetado

| Etapa | Onde a arquitetura aparece |
|---|---|
| Cadastro | `contas.web.CadastroController` → `CadastrarUsuarioService` → `UsuarioRepository`; termos e consentimento no mesmo caso de uso |
| Verificação | `contas.web.VerificacaoController` → service → `documento_verificacao` + Storage |
| Login | filtro do Spring Security + `contas.application.AutenticarUsuarioService` |
| Agenda | `agenda.web.HorarioController` → `PublicarHorarioService` (recorrência e conflito) |
| Solicitação | `solicitacoes.web.SolicitacaoController` → `CriarSolicitacaoService` |
| Análise | `solicitacoes.application.AnalisarSolicitacaoService` chamado pelo painel de mediação |
| Catálogo | `catalogo.application.ConsultarCatalogoService` com projeção |
| Reserva | `reservas.application.ReservarHorarioService`, transação única ([ADR 0012](0012-reserva-atomica-e-concorrencia.md)) |
| Chat | `comunicacao.application.EnviarMensagemService` |
| Conclusão | `reservas.application.RegistrarConfirmacaoService` |
| Avaliação | `reputacao.application.AvaliarService` |
| Denúncia | `moderacao.application.RegistrarDenunciaService` |
| Mediação | controllers sob `/mediacao`, mesmos serviços, com autorização própria |

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Controller gordo com repositório direto | Regra duplicada entre painel e site; impossível testar sem HTTP. |
| "Clean architecture" com quatro projetos Maven por módulo | Custo de build e navegação desproporcional para a v1. |
| Microsserviços | Transações distribuídas para reserva, notificação e auditoria; custo operacional alto sem ganho. |
| Regra em entidade JPA com `@PrePersist` | Efeitos colaterais e dificuldade de testar; a entidade guarda invariantes locais, não o fluxo. |
| Regra em trigger de banco | Fica invisível para o código e difícil de versionar; usada apenas como última barreira (constraints). |

## Consequências

- Mais classes por funcionalidade (controller, form, comando, service, resultado, mapper). O ganho é testabilidade e um único caminho de regra.
- O time precisa respeitar a direção das dependências; sem ArchUnit, a arquitetura degrada.
- Serviços "gigantes" são um risco real: cada caso de uso deve fazer **uma** operação, não um CRUD inteiro.
- Consultas de leitura podem ficar lentas se passarem por entidades; por isso há serviços de consulta com projeção.
- O mapeamento entidade → DTO → view model adiciona código, mas evita vazar campo sensível para o template.

## Critérios de aceite

Testes de arquitetura (ArchUnit) no build:

- [ ] Nenhum controller referencia `EntityManager`, `JpaRepository` ou entidade de outro módulo.
- [ ] Nenhum `@Service` de `application` importa `org.springframework.web.*`, `jakarta.servlet.*` ou `org.thymeleaf.*`.
- [ ] Nenhuma entidade em `domain` importa `jakarta.persistence.*` (mapa JPA fica em adapter) **ou** a exceção é documentada e restrita ao módulo.
- [ ] Nenhum módulo importa `infrastructure` de outro módulo.
- [ ] Não existem ciclos entre módulos.
- [ ] Nenhum `form.save()` equivalente: controller nunca chama `repository.save()`.
- [ ] Toda exceção de domínio herda de `EloVitalException`, não de `ResponseStatusException`.
- [ ] Todo caso de uso que escreve está anotado com `@Transactional`.
- [ ] Nenhum `LocalDateTime.now()` ou `Instant.now()` em `application`; usa-se `Relogio`.

## Decisões pendentes

> **DECISÃO PENDENTE 41:** confirmar a lista de módulos e a fronteira de `catalogo` (parâmetros e cadastros-base podem virar módulo próprio). Impacto: organização, não comportamento.
>
> **DECISÃO PENDENTE 42:** confirmar se entidades JPA ficam em `domain` (com anotações) ou em `infrastructure` (domínio puro + mapper). A opção pura é mais testável e mais verbosa; a opção anotada é mais rápida de escrever. Recomendação: entidades anotadas em `domain`, com a regra de não usar API de persistência dentro delas.
>
> **DECISÃO PENDENTE 43:** definir se a autorização fica no caso de uso (recomendado) ou em `@PreAuthorize` na borda. A recomendação é autorização de estado no caso de uso e `@PreAuthorize` apenas como barreira de papel.
