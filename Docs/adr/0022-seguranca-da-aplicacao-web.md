# ADR 0022: Segurança da aplicação web

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — RNF011, RNF012, RS001–RS013
- **Relacionado:** [ADR 0008](0008-seguranca-autenticacao-e-papeis.md), [ADR 0009](0009-conteudo-texto-puro-e-validacao.md), [ADR 0017](0017-armazenamento-de-documentos.md), [ADR 0020](0020-observabilidade-e-operacao.md)

## Contexto

O EloVital é um site público, com dados sensíveis, usado por pessoas vulneráveis. O RS001–RS013 lista os perigos: vazamento de dados, acesso indevido a contas, perfis falsos, golpes financeiros, assédio, abuso da plataforma, inconsistência de estado, denúncia maliciosa, perda de dados, uso para emergência, abuso de privilégios de mediação e profissional sem registro.

Segurança não é um módulo: é um conjunto de decisões de borda, sessão, entrada, saída, autorização, dependências e operação.

## Decisão

### Transporte e headers

| Cabeçalho | Valor |
|---|---|
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` (produção) |
| `Content-Security-Policy` | `default-src 'self'; img-src 'self' data: blob:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'self'` |
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` (imagens de documento: `no-referrer`) |
| `Permissions-Policy` | câmera, microfone e geolocalização desabilitados |
| `X-Frame-Options` | `DENY` (em conjunto com `frame-ancestors`) |
| `Cache-Control` | `no-store` em páginas autenticadas |

- HTTPS obrigatório, com redirect 301 de HTTP.
- CSP sem `unsafe-inline`: todo JavaScript em arquivo próprio, sem `onclick` inline.
- A política é aplicada pelo Spring Security e testada por verificação de header.

### Sessão e CSRF

- Sessão em cookie `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`.
- Rotação de id no login; invalidação no logout e na sanção.
- CSRF habilitado por padrão (`CookieCsrfTokenRepository` com `HttpOnly=false` apenas se houver HTMX; o token é lido de meta tag e enviado no header `X-CSRF-TOKEN`).
- Métodos `GET`/`HEAD` não mudam estado.
- `PUT`/`PATCH`/`DELETE` equivalentes usam POST com token.

### Autorização em três níveis

1. **Papel** (`ROLE_*`) na rota.
2. **Authority granular** (`@PreAuthorize`) na ação de mediação.
3. **Dono/estado** no caso de uso (o que realmente impede ver dado de outro).

Regras:

- Toda consulta filtra pelo dono e pelo estado, não só pela rota.
- URLs de recurso usam UUID; id sequencial é proibido na exposição.
- Negar acesso é 403 ou 404 (para não revelar existência), sempre auditado.
- Teste automatizado tenta acessar recurso de outro usuário em cada rota crítica.

### Rate limiting

| Escopo | Limite sugerido |
|---|---|
| Login por IP | 20/min |
| Login por e-mail | 5 falhas/15 min (já no RF002) |
| Cadastro por IP | 5/h |
| Recuperação de senha por IP | 10/h |
| Envio de mensagem | 30/min por usuário |
| Criação de solicitação | 10/h por usuário |
| Denúncia | 20/dia por usuário |
| Reserva | 20/h por usuário |
| API geral | 120/min por IP |

- Implementação: Bucket4j + Caffeine em memória (v1). Se houver várias instâncias, usar Redis ou o banco.
- Resposta 429 com `Retry-After`.
- Limite não substitui a regra de negócio (5 tentativas continua valendo).

### Validação de entrada

- Bean Validation em todo DTO/form; nunca confiar no cliente.
- Tamanho máximo de corpo e de multipart configurado no servidor.
- Upload validado por assinatura real ([ADR 0017](0017-armazenamento-de-documentos.md)).
- Parametrização obrigatória: JPA/`JdbcTemplate` com bind, nunca concatenação de SQL.
- `@RequestParam` com tipo explícito e validação; sem desserialização polimórfica.
- JSON com limite de profundidade e de tamanho quando houver API.

### Saída e XSS

- Thymeleaf com escape por padrão; `th:utext` proibido ([ADR 0009](0009-conteudo-texto-puro-e-validacao.md)).
- Nenhum conteúdo de usuário em atributo `href`, `src` ou evento inline.
- Respostas de erro não repetem o valor enviado.
- `Content-Type` correto e `nosniff`.

### Enumeração e mensagens

- Login, cadastro e recuperação não revelam existência de e-mail/CPF.
- Cadastro de CPF existente pode informar "CPF já cadastrado" (o RF001 exige), mas sem revelar nome ou e-mail.
- Recuperação sempre responde "se o e-mail existir, enviaremos instruções".
- Tempo de resposta uniformizado para não vazar por latência.

### Segredos

- Nenhum segredo no Git, na imagem ou em `application.yml`.
- Chaves em cofre/variável de ambiente, diferentes por ambiente.
- Rotação documentada (CPF pepper, chave AES, senha de banco, service role do Storage).
- `SUPABASE_SERVICE_ROLE_KEY` nunca vai ao navegador.
- Verificação automatizada de segredo no CI (por exemplo, gitleaks).

### Dependências

- Versões do BOM; atualização mensal de patch e imediata em CVE crítica.
- OWASP Dependency-Check ou `mvn dependency:tree` + alerta.
- SBOM CycloneDX publicado por release.
- Nenhuma dependência sem manutenção ou sem licença compatível.

### Cabeçalhos de erro e informação

- Página de erro genérica em produção, com `correlacaoId`.
- Sem stack trace, versão de framework, caminho interno ou SQL.
- `server.error.include-message=never`, `include-stacktrace=never`.

### Abuso e conteúdo

- Detector de pedido de dinheiro/telefone ([ADR 0009](0009-conteudo-texto-puro-e-validacao.md)).
- Denúncia com protocolo e anexo automático do trecho ([ADR 0023](0023-painel-de-mediacao.md)).
- Suspensão preventiva com 3 denunciantes distintos em 30 dias (RF014), com auditoria.
- Bloqueio entre usuários no chat.

### Segurança operacional

- MFA no repositório, no Supabase e no CI.
- Menor número de acessos de owner; revisão trimestral.
- Produção sem dados reais em dev/staging.
- Backup e restauração testados ([ADR 0007](0007-persistencia-postgresql-jpa-flyway.md)).
- Plano de resposta a incidentes com prazos (ANPD/titulares).
- Teste de intrusão antes do piloto (recomendado).

## Fluxo de usuário afetado

| Etapa | Controle |
|---|---|
| Cadastro | rate limit, validação, CPF/e-mail únicos sem enumeração ampla, consentimento |
| Login | bloqueio, mensagem genérica, CSRF, sessão |
| Verificação | upload seguro, acesso restrito |
| Perfil | autorização por dono, auditoria |
| Agenda | autorização, validação de estado |
| Solicitação | detector de conteúdo, mediação humana |
| Catálogo | projeção sem dado sensível, rate limit |
| Reserva | transação, idempotência, CSRF |
| Chat | autorização por participação, sem anexos |
| Conclusão/avaliação | autorização por parte, imutabilidade |
| Denúncia | protocolo, não revelação do denunciante |
| Mediação | papel + authority + motivo + auditoria |

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Desabilitar CSRF para simplificar HTMX | Abre o fluxo a CSRF; o token pode ser enviado por header. |
| CSP com `unsafe-inline` | Anula boa parte da proteção. |
| JWT no `localStorage` | Vulnerável a XSS e sem revogação imediata. |
| Rate limit só no proxy | Não protege contra abuso autenticado. |
| Segurança só por revisão manual | Não é repetível nem auditável. |

## Consequências

- CSP rígida exige cuidado com scripts e estilos; nada de biblioteca que injete inline.
- HTMX precisa enviar o token CSRF em todas as requisições.
- Rate limit em memória não funciona com várias instâncias; exige Redis ou banco.
- Mensagens genéricas podem frustrar o usuário; a orientação precisa ser clara.
- Teste de intrusão e revisão de dependências adicionam custo antes do lançamento.

## Critérios de aceite

- [ ] HTTP redireciona para HTTPS; HSTS presente em produção.
- [ ] CSP sem `unsafe-inline` e sem erro de console nas telas principais.
- [ ] Cookie de sessão tem `Secure`, `HttpOnly` e `SameSite=Lax`.
- [ ] POST sem token CSRF é rejeitado.
- [ ] 6ª tentativa de login é bloqueada.
- [ ] Recuperação de senha não revela existência de e-mail.
- [ ] Nenhuma rota permite ler recurso de outro usuário.
- [ ] Erro em produção não mostra stack trace nem caminho interno.
- [ ] Nenhum segredo no repositório (verificação no CI).
- [ ] Upload malicioso é recusado.
- [ ] Rate limit responde 429 com `Retry-After`.
- [ ] Acesso da mediação exige motivo e gera auditoria.

## Decisões pendentes

> **DECISÃO PENDENTE 111:** confirmar os limites de rate limiting e se o armazenamento será Redis na v1.
>
> **DECISÃO PENDENTE 112:** definir a política de CSP final (fontes de imagem, CDN, fontes externas).
>
> **DECISÃO PENDENTE 113:** contratar teste de intrusão antes do piloto? Qual escopo?
>
> **DECISÃO PENDENTE 114:** definir prazo e responsáveis da resposta a incidentes (ANPD em até 3 dias úteis para incidente relevante).
>
> **DECISÃO PENDENTE 115:** confirmar se 2FA fica fora da v1 (RF002).
