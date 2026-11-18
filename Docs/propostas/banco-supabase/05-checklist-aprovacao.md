# 05 — Checklist de aprovação

- **Status:** Rascunho para aprovação
- **Data:** 2026-11-16
- **ADR relacionada:** [ADR 0004](../../adr/0004-supabase-postgresql.md)

Este checklist existe para que a aprovação seja explícita. Marcar tudo significa aceitar que a proposta pode virar código; não significa que o banco já foi criado ou que a produção está liberada.

## Como usar

1. Leia os documentos na ordem do [README](README.md).
2. Marque apenas o que você realmente conferiu.
3. Use a coluna de observações para ressalvas, condições ou prazos.
4. Assine o bloco final.
5. Se algum item essencial ficar desmarcado, a proposta volta para ajuste em vez de seguir para implementação.

Legenda: **[essencial]** bloqueia a implementação; **[recomendado]** pode virar pendência com prazo.

## 1. Escopo e arquitetura

- [ ] **[essencial]** O escopo do banco cobre todos os RF001 a RF017 sem criar funcionalidade fora do PRD.
- [ ] **[essencial]** O schema `elovital` é separado de `public` e de `extensions`.
- [ ] **[essencial]** O Django continua sendo dono do schema, via migrations; o Supabase não cria tabelas de domínio por fora.
- [ ] **[recomendado]** A autenticação permanece no Django, sem Supabase Auth na primeira versão.
- [ ] **[recomendado]** O conteúdo continua em texto puro, sem HTML ou Markdown.

Observações: ______________________________________________

## 2. Projeto, região e plano

- [ ] **[essencial]** Região `sa-east-1` (São Paulo) aprovada.
- [ ] **[essencial]** Projetos separados para `dev`, `staging` e `prod`.
- [ ] **[essencial]** Plano Pro ou superior, com backup diário.
- [ ] **[recomendado]** PITR de 7 dias contratado.
- [ ] **[recomendado]** RPO de 5 minutos e RTO de 4 horas aceitos.
- [ ] **[recomendado]** Orçamento mensal aprovado, com alerta de custo.

Observações: ______________________________________________

## 3. Conexão e rede

- [ ] **[essencial]** Supavisor em modo sessão (porta 5432) como padrão da aplicação.
- [ ] **[essencial]** TLS `verify-full` com CA do Supabase, sem `sslmode=disable` ou `prefer`.
- [ ] **[essencial]** Data API desabilitada para o schema `elovital`.
- [ ] **[essencial]** Nenhum acesso do navegador ao banco.
- [ ] **[recomendado]** Pool dimensionado e monitorado; `CONN_MAX_AGE` entre 30 e 60 s.
- [ ] **[recomendado]** `statement_timeout`, `lock_timeout` e `idle_in_transaction_session_timeout` aplicados.

Observações: ______________________________________________

## 4. Papéis e privilégios

- [ ] **[essencial]** `elovital_migrator`, `elovital_app` e `elovital_readonly` com funções distintas.
- [ ] **[essencial]** A aplicação não é dona das tabelas e não tem DDL.
- [ ] **[essencial]** `anon` e `authenticated` sem `USAGE` no schema `elovital`.
- [ ] **[essencial]** `elovital_app` sem `UPDATE`/`DELETE` em `audit_log`, `acesso_dado_pessoal` e `expurgo_log`.
- [ ] **[essencial]** `elovital_app` sem `UPDATE` em `cpf_hash` e `cpf_cifrado`.
- [ ] **[recomendado]** `elovital_readonly` lê apenas views mascaradas.
- [ ] **[recomendado]** MFA obrigatório para todas as pessoas com acesso ao painel.
- [ ] **[recomendado]** Revisão trimestral de acessos e rotação de senhas.

Observações: ______________________________________________

## 5. Modelo de dados

- [ ] **[essencial]** As 30 tabelas do schema `elovital` conferem com o catálogo do documento 02 (26 de domínio e 4 operacionais).
- [ ] **[essencial]** CPF em `cpf_hash` + `cpf_cifrado`, sem texto puro em nenhuma coluna.
- [ ] **[essencial]** Índice único parcial garante uma reserva ativa por horário.
- [ ] **[essencial]** Índice único parcial garante uma reserva ativa por solicitação.
- [ ] **[essencial]** Função `elovital.reservar_horario` usa transação e bloqueio de linha.
- [ ] **[essencial]** Avaliação é imutável e limitada a uma por parte e por reserva.
- [ ] **[essencial]** Não há exclusão física de contas, solicitações, reservas ou mensagens.
- [ ] **[recomendado]** 26 tabelas de domínio revisadas com a mediação (ver observação abaixo).
- [ ] **[recomendado]** Índices de catálogo, histórico e distância revisados com plano de execução.

> A contagem de referência é **30 tabelas no schema**, sendo **26 de domínio** (contas, perfis, catálogos, agenda, solicitações, reservas, comunicação, reputação, moderação) e **4 operacionais** (`tentativa_login`, `audit_log`, `acesso_dado_pessoal`, `expurgo_log`). Se a implementação mudar a contagem, este item e o documento 02 precisam ser atualizados juntos.

Observações: ______________________________________________

## 6. Criptografia e segredos

- [ ] **[essencial]** CPF com HMAC-SHA-256 e pepper, separado da cifra.
- [ ] **[essencial]** CPF, telefone e contato de emergência cifrados com AES-256-GCM no application side.
- [ ] **[essencial]** Chave de cifra e pepper fora do banco e fora do Git.
- [ ] **[essencial]** Senhas com Argon2id e sessão com expiração de 30 minutos.
- [ ] **[essencial]** Documentos em bucket privado, com URL assinada de 60 s.
- [ ] **[recomendado]** Formato versionado (`v1:`) e plano de rotação de chave.
- [ ] **[recomendado]** Backup lógico mensal cifrado, guardado fora do Supabase.

Observações: ______________________________________________

## 7. Segurança e auditoria

- [ ] **[essencial]** RLS habilitada com `FORCE ROW LEVEL SECURITY`.
- [ ] **[essencial]** Política nega quando o contexto da sessão não está definido.
- [ ] **[essencial]** Autorização de linha continua nos services, não apenas na RLS.
- [ ] **[essencial]** Login, validação, aprovação, reserva, cancelamento, sanção e mudança de status auditados.
- [ ] **[essencial]** Acesso da mediação a dado pessoal registrado com motivo.
- [ ] **[essencial]** `audit_log`, `acesso_dado_pessoal` e `expurgo_log` imutáveis.
- [ ] **[essencial]** Logs de aplicação sem CPF, telefone, endereço, saúde ou conteúdo de chat.
- [ ] **[recomendado]** Testes de RLS e grants no CI.
- [ ] **[recomendado]** Registros de acesso (IP) retidos por 6 meses, conforme Marco Civil.

Observações: ______________________________________________

## 8. LGPD

- [ ] **[essencial]** Base legal definida por finalidade, com consentimento específico e destacado para dados de saúde.
- [ ] **[essencial]** Consentimento com versão, data, finalidade e revogação.
- [ ] **[essencial]** Encarregado definido e canal publicado.
- [ ] **[essencial]** Política de privacidade e termos revisados por jurídico.
- [ ] **[essencial]** Retenção e expurgo definidos para cada categoria de dado.
- [ ] **[essencial]** Direitos do titular com fluxo, prazo e registro.
- [ ] **[essencial]** Fornecedores com DPA e localização documentada.
- [ ] **[essencial]** Plano de resposta a incidente, com comunicação à ANPD e aos titulares.
- [ ] **[recomendado]** Simulação de incidente antes do piloto.
- [ ] **[recomendado]** Revisão jurídica da transferência internacional e do contrato do Supabase.

Observações: ______________________________________________

## 9. Migração, testes e operação

- [ ] **[essencial]** Migrations criam o banco do zero, sem passo manual.
- [ ] **[essencial]** Teste de reserva concorrente aprovado.
- [ ] **[essencial]** Teste de restauração aprovado, com expurgo reaplicado.
- [ ] **[essencial]** Teste de expurgo por prazo aprovado.
- [ ] **[essencial]** Runbooks escritos antes do piloto.
- [ ] **[recomendado]** Teste de desempenho com base sintética do primeiro ano.
- [ ] **[recomendado]** Critérios de saída do piloto definidos.
- [ ] **[recomendado]** Critérios de rollback acordados.

Observações: ______________________________________________

## 10. Decisões que precisam de resposta nominal

| # | Decisão | Resposta | Responsável | Data |
|---|---|---|---|---|
| 1 | Região `sa-east-1` | | | |
| 2 | Plano e PITR | | | |
| 3 | Retenção de conta encerrada | | | |
| 4 | Retenção de mensagens arquivadas | | | |
| 5 | Encarregado e canal | | | |
| 6 | Provedor de e-mail | | | |
| 7 | Provedor de mapas | | | |
| 8 | Push na primeira versão | | | |
| 9 | Supabase Auth no futuro | | | |
| 10 | Orçamento mensal | | | |

## 11. Resultado da aprovação

- [ ] **Aprovado sem ressalvas** — a ADR 0004 passa para `Aceito` e a implementação pode começar.
- [ ] **Aprovado com ressalvas** — a implementação começa apenas nos itens sem ressalva; o restante fica com prazo.
- [ ] **Devolvido para ajuste** — nenhum item é implementado até nova versão.

Ressalvas e condições:

```text

```

## 12. Assinaturas

| Papel | Nome | Assinatura | Data |
|---|---|---|---|
| Responsável pelo produto | | | |
| Responsável técnico | | | |
| Encarregado (DPO) | | | |
| Jurídico | | | |

## 13. Depois da aprovação

1. Atualizar o status da [ADR 0004](../../adr/0004-supabase-postgresql.md) para `Aceito`.
2. Atualizar a tabela de status do [README geral](../../README.md).
3. Registrar a data da aprovação neste checklist.
4. Abrir as specs de implementação por funcionalidade.
5. Só então criar os projetos no Supabase e escrever as migrations.

Se a proposta for recusada, o caminho alternativo é PostgreSQL gerenciado em outro provedor ou PostgreSQL autogerenciado, com nova ADR. O restante do PRD e dos ADRs 0001 a 0003 não muda.
