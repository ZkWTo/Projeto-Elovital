# ADR 0004: Supabase (PostgreSQL) como banco de dados

- **Status:** Proposto
- **Data:** 2026-11-16
- **PRD:** [PRD 0001](../prd/0001-elovital.md)
- **Proposta detalhada:** [Banco Supabase](../propostas/banco-supabase/README.md)
- **Substitui:** decisão de SQLite do projeto de referência ([ADR 0004 do labiv20262](../../../Modelo%20Wagner/labiv20262/docs/adr/0004-sqlite-como-banco-de-dados.md))

## Contexto

O projeto de referência usa SQLite, adequado para desenvolvimento e para uma primeira versão com poucas escritas simultâneas. O EloVital tem requisitos que o SQLite não atende bem:

- reserva atômica de horário, com garantia de que duas pessoas não reservem o mesmo horário (RF008 e RNF006);
- disponibilidade de 99% ao mês e operação 24 horas (RNF005);
- auditoria de ações críticas e retenção de registros (RNF012);
- dados pessoais sensíveis de saúde e documentos de identidade (RNF011 e RS001);
- backup automático com teste de restauração (RNF008);
- consultas por especialidade, data e distância, com índices e paginação (RNF009);
- crescimento de usuários, horários e solicitações sem reescrita.

O SQLite permite apenas uma escrita por vez e depende de um arquivo local, o que não combina com uma aplicação web de varias instâncias, backup gerenciado e recuperação point-in-time.

As alternativas consideradas foram: PostgreSQL gerenciado no Supabase, PostgreSQL gerenciado em outro provedor (RDS, Cloud SQL, Neon) e PostgreSQL autogerenciado. O Supabase foi escolhido por reunir PostgreSQL, backup, painel operacional, Storage privado para documentos e região em São Paulo, com custo inicial previsível.

## Decisão

- Usar **PostgreSQL gerenciado pelo Supabase**, na região **South America (São Paulo), `sa-east-1`**, com projetos separados para desenvolvimento, homologação e produção.
- Continuar usando o **ORM e as migrations do Django** como fonte de verdade do schema. O Supabase não substitui o Django nem cria tabelas por fora das migrations.
- Conectar a aplicação pelo **Supavisor em modo sessão (porta 5432)**, com TLS `verify-full` e o certificado CA do Supabase. O modo transação (porta 6543) fica reservado para cargas serverless, se existirem.
- Criar papéis separados para migração, aplicação e leitura operacional; a aplicação não tem permissão de DDL e não usa a chave `service_role` no navegador.
- Desabilitar o acesso público à Data API para o schema da aplicação e revogar privilégios de `anon` e `authenticated`. A autorização de linha fica nos services; a RLS é defesa em profundidade para papéis de API, conforme a proposta.
- Usar **Supabase Storage** apenas para foto de perfil e documentos de verificação, em bucket privado, com URL assinada de curta duração e expurgo programado. O conteúdo textual continua no PostgreSQL, em texto puro ([ADR 0003](0003-conteudo-em-texto-puro.md)).
- Manter a **autenticação do Django** na primeira versão; não usar Supabase Auth ([ADR 0002](0002-usuario-papeis-e-autenticacao.md)).
- Usar as extensões `postgis` para distância, `pgcrypto` para funções de apoio, `pg_trgm` e `unaccent` para busca, `pg_cron` para rotinas de expiração e `pgaudit`/logs para auditoria, conforme disponibilidade no plano.
- Contratar plano **Pro** (ou superior) para backup diário e PITR; sem backup gerenciado, a decisão não atende ao RNF008.
- Guardar credenciais apenas em variáveis de ambiente ou cofre de segredos; nunca no Git.

## Consequências

- A aplicação passa a depender da rede e do Supabase; indisponibilidade do provedor afeta o EloVital, o que precisa entrar no plano de continuidade.
- O custo mensal deixa de ser zero e passa a incluir plano, backup/PITR, armazenamento e tráfego.
- O limite de conexões exige pooler e configuração de `CONN_MAX_AGE`; sem isso, picos de acesso esgotam conexões.
- O vendor lock-in é reduzido porque o contrato é PostgreSQL padrão e o Django usa ORM; funções específicas de Supabase (Storage, RLS, extensões) exigem adaptação se houver troca.
- A RLS não substitui a autorização dos services: o papel da aplicação tem acesso amplo por design, e os testes de permissão continuam obrigatórios.
- Migrations destrutivas exigem janela e backup prévio; o processo precisa de revisão de SQL e teste de restauração.
- O ambiente local passa a usar Supabase CLI/Docker ou PostgreSQL com PostGIS, para não depender de produção.
- A decisão só vale depois da aprovação do pacote [Proposta do banco Supabase](../propostas/banco-supabase/README.md), incluindo região, plano, retenção e revisão jurídica da LGPD.
