# ADR 0011: Máquinas de estado do fluxo

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — seção 3 (máquinas de estado) e RF001–RF017
- **Relacionado:** [ADR 0006](0006-arquitetura-em-camadas.md), [ADR 0012](0012-reserva-atomica-e-concorrencia.md), [ADR 0014](0014-auditoria-e-acesso-a-dados.md), [ADR 0016](0016-rotinas-agendadas-e-expiracao.md)

## Contexto

Quase todo requisito do EloVital é uma transição de estado com condições e efeitos:

- conta: aprovar, recusar, suspender, reativar, desativar;
- verificação: enviar, aprovar, reprovar, expurgar;
- solicitação: analisar, aprovar, devolver, recusar, reservar, cancelar, expirar, concluir;
- horário: publicar, reservar, realizar, liberar, bloquear;
- reserva: ciência, cancelamento por antecedência, confirmação dupla, falta;
- denúncia, sanção e contestação.

O PRD já aponta contradições e lacunas (seção 0.2). Se cada tela ou service implementar a sua própria ideia de "quando pode", o sistema passa a ter comportamentos divergentes — e o requisito de auditoria (RNF012) vira ficção.

## Decisão

### Estados oficiais

Os enums ficam em `domain` e são gravados como string no banco, com `CHECK`. Os rótulos de tela são apenas tradução.

```java
public enum StatusConta {
    EM_ANALISE, ATIVA, SUSPENSA, BLOQUEADA, DESATIVADA
}

public enum StatusSolicitacao {
    EM_ANALISE, AGUARDANDO_AJUSTE, ABERTA, RESERVADA,
    AGUARDANDO_CONFIRMACAO, CONCLUIDA, CONCLUIDA_UMA_CONFIRMACAO,
    CANCELADA, EXPIRADA, RECUSADA
}

public enum StatusHorario {
    DISPONIVEL, RESERVADO, REALIZADO, LIBERADO, BLOQUEADO
}

public enum StatusReserva {
    CIENCIA_PENDENTE, ATIVA, AGUARDANDO_CONFIRMACAO,
    CONCLUIDA, CONCLUIDA_UMA_CONFIRMACAO,
    CANCELADA, EM_ANALISE_MEDIACAO, FALTA_REGISTRADA, REMARCADA
}

public enum StatusDocumento {
    PENDENTE, EM_ANALISE, APROVADO, REPROVADO, EXPURGADO
}

public enum StatusDenuncia {
    EM_ANALISE, PROCEDENTE, IMPROCEDENTE, ARQUIVADA
}

public enum StatusSancao {
    ATIVA, CUMPRIDA, REVOGADA
}

public enum StatusContestacao {
    ABERTA, EM_ANALISE, DEFERIDA, INDEFERIDA
}
```

### Transições explícitas, não genéricas

Cada agregado expõe métodos de transição com nome de negócio, e o método:

1. valida o estado de origem;
2. valida o ator e o motivo quando exigidos;
3. aplica o novo estado;
4. registra a transição no histórico do agregado;
5. publica evento de domínio.

```java
public void aprovar(UUID mediadorId, Prioridade prioridadeFinal, String observacao) {
    exigirEstado(EM_ANALISE, "aprovar");
    exigirPapelMediador(mediadorId);
    this.status = ABERTA;
    this.prioridadeFinal = prioridadeFinal;
    this.abertaEm = relogio.agora();
    registrarTransicao(ABERTA, mediadorId, observacao);
}
```

Não se usa uma máquina de estados genérica (`StateMachine<S,A>`) como única fonte de regra: ela tende a esconder as condições e os efeitos. A tabela de transições abaixo é a **especificação**; os métodos são a **implementação**; um teste de contrato percorre a tabela e verifica que transições proibidas são recusadas.

### Conta

| De | Ação | Para | Quem | Condição | Efeitos |
|---|---|---|---|---|---|
| EM_ANALISE | aprovar | ATIVA | mediação | identidade e documentos conferidos | auditoria, notificação, habilita agenda/solicitação |
| EM_ANALISE | recusar | BLOQUEADA¹ | mediação | justificativa obrigatória | auditoria, notificação com orientação de recurso |
| ATIVA | suspender | SUSPENSA | mediação | motivo e prazo | agenda oculta, horários futuros bloqueados, sessões invalidadas |
| SUSPENSA | reativar | ATIVA | mediação | denúncia improcedente ou prazo cumprido | auditoria, notificação, agenda volta se válida |
| ATIVA/SUSPENSA | bloquear | BLOQUEADA | mediação | decisão definitiva | sessões invalidadas, conteúdo oculto, auditoria |
| ATIVA | desativar | DESATIVADA | próprio usuário | sem solicitação/reserva em andamento | agenda removida, login bloqueado |
| BLOQUEADA | reativar | ATIVA | mediação | contestação deferida | decisão pendente 6 |
| DESATIVADA | anonimizar | DESATIVADA² | rotina | prazo legal | CPF, telefone, endereço e documentos zerados |

¹ O status da conta recusada não está definido na fonte (Decisão 5 do PRD). A tabela assume `BLOQUEADA` até decisão.
² `anonimizado` é um atributo de expurgo, não um sexto status de produto (Decisão do PRD 9.4).

Regras:

- Toda transição de conta grava ator, motivo, data/hora e estado anterior em `audit_log`.
- Suspensão preventiva por denúncias (RF014) é a única transição automática; ainda assim, gera auditoria e notificação.
- `is_active`/`enabled` do Spring Security **não** substitui `status`.

### Verificação de identidade

| De | Ação | Para | Quem | Condição | Efeitos |
|---|---|---|---|---|---|
| — | enviar documento | PENDENTE | usuário | arquivo válido | hash, storage, notificação à mediação |
| PENDENTE | iniciar análise | EM_ANALISE | mediação | — | — |
| EM_ANALISE | aprovar | APROVADO | mediação | documento e selfie compatíveis; registro conferido quando exigido | selo "Verificado", auditoria, notificação |
| EM_ANALISE | reprovar | REPROVADO | mediação | justificativa obrigatória | notificação, permite reenvio (novo registro) |
| APROVADO/REPROVADO | expurgar | EXPURGADO | rotina | prazo `documento_retencao_dias` | remove arquivo; preserva resultado, tipo e hash |

Regras:

- O resultado da verificação é preservado após o expurgo do arquivo (Decisão 29 do PRD).
- Especialidade regulamentada sem registro conferido não fica ativa (RF016).
- Nenhuma URL pública permanente de documento.

### Solicitação

| De | Ação | Para | Quem | Condição | Efeitos |
|---|---|---|---|---|---|
| — | criar | EM_ANALISE | Assistido/familiar | conta ativa e verificada; texto sem bloqueio | expiração calculada, notifica mediação |
| EM_ANALISE | aprovar | ABERTA | mediação | prioridade final definida | visível no catálogo de solicitações, notificação |
| EM_ANALISE | solicitar ajuste | AGUARDANDO_AJUSTE | mediação | justificativa obrigatória | notificação com orientação |
| EM_ANALISE | recusar | RECUSADA | mediação | justificativa obrigatória | notificação, direito a uma contestação |
| EM_ANALISE | cancelar | CANCELADA | Assistido | — | histórico preservado |
| AGUARDANDO_AJUSTE | reenviar | EM_ANALISE | Assistido | texto válido | nova entrada na fila, notificação |
| AGUARDANDO_AJUSTE | cancelar | CANCELADA | Assistido | — | — |
| ABERTA | reservar | RESERVADA | Assistido | horário disponível e compatível | cria reserva ([ADR 0012](0012-reserva-atomica-e-concorrencia.md)) |
| ABERTA | alterar relevantemente | EM_ANALISE | Assistido | mudança de tipo, local ou prioridade | revalidação pela mediação |
| ABERTA | cancelar | CANCELADA | Assistido | — | notificação |
| ABERTA | expirar | EXPIRADA | rotina | `expira_em` vencido | notificação, não pode mais ser reservada |
| RESERVADA | registrar realização | AGUARDANDO_CONFIRMACAO | parte da reserva | reserva ativa | notifica a outra parte |
| RESERVADA | cancelar ≥ 24 h | ABERTA | parte | antecedência | horário volta a DISPONIVEL |
| RESERVADA | cancelar < 24 h | RESERVADA³ | parte | motivo/justificativa | reserva vai para `EM_ANALISE_MEDIACAO`; horário fica BLOQUEADO até decisão |
| RESERVADA | cancelar | CANCELADA | mediação | ocorrência | horário conforme decisão |
| AGUARDANDO_CONFIRMACAO | segunda confirmação | CONCLUIDA | parte | prazo de 72 h | libera avaliação, contadores |
| AGUARDANDO_CONFIRMACAO | 72 h sem resposta | CONCLUIDA_UMA_CONFIRMACAO³ | rotina | prazo vencido | avaliação liberada, sinalização |
| AGUARDANDO_CONFIRMACAO | discordância | AGUARDANDO_CONFIRMACAO³ | parte | registro de ocorrência | reserva vai para `EM_ANALISE_MEDIACAO`; mediação decide |

³ `EM_ANALISE_MEDIACAO` é um estado operacional da **reserva**; a solicitação permanece `RESERVADA` enquanto a mediação decide. A separação evita inventar status de solicitação que a fonte não lista.

Regras:

- Estados terminais (`CONCLUIDA`, `CONCLUIDA_UMA_CONFIRMACAO`, `CANCELADA`, `EXPIRADA`, `RECUSADA`) não podem ser editados.
- A solicitação só pode ter **uma** reserva ativa (RF008).
- "Em andamento" (RF009) é tratado como sinônimo de `RESERVADA` até decisão do PRD.
- A conclusão com uma confirmação depende da Decisão 23 do PRD; implementada como estado próprio para não confundir indicadores.

### Horário

| De | Ação | Para | Quem | Condição | Efeitos |
|---|---|---|---|---|---|
| — | publicar | DISPONIVEL | Apoiador | conta ativa/verificada, data futura, sem conflito | entra no catálogo |
| DISPONIVEL | reservar | RESERVADO | sistema | reserva atômica | sai do catálogo |
| RESERVADO | cancelar ≥ 24 h | DISPONIVEL | sistema | antecedência | volta ao catálogo |
| RESERVADO | cancelar < 24 h | BLOQUEADO | sistema | — | aguarda mediação |
| RESERVADO | registrar realização | REALIZADO | sistema | conclusão | histórico |
| DISPONIVEL | bloquear | BLOQUEADO | Apoiador ou mediação | justificativa | oculto do catálogo |
| BLOQUEADO | liberar | DISPONIVEL | mediação | decisão | volta ao catálogo, auditoria |
| DISPONIVEL | liberar | LIBERADO⁴ | sistema | — | decisão pendente 9 |

⁴ O gatilho de "Liberado" não existe na fonte (Decisão 9 do PRD). Até decisão, `LIBERADO` não é produzido; `DISPONIVEL` cobre "volta à lista".

Regras:

- Horário reservado não pode ser editado nem removido; só cancelado ou remarcado (RF004/RF009).
- Horário no passado não pode ser publicado; recorrência não cria datas passadas.
- Bloqueio de horário denunciado é ato da mediação com justificativa (RF015).

### Reserva

```text
                    ┌── ciência em 24 h ──> ATIVA
CIENCIA_PENDENTE ───┼── cancelar ≥ 24 h ──> CANCELADA (horário DISPONIVEL)
                    ├── cancelar < 24 h ──> EM_ANALISE_MEDIACAO (horário BLOQUEADO)
                    └── sem ciência 24 h ─> permanece CIENCIA_PENDENTE + alerta à mediação

ATIVA ──1ª confirmação──> AGUARDANDO_CONFIRMACAO
ATIVA ──cancelamento──> CANCELADA | EM_ANALISE_MEDIACAO
ATIVA ──não comparecimento──> FALTA_REGISTRADA
ATIVA ──remarcação──> REMARCADA (nova reserva)
AGUARDANDO_CONFIRMACAO ──2ª confirmação──> CONCLUIDA
AGUARDANDO_CONFIRMACAO ──72 h──> CONCLUIDA_UMA_CONFIRMACAO
AGUARDANDO_CONFIRMACAO ──discordância──> EM_ANALISE_MEDIACAO
```

Regras:

- A ciência em 24 h **não** é aceite; a reserva existe desde a criação (RF008).
- Sem ciência, a reserva é mantida e a mediação é notificada.
- `REMARCADA` aponta para a reserva substituta; a anterior não é apagada.
- Duas reservas não canceladas para o mesmo horário são impossíveis no banco ([ADR 0012](0012-reserva-atomica-e-concorrencia.md)).

### Denúncia, sanção e contestação

| Agregado | Transições |
|---|---|
| Denúncia | `EM_ANALISE` → `PROCEDENTE` / `IMPROCEDENTE`; depois → `ARQUIVADA` |
| Sanção | `ATIVA` → `CUMPRIDA` (prazo) / `REVOGADA` (contestação deferida) |
| Contestação | `ABERTA` → `EM_ANALISE` → `DEFERIDA` / `INDEFERIDA` |

Regras:

- Sanção exige tipo, motivo, prazo, mediador e vínculo com denúncia/ocorrência.
- Contestação é analisada por **outro** mediador quando possível.
- Suspensão preventiva (3 denunciantes distintos em 30 dias) é automática, mas notifica e é registrada.
- Denúncia improcedente não deixa marca visível no perfil.

### Como a interface descobre as ações

O agregado expõe métodos `podeX(ator)`; a view model monta a lista de botões a partir deles. A tela **não** repete a regra:

```java
public boolean podeCancelar(UUID atorId) {
    return (status == ABERTA || status == RESERVADA) && ehParte(atorId);
}
```

Assim, se a regra mudar, muda em um lugar só.

### Histórico e auditoria

- Cada agregado mantém `criado_em`, `atualizado_em` e `versao` (`@Version`).
- A trilha completa fica em `audit_log` ([ADR 0014](0014-auditoria-e-acesso-a-dados.md)), com antes/depois mascarado.
- `analise_solicitacao` guarda o parecer; `reserva_confirmacao` guarda as duas confirmações; `expurgo_log` guarda descarte.
- A tela de histórico mostra a linha do tempo com status, data/hora e autor.

### Testes de contrato da máquina

Um teste parametrizado percorre a tabela de transições:

- toda transição permitida produz o estado esperado e um evento;
- toda transição proibida lança `EstadoInvalidoException` com `estadoAtual` e `acao`;
- nenhum estado terminal aceita transição;
- toda transição grava auditoria.

## Fluxo de usuário afetado

| Jornada do PRD | Estados percorridos |
|---|---|
| 2.1/2.2 Cadastro e aprovação | `EM_ANALISE` → `ATIVA`; verificação `PENDENTE` → `APROVADO` |
| 2.3 Solicitação e análise | `EM_ANALISE` → `ABERTA` (ou `AGUARDANDO_AJUSTE`/`RECUSADA`) |
| 2.4 Descoberta e reserva | `ABERTA` → `RESERVADA`; horário `DISPONIVEL` → `RESERVADO`; reserva `CIENCIA_PENDENTE` → `ATIVA` |
| 2.5 Atendimento, conclusão e avaliação | `AGUARDANDO_CONFIRMACAO` → `CONCLUIDA`/`CONCLUIDA_UMA_CONFIRMACAO` |
| 2.6 Cancelamento, falta e denúncia | `CANCELADA`, `EM_ANALISE_MEDIACAO`, `FALTA_REGISTRADA`; denúncia `EM_ANALISE` |
| 3.x Histórico e expiração | `EXPIRADA`, `ARQUIVADA`, `EXPURGADO` |

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Máquina genérica de estados | Esconde condições e efeitos; difícil de auditar e de ler. |
| Status como texto livre | Sem garantia de consistência nem de `CHECK`. |
| Regra só na interface | Bypass por URL direta e por API; fere RNF012. |
| Triggers de banco para transição | Invisível ao código, difícil de testar e de versionar. |
| Reutilizar `is_active`/`enabled` | Perde a distinção dos cinco status de conta. |

## Consequências

- Há mais métodos explícitos por agregado, mas cada um é legível e testável.
- Novos estados exigem migration, enum, transições, telas e testes — mudança consciente.
- As decisões pendentes do PRD precisam ser fechadas antes de implementar os pontos marcados; o código não deve "escolher" por conta própria.
- A interface fica acoplada aos métodos `podeX`, o que evita duplicação.

## Critérios de aceite

- [ ] Nenhum estado é gravado sem passar por um método de transição do agregado.
- [ ] Toda transição proibida lança `EstadoInvalidoException` e não grava nada.
- [ ] Toda transição relevante aparece em `audit_log` com ator, motivo e data.
- [ ] Estados terminais não aceitam edição.
- [ ] A view model de cada tela deriva botões de `podeX`, sem repetir condição.
- [ ] Existe teste parametrizado cobrindo a tabela de transições de cada agregado.
- [ ] Uma solicitação nunca tem duas reservas ativas.
- [ ] Um horário nunca tem duas reservas não canceladas.

## Decisões pendentes

Herdadas do PRD (seção 0.2 e Parte IV), que **bloqueiam** a implementação correspondente:

> **DECISÃO PENDENTE 58 (crítica):** status da conta recusada: `BLOQUEADA`, `DESATIVADA` ou novo `RECUSADA` (Decisão 5).
>
> **DECISÃO PENDENTE 59 (crítica):** desfecho das 72 h: concluir com uma confirmação (`CONCLUIDA_UMA_CONFIRMACAO`) ou expirar e abrir ocorrência (Decisões 3 e 23).
>
> **DECISÃO PENDENTE 60:** gatilho de `LIBERADO` no horário (Decisão 9).
>
> **DECISÃO PENDENTE 61:** "Em andamento" é sinônimo de `RESERVADA` (Decisão 7)?
>
> **DECISÃO PENDENTE 62:** a agenda passa por validação prévia da mediação (Decisão 12)? Se sim, entra um estado `AGUARDANDO_VALIDACAO` em `horario`.
>
> **DECISÃO PENDENTE 63:** a remarcação consome a reserva atual ou cria uma nova com vínculo (Decisão 20)?
>
> **DECISÃO PENDENTE 64:** limite de solicitações ativas por Assistido (Decisão 13), que afeta a transição "criar → EM_ANALISE".
