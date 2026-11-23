# ADR 0007: Persistência com PostgreSQL, Spring Data JPA e Flyway

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md)
- **Proposta detalhada:** [Banco Supabase](../propostas/banco-supabase/README.md)
- **Relacionado:** [ADR 0005](0005-stack-java-spring-boot.md), [ADR 0012](0012-reserva-atomica-e-concorrencia.md), [ADR 0013](0013-lgpd-e-dados-sensiveis.md)
- **Substitui:** [ADR 0004](0004-supabase-postgresql.md) (a decisão de banco permanece; muda o cliente e o versionamento do schema)

## Contexto

O EloVital precisa de:

- reserva atômica, com garantia de que duas pessoas não reservem o mesmo horário (RF008, RNF006);
- disponibilidade de 99% e operação 24 h (RNF005);
- pesquisa por especialidade, data e distância com índices e paginação (RNF009);
- dados de saúde e documentos de identidade (RNF011, RS001);
- backup gerenciado com restauração testada (RNF008);
- auditoria imutável (RNF012).

O ADR 0004 já decidiu **PostgreSQL gerenciado no Supabase, região `sa-east-1`**, com schema `elovital`, papéis separados e RLS como defesa em profundidade. O que muda com Java é o **cliente de acesso** e a **ferramenta de migração**: sai o ORM do Django e as migrations do Django; entra Spring Data JPA + Hibernate e Flyway.

## Decisão

### Banco e conexão

| Item | Decisão |
|---|---|
| Banco | PostgreSQL 16, Supabase, `sa-east-1` |
| Schema | `elovital` (não usar `public` para domínio) |
| Conexão | Supavisor em **modo sessão (5432)**, TLS `verify-full` |
| Driver | `org.postgresql.Driver` |
| Pool | HikariCP, `maximum-pool-size` por instância |
| Migrations | **Flyway** |
| Acesso | Apenas servidor; Data API desabilitada |

`application.yml` (valores fictícios):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:5432/postgres?sslmode=verify-full&currentSchema=elovital&ApplicationName=elovital-web
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 10000
      max-lifetime: 1800000
      keepalive-time: 300000
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
        default_schema: elovital
        query:
          in_clause_parameter_padding: true
  flyway:
    enabled: true
    schemas: elovital
    baseline-on-migrate: false
    validate-on-migrate: true
    locations: classpath:db/migration
```

Regras:

- `ddl-auto=validate` sempre. Nunca `update`, `create` ou `create-drop` fora de teste local isolado.
- `open-in-view=false`: nenhuma consulta implícita na renderização do template. Se o template precisa de dado, o caso de uso devolve DTO com o dado carregado.
- O fuso da sessão JDBC é UTC; a conversão para `America/Sao_Paulo` acontece na borda.
- O pool é dimensionado para `instâncias × pool por instância < limite do pooler`. Sem essa conta, o pico esgota conexões.
- `statement_timeout` e `lock_timeout` são aplicados no papel `elovital_app` (ver proposta 01) e reforçados em `spring.jpa.properties.jakarta.persistence.query.timeout` quando necessário.

### Flyway como fonte de verdade do schema

Esta é a mudança central em relação ao ADR 0004:

- **O schema é versionado por migrations Flyway**, escritas à mão, revisadas em pull request.
- O SQL de referência da proposta (`sql/001_schema.sql`, `002_seguranca.sql`, `003_funcoes.sql`) é a base das migrations `V1`, `V2` e `V3`, mas **não é executado manualmente** em nenhum ambiente.
- O Django deixa de existir como gerador de schema.
- `flyway_schema_history` fica no schema `elovital`.
- O deploy roda `flyway migrate` com o papel `elovital_migrator`; a aplicação roda com `elovital_app`.
- Proibido `flyway repair` em produção sem registro e aprovação.

Convenção de nomes:

```text
V1__schema_inicial.sql
V2__seguranca_rls_e_privilegios.sql
V3__funcoes_de_reserva_e_conclusao.sql
V4__indices_de_catalogo_e_historico.sql
V5__parametros_iniciais.sql
R__views_mascaradas.sql        # repeatable
```

Regras de migration:

- Toda migration nova é **aditiva** quando possível (expand/contract).
- Renomear coluna em duas etapas: adicionar nova, preencher, trocar código, remover antiga.
- Índice grande: `CREATE INDEX CONCURRENTLY` fora de transação, em migration própria com `flyway.mixed=true` apenas para esse arquivo.
- Migration destrutiva exige janela, backup e revisão; nunca no mesmo deploy de uma mudança de código que ainda usa a coluna.
- Toda migration que cria tabela com dado pessoal inclui grants, comentários, política de retenção e RLS explícita, conforme a proposta 01.
- CI roda `flyway migrate` do zero e valida contra `flyway validate`.

### Mapeamento JPA

```java
@Entity
@Table(name = "usuario", schema = "elovital")
public class Usuario {
    @Id
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;          // normalizado para minúsculas

    @Column(name = "nome_completo", nullable = false, length = 120)
    private String nomeCompleto;

    @Enumerated(EnumType.STRING)
    @Column(name = "papel", nullable = false, length = 20)
    private Papel papel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatusConta status;

    @Version
    private long versao;
}
```

Convenções:

| Tipo de dado | Tipo Java | Coluna |
|---|---|---|
| Identificador exposto | `UUID` | `uuid` |
| Instante | `Instant` | `timestamptz` |
| Data de calendário | `LocalDate` | `date` |
| Hora de agenda | `LocalTime` | `time` |
| Texto longo (chat, descrição) | `String` | `text` |
| Enum de estado | `enum` + `@Enumerated(STRING)` | `varchar` com `CHECK` |
| Dinheiro | não existe no escopo | — |
| Coordenada | `Point` (hibernate-spatial) | `geography(Point,4326)` |
| JSON de auditoria | `String`/`JsonNode` | `jsonb` |

Regras:

- **Nenhum enum ordinal.** Sempre `EnumType.STRING`, com `CHECK` no banco.
- `UUID` gerado na aplicação (`UUID.randomUUID()`) ou no banco; escolher um e manter. Recomendação: aplicação, para permitir montar o agregado antes de persistir.
- Entidades não usam `equals`/`hashCode` baseados em todos os campos; usam o id.
- Associações `@ManyToOne` são `LAZY` por padrão. `EAGER` só com justificativa e teste de N+1.
- Consultas de catálogo e histórico usam **projeção** (`interface` ou `record` com `@Query`), nunca entidade completa.
- Nada de `@Data`/Lombok (ver [ADR 0005](0005-stack-java-spring-boot.md)).

### Busca textual e tipos especiais

- `citext` para e-mail: a coluna é `citext` e o índice único garante "sem diferenciar maiúsculas". No Java, o service normaliza para minúsculas e valida formato.
- `pg_trgm` + `unaccent` para busca de nome na mediação: consulta nativa com índice GIN.
- `jsonb` para `antes`/`depois` da auditoria, com campos sensíveis mascarados antes de gravar ([ADR 0014](0014-auditoria-e-acesso-a-dados.md)).
- `geography` e `ST_DWithin` para distância ([ADR 0018](0018-geolocalizacao-cep-e-catalogo.md)).

### Transações e concorrência

- Isolamento padrão: `READ COMMITTED`.
- Operações compostas usam `@Transactional` no caso de uso.
- Reserva usa bloqueio pessimista explícito e constraint única parcial ([ADR 0012](0012-reserva-atomica-e-concorrencia.md)).
- Edição concorrente de horário usa `@Version` (bloqueio otimista) e retorna 409.
- **Nenhuma chamada externa dentro de transação** (e-mail, Storage, CEP, geocodificação). Efeito externo acontece depois do commit, por evento ([ADR 0015](0015-notificacoes-assincronas.md)).

### Leitura e desempenho

- Paginação sempre com `Pageable` e `LIMIT`; nunca "carregar tudo e filtrar em Java".
- Índices definidos na proposta 02, seção 5, são parte das migrations.
- `EXPLAIN (ANALYZE, BUFFERS)` obrigatório para consultas de catálogo e histórico antes do piloto.
- Nenhum `SELECT *` em tabela com coluna cifrada; projeção explícita.

### Papéis e privilégios

Mantidos do ADR 0004:

| Papel | Uso | Privilégios |
|---|---|---|
| `elovital_migrator` | Flyway | DDL; dono do schema |
| `elovital_app` | Aplicação | DML; sem DDL; sem `DELETE` em auditoria; sem `UPDATE` de CPF |
| `elovital_readonly` | Métricas e suporte | `SELECT` em views mascaradas |
| `anon`, `authenticated` | Data API | Nenhum privilégio no schema |

A autorização de linha continua nos casos de uso; RLS é defesa em profundidade.

### Desenvolvimento e testes

- Local: `docker compose` com `postgis/postgis:16-3.4` **na mesma versão** do Supabase.
- Testcontainers usa a mesma imagem; os testes de integração rodam contra PostgreSQL real, não H2.
- Seed sintético versionado (`R__seed_dev.sql` apenas no perfil `dev`). Nunca dados reais.
- `flyway validate` no CI e restauração de backup testada trimestralmente.

## Fluxo de usuário afetado

| Etapa | Impacto |
|---|---|
| Cadastro | Persistência de usuário, endereço, termos e perfis; unicidade de e-mail e CPF por índice |
| Verificação | `documento_verificacao` com status, hash e data de expurgo |
| Agenda | `horario` e `recorrencia`; conflito e status |
| Solicitação | `solicitacao` e `solicitacao_periodo`; expiração indexada |
| Análise | `analise_solicitacao` + auditoria |
| Catálogo | Projeções e índice por status/data/especialidade |
| Reserva | Transação e constraint única parcial |
| Chat | `mensagem` com índice por reserva e data |
| Conclusão | `reserva_confirmacao` e status da reserva |
| Avaliação | `avaliacao` com unicidade por reserva/autor |
| Mediação | Consultas com views mascaradas |
| Expiração/expurgo | Rotinas e índices por data ([ADR 0016](0016-rotinas-agendadas-e-expiracao.md)) |

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| `ddl-auto=update` | Schema imprevisível, sem revisão, risco de perda de dado. |
| Liquibase | Bom, mas Flyway é mais simples para SQL puro e já é padrão em PostgreSQL. |
| JOOQ para tudo | Excelente controle de SQL, mas aumenta o custo de aprendizado e o volume de código para CRUD simples. |
| H2 em teste | Não reproduz PostGIS, `citext`, RLS nem locking real. |
| Manter migrations do Django | Não existe Django no backend Java. |

## Consequências

- O SQL do schema passa a ser escrito e revisado pelo time Java; a proposta de banco deixa de ser "executável por ORM" e vira migration versionada.
- Testes de integração ficam mais lentos (container), mas reais.
- O time precisa cuidar de N+1, `open-in-view=false` e projeções; erros comuns de JPA aparecem cedo em teste.
- Flyway exige disciplina de versionamento: migration aplicada não é editada.
- A dependência do Supabase continua; o contrato é PostgreSQL padrão.

## Critérios de aceite

- [ ] `flyway migrate` roda do zero em banco vazio e `flyway validate` passa.
- [ ] A aplicação sobe com `ddl-auto=validate` sem divergência de schema.
- [ ] Reserva concorrente testada com duas conexões ([ADR 0012](0012-reserva-atomica-e-concorrencia.md)).
- [ ] Nenhuma migration aplicada é alterada depois do deploy.
- [ ] Nenhum `open-in-view=true`.
- [ ] Nenhum `SELECT *` em tabela com coluna cifrada.
- [ ] CI usa PostGIS 16-3.4, mesma versão da produção.
- [ ] `elovital_app` não consegue executar DDL nem `DELETE` em auditoria.
- [ ] Nenhum segredo no `application.yml` versionado.

## Decisões pendentes

> **DECISÃO PENDENTE 44:** definir `maximum-pool-size` por instância e o número de instâncias iniciais. Sem isso, o limite do Supavisor não pode ser validado.
>
> **DECISÃO PENDENTE 45:** decidir se as funções SQL `reservar_horario`, `cancelar_reserva` e `confirmar_conclusao` da proposta serão mantidas como última barreira ou substituídas por lógica 100% Java com constraint única. A recomendação é **uma única fonte de verdade em Java** (ADR 0012), usando a constraint do banco como rede de segurança.
>
> **DECISÃO PENDENTE 46:** confirmar se `citext` será usado ou se a unicidade será por índice em `lower(email)`. Ambas funcionam; a segunda reduz dependência de extensão.
