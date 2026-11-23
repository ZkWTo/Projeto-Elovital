# ADR 0016: Rotinas agendadas, expiração e expurgo

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — RF012, RF017, RF016, RNF008
- **Relacionado:** [ADR 0011](0011-maquinas-de-estado-do-fluxo.md), [ADR 0013](0013-lgpd-e-dados-sensiveis.md), [ADR 0015](0015-notificacoes-assincronas.md), [ADR 0017](0017-armazenamento-de-documentos.md)

## Contexto

O PRD depende de ações que acontecem **sem usuário**:

- expirar solicitações abertas sem reserva até a data desejada (RF017);
- lembrar o Assistido em 24 h e 48 h;
- lembrar o Apoiador da ciência pendente em 24 h;
- concluir reservas com uma confirmação após 72 h (RF012, pendente de decisão);
- encerrar o prazo de avaliação em 7 dias (RF013);
- expurgar documentos após o prazo (RF016);
- limpar tentativas de login (90 dias), notificações (12 meses), idempotência (24 h);
- anonimizar contas após o prazo legal (RNF011);
- encerrar chat em somente leitura e arquivar (RF011).

O RF017 exige que o processo seja **idempotente**: rodar duas vezes seguidas não duplica efeitos. A fonte não define frequência, janela ou tratamento de falha (Decisão 30 do PRD).

Com múltiplas instâncias, dois schedulers rodariam a mesma rotina ao mesmo tempo. Com regra no banco (`pg_cron`), a regra ficaria fora do código Java, contrariando o [ADR 0006](0006-arquitetura-em-camadas.md).

## Decisão

### Scheduler na aplicação, com lock distribuído

- Rotinas são `@Scheduled` em classes de `application/rotina`, executando **casos de uso** (não SQL direto).
- Concorrência entre instâncias controlada por **ShedLock** com tabela `shedlock` no PostgreSQL:

```java
@Scheduled(cron = "0 */5 * * * *", zone = "America/Sao_Paulo")
@SchedulerLock(name = "expirarSolicitacoes", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
public void expirarSolicitacoes() {
    expirarSolicitacoesService.executar(LocalDate.now(relogio));
}
```

- `pg_cron` fica reservado para manutenção de banco (por exemplo, `VACUUM`/estatísticas), nunca para regra de negócio.
- O scheduler roda em **todas** as instâncias, mas o lock garante execução única. Isso evita um "nó especial" que precisa ser lembrado no deploy.

### Frequência e janela

| Rotina | Frequência | Janela/lote | Efeito |
|---|---|---|---|
| Expirar solicitações | a cada 5 min | 200 por lote | `ABERTA` → `EXPIRADA` quando `expira_em < agora` |
| Lembrete 24 h/48 h | a cada 15 min | 200 por lote | notifica Assistido sem ação |
| Ciência pendente 24 h | a cada 15 min | 200 | notifica Apoiador e Mediação; **não** cancela |
| Conclusão 72 h | a cada 5 min | 200 | `AGUARDANDO_CONFIRMACAO` → desfecho |
| Fechar janela de avaliação | a cada 1 h | 500 | marca avaliação indisponível |
| Expurgo de documentos | diário, 03:00 | 100 | remove arquivo e marca `EXPURGADO` |
| Anonimização de contas | diário, 03:30 | 50 | anonimiza após prazo |
| Limpeza de tentativas | diário, 04:00 | 5.000 | remove > 90 dias |
| Limpeza de notificações | diário, 04:10 | 5.000 | remove > 12 meses |
| Limpeza de idempotência | horária | 5.000 | remove > 24 h |
| Arquivar chat | diário, 04:20 | 200 | somente leitura → arquivado |
| Métricas do painel | a cada 15 min | — | agrega indicadores |

Horários em `America/Sao_Paulo`; a rotina usa `Clock` injetável para ser testável.

### Idempotência

Três mecanismos, em ordem:

1. **Transição condicional:** `UPDATE ... WHERE status = 'ABERTA' AND expira_em < :agora`. Só afeta o que ainda está no estado esperado.
2. **Marca de processamento:** colunas `lembrete_24h_em`, `lembrete_48h_em`, `concluida_por_rotina_em` evitam reenvio.
3. **Chave de evento:** notificação com `dedupe_key` única (`solicitacao:{id}:lembrete24h`).

```sql
update elovital.solicitacao
   set status = 'EXPIRADA', atualizado_em = now()
 where status = 'ABERTA'
   and expira_em < now()
   and id in (:ids);
```

- Rodar duas vezes não muda o resultado da segunda.
- Nenhuma rotina usa `SELECT` seguido de `UPDATE` sem condição de estado.
- Processamento em lotes evita transação longa e lock global.

### Registro de execução

Tabela `job_execucao`:

```text
id, nome, iniciado_em, finalizado_em, status (OK/FALHA/PARCIAL),
linhas_afetadas, erro_resumido, correlacao_id
```

- Toda execução grava um registro, inclusive quando não há nada a fazer.
- Falha gera log técnico, métrica e alerta; não interrompe as demais rotinas.
- `erro_resumido` não contém dado pessoal.
- Retenção de 90 dias.

### Tratamento de falha

| Situação | Comportamento |
|---|---|
| Exceção em um item do lote | registra o erro, continua os demais, marca execução como `PARCIAL` |
| Banco indisponível | a rotina falha, o lock expira e a próxima execução tenta de novo; alerta |
| Duas instâncias | ShedLock impede duplicidade; a segunda não executa |
| Execução anterior ainda rodando | `lockAtMostFor` expira e a próxima assume; a idempotência evita duplicidade |
| Provedor de e-mail fora | notificação fica na central; outbox retenta ([ADR 0015](0015-notificacoes-assincronas.md)) |
| Storage fora | expurgo fica pendente e retenta no dia seguinte |

### Conclusão das 72 h (ponto crítico)

A rotina de conclusão **depende da Decisão 23 do PRD**:

- **Opção A (fonte literal):** concluir com `CONCLUIDA_UMA_CONFIRMACAO` e registrar "confirmado por uma das partes". A rotina cria o registro, libera avaliação e sinaliza a mediação.
- **Opção B (regra das duas confirmações):** expirar a confirmação, abrir ocorrência e deixar a mediação decidir.

Enquanto não houver decisão, a rotina **não** deve ser implementada; o estado fica em `AGUARDANDO_CONFIRMACAO` e a mediação é notificada. Implementar por suposição viola a regra de precedência do PRD.

### Expiração de solicitação

- A data de expiração é calculada na criação (`solicitacao_validade_dias`) e ajustável pela mediação (RF006).
- Ao expirar, notifica o Assistido com opção de reabrir com nova data ou ampliar o raio (RF017).
- Solicitação expirada não pode ser reservada (critério de aceite).
- A transição é `ABERTA → EXPIRADA`; `AGUARDANDO_AJUSTE` também pode expirar.

### Expurgo e anonimização

- Documentos: a rotina lista `expurgo_em <= hoje`, remove do Storage e marca `EXPURGADO`, preservando resultado e hash ([ADR 0017](0017-armazenamento-de-documentos.md)).
- Contas: após o prazo, anonimiza CPF, telefone, endereço, documentos e nome; mantém id, status e auditoria.
- Chat: 30 dias após conclusão/cancelamento em somente leitura; depois conteúdo expurgado e metadados mantidos.
- Todo expurgo grava `expurgo_log` com tipo, quantidade e data.
- Backups não são reescritos; toda restauração reaplica o expurgo antes de voltar ao ar.

### Observabilidade

- Métricas por rotina: duração, linhas afetadas, falhas.
- Alerta se uma rotina não executa há 2× o intervalo esperado.
- Alerta se `job_execucao` acusa falha duas vezes seguidas.
- Alerta se o expurgo não roda (indício de lock preso).

## Fluxo de usuário afetado

1. **Solicitação (RF005/RF017):** expiração e lembretes.
2. **Reserva (RF008):** ciência pendente, sem cancelamento automático.
3. **Conclusão (RF012):** desfecho após 72 h.
4. **Avaliação (RF013):** fechamento em 7 dias.
5. **Chat (RF011):** somente leitura em 30 dias e arquivamento.
6. **Verificação (RF016):** expurgo de documento.
7. **Conta (RF003/RNF011):** anonimização.
8. **Mediação (RF015):** indicadores e alertas de prazo.

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| `pg_cron` para regra de negócio | Regra fora do Java, invisível ao código e aos testes. |
| Um único nó "worker" | Ponto único de falha e configuração especial de deploy. |
| Fila externa com agendador | Infraestrutura extra; ShedLock resolve. |
| Spring Batch | Peso e complexidade para lotes simples. |
| Rotina sem lock | Duplicidade garantida com mais de uma instância. |

## Consequências

- A tabela `shedlock` e `job_execucao` precisam de manutenção e índice.
- O scheduler na aplicação compete com o tráfego; lotes pequenos e horários de menor uso mitigam.
- A idempotência exige disciplina: toda rotina precisa de condição de estado e marca.
- A decisão das 72 h bloqueia parte da implementação.
- Rotinas que chamam Storage dependem de disponibilidade externa e precisam de retentativa.

## Critérios de aceite

- [ ] Rodar a rotina duas vezes seguidas não duplica efeitos.
- [ ] Solicitação expirada não pode ser reservada.
- [ ] Lembrete 24 h não é reenviado depois de enviado.
- [ ] Duas instâncias não executam a mesma rotina ao mesmo tempo.
- [ ] Falha em um item não interrompe o lote inteiro.
- [ ] Toda execução gera linha em `job_execucao`.
- [ ] Expurgo de documento registra em `expurgo_log` e remove o arquivo.
- [ ] Anonimização mantém o id e a auditoria.
- [ ] Alerta dispara quando uma rotina não executa no intervalo esperado.
- [ ] Nenhuma rotina contém `LocalDate.now()`/`Instant.now()` direto; usa `Relogio`.

## Decisões pendentes

> **DECISÃO PENDENTE 83 (crítica):** desfecho das 72 h (Decisão 23 do PRD) — bloqueia a rotina de conclusão.
>
> **DECISÃO PENDENTE 84:** confirmar frequência e janela de cada rotina (a tabela é proposta).
>
> **DECISÃO PENDENTE 85:** confirmar expurgo de documentos em 90 dias e de contas em 5 anos com o jurídico.
>
> **DECISÃO PENDENTE 86:** definir se a rotina de expiração deve reabrir automaticamente a solicitação ou apenas notificar (RF017 sugere notificar e oferecer reabertura).
>
> **DECISÃO PENDENTE 87:** definir o calendário de "horas úteis" usado no prazo de análise (Decisão 16 do PRD), que afeta o alerta de prazo e não a rotina de expiração.
