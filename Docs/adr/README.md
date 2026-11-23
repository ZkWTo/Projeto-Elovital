# ADRs do EloVital — backend em Java e Spring

- **Status geral:** proposto para revisão
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md)
- **Fonte de requisitos:** [Documento de Requisitos Funcionais do EloVital](../Documento_Requisitos_Funcionais_EloVital_versãopdf.pdf)
- **Stack decidida:** Java 21 + Spring Boot 3, PostgreSQL gerenciado no Supabase

## Por que esta série existe

Os ADRs 0001 a 0004 foram escritos quando a implementação de referência era **Django/Python**. A decisão de produção passou a ser **Java 21 com Spring Boot 3**. Como a troca de linguagem muda autenticação, camadas, migrations, sessão, templates e criptografia de campos, os quatro ADRs originais foram mantidos como histórico e marcados como substituídos.

Esta série cobre **todo o fluxo de usuário descrito no PRD**, do cadastro à mediação, e é a referência para implementação.

## Mapa do fluxo de usuário → ADRs

| # | Etapa do fluxo (PRD) | RF | ADRs principais |
|---|---|---|---|
| 1 | Visitante aceita termos, consentimento de saúde e cria conta | RF001 | [0005](0005-stack-java-spring-boot.md), [0008](0008-seguranca-autenticacao-e-papeis.md), [0009](0009-conteudo-texto-puro-e-validacao.md), [0013](0013-lgpd-e-dados-sensiveis.md) |
| 2 | Envio de documento, selfie e registro profissional | RF016 | [0017](0017-armazenamento-de-documentos.md), [0013](0013-lgpd-e-dados-sensiveis.md), [0011](0011-maquinas-de-estado-do-fluxo.md) |
| 3 | Login, bloqueio por tentativas, recuperação de senha | RF002 | [0008](0008-seguranca-autenticacao-e-papeis.md), [0022](0022-seguranca-da-aplicacao-web.md) |
| 4 | Perfil, preferências, privacidade, exclusão | RF003 | [0013](0013-lgpd-e-dados-sensiveis.md), [0014](0014-auditoria-e-acesso-a-dados.md) |
| 5 | Apoiador publica agenda e recorrência | RF004 | [0011](0011-maquinas-de-estado-do-fluxo.md), [0018](0018-geolocalizacao-cep-e-catalogo.md), [0019](0019-parametros-e-cadastros-base.md) |
| 6 | Assistido cria solicitação de apoio | RF005 | [0009](0009-conteudo-texto-puro-e-validacao.md), [0011](0011-maquinas-de-estado-do-fluxo.md), [0019](0019-parametros-e-cadastros-base.md) |
| 7 | Mediação analisa conta, solicitação e agenda | RF006, RF015 | [0011](0011-maquinas-de-estado-do-fluxo.md), [0014](0014-auditoria-e-acesso-a-dados.md), [0023](0023-painel-de-mediacao.md) |
| 8 | Assistido descobre apoiadores no catálogo | RF007 | [0018](0018-geolocalizacao-cep-e-catalogo.md), [0013](0013-lgpd-e-dados-sensiveis.md) |
| 9 | Reserva atômica do horário | RF008 | [0012](0012-reserva-atomica-e-concorrencia.md), [0011](0011-maquinas-de-estado-do-fluxo.md) |
| 10 | Chat entre as partes | RF011 | [0010](0010-entrega-web-e-api.md), [0009](0009-conteudo-texto-puro-e-validacao.md), [0015](0015-notificacoes-assincronas.md) |
| 11 | Cancelamento e remarcação | RF009 | [0011](0011-maquinas-de-estado-do-fluxo.md), [0012](0012-reserva-atomica-e-concorrencia.md) |
| 12 | Dupla confirmação da conclusão | RF012 | [0011](0011-maquinas-de-estado-do-fluxo.md), [0016](0016-rotinas-agendadas-e-expiracao.md) |
| 13 | Avaliação mútua | RF013 | [0009](0009-conteudo-texto-puro-e-validacao.md), [0011](0011-maquinas-de-estado-do-fluxo.md) |
| 14 | Denúncia, sanção e contestação | RF014, RF015 | [0023](0023-painel-de-mediacao.md), [0014](0014-auditoria-e-acesso-a-dados.md), [0011](0011-maquinas-de-estado-do-fluxo.md) |
| 15 | Notificações em todos os eventos | RF010 | [0015](0015-notificacoes-assincronas.md), [0016](0016-rotinas-agendadas-e-expiracao.md) |
| 16 | Histórico, ocorrências e expiração | RF017 | [0016](0016-rotinas-agendadas-e-expiracao.md), [0014](0014-auditoria-e-acesso-a-dados.md) |
| — | Parâmetros configuráveis (transversal) | RNF010 | [0019](0019-parametros-e-cadastros-base.md) |
| — | Observabilidade e operação (transversal) | RNF005, RNF012 | [0020](0020-observabilidade-e-operacao.md) |
| — | Qualidade e testes (transversal) | RNF001–RNF015 | [0021](0021-testes-qualidade-e-ci.md) |
| — | Segurança web (transversal) | RNF011, RS001–RS013 | [0022](0022-seguranca-da-aplicacao-web.md) |

## Lista de ADRs

| ADR | Título | Substitui | Status |
|---|---|---|---|
| [0005](0005-stack-java-spring-boot.md) | Stack Java 21 + Spring Boot 3 | parte de 0001 | Proposto |
| [0006](0006-arquitetura-em-camadas.md) | Arquitetura modular em camadas | 0001 | Proposto |
| [0007](0007-persistencia-postgresql-jpa-flyway.md) | Persistência com PostgreSQL, JPA e Flyway | 0004 | Proposto |
| [0008](0008-seguranca-autenticacao-e-papeis.md) | Usuário, papéis, autenticação e sessão | 0002 | Proposto |
| [0009](0009-conteudo-texto-puro-e-validacao.md) | Conteúdo em texto puro e validação | 0003 | Proposto |
| [0010](0010-entrega-web-e-api.md) | Entrega web: páginas, interações e API | — | Proposto |
| [0011](0011-maquinas-de-estado-do-fluxo.md) | Máquinas de estado do fluxo | — | Proposto |
| [0012](0012-reserva-atomica-e-concorrencia.md) | Reserva atômica e concorrência | — | Proposto |
| [0013](0013-lgpd-e-dados-sensiveis.md) | LGPD, criptografia e retenção | — | Proposto |
| [0014](0014-auditoria-e-acesso-a-dados.md) | Auditoria e acesso a dados pessoais | — | Proposto |
| [0015](0015-notificacoes-assincronas.md) | Notificações assíncronas e central | — | Proposto |
| [0016](0016-rotinas-agendadas-e-expiracao.md) | Rotinas agendadas, expiração e expurgo | — | Proposto |
| [0017](0017-armazenamento-de-documentos.md) | Armazenamento de fotos e documentos | — | Proposto |
| [0018](0018-geolocalizacao-cep-e-catalogo.md) | CEP, geolocalização e catálogo por distância | — | Proposto |
| [0019](0019-parametros-e-cadastros-base.md) | Parâmetros configuráveis e cadastros-base | — | Proposto |
| [0020](0020-observabilidade-e-operacao.md) | Observabilidade e operação sem dados pessoais | — | Proposto |
| [0021](0021-testes-qualidade-e-ci.md) | Testes, qualidade e CI | — | Proposto |
| [0022](0022-seguranca-da-aplicacao-web.md) | Segurança da aplicação web | — | Proposto |
| [0023](0023-painel-de-mediacao.md) | Painel de mediação e moderação | — | Proposto |

## ADRs históricos (substituídos)

| ADR | Título | Situação |
|---|---|---|
| [0001](0001-mvt-com-camada-de-service.md) | MVT com camada de service | Substituído por 0005 e 0006. O princípio de manter regra fora da camada HTTP permanece. |
| [0002](0002-usuario-papeis-e-autenticacao.md) | Usuário, papéis e autenticação | Substituído por 0008. O modelo de CPF, papéis e status permanece. |
| [0003](0003-conteudo-em-texto-puro.md) | Conteúdo em texto puro | Substituído por 0009. A decisão de texto puro permanece. |
| [0004](0004-supabase-postgresql.md) | Supabase/PostgreSQL | Substituído por 0007. A decisão de banco permanece. |

## Convenções desta série

Cada ADR segue a mesma estrutura:

- **Contexto** — o problema e o que o PRD exige.
- **Decisão** — o que foi escolhido, com detalhe de implementação.
- **Fluxo de usuário afetado** — onde a decisão aparece nas jornadas.
- **Alternativas consideradas** — o que foi descartado e por quê.
- **Consequências** — custos, riscos e o que fica mais difícil.
- **Critérios de aceite** — como verificar que a decisão foi cumprida.
- **Decisões pendentes** — o que depende de aprovação, referenciando o PRD.

## Regras que valem para toda a série

1. O [PRD](../prd/0001-detalhamento-prd-e-adrs.md) e o Documento de Requisitos são a fonte de verdade do comportamento.
2. Nenhuma solicitação é publicada sem mediação humana.
3. Nenhuma operação crítica grava estado parcial.
4. Nenhum dado sensível aparece em log, métrica, rastreamento ou resposta de erro.
5. Nenhuma exclusão física de conta, solicitação ou mensagem; usa-se status e anonimização.
6. Toda transição de estado relevante gera auditoria.
7. Todo prazo, limite e raio vem da tabela `parametro`, não de constante no código.

## Como aprovar

1. Ler o PRD e confirmar o escopo da primeira versão.
2. Ler os ADRs 0005 a 0009 (fundação) e aprovar a stack.
3. Ler os ADRs 0011, 0012 e 0013 (fluxo crítico: estados, reserva e dados sensíveis).
4. Ler os ADRs transversais 0014 a 0023.
5. Resolver as decisões pendentes herdadas do PRD (seção 9.4 do PRD e checklist da Parte IV).
6. Marcar cada ADR de `Proposto` para `Aceito`.

## Relação com a proposta de banco

O pacote [Proposta de banco Supabase](../propostas/banco-supabase/README.md) continua válido para **schema, papéis, RLS, criptografia, retenção e operação**. O que muda é o cliente: o schema deixa de ser gerado pelo Django e passa a ser versionado por **Flyway** (ver [ADR 0007](0007-persistencia-postgresql-jpa-flyway.md)). Os nomes de tabelas, colunas e as funções SQL da proposta permanecem a referência.
