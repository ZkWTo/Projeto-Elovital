# ADR 0023: Painel de mediação e moderação

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — RF006, RF014, RF015, RF016, RF017
- **Relacionado:** [ADR 0008](0008-seguranca-autenticacao-e-papeis.md), [ADR 0011](0011-maquinas-de-estado-do-fluxo.md), [ADR 0014](0014-auditoria-e-acesso-a-dados.md), [ADR 0019](0019-parametros-e-cadastros-base.md), [ADR 0021](0021-testes-qualidade-e-ci.md)

## Contexto

A mediação é o **controle central** do fluxo (RF006): nenhuma conta, solicitação ou agenda é publicada sem análise humana; denúncias são julgadas por pessoa; sanções exigem justificativa; o sancionado pode contestar. O RF015 descreve nove áreas de trabalho, e o RS012 alerta para abuso de privilégio.

A mediação precisa ser rápida (prazo de 24 h úteis para solicitações), segura (acesso mínimo a dado sensível) e auditável (toda decisão com autor, motivo e data).

## Decisão

### Mesma aplicação, área separada

- O painel fica na **mesma aplicação**, sob `/mediacao/**`, com layout próprio.
- Não se usa Spring Admin, JHipster ou framework de admin externo: o fluxo é específico e a superfície de ataque precisa ser conhecida.
- Acesso exige `ROLE_MEDIADOR` **e** authority `MEDIACAO`; cada ação exige a authority específica.
- Sessão de mediação tem timeout curto (15 min sugerido) e não é "lembrada".

### Estrutura por fila de trabalho

| Rota | Fila | Ações |
|---|---|---|
| `/mediacao/contas` | contas em análise | ver documentos, aprovar, recusar |
| `/mediacao/verificacoes` | documentos pendentes | aprovar, reprovar com motivo |
| `/mediacao/solicitacoes` | solicitações em análise | aprovar, ajustar, recusar, definir prioridade |
| `/mediacao/agendas` | horários denunciados | bloquear, liberar, suspender agenda |
| `/mediacao/denuncias` | denúncias por gravidade | ver contexto, decidir, sancionar, notificar |
| `/mediacao/ocorrencias` | faltas, desistências, discordâncias | decidir, liberar horário, advertir |
| `/mediacao/contestacoes` | contestações abertas | deferir, indeferir |
| `/mediacao/parametros` | parâmetros e cadastros-base | alterar com justificativa |
| `/mediacao/indicadores` | contadores e prazos | consultar |

Regras:

- Cada fila mostra apenas o necessário: identificador, tipo, data de entrada, prazo, status e indicador de atraso.
- Dado sensível não aparece na listagem; só no detalhe, com motivo.
- Ordenação padrão por **data de envio** (RF006), com destaque para fora do prazo.
- Filtros por status, período, tipo e atraso.
- Paginação de 10 (parâmetro `historico_pagina`).

### Detalhe e decisão

O detalhe de uma solicitação mostra:

- dados da conta (status, verificação), sem CPF na listagem;
- conteúdo da solicitação em texto puro;
- histórico de análises anteriores (RF006);
- reservas e cancelamentos vinculados;
- linha do tempo de status;
- ações disponíveis, derivadas de `podeX` do agregado ([ADR 0011](0011-maquinas-de-estado-do-fluxo.md)).

Toda decisão:

1. exige parecer (`Aprovar`, `Solicitar ajuste`, `Recusar`);
2. exige prioridade na aprovação;
3. exige justificativa em ajuste/recusa (500 caracteres);
4. grava `analise_solicitacao` + `audit_log`;
5. notifica o Assistido;
6. atualiza a fila.

### Acesso a dado sensível

- A listagem não mostra CPF, endereço, documento ou condição.
- O detalhe revela o dado **somente** após o mediador informar um motivo ([ADR 0014](0014-auditoria-e-acesso-a-dados.md)).
- Não existe busca livre por CPF que devolva o dado; a busca por CPF retorna a conta, e o dado exige motivo.
- Cada revelação registra titular, tipo de dado, mediador, motivo, data/hora e correlação.
- Exportação em massa é restrita e auditada (decisão pendente).
- A mediação **não** vê senha, token nem chave.

### Denúncia e sanção

Fluxo:

```text
denúncia recebida (protocolo) → fila por gravidade → mediação lê contexto
  → procedente → escolhe sanção (advertência, suspensão, bloqueio) com motivo e prazo
      → notifica sancionado com direito de contestação
  → improcedente → arquiva, notifica denunciante sem revelar o denunciado
```

Regras:

- O trecho da mensagem denunciada é anexado automaticamente na criação (snapshot imutável).
- A identidade do denunciante não é revelada ao denunciado (RF014).
- Suspensão preventiva por 3 denunciantes distintos em 30 dias é automática (RF014), mas o mediador confirma ou revoga.
- Toda sanção exige tipo, motivo, prazo, mediador e vínculo com denúncia/ocorrência.
- Contestação é analisada por **outro** mediador quando possível; se não houver outro, fica registrado o motivo.
- A mediação pode ocultar conteúdo ofensivo, nunca alterar nota de avaliação (RF015).

### Agendas e horários

- A mediação pode bloquear horário denunciado, liberar horário bloqueado e suspender agenda.
- Suspender conta oculta a agenda automaticamente.
- Bloqueio e liberação exigem justificativa e auditoria.
- A decisão sobre validação prévia de agenda (RF004 × RF015) está pendente; enquanto isso, o painel apenas fiscaliza e reage a denúncia.

### Indicadores

Indicadores mínimos (RF015): contas ativas, solicitações criadas/aprovadas/concluídas, tempo médio de validação, tempo até a reserva, taxa de cancelamento, denúncias abertas e itens fora do prazo.

Regras:

- Consultas de leitura com projeção e cache curto (1 min).
- Nenhum indicador usa dado pessoal.
- O critério de aceite exige que o número confira com a consulta de auditoria.
- Atualização por página (não precisa de tempo real).

### Segurança do painel

- Nenhuma ação crítica é `GET`; todas são `POST` com CSRF.
- Confirmação explícita para sanção, bloqueio e alteração de parâmetro.
- Dupla aprovação para parâmetros críticos, se aprovada ([ADR 0019](0019-parametros-e-cadastros-base.md)).
- Logout invalida a sessão; inatividade de 15 min encerra.
- Acesso negado é auditado.
- Revisão periódica de quem tem `ROLE_MEDIADOR`.

### O que o painel não faz

- Não exclui conta, solicitação ou mensagem (RF015: sem exclusão física).
- Não altera nota de avaliação.
- Não revela dado sensível sem motivo.
- Não aprova automaticamente nada por prazo.
- Não acessa chat fora de denúncia ou ocorrência (leitura auditada).

## Fluxo de usuário afetado

1. **Cadastro (RF001):** conta entra na fila de contas; mediação aprova ou recusa.
2. **Verificação (RF016):** documentos na fila; aprovação concede o selo.
3. **Solicitação (RF005/RF006):** fila por data de envio; parecer, prioridade e justificativa.
4. **Agenda (RF004):** fiscalização e bloqueio de horário denunciado.
5. **Reserva/cancelamento (RF008/RF009):** cancelamento tardio e ocorrências na fila.
6. **Conclusão (RF012):** discordância e falta vão para ocorrências.
7. **Denúncia (RF014):** fila por gravidade, decisão, sanção, contestação.
8. **Parâmetros (RNF010):** tela de alteração com auditoria.
9. **Indicadores (RF015):** painel de acompanhamento.

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Django Admin / Spring Admin | Não modela filas, prazos e justificativa obrigatória. |
| Planilha externa | Sem auditoria, sem controle de acesso e sem transação. |
| Painel em aplicação separada | Duplica autenticação e aumenta superfície. |
| Aprovação automática por prazo | Proibida pelo RF006 e pelo PRD. |
| Tela única "ver tudo" | Viola minimização e RS012. |

## Consequências

- A mediação ganha um fluxo sob medida, mas o time precisa construir e manter telas adicionais.
- O motivo obrigatório adiciona atrito; é intencional e exigido pela LGPD.
- A fila por data de envio pode não refletir gravidade; a ordenação é requisito e não deve ser alterada sem decisão.
- Indicadores precisam de consultas eficientes para não pesar no banco.
- A dupla aprovação, se aprovada, adiciona estado e tela.

## Critérios de aceite

- [ ] Nenhuma solicitação é publicada sem parecer humano registrado.
- [ ] Mediação não consegue alterar nota de avaliação.
- [ ] Nenhuma tela oferece exclusão física de conta, solicitação ou mensagem.
- [ ] Acesso a CPF/endereço/documento/condição exige motivo e gera registro.
- [ ] Toda sanção tem tipo, motivo, prazo e mediador.
- [ ] Contestação é encaminhada a outro mediador quando existir.
- [ ] Indicadores do painel conferem com a auditoria.
- [ ] Conta suspensa some do catálogo e tem sessões invalidadas.
- [ ] Fila de solicitações ordena por data de envio e destaca atraso.
- [ ] Mediação sem authority recebe 403 e o evento é auditado.

## Decisões pendentes

> **DECISÃO PENDENTE 116:** a agenda passa por validação prévia (Decisão 12 do PRD)? Se sim, entra uma fila de agendas em análise.
>
> **DECISÃO PENDENTE 117:** definir os indicadores obrigatórios da v1 e a periodicidade (Decisão 27).
>
> **DECISÃO PENDENTE 118:** definir se a mediação pode exportar dados em massa e se exige dupla aprovação.
>
> **DECISÃO PENDENTE 119:** definir a regra de "outro mediador" na contestação quando houver apenas um disponível.
>
> **DECISÃO PENDENTE 120:** confirmar o prazo de atendimento de denúncias (proposta: 5 dias úteis).
>
> **DECISÃO PENDENTE 121:** confirmar se a suspensão preventiva é automática ou proposta ao mediador (Decisão 26).
