# ADR 0019: Parâmetros configuráveis e cadastros-base

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — RNF010, RF015, seção 6
- **Relacionado:** [ADR 0006](0006-arquitetura-em-camadas.md), [ADR 0009](0009-conteudo-texto-puro-e-validacao.md), [ADR 0014](0014-auditoria-e-acesso-a-dados.md), [ADR 0023](0023-painel-de-mediacao.md)

## Contexto

O RNF010 exige que limites, prazos, raios e regras de antecedência sejam configuráveis pela mediação **sem alteração de código**. A seção 6 do PRD lista 26 parâmetros, entre eles:

- sessão (30 min), bloqueio de tentativas (5/15 min), validade do link (24 h);
- prazo de análise (24 h úteis), validade da solicitação (30 dias), limite de abertas;
- duração de horário (30 min–4 h), limite diário, raio padrão e máximo;
- antecedência de cancelamento (24 h), ciência (24 h), conclusão (72 h), avaliação (7 dias);
- chat somente leitura (30 dias), suspensão preventiva (3 em 30 dias), retenção de documento (90 dias);
- limites de texto, paginação.

Além dos parâmetros, o RF015 exige **cadastros-base** editáveis: tipos de apoio/especialidades, condições crônicas, motivos de denúncia e status.

Se esses valores ficarem em constantes, cada ajuste vira deploy; se ficarem em tabela sem validação, um valor absurdo quebra o sistema.

## Decisão

### Tabela `parametro`

```text
parametro
  chave (pk, text)
  valor (text)
  tipo (INTEIRO, DECIMAL, TEXTO, BOOLEANO, DURACAO_MINUTOS, LISTA)
  descricao (text)
  valor_minimo, valor_maximo
  unidade (text)
  editavel (bool)
  versao (int)
  atualizado_em, atualizado_por
```

- `chave` é estável e referenciada no código por uma constante tipada (`Parametros.SESSAO_INATIVIDADE_MIN`), não por string solta.
- `valor` é sempre texto; o `tipo` define a conversão e a validação.
- `valor_minimo`/`valor_maximo` garantem faixa; valor fora da faixa é recusado.
- `editavel=false` para parâmetros que não devem ser alterados pela mediação (por exemplo, versão de algoritmo).
- Alteração grava `audit_log` com valor anterior, novo, autor, data e justificativa.

### Acesso tipado

```java
@Component
public class Parametros {

    private final ParametroRepository repository;
    private final Cache<String, String> cache;

    public int sessaoInatividadeMin() {
        return inteiro("sessao_inatividade_min", 30);
    }

    public Duration antecedenciaCancelamento() {
        return duracao("reserva_antecedencia_cancelamento_h", Duration.ofHours(24));
    }

    // ...
}
```

- Cache em memória (Caffeine) com TTL de 60 segundos e invalidação explícita na alteração.
- Se a chave não existe, usa-se o padrão **e registra alerta**; nunca se lança exceção em produção por parâmetro ausente.
- Se o valor é inválido (fora da faixa, tipo errado), usa-se o padrão e registra alerta; a alteração inválida é recusada na origem.
- Nenhum service lê `System.getenv` para regra de negócio; variável de ambiente é infraestrutura, parâmetro é produto.

### Parâmetros iniciais

| Chave | Padrão | Faixa | Origem |
|---|---|---|---|
| `sessao_inatividade_min` | 30 | 5–120 | RF002 |
| `bloqueio_tentativas` | 5 | 3–10 | RF002 |
| `bloqueio_duracao_min` | 15 | 5–60 | RF002 |
| `email_confirmacao_validade_h` | 24 | 1–72 | RF001 |
| `solicitacao_prazo_analise_horas_uteis` | 24 | 4–72 | RF006 |
| `solicitacao_validade_dias` | 30 | 7–90 | proposta |
| `solicitacao_max_abertas` | sem limite | 1–10 se aprovado | RF005 × RF009 |
| `horario_duracao_min_min` | 30 | 30–60 | RF004 |
| `horario_duracao_max_h` | 4 | 2–8 | RF004 |
| `horario_limite_diario` | proposta | 1–12 | RF004 |
| `raio_padrao_km` | 10 | 1–50 | RF001/RF007 |
| `raio_max_km` | 100 | 10–200 | proposta |
| `reserva_antecedencia_cancelamento_h` | 24 | 12–72 | RF009 |
| `reserva_ciencia_h` | 24 | 1–48 | RF008 |
| `conclusao_prazo_h` | 72 | 24–168 | RF012 |
| `avaliacao_prazo_dias` | 7 | 3–30 | RF013 |
| `chat_somente_leitura_dias` | 30 | 15–90 | RF011 |
| `denuncia_suspensao_qtd` | 3 | 2–10 | RF014 |
| `denuncia_suspensao_janela_dias` | 30 | 15–90 | RF014 |
| `documento_retencao_dias` | 90 | 30–365 | RF016 |
| `mensagem_max_caracteres` | 1.000 | 200–2.000 | RF011 |
| `avaliacao_comentario_max` | 300 | 100–1.000 | RF013 |
| `catalogo_pagina` | 10 | 10–50 | RF007 |
| `historico_pagina` | 10 | 10–50 | RF017 |

### Regra de não invalidação

Um parâmetro não pode ser reduzido a ponto de invalidar registros existentes. Exemplos:

- Reduzir `horario_duracao_max_h` não afeta horários já publicados com duração maior; a validação é aplicada apenas a novos.
- Aumentar `mensagem_max_caracteres` não afeta mensagens antigas.
- Reduzir `avaliacao_prazo_dias` não invalida avaliações já feitas.
- Alterar `reserva_antecedencia_cancelamento_h` não reclassifica cancelamentos passados.

Implementação: toda validação de parâmetro em operação nova recebe o **valor vigente**, nunca reinterpreta o histórico.

### Cadastros-base

| Tabela | Conteúdo | Regras |
|---|---|---|
| `especialidade` | tipos de apoio, com `regulamentada` e `ativo` | não excluir; desativar; não remover se em uso |
| `condicao_cronica` | lista editável | idem |
| `motivo_denuncia` | lista de motivos | idem |
| `motivo_cancelamento` | lista do RF009 | idem |
| `termo` / `termo_versao` | versões de termos e política | nova versão, nunca editar a antiga |
| `municipio` | dados do IBGE | sincronização periódica |

Regras:

- Desativar um item impede o uso em novos registros, mas não altera os existentes.
- Nenhum cadastro-base tem `DELETE` físico.
- Cada item tem `criado_em`, `atualizado_em`, `atualizado_por`.
- A lista de tipos de apoio é a mesma usada em RF001 (Apoiador), RF004 (agenda) e RF005 (solicitação).

### Termos e consentimento

- `termo_versao` guarda versão, texto (ou referência ao texto versionado) e data de vigência.
- `termo_aceite` guarda usuário, tipo (`TERMOS`, `POLITICA`, `SAUDE`, `CODIGO_CONDUTA`), versão, data/hora e IP hash.
- Nova versão exige novo aceite quando a mudança é material; o sistema informa no próximo login.
- O consentimento de saúde é **separado** e revogável (LGPD art. 11).

### Interface da mediação

- Tela de parâmetros lista chave, valor atual, faixa, descrição e última alteração.
- Alteração exige justificativa e confirmação; o valor anterior fica visível.
- Parâmetros críticos (prazos de conclusão, antecedência, retenção) podem exigir **dupla aprovação** — decisão pendente.
- Cadastros-base têm tela própria, com ativar/desativar e histórico.

### Testes

- Teste que verifica se toda chave usada no código existe na migration de seed.
- Teste que verifica se toda chave tem padrão e faixa.
- Teste que altera um parâmetro e verifica o efeito no caso de uso correspondente.
- Teste que um valor fora da faixa é recusado.

## Fluxo de usuário afetado

| Etapa | Parâmetros envolvidos |
|---|---|
| Cadastro | validade do link, termos, limites de texto |
| Login | sessão, tentativas, bloqueio |
| Agenda | duração, limite diário, raio |
| Solicitação | validade, limite de abertas, prazo de análise |
| Catálogo | raio, página |
| Reserva | ciência, antecedência de cancelamento |
| Conclusão | prazo de 72 h |
| Avaliação | prazo de 7 dias, tamanho do comentário |
| Chat | tamanho da mensagem, somente leitura |
| Denúncia | quantidade/janela de suspensão preventiva |
| Verificação | retenção de documento |
| Mediação | todos os anteriores + cadastros-base |

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Constantes no código | Exige deploy para cada ajuste; viola RNF010. |
| Variáveis de ambiente | Mudança exige restart e não tem auditoria nem faixa. |
| Tabela sem validação | Um valor absurdo quebra prazos e regras. |
| Feature flags externas | Complexidade e dependência sem ganho para valores de negócio. |
| Arquivo YAML editável em runtime | Sem auditoria, sem tipagem e sem controle de acesso. |

## Consequências

- Todo service precisa ler parâmetro, o que adiciona uma dependência e um cache.
- O cache de 60 s significa que uma alteração pode demorar até 1 minuto para valer; a invalidação explícita reduz isso.
- A mediação ganha poder de mudar comportamento; a auditoria e a faixa limitam o risco.
- Parâmetros sem uso ficam como dívida; a tela deve indicar os que estão em uso.
- A dupla aprovação, se aprovada, adiciona um estado de "alteração pendente".

## Critérios de aceite

- [ ] Alterar um parâmetro na mediação muda o comportamento sem deploy.
- [ ] Valor fora da faixa é recusado com mensagem clara.
- [ ] Toda alteração registra valor anterior, novo, autor, data e justificativa.
- [ ] Nenhuma constante de prazo/limite existe em service.
- [ ] Nenhum parâmetro ausente derruba a aplicação; usa padrão e alerta.
- [ ] Desativar especialidade não altera registros existentes.
- [ ] Nenhum cadastro-base é excluído fisicamente.
- [ ] Aceite de termo registra versão, data/hora e tipo.
- [ ] Consentimento de saúde é separado e revogável.
- [ ] Teste garante que toda chave usada no código está no seed.

## Decisões pendentes

> **DECISÃO PENDENTE 98:** aprovar a tabela de parâmetros e os valores padrão (seção 6 do PRD).
>
> **DECISÃO PENDENTE 99:** definir quem aprova mudança crítica e se há dupla aprovação.
>
> **DECISÃO PENDENTE 100:** confirmar se `solicitacao_max_abertas` existe (Decisão 13 do PRD).
>
> **DECISÃO PENDENTE 101:** definir o calendário de horas úteis usado no prazo de análise (Decisão 16).
>
> **DECISÃO PENDENTE 102:** confirmar a lista inicial de especialidades, condições crônicas e motivos de denúncia/cancelamento.
