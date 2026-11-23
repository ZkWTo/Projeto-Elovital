# ADR 0015: Notificações assíncronas e central interna

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — RF010, RNF004
- **Relacionado:** [ADR 0010](0010-entrega-web-e-api.md), [ADR 0014](0014-auditoria-e-acesso-a-dados.md), [ADR 0016](0016-rotinas-agendadas-e-expiracao.md), [ADR 0019](0019-parametros-e-cadastros-base.md)

## Contexto

O RF010 define eventos e canais:

| Papel | Eventos |
|---|---|
| Apoiador | novo horário reservado, reserva cancelada/remarcada, nova mensagem, confirmação de ciência, lembrete de atendimento |
| Assistido | solicitação aprovada/devolvida/recusada, horário reservado, cancelamento, nova mensagem, confirmação de conclusão pendente, solicitação prestes a expirar |
| Mediação | nova conta, nova solicitação, prazo excedido, nova denúncia, ocorrência, cancelamento tardio |

Canais: central interna (essencial), e-mail (importante), push (desejável, fora da v1). Regras: reservas e cancelamentos notificados imediatamente; o usuário pode desativar categorias, **exceto** avisos obrigatórios de segurança, termos e mediação; notificações de novos horários podem ser agrupadas; cada notificação tem lida/não lida; falha de e-mail não pode impedir a operação principal.

O RNF004 exige entrega em até 1 minuto para reserva e cancelamento.

O risco técnico é acoplar o envio ao fluxo principal: se o provedor de e-mail cair, a reserva falha — o que viola o RF010 e o RNF006.

## Decisão

### A central interna é a fonte de verdade

- Toda notificação nasce como linha em `notificacao` na **mesma transação** da operação que a originou.
- O e-mail é uma **cópia de entrega**, não a notificação em si.
- Se o e-mail falhar, a central já tem o registro e a operação principal está concluída.
- O contador de não lidas vem da própria tabela.

```text
notificacao
  id, usuario_id, categoria, tipo, titulo, corpo (texto puro),
  entidade, entidade_id, obrigatoria (bool), lida_em, criada_em,
  enviar_email (bool), email_enviado_em, tentativas_email
```

### Outbox transacional

Para que a notificação e o e-mail não se percam nem sejam enviados antes do commit:

```text
outbox_evento
  id, tipo, payload jsonb, criado_em, processado_em, tentativas, proxima_tentativa_em, erro
```

Fluxo:

1. Caso de uso grava `notificacao` + `outbox_evento` na transação.
2. Commit.
3. Um worker (`@Scheduled` com ShedLock) lê o outbox pendente e envia o e-mail.
4. Sucesso marca `processado_em`; falha incrementa `tentativas` e agenda retry com backoff.
5. Após o limite, marca como falho e alerta; a central interna continua funcionando.

Regras:

- O worker é o **único** que chama o provedor de e-mail.
- Nenhum envio de e-mail dentro de transação de negócio.
- O payload do outbox não carrega dado sensível além do necessário (e-mail, nome, tipo do evento, id).
- O outbox é limpo após 30 dias de processamento.

### Categorias e preferências

| Categoria | Exemplos | Pode desativar? |
|---|---|---|
| `RESERVA` | reserva criada, cancelada, remarcada | Não (obrigatória) |
| `CONFIRMACAO` | ciência pendente, confirmação de conclusão | Não |
| `MENSAGEM` | nova mensagem de chat | Sim |
| `SOLICITACAO` | aprovada, devolvida, recusada, expirando | Não |
| `SEGURANCA` | login suspeito, sanção, contestação | Não |
| `TERMOS` | nova versão de termos/política | Não |
| `AGENDA` | novo horário publicado (pode agrupar) | Sim |
| `MEDIACAO` | fila de trabalho para mediadores | Não |

- Preferências em `preferencia_notificacao (usuario_id, categoria, email_ativo, central_ativo)`.
- A obrigatoriedade é uma propriedade da categoria, não da preferência: o serviço ignora a preferência quando `obrigatoria=true`.
- Categoria desativada não gera linha nem e-mail (exceto obrigatória, que sempre gera).

### Entrega em tempo real (SSE)

- O frontend assina `/eventos/notificacoes` (SSE) e recebe `{tipo, contadorNaoLidas, titulo}`.
- O evento é publicado **após o commit**, por `@TransactionalEventListener(AFTER_COMMIT)`.
- Se o usuário estiver offline, a central mostra ao voltar; o SSE é conveniência.
- SSE não substitui a linha no banco.
- Fallback de polling a cada 20–30 s (ver [ADR 0010](0010-entrega-web-e-api.md)).

### Conteúdo das mensagens

- Texto puro, em pt-BR, curto e sem dado sensível.
- E-mail **não** contém: condição de saúde, descrição da solicitação, endereço exato, CPF, telefone, conteúdo de chat.
- E-mail pode conter: primeiro nome, tipo do evento, data/hora do horário, link para a plataforma.
- Nenhum link de e-mail contém token de sessão; links de ação usam token de uso único quando necessário.
- Assunto e remetente fixos por categoria, para não parecer phishing.
- Rodapé com aviso de que a plataforma não substitui o SAMU (192) quando o evento é de solicitação.

### Templates

- Templates Thymeleaf de e-mail versionados no repositório (`templates/email/`).
- Um layout base e um fragmento por evento.
- Texto alternativo em texto puro para clientes que não renderizam HTML.
- Teste de snapshot do HTML gerado.
- Versionamento: mudança de texto não exige migration; mudança de variável exige teste.

### Retentativa e falha

| Situação | Comportamento |
|---|---|
| Provedor indisponível | retry com backoff exponencial (1 min, 5 min, 15 min, 1 h), máximo 5 tentativas |
| Bounce permanente | marca o e-mail como inválido, alerta a mediação, não retenta |
| Bounce temporário | retenta |
| Limite de envio atingido | enfileira e reduz taxa; alerta |
| Falha após 5 tentativas | `outbox_evento.erro` preenchido, métrica e alerta; central permanece |

- A taxa de envio é limitada por provedor e por usuário (evita spam).
- Notificações de `AGENDA` podem ser agrupadas em uma janela de 10 minutos; `RESERVA` e `CANCELAMENTO` nunca são agrupados.
- Deduplicação: mesma entidade + mesmo tipo + mesma janela de 1 minuto gera uma notificação só.

### 1 minuto (RNF004)

- O worker roda a cada 15 segundos com lotes pequenos.
- Reserva e cancelamento têm prioridade na fila (`prioridade` no outbox).
- O commit já cria a notificação; o atraso máximo é o intervalo do worker + envio.
- Métrica `notificacao_atraso_segundos` com alerta se p95 > 60 s.

## Fluxo de usuário afetado

| Evento do fluxo | Notificação |
|---|---|
| Cadastro enviado | Assistido: "Cadastro em análise"; Mediação: nova conta |
| Conta aprovada/recusada | Assistido/Apoiador |
| Solicitação criada | Mediação: nova solicitação |
| Solicitação aprovada/devolvida/recusada | Assistido, com link |
| Solicitação prestes a expirar | Assistido (rotina, [ADR 0016](0016-rotinas-agendadas-e-expiracao.md)) |
| Reserva criada | Apoiador e Assistido; Apoiador recebe pedido de ciência |
| Ciência pendente há 24 h | Apoiador + Mediação |
| Nova mensagem | A outra parte |
| Cancelamento | A contraparte + Mediação se tardio |
| Confirmação pendente | A parte que ainda não confirmou |
| Conclusão | Ambas as partes, com convite à avaliação |
| Avaliação 1–2 com comentário | Mediação: sinalização |
| Denúncia | Mediação: nova denúncia + protocolo ao denunciante |
| Sanção | Usuário sancionado, com orientação de contestação |
| Expiração | Assistido |

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Enviar e-mail síncrono no service | Provedor lento derruba reserva; viola RNF006. |
| Fila externa (RabbitMQ/Kafka) | Infraestrutura extra para o volume inicial; outbox no banco resolve. |
| Notificar só por e-mail | Sem central, o usuário perde histórico e o requisito exige central. |
| Push na v1 | Desejável, não essencial; aumenta custo e complexidade de permissão. |
| Template livre digitado pela mediação | Risco de phishing e de dado sensível; templates versionados são mais seguros. |

## Consequências

- O outbox adiciona uma tabela e um worker; em troca, a operação principal nunca depende do provedor.
- A central interna precisa de paginação, contador e marcação de lida.
- A obrigatoriedade de categorias precisa ser clara na interface para não frustrar o usuário.
- A taxa de envio precisa ser monitorada para não ser classificada como spam.
- O provedor de e-mail é uma decisão pendente que afeta reputação de domínio (SPF, DKIM, DMARC).

## Critérios de aceite

- [ ] Falha do provedor de e-mail não impede reserva, cancelamento ou qualquer operação principal.
- [ ] Notificação de reserva/cancelamento é gerada na central em até 1 minuto.
- [ ] Categoria obrigatória não pode ser desativada pelo usuário.
- [ ] Categoria desativada não gera linha nem e-mail (exceto obrigatória).
- [ ] Nenhum e-mail contém dado de saúde, endereço exato, CPF ou telefone.
- [ ] Reenvio após falha não duplica notificação (deduplicação por entidade/tipo/janela).
- [ ] Bounce permanente é registrado e não retenta indefinidamente.
- [ ] Central mostra não lidas e permite marcar como lida.
- [ ] Nenhum envio de e-mail ocorre dentro de transação.
- [ ] Templates de e-mail têm versão em texto puro.

## Decisões pendentes

> **DECISÃO PENDENTE 78:** escolher o provedor de e-mail transacional e configurar SPF/DKIM/DMARC.
>
> **DECISÃO PENDENTE 79:** confirmar push fora da v1 (Decisão 22 do PRD).
>
> **DECISÃO PENDENTE 80:** definir retenção de notificações (proposta: 12 meses).
>
> **DECISÃO PENDENTE 81:** confirmar a janela de agrupamento de notificações de agenda (10 min é sugestão).
>
> **DECISÃO PENDENTE 82:** definir se a mediação recebe notificação por e-mail ou apenas na central.
