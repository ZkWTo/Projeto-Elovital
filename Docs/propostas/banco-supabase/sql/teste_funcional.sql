-- =============================================================================
-- EloVital — teste_funcional.sql
-- Teste funcional de referência, com dados sintéticos.
--
-- Status: RASCUNHO. NÃO EXECUTAR EM PRODUÇÃO.
--
-- Pré-requisitos:
--   1. Banco vazio, com 001_schema.sql, 002_seguranca.sql e 003_funcoes.sql
--      aplicados.
--   2. Executar como superusuário (ou papel administrativo) para poder usar
--      SET ROLE e validar privilégios.
--   3. Executar com psql: o script usa \set e \echo, que não são SQL padrão.
--
-- O script cobre: reserva atômica, RLS por usuário, dupla confirmação,
-- imutabilidade de CPF, papel e auditoria, exceção de IP, bloqueio de rotinas
-- de cron, leitura mascarada, anonimização e índices parciais únicos.
-- Cada verificação imprime PASS ou FALHA.
-- =============================================================================

\set ON_ERROR_STOP on
set search_path = elovital, public, extensions;

\echo '=== 0. Fixtures (migrator) ==='
set role elovital_migrator;

insert into elovital.usuario (id, email, password, nome_completo, cpf_hash, cpf_cifrado,
                              data_nascimento, celular_cifrado, papel, status, is_staff)
values
 ('11111111-1111-1111-1111-111111111111', 'ana@example.com', 'x', 'Ana Assistida',
  extensions.digest('ana', 'sha256'), 'v1:ana', '1990-01-01', 'v1:ana', 'assistido', 'ativo', false),
 ('22222222-2222-2222-2222-222222222222', 'carla@example.com', 'x', 'Carla Assistida',
  extensions.digest('carla', 'sha256'), 'v1:carla', '1992-01-01', 'v1:carla', 'assistido', 'ativo', false),
 ('33333333-3333-3333-3333-333333333333', 'beto@example.com', 'x', 'Beto Apoiador',
  extensions.digest('beto', 'sha256'), 'v1:beto', '1985-01-01', 'v1:beto', 'apoiador', 'ativo', false),
 ('44444444-4444-4444-4444-444444444444', 'mara@example.com', 'x', 'Mara Mediadora',
  extensions.digest('mara', 'sha256'), 'v1:mara', '1980-01-01', 'v1:mara', 'mediador', 'ativo', true);

insert into elovital.especialidade (id, nome, regulamentada, conselho)
values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Acompanhamento', false, null);

insert into elovital.endereco (id, usuario_id, cep, logradouro, numero, bairro, municipio, uf, localizacao, principal)
values
 ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '33333333-3333-3333-3333-333333333333',
  '01310100', 'Av Paulista', '1000', 'Bela Vista', 'São Paulo', 'SP',
  st_setsrid(st_makepoint(-46.655, -23.564), 4326)::geography, true),
 ('ffffffff-ffff-ffff-ffff-ffffffffffff', '22222222-2222-2222-2222-222222222222',
  '04538132', 'Av Faria Lima', '2000', 'Itaim Bibi', 'São Paulo', 'SP',
  st_setsrid(st_makepoint(-46.690, -23.585), 4326)::geography, true);

insert into elovital.perfil_assistido (usuario_id, observacoes_cuidado)
values ('11111111-1111-1111-1111-111111111111', 'Usa cadeira de rodas.');

insert into elovital.perfil_apoiador (usuario_id, modalidade, raio_km)
values ('33333333-3333-3333-3333-333333333333', 'presencial', 15);

insert into elovital.documento_verificacao (id, usuario_id, tipo, storage_path, hash_sha256, status, decidido_por, decidido_em)
values ('cccccccc-cccc-cccc-cccc-cccccccccccc', '33333333-3333-3333-3333-333333333333',
        'documento', 'docs/beto.pdf', extensions.digest('doc', 'sha256'), 'aprovado',
        '44444444-4444-4444-4444-444444444444', now());

insert into elovital.parametro (chave, valor, tipo, descricao)
values ('prazo_confirmacao_horas', '72', 'inteiro', 'Prazo de dupla confirmação');

insert into elovital.horario (id, apoiador_id, especialidade_id, data, hora_inicio, hora_fim, modalidade, status)
values
 ('dddddddd-dddd-dddd-dddd-dddddddddddd', '33333333-3333-3333-3333-333333333333',
  'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', current_date + 1, '10:00', '11:00', 'presencial', 'disponivel'),
 ('99999999-9999-9999-9999-999999999999', '33333333-3333-3333-3333-333333333333',
  'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', current_date + 2, '14:00', '15:00', 'presencial', 'disponivel');

insert into elovital.solicitacao (id, assistido_id, tipo_apoio_id, titulo, descricao,
                                  modalidade, status, prioridade_final, expira_em)
values
 ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', '11111111-1111-1111-1111-111111111111',
  'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Acompanhar consulta',
  'Preciso de companhia para ir à consulta.', 'presencial', 'aberta', 'media', now() + interval '2 days'),
 ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2', '22222222-2222-2222-2222-222222222222',
  'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Acompanhar exame',
  'Preciso de companhia para fazer um exame.', 'presencial', 'aberta', 'media', now() + interval '2 days');

reset role;

\echo '=== 1. RLS antes da reserva: Ana vê só a si mesma e a própria solicitação ==='
set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('11111111-1111-1111-1111-111111111111', 'assistido');
select 'usuarios_visiveis_ana_antes' as teste, count(*) from elovital.usuario;
select 'enderecos_visiveis_ana_antes' as teste, count(*) from elovital.endereco;
select 'solicitacoes_visiveis_ana' as teste, count(*) from elovital.solicitacao;
commit;
reset role;

\echo '=== 2. Reserva atômica (Ana) ==='
set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('11111111-1111-1111-1111-111111111111', 'assistido');
select 'reserva_criada' as teste,
       (elovital.reservar_horario('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
                                  'dddddddd-dddd-dddd-dddd-dddddddddddd',
                                  '11111111-1111-1111-1111-111111111111')).status;
commit;
reset role;

select 'reserva_status' as teste, status from elovital.reserva;
select 'horario_status' as teste, status, versao from elovital.horario order by hora_inicio;
select 'solicitacao_status' as teste, status from elovital.solicitacao order by titulo;

\echo '=== 3. RLS depois da reserva: Ana vê Beto e o endereço do atendimento ==='
set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('11111111-1111-1111-1111-111111111111', 'assistido');
select 'usuarios_visiveis_ana_depois' as teste, count(*) from elovital.usuario;
select 'enderecos_visiveis_ana_depois' as teste, count(*) from elovital.endereco;
commit;
reset role;

set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('22222222-2222-2222-2222-222222222222', 'assistido');
select 'usuarios_visiveis_carla' as teste, count(*) from elovital.usuario;
select 'enderecos_visiveis_carla' as teste, count(*) from elovital.endereco;
select 'solicitacoes_visiveis_carla' as teste, count(*) from elovital.solicitacao;
commit;
reset role;

\echo '=== 4. Concorrência: segunda reserva no mesmo horário deve falhar ==='
set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('22222222-2222-2222-2222-222222222222', 'assistido');
do $$
begin
    perform elovital.reservar_horario('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2',
                                      'dddddddd-dddd-dddd-dddd-dddddddddddd',
                                      '22222222-2222-2222-2222-222222222222');
    raise notice 'FALHA: segunda reserva foi aceita';
exception when others then
    raise notice 'PASS: segunda reserva bloqueada (%)', sqlerrm;
end $$;
rollback;
reset role;

\echo '=== 5. Insert direto de reserva pela aplicação deve ser barrado pela RLS ==='
set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('22222222-2222-2222-2222-222222222222', 'assistido');
do $$
begin
    insert into elovital.reserva (solicitacao_id, horario_id, assistido_id, apoiador_id)
    values ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2', '99999999-9999-9999-9999-999999999999',
            '22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333');
    raise notice 'FALHA: insert direto de reserva foi aceito';
exception when others then
    raise notice 'PASS: insert direto bloqueado (%)', sqlerrm;
end $$;
rollback;
reset role;

\echo '=== 6. Dupla confirmação ==='
set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('11111111-1111-1111-1111-111111111111', 'assistido');
select 'conclusao_1' as teste,
       elovital.confirmar_conclusao((select id from elovital.reserva limit 1), 'assistido');
commit;
reset role;

select 'reserva_apos_1_confirmacao' as teste, status from elovital.reserva;

set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('33333333-3333-3333-3333-333333333333', 'apoiador');
select 'conclusao_2' as teste,
       elovital.confirmar_conclusao((select id from elovital.reserva limit 1), 'apoiador');
commit;
reset role;

select 'reserva_apos_2_confirmacoes' as teste, status from elovital.reserva;
select 'solicitacao_final' as teste, status, concluida_em is not null as tem_conclusao from elovital.solicitacao order by titulo;
select 'horario_final' as teste, status from elovital.horario order by hora_inicio;

\echo '=== 7. CPF, papel e auditoria imutáveis para a aplicação ==='
set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('11111111-1111-1111-1111-111111111111', 'assistido');
do $$
begin
    update elovital.usuario set cpf_cifrado = 'v1:hack'
     where id = '11111111-1111-1111-1111-111111111111';
    raise notice 'FALHA: CPF foi alterado pela aplicação';
exception when others then
    raise notice 'PASS: CPF imutável (%)', sqlerrm;
end $$;
do $$
begin
    update elovital.usuario set papel = 'mediador'
     where id = '11111111-1111-1111-1111-111111111111';
    raise notice 'FALHA: papel foi escalado pela aplicação';
exception when others then
    raise notice 'PASS: escalada de papel bloqueada (%)', sqlerrm;
end $$;
rollback;
reset role;

set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('44444444-4444-4444-4444-444444444444', 'mediador');
do $$
begin
    update elovital.audit_log set acao = 'x';
    raise notice 'FALHA: audit_log foi alterado pela aplicação';
exception when others then
    raise notice 'PASS: audit_log imutável para a aplicação (%)', sqlerrm;
end $$;
rollback;
reset role;

\echo '=== 8. Auditoria: exceção estreita de IP vale só para o migrator ==='
set role elovital_migrator;
insert into elovital.audit_log (acao, entidade, ip_hash, user_agent_hash)
values ('teste_ip', 'teste', extensions.digest('1.2.3.4', 'sha256'), extensions.digest('ua', 'sha256'));
reset role;

set role elovital_migrator;
do $$
begin
    update elovital.audit_log set acao = 'outra_coisa' where acao = 'teste_ip';
    raise notice 'FALHA: migrator alterou campo fora da exceção';
exception when others then
    raise notice 'PASS: migrator não altera campos fora da exceção (%)', sqlerrm;
end $$;
update elovital.audit_log set ip_hash = null, user_agent_hash = null where acao = 'teste_ip';
select 'ip_zerado' as teste, ip_hash is null and user_agent_hash is null as zerado
from elovital.audit_log where acao = 'teste_ip';
reset role;

\echo '=== 9. Funções de cron não são executáveis pela aplicação ==='
set role elovital_app;
set search_path = elovital, public, extensions;
do $$
begin
    perform elovital.expirar_solicitacoes();
    raise notice 'FALHA: aplicação executou rotina de cron';
exception when others then
    raise notice 'PASS: rotina de cron bloqueada (%)', sqlerrm;
end $$;
reset role;

\echo '=== 10. Leitura mascarada ==='
set role elovital_readonly;
set search_path = elovital, public, extensions;
select 'catalogo_publico' as teste, primeiro_nome, bairro, municipio, verificado
from elovital.v_catalogo_apoiador;
do $$
begin
    perform count(*) from elovital.usuario;
    raise notice 'FALHA: readonly leu tabela base';
exception when others then
    raise notice 'PASS: readonly não lê tabela base (%)', sqlerrm;
end $$;
reset role;

\echo '=== 11. Anonimização pelo próprio titular ==='
set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('22222222-2222-2222-2222-222222222222', 'assistido');
do $$
begin
    perform elovital.anonimizar_usuario('11111111-1111-1111-1111-111111111111',
                                        'pedido de eliminação do titular');
    raise notice 'FALHA: assistido anonimizou outra pessoa';
exception when others then
    raise notice 'PASS: anonimização de terceiro bloqueada (%)', sqlerrm;
end $$;
rollback;
reset role;

set role elovital_app;
set search_path = elovital, public, extensions;
begin;
select elovital.definir_contexto('11111111-1111-1111-1111-111111111111', 'assistido');
select elovital.anonimizar_usuario('11111111-1111-1111-1111-111111111111',
                                   'pedido de eliminação do titular');
commit;
reset role;

select 'usuario_anonimizado' as teste, status, nome_completo, email::text,
       cpf_cifrado = '' as cpf_zerado, is_active
from elovital.usuario where id = '11111111-1111-1111-1111-111111111111';

select 'solicitacao_anonimizada' as teste, titulo, endereco_id is null as sem_endereco
from elovital.solicitacao where id = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';

select 'expurgo_log' as teste, regra, tabela, linhas_afetadas from elovital.expurgo_log;

select 'auditoria_total' as teste, count(*) from elovital.audit_log;

\echo '=== 12. Índices parciais únicos são a última barreira ==='
set role elovital_migrator;
insert into elovital.horario (id, apoiador_id, especialidade_id, data, hora_inicio, hora_fim, modalidade, status)
values
 ('77777777-7777-7777-7777-777777777777','33333333-3333-3333-3333-333333333333',
  'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', current_date + 3, '09:00','10:00','presencial','disponivel'),
 ('88888888-8888-8888-8888-888888888888','33333333-3333-3333-3333-333333333333',
  'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', current_date + 4, '09:00','10:00','presencial','disponivel');

do $$
begin
    insert into elovital.reserva (solicitacao_id, horario_id, assistido_id, apoiador_id)
    values ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2','77777777-7777-7777-7777-777777777777',
            '22222222-2222-2222-2222-222222222222','33333333-3333-3333-3333-333333333333');

    begin
        insert into elovital.reserva (solicitacao_id, horario_id, assistido_id, apoiador_id)
        values ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2','88888888-8888-8888-8888-888888888888',
                '22222222-2222-2222-2222-222222222222','33333333-3333-3333-3333-333333333333');
        raise notice 'FALHA: duas reservas ativas para a mesma solicitação';
    exception when unique_violation then
        raise notice 'PASS: índice de solicitação ativa bloqueou a segunda reserva';
    end;

    begin
        insert into elovital.reserva (solicitacao_id, horario_id, assistido_id, apoiador_id)
        values ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2','77777777-7777-7777-7777-777777777777',
                '22222222-2222-2222-2222-222222222222','33333333-3333-3333-3333-333333333333');
        raise notice 'FALHA: duas reservas não canceladas para o mesmo horário';
    exception when unique_violation then
        raise notice 'PASS: índice de horário ativo bloqueou a duplicidade';
    end;
end $$;
reset role;
