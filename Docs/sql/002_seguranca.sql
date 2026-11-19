-- =============================================================================
-- EloVital — 002_seguranca.sql
-- Papéis, privilégios, RLS, views mascaradas e triggers de proteção.
--
-- Status: RASCUNHO PARA APROVAÇÃO.
-- Executar depois de 001_schema.sql e antes de 003_funcoes.sql.
--
-- Princípio: RLS é defesa em profundidade. A autorização principal fica nos
-- services (ADR 0001). O papel da aplicação tem acesso amplo por design, então
-- a RLS não substitui teste de permissão.
-- =============================================================================

begin;

set local search_path = elovital, public, extensions;

-- -----------------------------------------------------------------------------
-- 1. Papéis
-- -----------------------------------------------------------------------------
-- As senhas NÃO ficam neste arquivo. Elas são definidas no painel/cofre:
--   alter role elovital_app password '<segredo>';
-- e rotacionadas conforme 01-configuracao.md.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'elovital_migrator') then
        execute 'create role elovital_migrator login noinherit';
    end if;
    if not exists (select 1 from pg_roles where rolname = 'elovital_app') then
        execute 'create role elovital_app login noinherit';
    end if;
    if not exists (select 1 from pg_roles where rolname = 'elovital_readonly') then
        execute 'create role elovital_readonly login noinherit';
    end if;
end $$;

-- Limites de sessão da aplicação (espelham 01-configuracao.md).
alter role elovital_app set statement_timeout = '5s';
alter role elovital_app set lock_timeout = '3s';
alter role elovital_app set idle_in_transaction_session_timeout = '10s';
alter role elovital_app set search_path = elovital, public, extensions;
alter role elovital_app set row_security = on;
alter role elovital_app set timezone = 'America/Sao_Paulo';

alter role elovital_readonly set statement_timeout = '60s';
alter role elovital_readonly set lock_timeout = '5s';
alter role elovital_readonly set search_path = elovital, public, extensions;
alter role elovital_readonly set timezone = 'America/Sao_Paulo';

alter role elovital_migrator set lock_timeout = '10s';
alter role elovital_migrator set search_path = elovital, public, extensions;
alter role elovital_migrator set timezone = 'America/Sao_Paulo';

-- -----------------------------------------------------------------------------
-- 2. Propriedade do schema
-- -----------------------------------------------------------------------------
-- O papel que executa este arquivo precisa poder criar roles e transferir
-- propriedade (superuser ou papel administrativo do Supabase). O bloco garante
-- que ele seja membro de elovital_migrator, o que também permite conceder
-- privilégios em nome do dono.
do $$
begin
    if current_user <> 'elovital_migrator' then
        execute format('grant elovital_migrator to %I', current_user);
    end if;
end $$;

alter schema elovital owner to elovital_migrator;

do $$
declare
    r record;
begin
    for r in select tablename from pg_tables where schemaname = 'elovital' loop
        execute format('alter table elovital.%I owner to elovital_migrator', r.tablename);
    end loop;
    for r in select sequencename from pg_sequences where schemaname = 'elovital' loop
        execute format('alter sequence elovital.%I owner to elovital_migrator', r.sequencename);
    end loop;
end $$;

-- -----------------------------------------------------------------------------
-- 3. Fechamento do schema
-- -----------------------------------------------------------------------------
revoke all on schema elovital from public;
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on schema elovital from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on schema elovital from authenticated';
    end if;
end $$;

grant usage on schema elovital to elovital_app, elovital_readonly;
grant usage on schema extensions to elovital_migrator, elovital_app, elovital_readonly;

-- app: leitura e escrita; sem DELETE em nenhuma tabela.
grant select, insert, update on all tables in schema elovital to elovital_app;
grant usage, select on all sequences in schema elovital to elovital_app;

-- Logs são somente inserção.
revoke update on elovital.audit_log, elovital.acesso_dado_pessoal, elovital.expurgo_log
    from elovital_app;

-- readonly não lê tabela base.
revoke all on all tables in schema elovital from elovital_readonly;

-- -----------------------------------------------------------------------------
-- 4. Funções de contexto
-- -----------------------------------------------------------------------------
-- O contexto é definido pela aplicação no início de uma transação:
--   select elovital.definir_contexto('<uuid>', 'assistido');
-- e dura apenas a transação (set_config com is_local = true).
-- Sem contexto, as políticas de linha negam.

create or replace function elovital.definir_contexto(p_usuario_id uuid, p_papel text)
returns void
language plpgsql
as $$
begin
    perform set_config('elovital.usuario_id', coalesce(p_usuario_id::text, ''), true);
    perform set_config('elovital.papel', coalesce(p_papel, ''), true);
end $$;

comment on function elovital.definir_contexto(uuid, text) is
    'Contexto transaction-local para RLS. Não persiste entre requisições.';

create or replace function elovital.usuario_atual()
returns uuid
language sql
stable
as $$
    select nullif(current_setting('elovital.usuario_id', true), '')::uuid
$$;

create or replace function elovital.papel_atual()
returns text
language sql
stable
as $$
    select nullif(current_setting('elovital.papel', true), '')
$$;

create or replace function elovital.e_mediador()
returns boolean
language sql
stable
as $$
    select coalesce(elovital.papel_atual() = 'mediador', false)
$$;

-- Helpers de autorização usados em políticas. São security definer para evitar
-- recursão de RLS e retornam apenas boolean; não expõem dados.
create or replace function elovital.dono_da_solicitacao(p_solicitacao_id uuid)
returns boolean
language sql
stable
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
    select exists (
        select 1 from elovital.solicitacao s
        where s.id = p_solicitacao_id
          and s.assistido_id = elovital.usuario_atual()
    )
$$;

create or replace function elovital.parte_da_reserva(p_reserva_id uuid)
returns boolean
language sql
stable
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
    select exists (
        select 1 from elovital.reserva r
        where r.id = p_reserva_id
          and (r.assistido_id = elovital.usuario_atual()
               or r.apoiador_id = elovital.usuario_atual())
    )
$$;

revoke execute on function elovital.definir_contexto(uuid, text) from public;
revoke execute on function elovital.usuario_atual() from public;
revoke execute on function elovital.papel_atual() from public;
revoke execute on function elovital.e_mediador() from public;
revoke execute on function elovital.dono_da_solicitacao(uuid) from public;
revoke execute on function elovital.parte_da_reserva(uuid) from public;

grant execute on function elovital.definir_contexto(uuid, text) to elovital_app;
grant execute on function elovital.usuario_atual() to elovital_app;
grant execute on function elovital.papel_atual() to elovital_app;
grant execute on function elovital.e_mediador() to elovital_app;
grant execute on function elovital.dono_da_solicitacao(uuid) to elovital_app;
grant execute on function elovital.parte_da_reserva(uuid) to elovital_app;

-- -----------------------------------------------------------------------------
-- 5. RLS: habilitação e política do migrator
-- -----------------------------------------------------------------------------
-- FORCE submete também o dono à RLS. Para que migrations e rotinas
-- administrativas continuem funcionando, o migrator recebe uma política
-- permissiva explícita. A aplicação NÃO tem essa política.
do $$
declare
    r record;
begin
    for r in select tablename from pg_tables where schemaname = 'elovital' loop
        execute format('alter table elovital.%I enable row level security', r.tablename);
        execute format('alter table elovital.%I force row level security', r.tablename);
        execute format(
            'create policy %I on elovital.%I for all to elovital_migrator using (true) with check (true)',
            r.tablename || '_migrator', r.tablename
        );
    end loop;
end $$;

-- -----------------------------------------------------------------------------
-- 6. Políticas da aplicação
-- -----------------------------------------------------------------------------
-- Padrão: sem política = nega. A aplicação só acessa o que está listado.
-- Tabelas com escrita exclusiva por função (reserva, confirmação, logs de
-- auditoria) não têm política de INSERT/UPDATE para elovital_app.

-- 6.1 Conta e perfil ---------------------------------------------------------

create policy usuario_select on elovital.usuario
    for select to elovital_app
    using (
        elovital.e_mediador()
        or id = elovital.usuario_atual()
        or exists (
            select 1 from elovital.reserva r
            where r.status in ('ativa', 'concluida')
              and (
                  (r.assistido_id = elovital.usuario.id
                   and r.apoiador_id = elovital.usuario_atual())
                  or (r.apoiador_id = elovital.usuario.id
                      and r.assistido_id = elovital.usuario_atual())
              )
        )
    );

-- Cadastro acontece sem contexto; o service valida e cria a conta.
create policy usuario_insert on elovital.usuario
    for insert to elovital_app
    with check (true);

create policy usuario_update on elovital.usuario
    for update to elovital_app
    using (elovital.e_mediador() or id = elovital.usuario_atual())
    with check (elovital.e_mediador() or id = elovital.usuario_atual());

create policy endereco_select on elovital.endereco
    for select to elovital_app
    using (
        elovital.e_mediador()
        or usuario_id = elovital.usuario_atual()
        or exists (
            select 1 from elovital.reserva r
            where r.apoiador_id = endereco.usuario_id
              and r.assistido_id = elovital.usuario_atual()
              and r.status in ('ativa', 'concluida')
        )
    );

create policy endereco_insert on elovital.endereco
    for insert to elovital_app
    with check (true);

create policy endereco_update on elovital.endereco
    for update to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual())
    with check (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy termo_select on elovital.termo_aceite
    for select to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy termo_insert on elovital.termo_aceite
    for insert to elovital_app
    with check (true);

-- Aceite não é editado; a revogação é uma atualização de revogado_em.
create policy termo_update on elovital.termo_aceite
    for update to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual())
    with check (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy perfil_assistido_select on elovital.perfil_assistido
    for select to elovital_app
    using (
        elovital.e_mediador()
        or usuario_id = elovital.usuario_atual()
        or exists (
            select 1 from elovital.reserva r
            where r.assistido_id = perfil_assistido.usuario_id
              and r.apoiador_id = elovital.usuario_atual()
              and r.status in ('ativa', 'concluida')
        )
    );

create policy perfil_assistido_insert on elovital.perfil_assistido
    for insert to elovital_app
    with check (true);

create policy perfil_assistido_update on elovital.perfil_assistido
    for update to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual())
    with check (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy perfil_apoiador_select on elovital.perfil_apoiador
    for select to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy perfil_apoiador_insert on elovital.perfil_apoiador
    for insert to elovital_app
    with check (true);

create policy perfil_apoiador_update on elovital.perfil_apoiador
    for update to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual())
    with check (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy documento_select on elovital.documento_verificacao
    for select to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy documento_insert on elovital.documento_verificacao
    for insert to elovital_app
    with check (true);

create policy documento_update on elovital.documento_verificacao
    for update to elovital_app
    using (elovital.e_mediador())
    with check (elovital.e_mediador());

create policy usuario_condicao_select on elovital.usuario_condicao
    for select to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy usuario_condicao_insert on elovital.usuario_condicao
    for insert to elovital_app
    with check (true);

create policy usuario_condicao_update on elovital.usuario_condicao
    for update to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual())
    with check (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy usuario_especialidade_select on elovital.usuario_especialidade
    for select to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy usuario_especialidade_insert on elovital.usuario_especialidade
    for insert to elovital_app
    with check (true);

create policy usuario_especialidade_update on elovital.usuario_especialidade
    for update to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual())
    with check (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

-- 6.2 Catálogos e parâmetros ------------------------------------------------

create policy condicao_select on elovital.condicao_cronica
    for select to elovital_app using (true);

create policy condicao_write on elovital.condicao_cronica
    for all to elovital_app
    using (elovital.e_mediador())
    with check (elovital.e_mediador());

create policy especialidade_select on elovital.especialidade
    for select to elovital_app using (true);

create policy especialidade_write on elovital.especialidade
    for all to elovital_app
    using (elovital.e_mediador())
    with check (elovital.e_mediador());

create policy parametro_select on elovital.parametro
    for select to elovital_app using (true);

create policy parametro_write on elovital.parametro
    for all to elovital_app
    using (elovital.e_mediador())
    with check (elovital.e_mediador());

-- 6.3 Agenda ----------------------------------------------------------------

create policy recorrencia_select on elovital.recorrencia
    for select to elovital_app
    using (elovital.e_mediador() or apoiador_id = elovital.usuario_atual());

create policy recorrencia_write on elovital.recorrencia
    for all to elovital_app
    using (elovital.e_mediador() or apoiador_id = elovital.usuario_atual())
    with check (elovital.e_mediador() or apoiador_id = elovital.usuario_atual());

-- Horário disponível é público para o catálogo; o restante é do Apoiador.
create policy horario_select on elovital.horario
    for select to elovital_app
    using (
        elovital.e_mediador()
        or apoiador_id = elovital.usuario_atual()
        or status = 'disponivel'
    );

create policy horario_insert on elovital.horario
    for insert to elovital_app
    with check (elovital.e_mediador() or apoiador_id = elovital.usuario_atual());

create policy horario_update on elovital.horario
    for update to elovital_app
    using (elovital.e_mediador() or apoiador_id = elovital.usuario_atual())
    with check (elovital.e_mediador() or apoiador_id = elovital.usuario_atual());

-- 6.4 Solicitações ----------------------------------------------------------

create policy solicitacao_select on elovital.solicitacao
    for select to elovital_app
    using (
        elovital.e_mediador()
        or assistido_id = elovital.usuario_atual()
        or exists (
            select 1 from elovital.reserva r
            where r.solicitacao_id = solicitacao.id
              and r.apoiador_id = elovital.usuario_atual()
              and r.status in ('ativa', 'concluida')
        )
    );

create policy solicitacao_insert on elovital.solicitacao
    for insert to elovital_app
    with check (elovital.e_mediador() or assistido_id = elovital.usuario_atual());

create policy solicitacao_update on elovital.solicitacao
    for update to elovital_app
    using (elovital.e_mediador() or assistido_id = elovital.usuario_atual())
    with check (elovital.e_mediador() or assistido_id = elovital.usuario_atual());

create policy solicitacao_periodo_select on elovital.solicitacao_periodo
    for select to elovital_app
    using (elovital.e_mediador() or elovital.dono_da_solicitacao(solicitacao_id));

create policy solicitacao_periodo_write on elovital.solicitacao_periodo
    for all to elovital_app
    using (elovital.e_mediador() or elovital.dono_da_solicitacao(solicitacao_id))
    with check (elovital.e_mediador() or elovital.dono_da_solicitacao(solicitacao_id));

-- A análise é escrita pela mediação e lida pelo dono da solicitação.
create policy analise_select on elovital.analise_solicitacao
    for select to elovital_app
    using (elovital.e_mediador() or elovital.dono_da_solicitacao(solicitacao_id));

create policy analise_insert on elovital.analise_solicitacao
    for insert to elovital_app
    with check (elovital.e_mediador());

-- 6.5 Reservas --------------------------------------------------------------
-- Escrita somente pelas funções de 003 (security definer). A aplicação lê.

create policy reserva_select on elovital.reserva
    for select to elovital_app
    using (
        elovital.e_mediador()
        or assistido_id = elovital.usuario_atual()
        or apoiador_id = elovital.usuario_atual()
    );

create policy reserva_confirmacao_select on elovital.reserva_confirmacao
    for select to elovital_app
    using (elovital.e_mediador() or elovital.parte_da_reserva(reserva_id));

-- 6.6 Comunicação e reputação ----------------------------------------------

create policy mensagem_select on elovital.mensagem
    for select to elovital_app
    using (elovital.e_mediador() or elovital.parte_da_reserva(reserva_id));

create policy mensagem_insert on elovital.mensagem
    for insert to elovital_app
    with check (autor_id = elovital.usuario_atual() and elovital.parte_da_reserva(reserva_id));

create policy mensagem_update on elovital.mensagem
    for update to elovital_app
    using (elovital.e_mediador() or elovital.parte_da_reserva(reserva_id))
    with check (elovital.e_mediador() or elovital.parte_da_reserva(reserva_id));

create policy bloqueio_select on elovital.bloqueio_usuario
    for select to elovital_app
    using (elovital.e_mediador() or bloqueador_id = elovital.usuario_atual());

create policy bloqueio_insert on elovital.bloqueio_usuario
    for insert to elovital_app
    with check (bloqueador_id = elovital.usuario_atual());

create policy avaliacao_select on elovital.avaliacao
    for select to elovital_app using (true);

create policy avaliacao_insert on elovital.avaliacao
    for insert to elovital_app
    with check (autor_id = elovital.usuario_atual() and elovital.parte_da_reserva(reserva_id));

-- Apenas a mediação altera oculta/oculta_motivo; nota e comentário são imutáveis.
create policy avaliacao_update on elovital.avaliacao
    for update to elovital_app
    using (elovital.e_mediador())
    with check (elovital.e_mediador());

create policy notificacao_select on elovital.notificacao
    for select to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy notificacao_insert on elovital.notificacao
    for insert to elovital_app
    with check (true);

create policy notificacao_update on elovital.notificacao
    for update to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual())
    with check (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy ocorrencia_select on elovital.ocorrencia
    for select to elovital_app
    using (
        elovital.e_mediador()
        or aberta_por = elovital.usuario_atual()
        or (reserva_id is not null and elovital.parte_da_reserva(reserva_id))
        or (solicitacao_id is not null and elovital.dono_da_solicitacao(solicitacao_id))
    );

create policy ocorrencia_insert on elovital.ocorrencia
    for insert to elovital_app
    with check (aberta_por = elovital.usuario_atual() or elovital.e_mediador());

create policy ocorrencia_update on elovital.ocorrencia
    for update to elovital_app
    using (elovital.e_mediador())
    with check (elovital.e_mediador());

-- 6.7 Moderação -------------------------------------------------------------

-- O denunciado não vê a denúncia; o denunciante vê a própria.
create policy denuncia_select on elovital.denuncia
    for select to elovital_app
    using (elovital.e_mediador() or denunciante_id = elovital.usuario_atual());

create policy denuncia_insert on elovital.denuncia
    for insert to elovital_app
    with check (denunciante_id = elovital.usuario_atual());

create policy denuncia_update on elovital.denuncia
    for update to elovital_app
    using (elovital.e_mediador())
    with check (elovital.e_mediador());

create policy sancao_select on elovital.sancao
    for select to elovital_app
    using (elovital.e_mediador() or usuario_id = elovital.usuario_atual());

create policy sancao_insert on elovital.sancao
    for insert to elovital_app
    with check (elovital.e_mediador());

create policy contestacao_select on elovital.contestacao
    for select to elovital_app
    using (elovital.e_mediador() or contestante_id = elovital.usuario_atual());

create policy contestacao_insert on elovital.contestacao
    for insert to elovital_app
    with check (contestante_id = elovital.usuario_atual());

create policy contestacao_update on elovital.contestacao
    for update to elovital_app
    using (elovital.e_mediador())
    with check (elovital.e_mediador());

-- 6.8 Operação e auditoria --------------------------------------------------
-- tentativa_login é escrita antes do login, sem contexto.

create policy tentativa_login_insert on elovital.tentativa_login
    for insert to elovital_app with check (true);

create policy tentativa_login_select on elovital.tentativa_login
    for select to elovital_app using (true);

create policy audit_log_insert on elovital.audit_log
    for insert to elovital_app with check (true);

create policy audit_log_select on elovital.audit_log
    for select to elovital_app using (elovital.e_mediador());

create policy acesso_dado_pessoal_insert on elovital.acesso_dado_pessoal
    for insert to elovital_app with check (true);

create policy acesso_dado_pessoal_select on elovital.acesso_dado_pessoal
    for select to elovital_app using (elovital.e_mediador());

create policy expurgo_log_insert on elovital.expurgo_log
    for insert to elovital_app with check (true);

create policy expurgo_log_select on elovital.expurgo_log
    for select to elovital_app using (elovital.e_mediador());

-- -----------------------------------------------------------------------------
-- 7. Views mascaradas
-- -----------------------------------------------------------------------------
-- Views rodam como o dono (migrator). A RLS do migrator é permissiva, então a
-- proteção aqui é a lista de colunas e o filtro da própria view.
-- NÃO adicionar coluna sensível sem revisão de privacidade.

create or replace view elovital.v_catalogo_apoiador as
select
    u.id,
    split_part(u.nome_completo, ' ', 1) as primeiro_nome,
    u.foto_path,
    pa.modalidade,
    pa.raio_km,
    e.bairro,
    e.municipio,
    e.uf,
    st_snaptogrid(
        e.localizacao::geometry,
        0, 0, 0.01, 0.01
    )::geography as ponto_aproximado,
    coalesce((
        select round(avg(a.nota)::numeric, 2)
        from elovital.avaliacao a
        where a.alvo_id = u.id and not a.oculta
    ), 0) as nota_media,
    (
        select count(*)
        from elovital.avaliacao a
        where a.alvo_id = u.id and not a.oculta
    ) as total_avaliacoes,
    (
        select count(*)
        from elovital.reserva r
        where r.apoiador_id = u.id and r.status = 'concluida'
    ) as apoios_concluidos,
    (
        select coalesce(array_agg(esp.nome order by esp.nome), '{}')
        from elovital.usuario_especialidade ue
        join elovital.especialidade esp on esp.id = ue.especialidade_id
        where ue.usuario_id = u.id and esp.ativo
    ) as especialidades,
    exists (
        select 1 from elovital.documento_verificacao d
        where d.usuario_id = u.id and d.tipo = 'documento' and d.status = 'aprovado'
    ) as verificado
from elovital.usuario u
join elovital.perfil_apoiador pa on pa.usuario_id = u.id
join elovital.endereco e on e.usuario_id = u.id and e.principal
where u.papel = 'apoiador'
  and u.status = 'ativo'
  and u.is_active
  and u.anonimizado_em is null
  and exists (
      select 1 from elovital.horario h
      where h.apoiador_id = u.id and h.status = 'disponivel' and h.data >= current_date
  );

comment on view elovital.v_catalogo_apoiador is
    'Catálogo público (RF007). Sem sobrenome, CPF, telefone, e-mail, endereço exato, condição ou observações.';

create or replace view elovital.v_usuario_operacional as
select
    u.id,
    split_part(u.nome_completo, ' ', 1)
        || ' ' || left(coalesce(split_part(u.nome_completo, ' ', 2), ''), 1) || '.' as nome_mascarado,
    regexp_replace(u.email::text, '^(.).*(@.*)$', '\1***\2') as email_mascarado,
    '***.***.***-**'::text as cpf_mascarado,
    u.papel,
    u.status,
    u.criado_em,
    u.atualizado_em
from elovital.usuario u;

comment on view elovital.v_usuario_operacional is
    'Leitura operacional mascarada. Sem CPF, telefone, e-mail completo ou endereço.';

create or replace view elovital.v_solicitacao_operacional as
select
    s.id,
    s.tipo_apoio_id,
    s.status,
    s.prioridade_final,
    s.modalidade,
    s.criado_em,
    s.expira_em,
    s.concluida_em
from elovital.solicitacao s;

comment on view elovital.v_solicitacao_operacional is
    'Métricas e fila sem título, descrição ou dado de saúde.';

grant select on elovital.v_catalogo_apoiador to elovital_app, elovital_readonly;
grant select on elovital.v_usuario_operacional to elovital_app, elovital_readonly;
grant select on elovital.v_solicitacao_operacional to elovital_app, elovital_readonly;

-- -----------------------------------------------------------------------------
-- 8. Imutabilidade e proteções por trigger
-- -----------------------------------------------------------------------------

create or replace function elovital.proteger_auditoria()
returns trigger
language plpgsql
as $$
begin
    -- Exceção estreita: o migrator pode zerar IP e user agent após o prazo do
    -- Marco Civil, sem alterar nenhum outro campo.
    if tg_op = 'UPDATE'
       and current_user = 'elovital_migrator'
       and new.ip_hash is null
       and new.user_agent_hash is null
       and (to_jsonb(new) - 'ip_hash' - 'user_agent_hash')
           = (to_jsonb(old) - 'ip_hash' - 'user_agent_hash') then
        return new;
    end if;

    raise exception 'Tabela % é somente inserção', tg_table_name
        using errcode = '42501',
              hint = 'UPDATE e DELETE são proibidos em tabelas de auditoria.';
end $$;

comment on function elovital.proteger_auditoria() is
    'Bloqueia UPDATE e DELETE nas tabelas de auditoria, exceto a zeragem de IP pelo migrator.';

create trigger audit_log_imutavel
    before update or delete on elovital.audit_log
    for each row execute function elovital.proteger_auditoria();

create trigger acesso_dado_pessoal_imutavel
    before update or delete on elovital.acesso_dado_pessoal
    for each row execute function elovital.proteger_auditoria();

create trigger expurgo_log_imutavel
    before update or delete on elovital.expurgo_log
    for each row execute function elovital.proteger_auditoria();

-- CPF imutável para a aplicação. A anonimização roda como migrator (security
-- definer) e é a única operação autorizada a alterar os dois campos.
create or replace function elovital.proteger_cpf()
returns trigger
language plpgsql
as $$
begin
    if new.cpf_hash is distinct from old.cpf_hash
       or new.cpf_cifrado is distinct from old.cpf_cifrado then
        if current_user <> 'elovital_migrator' then
            raise exception 'CPF é imutável'
                using errcode = '42501';
        end if;
    end if;
    return new;
end $$;

create trigger usuario_cpf_imutavel
    before update of cpf_hash, cpf_cifrado on elovital.usuario
    for each row execute function elovital.proteger_cpf();

-- Avaliação: nota, comentário e vínculo não mudam; só ocultação.
create or replace function elovital.proteger_avaliacao()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'INSERT' then
        if not (elovital.e_mediador() or current_user = 'elovital_migrator') then
            new.oculta := false;
            new.oculta_por := null;
            new.oculta_motivo := null;
        end if;
        return new;
    end if;

    -- A anonimização roda como migrator (security definer) e pode limpar o
    -- comentário; a aplicação não pode editar a avaliação.
    if current_user <> 'elovital_migrator'
       and (
           new.nota is distinct from old.nota
           or new.comentario is distinct from old.comentario
           or new.reserva_id is distinct from old.reserva_id
           or new.autor_id is distinct from old.autor_id
           or new.alvo_id is distinct from old.alvo_id
       ) then
        raise exception 'Avaliação não pode ser editada'
            using errcode = '42501',
                  hint = 'A mediação pode ocultar o comentário, sem alterar a nota.';
    end if;
    return new;
end $$;

drop trigger if exists avaliacao_imutavel on elovital.avaliacao;
create trigger avaliacao_imutavel
    before insert or update on elovital.avaliacao
    for each row execute function elovital.proteger_avaliacao();

-- Mensagem: texto e autor não mudam; só leitura e ocultação.
create or replace function elovital.proteger_mensagem()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'INSERT' then
        if not (elovital.e_mediador() or current_user = 'elovital_migrator') then
            new.oculta := false;
            new.oculta_por := null;
            new.oculta_motivo := null;
        end if;
        return new;
    end if;

    -- A anonimização roda como migrator (security definer) e pode limpar o texto.
    if current_user <> 'elovital_migrator'
       and (
           new.texto is distinct from old.texto
           or new.autor_id is distinct from old.autor_id
           or new.reserva_id is distinct from old.reserva_id
           or new.enviada_em is distinct from old.enviada_em
       ) then
        raise exception 'Mensagem não pode ser editada'
            using errcode = '42501';
    end if;
    return new;
end $$;

drop trigger if exists mensagem_imutavel on elovital.mensagem;
create trigger mensagem_imutavel
    before insert or update on elovital.mensagem
    for each row execute function elovital.proteger_mensagem();

-- Campos de privilégio da conta: só mediação ou migrator alteram papel,
-- flags administrativas, maioridade e status fora da desativação voluntária.
create or replace function elovital.proteger_campos_usuario()
returns trigger
language plpgsql
as $$
declare
    v_privilegiado boolean := elovital.e_mediador() or current_user = 'elovital_migrator';
begin
    if tg_op = 'INSERT' then
        if not v_privilegiado then
            if new.papel not in ('assistido', 'apoiador') then
                raise exception 'Papel inválido no cadastro' using errcode = '42501';
            end if;
            if new.is_staff or new.is_superuser then
                raise exception 'Flag administrativa não pode ser definida no cadastro'
                    using errcode = '42501';
            end if;
            if new.status <> 'em_analise' then
                raise exception 'Conta nova deve nascer em análise' using errcode = '42501';
            end if;
        end if;
        return new;
    end if;

    if not v_privilegiado then
        if new.papel is distinct from old.papel
           or new.is_staff is distinct from old.is_staff
           or new.is_superuser is distinct from old.is_superuser
           or new.data_nascimento is distinct from old.data_nascimento then
            raise exception 'Campo protegido da conta' using errcode = '42501';
        end if;
        if new.status is distinct from old.status
           and not (old.status = 'ativo' and new.status = 'desativado') then
            raise exception 'Mudança de status não permitida' using errcode = '42501';
        end if;
    end if;
    return new;
end $$;

drop trigger if exists usuario_campos_protegidos on elovital.usuario;
create trigger usuario_campos_protegidos
    before insert or update on elovital.usuario
    for each row execute function elovital.proteger_campos_usuario();

-- Verificação de especialidade: usuário não pode se declarar verificado.
create or replace function elovital.proteger_verificacao_especialidade()
returns trigger
language plpgsql
as $$
declare
    v_privilegiado boolean := elovital.e_mediador() or current_user = 'elovital_migrator';
begin
    if tg_op = 'INSERT' then
        if not v_privilegiado
           and (new.verificado_em is not null or new.verificado_por is not null) then
            raise exception 'Verificação só pode ser feita pela mediação' using errcode = '42501';
        end if;
        return new;
    end if;

    if not v_privilegiado then
        -- Editar o registro ou a especialidade invalida a verificação anterior.
        if new.registro_profissional is distinct from old.registro_profissional
           or new.especialidade_id is distinct from old.especialidade_id then
            new.verificado_em := null;
            new.verificado_por := null;
            return new;
        end if;

        if new.verificado_em is distinct from old.verificado_em
           or new.verificado_por is distinct from old.verificado_por then
            raise exception 'Verificação só pode ser feita pela mediação' using errcode = '42501';
        end if;
    end if;
    return new;
end $$;

drop trigger if exists usuario_especialidade_verificacao on elovital.usuario_especialidade;
create trigger usuario_especialidade_verificacao
    before insert or update on elovital.usuario_especialidade
    for each row execute function elovital.proteger_verificacao_especialidade();

-- Decisão de documento: usuário envia pendente; só a mediação aprova/reprova.
create or replace function elovital.proteger_decisao_documento()
returns trigger
language plpgsql
as $$
declare
    v_privilegiado boolean := elovital.e_mediador() or current_user = 'elovital_migrator';
begin
    if tg_op = 'INSERT' then
        if not v_privilegiado
           and (new.status <> 'pendente'
                or new.decidido_por is not null
                or new.decidido_em is not null) then
            raise exception 'Decisão de documento só pode ser feita pela mediação'
                using errcode = '42501';
        end if;
        return new;
    end if;

    if not v_privilegiado then
        if new.decidido_por is distinct from old.decidido_por
           or new.decidido_em is distinct from old.decidido_em then
            raise exception 'Decisão de documento só pode ser feita pela mediação'
                using errcode = '42501';
        end if;
        if new.status is distinct from old.status and new.status <> 'expirado' then
            raise exception 'Status de documento só muda pela mediação' using errcode = '42501';
        end if;
    end if;
    return new;
end $$;

drop trigger if exists documento_decisao_protegida on elovital.documento_verificacao;
create trigger documento_decisao_protegida
    before insert or update on elovital.documento_verificacao
    for each row execute function elovital.proteger_decisao_documento();

-- Sem exclusão física nas entidades centrais. TRUNCATE continua disponível
-- para o migrator em testes; a rotina normal é status + anonimização.
create or replace function elovital.proteger_exclusao()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Exclusão física não é permitida em %', tg_table_name
        using errcode = '42501',
              hint = 'Use status ou anonimização.';
end $$;

create trigger usuario_sem_exclusao
    before delete on elovital.usuario
    for each row execute function elovital.proteger_exclusao();

create trigger solicitacao_sem_exclusao
    before delete on elovital.solicitacao
    for each row execute function elovital.proteger_exclusao();

create trigger reserva_sem_exclusao
    before delete on elovital.reserva
    for each row execute function elovital.proteger_exclusao();

create trigger mensagem_sem_exclusao
    before delete on elovital.mensagem
    for each row execute function elovital.proteger_exclusao();

create trigger avaliacao_sem_exclusao
    before delete on elovital.avaliacao
    for each row execute function elovital.proteger_exclusao();

create trigger denuncia_sem_exclusao
    before delete on elovital.denuncia
    for each row execute function elovital.proteger_exclusao();

create trigger sancao_sem_exclusao
    before delete on elovital.sancao
    for each row execute function elovital.proteger_exclusao();

-- -----------------------------------------------------------------------------
-- 9. Default privileges para objetos futuros das migrations
-- -----------------------------------------------------------------------------

alter default privileges for role elovital_migrator in schema elovital
    revoke all on tables from public;
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'alter default privileges for role elovital_migrator in schema elovital revoke all on tables from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'alter default privileges for role elovital_migrator in schema elovital revoke all on tables from authenticated';
    end if;
end $$;
alter default privileges for role elovital_migrator in schema elovital
    grant select, insert, update on tables to elovital_app;
alter default privileges for role elovital_migrator in schema elovital
    grant usage, select on sequences to elovital_app;
alter default privileges for role elovital_migrator in schema elovital
    revoke execute on functions from public;
alter default privileges for role elovital_migrator in schema elovital
    grant execute on functions to elovital_app;

-- Propriedade das funções e views criadas neste arquivo.
do $$
declare
    r record;
begin
    for r in
        select p.oid::regprocedure as assinatura
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'elovital'
    loop
        execute format('alter function %s owner to elovital_migrator', r.assinatura);
    end loop;
    for r in
        select viewname from pg_views where schemaname = 'elovital'
    loop
        execute format('alter view elovital.%I owner to elovital_migrator', r.viewname);
    end loop;
end $$;

commit;
