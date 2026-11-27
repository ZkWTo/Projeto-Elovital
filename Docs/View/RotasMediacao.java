package View;

import Model.Dominio;
import Model.Erros;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ADR 0023 / RF015: painel de mediacao com filas, acoes auditadas e acesso minimo. */
public final class RotasMediacao {

    private final Contexto ctx;

    public RotasMediacao(Contexto ctx) {
        this.ctx = ctx;
    }

    public void despachar(Http.Requisicao req) throws IOException {
        Dominio.Usuario mediador = SegurancaWeb.exigirMediacao(req, ctx.repo);
        String caminho = req.caminho();
        String metodo = req.exchange().getRequestMethod();
        String[] p = Http.partes(req.exchange());

        if (metodo.equals("GET") && caminho.equals("/mediacao")) {
            painel(req);
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/mediacao/solicitacoes")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("solicitacoes", ctx.solicitacoesView(ctx.solicitacoes.filaAnalise(), mediador.id));
            m.put("abertas", ctx.solicitacoesView(ctx.solicitacoes.publicas(), mediador.id));
            render(req, "mediacao-solicitacoes", "Fila de solicitacoes", m);
            return;
        }
        if (metodo.equals("POST") && p.length == 4 && p[0].equals("mediacao")
                && p[1].equals("solicitacoes")) {
            SegurancaWeb.validarCsrf(req);
            UUID id = Http.uuid(p[2]);
            switch (p[3]) {
                case "aprovar" -> ctx.solicitacoes.aprovar(mediador.id, id,
                        Rotas.prioridade(req.param("prioridade")), req.param("observacao"), req.ipHash());
                case "ajuste" -> ctx.solicitacoes.solicitarAjuste(mediador.id, id,
                        req.param("justificativa"), req.ipHash());
                case "recusar" -> ctx.solicitacoes.recusar(mediador.id, id,
                        req.param("justificativa"), req.ipHash());
                case "cancelar" -> ctx.solicitacoes.cancelar(mediador.id, id,
                        req.param("justificativa"), req.ipHash());
                default -> throw new Erros.NaoEncontradoException("acao");
            }
            Http.redirect(req.exchange(), "/mediacao/solicitacoes?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/mediacao/contas")) {
            List<Map<String, Object>> contas = ctx.repo.usuariosOrdenados().stream()
                    .map(ctx::usuarioView).toList();
            List<Map<String, Object>> docs = ctx.repo.documentos.values().stream()
                    .filter(d -> d.status != Dominio.StatusDocumento.EXPURGADO)
                    .map(ctx::documentoView).toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("contas", contas);
            m.put("documentos", docs);
            m.put("emAnalise", ctx.contas.contasEmAnalise().size());
            render(req, "mediacao-contas", "Contas e verificacao", m);
            return;
        }
        if (metodo.equals("POST") && p.length == 4 && p[0].equals("mediacao")
                && p[1].equals("contas")) {
            SegurancaWeb.validarCsrf(req);
            UUID id = Http.uuid(p[2]);
            switch (p[3]) {
                case "aprovar" -> ctx.contas.aprovarConta(mediador.id, id, req.ipHash());
                case "recusar" -> ctx.contas.recusarConta(mediador.id, id,
                        req.param("motivo"), req.ipHash());
                case "suspender" -> ctx.contas.suspenderConta(mediador.id, id,
                        req.param("motivo"), req.ipHash());
                case "reativar" -> ctx.contas.reativarConta(mediador.id, id, req.ipHash());
                case "anonimizar" -> ctx.contas.anonimizar(id, req.ipHash());
                default -> throw new Erros.NaoEncontradoException("acao");
            }
            Http.redirect(req.exchange(), "/mediacao/contas?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("POST") && p.length == 4 && p[0].equals("mediacao")
                && p[1].equals("documentos") && p[3].equals("decidir")) {
            SegurancaWeb.validarCsrf(req);
            ctx.contas.decidirDocumento(mediador.id, Http.uuid(p[2]),
                    "aprovar".equals(req.param("decisao")), req.param("justificativa"),
                    req.param("motivoAcesso"));
            Http.redirect(req.exchange(), "/mediacao/contas?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/mediacao/denuncias")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("denuncias", ctx.moderacao.denunciasEmAnalise().stream()
                    .map(ctx::denunciaView).toList());
            m.put("contestacoes", ctx.moderacao.contestacoesAbertas().stream()
                    .map(ctx::contestacaoView).toList());
            m.put("sancoes", ctx.repo.sancoes.values().stream()
                    .map(ctx::sancaoView).toList());
            m.put("reservasAnalise", ctx.repo.reservas.values().stream()
                    .filter(r -> r.status == Dominio.StatusReserva.EM_ANALISE_MEDIACAO)
                    .map(r -> ctx.reservaView(r, mediador.id)).toList());
            render(req, "mediacao-denuncias", "Denuncias e sancoes", m);
            return;
        }
        if (metodo.equals("POST") && p.length == 4 && p[0].equals("mediacao")
                && p[1].equals("denuncias") && p[3].equals("decidir")) {
            SegurancaWeb.validarCsrf(req);
            ctx.moderacao.decidirDenuncia(mediador.id, Http.uuid(p[2]),
                    "procedente".equals(req.param("decisao")), req.param("justificativa"), req.ipHash());
            Http.redirect(req.exchange(), "/mediacao/denuncias?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/mediacao/sancoes")) {
            SegurancaWeb.validarCsrf(req);
            UUID usuarioId = req.uuid("usuarioId");
            if (usuarioId == null) {
                throw new Erros.ValidacaoException("usuario", "obrigatorio", "Selecione o usuario.");
            }
            ctx.moderacao.aplicarSancao(mediador.id, usuarioId,
                    Dominio.TipoSancao.valueOf(req.param("tipo")), req.param("motivo"),
                    req.inteiro("dias", 0), req.uuid("denunciaId"), req.ipHash());
            Http.redirect(req.exchange(), "/mediacao/denuncias?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("POST") && p.length == 4 && p[0].equals("mediacao")
                && p[1].equals("contestacoes") && p[3].equals("decidir")) {
            SegurancaWeb.validarCsrf(req);
            ctx.moderacao.decidirContestacao(mediador.id, Http.uuid(p[2]),
                    "deferir".equals(req.param("decisao")), req.param("justificativa"), req.ipHash());
            Http.redirect(req.exchange(), "/mediacao/denuncias?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/mediacao/ocultar")) {
            SegurancaWeb.validarCsrf(req);
            UUID id = req.uuid("id");
            if (id == null) {
                throw new Erros.ValidacaoException("id", "obrigatorio", "Informe o identificador.");
            }
            ctx.moderacao.ocultar(mediador.id, req.param("tipo"), id, req.param("motivo"), req.ipHash());
            Http.redirect(req.exchange(), "/mediacao/denuncias?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("POST") && p.length == 4 && p[0].equals("mediacao")
                && p[1].equals("reservas") && p[3].equals("decisao")) {
            SegurancaWeb.validarCsrf(req);
            ctx.reservas.decidirMediacao(mediador.id, Http.uuid(p[2]),
                    "cancelar".equals(req.param("decisao")), req.param("motivo"), req.ipHash());
            Http.redirect(req.exchange(), "/mediacao/denuncias?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/mediacao/parametros")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("parametros", ctx.parametros.listar().stream().map(ctx::parametroView).toList());
            m.put("especialidades", ctx.catalogo.especialidades());
            m.put("condicoes", ctx.catalogo.condicoes());
            m.put("municipios", ctx.catalogo.municipios());
            render(req, "mediacao-parametros", "Parametros e cadastros", m);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/mediacao/parametros")) {
            SegurancaWeb.validarCsrf(req);
            ctx.parametros.atualizar(mediador.id, req.param("chave"), req.param("valor"), req.ipHash());
            Http.redirect(req.exchange(), "/mediacao/parametros?ok=parametro_ok");
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/mediacao/cadastros")) {
            SegurancaWeb.validarCsrf(req);
            switch (req.param("tipo")) {
                case "especialidade" -> ctx.parametros.adicionarEspecialidade(mediador.id,
                        req.param("nome"), req.flag("regulamentada"), req.ipHash());
                case "condicao" -> ctx.parametros.adicionarCondicao(mediador.id,
                        req.param("nome"), req.ipHash());
                case "municipio" -> ctx.parametros.adicionarMunicipio(mediador.id,
                        req.param("nome"), req.param("uf"), req.decimal("latitude", 0),
                        req.decimal("longitude", 0), req.ipHash());
                default -> throw new Erros.ValidacaoException("tipo", "invalido",
                        "Tipo de cadastro desconhecido.");
            }
            Http.redirect(req.exchange(), "/mediacao/parametros?ok=mediacao_ok");
            return;
        }
        if (metodo.equals("GET") && caminho.equals("/mediacao/auditoria")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("auditoria", ctx.auditoria.listar(200).stream().map(ctx::auditoriaView).toList());
            m.put("acessos", ctx.auditoria.acessos().stream().map(a -> Contexto.m(
                    "campo", a.campo, "motivo", a.motivo,
                    "mediador", ctx.repo.usuario(a.mediadorId).map(u -> u.nomeCompleto).orElse("-"),
                    "alvo", ctx.repo.usuario(a.alvoUsuarioId).map(u -> u.nomeCompleto).orElse("-"),
                    "quando", Formatador.dataHora(a.criadoEm))).toList());
            render(req, "mediacao-auditoria", "Auditoria", m);
            return;
        }
        if (metodo.equals("POST") && caminho.equals("/mediacao/rotinas")) {
            SegurancaWeb.validarCsrf(req);
            ctx.rotinas.executar();
            Http.redirect(req.exchange(), "/mediacao?ok=mediacao_ok");
            return;
        }
        throw new Erros.NaoEncontradoException("pagina da mediacao");
    }

    private void painel(Http.Requisicao req) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filaSolicitacoes", ctx.solicitacoes.filaAnalise().size());
        m.put("contasAnalise", ctx.contas.contasEmAnalise().size());
        m.put("denuncias", ctx.moderacao.denunciasEmAnalise().size());
        m.put("contestacoes", ctx.moderacao.contestacoesAbertas().size());
        m.put("reservasAnalise", ctx.repo.reservas.values().stream()
                .filter(r -> r.status == Dominio.StatusReserva.EM_ANALISE_MEDIACAO).count());
        m.put("documentos", ctx.repo.documentos.values().stream()
                .filter(d -> d.status == Dominio.StatusDocumento.PENDENTE
                        || d.status == Dominio.StatusDocumento.EM_ANALISE)
                .count());
        m.put("solicitacoes", ctx.solicitacoesView(ctx.solicitacoes.filaAnalise().stream()
                .limit(8).toList(), null));
        m.put("contas", ctx.repo.usuarios.values().stream()
                .filter(u -> u.status == Dominio.StatusConta.EM_ANALISE)
                .limit(8).map(ctx::usuarioView).toList());
        m.put("notificacoes", ctx.notificacoesView(ctx.notificacao.ultimasParaMediacao()));
        m.put("rotinas", ctx.rotinas.resumo());
        m.put("ultimaRotina", Formatador.dataHora(ctx.rotinas.ultimaExecucao()));
        m.put("auditoria", ctx.auditoria.listar(12).stream().map(ctx::auditoriaView).toList());
        render(req, "mediacao", "Painel da mediacao", m);
    }

    private void render(Http.Requisicao req, String template, String titulo,
            Map<String, Object> extras) throws IOException {
        String html = ctx.templates.render(template, ctx.pagina(req, titulo, extras));
        Http.html(req.exchange(), 200, html);
    }
}

