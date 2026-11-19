-- =============================================================================
-- EloVital — 001_schema.sql
-- Schema de referência da proposta Supabase/PostgreSQL.
--
-- Status: RASCUNHO PARA APROVAÇÃO. Não aplicar em produção antes do aceite
--         da ADR 0004 e do checklist 05.
--
-- Este arquivo é a versão executável de referência do documento
-- 02-modelo-de-dados.md. Na implementação, o Django gera as migrations
-- equivalentes e continua sendo a fonte de verdade do schema.
--
-- Ordem de execução: 001_schema.sql -> 002_seguranca.sql -> 003_funcoes.sql
-- =============================================================================

begin;

-- -----------------------------------------------------------------------------
-- 1. Schemas e extensões
-- -----------------------------------------------------------------------------
create schema if not exists extensions;
create schema if not exists elovital;

create extension if not exists pgcrypto  with schema extensions;
create extension if not exists postgis   with schema extensions;
create extension if not exists pg_trgm   with schema extensions;
create extension if not exists unaccent  with schema extensions;
create extension if not exists citext    with schema extensions;

-- pg_cron é habilitado no painel do Supabase (shared_preload_libraries).
-- O schema criado é `cron`; as rotinas são agendadas em 003_funcoes.sql.
-- create extension if not exists pg_cron;

-- search_path do arquivo: elovital primeiro, sem depender de public.
set local search_path = elovital, public, extensions;

-- -----------------------------------------------------------------------------
-- 2. Tipos auxiliares de checagem
-- -----------------------------------------------------------------------------
-- Optamos por `text` + CHECK em vez de ENUM: o Django usa varchar com choices,
-- e adicionar valor a um ENUM exige ALTER TYPE, que é mais burocrático.

-- -----------------------------------------------------------------------------
-- 3. Contas e perfis
-- -----------------------------------------------------------------------------

create table elovital.usuario (
    id                  uuid primary key default gen_random_uuid(),
    email               citext not null,
    password            text not null,
    nome_completo       text not null,
    cpf_hash            bytea not null,
    cpf_cifrado         text not null,
    data_nascimento     date not null,
    celular_cifrado     text not null,
    foto_path           text,
    papel               text not null,
    status              text not null default 'em_analise',
    is_staff            boolean not null default false,
    is_active           boolean not null default true,
    is_superuser        boolean not null default false,
    email_verificado_em timestamptz,
    ultimo_login        timestamptz,
    anonimizado_em      timestamptz,
    criado_em           timestamptz not null default now(),
    atualizado_em       timestamptz not null default now(),
    constraint usuario_papel_valido check (papel in ('assistido', 'apoiador', 'mediador')),
    constraint usuario_status_valido check (
        status in ('em_analise', 'ativo', 'suspenso', 'bloqueado', 'desativado', 'anonimizado')
    ),
    constraint usuario_maioridade check (data_nascimento <= (current_date - interval '18 years')),
    constraint usuario_email_nao_vazio check (char_length(email::text) > 3),
    constraint usuario_sem_anonimizacao_parcial check (
        (status = 'anonimizado') = (anonimizado_em is not null)
    )
);

comment on table elovital.usuario is
    'Conta única por CPF. CPF nunca em texto puro: cpf_hash para unicidade/busca e cpf_cifrado para leitura autorizada.';
comment on column elovital.usuario.cpf_hash is
    'HMAC-SHA-256(pepper, cpf). Único. Nunca exibido.';
comment on column elovital.usuario.cpf_cifrado is
    'AES-256-GCM application-side, formato v1:<base64(nonce||ct||tag)>.';
comment on column elovital.usuario.celular_cifrado is
    'Telefone cifrado. Nunca exibido no catálogo; contato só pelo chat.';
comment on column elovital.usuario.status is
    'Estado de negócio, separado de is_active do Django.';

create unique index usuario_email_uidx on elovital.usuario (email);
create unique index usuario_cpf_hash_uidx on elovital.usuario (cpf_hash);
create index usuario_papel_status_idx on elovital.usuario (papel, status);
create index usuario_nome_trgm_idx on elovital.usuario using gin (nome_completo gin_trgm_ops);

create table elovital.endereco (
    id            uuid primary key default gen_random_uuid(),
    usuario_id    uuid not null references elovital.usuario (id),
    cep           text not null,
    logradouro    text not null,
    numero        text,
    complemento   text,
    bairro        text not null,
    municipio     text not null,
    uf            char(2) not null,
    localizacao   geography(Point, 4326),
    principal     boolean not null default false,
    criado_em     timestamptz not null default now(),
    atualizado_em timestamptz not null default now(),
    constraint endereco_cep_valido check (cep ~ '^[0-9]{8}$'),
    constraint endereco_uf_valida check (uf ~ '^[A-Z]{2}$')
);

comment on table elovital.endereco is
    'Endereço do usuário. A coordenada exata não sai no catálogo; a view pública usa grade aproximada.';
comment on column elovital.endereco.localizacao is
    'Ponto geográfico para ST_DWithin. Acesso restrito ao dono, à mediação e ao cálculo de distância.';

create unique index endereco_principal_uidx on elovital.endereco (usuario_id) where principal;
create index endereco_usuario_idx on elovital.endereco (usuario_id);
create index endereco_localizacao_gist_idx on elovital.endereco using gist (localizacao);

create table elovital.termo_aceite (
    id           bigserial primary key,
    usuario_id   uuid not null references elovital.usuario (id),
    tipo         text not null,
    versao       text not null,
    aceito_em    timestamptz not null default now(),
    revogado_em  timestamptz,
    ip_hash      bytea,
    constraint termo_tipo_valido check (
        tipo in ('termos', 'privacidade', 'consentimento_saude', 'codigo_conduta')
    ),
    constraint termo_revogacao_coerente check (revogado_em is null or revogado_em >= aceito_em)
);

comment on table elovital.termo_aceite is
    'Prova de aceite e de consentimento, com versão. O consentimento de saúde é separado do aceite geral.';

create unique index termo_aceite_usuario_tipo_versao_uidx
    on elovital.termo_aceite (usuario_id, tipo, versao);
create index termo_aceite_usuario_idx on elovital.termo_aceite (usuario_id, aceito_em desc);

create table elovital.perfil_assistido (
    usuario_id                            uuid primary key references elovital.usuario (id),
    contato_emergencia_nome               text,
    contato_emergencia_telefone_cifrado   text,
    autorizacao_familiar_nome             text,
    autorizacao_familiar_parentesco       text,
    autorizacao_familiar_documento_cifrado text,
    observacoes_cuidado                   text,
    criado_em                             timestamptz not null default now(),
    atualizado_em                         timestamptz not null default now(),
    constraint perfil_assistido_observacoes_tamanho check (
        observacoes_cuidado is null or char_length(observacoes_cuidado) <= 1000
    )
);

comment on column elovital.perfil_assistido.contato_emergencia_telefone_cifrado is
    'Dado pessoal de terceiro, cifrado. A política de privacidade deve informar a origem.';
comment on column elovital.perfil_assistido.observacoes_cuidado is
    'Texto livre com possível dado de saúde. Não aparece no catálogo e não vai para log.';

create table elovital.perfil_apoiador (
    usuario_id    uuid primary key references elovital.usuario (id),
    modalidade    text not null default 'presencial',
    raio_km       numeric(5, 2) not null default 10,
    formacao      text,
    bio           text,
    criado_em     timestamptz not null default now(),
    atualizado_em timestamptz not null default now(),
    constraint perfil_apoiador_modalidade_valida check (modalidade in ('presencial', 'remoto')),
    constraint perfil_apoiador_raio_valido check (raio_km > 0 and raio_km <= 100),
    constraint perfil_apoiador_bio_tamanho check (bio is null or char_length(bio) <= 500)
);

create table elovital.documento_verificacao (
    id             uuid primary key default gen_random_uuid(),
    usuario_id     uuid not null references elovital.usuario (id),
    tipo           text not null,
    storage_path   text not null,
    hash_sha256    bytea not null,
    status         text not null default 'pendente',
    decidido_por   uuid references elovital.usuario (id),
    decidido_em    timestamptz,
    motivo         text,
    expurgo_em     timestamptz,
    criado_em      timestamptz not null default now(),
    constraint documento_tipo_valido check (
        tipo in ('documento', 'selfie', 'registro_profissional')
    ),
    constraint documento_status_valido check (
        status in ('pendente', 'aprovado', 'reprovado', 'expirado')
    ),
    constraint documento_decisao_coerente check (
        status not in ('aprovado', 'reprovado') or decidido_em is not null
    )
);

comment on table elovital.documento_verificacao is
    'Metadados do documento. O arquivo fica no bucket privado do Storage; o banco guarda caminho, hash e decisão.';
comment on column elovital.documento_verificacao.storage_path is
    'Caminho no bucket privado. Nunca exposto ao cliente; acesso por URL assinada de 60 s.';
comment on column elovital.documento_verificacao.expurgo_em is
    'Data em que o arquivo deve ser removido do Storage (90 dias após a decisão).';

create index documento_usuario_idx on elovital.documento_verificacao (usuario_id, criado_em desc);
create index documento_status_expurgo_idx on elovital.documento_verificacao (status, expurgo_em);

create table elovital.tentativa_login (
    id          bigserial primary key,
    email_hash  bytea not null,
    ip_hash     bytea,
    sucesso     boolean not null,
    criado_em   timestamptz not null default now()
);

comment on table elovital.tentativa_login is
    'Bloqueio temporário por tentativas falhas. Guarda hash do e-mail e do IP, nunca o valor original.';

create index tentativa_login_email_idx on elovital.tentativa_login (email_hash, criado_em desc);
create index tentativa_login_criado_idx on elovital.tentativa_login (criado_em);

-- -----------------------------------------------------------------------------
-- 4. Catálogos e parâmetros
-- -----------------------------------------------------------------------------

create table elovital.condicao_cronica (
    id            uuid primary key default gen_random_uuid(),
    nome          text not null,
    ativo         boolean not null default true,
    criado_em     timestamptz not null default now(),
    atualizado_em timestamptz not null default now(),
    constraint condicao_nome_tamanho check (char_length(nome) between 2 and 120)
);

create unique index condicao_cronica_nome_uidx on elovital.condicao_cronica (lower(nome));

create table elovital.usuario_condicao (
    id               uuid primary key default gen_random_uuid(),
    usuario_id       uuid not null references elovital.usuario (id),
    condicao_id      uuid not null references elovital.condicao_cronica (id),
    observacao       text,
    consentimento_id bigint references elovital.termo_aceite (id),
    criado_em        timestamptz not null default now(),
    constraint usuario_condicao_observacao_tamanho check (
        observacao is null or char_length(observacao) <= 500
    )
);

comment on table elovital.usuario_condicao is
    'Dado sensível de saúde (art. 11). Consentimento específico referenciado em consentimento_id.';

create unique index usuario_condicao_uidx on elovital.usuario_condicao (usuario_id, condicao_id);
create index usuario_condicao_condicao_idx on elovital.usuario_condicao (condicao_id);

create table elovital.especialidade (
    id             uuid primary key default gen_random_uuid(),
    nome           text not null,
    regulamentada  boolean not null default false,
    conselho       text,
    ativo          boolean not null default true,
    criado_em      timestamptz not null default now(),
    atualizado_em  timestamptz not null default now(),
    constraint especialidade_nome_tamanho check (char_length(nome) between 2 and 120),
    constraint especialidade_conselho_coerente check (not regulamentada or conselho is not null)
);

create unique index especialidade_nome_uidx on elovital.especialidade (lower(nome));

create table elovital.usuario_especialidade (
    id                    uuid primary key default gen_random_uuid(),
    usuario_id            uuid not null references elovital.usuario (id),
    especialidade_id      uuid not null references elovital.especialidade (id),
    registro_profissional text,
    registro_link         text,
    verificado_em         timestamptz,
    verificado_por        uuid references elovital.usuario (id),
    criado_em             timestamptz not null default now(),
    atualizado_em         timestamptz not null default now(),
    constraint usuario_especialidade_verificacao_coerente check (
        (verificado_em is null) = (verificado_por is null)
    )
);

create unique index usuario_especialidade_uidx
    on elovital.usuario_especialidade (usuario_id, especialidade_id);
create index usuario_especialidade_especialidade_idx
    on elovital.usuario_especialidade (especialidade_id);

create table elovital.parametro (
    chave         text primary key,
    valor         text not null,
    tipo          text not null,
    descricao     text,
    atualizado_por uuid references elovital.usuario (id),
    criado_em     timestamptz not null default now(),
    atualizado_em timestamptz not null default now(),
    constraint parametro_tipo_valido check (
        tipo in ('inteiro', 'decimal', 'texto', 'booleano', 'duracao')
    )
);

comment on table elovital.parametro is
    'Configuração sem deploy (RNF010): limites, prazos, raios, antecedência e limiares.';

-- -----------------------------------------------------------------------------
-- 5. Agenda
-- -----------------------------------------------------------------------------

create table elovital.recorrencia (
    id               uuid primary key default gen_random_uuid(),
    apoiador_id      uuid not null references elovital.usuario (id),
    especialidade_id uuid not null references elovital.especialidade (id),
    dias_semana      smallint[] not null,
    data_inicio      date not null,
    data_fim         date,
    hora_inicio      time not null,
    hora_fim         time not null,
    modalidade       text not null default 'presencial',
    ativo            boolean not null default true,
    criado_em        timestamptz not null default now(),
    atualizado_em    timestamptz not null default now(),
    constraint recorrencia_dias_validos check (
        dias_semana <@ array[0,1,2,3,4,5,6]::smallint[]
        and coalesce(array_length(dias_semana, 1), 0) > 0
    ),
    constraint recorrencia_periodo_valido check (
        data_fim is null or data_fim >= data_inicio
    ),
    constraint recorrencia_horas_validas check (
        hora_fim > hora_inicio
        and (hora_fim - hora_inicio) >= interval '30 minutes'
        and (hora_fim - hora_inicio) <= interval '4 hours'
    ),
    constraint recorrencia_modalidade_valida check (modalidade in ('presencial', 'remoto'))
);

create index recorrencia_apoiador_idx on elovital.recorrencia (apoiador_id, ativo);

create table elovital.horario (
    id               uuid primary key default gen_random_uuid(),
    apoiador_id      uuid not null references elovital.usuario (id),
    recorrencia_id   uuid references elovital.recorrencia (id),
    especialidade_id uuid not null references elovital.especialidade (id),
    data             date not null,
    hora_inicio      time not null,
    hora_fim         time not null,
    modalidade       text not null default 'presencial',
    local_texto      text,
    raio_km          numeric(5, 2),
    status           text not null default 'disponivel',
    versao           integer not null default 0,
    criado_em        timestamptz not null default now(),
    atualizado_em    timestamptz not null default now(),
    constraint horario_periodo_valido check (
        hora_fim > hora_inicio
        and (hora_fim - hora_inicio) >= interval '30 minutes'
        and (hora_fim - hora_inicio) <= interval '4 hours'
    ),
    constraint horario_modalidade_valida check (modalidade in ('presencial', 'remoto')),
    constraint horario_status_valido check (
        status in ('disponivel', 'reservado', 'realizado', 'liberado', 'bloqueado', 'cancelado')
    ),
    constraint horario_raio_valido check (raio_km is null or (raio_km > 0 and raio_km <= 100))
);

comment on table elovital.horario is
    'Horário publicado pelo Apoiador. O índice parcial em reserva é a última barreira contra dupla reserva.';
comment on column elovital.horario.versao is
    'Versão para concorrência otimista; incrementada em toda transição de status.';

create index horario_catalogo_idx on elovital.horario (status, data, hora_inicio);
create index horario_apoiador_idx on elovital.horario (apoiador_id, data);
create index horario_especialidade_idx on elovital.horario (especialidade_id, status, data);

-- -----------------------------------------------------------------------------
-- 6. Solicitações
-- -----------------------------------------------------------------------------

create table elovital.solicitacao (
    id                   uuid primary key default gen_random_uuid(),
    assistido_id         uuid not null references elovital.usuario (id),
    tipo_apoio_id        uuid not null references elovital.especialidade (id),
    titulo               text not null,
    descricao            text not null,
    prioridade_sugerida  text,
    prioridade_final     text,
    modalidade           text not null default 'presencial',
    endereco_id          uuid references elovital.endereco (id),
    raio_km              numeric(5, 2),
    status               text not null default 'em_analise',
    expira_em            timestamptz,
    concluida_em         timestamptz,
    cancelada_em         timestamptz,
    criado_em            timestamptz not null default now(),
    atualizado_em        timestamptz not null default now(),
    constraint solicitacao_titulo_tamanho check (char_length(titulo) between 5 and 80),
    constraint solicitacao_descricao_tamanho check (char_length(descricao) between 10 and 1000),
    constraint solicitacao_prioridade_sugerida_valida check (
        prioridade_sugerida is null or prioridade_sugerida in ('baixa', 'media', 'alta')
    ),
    constraint solicitacao_prioridade_final_valida check (
        prioridade_final is null or prioridade_final in ('baixa', 'media', 'alta')
    ),
    constraint solicitacao_modalidade_valida check (modalidade in ('presencial', 'remoto')),
    constraint solicitacao_status_valido check (
        status in ('em_analise', 'ajuste', 'aberta', 'reservada', 'em_andamento',
                   'concluida', 'cancelada', 'expirada', 'recusada')
    ),
    constraint solicitacao_raio_valido check (raio_km is null or (raio_km > 0 and raio_km <= 100)),
    constraint solicitacao_conclusao_coerente check (
        (status = 'concluida') = (concluida_em is not null)
    ),
    constraint solicitacao_cancelamento_coerente check (
        (status = 'cancelada') = (cancelada_em is not null)
    )
);

comment on table elovital.solicitacao is
    'Necessidade criada pelo Assistido. Nasce em_analise e só é publicada após aprovação humana.';
comment on column elovital.solicitacao.descricao is
    'Texto livre com possível dado de saúde. Não indexar para busca pública e não enviar a log.';

create index solicitacao_status_expira_idx on elovital.solicitacao (status, expira_em);
create index solicitacao_assistido_idx on elovital.solicitacao (assistido_id, criado_em desc);
create index solicitacao_tipo_status_idx on elovital.solicitacao (tipo_apoio_id, status);
create index solicitacao_titulo_trgm_idx
    on elovital.solicitacao using gin (titulo gin_trgm_ops);

create table elovital.solicitacao_periodo (
    id             uuid primary key default gen_random_uuid(),
    solicitacao_id uuid not null references elovital.solicitacao (id),
    data           date not null,
    periodo        text not null,
    constraint solicitacao_periodo_valido check (periodo in ('manha', 'tarde', 'noite', 'integral'))
);

create unique index solicitacao_periodo_uidx
    on elovital.solicitacao_periodo (solicitacao_id, data, periodo);
create index solicitacao_periodo_solicitacao_idx on elovital.solicitacao_periodo (solicitacao_id);

create table elovital.analise_solicitacao (
    id                   uuid primary key default gen_random_uuid(),
    solicitacao_id       uuid not null references elovital.solicitacao (id),
    mediador_id          uuid not null references elovital.usuario (id),
    parecer              text not null,
    prioridade           text,
    justificativa        text,
    observacoes_internas text,
    criado_em            timestamptz not null default now(),
    constraint analise_parecer_valido check (parecer in ('aprovar', 'devolver', 'recusar')),
    constraint analise_prioridade_valida check (
        prioridade is null or prioridade in ('baixa', 'media', 'alta')
    ),
    constraint analise_justificativa_obrigatoria check (
        parecer = 'aprovar' or (justificativa is not null and char_length(justificativa) between 10 and 500)
    )
);

comment on table elovital.analise_solicitacao is
    'Parecer humano. Toda decisão é auditada e a justificativa é obrigatória em devolver/recusar.';

create index analise_solicitacao_idx on elovital.analise_solicitacao (solicitacao_id, criado_em desc);
create index analise_mediador_idx on elovital.analise_solicitacao (mediador_id, criado_em desc);

-- -----------------------------------------------------------------------------
-- 7. Reservas e confirmações
-- -----------------------------------------------------------------------------

create table elovital.reserva (
    id                       uuid primary key default gen_random_uuid(),
    solicitacao_id           uuid not null references elovital.solicitacao (id),
    horario_id               uuid not null references elovital.horario (id),
    assistido_id             uuid not null references elovital.usuario (id),
    apoiador_id              uuid not null references elovital.usuario (id),
    status                   text not null default 'ativa',
    cancelada_por            uuid references elovital.usuario (id),
    motivo_cancelamento      text,
    justificativa_cancelamento text,
    cancelada_em             timestamptz,
    criado_em                timestamptz not null default now(),
    atualizado_em            timestamptz not null default now(),
    constraint reserva_status_valido check (status in ('ativa', 'cancelada', 'concluida', 'bloqueada')),
    constraint reserva_partes_distintas check (assistido_id <> apoiador_id),
    constraint reserva_cancelamento_coerente check (
        (status = 'cancelada') = (cancelada_em is not null)
    ),
    constraint reserva_justificativa_tamanho check (
        justificativa_cancelamento is null
        or char_length(justificativa_cancelamento) <= 500
    )
);

comment on table elovital.reserva is
    'Vínculo entre solicitação e horário. O índice parcial único é a garantia final contra dupla reserva.';

-- Última barreira: um horário não pode ter duas reservas não canceladas.
create unique index reserva_horario_ativo_uidx
    on elovital.reserva (horario_id) where status <> 'cancelada';

-- Uma solicitação não pode ter duas reservas ativas.
create unique index reserva_solicitacao_ativa_uidx
    on elovital.reserva (solicitacao_id) where status = 'ativa';

create index reserva_apoiador_idx on elovital.reserva (apoiador_id, status);
create index reserva_assistido_idx on elovital.reserva (assistido_id, status);
create index reserva_criado_idx on elovital.reserva (criado_em desc);

create table elovital.reserva_confirmacao (
    id            uuid primary key default gen_random_uuid(),
    reserva_id    uuid not null references elovital.reserva (id),
    parte         text not null,
    confirmado_em timestamptz not null default now(),
    constraint reserva_confirmacao_parte_valida check (parte in ('assistido', 'apoiador'))
);

create unique index reserva_confirmacao_uidx on elovital.reserva_confirmacao (reserva_id, parte);

-- -----------------------------------------------------------------------------
-- 8. Comunicação e reputação
-- -----------------------------------------------------------------------------

create table elovital.mensagem (
    id          uuid primary key default gen_random_uuid(),
    reserva_id  uuid not null references elovital.reserva (id),
    autor_id    uuid not null references elovital.usuario (id),
    texto       text not null,
    enviada_em  timestamptz not null default now(),
    lida_em     timestamptz,
    oculta      boolean not null default false,
    oculta_por  uuid references elovital.usuario (id),
    oculta_motivo text,
    constraint mensagem_texto_tamanho check (char_length(texto) between 1 and 1000),
    constraint mensagem_ocultacao_coerente check (
        not oculta or (oculta_por is not null and oculta_motivo is not null)
    )
);

comment on table elovital.mensagem is
    'Chat da reserva. Texto puro, sem anexos. Conteúdo não vai para log e é expurgado após a retenção.';

create index mensagem_reserva_idx on elovital.mensagem (reserva_id, enviada_em);
create index mensagem_autor_idx on elovital.mensagem (autor_id, enviada_em desc);

create table elovital.bloqueio_usuario (
    id            uuid primary key default gen_random_uuid(),
    bloqueador_id uuid not null references elovital.usuario (id),
    bloqueado_id  uuid not null references elovital.usuario (id),
    reserva_id    uuid references elovital.reserva (id),
    motivo        text,
    criado_em     timestamptz not null default now(),
    constraint bloqueio_partes_distintas check (bloqueador_id <> bloqueado_id),
    constraint bloqueio_motivo_tamanho check (motivo is null or char_length(motivo) <= 500)
);

create unique index bloqueio_usuario_uidx on elovital.bloqueio_usuario (bloqueador_id, bloqueado_id);
create index bloqueio_bloqueado_idx on elovital.bloqueio_usuario (bloqueado_id, criado_em desc);

create table elovital.avaliacao (
    id            uuid primary key default gen_random_uuid(),
    reserva_id    uuid not null references elovital.reserva (id),
    autor_id      uuid not null references elovital.usuario (id),
    alvo_id       uuid not null references elovital.usuario (id),
    nota          smallint not null,
    comentario    text,
    pontualidade  smallint,
    comunicacao   smallint,
    cuidado       smallint,
    respeito      smallint,
    sinalizada    boolean not null default false,
    oculta        boolean not null default false,
    oculta_por    uuid references elovital.usuario (id),
    oculta_motivo text,
    criado_em     timestamptz not null default now(),
    constraint avaliacao_nota_valida check (nota between 1 and 5),
    constraint avaliacao_comentario_tamanho check (
        comentario is null or char_length(comentario) <= 300
    ),
    constraint avaliacao_criterios_validos check (
        (pontualidade is null or pontualidade between 1 and 5)
        and (comunicacao is null or comunicacao between 1 and 5)
        and (cuidado is null or cuidado between 1 and 5)
        and (respeito is null or respeito between 1 and 5)
    ),
    constraint avaliacao_partes_distintas check (autor_id <> alvo_id),
    constraint avaliacao_ocultacao_coerente check (
        not oculta or (oculta_por is not null and oculta_motivo is not null)
    )
);

comment on table elovital.avaliacao is
    'Imutável após o envio. A mediação pode ocultar o comentário, nunca alterar a nota.';

create unique index avaliacao_reserva_autor_uidx on elovital.avaliacao (reserva_id, autor_id);
create index avaliacao_alvo_idx on elovital.avaliacao (alvo_id, criado_em desc);
create index avaliacao_sinalizada_idx on elovital.avaliacao (sinalizada) where sinalizada;

create table elovital.notificacao (
    id         uuid primary key default gen_random_uuid(),
    usuario_id uuid not null references elovital.usuario (id),
    categoria  text not null,
    titulo     text not null,
    corpo      text,
    canal      text not null default 'interna',
    lida_em    timestamptz,
    enviada_em timestamptz,
    criado_em  timestamptz not null default now(),
    constraint notificacao_categoria_valida check (
        categoria in ('reserva', 'cancelamento', 'mensagem', 'solicitacao', 'prazo',
                      'denuncia', 'ocorrencia', 'seguranca', 'termos', 'mediacao')
    ),
    constraint notificacao_canal_valido check (canal in ('interna', 'email', 'push')),
    constraint notificacao_titulo_tamanho check (char_length(titulo) between 1 and 120)
);

create index notificacao_usuario_idx on elovital.notificacao (usuario_id, lida_em);
create index notificacao_criado_idx on elovital.notificacao (criado_em);

create table elovital.ocorrencia (
    id              uuid primary key default gen_random_uuid(),
    reserva_id      uuid references elovital.reserva (id),
    solicitacao_id  uuid references elovital.solicitacao (id),
    aberta_por      uuid not null references elovital.usuario (id),
    tipo            text not null,
    descricao       text,
    status          text not null default 'aberta',
    resolvida_por   uuid references elovital.usuario (id),
    resolvida_em    timestamptz,
    resolucao       text,
    criado_em       timestamptz not null default now(),
    atualizado_em   timestamptz not null default now(),
    constraint ocorrencia_tipo_valido check (
        tipo in ('desistencia', 'nao_comparecimento', 'falta_resposta',
                 'discordancia_conclusao', 'falha_tecnica', 'cancelamento_tardio', 'outro')
    ),
    constraint ocorrencia_status_valido check (
        status in ('aberta', 'em_analise', 'resolvida', 'arquivada')
    ),
    constraint ocorrencia_vinculo_obrigatorio check (reserva_id is not null or solicitacao_id is not null),
    constraint ocorrencia_resolucao_coerente check (
        (status in ('resolvida', 'arquivada')) = (resolvida_em is not null)
    )
);

create index ocorrencia_reserva_idx on elovital.ocorrencia (reserva_id);
create index ocorrencia_status_idx on elovital.ocorrencia (status, criado_em desc);
create index ocorrencia_aberta_por_idx on elovital.ocorrencia (aberta_por, criado_em desc);

-- -----------------------------------------------------------------------------
-- 9. Moderação
-- -----------------------------------------------------------------------------

create table elovital.denuncia (
    id             uuid primary key default gen_random_uuid(),
    protocolo      text not null default ('DEN-' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12))),
    denunciante_id uuid not null references elovital.usuario (id),
    alvo_tipo      text not null,
    alvo_id        uuid not null,
    denunciado_id  uuid references elovital.usuario (id),
    motivo         text not null,
    descricao      text,
    status         text not null default 'aberta',
    resultado      text,
    analisada_por  uuid references elovital.usuario (id),
    analisada_em   timestamptz,
    criado_em      timestamptz not null default now(),
    atualizado_em  timestamptz not null default now(),
    constraint denuncia_alvo_valido check (alvo_tipo in ('perfil', 'solicitacao', 'reserva', 'mensagem')),
    constraint denuncia_motivo_valido check (
        motivo in ('comportamento_inadequado', 'assedio', 'pedido_dinheiro', 'perfil_falso',
                   'conteudo_ofensivo', 'risco_seguranca', 'outro')
    ),
    constraint denuncia_status_valido check (
        status in ('aberta', 'em_analise', 'procedente', 'improcedente', 'arquivada')
    ),
    constraint denuncia_descricao_tamanho check (
        descricao is null or char_length(descricao) <= 500
    ),
    constraint denuncia_analise_coerente check (
        (status in ('procedente', 'improcedente', 'arquivada')) = (analisada_em is not null)
    )
);

comment on table elovital.denuncia is
    'Identidade do denunciante não é revelada ao denunciado. Protocolo é o identificador mostrado ao usuário.';

create unique index denuncia_protocolo_uidx on elovital.denuncia (protocolo);
create index denuncia_denunciado_idx on elovital.denuncia (denunciado_id, criado_em desc);
create index denuncia_status_idx on elovital.denuncia (status, criado_em);
create index denuncia_denunciante_idx on elovital.denuncia (denunciante_id, criado_em desc);

create table elovital.sancao (
    id          uuid primary key default gen_random_uuid(),
    usuario_id  uuid not null references elovital.usuario (id),
    mediador_id uuid not null references elovital.usuario (id),
    tipo        text not null,
    motivo      text not null,
    inicio_em   timestamptz not null default now(),
    fim_em      timestamptz,
    denuncia_id uuid references elovital.denuncia (id),
    criado_em   timestamptz not null default now(),
    constraint sancao_tipo_valido check (tipo in ('advertencia', 'suspensao', 'bloqueio')),
    constraint sancao_motivo_tamanho check (char_length(motivo) between 10 and 1000),
    constraint sancao_periodo_valido check (fim_em is null or fim_em > inicio_em),
    constraint sancao_mediador_distinto check (usuario_id <> mediador_id)
);

create index sancao_usuario_idx on elovital.sancao (usuario_id, criado_em desc);
create index sancao_vigente_idx on elovital.sancao (usuario_id, fim_em);

create table elovital.contestacao (
    id             uuid primary key default gen_random_uuid(),
    sancao_id      uuid references elovital.sancao (id),
    analise_id     uuid references elovital.analise_solicitacao (id),
    contestante_id uuid not null references elovital.usuario (id),
    justificativa  text not null,
    status         text not null default 'aberta',
    analisada_por  uuid references elovital.usuario (id),
    analisada_em   timestamptz,
    decisao        text,
    criado_em      timestamptz not null default now(),
    atualizado_em  timestamptz not null default now(),
    constraint contestacao_vinculo_obrigatorio check (sancao_id is not null or analise_id is not null),
    constraint contestacao_status_valido check (
        status in ('aberta', 'em_analise', 'deferida', 'indeferida')
    ),
    constraint contestacao_justificativa_tamanho check (
        char_length(justificativa) between 10 and 1000
    ),
    constraint contestacao_analise_coerente check (
        (status in ('deferida', 'indeferida')) = (analisada_em is not null)
    )
);

create index contestacao_status_idx on elovital.contestacao (status, criado_em);
create index contestacao_contestante_idx on elovital.contestacao (contestante_id, criado_em desc);

-- -----------------------------------------------------------------------------
-- 10. Auditoria e operação (somente inserção)
-- -----------------------------------------------------------------------------

create table elovital.audit_log (
    id             bigserial primary key,
    criado_em      timestamptz not null default now(),
    ator_id        uuid,
    ator_papel     text,
    acao           text not null,
    entidade       text not null,
    entidade_id    text,
    antes          jsonb,
    depois         jsonb,
    motivo         text,
    ip_hash        bytea,
    user_agent_hash bytea,
    correlacao_id  uuid
);

comment on table elovital.audit_log is
    'Trilha de negócio imutável. UPDATE e DELETE bloqueados por trigger e por privilégio. Retenção de 5 anos.';
comment on column elovital.audit_log.antes is
    'Somente campos alterados. Campos sensíveis viram "[alterado]".';

create index audit_log_entidade_idx on elovital.audit_log (entidade, entidade_id);
create index audit_log_criado_idx on elovital.audit_log (criado_em);
create index audit_log_ator_idx on elovital.audit_log (ator_id, criado_em desc);

create table elovital.acesso_dado_pessoal (
    id           bigserial primary key,
    criado_em    timestamptz not null default now(),
    mediador_id  uuid not null references elovital.usuario (id),
    usuario_id   uuid not null references elovital.usuario (id),
    campo        text not null,
    motivo       text not null,
    entidade     text,
    entidade_id  text,
    constraint acesso_campo_valido check (
        campo in ('cpf', 'telefone', 'endereco', 'documento', 'condicao_saude', 'contato_emergencia')
    ),
    constraint acesso_motivo_tamanho check (char_length(motivo) between 5 and 500)
);

comment on table elovital.acesso_dado_pessoal is
    'Registro de acesso da mediação a dado pessoal. Imutável. Retenção de 5 anos.';

create index acesso_usuario_idx on elovital.acesso_dado_pessoal (usuario_id, criado_em desc);
create index acesso_mediador_idx on elovital.acesso_dado_pessoal (mediador_id, criado_em desc);

create table elovital.expurgo_log (
    id               bigserial primary key,
    criado_em        timestamptz not null default now(),
    regra            text not null,
    tabela           text not null,
    linhas_afetadas  integer not null,
    detalhe          jsonb,
    constraint expurgo_linhas_nao_negativas check (linhas_afetadas >= 0)
);

comment on table elovital.expurgo_log is
    'Prova de anonimização e descarte. Imutável. Retenção de 5 anos.';

create index expurgo_log_regra_idx on elovital.expurgo_log (regra, criado_em desc);

commit;
