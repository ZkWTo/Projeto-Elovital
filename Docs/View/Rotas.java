package View;

import Model.Dominio;
import Model.Erros;
import Model.ServicoCatalogo;
import Model.ServicoContas;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** ADR 0010: rotas do site, fragmentos, SSE e API versionada. */
public final class Rotas implements HttpHandler {

    private final Contexto ctx;
    private final RotasMediacao mediacao;

    public Rotas(Contexto ctx) {
        this.ctx = ctx;
        this.mediacao = new RotasMediacao(ctx);
    }

    @Override
    public void handle(HttpExchange ex) {
        SegurancaWeb.cabecalhos(ex);
        String correlacao = java.util.UUID.randomUUID().toString();
        ex.getResponseHeaders().set("X-Correlacao-Id", correlacao);
        ctx.metricas.requisicao();
        Http.Requisicao req = null;
        try {
            req = Http.ler(ex, ctx.sessoes, 30);
            if (req.caminho().startsWith("/mediacao")) {
                mediacao.despachar(req);
            } else if (req.caminho().startsWith("/api/")) {
                api(req);
            } else {
                site(req);
            }
        } catch (SegurancaWeb.NaoAutenticado e) {
            if (req != null && req.json()) {
                try {
                    Http.texto(ex, 401, "application/problem+json",
                            Json.problema(401, "Autenticacao necessaria",
                                    "Entre com uma sessao valida para usar a API.", null));
                } catch (IOException ignorada) {
                    // resposta ja encerrada
                }
            } else {
                String destino = req == null ? "/entrar"
                        : "/entrar?next=" + url(req.caminho());
                try {
                    Http.redirect(ex, destino);
                } catch (IOException ignorada) {
                    // resposta ja encerrada
                }
            }
        } catch (Exception e) {
            responderErro(req, e, correlacao);
        } finally {
            ex.close();
        }
    }

    private void site(Http.Requisicao req) throws IOException {
        String caminho = req.caminho();
        String metodo = req.exchange().getRequestMethod();
        String[] p = Http.partes(req.exchange());

        if (metodo.equals("GET") && caminho.equals("/estilo.css")) {
            var arquivo = ctx.templates.base().resolve("estilo.css");
            if (Files.exists(arquivo)) {
                Http.bytes(req.exchange(), 200, "text/css; charset=utf-8", Files.readAllBytes(arquivo));
            } else {
                Http.texto(req.exchange(), 404, "text/plain; charset=utf-8", "Sem estilo.");
            }
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/app.js")) {
            var arquivo = ctx.templates.base().resolve("app.js");
            Http.bytes(req.exchange(), 200, "text/javascript; charset=utf-8",
                    Files.readAllBytes(arquivo));
            return;
        }
        if (metodo.equals("GET") && (caminho.equals("/health/live") || caminho.equals("/health/ready"))) {
            String tipo = caminho.endsWith("live") ? "liveness" : "readiness";
            Http.texto(req.exchange(), 200, "application/json",
                    "{\"status\":\"UP\",\"probe\":\"" + tipo + "\"}");
            return;
        }
        if (metodo.equals("GET") && (caminho.equals("/health") || caminho.equals("/actuator/health"))) {
            Http.texto(req.exchange(), 200, "application/json",
                    "{\"status\":\"UP\",\"checks\":{\"banco\":\"UP\",\"rotinas\":\"UP\",\"storage\":\"UP\"}}");
            return;
        }
        if (metodo.equals("GET") && (caminho.equals("/metrics") || caminho.equals("/actuator/prometheus"))) {
            Http.texto(req.exchange(), 200, "text/plain; version=0.0.4", ctx.metricas.prometheus());
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/")) {
            if (req.sessao().isPresent()) {
                Http.redirect(req.exchange(), "/painel");
            } else {
                render(req, "home", "EloVital Â· rede de apoio solidaria", null, 200);
            }
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/termos")) {
            render(req, "termos", "Termos de uso", null, 200);
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/politica")) {
            render(req, "politica", "Politica de privacidade", null, 200);
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/cadastro")) {
            renderCadastro(req, null);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/cadastro")) {
            cadastrar(req);
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/entrar")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("next", req.query("next"));
            render(req, "entrar", "Entrar", m, 200);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/entrar")) {
            entrar(req);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/sair")) {
            SegurancaWeb.validarCsrf(req);
            req.sessao().ifPresent(s -> ctx.sessoes.invalidar(s.token));
            Http.apagarCookie(req.exchange());
            Http.redirect(req.exchange(), "/?ok=login_ok");
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/esqueci-minha-senha")) {
            render(req, "recuperar", "Recuperar senha", null, 200);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/esqueci-minha-senha")) {
            ctx.limitador.exigir("recuperacao:" + req.ipHash(), 10, Duration.ofHours(1));
            Optional<String> token = ctx.contas.solicitarRecuperacao(req.param("email"), req.ipHash());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mensagem", "Se o e-mail existir, enviaremos um link valido por 1 hora.");
            m.put("linkDemo", token.map(t -> "/redefinir-senha?token=" + url(t)).orElse(""));
            render(req, "recuperar", "Recuperar senha", m, 200);
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/redefinir-senha")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("token", req.query("token"));
            render(req, "redefinir", "Redefinir senha", m, 200);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/redefinir-senha")) {
            ctx.contas.redefinirSenha(req.param("token"), req.param("senha"),
                    req.param("confirmacao"), req.ipHash());
            Http.redirect(req.exchange(), "/entrar?ok=senha_ok");
            return;
        }

        // ---------------------------------------------------------- autenticado
        Dominio.Usuario u = SegurancaWeb.exigirLogado(req, ctx.repo);

        if (metodo.equals("GET") && caminho.equals("/painel")) {
            painel(req, u);
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/perfil")) {
            perfil(req, u);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/perfil")) {
            SegurancaWeb.validarCsrf(req);
            ctx.contas.atualizarPerfil(u.id, req.param("nome"), req.param("celular"),
                    req.param("cep"), req.param("bairro"), req.param("municipio"),
                    req.param("uf"), req.param("condicoes"), req.param("limitacoes"),
                    modalidade(req.param("modalidade")), req.inteiro("raioKm", 0));
            Http.redirect(req.exchange(), "/perfil?ok=perfil_ok");
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/verificacao")) {
            List<Map<String, Object>> docs = ctx.repo.documentos.values().stream()
                    .filter(d -> d.usuarioId.equals(u.id))
                    .map(ctx::documentoView).toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("documentos", docs);
            render(req, "verificacao", "Verificacao de identidade", m, 200);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/verificacao")) {
            SegurancaWeb.validarCsrf(req);
            ctx.contas.enviarDocumento(u.id, req.param("tipo"), req.param("arquivo"));
            Http.redirect(req.exchange(), "/verificacao?ok=documento_ok");
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/notificacoes")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("notificacoes", ctx.notificacoesView(ctx.notificacao.listar(u.id)));
            render(req, "notificacoes", "Notificacoes", m, 200);
            return;
        }
        if (metodo.equals("POST") && p.length == 3 && p[0].equals("notificacoes")
                && p[2].equals("lida")) {
            SegurancaWeb.validarCsrf(req);
            ctx.notificacao.marcarLida(u.id, Http.uuid(p[1]));
            Http.redirect(req.exchange(), "/notificacoes");
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/notificacoes/todas")) {
            SegurancaWeb.validarCsrf(req);
            ctx.notificacao.marcarTodas(u.id);
            Http.redirect(req.exchange(), "/notificacoes");
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/solicitacoes")) {
            listarSolicitacoes(req, u);
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/solicitacoes/nova")) {
            if (u.papel != Dominio.Papel.ASSISTIDO) {
                throw new Erros.PermissaoNegadaException("Apenas Assistido cria solicitacao.");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("condicoes", ctx.catalogo.condicoes());
            render(req, "nova-solicitacao", "Nova solicitacao", m, 200);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/solicitacoes")) {
            SegurancaWeb.validarCsrf(req);
            ctx.limitador.exigir("solicitacao:" + u.id, 10, Duration.ofHours(1));
            Dominio.Solicitacao s = ctx.solicitacoes.criar(u.id, req.param("titulo"),
                    req.param("descricao"), req.param("observacao"),
                    prioridade(req.param("prioridade")), req.param("tipoApoio"),
                    req.param("cep"), req.param("bairro"), req.param("municipio"),
                    req.param("uf"), req.decimal("raioKm", 10), req.ipHash());
            Http.redirect(req.exchange(), "/solicitacoes?ok=solicitacao_ok#" + s.id);
            return;
        }
        if (metodo.equals("POST") && p.length == 3 && p[0].equals("solicitacoes")
                && p[2].equals("cancelar")) {
            SegurancaWeb.validarCsrf(req);
            ctx.solicitacoes.cancelar(u.id, Http.uuid(p[1]),
                    req.param("motivo"), req.ipHash());
            Http.redirect(req.exchange(), "/solicitacoes?ok=solicitacao_cancelada");
            return;
        }
        if (metodo.equals("POST") && p.length == 3 && p[0].equals("solicitacoes")
                && p[2].equals("reenviar")) {
            SegurancaWeb.validarCsrf(req);
            ctx.solicitacoes.reenviar(u.id, Http.uuid(p[1]), req.param("titulo"),
                    req.param("descricao"), req.param("observacao"), req.ipHash());
            Http.redirect(req.exchange(), "/solicitacoes?ok=solicitacao_ok");
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/apoiadores")) {
            catalogo(req, u);
            return;
        }
        if (metodo.equals("GET") && p.length == 2 && p[0].equals("apoiadores")) {
            perfilApoiador(req, u, Http.uuid(p[1]));
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/agenda")) {
            agenda(req, u);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/agenda/horarios")) {
            SegurancaWeb.validarCsrf(req);
            ctx.agenda.publicar(u.id, LocalDate.parse(req.param("data")),
                    LocalTime.parse(req.param("inicio")), LocalTime.parse(req.param("fim")),
                    modalidade(req.param("modalidade")), req.param("observacoes"), req.ipHash());
            Http.redirect(req.exchange(), "/agenda?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("POST") && p.length == 4 && p[0].equals("agenda")
                && p[1].equals("horarios") && p[3].equals("bloquear")) {
            SegurancaWeb.validarCsrf(req);
            ctx.agenda.bloquear(u.id, Http.uuid(p[2]), req.param("motivo"), req.ipHash());
            Http.redirect(req.exchange(), "/agenda?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/reservas")) {
            SegurancaWeb.validarCsrf(req);
            ctx.limitador.exigir("reserva:" + u.id, 20, Duration.ofHours(1));
            UUID solicitacaoId = req.uuid("solicitacaoId");
            UUID horarioId = req.uuid("horarioId");
            if (solicitacaoId == null || horarioId == null) {
                throw new Erros.ValidacaoException("reserva", "obrigatorio",
                        "Escolha a solicitacao e o horario.");
            }
            Dominio.Reserva r = ctx.reservas.reservar(u.id, solicitacaoId, horarioId, req.ipHash());
            Http.redirect(req.exchange(), "/reservas/" + r.id + "?ok=reserva_ok");
            return;
        }
        if (metodo.equals("GET") && p.length == 2 && p[0].equals("reservas")) {
            reserva(req, u, Http.uuid(p[1]));
            return;
        }
        if (metodo.equals("GET") && p.length == 3 && p[0].equals("reservas")
                && p[2].equals("chat")) {
            chat(req, u, Http.uuid(p[1]));
            return;
        }
        if (metodo.equals("POST") && p.length == 3 && p[0].equals("reservas")
                && p[2].equals("chat")) {
            SegurancaWeb.validarCsrf(req);
            ctx.limitador.exigir("mensagem:" + u.id, 30, Duration.ofMinutes(1));
            ctx.comunicacao.enviar(u.id, Http.uuid(p[1]), req.param("texto"), req.ipHash());
            Http.redirect(req.exchange(), "/reservas/" + p[1] + "/chat");
            return;
        }
        if (metodo.equals("POST") && p.length == 3 && p[0].equals("reservas")) {
            SegurancaWeb.validarCsrf(req);
            UUID id = Http.uuid(p[1]);
            String acao = p[2];
            switch (acao) {
                case "ciencia" -> ctx.reservas.registrarCiencia(u.id, id, req.ipHash());
                case "cancelar" -> ctx.reservas.cancelar(u.id, id, req.param("motivo"), req.ipHash());
                case "realizacao" -> ctx.reservas.registrarRealizacao(u.id, id, req.ipHash());
                case "confirmacoes" -> ctx.reservas.confirmar(u.id, id, req.ipHash());
                case "discordar" -> ctx.reservas.discordar(u.id, id, req.param("ocorrencia"), req.ipHash());
                case "falta" -> ctx.reservas.registrarFalta(u.id, id, req.ipHash());
                case "remarcar" -> {
                    UUID novo = req.uuid("horarioId");
                    if (novo == null) {
                        throw new Erros.ValidacaoException("horario", "obrigatorio",
                                "Escolha o novo horario.");
                    }
                    ctx.reservas.remarcar(u.id, id, novo, req.ipHash());
                }
                case "avaliacao" -> ctx.reputacao.avaliar(u.id, id,
                        req.inteiro("nota", 0), req.param("comentario"), req.ipHash());
                default -> throw new Erros.NaoEncontradoException("acao");
            }
            Http.redirect(req.exchange(), "/reservas/" + id + "?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/historico")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reservas", ctx.reservasView(ctx.reservas.doUsuario(u.id), u.id));
            m.put("solicitacoes", u.papel == Dominio.Papel.ASSISTIDO
                    ? ctx.solicitacoesView(ctx.repo.solicitacoesDoAssistido(u.id), u.id)
                    : List.of());
            render(req, "historico", "Historico", m, 200);
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/denuncias/nova")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alvo", req.query("alvo"));
            m.put("reservaId", req.query("reserva"));
            m.put("alvoNome", ctx.repo.usuario(parseUuid(req.query("alvo")))
                    .map(x -> x.nomeCompleto).orElse(""));
            render(req, "denuncia", "Registrar denuncia", m, 200);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/denuncias")) {
            SegurancaWeb.validarCsrf(req);
            ctx.limitador.exigir("denuncia:" + u.id, 20, Duration.ofDays(1));
            UUID alvoId = req.uuid("alvoId");
            if (alvoId == null) {
                throw new Erros.ValidacaoException("alvo", "obrigatorio", "Destinatario invalido.");
            }
            ctx.moderacao.denunciar(u.id, alvoId, req.uuid("reservaId"),
                    req.param("descricao"), req.ipHash());
            Http.redirect(req.exchange(), "/painel?ok=denuncia_ok");
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/eventos/notificacoes")) {
            sse(req, u.id, "notificacao");
            return;
        }
        if (metodo.equals("GET") && p.length == 3 && p[0].equals("eventos") && p[1].equals("chat")) {
            Dominio.Reserva r = ctx.reservas.exigir(Http.uuid(p[2]));
            if (!ctx.reservas.ehParte(r, u.id) && !SegurancaWeb.mediacao(u)) {
                throw new Erros.PermissaoNegadaException("Sem acesso ao chat.");
            }
            sse(req, r.id, "mensagem");
            return;
        }
        if (metodo.equals("GET") && p.length == 2 && p[0].equals("arquivos")) {
            byte[] conteudo = ctx.armazenamento.ler(p[1]);
            Http.bytes(req.exchange(), 200, "application/octet-stream", conteudo);
            return;
        }
        throw new Erros.NaoEncontradoException("pagina");
    }

    // ------------------------------------------------------------------- paginas

    private void renderCadastro(Http.Requisicao req, String erro) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("especialidades", ctx.catalogo.especialidades());
        m.put("condicoes", ctx.catalogo.condicoes());
        m.put("erro", erro == null ? "" : erro);
        for (String campo : List.of("nome", "email", "cpf", "celular", "cep", "bairro",
                "municipio", "uf", "condicoesTexto")) {
            m.put("f_" + campo, req.param(campo));
        }
        m.put("f_papel", req.param("papel"));
        render(req, "cadastro", "Criar conta", m, 200);
    }

    private void cadastrar(Http.Requisicao req) throws IOException {
        ctx.limitador.exigir("cadastro:" + req.ipHash(), 5, Duration.ofHours(1));
        try {
            ctx.contas.cadastrar(new ServicoContas.CadastroComando(
                    req.param("nome"), req.param("email"), req.param("senha"),
                    req.param("confirmacaoSenha"), req.param("cpf"), req.param("celular"),
                    parseData(req.param("nascimento")),
                    "APOIADOR".equals(req.param("papel")) ? Dominio.Papel.APOIADOR : Dominio.Papel.ASSISTIDO,
                    req.flag("termos"), req.flag("consentimento"),
                    req.param("cep"), req.param("bairro"), req.param("municipio"),
                    req.param("uf"), req.param("condicoes"), req.decimal("raioKm", 10)),
                    req.ipHash());
            Http.redirect(req.exchange(), "/entrar?ok=cadastro_ok");
        } catch (Erros.ValidacaoException | Erros.ConflitoException e) {
            renderCadastro(req, e.getMessage());
        }
    }

    private void entrar(Http.Requisicao req) throws IOException {
        ctx.limitador.exigir("login:" + req.ipHash(), 20, Duration.ofMinutes(1));
        try {
            Dominio.Usuario u = ctx.contas.autenticar(req.param("email"),
                    req.param("senha"), req.ip());
            Http.Sessao s = ctx.sessoes.criar(u.id, SegurancaWeb.mediacao(u));
            Http.cookieSessao(req.exchange(), s.token, 1800);
            String next = req.param("next");
            if (next.isBlank() || !next.startsWith("/") || next.startsWith("//")) {
                next = SegurancaWeb.mediacao(u) ? "/mediacao" : "/painel";
            }
            Http.redirect(req.exchange(), next);
        } catch (Erros.EloVitalException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("erro", e.getMessage());
            m.put("email", req.param("email"));
            m.put("next", req.param("next"));
            render(req, "entrar", "Entrar", m, 401);
        }
    }

    private void painel(Http.Requisicao req, Dominio.Usuario u) throws IOException {
        if (SegurancaWeb.mediacao(u)) {
            Http.redirect(req.exchange(), "/mediacao");
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reservas", ctx.reservasView(ctx.reservas.doUsuario(u.id), u.id));
        m.put("notificacoes", ctx.notificacoesView(ctx.notificacao.listar(u.id)));
        m.put("qtdReservas", ctx.reservas.doUsuario(u.id).size());
        if (u.papel == Dominio.Papel.ASSISTIDO) {
            m.put("solicitacoes", ctx.solicitacoesView(ctx.repo.solicitacoesDoAssistido(u.id), u.id));
            m.put("qtdSolicitacoes", ctx.repo.solicitacoesDoAssistido(u.id).size());
            m.put("apoiadores", ctx.catalogo.listar(null, null, null, null).stream()
                    .limit(3).map(ctx::apoiadorView).toList());
        } else {
            m.put("agenda", ctx.horariosView(ctx.agenda.agendaDoApoiador(u.id)));
            m.put("qtdAgenda", ctx.agenda.agendaDoApoiador(u.id).size());
            m.put("solicitacoesAbertas", ctx.solicitacoesView(ctx.solicitacoes.publicas(), u.id));
            m.put("qtdSolicitacoes", ctx.solicitacoes.publicas().size());
        }
        m.put("rotinas", ctx.rotinas.resumo());
        render(req, "painel", "Painel", m, 200);
    }

    private void perfil(Http.Requisicao req, Dominio.Usuario u) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.email);
        m.put("cpfMascarado", u.cpfCifrado == null ? "-" : Model.Cripto.mascararCpf(
                Model.Cripto.decifrarCpf(u.cpfCifrado)));
        m.put("nascimento", Formatador.data(u.dataNascimento));
        m.put("criadoEm", Formatador.dataHora(u.criadoEm));
        m.put("statusConta", Formatador.status(u.status));
        m.put("papel", Formatador.titulo(u.papel.name()));
        m.put("modalidadeAtual", u.papel == Dominio.Papel.APOIADOR ? "PRESENCIAL" : "");
        ctx.repo.perfilAssistidoPorUsuario(u.id).ifPresent(p -> {
            m.put("condicoes", p.condicoes);
            m.put("limitacoes", p.limitacoes);
            m.put("cep", p.cep);
            m.put("bairro", p.bairro);
            m.put("municipio", p.municipio);
            m.put("uf", p.uf);
            m.put("raioKm", p.raioKm);
        });
        ctx.repo.perfilApoiadorPorUsuario(u.id).ifPresent(p -> {
            m.put("especialidades", p.especialidades);
            m.put("modalidadeAtual", p.modalidade.name());
            m.put("cep", p.cep);
            m.put("bairro", p.bairro);
            m.put("municipio", p.municipio);
            m.put("uf", p.uf);
            m.put("raioKm", p.raioKm);
            m.put("nota", Formatador.nota(p.notaMedia, p.totalAvaliacoes));
            m.put("verificado", p.verificado);
        });
        m.put("avaliacoes", ctx.repo.avaliacoesDoAlvo(u.id).stream()
                .map(ctx::avaliacaoView).toList());
        render(req, "perfil", "Perfil e privacidade", m, 200);
    }

    private void listarSolicitacoes(Http.Requisicao req, Dominio.Usuario u) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        if (u.papel == Dominio.Papel.ASSISTIDO) {
            m.put("minhas", true);
            m.put("solicitacoes", ctx.solicitacoesView(ctx.repo.solicitacoesDoAssistido(u.id), u.id));
        } else {
            m.put("minhas", false);
            m.put("solicitacoes", ctx.solicitacoesView(ctx.solicitacoes.publicas(), u.id));
            m.put("horarios", ctx.horariosView(ctx.repo.horariosDoApoiador(u.id)));
        }
        render(req, "solicitacoes", "Solicitacoes", m, 200);
    }

    private void catalogo(Http.Requisicao req, Dominio.Usuario u) throws IOException {
        String especialidade = req.query("especialidade");
        String municipio = req.query("municipio");
        String modalidade = req.query("modalidade");
        Double distancia = req.query("distancia").isBlank() ? null : parseDouble(req.query("distancia"));
        List<Map<String, Object>> lista = ctx.catalogo
                .listar(especialidade, modalidade, municipio, distancia)
                .stream().map(ctx::apoiadorView).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("apoiadores", lista);
        m.put("qtdApoiadores", lista.size());
        m.put("especialidades", ctx.catalogo.especialidades());
        m.put("municipios", ctx.catalogo.municipios());
        m.put("f_especialidade", especialidade);
        m.put("f_municipio", municipio);
        m.put("f_modalidade", modalidade);
        m.put("f_distancia", req.query("distancia"));
        m.put("minhasSolicitacoes",
                ctx.solicitacoesView(ctx.repo.solicitacoesDoAssistido(u.id).stream()
                        .filter(s -> s.status == Dominio.StatusSolicitacao.ABERTA).toList(), u.id));
        render(req, "apoiadores", "Apoiadores disponiveis", m, 200);
    }

    private void perfilApoiador(Http.Requisicao req, Dominio.Usuario u, UUID apoiadorId)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        ServicoCatalogo.ApoiadorPublico a = ctx.catalogo.perfilPublico(apoiadorId)
                .orElseThrow(() -> new Erros.NaoEncontradoException("apoiador"));
        m.put("apoiador", ctx.apoiadorView(a));
        m.put("horarios", ctx.horariosView(ctx.agenda.agendaDoApoiador(apoiadorId).stream()
                .filter(h -> h.status == Dominio.StatusHorario.DISPONIVEL)
                .filter(h -> h.futuro(ctx.agora())).toList()));
        m.put("minhasSolicitacoes", ctx.solicitacoesView(
                ctx.repo.solicitacoesDoAssistido(u.id).stream()
                        .filter(s -> s.status == Dominio.StatusSolicitacao.ABERTA).toList(), u.id));
        render(req, "apoiador", a.primeiroNome(), m, 200);
    }

    private void agenda(Http.Requisicao req, Dominio.Usuario u) throws IOException {
        if (u.papel != Dominio.Papel.APOIADOR) {
            throw new Erros.PermissaoNegadaException("Agenda restrita ao Apoiador.");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("horarios", ctx.horariosView(ctx.agenda.agendaDoApoiador(u.id)));
        render(req, "agenda", "Minha agenda", m, 200);
    }

    private void reserva(Http.Requisicao req, Dominio.Usuario u, UUID id) throws IOException {
        Dominio.Reserva r = ctx.reservas.exigir(id);
        boolean mediador = SegurancaWeb.mediacao(u);
        if (!ctx.reservas.ehParte(r, u.id) && !mediador) {
            throw new Erros.PermissaoNegadaException("Reserva de outra pessoa.");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reserva", ctx.reservaView(r, u.id));
        m.put("mensagens", ctx.repo.mensagensDaReserva(id).stream()
                .map(msg -> ctx.mensagemView(msg, u.id, mediador)).toList());
        m.put("avaliacoes", ctx.reputacao.daReserva(id).stream().map(ctx::avaliacaoView).toList());
        m.put("horarios", ctx.horariosView(ctx.agenda.disponiveis()));
        render(req, "reserva", "Reserva", m, 200);
    }

    private void chat(Http.Requisicao req, Dominio.Usuario u, UUID id) throws IOException {
        Dominio.Reserva r = ctx.reservas.exigir(id);
        boolean mediador = SegurancaWeb.mediacao(u);
        if (!ctx.reservas.ehParte(r, u.id) && !mediador) {
            throw new Erros.PermissaoNegadaException("Chat de outra pessoa.");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reserva", ctx.reservaView(r, u.id));
        m.put("mensagens", ctx.repo.mensagensDaReserva(id).stream()
                .map(msg -> ctx.mensagemView(msg, u.id, mediador)).toList());
        render(req, "chat", "Chat da reserva", m, 200);
    }

    // ----------------------------------------------------------------------- SSE

    private void sse(Http.Requisicao req, UUID chave, String evento) throws IOException {
        var ex = req.exchange();
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        ctx.metricas.sseAberta();
        try (OutputStream out = ex.getResponseBody()) {
            Runnable cancelar = ctx.comunicacao.eventos().inscrever(chave, e -> {
                try {
                    out.write(("event: " + evento + "\ndata: " + e + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignorada) {
                    // conexao encerrada pelo cliente
                }
            });
            try {
                for (int i = 0; i < 120; i++) {
                    out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(15_000);
                }
            } catch (IOException | InterruptedException encerrada) {
                Thread.currentThread().interrupt();
            } finally {
                cancelar.run();
            }
        } finally {
            ctx.metricas.sseFechada();
        }
    }

    // ----------------------------------------------------------------------- API

    private void api(Http.Requisicao req) throws IOException {
        ctx.limitador.exigir("api:" + req.ipHash(), 120, Duration.ofMinutes(1));
        String caminho = req.caminho();
        String[] p = Http.partes(req.exchange());
        String metodo = req.exchange().getRequestMethod();

        if (metodo.equals("GET") && caminho.startsWith("/api/v1/cep/")) {
            String cep = p.length >= 4 ? p[3] : "";
            var endereco = ctx.catalogo.consultarCep(cep);
            json(req, 200, Json.de(Contexto.m("cep", endereco.cep(), "bairro", endereco.bairro(),
                    "municipio", endereco.municipio(), "uf", endereco.uf(),
                    "latitude", endereco.latitude(), "longitude", endereco.longitude())));
            return;
        }
        Dominio.Usuario u = SegurancaWeb.exigirLogado(req, ctx.repo);
        if (metodo.equals("GET") && caminho.equals("/api/v1/apoiadores")) {
            List<Map<String, Object>> lista = ctx.catalogo.listar(
                    req.query("especialidade"), req.query("modalidade"),
                    req.query("municipio"), null)
                    .stream().map(ctx::apoiadorView).toList();
            json(req, 200, Json.de(Contexto.m("items", lista, "total", lista.size())));
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/api/v1/notificacoes")) {
            json(req, 200, Json.de(Contexto.m("items",
                    ctx.notificacoesView(ctx.notificacao.listar(u.id)),
                    "naoLidas", ctx.notificacao.naoLidas(u.id))));
            return;
        }
        if (metodo.equals("GET") && p.length == 4 && p[0].equals("api") && p[1].equals("v1")
                && p[2].equals("reservas")) {
            Dominio.Reserva r = ctx.reservas.exigir(Http.uuid(p[3]));
            if (!ctx.reservas.ehParte(r, u.id) && !SegurancaWeb.mediacao(u)) {
                throw new Erros.PermissaoNegadaException("Reserva de outra pessoa.");
            }
            json(req, 200, Json.de(ctx.reservaView(r, u.id)));
            return;
        }
        throw new Erros.NaoEncontradoException("endpoint");
    }

    // -------------------------------------------------------------------- erros

    private void responderErro(Http.Requisicao req, Exception e, String correlacao) {
        int status = 500;
        String mensagem;
        String titulo;
        if (e instanceof Erros.ValidacaoException v) {
            status = 422;
            titulo = "Dados invalidos";
            mensagem = v.getMessage();
        } else if (e instanceof Erros.PermissaoNegadaException pe) {
            status = 403;
            titulo = "Acesso negado";
            mensagem = pe.getMessage();
        } else if (e instanceof Erros.ConflitoException ce) {
            status = 409;
            titulo = "Conflito";
            mensagem = ce.getMessage();
        } else if (e instanceof Erros.EstadoInvalidoException ee) {
            status = 409;
            titulo = "Acao indisponivel";
            mensagem = ee.getMessage();
        } else if (e instanceof Erros.PrazoExpiradoException pe) {
            status = 410;
            titulo = "Prazo expirado";
            mensagem = pe.getMessage();
        } else if (e instanceof Erros.LimiteExcedidoException le) {
            status = 429;
            titulo = "Limite excedido";
            mensagem = le.getMessage();
        } else if (e instanceof Erros.NaoEncontradoException ne) {
            status = 404;
            titulo = "Nao encontrado";
            mensagem = ne.getMessage();
        } else if (e instanceof Erros.IntegracaoException ie) {
            status = 503;
            titulo = "Servico indisponivel";
            mensagem = ie.getMessage();
        } else if (e instanceof Erros.EloVitalException ev) {
            status = 400;
            titulo = "Nao foi possivel concluir";
            mensagem = ev.getMessage();
        } else {
            e.printStackTrace(System.err);
            titulo = "Erro interno";
            mensagem = "Ocorreu um erro inesperado. Tente novamente.";
        }
        ctx.metricas.erro();
        try {
            if (req != null && status == 429) {
                req.exchange().getResponseHeaders().set("Retry-After", "60");
            }
            if (req != null && req.json()) {
                Http.texto(req.exchange(), status, "application/problem+json",
                        Json.problema(status, titulo, mensagem, null));
            } else if (req != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", status);
                m.put("titulo", titulo);
                m.put("mensagem", mensagem);
                m.put("correlacao", correlacao);
                String html = ctx.templates.render("erro", ctx.pagina(req, titulo, m));
                Http.html(req.exchange(), status, html);
            } else {
                return;
            }
        } catch (Exception ignorada) {
            // a resposta pode ja ter sido enviada (SSE)
        }
    }

    // ------------------------------------------------------------------ util

    private void render(Http.Requisicao req, String template, String titulo,
            Map<String, Object> extras, int status) throws IOException {
        String html = ctx.templates.render(template, ctx.pagina(req, titulo, extras));
        Http.html(req.exchange(), status, html);
    }

    private void json(Http.Requisicao req, int status, String corpo) throws IOException {
        Http.texto(req.exchange(), status, "application/json; charset=utf-8", corpo);
    }

    private static String url(String texto) {
        return URLEncoder.encode(texto == null ? "" : texto, StandardCharsets.UTF_8);
    }

    private static LocalDate parseData(String texto) {
        try {
            return LocalDate.parse(texto);
        } catch (Exception e) {
            throw new Erros.ValidacaoException("nascimento", "formato",
                    "Informe a data no formato AAAA-MM-DD.");
        }
    }

    private static double parseDouble(String texto) {
        try {
            return Double.parseDouble(texto.replace(",", "."));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static UUID parseUuid(String texto) {
        try {
            return texto == null || texto.isBlank() ? null : UUID.fromString(texto);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Dominio.Modalidade modalidade(String valor) {
        try {
            return valor == null || valor.isBlank() ? null : Dominio.Modalidade.valueOf(valor);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Dominio.Prioridade prioridade(String valor) {
        try {
            return valor == null || valor.isBlank() ? Dominio.Prioridade.MEDIA
                    : Dominio.Prioridade.valueOf(valor);
        } catch (IllegalArgumentException e) {
            return Dominio.Prioridade.MEDIA;
        }
    }
}

