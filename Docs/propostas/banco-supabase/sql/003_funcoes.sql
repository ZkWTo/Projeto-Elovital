-- =============================================================================
-- EloVital — 003_funcoes.sql
-- Funções transacionais do fluxo de reserva.
--
-- Status: RASCUNHO PARA APROVAÇÃO.
-- Executar depois de 001_schema.sql e 002_seguranca.sql.
--
-- Escopo: material de documentação. Cada função concentra uma transição de
-- estado que não deve ser feita com UPDATE solto pela aplicação. Auditoria,
-- anonimização, expurgo e agendamento ficaram fora por não serem necessários
-- à demonstração da estrutura.
-- =============================================================================

begin;

set local search_path = elovital, public, extensions;

-- -----------------------------------------------------------------------------
-- 1. Reserva de horário
-- -----------------------------------------------------------------------------
-- Valida solicitação e horário, cria a reserva e move os dois para o estado
-- seguinte. Os índices únicos parciais de 001 são a barreira final contra
-- dupla reserva.

create or replace function elovital.reservar_horario(
    p_solicitacao_id uuid,
    p_horario_id uuid,
    p_assistido_id uuid
)
returns elovital.reserva
language plpgsql
as $$
declare
    v_horario elovital.horario;
    v_solicitacao elovital.solicitacao;
    v_reserva elovital.reserva;
begin
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

    if v_horario.data < current_date then
        raise exception 'Horário no passado' using errcode = 'P0001';
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

    if v_solicitacao.tipo_apoio_id <> v_horario.especialidade_id then
        raise exception 'Especialidade incompatível com a solicitação' using errcode = 'P0001';
    end if;

    if v_solicitacao.modalidade <> v_horario.modalidade then
        raise exception 'Modalidade incompatível com a solicitação' using errcode = 'P0001';
    end if;

    insert into elovital.reserva (
        solicitacao_id, horario_id, assistido_id, apoiador_id, status
    ) values (
        p_solicitacao_id, p_horario_id, p_assistido_id, v_horario.apoiador_id, 'ativa'
    )
    returning * into v_reserva;

    update elovital.horario
       set status = 'reservado', versao = versao + 1
     where id = p_horario_id;

    update elovital.solicitacao
       set status = 'reservada'
     where id = p_solicitacao_id;

    return v_reserva;
end $$;

comment on function elovital.reservar_horario(uuid, uuid, uuid) is
    'Reserva atômica (RF008). O índice parcial único impede duas reservas no mesmo horário.';

-- -----------------------------------------------------------------------------
-- 2. Cancelamento
-- -----------------------------------------------------------------------------
-- Cancela a reserva, devolve o horário ao catálogo e reabre a solicitação.

create or replace function elovital.cancelar_reserva(
    p_reserva_id uuid,
    p_autor_id uuid,
    p_motivo text,
    p_justificativa text default null
)
returns elovital.reserva
language plpgsql
as $$
declare
    v_reserva elovital.reserva;
begin
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

    update elovital.reserva
       set status = 'cancelada',
           cancelada_por = p_autor_id,
           motivo_cancelamento = p_motivo,
           justificativa_cancelamento = p_justificativa,
           cancelada_em = now()
     where id = p_reserva_id
    returning * into v_reserva;

    update elovital.horario
       set status = 'disponivel', versao = versao + 1
     where id = v_reserva.horario_id;

    update elovital.solicitacao
       set status = 'aberta'
     where id = v_reserva.solicitacao_id
       and status = 'reservada';

    return v_reserva;
end $$;

comment on function elovital.cancelar_reserva(uuid, uuid, text, text) is
    'Cancelamento (RF009): libera o horário e reabre a solicitação.';

-- -----------------------------------------------------------------------------
-- 3. Conclusão com dupla confirmação
-- -----------------------------------------------------------------------------
-- Retorna false até as duas partes confirmarem; na segunda confirmação,
-- conclui reserva, horário e solicitação.

create or replace function elovital.confirmar_conclusao(
    p_reserva_id uuid,
    p_parte text
)
returns boolean
language plpgsql
as $$
declare
    v_reserva elovital.reserva;
    v_confirmacoes integer;
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

    insert into elovital.reserva_confirmacao (reserva_id, parte)
    values (p_reserva_id, p_parte)
    on conflict (reserva_id, parte) do nothing;

    select count(*) into v_confirmacoes
    from elovital.reserva_confirmacao
    where reserva_id = p_reserva_id;

    if v_confirmacoes < 2 then
        return false;
    end if;

    update elovital.reserva
       set status = 'concluida'
     where id = p_reserva_id;

    update elovital.horario
       set status = 'realizado', versao = versao + 1
     where id = v_reserva.horario_id;

    update elovital.solicitacao
       set status = 'concluida', concluida_em = now()
     where id = v_reserva.solicitacao_id;

    return true;
end $$;

comment on function elovital.confirmar_conclusao(uuid, text) is
    'Dupla confirmação (RF012). Retorna true quando as duas partes confirmaram.';

-- -----------------------------------------------------------------------------
-- 4. Privilégios das funções
-- -----------------------------------------------------------------------------
-- O padrão do PostgreSQL é EXECUTE para PUBLIC; fechamos e liberamos só o
-- necessário para a aplicação.

revoke execute on all functions in schema elovital from public;

grant execute on function elovital.reservar_horario(uuid, uuid, uuid) to elovital_app;
grant execute on function elovital.cancelar_reserva(uuid, uuid, text, text) to elovital_app;
grant execute on function elovital.confirmar_conclusao(uuid, text) to elovital_app;

commit;
