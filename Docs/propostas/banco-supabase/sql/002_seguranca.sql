-- =============================================================================
-- EloVital — 002_seguranca.sql
-- Contas de banco, privilégios, view pública e triggers de proteção.
--
-- Status: RASCUNHO PARA APROVAÇÃO.
-- Executar depois de 001_schema.sql e antes de 003_funcoes.sql.
--
-- Escopo: material de documentação. A autorização por linha (quem vê o quê)
-- fica nos services da aplicação (ADR 0001); o banco garante aqui apenas
-- privilégio de papel, leitura mascarada e imutabilidade do que é histórico.
-- =============================================================================

begin;

set local search_path = elovital, public, extensions;

-- -----------------------------------------------------------------------------
-- 1. Contas de banco
-- -----------------------------------------------------------------------------
-- As senhas NÃO ficam neste arquivo. São definidas no cofre/painel:
--   alter role elovital_app password '<segredo>';
-- Papéis:
--   elovital_migrator — papel administrativo, usado por migrations.
--   elovital_app      — aplicação: lê e escreve, nunca apaga.
--   elovital_readonly — relatórios: lê somente as views.

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

alter role elovital_app set search_path = elovital, public, extensions;
alter role elovital_app set statement_timeout = '5s';
alter role elovital_app set lock_timeout = '3s';
alter role elovital_app set timezone = 'America/Sao_Paulo';

alter role elovital_readonly set search_path = elovital, public, extensions;
alter role elovital_readonly set statement_timeout = '60s';
alter role elovital_readonly set timezone = 'America/Sao_Paulo';

alter role elovital_migrator set search_path = elovital, public, extensions;
alter role elovital_migrator set lock_timeout = '10s';
alter role elovital_migrator set timezone = 'America/Sao_Paulo';

-- -----------------------------------------------------------------------------
-- 2. Privilégios
-- -----------------------------------------------------------------------------
revoke all on schema elovital from public;
grant usage on schema elovital to elovital_app, elovital_readonly;
grant usage on schema extensions to elovital_app, elovital_readonly;

-- Aplicação: SELECT, INSERT e UPDATE em tudo. DELETE não é concedido.
grant select, insert, update on all tables in schema elovital to elovital_app;
grant usage, select on all sequences in schema elovital to elovital_app;

-- Trilhas de auditoria aceitam apenas INSERT.
revoke update on elovital.audit_log, elovital.acesso_dado_pessoal, elovital.expurgo_log
    from elovital_app;

-- Relatórios não leem tabela base; o acesso vem da view da seção 3.
revoke all on all tables in schema elovital from elovital_readonly;

-- Supabase: fecha os papéis padrão, quando existirem.
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on schema elovital from anon';
        execute 'revoke all on all tables in schema elovital from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on schema elovital from authenticated';
        execute 'revoke all on all tables in schema elovital from authenticated';
    end if;
end $$;

-- -----------------------------------------------------------------------------
-- 3. View pública do catálogo
-- -----------------------------------------------------------------------------
-- Somente dados de apresentação. Nunca CPF, e-mail, telefone, endereço exato
-- ou dado de saúde. A nota é a média das avaliações não ocultas.

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
    exists (
        select 1
        from elovital.documento_verificacao d
        where d.usuario_id = u.id
          and d.tipo = 'documento'
          and d.status = 'aprovado'
    ) as verificado
from elovital.usuario u
join elovital.perfil_apoiador pa on pa.usuario_id = u.id
join elovital.endereco e on e.usuario_id = u.id and e.principal
where u.papel = 'apoiador'
  and u.status = 'ativo'
  and u.is_active
  and u.anonimizado_em is null;

comment on view elovital.v_catalogo_apoiador is
    'Catálogo público (RF007). Sem sobrenome, CPF, telefone, e-mail, endereço exato ou dado de saúde.';

grant select on elovital.v_catalogo_apoiador to elovital_app, elovital_readonly;

-- -----------------------------------------------------------------------------
-- 4. Triggers de proteção
-- -----------------------------------------------------------------------------

-- 4.1 Atualiza atualizado_em em qualquer UPDATE, sem depender da aplicação.
create or replace function elovital.set_atualizado_em()
returns trigger
language plpgsql
as $$
begin
    new.atualizado_em := now();
    return new;
end $$;

do $$
declare
    r record;
begin
    for r in
        select c.table_name
        from information_schema.columns c
        where c.table_schema = 'elovital'
          and c.column_name = 'atualizado_em'
    loop
        execute format(
            'drop trigger if exists %I on elovital.%I',
            r.table_name || '_atualizado_em', r.table_name
        );
        execute format(
            'create trigger %I before update on elovital.%I
             for each row execute function elovital.set_atualizado_em()',
            r.table_name || '_atualizado_em', r.table_name
        );
    end loop;
end $$;

-- 4.2 Histórico é somente inserção.
create or replace function elovital.proteger_auditoria()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Tabela % é somente inserção', tg_table_name
        using errcode = '42501',
              hint = 'UPDATE e DELETE são proibidos em tabelas de auditoria.';
end $$;

create trigger audit_log_imutavel
    before update or delete on elovital.audit_log
    for each row execute function elovital.proteger_auditoria();

create trigger acesso_dado_pessoal_imutavel
    before update or delete on elovital.acesso_dado_pessoal
    for each row execute function elovital.proteger_auditoria();

create trigger expurgo_log_imutavel
    before update or delete on elovital.expurgo_log
    for each row execute function elovital.proteger_auditoria();

-- 4.3 Entidades centrais não têm exclusão física: usa-se status/anonimização.
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

commit;
