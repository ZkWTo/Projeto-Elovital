# ADR 0008: Usuário, papéis, autenticação e sessão com Spring Security

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md)
- **Relacionado:** [ADR 0007](0007-persistencia-postgresql-jpa-flyway.md), [ADR 0013](0013-lgpd-e-dados-sensiveis.md), [ADR 0022](0022-seguranca-da-aplicacao-web.md)
- **Substitui:** [ADR 0002](0002-usuario-papeis-e-autenticacao.md)

## Contexto

O EloVital exige:

- uma conta por CPF, e-mail único e login por e-mail (RF001);
- dois perfis de uso — Assistido e Apoiador — e uma equipe de mediação com privilégios diferentes (RF015);
- estados de conta próprios (Em análise, Ativa, Suspensa, Bloqueada, Desativada) que **não** se confundem com "habilitado/desabilitado" do framework (RF001/RF002);
- sessão que expira em 30 minutos de inatividade e logout imediato (RF002);
- bloqueio temporário após 5 tentativas falhas (RF002);
- recuperação de senha por link de uso único, válido por 1 hora (RF002);
- mensagens que não revelem se um e-mail existe (RF002);
- autorização de mediação com acesso mínimo e auditado (RF015, RS012).

Com Java, a autenticação é do **Spring Security**, não do Supabase Auth. Usar dois sistemas de identidade na v1 aumentaria a superfície de erro sem resolver requisito algum.

## Decisão

### Modelo de usuário

`Usuario` é uma entidade do módulo `contas`, mapeada para `elovital.usuario`.

| Campo | Tipo | Regra |
|---|---|---|
| `id` | UUID | chave exposta |
| `email` | `citext` único | login; normalizado para minúsculas |
| `senha_hash` | texto | BCrypt |
| `nome_completo` | texto | 3–120 caracteres (RF001) |
| `cpf_hash` | texto único | HMAC-SHA-256 com pepper; nunca exposto |
| `cpf_cifrado` | texto | AES-256-GCM; leitura auditada |
| `data_nascimento` | date | ≥ 18 anos; imutável após aprovação |
| `celular_cifrado` | texto | cifrado; nunca exibido ao outro usuário |
| `papel` | enum | `ASSISTIDO`, `APOIADOR`, `MEDIADOR` |
| `status` | enum | `EM_ANALISE`, `ATIVA`, `SUSPENSA`, `BLOQUEADA`, `DESATIVADA` |
| `foto_path` | texto | bucket privado; obrigatória no cadastro |
| `email_verificado_em` | timestamptz | confirmação de e-mail |
| `ultimo_login_em` | timestamptz | métrica e segurança |
| `versao` | bigint | `@Version` |

Campos específicos ficam em `perfil_assistido` e `perfil_apoiador` (condições, limitações, contato de emergência, autorização familiar, especialidades, modalidade, raio), conforme a proposta de banco.

**CPF:** duas representações — hash para unicidade/busca e cifra para leitura autorizada ([ADR 0013](0013-lgpd-e-dados-sensiveis.md)). O CPF é imutável; a única exceção é a anonimização por LGPD, que zera hash e cifra.

**Familiar/responsável autorizado** não é papel: é um registro de autorização na conta do Assistido, com nome e vínculo (RF001). A matriz de permissões do PRD define o que ele pode fazer.

### Papéis e autoridades

Papel (`ROLE_*`) define o tipo de conta. Autoridades granulares definem o que a mediação pode fazer:

| Papel | Autoridades |
|---|---|
| `ROLE_ASSISTIDO` | `PERFIL_PROPRIO_LER`, `PERFIL_PROPRIO_EDITAR`, `SOLICITACAO_CRIAR`, `RESERVA_CRIAR`, `CHAT_PARTICIPAR`, `AVALIACAO_CRIAR`, `DENUNCIA_CRIAR` |
| `ROLE_APOIADOR` | `PERFIL_PROPRIO_LER`, `PERFIL_PROPRIO_EDITAR`, `AGENDA_GERENCIAR`, `CHAT_PARTICIPAR`, `AVALIACAO_CRIAR`, `DENUNCIA_CRIAR` |
| `ROLE_MEDIADOR` | `CONTAS_LER`, `CONTAS_VALIDAR`, `CONTAS_SANCIONAR`, `SOLICITACOES_ANALISAR`, `AGENDAS_GERENCIAR`, `DENUNCIAS_TRATAR`, `OCORRENCIAS_TRATAR`, `CONTEUDO_OCULTAR`, `PARAMETROS_EDITAR`, `AUDITORIA_LER`, `DADOS_SENSIVEIS_ACESSAR` |

Regras:

- Um usuário tem **um** papel de produto. A mediação é um papel, não um "superusuário".
- `ROLE_MEDIADOR` exige também a authority `MEDIACAO` e vínculo ativo; sem isso, as telas de mediação negam acesso.
- Autorização de **estado** (conta ativa, solicitação aberta, participante da reserva) é feita no caso de uso, não só na anotação.
- `@PreAuthorize` na borda é barreira de papel; não substitui a checagem de dono/estado.

### Senha

- Hash com **BCrypt** (`strength = 12`), via `DelegatingPasswordEncoder` com prefixo `{bcrypt}` para permitir migração futura para Argon2id sem quebrar hashes existentes.
- Política: mínimo **10 caracteres** (a proposta de segurança já adota 10; o RF001 cita 8 como piso), sem regra de composição obrigatória, com bloqueio de senhas comuns e de senhas iguais ao e-mail/nome.
- `@Password` customizado ou validador dedicado; mensagem clara em pt-BR.
- Nunca logar senha, hash ou token.

### Autenticação

```text
POST /entrar
  → valida CSRF e formato
  → consulta bloqueio por e-mail/IP (tentativa_login, janela de 15 min)
  → AuthenticationManager autentica (BCrypt)
  → em caso de falha: registra tentativa e responde mensagem genérica
  → em caso de sucesso: verifica status da conta
      ├─ EM_ANALISE / SUSPENSA / BLOQUEADA / DESATIVADA → nega com orientação para a mediação
      └─ ATIVA → cria sessão, rotaciona id, registra último login
```

Detalhes:

- `UserDetailsService` próprio carrega `Usuario` e devolve `UserDetails` com papel/autoridades.
- O status é aplicado por um `ContaStatusChecker` no `AuthenticationProvider`: a conta não ativa é recusada **depois** da senha correta. A mensagem pode dizer que a conta está em análise/suspensa porque a senha já foi validada; isso não enumera e-mails.
- Falha de credencial usa a mesma mensagem para e-mail inexistente e senha errada: "E-mail ou senha incorretos".
- 5 falhas consecutivas em 15 minutos bloqueiam temporariamente, mesmo com senha correta. O contador é por **e-mail hash + IP hash**, em `tentativa_login`, e a limpeza é feita por rotina ([ADR 0016](0016-rotinas-agendadas-e-expiracao.md)).
- O bloqueio é consultado **antes** da autenticação para não gastar BCrypt em ataque de força bruta.
- Sucesso zera o contador do par e-mail/IP.

### Sessão

- Sessão **no servidor** (`HttpSession`), com `spring.session.jdbc` quando houver mais de uma instância. Não usar JWT na v1: revogação imediata no logout é requisito (RF002) e JWT stateless complica isso.
- `server.servlet.session.timeout=30m` (valor lido do parâmetro `sessao_inatividade_min`).
- Cookie: `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, sem `Domain` amplo.
- Rotação de id no login (`changeSessionId`) contra fixação de sessão.
- Logout invalida a sessão no servidor, apaga o cookie e registra auditoria.
- Sessões concorrentes: permitidas por padrão; a v1 pode limitar a 3 por usuário se a decisão pendente 10 do PRD for aprovada.

### Recuperação de senha

```text
POST /esqueci-minha-senha
  → sempre a mesma resposta (não revela existência)
  → se o e-mail existe e a conta é elegível: gera token aleatório de 256 bits
  → grava apenas o hash SHA-256 do token, com expiração de 1 hora e uso único
  → envia link por e-mail
GET /redefinir-senha?token=...
  → valida hash, expiração e não uso
POST /redefinir-senha
  → valida política, grava novo hash, marca token como usado
  → invalida todas as sessões do usuário
```

- Token nunca em log, nunca em URL de terceiro (sem parâmetro de rastreamento).
- Reenvio invalida o token anterior.

### Autorização da mediação

- Área `/mediacao/**` protegida por `hasRole('MEDIADOR')` **e** authority `MEDIACAO`.
- Cada ação sensível tem autorização própria (`@PreAuthorize("hasAuthority('CONTAS_SANCIONAR')")`).
- Acesso a CPF, endereço exato, documento ou condição exige **motivo registrado** na mesma operação ([ADR 0014](0014-auditoria-e-acesso-a-dados.md)).
- Sessão de mediação tem timeout próprio (recomendado 15 min) e nunca é "lembrada".
- Mediação não vê senha, token nem chave.

### Eventos de segurança

| Evento | Ação |
|---|---|
| `AuthenticationSuccessEvent` | atualizar último login, zerar contador, auditar |
| `AbstractAuthenticationFailureEvent` | incrementar contador, auditar |
| `SessionDestroyedEvent` | auditar logout/expiração |
| Bloqueio por tentativas | auditar e notificar (aviso de segurança) |

## Fluxo de usuário afetado

1. **Cadastro (RF001):** cria `Usuario` com `status=EM_ANALISE`; o cadastro não autentica automaticamente.
2. **Aprovação (RF016/RF006):** mediação muda status para `ATIVA`; a partir daí o login é permitido.
3. **Login (RF002):** valida bloqueio, credencial e status; abre sessão.
4. **Uso do site:** cada requisição passa pelo filtro de sessão; a conta é revalidada quanto a status (uma suspensão derruba o acesso na próxima requisição).
5. **Suspensão/bloqueio (RF015):** sessões ativas do usuário são invalidadas no momento da sanção (remoção da sessão no servidor).
6. **Logout:** invalida a sessão.
7. **Recuperação:** link de uso único, 1 hora.
8. **Mediação:** login normal com papel `MEDIADOR` e autoridades granulares.

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Supabase Auth / OAuth | Duplica identidade, não cobre os estados de conta do produto e amarra a sessão ao provedor. |
| JWT stateless | Logout e suspensão imediatos exigem lista de revogação, o que reintroduz estado no servidor. |
| Sessão só em memória | Quebra com múltiplas instâncias e reinício do deploy. |
| `isEnabled=false` como status | Perde a distinção entre Em análise, Suspensa, Bloqueada e Desativada, exigida pelo RF001. |
| Login por CPF | CPF é dado sensível; e-mail é o identificador escolhido e menos exposto. |

## Consequências

- A sessão no banco (`spring-session-jdbc`) adiciona uma tabela e uma consulta por requisição; é o custo da revogação imediata.
- BCrypt com custo 12 é lento por design; o p95 de login pode chegar perto do limite e deve ser medido.
- O bloqueio por tentativas em tabela exige limpeza periódica e índice por `(email_hash, ip_hash, criado_em)`.
- O status de conta precisa ser revalidado em cada requisição; um cache curto pode ser necessário se o volume crescer.
- A mediação ganha um papel explícito e auditável, mas exige treinamento sobre acesso mínimo.

## Critérios de aceite

- [ ] Login com e-mail inexistente e com senha errada devolve exatamente a mesma mensagem e o mesmo tempo de resposta perceptível.
- [ ] Conta `EM_ANALISE`, `SUSPENSA`, `BLOQUEADA` ou `DESATIVADA` não acessa o painel e recebe orientação.
- [ ] 5 falhas consecutivas bloqueiam por 15 minutos, mesmo com senha correta.
- [ ] Após 30 minutos sem requisição autenticada, a próxima ação exige novo login.
- [ ] Logout invalida a sessão no servidor; reutilizar o cookie antigo não autentica.
- [ ] Suspender um usuário invalida as sessões ativas dele.
- [ ] Token de recuperação é de uso único, expira em 1 hora e não aparece em log.
- [ ] Nenhuma rota permite alterar CPF após o cadastro.
- [ ] Mediação sem a authority correta recebe 403 e o evento é auditado.
- [ ] Nenhum `import` de entidade de outro módulo em configuração de segurança.

## Decisões pendentes

> **DECISÃO PENDENTE 47:** política de senha definitiva: mínimo 10 caracteres sem composição (recomendado) ou 12 com histórico. O RF001 cita 8; a proposta de segurança cita 10. Precisa haver um número só.
>
> **DECISÃO PENDENTE 48:** 2FA e "sair de todos os dispositivos" ficam fora da v1? O RF002 trata 2FA como desejável futura.
>
> **DECISÃO PENDENTE 49:** limite de sessões simultâneas por usuário (1, 3 ou ilimitado).
>
> **DECISÃO PENDENTE 50:** canal de contestação para conta suspensa/bloqueada, já que o login é negado (Decisão 6 do PRD). A autenticação precisa prever um acesso restrito ou um formulário público.
>
> **DECISÃO PENDENTE 51:** reativação da própria conta desativada (Decisão 11 do PRD) — se existir, o login precisa reconhecer o estado e oferecer o fluxo.
