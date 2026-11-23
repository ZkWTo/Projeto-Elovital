# ADR 0014: Auditoria e acesso a dados pessoais

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — RNF012, RS012, RF006, RF015, RF017
- **Relacionado:** [ADR 0013](0013-lgpd-e-dados-sensiveis.md), [ADR 0020](0020-observabilidade-e-operacao.md), [ADR 0023](0023-painel-de-mediacao.md)

## Contexto

O RNF012 exige registrar, com usuário, ação, data e hora: login, validação de conta, aprovação de solicitação, reserva, cancelamento, sanção, acesso da mediação a dados pessoais e mudança de status. O RS012 trata do uso indevido de privilégios por mediadores. O RF015 exige justificativa para sanção e proíbe a mediação de alterar nota de avaliação.

A auditoria tem duas funções diferentes, que costumam ser confundidas:

1. **Trilha de negócio:** prova de que uma decisão foi tomada, por quem e com qual fundamento. Imutável, retida por anos.
2. **Log técnico:** diagnóstico de erro e desempenho. Efêmero, sem dado pessoal.

Se as duas forem a mesma coisa, ou a auditoria perde dado por rotação de log, ou o log carrega dado sensível.

## Decisão

### Tabelas separadas

| Tabela | Finalidade | Retenção | Mutabilidade |
|---|---|---|---|
| `audit_log` | trilha de negócio | 5 anos | somente inserção |
| `acesso_dado_pessoal` | acesso da mediação a CPF/endereço/documento/condição | 5 anos | somente inserção |
| `expurgo_log` | anonimização e descarte | 5 anos | somente inserção |
| `tentativa_login` | segurança de autenticação | 90 dias | inserção + limpeza |
| log da aplicação | diagnóstico técnico | 90 dias | externo ao banco |

### `audit_log`

| Coluna | Conteúdo |
|---|---|
| `id` | bigserial |
| `criado_em` | timestamptz, relógio do banco em UTC |
| `ator_id` | UUID do usuário (nulo para sistema/rotina) |
| `ator_papel` | papel no momento da ação |
| `acao` | enum estável (`CONTA_APROVADA`, `RESERVA_CRIADA`, ...) |
| `entidade` | tabela/agregado afetado |
| `entidade_id` | UUID |
| `antes` | jsonb com apenas campos alterados permitidos |
| `depois` | jsonb com apenas campos alterados permitidos |
| `motivo` | obrigatório em ações sensíveis |
| `ip_hash` | HMAC do IP, não o valor |
| `user_agent_hash` | HMAC do user agent |
| `correlacao_id` | id de correlação da requisição |
| `origem` | `WEB`, `MEDIACAO`, `ROTINA`, `API` |

Regras:

- `antes`/`depois` guardam **somente os campos alterados** e **nunca** CPF, telefone, documento, endereço, condição de saúde ou conteúdo de chat. Para campo sensível, grava-se `"[alterado]"`.
- `audit_log` é append-only: `UPDATE` e `DELETE` revogados para `elovital_app` e bloqueados por trigger. A única exceção é a zeragem de `ip_hash`/`user_agent_hash` após 6 meses, feita pelo papel de migração.
- O relógio é do banco, não da aplicação, para evitar divergência entre instâncias.
- O registro é gravado **na mesma transação** da mudança de estado. Se a transação falha, a auditoria não fica órfã.

### Ações obrigatórias

| Categoria | Ações |
|---|---|
| Autenticação | login, falha de login, logout, recuperação de senha, bloqueio por tentativas |
| Conta | aprovação, recusa, suspensão, reativação, bloqueio, desativação, anonimização |
| Verificação | envio, aprovação, reprovação, expurgo |
| Solicitação | criação, aprovação, ajuste, recusa, contestação, cancelamento, expiração |
| Agenda | publicação, edição, bloqueio, liberação, cancelamento de recorrência |
| Reserva | criação, ciência, cancelamento, remarcação, confirmação, conclusão, falta |
| Reputação | avaliação, ocultação de comentário |
| Moderação | denúncia, decisão, sanção, revogação, contestação |
| Parâmetros | alteração com valor anterior e novo |
| Dados pessoais | acesso da mediação com motivo, exportação, eliminação |

### `acesso_dado_pessoal`

Toda vez que a mediação consulta CPF, endereço exato, documento ou condição de saúde, grava-se:

```text
id, criado_em, mediador_id, titular_id, tipo_dado, entidade, entidade_id,
motivo (obrigatório, texto de 20 a 500), correlacao_id
```

Implementação:

- O service expõe um método que **exige** um `MotivoAcesso` (value object não vazio, com tamanho mínimo) antes de devolver o dado.
- A tela de mediação abre um modal de motivo antes de revelar o campo.
- Não existe endpoint genérico "ver todos os dados do usuário"; existem visões por tarefa.
- Consulta em lista não revela o dado sensível; só o detalhe, com motivo.
- Acesso negado também é auditado (tentativa indevida).

```java
public DadosSensiveisConta revelarDadosSensiveis(UUID titularId, MotivoAcesso motivo) {
    autorizacao.exigirAuthority(DADOS_SENSIVEIS_ACESSAR);
    acessoRegistro.registrar(atorAtual(), titularId, TipoDado.CPF, motivo);
    return ...
}
```

### Como a auditoria é disparada

1. **Explícita no caso de uso**, para decisões de negócio (aprovar, reservar, sancionar). É o caminho principal.
2. **Por transição de agregado:** todo método `aprovar`, `cancelar`, `concluir` chama `registrarTransicao`, que alimenta o histórico do agregado e o `audit_log`.
3. **Por evento após commit**, apenas para efeitos complementares (notificação enviada), nunca para a decisão em si.
4. **Aspecto (AOP) apenas para acesso a dado pessoal**, porque é transversal e precisa de motivo; não se usa AOP para regra de negócio.

### Correlação

- Um filtro gera `correlacaoId` por requisição e o coloca no MDC.
- O id vai para o log técnico, para o `audit_log` e para a resposta de erro (para suporte).
- O id não identifica pessoa, apenas a requisição.

### Consulta e exportação

- A mediação consulta por período, ator, ação e entidade, com paginação.
- A exportação para auditoria externa é feita pelo papel de migração/leitura, em CSV/JSON, com registro do próprio acesso.
- Indicadores do painel (RF015) são consultas de leitura sobre as tabelas de domínio, não sobre o log; o critério de aceite exige que confiram com a auditoria.

### Imutabilidade no banco

- `REVOKE UPDATE, DELETE` para `elovital_app`.
- Trigger que rejeita `UPDATE`/`DELETE` (exceto a zeragem de IP pelo migrator).
- `GRANT INSERT, SELECT` apenas.
- Partitionamento por mês é opcional na v1; se adotado, não muda o contrato.

### O que **não** vai para a auditoria

- Conteúdo de chat (o trecho relevante de uma denúncia é copiado para `denuncia.trecho`, não para o log).
- CPF, telefone, endereço, documento e condição em valor.
- Senha, hash, token, chave.
- Corpo completo de requisição/resposta.

## Fluxo de usuário afetado

| Etapa | O que é auditado |
|---|---|
| Cadastro | criação da conta (sem CPF no payload), aceite de termos |
| Aprovação de conta | ator, decisão, justificativa, data |
| Login | sucesso, falha, bloqueio, logout |
| Verificação | envio, decisão, expurgo |
| Agenda | publicação, edição, bloqueio, liberação |
| Solicitação | criação, decisão da mediação, contestação |
| Catálogo | nenhuma ação de escrita; acesso a dado pessoal pela mediação é auditado |
| Reserva | criação, ciência, cancelamento, remarcação |
| Chat | envio (metadado), denúncia de mensagem, ocultação |
| Conclusão | cada confirmação, desfecho |
| Avaliação | criação, ocultação |
| Denúncia | protocolo, decisão, sanção, contestação |
| Expiração/expurgo | execução da rotina, linhas afetadas |
| Mediação | toda consulta a dado sensível, com motivo |

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Usar só o log da aplicação | Rotação apaga prova; log tem dado sensível; não é consultável por negócio. |
| Hibernate Envers | Auditoria automática de todas as colunas tende a gravar dado sensível e não registra motivo. |
| Trigger genérica em todas as tabelas | Não sabe o ator nem o motivo; gera volume inútil. |
| Auditoria em arquivo | Difícil de consultar e de garantir imutabilidade. |
| `pgaudit` como trilha de negócio | Complementar, mas registra comando SQL, não a decisão. |

## Consequências

- Cada caso de uso crítico precisa lembrar de auditar; o teste de contrato da máquina de estados ajuda a garantir.
- O volume de `audit_log` cresce com o uso; a retenção de 5 anos exige planejamento de disco e índice por data.
- Guardar `antes/depois` em jsonb exige disciplina para não vazar campo sensível.
- A consulta de mediação a dado pessoal fica mais lenta (um insert a mais), o que é aceitável.
- A imutabilidade bloqueia correções manuais; erros de auditoria não são "consertados", são registrados.

## Critérios de aceite

- [ ] Toda ação da tabela de ações obrigatórias gera linha em `audit_log`.
- [ ] `audit_log` rejeita `UPDATE` e `DELETE` para a aplicação (teste de banco).
- [ ] Nenhum campo sensível aparece em `antes`/`depois`.
- [ ] Acesso a CPF, endereço, documento ou condição sem motivo é negado e auditado.
- [ ] O motivo tem tamanho mínimo e não pode ser genérico vazio.
- [ ] A auditoria é gravada na mesma transação da mudança de estado.
- [ ] Exportação de auditoria registra quem exportou.
- [ ] Indicadores do painel conferem com consultas de auditoria.
- [ ] O log técnico não é usado como fonte de auditoria.
- [ ] Toda transição de status aparece com ator, data/hora e motivo quando exigido.

## Decisões pendentes

> **DECISÃO PENDENTE 74:** definir o texto mínimo e o conjunto de motivos padronizados para acesso a dado pessoal (lista fechada + "outro").
>
> **DECISÃO PENDENTE 75:** confirmar retenção de 5 anos para `audit_log` e `acesso_dado_pessoal` com o jurídico.
>
> **DECISÃO PENDENTE 76:** decidir se `audit_log` será particionado por mês já na v1 ou só quando o volume exigir.
>
> **DECISÃO PENDENTE 77:** definir se a mediação pode exportar dados pessoais em massa ou apenas por caso, com aprovação de segundo mediador.
