-- =============================================================================
-- EloVital — 003_funcoes.sql
-- Funções transacionais, auditoria, anonimização, expurgo e agendamento.
--
-- Status: RASCUNHO PARA APROVAÇÃO.
-- Executar depois de 001_schema.sql e 002_seguranca.sql.
--
-- Todas as funções security definer fixam search_path e têm dono
-- elovital_migrator. A aplicação recebe EXECUTE apenas nas funções listadas
-- no fim do arquivo.
-- =============================================================================

begin;

set local search_path = elovital, public, extensions;

-- -----------------------------------------------------------------------------
-- 1. Atualização de atualizado_em
-- -----------------------------------------------------------------------------

create or replace function elovital.set_atualizado_em()
returns trigger
language plpgsql
as $$
begin
    new.atualizado_em := now();
    return new;
end $$;

comment on function elovital.set_atualizado_em() is
    'Mantém atualizado_em coerente em toda escrita.';

do $$
declare
    r record;
begin
    for r in
        select t.tablename as table_name
        from pg_tables t
        where t.schemaname = 'elovital'
          and exists (
              select 1
              from information_schema.columns c
              where c.table_schema = 'elovital'
                and c.table_name = t.tablename
                and c.column_name = 'atualizado_em'
          )
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

-- -----------------------------------------------------------------------------
-- 2. Auditoria
-- -----------------------------------------------------------------------------

create or replace function elovital.mascarar_jsonb(p_dados jsonb)
returns jsonb
language sql
immutable
as $$
    select p_dados - array[
        'cpf_hash', 'cpf_cifrado', 'celular_cifrado',
        'contato_emergencia_telefone_cifrado',
        'autorizacao_familiar_documento_cifrado',
        'password', 'texto', 'descricao', 'justificativa',
        'observacoes_cuidado', 'storage_path', 'hash_sha256',
        'ip_hash', 'user_agent_hash', 'registro_profissional'
    ];
$$;

comment on function elovital.mascarar_jsonb(jsonb) is
    'Remove campos sensíveis antes de gravar antes/depois na auditoria.';

create or replace function elovital.diff_jsonb(p_antes jsonb, p_depois jsonb)
returns table (antes jsonb, depois jsonb)
language sql
immutable
as $$
    with chaves as (
        select key from jsonb_each(coalesce(p_antes, '{}'::jsonb))
        union
        select key from jsonb_each(coalesce(p_depois, '{}'::jsonb))
    ),
    diferencas as (
        select c.key,
               p_antes -> c.key as valor_antes,
               p_depois -> c.key as valor_depois
        from chaves c
        where p_antes -> c.key is distinct from p_depois -> c.key
    )
    select
        coalesce(jsonb_object_agg(key, valor_antes) filter (where valor_antes is not null), '{}'::jsonb),
        coalesce(jsonb_object_agg(key, valor_depois) filter (where valor_depois is not null), '{}'::jsonb)
    from diferencas;
$$;

comment on function elovital.diff_jsonb(jsonb, jsonb) is
    'Retorna somente os campos que mudaram entre antes e depois.';

create or replace function elovital.auditar_alteracao()
returns trigger
language plpgsql
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
declare
    v_antes jsonb;
    v_depois jsonb;
    v_entidade_id text;
begin
    select d.antes, d.depois
      into v_antes, v_depois
      from elovital.diff_jsonb(
          elovital.mascarar_jsonb(coalesce(to_jsonb(old), '{}'::jsonb)),
          elovital.mascarar_jsonb(coalesce(to_jsonb(new), '{}'::jsonb))
      ) d;

    -- atualizado_em sozinho não é evento de negócio.
    v_antes := v_antes - 'atualizado_em';
    v_depois := v_depois - 'atualizado_em';

    if v_antes = '{}'::jsonb and v_depois = '{}'::jsonb then
        return null;
    end if;

    v_entidade_id := coalesce(
        to_jsonb(new) ->> 'id',
        to_jsonb(old) ->> 'id',
        to_jsonb(new) ->> 'chave',
        to_jsonb(old) ->> 'chave'
    );

    insert into elovital.audit_log (
        ator_id, ator_papel, acao, entidade, entidade_id, antes, depois
    ) values (
        elovital.usuario_atual(),
        elovital.papel_atual(),
        lower(tg_op),
        tg_table_name,
        v_entidade_id,
        nullif(v_antes, '{}'::jsonb),
        nullif(v_depois, '{}'::jsonb)
    );

    return null;
end $$;

comment on function elovital.auditar_alteracao() is
    'Trigger AFTER que registra o diff mascarado em audit_log.';

do $$
declare
    r record;
    tabelas text[] := array[
        'usuario', 'documento_verificacao', 'usuario_especialidade',
        'horario', 'solicitacao', 'analise_solicitacao', 'reserva',
        'avaliacao', 'denuncia', 'sancao', 'contestacao', 'ocorrencia',
        'parametro'
    ];
begin
    for r in
        select unnest(tabelas) as tabela
    loop
        execute format(
            'drop trigger if exists %I on elovital.%I',
            r.tabela || '_auditar', r.tabela
        );
        execute format(
            'create trigger %I after insert or update or delete on elovital.%I
             for each row execute function elovital.auditar_alteracao()',
            r.tabela || '_auditar', r.tabela
        );
    end loop;
end $$;

-- -----------------------------------------------------------------------------
-- 3. Autenticação e acesso a dado pessoal
-- -----------------------------------------------------------------------------

create or replace function elovital.buscar_usuario_login(p_email text)
returns table (
    id uuid,
    password text,
    status text,
    papel text,
    is_active boolean
)
language sql
stable
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
    select u.id, u.password, u.status, u.papel, u.is_active
    from elovital.usuario u
    where u.email = p_email::citext
      and u.anonimizado_em is null
$$;

comment on function elovital.buscar_usuario_login(text) is
    'Leitura mínima para login, antes de existir contexto. Não revela se o e-mail existe (retorna 0 linhas).';

create or replace function elovital.buscar_usuario_por_email(p_email text)
returns table (
    id uuid,
    email text,
    status text,
    is_active boolean
)
language sql
stable
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
    select u.id, u.email::text, u.status, u.is_active
    from elovital.usuario u
    where u.email = p_email::citext
      and u.anonimizado_em is null
$$;

comment on function elovital.buscar_usuario_por_email(text) is
    'Leitura mínima para recuperação de senha.';

create or replace function elovital.registrar_acesso_dado_pessoal(
    p_mediador_id uuid,
    p_usuario_id uuid,
    p_campo text,
    p_motivo text,
    p_entidade text default null,
    p_entidade_id text default null
)
returns void
language plpgsql
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
begin
    if elovital.usuario_atual() is distinct from p_mediador_id then
        raise exception 'Registro de acesso deve ser feito pelo próprio mediador'
            using errcode = '42501';
    end if;

    if not exists (
        select 1 from elovital.usuario u
        where u.id = p_mediador_id
          and u.papel = 'mediador'
          and u.is_staff
          and u.status = 'ativo'
    ) then
        raise exception 'Apenas mediador ativo pode acessar dado pessoal'
            using errcode = '42501';
    end if;

    insert into elovital.acesso_dado_pessoal (
        mediador_id, usuario_id, campo, motivo, entidade, entidade_id
    ) values (
        p_mediador_id, p_usuario_id, p_campo, p_motivo, p_entidade, p_entidade_id
    );
end $$;

comment on function elovital.registrar_acesso_dado_pessoal(uuid, uuid, text, text, text, text) is
    'Registra o acesso da mediação, com motivo obrigatório.';

-- -----------------------------------------------------------------------------
-- 4. Reserva
-- -----------------------------------------------------------------------------

create or replace function elovital.reservar_horario(
    p_solicitacao_id uuid,
    p_horario_id uuid,
    p_assistido_id uuid
)
returns elovital.reserva
language plpgsql
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
declare
    v_horario elovital.horario;
    v_solicitacao elovital.solicitacao;
    v_reserva elovital.reserva;
    v_status_apoiador text;
begin
    -- O chamador precisa ser o assistido dono da solicitação.
    if elovital.usuario_atual() is distinct from p_assistido_id then
        raise exception 'Usuário autenticado não corresponde ao assistido' using errcode = '42501';
    end if;

    -- Ordem de bloqueio: horário, depois solicitação. Evita ciclo com cancelamento.
    select * into v_horario
    from elovital.horario
    where id = p_horario_id
    for update;

    if not found then
        raise exception 'Horário não encontrado' using errcode = 'P0002';
    end if;

    if v_horario.status <> 'disponivel' then
        raise exception 'Horário não está disponível' using errcode = 'P0001';
    end if;

    select * into v_solicitacao
    from elovital.solicitacao
    where id = p_solicitacao_id
    for update;

    if not found then
        raise exception 'Solicitação não encontrada' using errcode = 'P0002';
    end if;

    if v_solicitacao.assistido_id <> p_assistido_id then
        raise exception 'Solicitação não pertence ao assistido' using errcode = '42501';
    end if;

    if v_solicitacao.status <> 'aberta' then
        raise exception 'Solicitação não está aberta para reserva' using errcode = 'P0001';
    end if;

    if v_solicitacao.expira_em is not null and v_solicitacao.expira_em <= now() then
        raise exception 'Solicitação expirada' using errcode = 'P0001';
    end if;

    if v_solicitacao.tipo_apoio_id <> v_horario.especialidade_id then
        raise exception 'Especialidade incompatível com a solicitação' using errcode = 'P0001';
    end if;

    if v_solicitacao.modalidade <> v_horario.modalidade then
        raise exception 'Modalidade incompatível com a solicitação' using errcode = 'P0001';
    end if;

    if v_horario.data < current_date then
        raise exception 'Horário no passado' using errcode = 'P0001';
    end if;

    select u.status into v_status_apoiador
    from elovital.usuario u
    where u.id = v_horario.apoiador_id;

    if v_status_apoiador is distinct from 'ativo' then
        raise exception 'Apoiador não está ativo' using errcode = 'P0001';
    end if;

    if not exists (
        select 1 from elovital.documento_verificacao d
        where d.usuario_id = v_horario.apoiador_id
          and d.tipo = 'documento'
          and d.status = 'aprovado'
    ) then
        raise exception 'Apoiador não verificado' using errcode = 'P0001';
    end if;

    if exists (
        select 1 from elovital.especialidade e
        where e.id = v_horario.especialidade_id and e.regulamentada
    ) and not exists (
        select 1 from elovital.usuario_especialidade ue
        where ue.usuario_id = v_horario.apoiador_id
          and ue.especialidade_id = v_horario.especialidade_id
          and ue.verificado_em is not null
    ) then
        raise exception 'Registro profissional não verificado' using errcode = 'P0001';
    end if;

    insert into elovital.reserva (
        solicitacao_id, horario_id, assistido_id, apoiador_id, status
    ) values (
        v_solicitacao.id, v_horario.id, p_assistido_id, v_horario.apoiador_id, 'ativa'
    ) returning * into v_reserva;

    update elovital.horario
       set status = 'reservado', versao = versao + 1
     where id = v_horario.id;

    update elovital.solicitacao
       set status = 'reservada'
     where id = v_solicitacao.id;

    insert into elovital.audit_log (ator_id, ator_papel, acao, entidade, entidade_id, depois)
    values (
        p_assistido_id, elovital.papel_atual(), 'reservar_horario',
        'reserva', v_reserva.id::text, to_jsonb(v_reserva)
    );

    return v_reserva;

exception
    when unique_violation then
        raise exception 'Horário já reservado' using errcode = 'P0001';
end $$;

comment on function elovital.reservar_horario(uuid, uuid, uuid) is
    'Reserva atômica (RF008, RNF006). Bloqueia horário e solicitação; o índice parcial único é a barreira final.';

-- -----------------------------------------------------------------------------
-- 5. Cancelamento
-- -----------------------------------------------------------------------------

create or replace function elovital.cancelar_reserva(
    p_reserva_id uuid,
    p_autor_id uuid,
    p_motivo text,
    p_justificativa text default null
)
returns elovital.reserva
language plpgsql
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
declare
    v_reserva elovital.reserva;
    v_horario elovital.horario;
    v_antecedencia interval;
    v_libera boolean;
begin
    if elovital.usuario_atual() is distinct from p_autor_id then
        raise exception 'Usuário autenticado não corresponde ao autor do cancelamento'
            using errcode = '42501';
    end if;

    select * into v_reserva
    from elovital.reserva
    where id = p_reserva_id
    for update;

    if not found then
        raise exception 'Reserva não encontrada' using errcode = 'P0002';
    end if;

    if v_reserva.status <> 'ativa' then
        raise exception 'Reserva não está ativa' using errcode = 'P0001';
    end if;

    if p_autor_id not in (v_reserva.assistido_id, v_reserva.apoiador_id) then
        raise exception 'Sem permissão para cancelar esta reserva' using errcode = '42501';
    end if;

    if p_motivo is null or btrim(p_motivo) = '' then
        raise exception 'Motivo do cancelamento é obrigatório' using errcode = 'P0001';
    end if;

    if p_motivo = 'outro' and (p_justificativa is null or char_length(btrim(p_justificativa)) < 10) then
        raise exception 'Justificativa obrigatória para o motivo "outro"' using errcode = 'P0001';
    end if;

    select * into v_horario
    from elovital.horario
    where id = v_reserva.horario_id
    for update;

    v_antecedencia := ((v_horario.data + v_horario.hora_inicio) at time zone 'America/Sao_Paulo') - now();
    v_libera := v_antecedencia >= interval '24 hours';

    if not v_libera and (p_justificativa is null or char_length(btrim(p_justificativa)) < 10) then
        raise exception 'Justificativa obrigatória para cancelamento com menos de 24 horas'
            using errcode = 'P0001';
    end if;

    update elovital.reserva
       set status = 'cancelada',
           cancelada_por = p_autor_id,
           motivo_cancelamento = p_motivo,
           justificativa_cancelamento = p_justificativa,
           cancelada_em = now()
     where id = v_reserva.id
    returning * into v_reserva;

    if v_libera then
        update elovital.horario
           set status = 'disponivel', versao = versao + 1
         where id = v_horario.id;
    else
        update elovital.horario
           set status = 'bloqueado', versao = versao + 1
         where id = v_horario.id;

        insert into elovital.ocorrencia (
            reserva_id, aberta_por, tipo, descricao, status
        ) values (
            v_reserva.id, p_autor_id, 'cancelamento_tardio',
            coalesce(p_justificativa, p_motivo), 'aberta'
        );
    end if;

    update elovital.solicitacao
       set status = 'aberta'
     where id = v_reserva.solicitacao_id
       and status = 'reservada'
       and (expira_em is null or expira_em > now());

    insert into elovital.audit_log (
        ator_id, ator_papel, acao, entidade, entidade_id, motivo, depois
    ) values (
        p_autor_id, elovital.papel_atual(), 'cancelar_reserva',
        'reserva', v_reserva.id::text, p_motivo, to_jsonb(v_reserva)
    );

    return v_reserva;
end $$;

comment on function elovital.cancelar_reserva(uuid, uuid, text, text) is
    'Cancelamento com regra de 24 h (RF009). Libera ou bloqueia o horário e registra ocorrência tardia.';

-- -----------------------------------------------------------------------------
-- 6. Conclusão com dupla confirmação
-- -----------------------------------------------------------------------------

create or replace function elovital.confirmar_conclusao(
    p_reserva_id uuid,
    p_parte text
)
returns boolean
language plpgsql
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
declare
    v_reserva elovital.reserva;
    v_confirmacoes integer;
    v_ator uuid := elovital.usuario_atual();
begin
    if p_parte not in ('assistido', 'apoiador') then
        raise exception 'Parte inválida' using errcode = 'P0001';
    end if;

    select * into v_reserva
    from elovital.reserva
    where id = p_reserva_id
    for update;

    if not found then
        raise exception 'Reserva não encontrada' using errcode = 'P0002';
    end if;

    if v_reserva.status <> 'ativa' then
        raise exception 'Reserva não está ativa' using errcode = 'P0001';
    end if;

    if (p_parte = 'assistido' and v_ator is distinct from v_reserva.assistido_id)
       or (p_parte = 'apoiador' and v_ator is distinct from v_reserva.apoiador_id) then
        raise exception 'Sem permissão para confirmar por esta parte' using errcode = '42501';
    end if;

    insert into elovital.reserva_confirmacao (reserva_id, parte)
    values (v_reserva.id, p_parte)
    on conflict (reserva_id, parte) do nothing;

    select count(*) into v_confirmacoes
    from elovital.reserva_confirmacao
    where reserva_id = v_reserva.id;

    if v_confirmacoes < 2 then
        return false;
    end if;

    update elovital.reserva
       set status = 'concluida'
     where id = v_reserva.id;

    update elovital.horario
       set status = 'realizado', versao = versao + 1
     where id = v_reserva.horario_id;

    update elovital.solicitacao
       set status = 'concluida', concluida_em = now()
     where id = v_reserva.solicitacao_id;

    insert into elovital.audit_log (ator_id, ator_papel, acao, entidade, entidade_id)
    values (v_ator, elovital.papel_atual(), 'concluir_reserva', 'reserva', v_reserva.id::text);

    return true;
end $$;

comment on function elovital.confirmar_conclusao(uuid, text) is
    'Dupla confirmação (RF012). Retorna true quando as duas partes confirmaram.';

-- -----------------------------------------------------------------------------
-- 7. Expiração e lembretes
-- -----------------------------------------------------------------------------

create or replace function elovital.expirar_solicitacoes()
returns integer
language plpgsql
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
declare
    v_expiradas integer := 0;
    v_bloqueadas integer := 0;
    v_prazo integer;
begin
    update elovital.solicitacao
       set status = 'expirada'
     where status in ('em_analise', 'aberta')
       and expira_em is not null
       and expira_em <= now();

    get diagnostics v_expiradas = row_count;

    select coalesce((p.valor)::integer, 72)
      into v_prazo
      from elovital.parametro p
     where p.chave = 'prazo_confirmacao_horas';

    if v_prazo is null then
        v_prazo := 72;
    end if;

    update elovital.reserva r
       set status = 'bloqueada'
      from elovital.horario h
     where r.horario_id = h.id
       and r.status = 'ativa'
       and ((h.data + h.hora_inicio) at time zone 'America/Sao_Paulo')
           + (v_prazo || ' hours')::interval <= now()
       and (
           select count(*) from elovital.reserva_confirmacao c
           where c.reserva_id = r.id
       ) < 2;

    get diagnostics v_bloqueadas = row_count;

    if v_expiradas > 0 or v_bloqueadas > 0 then
        insert into elovital.audit_log (acao, entidade, entidade_id, depois)
        values (
            'expirar_solicitacoes', 'solicitacao', null,
            jsonb_build_object(
                'expiradas', v_expiradas,
                'reservas_bloqueadas', v_bloqueadas,
                'executado_em', now()
            )
        );
    end if;

    return v_expiradas;
end $$;

comment on function elovital.expirar_solicitacoes() is
    'Rotina de RF017: expira solicitações vencidas e bloqueia reservas sem dupla confirmação no prazo.';

-- -----------------------------------------------------------------------------
-- 8. Anonimização
-- -----------------------------------------------------------------------------

create or replace function elovital.anonimizar_usuario(
    p_usuario_id uuid,
    p_motivo text
)
returns void
language plpgsql
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
declare
    v_usuario elovital.usuario;
begin
    if elovital.usuario_atual() is distinct from p_usuario_id
       and not elovital.e_mediador() then
        raise exception 'Sem permissão para anonimizar este usuário' using errcode = '42501';
    end if;

    select * into v_usuario
    from elovital.usuario
    where id = p_usuario_id
    for update;

    if not found then
        raise exception 'Usuário não encontrado' using errcode = 'P0002';
    end if;

    if v_usuario.status = 'anonimizado' then
        return;
    end if;

    if p_motivo is null or char_length(btrim(p_motivo)) < 10 then
        raise exception 'Motivo da anonimização é obrigatório' using errcode = 'P0001';
    end if;

    update elovital.usuario
       set nome_completo = 'Titular anonimizado',
           email = ('anonimizado+' || p_usuario_id::text || '@invalido.elovital')::citext,
           cpf_hash = digest(gen_random_uuid()::text || p_usuario_id::text, 'sha256'),
           cpf_cifrado = '',
           celular_cifrado = '',
           foto_path = null,
           status = 'anonimizado',
           is_active = false,
           anonimizado_em = now()
     where id = p_usuario_id;

    delete from elovital.usuario_condicao where usuario_id = p_usuario_id;

    update elovital.endereco
       set cep = '00000000',
           logradouro = '[removido]',
           numero = null,
           complemento = null,
           bairro = '[removido]',
           municipio = '[removido]',
           localizacao = null
     where usuario_id = p_usuario_id;

    update elovital.perfil_assistido
       set contato_emergencia_nome = null,
           contato_emergencia_telefone_cifrado = null,
           autorizacao_familiar_nome = null,
           autorizacao_familiar_parentesco = null,
           autorizacao_familiar_documento_cifrado = null,
           observacoes_cuidado = null
     where usuario_id = p_usuario_id;

    update elovital.perfil_apoiador
       set formacao = null, bio = null
     where usuario_id = p_usuario_id;

    -- O arquivo é removido pelo job do Storage; aqui marcamos o expurgo.
    update elovital.documento_verificacao
       set status = 'expirado', expurgo_em = now(),
           motivo = coalesce(motivo, 'anonimizacao')
     where usuario_id = p_usuario_id;

    update elovital.mensagem
       set texto = '[mensagem expurgada]'
     where autor_id = p_usuario_id;

    update elovital.avaliacao
       set comentario = null
     where autor_id = p_usuario_id;

    update elovital.solicitacao
       set titulo = '[anonimizada]',
           descricao = '[anonimizada]',
           endereco_id = null
     where assistido_id = p_usuario_id;

    update elovital.denuncia
       set descricao = null
     where denunciante_id = p_usuario_id;

    insert into elovital.expurgo_log (regra, tabela, linhas_afetadas, detalhe)
    values (
        'anonimizacao_usuario', 'usuario', 1,
        jsonb_build_object('usuario_id', p_usuario_id, 'motivo', p_motivo, 'executado_em', now())
    );
end $$;

comment on function elovital.anonimizar_usuario(uuid, text) is
    'Anonimiza sem excluir a linha. Mantém auditoria, histórico e integridade referencial.';

-- -----------------------------------------------------------------------------
-- 9. Expurgo de documentos e dados vencidos
-- -----------------------------------------------------------------------------

create or replace function elovital.documentos_a_expurgar()
returns table (id uuid, usuario_id uuid, storage_path text)
language sql
stable
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
    select d.id, d.usuario_id, d.storage_path
    from elovital.documento_verificacao d
    where d.status <> 'pendente'
      and d.expurgo_em is not null
      and d.expurgo_em <= now()
      and d.storage_path <> ''
    order by d.expurgo_em
$$;

comment on function elovital.documentos_a_expurgar() is
    'Lista arquivos a remover do bucket privado pelo job do Storage.';

create or replace function elovital.confirmar_expurgo_documento(p_documento_id uuid)
returns void
language plpgsql
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
begin
    update elovital.documento_verificacao
       set storage_path = '',
           motivo = coalesce(motivo, 'expurgo')
     where id = p_documento_id
       and storage_path <> '';

    if found then
        insert into elovital.expurgo_log (regra, tabela, linhas_afetadas, detalhe)
        values (
            'expurgo_documento', 'documento_verificacao', 1,
            jsonb_build_object('documento_id', p_documento_id, 'executado_em', now())
        );
    end if;
end $$;

create or replace function elovital.expurgar_dados_vencidos()
returns integer
language plpgsql
security definer
set search_path = elovital, public, extensions, pg_temp
as $$
declare
    v_total integer := 0;
    v_linhas integer;
begin
    -- Notificações: 12 meses.
    delete from elovital.notificacao
     where criado_em < now() - interval '12 months';
    get diagnostics v_linhas = row_count;
    v_total := v_total + v_linhas;
    if v_linhas > 0 then
        insert into elovital.expurgo_log (regra, tabela, linhas_afetadas)
        values ('retencao_12_meses', 'notificacao', v_linhas);
    end if;

    -- Tentativas de login: 90 dias.
    delete from elovital.tentativa_login
     where criado_em < now() - interval '90 days';
    get diagnostics v_linhas = row_count;
    v_total := v_total + v_linhas;
    if v_linhas > 0 then
        insert into elovital.expurgo_log (regra, tabela, linhas_afetadas)
        values ('retencao_90_dias', 'tentativa_login', v_linhas);
    end if;

    -- Mensagens de reservas encerradas há mais de 2 anos.
    update elovital.mensagem m
       set texto = '[mensagem expurgada]'
      from elovital.reserva r
     where m.reserva_id = r.id
       and r.status in ('concluida', 'cancelada')
       and r.atualizado_em < now() - interval '2 years'
       and m.texto <> '[mensagem expurgada]';
    get diagnostics v_linhas = row_count;
    v_total := v_total + v_linhas;
    if v_linhas > 0 then
        insert into elovital.expurgo_log (regra, tabela, linhas_afetadas)
        values ('retencao_2_anos', 'mensagem', v_linhas);
    end if;

    -- IP e user agent: 6 meses (Marco Civil). O restante do audit_log fica.
    update elovital.audit_log
       set ip_hash = null, user_agent_hash = null
     where criado_em < now() - interval '6 months'
       and (ip_hash is not null or user_agent_hash is not null);
    get diagnostics v_linhas = row_count;
    v_total := v_total + v_linhas;
    if v_linhas > 0 then
        insert into elovital.expurgo_log (regra, tabela, linhas_afetadas)
        values ('retencao_6_meses', 'audit_log', v_linhas);
    end if;

    return v_total;
end $$;

comment on function elovital.expurgar_dados_vencidos() is
    'Expurgo de notificações, tentativas de login e conteúdo de chat fora do prazo.';

-- -----------------------------------------------------------------------------
-- 10. Agendamento com pg_cron
-- -----------------------------------------------------------------------------

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.unschedule(jobid)
        from cron.job
        where jobname in ('elovital_expirar_solicitacoes', 'elovital_expurgar_dados');

        perform cron.schedule(
            'elovital_expirar_solicitacoes',
            '*/15 * * * *',
            'select elovital.expirar_solicitacoes()'
        );
        perform cron.schedule(
            'elovital_expurgar_dados',
            '20 3 * * *',
            'select elovital.expurgar_dados_vencidos()'
        );
    else
        raise notice 'pg_cron indisponível: agendar as rotinas externamente.';
    end if;
end $$;

-- -----------------------------------------------------------------------------
-- 11. Propriedade e privilégios das funções
-- -----------------------------------------------------------------------------

-- Dono único: migrator. Isso é o que permite a anonimização passar pelo
-- trigger de CPF imutável.
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
end $$;

revoke execute on all functions in schema elovital from public;

grant execute on function elovital.definir_contexto(uuid, text) to elovital_app;
grant execute on function elovital.usuario_atual() to elovital_app;
grant execute on function elovital.papel_atual() to elovital_app;
grant execute on function elovital.e_mediador() to elovital_app;
grant execute on function elovital.dono_da_solicitacao(uuid) to elovital_app;
grant execute on function elovital.parte_da_reserva(uuid) to elovital_app;

grant execute on function elovital.buscar_usuario_login(text) to elovital_app;
grant execute on function elovital.buscar_usuario_por_email(text) to elovital_app;
grant execute on function elovital.registrar_acesso_dado_pessoal(uuid, uuid, text, text, text, text)
    to elovital_app;
grant execute on function elovital.reservar_horario(uuid, uuid, uuid) to elovital_app;
grant execute on function elovital.cancelar_reserva(uuid, uuid, text, text) to elovital_app;
grant execute on function elovital.confirmar_conclusao(uuid, text) to elovital_app;
grant execute on function elovital.anonimizar_usuario(uuid, text) to elovital_app;
grant execute on function elovital.documentos_a_expurgar() to elovital_app;
grant execute on function elovital.confirmar_expurgo_documento(uuid) to elovital_app;

-- expirar_solicitacoes e expurgar_dados_vencidos são do cron, não da aplicação.
revoke execute on function elovital.expirar_solicitacoes() from elovital_app;
revoke execute on function elovital.expurgar_dados_vencidos() from elovital_app;

commit;
