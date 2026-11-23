# ADR 0010: Entrega web — páginas, interações e API

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md)
- **Relacionado:** [ADR 0005](0005-stack-java-spring-boot.md), [ADR 0006](0006-arquitetura-em-camadas.md), [ADR 0009](0009-conteudo-texto-puro-e-validacao.md), [ADR 0022](0022-seguranca-da-aplicacao-web.md)

## Contexto

O produto é um site responsivo **mobile-first**, sem aplicativos nativos (RF003/RNF003), usado por pessoas com pouca familiaridade digital e possível dor ou limitação (RNF001), com requisito de acessibilidade WCAG 2.1 AA (RNF002). O desempenho exigido é p95 ≤ 3 s (RNF004), e notificações de reserva/cancelamento devem chegar em até 1 minuto (RF010).

O PRD descreve 15 telas (01A a 11), incluindo:

- formulários longos com validação e mensagens campo a campo (cadastro, solicitação, agenda);
- listas com filtros e paginação (catálogo, histórico, filas da mediação);
- um chat que precisa de atualização quase em tempo real;
- uma central de notificações com indicador de não lidas;
- ações com confirmação e justificativa (cancelar, sancionar, ocultar).

A escolha de tecnologia de frontend precisa equilibrar: acessibilidade, simplicidade para o time Java, SEO/indexação controlada, tempo de entrega e chat em tempo real.

## Decisão

### Páginas renderizadas no servidor (Thymeleaf) como padrão

- A maior parte das telas é **renderizada no servidor** com Spring MVC + Thymeleaf.
- Formulários usam POST + redirect (padrão PRG), com validação no servidor e repopulação em caso de erro.
- Navegação funciona **sem JavaScript** para os fluxos essenciais (cadastro, login, solicitação, agenda, reserva, confirmação, avaliação, denúncia). JavaScript é aprimoramento, não pré-requisito.
- CSS próprio, responsivo, mobile-first, com foco visível, alvos ≥ 44×44 px e contraste AA.
- Nenhum framework SPA na v1 (React/Vue/Angular). Não há build de frontend separado.

Motivos:

1. Acessibilidade é mais fácil de garantir com HTML semântico renderizado no servidor.
2. O time é Java; evitar um segundo ecossistema reduz custo.
3. O conteúdo é sensível e a superfície de ataque diminui sem estado no cliente.
4. O desempenho p95 é previsível, sem bundle grande.

### Interações assíncronas pontuais

Onde a página inteira não faz sentido, usa-se **HTMX** (atributos `hx-*`) para fragmentos HTML:

| Interação | Mecanismo |
|---|---|
| Autocomplete de CEP | `hx-get` em `/api/v1/cep/{cep}` ou endpoint interno que devolve fragmento |
| Filtros do catálogo | `hx-get` que devolve a lista parcial, com URL atualizada |
| Paginação | `hx-get` do próximo bloco |
| Envio de mensagem no chat | POST que devolve o fragmento da mensagem |
| Marcar notificação como lida | POST com troca de fragmento |
| Modal de confirmação | HTML no próprio template, sem biblioteca |

Regras:

- Todo endpoint de fragmento responde **HTML**, não JSON, quando o consumo é a própria página.
- Se o JavaScript falhar, o formulário equivalente continua funcionando.
- Nada de estado de negócio no navegador; o servidor é a fonte de verdade.
- Sem `localStorage` para dado pessoal.

### Chat e notificações em tempo real

- **SSE (Server-Sent Events)** por conexão autenticada:
  - `/eventos/chat/{reservaId}` para novas mensagens da reserva;
  - `/eventos/notificacoes` para o contador e a central.
- Fallback: *polling* a cada 20–30 segundos e atualização ao focar a aba.
- O SSE é **entrega**, não fonte de verdade: a mensagem é gravada e auditada antes de ser publicada; se o cliente perder o evento, recarrega e vê o histórico.
- Sem WebSocket na v1: SSE resolve o caso (servidor → cliente) e é mais simples atrás de proxy.
- Uma conexão SSE por aba é aceitável; o servidor limita conexões por usuário e encerra por inatividade.

### API JSON

Existe uma API versionada em `/api/v1` para:

- integrações internas (fragmentos que precisam de JSON, futura aplicação móvel);
- verificação de disponibilidade e ações que exigem resposta estruturada;
- documentação de contrato com OpenAPI (springdoc).

Regras:

- A API usa os **mesmos casos de uso** das páginas; não há regra duplicada.
- Autenticação por sessão + CSRF para o mesmo domínio; token de API só se houver cliente externo aprovado.
- Versionamento por caminho (`/api/v1`); mudança incompatível cria `/api/v2`.
- Erros em `application/problem+json` (RFC 9457) com `type`, `title`, `status`, `detail`, `instance` e `errors[]`.
- Paginação por `page`, `size` (máximo 50), com metadados.
- Nenhum DTO de API expõe campo que a tela não pode ver ([ADR 0013](0013-lgpd-e-dados-sensiveis.md)).

### Mapa de telas → rotas → casos de uso

| Tela (PRD 8.5) | Rota | Caso de uso |
|---|---|---|
| 01A/01B Cadastro | `GET/POST /cadastro` | `CadastrarUsuarioService` |
| 02 Login | `GET/POST /entrar` | Spring Security |
| 03 Central de notificações | `GET /notificacoes` | `ListarNotificacoesService` |
| 04A/04B Nova solicitação | `GET/POST /solicitacoes/nova` | `CriarSolicitacaoService` |
| 05 Apoiadores disponíveis | `GET /apoiadores` | `ConsultarCatalogoService` |
| 06 Perfil do apoiador | `GET /apoiadores/{id}` | `ConsultarPerfilPublicoService` |
| 07A/07B Reserva | `POST /reservas` + `GET /reservas/{id}` | `ReservarHorarioService` |
| 08A/08B Minha agenda | `GET /agenda`, `POST /agenda/horarios` | `PublicarHorarioService` |
| 09 Chat | `GET /reservas/{id}/chat` | `ListarMensagensService` / `EnviarMensagemService` |
| 10 Conclusão e avaliação | `POST /reservas/{id}/confirmacoes`, `POST /reservas/{id}/avaliacao` | `RegistrarConfirmacaoService`, `AvaliarService` |
| 11 Painel da mediação | `GET /mediacao/solicitacoes` | `ListarFilaAnaliseService` |
| Perfil e privacidade | `GET/POST /perfil` | `AlterarPerfilService` |
| Verificação | `GET/POST /verificacao` | `EnviarDocumentoService` |

Rotas públicas: termos, política, login, cadastro, recuperação. Tudo o mais exige sessão.

### Acessibilidade

- HTML semântico (`<main>`, `<nav>`, `<form>`, `<label for>`).
- Toda mensagem de erro associada ao campo, com `aria-invalid` e `aria-describedby` (RNF007).
- Foco gerenciado após submit e em modais.
- Textos alternativos em imagens; ícones com rótulo.
- Teste automatizado (axe-core via Playwright) nas telas principais + revisão manual.
- Sem dependência de cor para transmitir estado.

### Internacionalização e formatação

- pt-BR apenas; `Locale` fixo no servidor.
- Datas `dd/MM/yyyy`, horas `HH:mm`, fuso `America/Sao_Paulo` na exibição.
- Mensagens em `messages.properties`.
- Números e distância arredondada ("cerca de 3 km") — ver [ADR 0018](0018-geolocalizacao-cep-e-catalogo.md).

## Fluxo de usuário afetado

1. **Cadastro:** formulário em duas etapas (01A dados, 01B endereço/consentimento), com validação server-side e retorno ao mesmo passo em erro.
2. **Verificação:** upload com barra de progresso e retorno claro de status.
3. **Login:** formulário simples; mensagem genérica; redirect ao painel.
4. **Agenda:** formulário com recorrência; lista com status.
5. **Solicitação:** formulário longo com aviso do SAMU na prioridade Alta; PRG para não reenviar.
6. **Catálogo:** filtros com HTMX; estados vazios orientativos; paginação.
7. **Reserva:** confirmação em modal/página com resumo do que será compartilhado; resposta 409 tratada com atualização da lista.
8. **Chat:** histórico cronológico + envio; SSE para novas mensagens; fallback por polling.
9. **Conclusão/avaliação:** botões de confirmação, prazo visível, formulário de nota.
10. **Mediação:** filas com filtro, detalhe com histórico e ações que exigem justificativa.

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| SPA React/Next | Segundo ecossistema, acessibilidade mais difícil, bundle maior, sem requisito de app. |
| Thymeleaf + Alpine.js | Boa opção; HTMX foi escolhido por reduzir JavaScript escrito. |
| WebSocket | Complexidade de proxy, reconexão e escala; SSE cobre o caso. |
| API-first com frontend separado | Atrasa a entrega e duplica autenticação/CSRF. |
| Templates prontos de admin (Spring Admin) | Não atende ao fluxo de mediação nem à LGPD. |

## Consequências

- O time mantém um único artefato e um único deploy.
- A experiência é menos "fluida" que uma SPA, mas previsível e acessível.
- SSE exige atenção a proxies (buffering), reconexão e limite de conexões.
- Testes de interface precisam cobrir o caminho sem JavaScript.
- Se no futuro houver app nativo, a API `/api/v1` já existe, mas precisará de autenticação por token e revisão de CSRF.

## Critérios de aceite

- [ ] Todos os fluxos essenciais funcionam com JavaScript desabilitado.
- [ ] Nenhum template usa `th:utext` com conteúdo de usuário.
- [ ] Mensagens de erro estão associadas ao campo com `aria-invalid`.
- [ ] Catálogo, histórico e filas paginam no servidor.
- [ ] Nova mensagem chega ao outro participante em até 1 minuto (SSE ou polling).
- [ ] `POST` seguido de recarregar a página não duplica operação (PRG + idempotência em reserva).
- [ ] `/api/v1` documentado no OpenAPI e sem campo sensível.
- [ ] Erros de API no formato `application/problem+json`.
- [ ] Layout utilizável em 360 px de largura.

## Decisões pendentes

> **DECISÃO PENDENTE 55:** confirmar Thymeleaf + HTMX como frontend da v1, em vez de SPA. A decisão afeta prazo, acessibilidade e chat.
>
> **DECISÃO PENDENTE 56:** confirmar SSE como mecanismo de tempo real e se o polling de fallback é obrigatório em todos os navegadores-alvo.
>
> **DECISÃO PENDENTE 57:** definir se a API `/api/v1` é pública documentada ou apenas interna. Se pública, exige contrato, versionamento e autenticação por token.
