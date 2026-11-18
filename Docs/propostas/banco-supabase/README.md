# Proposta de banco Supabase (em revisão)

- **Status:** Rascunho para aprovação — nada aplicado
- **Data:** 2026-11-16
- **ADR relacionada:** [ADR 0004 — Supabase (PostgreSQL)](../../adr/0004-supabase-postgresql.md)
- **PRD:** [PRD 0001](../../prd/0001-elovital.md)

## Por que este pacote está separado

O EloVital nasceu com a decisão de SQLite do projeto de referência. A mudança para Supabase/PostgreSQL não é só trocar uma linha de configuração: envolve schema, papéis, criptografia, RLS, backup, retenção, LGPD, custo e operação. Por isso, tudo que muda por causa do banco está concentrado aqui, em vez de espalhado pelo PRD e pelos ADRs.

Nada nesta pasta foi aplicado em código, migrations ou no Supabase. A leitura e a aprovação são o próximo passo.

## O que muda

- O banco deixa de ser SQLite e passa a ser PostgreSQL gerenciado no Supabase, região de São Paulo.
- O schema da aplicação ganha tabelas para contas, perfis, agenda, solicitações, reservas, chat, avaliações, denúncias, sanções, notificações, parâmetros e auditoria.
- A reserva de horário passa a usar transação e bloqueio de linha no PostgreSQL, com função atômica.
- CPF, telefone, contato de emergência e documentos passam a ter tratamento explícito de dado sensível.
- A aplicação usa papéis de banco com privilégio mínimo; a Data API pública fica desabilitada para o schema da aplicação.
- Backup diário, PITR, restauração testada e expurgo programado entram como requisitos operacionais.
- Documentos de verificação vão para bucket privado no Supabase Storage, com URL assinada e expurgo.

## O que não muda

- O produto e o escopo do [PRD 0001](../../prd/0001-elovital.md).
- A arquitetura MVT com camada de service ([ADR 0001](../../adr/0001-mvt-com-camada-de-service.md)).
- A autenticação e a sessão do Django ([ADR 0002](../../adr/0002-usuario-papeis-e-autenticacao.md)).
- O conteúdo em texto puro ([ADR 0003](../../adr/0003-conteudo-em-texto-puro.md)).
- As migrations do Django como fonte de verdade do schema.
- A regra de que nenhuma solicitação é publicada sem mediação humana.

## Ordem de leitura sugerida

| Ordem | Documento | Tempo estimado | O que decide |
|---|---|---|---|
| 1 | [Configuração e operação](01-configuracao.md) | 10 min | Projeto, região, conexão, papéis, migrations, backup |
| 2 | [Modelo de dados](02-modelo-de-dados.md) | 15 min | Tabelas, relações, constraints, índices e funções |
| 3 | [Segurança e LGPD](03-seguranca-lgpd.md) | 15 min | Classificação, criptografia, retenção, direitos, incidentes |
| 4 | [Migração e operação](04-migracao-e-operacao.md) | 10 min | Fases, testes de aceite, rollback e riscos |
| 5 | [Checklist de aprovação](05-checklist-aprovacao.md) | 5 min | O que precisa ser marcado antes de implementar |

Os arquivos SQL em [`sql/`](sql/) são a versão executável de referência do schema e das políticas. Eles servem para revisão e para testes; na implementação, o Django gera as migrations equivalentes.

### Validação já realizada

Os três arquivos foram aplicados, na ordem, em um PostgreSQL 16 com PostGIS 3.4 e encerraram com `COMMIT`. Um teste funcional adicional verificou, com dados sintéticos:

| Verificação | Resultado |
|---|---|
| Reserva atômica cria reserva, marca horário como `reservado` e solicitação como `reservada` | OK |
| Segunda reserva no mesmo horário é recusada com erro de negócio | OK |
| `INSERT` direto de reserva pela aplicação é barrado pela RLS | OK |
| Índices parciais únicos barram dupla reserva por horário e por solicitação | OK |
| RLS mostra ao Assistido apenas a própria conta e, após a reserva, a contraparte e o endereço do atendimento | OK |
| Dupla confirmação só conclui quando as duas partes confirmam | OK |
| CPF, papel administrativo e `audit_log` são imutáveis para a aplicação | OK |
| Exceção de IP na auditoria vale apenas para o migrator e apenas para `ip_hash`/`user_agent_hash` | OK |
| Rotinas de cron não são executáveis pela aplicação | OK |
| Leitura operacional vê o catálogo mascarado e não acessa tabela base | OK |
| Anonimização substitui dados, encerra a conta e registra em `expurgo_log` | OK |

O teste não cobre, ainda, concorrência com duas conexões simultâneas, expurgo por tempo, restauração de backup e rotação de chave — esses cenários estão especificados em [04 — Migração e operação](04-migracao-e-operacao.md) e pertencem à fase de homologação.

O script usado nessa validação está em [`sql/teste_funcional.sql`](sql/teste_funcional.sql). Ele exige banco vazio e **não deve ser executado em produção**.

## Resumo das decisões que precisam de aprovação

1. **Plano e região:** Supabase Pro em `sa-east-1` (São Paulo), com projetos separados por ambiente.
2. **Conexão:** Supavisor em modo sessão, TLS `verify-full`, pool dimensionado para o plano.
3. **Schema:** schema `elovital`, separado de `public` e de `extensions`; Data API desabilitada.
4. **Papéis:** `elovital_migrator`, `elovital_app`, `elovital_readonly`; `anon` e `authenticated` sem privilégios.
5. **RLS:** habilitada como defesa em profundidade; autorização de linha nos services.
6. **Criptografia:** CPF com HMAC para unicidade + cifra; telefone e contato de emergência cifrados; documentos em bucket privado.
7. **Autenticação:** continua no Django; Supabase Auth fica fora da primeira versão.
8. **Backup:** backup diário e PITR de 7 dias; teste de restauração trimestral.
9. **Retenção:** prazos da seção 7 da [Segurança e LGPD](03-seguranca-lgpd.md), com revisão jurídica.
10. **Documentos:** Supabase Storage privado, expurgo 90 dias após a verificação.

## Como aprovar

1. Leia os cinco documentos na ordem acima.
2. Marque cada item no [checklist](05-checklist-aprovacao.md).
3. Registre nome, data e eventuais ressalvas no fim do checklist.
4. Depois do aceite, a [ADR 0004](../../adr/0004-supabase-postgresql.md) passa de `Proposto` para `Aceito`, e as migrations e os testes de segurança podem ser implementados.

## Fora do escopo desta proposta

- Contratar o plano ou criar o projeto no Supabase.
- Escrever as migrations Django definitivas.
- Migrar dados reais de produção (não existe produção ainda).
- Revisão jurídica formal do contrato, DPA e política de privacidade.
- Escolher provedor de e-mail, mapas e notificações push.
- Definir preços, metas comerciais ou indicadores de produto.
