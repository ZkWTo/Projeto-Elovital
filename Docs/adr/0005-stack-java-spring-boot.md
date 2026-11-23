# ADR 0005: Backend em Java 21 com Spring Boot 3

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md)
- **Relacionado:** [ADR 0006](0006-arquitetura-em-camadas.md), [ADR 0007](0007-persistencia-postgresql-jpa-flyway.md), [ADR 0008](0008-seguranca-autenticacao-e-papeis.md)
- **Substitui parcialmente:** [ADR 0001](0001-mvt-com-camada-de-service.md) (a decisão de framework; o princípio de regra fora da camada HTTP permanece)

## Contexto

O EloVital é um site responsivo, mobile-first, sem aplicativos nativos, com regras de negócio densas: curadoria humana de contas e solicitações, reserva atômica, liberação progressiva de dados, dupla confirmação, chat interno, avaliação, denúncia, sanção, contestação e auditoria. Os requisitos não funcionais exigem transacionalidade (RNF006), auditabilidade (RNF012), LGPD (RNF011), acessibilidade (RNF002), disponibilidade de 99% (RNF005) e desempenho p95 ≤ 3 s (RNF004).

A implementação de referência era Django/Python. A decisão de produção passou a ser **Java** com **Spring**. As razões registradas:

- tipagem forte e compilação antecipada para um domínio com muitos estados e invariantes;
- Spring Security, Spring Data, Spring Validation, Spring Mail, Spring Scheduling e Micrometer cobrem, sem integrações frágeis, os requisitos de autenticação, persistência, validação, e-mail, rotinas e observabilidade;
- **virtual threads** no Java 21 absorvem bem o modelo bloqueante de JDBC e e-mail, que é o perfil do EloVital (muitas requisições curtas e IO de banco), sem reescrever tudo em reativo;
- maturidade do ecossistema no mercado brasileiro, o que reduz risco de contratação e manutenção;
- o Spring Boot 3 tem suporte de primeira classe a `ProblemDetail`, observabilidade (Micrometer/OpenTelemetry), Testcontainers e containers.

Alternativas consideradas:

| Alternativa | Por que não foi escolhida |
|---|---|
| Manter Django/Python | Contraria a decisão de produção; o time definiu Java como linguagem do backend. |
| Node.js/NestJS | Ecossistema adequado, mas menos forte em transações JPA/locking explícito e em segurança corporativa já pronta. |
| .NET | Tecnicamente equivalente, mas fora da decisão do projeto e do perfil do time. |
| Quarkus/Micronaut | Menor familiaridade; o ganho de startup não é relevante para um monólito web. |
| Microsserviços | Aumentaria custo operacional sem necessidade: o volume inicial não justifica e o domínio é fortemente acoplado (reserva toca agenda, solicitação, chat e notificação). |

## Decisão

### Versões e build

| Item | Decisão |
|---|---|
| Linguagem | **Java 21 LTS** |
| Framework | **Spring Boot 3.4.x** (linha 3.x) |
| Build | **Maven** com `mvnw` versionado |
| Empacotamento | JAR executável em imagem de container (JRE 21, non-root) |
| Banco | PostgreSQL 16 gerenciado no Supabase ([ADR 0007](0007-persistencia-postgresql-jpa-flyway.md)) |
| Migrations | Flyway |
| Testes | JUnit 5, AssertJ, Mockito, Testcontainers, ArchUnit ([ADR 0021](0021-testes-qualidade-e-ci.md)) |

Dependências iniciais (versões gerenciadas pelo BOM do Spring Boot):

```text
spring-boot-starter-web
spring-boot-starter-thymeleaf
spring-boot-starter-security
spring-boot-starter-data-jpa
spring-boot-starter-validation
spring-boot-starter-mail
spring-boot-starter-actuator
spring-boot-starter-cache
org.flywaydb:flyway-core
org.flywaydb:flyway-database-postgresql
org.hibernate.orm:hibernate-spatial
com.github.ben-manes.caffeine:caffeine
net.javacrumbs.shedlock:shedlock-spring + shedlock-provider-jdbc-template
org.springdoc:springdoc-openapi-starter-webmvc-ui
io.micrometer:micrometer-registry-prometheus
io.micrometer:micrometer-tracing-bridge-otel
org.testcontainers:postgresql, junit-jupiter
com.tngtech.archunit:archunit-junit5
```

### Estrutura do repositório

```text
elovital/
  pom.xml                  # reator único (não há multi-módulo Maven na v1)
  mvnw, mvnw.cmd
  src/main/java/br/com/elovital/
    EloVitalApplication.java
    shared/                 # kernel: exceções, segurança, cripto, relógio, paginação
    contas/
    catalogo/
    agenda/
    solicitacoes/
    reservas/
    comunicacao/
    reputacao/
    moderacao/
    auditoria/
  src/main/resources/
    application.yml
    application-dev.yml
    application-staging.yml
    application-prod.yml
    db/migration/           # Flyway
    templates/              # Thymeleaf
    messages.properties     # pt-BR
  src/test/java/...
```

O monólito é **modular**: um único artefato, um único deploy, módulos delimitados por pacote (detalhe em [ADR 0006](0006-arquitetura-em-camadas.md)).

### Convenções de código

- **Linguagem ubíqua em português** para o domínio: `Usuario`, `Solicitacao`, `Horario`, `Reserva`, `Avaliacao`, `Denuncia`, `Sancao`. Nomes de infraestrutura e de bibliotecas seguem o inglês da plataforma (`Repository`, `Controller`, `Service`).
- **Sem Lombok.** DTOs, comandos e resultados usam `record`. Entidades JPA têm acessores explícitos. Motivo: previsibilidade com JPA, depuração e ausência de geração de código em tempo de compilação.
- **Imutabilidade por padrão** em comandos, resultados e value objects.
- **Injeção por construtor**, sem campo anotado com `@Autowired`.
- **`Optional` apenas em retorno de busca**, nunca em campo ou parâmetro.
- **Proibido `double`/`float` para dinheiro** (não há pagamento no escopo) e para coordenadas geográficas (usar `double` apenas dentro do adapter PostGIS, conforme [ADR 0018](0018-geolocalizacao-cep-e-catalogo.md)).
- Formatação por **Spotless + google-java-format** no build.

### Configuração

- Toda configuração sensível vem de **variável de ambiente** e é vinculada por classes `@ConfigurationProperties` validadas com Bean Validation. A aplicação **falha ao subir** se faltar segredo obrigatório.
- Perfis: `dev`, `staging`, `prod`. Nunca há `prod` com dados sintéticos nem `dev` com dados reais.
- `spring.jpa.hibernate.ddl-auto=validate` em todos os perfis; o schema é do Flyway.
- `spring.jpa.open-in-view=false`.
- `spring.threads.virtual.enabled=true`, com pool de conexões dimensionado (o gargalo passa a ser o banco, não a thread).
- Fuso: instantes gravados em UTC e convertidos para `America/Sao_Paulo` na borda; `Clock` injetável para testar prazos (24 h, 72 h, 7 dias).
- Idioma único: **pt-BR**. Mensagens em `messages.properties`, datas `dd/MM/yyyy`, moeda não se aplica.

### Segredos e configuração obrigatória

```text
DB_URL, DB_USER, DB_PASSWORD, DB_SSL_ROOT_CERT
CPF_HMAC_PEPPER, CPF_ENC_KEY, CPF_ENC_KEY_VERSION
SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, SUPABASE_STORAGE_BUCKET
MAIL_HOST, MAIL_PORT, MAIL_USER, MAIL_PASSWORD, MAIL_FROM
APP_BASE_URL, SERVER_PORT, SPRING_PROFILES_ACTIVE
```

Nenhum segredo no Git, na imagem ou em `application.yml` versionado.

### Operação

- `actuator/health` com sondas de liveness e readiness para o orquestrador.
- `actuator/prometheus` para métricas (ver [ADR 0020](0020-observabilidade-e-operacao.md)).
- Logs em JSON no console, sem dados pessoais.
- A aplicação é **stateless quanto a arquivos**; sessão fica no servidor apenas se houver instância única — com múltiplas instâncias, a sessão é persistida (ver [ADR 0008](0008-seguranca-autenticacao-e-papeis.md)).

## Fluxo de usuário afetado

Esta decisão é a base de todas as etapas do PRD. Ela define como cada tela, cada regra e cada rotina serão implementadas:

1. Cadastro e consentimento (RF001) — formulário Thymeleaf + validação Bean Validation + service.
2. Verificação (RF016) — upload multipart + adapter de Storage.
3. Login (RF002) — Spring Security + tabela de tentativas.
4. Agenda, solicitação, catálogo, reserva, chat, conclusão, avaliação, denúncia e mediação — módulos descritos em [ADR 0006](0006-arquitetura-em-camadas.md).
5. Rotinas de expiração e lembrete — Spring Scheduling ([ADR 0016](0016-rotinas-agendadas-e-expiracao.md)).

## Consequências

- O time precisa dominar Java 21, Spring Boot, JPA e Spring Security; a curva é maior que a de Django para quem vem de Python.
- O tempo de startup e o consumo de memória são maiores que em frameworks leves, o que exige dimensionamento de container.
- A tipagem forte e a compilação reduzem erros de estado, mas aumentam o volume de código de mapeamento entre entidade, DTO e template.
- Virtual threads simplificam o modelo concorrente, mas **não** resolvem contenção de banco: o pool Hikari e o `lock_timeout` continuam sendo o limite real.
- A ausência de Lombok aumenta o código escrito, em troca de menos "magia" em entidades JPA.
- A decisão fica presa ao ecossistema Spring; migrar depois exige reescrever segurança, transações e web.

## Critérios de aceite

- [ ] `./mvnw verify` compila e roda a suíte em Java 21.
- [ ] A aplicação sobe com `dev` e falha rápido se um segredo obrigatório não estiver definido em `prod`.
- [ ] `ddl-auto` é `validate` em todos os perfis.
- [ ] Nenhuma dependência fora do BOM sem justificativa registrada no `pom.xml`.
- [ ] Imagem sobe como usuário não-root e responde a `/actuator/health`.
- [ ] Todas as mensagens de interface estão em pt-BR em `messages.properties`.
- [ ] Todo prazo de negócio é lido de `Clock`/`parametro`, nunca de `LocalDateTime.now()` direto no service.

## Decisões pendentes

> **DECISÃO PENDENTE 38:** confirmar Maven como build oficial (alternativa: Gradle Kotlin DSL). Impacto: baixo, mas muda comandos de CI e cache.
>
> **DECISÃO PENDENTE 39:** confirmar Spring Boot 3.4.x como versão fixa da v1 e a política de atualização de patch (mensal? a cada CVE?).
>
> **DECISÃO PENDENTE 40:** confirmar se o frontend será renderizado no servidor (Thymeleaf) ou se haverá SPA consumindo `/api/v1`. A decisão muda o esforço de acessibilidade e de chat em tempo real — ver [ADR 0010](0010-entrega-web-e-api.md).
