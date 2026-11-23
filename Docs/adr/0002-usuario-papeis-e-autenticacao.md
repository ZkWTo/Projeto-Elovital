# ADR 0002: Usuário customizado, papéis e autenticação própria

- **Status:** Substituído em 2026-11-23
- **Substituído por:** [ADR 0008 — Usuário, papéis, autenticação e sessão com Spring Security](0008-seguranca-autenticacao-e-papeis.md)
- **Data:** 2026-11-16
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md)
- **Relacionado:** [ADR 0004](0004-supabase-postgresql.md)

> **Documento histórico.** Escrito para Django. O modelo de conta única por CPF, os três papéis, os cinco estados de conta, o CPF em hash + cifra e a recusa ao Supabase Auth continuam válidos e foram transportados para o [ADR 0008](0008-seguranca-autenticacao-e-papeis.md), agora com Spring Security.

## Contexto

O EloVital precisa de uma conta por CPF, dois perfis de uso — Assistido e Apoiador — e uma equipe de mediação com privilégios diferentes. O CPF é imutável, o e-mail é único e a conta tem estados próprios (Em análise, Ativa, Suspensa, Bloqueada, Desativada) que não se confundem com `is_active`.

A documentação do Django recomenda definir o modelo de usuário antes da primeira migração, porque trocá-lo depois é trabalhoso. O projeto de referência já usa um modelo customizado, e o EloVital ainda não tem banco de produção.

O Supabase oferece autenticação própria, mas o projeto segue MVT com autenticação e sessão do Django. Usar dois sistemas de identidade na primeira versão aumentaria a superfície de erro sem resolver um requisito do produto.

## Decisão

- Criar o app `contas`, com o modelo `Usuario` herdando de `AbstractUser`.
- Usar o e-mail como identificador de login (`USERNAME_FIELD = "email"`), sem campo `username`.
- Definir `AUTH_USER_MODEL = "contas.Usuario"` antes da primeira execução de `migrate`.
- Guardar o papel em um campo único `papel`, com os valores `ASSISTIDO`, `APOIADOR` e `MEDIADOR`. O familiar/responsável autorizado não é um papel: é uma autorização registrada na conta do Assistido.
- Guardar o estado da conta em `status`, separado de `is_active`.
- Guardar o CPF de duas formas: um HMAC-SHA-256 com pepper para unicidade e busca, e o valor cifrado para exibição à mediação. O CPF nunca é gravado em texto puro.
- Usar o sistema de autenticação do Django: `AuthenticationForm`, `login()`, `logout()` e `LoginRequiredMixin`; as views de entrar e sair são classes que herdam de `View`.
- Aplicar a política de sessão do RF002: expiração após 30 minutos de inatividade e encerramento imediato ao sair.
- Aplicar bloqueio temporário após tentativas de login falhas, registrado em tabela própria, sem revelar se o e-mail existe.
- Reservar o acesso à mediação a usuários com `papel = MEDIADOR`, `is_staff = True` e participação no grupo `Mediacao`; a escrita no Admin continua sujeita aos services.
- Não usar Supabase Auth na primeira versão. O Supabase entra como banco PostgreSQL e, opcionalmente, armazenamento privado de documentos, conforme a [ADR 0004](0004-supabase-postgresql.md).

## Consequências

- O modelo de usuário precisa existir antes da primeira migração; mudanças posteriores exigem migração cuidadosa.
- O código deve referenciar o usuário por `settings.AUTH_USER_MODEL` ou `get_user_model()`, nunca importando `User` diretamente.
- O CPF exige duas operações: HMAC para unicidade e cifra para leitura autorizada. A rotação do pepper e da chave de cifra precisa de plano de migração.
- O e-mail passa a ser o login; o cadastro e a recuperação de senha dependem de envio de e-mail confiável.
- A mediação ganha um papel explícito, com permissões mínimas e auditoria de acesso a dados pessoais.
- O bloqueio de tentativas exige uma tabela e uma rotina de limpeza; não é necessária uma infraestrutura de cache na primeira versão.
- A autenticação em dois fatores fica para uma decisão futura, conforme o RF002.
- A sessão do Django é independente do Supabase; encerrar a sessão não revoga chaves do banco.
