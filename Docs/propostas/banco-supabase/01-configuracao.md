# 01 — Configuração e operação do banco

- **Status:** Rascunho para aprovação
- **Decisão relacionada:** [ADR 0004](../../adr/0004-supabase-postgresql.md)
- **Schema de referência:** [`sql/001_schema.sql`](sql/001_schema.sql)

## 1. Escopo e premissas

Esta proposta assume:

- aplicação Django monolítica, com views, services e ORM;
- PostgreSQL gerenciado pelo Supabase;
- autenticação e sessão no Django, não no Supabase Auth;
- migrations do Django como fonte de verdade do schema;
- nenhum acesso direto do navegador ao banco;
- documentos de verificação no Supabase Storage privado;
- região de São Paulo para produção.

O banco não é um serviço público. A aplicação é a única cliente do schema `elovital`.

## 2. Topologia de ambientes

| Ambiente | Projeto Supabase | Uso | Dados |
|---|---|---|---|
| Desenvolvimento | `elovital-dev` | Máquina local e testes de integração | Sintéticos, nunca dados reais |
| Homologação | `elovital-staging` | Teste de migrations, RLS e carga | Sintéticos ou anonimizados |
| Produção | `elovital-prod` | Usuários reais | Dados pessoais e sensíveis |

Regras:

- Um projeto por ambiente; nunca compartilhar o mesmo projeto entre produção e testes.
- Credenciais diferentes por ambiente.
- Produção não é usada para desenvolvimento, demonstração ou teste de migration destrutiva.
- O acesso ao painel do Supabase exige MFA para todas as pessoas da equipe.
- Apenas duas ou três pessoas têm acesso de owner; o restante usa papéis operacionais.

## 3. Região e plano

- **Região:** South America (São Paulo), `sa-east-1`.
- **Plano:** Pro ou superior, obrigatório para backup diário e PITR.
- **Compute:** dimensionar após teste de carga; começar pequeno e monitorar conexões, CPU, disco e latência.
- **PITR:** 7 dias, se disponível no plano; sem PITR, o RPO sobe para 24 horas.
- **Storage:** bucket privado, com limite e política de expurgo.
- **Rede:** conexão apenas TLS; nenhuma regra de IP aberta para o mundo sem necessidade.

A região precisa ser confirmada antes da criação do projeto. Trocar de região depois exige novo projeto e migração completa.

## 4. Conexão da aplicação

### 4.1 Modos de conexão

| Modo | Porta | Uso no EloVital | Observação |
|---|---|---|---|
| Pooler em modo sessão (Supavisor) | 5432 | **Padrão da aplicação Django** | Mantém estado de sessão; indicado para processo longo |
| Pooler em modo transação | 6543 | Cargas serverless ou jobs curtos, se existirem | Não suporta estado de sessão; exige cuidado com prepared statements |
| Conexão direta | 5432 | Migrations e operação, quando houver IPv6 | Não usar em pool de aplicação |

O Django é um processo longo, então o modo sessão é o padrão. O modo transação só entra se houver uma função serverless ou um job que abra muitas conexões curtas.

### 4.2 Configuração do Django

Exemplo de configuração, com valores fictícios:

```python
DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.postgresql",
        "NAME": "postgres",
        "USER": os.environ["DB_USER"],
        "PASSWORD": os.environ["DB_PASSWORD"],
        "HOST": os.environ["DB_HOST"],
        "PORT": os.environ.get("DB_PORT", "5432"),
        "CONN_MAX_AGE": 60,
        "CONN_HEALTH_CHECKS": True,
        "OPTIONS": {
            "sslmode": "verify-full",
            "sslrootcert": os.environ["DB_SSL_ROOT_CERT"],
            "connect_timeout": 10,
            "application_name": "elovital-web",
            "options": "-c search_path=elovital,public,extensions",
        },
    }
}
```

Regras:

- `CONN_MAX_AGE` entre 30 e 60 segundos; não usar conexão eterna.
- `CONN_HEALTH_CHECKS = True` para descartar conexões mortas.
- `DISABLE_SERVER_SIDE_CURSORS = True` apenas se o modo transação for usado.
- O `search_path` aponta para `elovital` antes de `public` e `extensions`.
- O fuso da sessão é `America/Sao_Paulo`; instantes são gravados em `timestamptz` e convertidos na exibição.
- O certificado CA do Supabase fica no servidor, com caminho configurável e rotação documentada.
- Nunca usar `sslmode=disable` ou `prefer` em produção.
- Não usar a chave `service_role` no navegador nem em código client-side.

### 4.3 Limites de sessão

Aplicar no papel da aplicação:

```sql
alter role elovital_app set statement_timeout = '5s';
alter role elovital_app set lock_timeout = '3s';
alter role elovital_app set idle_in_transaction_session_timeout = '10s';
alter role elovital_app set search_path = elovital, public, extensions;
alter role elovital_app set timezone = 'America/Sao_Paulo';
```

- `statement_timeout` evita consulta travada consumindo conexão.
- `lock_timeout` evita fila de espera em migration ou operação crítica.
- `idle_in_transaction_session_timeout` libera transação esquecida.
- Consultas analíticas e relatórios usam a role `elovital_readonly`, com timeout maior.

### 4.4 Dimensionamento do pool

- Número de conexões = instâncias web × workers × conexões por worker.
- Deixar margem para migrations, jobs e operação.
- Monitorar `pg_stat_activity` e o limite do pooler.
- Se o pool esgotar, enfileirar na aplicação em vez de abrir conexão sem limite.
- O modo transação permite mais clientes por conexão, mas quebra estado de sessão; não usar sem necessidade.

## 5. Variáveis de ambiente

Nomes previstos no `.env.example` (sem valores reais):

| Variável | Uso | Sensível |
|---|---|---|
| `DJANGO_SECRET_KEY` | Assinatura de sessão e CSRF | Sim |
| `DJANGO_DEBUG` | `False` fora do desenvolvimento | Não |
| `DJANGO_ALLOWED_HOSTS` | Domínios permitidos | Não |
| `DB_HOST` | Host do pooler | Não |
| `DB_PORT` | `5432` | Não |
| `DB_NAME` | `postgres` | Não |
| `DB_USER` | `elovital_app.<ref>` | Não |
| `DB_PASSWORD` | Senha rotacionável | Sim |
| `DB_SSL_ROOT_CERT` | Caminho do CA do Supabase | Não |
| `APP_ENCRYPTION_KEY` | Cifra de campos sensíveis | Sim |
| `CPF_HMAC_PEPPER` | HMAC do CPF | Sim |
| `SUPABASE_URL` | URL do projeto | Não |
| `SUPABASE_SERVICE_ROLE_KEY` | Operações server-side de Storage | Sim |
| `SUPABASE_STORAGE_BUCKET` | Bucket privado de documentos | Não |
| `EMAIL_*` | Provedor de e-mail | Sim |

Regras:

- Segredos ficam no cofre do ambiente, nunca no Git.
- Chaves diferentes por ambiente.
- Rotação documentada e testada.
- A chave `service_role` só é usada no servidor, para Storage; nunca vai para template, JavaScript ou aplicativo.
- A `anon key` não é necessária se a Data API estiver desabilitada. Se existir, não concede acesso aos dados.

## 6. Papéis e privilégios

| Papel | Login | Uso | Privilégios |
|---|---|---|---|
| `elovital_migrator` | Sim | Migrations e DDL | Dono do schema; usado apenas no deploy |
| `elovital_app` | Sim | Aplicação Django | DML nas tabelas necessárias; sem DDL; sem `DELETE` em auditoria |
| `elovital_readonly` | Sim | Métricas, suporte e relatórios | `SELECT` em views e tabelas permitidas |
| `anon` | Não | Papel do Supabase | Sem privilégio no schema `elovital` |
| `authenticated` | Não | Papel do Supabase | Sem privilégio no schema `elovital` |
| `service_role` | Sim | Operação de Storage e administração | Uso restrito ao servidor; nunca no cliente |

Regras:

- A aplicação não é dona das tabelas. O dono é o papel de migração.
- `elovital_app` recebe apenas os privilégios necessários, tabela por tabela.
- `elovital_app` não pode alterar `audit_log` nem `acesso_dado_pessoal`.
- `elovital_readonly` não vê colunas cifradas nem dados sensíveis; usa views mascaradas.
- `anon` e `authenticated` não recebem `USAGE` no schema `elovital`.
- `extensions` recebe `USAGE` para os três papéis do projeto, porque funções `security definer` e índices usam `digest`, `citext`, `geography` e `gin_trgm_ops`; isso não dá acesso a dados, apenas a tipos e funções.
- A Data API é desabilitada para o schema da aplicação; se não for possível desabilitar, revogar todos os privilégios e manter RLS sem políticas para esses papéis.
- A senha de cada papel é rotacionada em caso de suspeita e a cada saída de pessoa com acesso.

## 7. Schemas e extensões

- `elovital`: todas as tabelas do Django (domínio, auth, sessions, admin, migrations), views, funções e tipos da aplicação. O `search_path` da aplicação e das migrations começa aqui.
- `public`: vazio de dados do EloVital; nenhuma tabela de domínio. A Data API não recebe acesso ao schema `elovital`.
- `extensions`: extensões do PostgreSQL.
- `supabase_migrations`: controle interno do Supabase, se a CLI for usada.

Como o `search_path` começa em `elovital`, as tabelas internas do Django também ficam nesse schema. Elas não entram na contagem das 30 tabelas do documento 02 nem na modelagem de domínio.

Extensões previstas:

| Extensão | Uso |
|---|---|
| `postgis` | Distância entre endereços e filtro por raio |
| `pgcrypto` | Funções de apoio a hash e aleatoriedade |
| `citext` | E-mail sem diferenciar maiúsculas |
| `pg_trgm` | Busca textual por nome e descrição |
| `unaccent` | Busca sem acento |
| `pg_cron` | Expiração, lembretes e expurgo |
| `pgaudit` | Auditoria de comandos, se disponível |
| `pg_stat_statements` | Diagnóstico de consultas lentas |

Regras:

- Extensões ficam no schema `extensions`, não em `public`.
- `CREATE EXTENSION` é feito por migration controlada, não manualmente em produção.
- Antes de usar uma extensão, confirmar disponibilidade e impacto no plano.
- Funções `security definer` fixam `search_path` e ficam no schema `elovital`.

## 8. Migrations

- As migrations do Django são a fonte de verdade.
- A CLI do Supabase não cria tabelas de domínio; serve para o ambiente local e para o controle de versão do projeto.
- O deploy roda `python manage.py migrate` com o papel `elovital_migrator`.
- Antes de migration destrutiva: backup, revisão do SQL (`sqlmigrate`) e janela combinada.
- Não usar `--fake` em produção.
- Criar índices grandes com `AddIndexConcurrently` e `atomic = False`, quando necessário.
- Renomear coluna em duas etapas: adicionar, preencher, trocar o código, remover depois.
- Toda migration que cria tabela com dado pessoal inclui grants, comentários, política de retenção e **política de RLS explícita**; os `default privileges` cobrem apenas os grants, não a RLS.
- Toda tabela nova nasce com `enable row level security` e `force row level security`; tabela sem política para `elovital_app` é intencionalmente inacessível.
- O CI roda migrations do zero e os testes de RLS/permissão.

## 9. Backup, restauração e continuidade

- **Backup diário** automático no plano Pro.
- **PITR de 7 dias** como alvo recomendado; sem PITR, o RPO é de até 24 horas.
- **RPO desejado:** 5 minutos com PITR; 24 horas sem PITR.
- **RTO desejado:** 4 horas para restauração completa.
- **Teste de restauração:** trimestral, em projeto separado, com registro do resultado.
- **Backup lógico mensal:** `pg_dump` cifrado, guardado fora do Supabase, com retenção definida.
- **Backup não é arquivo morto:** dados expurgados por LGPD não voltam em restauração sem novo expurgo.
- **Plano de continuidade:** se o Supabase ficar indisponível, a aplicação mostra erro controlado, não perde a transação em andamento e não grava estado parcial.

## 10. Observabilidade e alertas

- Ativar logs do Postgres e do pooler, sem registrar dados pessoais.
- Monitorar: conexões ativas, tempo de consulta, locks, CPU, disco, erros 5xx, falhas de autenticação e crescimento de tabelas.
- Alertas mínimos: disco acima de 80%, conexões acima de 80% do limite, consulta acima de 5 segundos, falha de backup e falha de expurgo.
- `pg_stat_statements` para as consultas mais caras; revisar mensalmente.
- `pgaudit` para comandos administrativos, se disponível.
- Logs de aplicação não incluem CPF, telefone, endereço, condição de saúde, conteúdo de chat ou documento.
- Retenção de logs operacionais definida e compatível com a LGPD.

## 11. Desenvolvimento local e CI

- **Local:** Supabase CLI (`supabase start`) ou PostgreSQL 16 com PostGIS em Docker.
- **Seed:** dados sintéticos, com script versionado; nunca copiar produção.
- **Testes:** suíte Django + testes SQL de RLS, grants e funções.
- **CI:** banco efêmero, migrations do zero, testes de concorrência da reserva e testes de permissão.
- **Paridade:** mesma versão principal do PostgreSQL e mesmas extensões do ambiente gerenciado.
- **Segredos:** CI usa segredos do pipeline, não arquivo versionado.

## 12. Limites, capacidade e custos

- O limite de conexões varia com o compute; medir antes de aumentar workers.
- Storage e tráfego de documentos crescem com o número de verificações; o expurgo reduz o acumulado.
- PITR e compute são os maiores custos variáveis; revisar trimestralmente.
- A capacidade inicial deve suportar o piloto e o primeiro ano, com margem de 40%.
- Escalar verticalmente antes de sharding; o EloVital não precisa de sharding na primeira versão.
- Registrar custo mensal e alerta de orçamento no painel do provedor.

## 13. Checklist técnico desta etapa

- [ ] Projeto `elovital-prod` criado em `sa-east-1`.
- [ ] Projetos `dev` e `staging` separados.
- [ ] Plano Pro com backup diário e PITR contratados.
- [ ] Papéis `elovital_migrator`, `elovital_app` e `elovital_readonly` criados.
- [ ] `anon` e `authenticated` sem privilégios no schema `elovital`.
- [ ] Data API desabilitada para o schema da aplicação.
- [ ] Pooler em modo sessão configurado.
- [ ] TLS `verify-full` testado.
- [ ] `statement_timeout`, `lock_timeout` e `idle_in_transaction_session_timeout` aplicados.
- [ ] Extensões instaladas por migration.
- [ ] Rotina de backup e teste de restauração agendados.
- [ ] Alertas de disco, conexão, consulta lenta e backup configurados.
- [ ] Segredos no cofre e rotação documentada.
- [ ] Ambiente local e CI reproduzindo o schema.
