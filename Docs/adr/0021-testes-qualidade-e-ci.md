# ADR 0021: Testes, qualidade e CI

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — critérios de conclusão 6 e 7; RF001–RF017
- **Relacionado:** [ADR 0006](0006-arquitetura-em-camadas.md), [ADR 0011](0011-maquinas-de-estado-do-fluxo.md), [ADR 0012](0012-reserva-atomica-e-concorrencia.md), [ADR 0020](0020-observabilidade-e-operacao.md)

## Contexto

O critério de conclusão 6 do PRD exige que **todos os requisitos funcionais tenham pelo menos um teste automatizado** e que a suíte passe por completo. O critério 4 exige controles de segurança e LGPD testados. O RNF006 exige prova de transacionalidade, e o RS008 exige prova de que duas reservas no mesmo horário são impossíveis.

Testar isso com mocks de banco não serve: o comportamento crítico está justamente no banco (lock, constraint, isolamento, expurgo). E testar arquitetura não é luxo: sem ArchUnit, as camadas degradam em poucas sprints.

## Decisão

### Pirâmide de testes

| Nível | Ferramenta | O que cobre | Quantidade |
|---|---|---|---|
| Unitário de domínio | JUnit 5 + AssertJ | transições, invariantes, value objects, cálculo de prazo | muitos |
| Unitário de caso de uso | JUnit 5 + Mockito | orquestração, autorização, exceções | muitos |
| Integração de service | Spring Boot Test + Testcontainers | transação, lock, constraint, JPA | médio |
| Integração web | MockMvc | rotas, validação, CSRF, autorização, template | médio |
| Contrato/API | MockMvc + OpenAPI | formato de erro, paginação, campos | pequeno |
| Concorrência | Testcontainers + threads | reserva dupla, idempotência | pequeno e crítico |
| Arquitetura | ArchUnit | dependências entre camadas/módulos | pequeno |
| End-to-end | Playwright (fora do Java) | fluxo completo no navegador | pequeno |
| Acessibilidade | axe-core via Playwright | telas principais | pequeno |
| Segurança | OWASP ZAP (baseline) + testes de autorização | XSS, CSRF, enumeração | pequeno |

### Banco real nos testes de integração

- Testcontainers com `postgis/postgis:16-3.4`, **mesma versão** da produção.
- Flyway roda do zero em cada contexto de teste.
- Não se usa H2: não reproduz PostGIS, `citext`, RLS nem locking.
- Cada teste de integração roda em transação com rollback, exceto os de concorrência (que precisam de commits reais e limpeza explícita).

```java
@SpringBootTest
@Testcontainers
class ReservarHorarioIT {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgis/postgis:16-3.4");

    @Test
    void duasReservasConcorrentesProduzemUmaSomente() throws Exception {
        // duas threads, duas conexões, mesma horarioId
        // assert: uma sucesso, uma ConflitoException
    }
}
```

### Testes de concorrência

Cenários obrigatórios:

1. Duas reservas simultâneas no mesmo horário → exatamente uma.
2. Duas confirmações simultâneas da mesma parte → uma só.
3. Cancelamento e reserva simultâneos no mesmo horário → estado consistente.
4. Duas execuções da rotina de expiração → sem efeito duplicado.
5. Reenvio com a mesma `Idempotency-Key` → uma reserva.
6. Suspensão de conta durante reserva → nenhuma reserva órfã.

Regras:

- Usar `CountDownLatch`/`CyclicBarrier` para disparar ao mesmo tempo.
- Cada thread com sua própria conexão.
- Validar não só o resultado, mas o estado final no banco.

### Testes de segurança

| Teste | Espera |
|---|---|
| Assistido A acessa chat da reserva de B | 403/404 |
| Mediação sem authority acessa CPF | 403 + auditoria |
| Payload `<script>` em todos os campos | exibido como texto |
| Upload com extensão falsa | recusado |
| URL assinada expirada | recusada |
| 6ª tentativa de login | bloqueio |
| Enumeração de e-mail | mesma resposta e tempo |
| CSRF ausente em POST | 403 |
| SQL injection em filtro | tratado como texto |
| Documento de outro usuário | negado + auditado |

### Testes de arquitetura (ArchUnit)

```java
@ArchTest
static final ArchRule controllers_nao_acessam_repositorios =
    noClasses().that().resideInAPackage("..web..")
        .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

@ArchTest
static final ArchRule application_nao_conhece_web =
    noClasses().that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.web..", "jakarta.servlet..", "org.thymeleaf..");

@ArchTest
static final ArchRule modulos_sem_ciclo = slices().matching("br.com.elovital.(*)..")
    .should().beFreeOfCycles();
```

### Cobertura

- Mínimo **85% de linhas em `application` e `domain`** (Decisão do PRD sugere 85% em services).
- Cobertura não é meta isolada: um caso de uso com 100% de linha pode não testar transição proibida.
- Cobertura de mutação é opcional na v1.
- Cobertura de controller não é meta; o que importa é a regra.

### Dados de teste

- Builders e fixtures (`UsuarioBuilder`, `SolicitacaoBuilder`) em `src/test/java`.
- Nenhum dado real, nem anonimizado de produção.
- Seed de teste determinístico; `Clock` fixo.
- Testes não dependem de ordem de execução.

### CI

Pipeline em pull request:

```text
1. spotless:check (formatação)
2. compile + spotbugs/PMD (opcional na v1)
3. ArchUnit
4. unit tests
5. integration tests (Testcontainers)
6. flyway validate + migrate do zero
7. OWASP Dependency-Check + SBOM (CycloneDX)
8. testes de contrato e segurança
9. build da imagem
10. (main) deploy em staging + E2E Playwright
```

Regras:

- PR não entra com teste vermelho.
- Build de `main` publica artefato versionado.
- Migrations são validadas em banco vazio e em cópia anonimizada (quando houver).
- Segredos vêm do cofre do CI.
- Duração alvo do pipeline: ≤ 15 min (integração paralela por módulo).

### Mapa mínimo de testes por requisito

| RF | Teste mínimo |
|---|---|
| RF001 | CPF duplicado, menor de 18, foto inválida |
| RF002 | bloqueio, recuperação de uso único, sessão expirada |
| RF003 | CPF imutável, auditoria de alteração |
| RF004 | recorrência sem data passada, conflito de horário |
| RF005 | não publica sem mediação, pedido de dinheiro bloqueado |
| RF006 | recusa com justificativa, contestação única |
| RF007 | nenhum campo proibido na resposta do catálogo |
| RF008 | duas reservas concorrentes, ciência em 24 h |
| RF009 | 24 h vs. 23h59, remarcação |
| RF010 | categoria obrigatória, entrega em 60 s |
| RF011 | acesso indevido ao chat, mensagem oculta |
| RF012 | uma confirmação não conclui antes do prazo |
| RF013 | avaliação duplicada e fora do prazo |
| RF014 | terceira denúncia suspende, trecho anexado |
| RF015 | mediação não altera nota, acesso auditado |
| RF016 | documento sem URL pública, expurgo |
| RF017 | rotina idempotente, histórico só do titular |

### Qualidade de código

- Spotless + google-java-format.
- Regras de nome: domínio em português, infraestrutura em inglês.
- Proibido `System.out`, `printStackTrace`, `catch (Exception e) {}` vazio.
- Proibido `@Autowired` em campo.
- Revisão obrigatória por outra pessoa.
- Dependências: versão gerenciada pelo BOM; adição exige justificativa.

## Fluxo de usuário afetado

Todos. O mapa acima garante que cada etapa do PRD tem prova automatizada, e os testes de concorrência cobrem o ponto mais crítico do fluxo (reserva).

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| H2 em memória | Não reproduz o comportamento real. |
| Só testes E2E | Lentos, frágeis e não localizam a falha. |
| Só testes unitários com mock | Não provam transação nem constraint. |
| Cobertura como única métrica | Não mede qualidade de asserção. |
| Testes manuais para LGPD | Não são repetíveis nem auditáveis. |

## Consequências

- A suíte de integração exige Docker no CI e na máquina de desenvolvimento.
- Testcontainers aumenta o tempo de build; paralelização é necessária.
- 85% em `application`/`domain` exige disciplina contínua.
- Testes de concorrência são sensíveis e precisam de repetição para não serem intermitentes.
- E2E e acessibilidade adicionam tempo, mas cobrem requisito explícito.

## Critérios de aceite

- [ ] `./mvnw verify` roda unitário, integração, ArchUnit e Spotless.
- [ ] CI usa Testcontainers com PostGIS 16-3.4.
- [ ] Existe teste de concorrência para reserva, confirmação e idempotência.
- [ ] Todos os RF001–RF017 têm ao menos um teste automatizado.
- [ ] Cobertura ≥ 85% em `application` e `domain`.
- [ ] ArchUnit falha se uma camada violar a dependência.
- [ ] Teste de XSS e de autorização por URL direta existe.
- [ ] Nenhum teste depende de dado real.
- [ ] Pipeline falha com migration inválida.
- [ ] SBOM é gerado no build.

## Decisões pendentes

> **DECISÃO PENDENTE 107:** confirmar cobertura mínima (85% em `application`/`domain`) e se mutação entra na v1.
>
> **DECISÃO PENDENTE 108:** escolher ferramenta de CI e se E2E roda em todo PR ou só em `main`.
>
> **DECISÃO PENDENTE 109:** definir se haverá teste de carga (k6/JMeter) antes do piloto e com qual meta.
>
> **DECISÃO PENDENTE 110:** confirmar se OWASP ZAP baseline entra no pipeline ou em revisão manual.
