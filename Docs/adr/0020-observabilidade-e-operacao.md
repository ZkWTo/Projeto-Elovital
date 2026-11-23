# ADR 0020: Observabilidade e operação sem dados pessoais

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — RNF004, RNF005, RNF012, RNF011
- **Relacionado:** [ADR 0013](0013-lgpd-e-dados-sensiveis.md), [ADR 0014](0014-auditoria-e-acesso-a-dados.md), [ADR 0021](0021-testes-qualidade-e-ci.md)

## Contexto

O sistema precisa ser operável 24 h, com disponibilidade de 99% (RNF005), p95 ≤ 3 s (RNF004) e trilha de auditoria (RNF012). Ao mesmo tempo, trata dados de saúde, CPF, endereço e conteúdo de chat.

A tensão é direta: quanto mais detalhe no log, mais fácil diagnosticar — e maior o risco de vazar dado pessoal para uma ferramenta de terceiros. O RS001 e a LGPD tornam o vazamento por log um incidente, não um detalhe.

Além disso, auditoria de negócio ([ADR 0014](0014-auditoria-e-acesso-a-dados.md)) **não** é log técnico: misturar os dois cria prova frágil.

## Decisão

### Separação de sinais

| Sinal | Ferramenta | Retenção | Conteúdo |
|---|---|---|---|
| Log técnico | saída JSON + agregador | 90 dias | evento, nível, correlação, duração, erro |
| Métricas | Micrometer → Prometheus | 13 meses (agregado) | contadores, histogramas, sem rótulo pessoal |
| Rastreamento | OpenTelemetry | 7 dias | spans, sem atributo pessoal |
| Auditoria de negócio | tabela `audit_log` | 5 anos | decisão, ator, motivo |
| Erros | Sentry/GlitchTip | 30 dias | stack trace com scrubbing |

Nenhum sinal carrega CPF, telefone, endereço, condição de saúde ou conteúdo de chat.

### Logs estruturados

```json
{
  "timestamp": "2026-11-23T14:02:11.412Z",
  "level": "INFO",
  "logger": "br.com.elovital.reservas.ReservarHorarioService",
  "message": "reserva_criada",
  "correlacaoId": "8f2c...",
  "usuarioId": "uuid",
  "papel": "ASSISTIDO",
  "entidade": "reserva",
  "entidadeId": "uuid",
  "duracaoMs": 37,
  "resultado": "OK"
}
```

Regras:

- Formato JSON em todos os ambientes, inclusive local (facilita teste).
- Nível `INFO` para eventos de negócio (reserva criada, conta aprovada), sem conteúdo.
- `WARN` para regra recusada (conflito, limite), `ERROR` para falha inesperada.
- **Nunca** logar: corpo de requisição, formulário, texto de usuário, CPF, telefone, e-mail completo, endereço, documento, token, senha, conteúdo de chat.
- `usuarioId` (UUID) é permitido; e-mail não.
- Erros de validação não geram `ERROR`; geram `WARN` com código.

### Mascaramento na origem

- Um `LogSanitizer` central mascara padrões de CPF, telefone, e-mail e cartão em qualquer mensagem de exceção antes de logar.
- Exceções de biblioteca que possam conter SQL com parâmetro são logadas sem a mensagem original ou com o statement parametrizado.
- O `ProblemDetail` devolvido ao usuário não repete o valor do campo; diz o que corrigir (RNF007).
- Em desenvolvimento, o mascaramento permanece ativo; não existe "modo debug que mostra tudo" em produção.

### Correlação

- Filtro gera `correlacaoId` por requisição, coloca no MDC e devolve no header `X-Correlacao-Id`.
- O id aparece na tela de erro ("informe este código ao suporte") e no `audit_log`.
- Jobs e eventos assíncronos propagam o id.
- O id não contém dado pessoal.

### Métricas

| Métrica | Tipo | Uso |
|---|---|---|
| `http_server_requests_seconds` | histograma | p95 por rota (RNF004) |
| `reserva_conflito_total` | contador | disputa de horário |
| `reserva_duracao_seconds` | histograma | saúde da transação |
| `notificacao_atraso_seconds` | histograma | RNF004 (≤ 60 s) |
| `outbox_pendente` | gauge | fila de e-mail |
| `job_falhas_total` | contador | rotinas |
| `login_falhas_total` | contador | segurança |
| `documento_expurgo_pendente` | gauge | LGPD |
| `db_pool_ativas` | gauge | saturação de conexão |

- Rótulos nunca contêm id de usuário, e-mail ou CPF (alta cardinalidade e dado pessoal).
- Rótulos permitidos: rota, método, status, resultado, tipo de evento.

### Rastreamento

- OpenTelemetry com propagação W3C.
- Spans para controller → service → repositório; span de banco com statement **sem parâmetro**.
- Nenhum atributo com dado pessoal; `usuarioId` só se a política permitir (recomendação: não).
- Amostragem de 10% em produção, 100% em erro.

### Saúde e alertas

`/actuator/health` com:

- `db` (conexão e validação),
- `storage` (opcional, não deve derrubar readiness),
- `mail` (informativo),
- `diskSpace`.

Alertas mínimos:

| Alerta | Limite |
|---|---|
| Erro 5xx | > 1% por 5 min |
| p95 de rota crítica | > 3 s por 10 min |
| Pool de conexões | > 80% por 5 min |
| Fila de outbox | > 500 por 10 min |
| Rotina parada | 2× o intervalo |
| Falha de backup | imediato |
| Expurgo pendente | > 100 por 24 h |
| Login falhas | pico anômalo |
| Disco | > 80% |

### Erros e ferramenta externa

- Sentry/GlitchTip com `beforeSend` que remove `request.data`, cookies, headers de autenticação e qualquer campo sensível.
- O `correlacaoId` liga o erro ao log.
- Nenhum payload de formulário é enviado.
- Em caso de dúvida, envia-se menos.

### Operação

- Deploy com health check e rollback.
- Migrations rodam antes da aplicação ([ADR 0007](0007-persistencia-postgresql-jpa-flyway.md)).
- Janela de manutenção anunciada quando necessário (RNF005).
- Runbook para: banco indisponível, Storage indisponível, e-mail falhando, expurgo travado, pico de conflito de reserva.
- Acesso a logs e métricas é restrito e auditado quando envolve incidente.

## Fluxo de usuário afetado

| Etapa | Sinal observado |
|---|---|
| Cadastro | taxa de erro por campo, tempo de resposta |
| Login | falhas, bloqueios, pico |
| Verificação | tempo de análise, documentos pendentes |
| Agenda | conflitos, duração |
| Solicitação | fila de análise, tempo até aprovação |
| Catálogo | p95, resultados vazios |
| Reserva | conflito, duração da transação |
| Chat | atraso de entrega |
| Conclusão | conclusões por rotina vs. dupla confirmação |
| Denúncia | tempo de tratamento |
| Expurgo | pendências |

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Log de texto livre | Difícil de consultar e propenso a dado pessoal. |
| Logar payload para depurar | Viola LGPD e RS001. |
| Só auditoria, sem log técnico | Não diagnostica erro nem desempenho. |
| Rótulo de métrica com usuário | Cardinalidade explosiva e dado pessoal. |
| APM com captura de request completo | Vaza formulário e conteúdo. |

## Consequências

- Depurar exige correlação e contexto de negócio, não o payload.
- O mascaramento pode esconder informação útil; o código de erro e o id compensam.
- Ferramentas externas exigem DPA e configuração de scrubbing.
- A retenção de logs (90 dias) precisa ser configurada no provedor, não só no código.

## Critérios de aceite

- [ ] Nenhum log contém CPF, telefone, e-mail completo, endereço, documento ou conteúdo de chat.
- [ ] Todo erro tem `correlacaoId` e a tela mostra o código.
- [ ] Rotas críticas têm métrica de p95 e alerta em 3 s.
- [ ] Notificação tem métrica de atraso e alerta em 60 s.
- [ ] Nenhum rótulo de métrica contém dado pessoal.
- [ ] Sentry não recebe corpo de requisição nem cookie.
- [ ] Health check distingue liveness de readiness.
- [ ] Runbook cobre banco, Storage, e-mail e expurgo.
- [ ] Log de desenvolvimento também mascara.
- [ ] Auditoria de negócio não depende do log.

## Decisões pendentes

> **DECISÃO PENDENTE 103:** escolher agregador de logs, APM e ferramenta de erro (com DPA e região).
>
> **DECISÃO PENDENTE 104:** confirmar retenção de 90 dias para log e 5 anos para auditoria.
>
> **DECISÃO PENDENTE 105:** definir se `usuarioId` pode aparecer em span de rastreamento.
>
> **DECISÃO PENDENTE 106:** definir orçamento e limites de ingestão de logs/métricas.
