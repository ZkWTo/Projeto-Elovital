package View;

import Model.Dominio;
import Model.Erros;
import java.util.Set;

/** ADR 0008 / 0022: barreira de papel, CSRF, cabecalhos e mensagens seguras. */
public final class SegurancaWeb {

    private SegurancaWeb() {}

    public static final class NaoAutenticado extends RuntimeException {
        public NaoAutenticado() {
            super("Sessao ausente ou expirada.");
        }
    }

    public static void cabecalhos(com.sun.net.httpserver.HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Content-Security-Policy",
                "default-src 'self'; style-src 'self'; script-src 'self'; "
                + "img-src 'self' data: blob:; connect-src 'self'; "
                + "form-action 'self'; frame-ancestors 'none'; base-uri 'self'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "strict-origin-when-cross-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        h.set("Cache-Control", "no-store");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        if ("true".equalsIgnoreCase(System.getenv("ELOVITAL_HTTPS"))) {
            h.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    public static Set<String> autoridades(Dominio.Usuario u) {
        return switch (u.papel) {
            case ASSISTIDO -> Set.of("PERFIL_PROPRIO_LER", "PERFIL_PROPRIO_EDITAR",
                    "SOLICITACAO_CRIAR", "RESERVA_CRIAR", "CHAT_PARTICIPAR",
                    "AVALIACAO_CRIAR", "DENUNCIA_CRIAR");
            case APOIADOR -> Set.of("PERFIL_PROPRIO_LER", "PERFIL_PROPRIO_EDITAR",
                    "AGENDA_GERENCIAR", "CHAT_PARTICIPAR",
                    "AVALIACAO_CRIAR", "DENUNCIA_CRIAR");
            case MEDIADOR -> Set.of("CONTAS_LER", "CONTAS_VALIDAR", "CONTAS_SANCIONAR",
                    "SOLICITACOES_ANALISAR", "AGENDAS_GERENCIAR", "DENUNCIAS_TRATAR",
                    "OCORRENCIAS_TRATAR", "CONTEUDO_OCULTAR", "PARAMETROS_EDITAR",
                    "AUDITORIA_LER", "DADOS_SENSIVEIS_ACESSAR", "MEDIACAO");
        };
    }

    public static boolean mediacao(Dominio.Usuario u) {
        return u.papel == Dominio.Papel.MEDIADOR && u.mediacaoVinculo;
    }

    public static void validarCsrf(Http.Requisicao req) {
        if (!"POST".equalsIgnoreCase(req.exchange().getRequestMethod())) {
            return;
        }
        String enviado = req.param("csrf");
        if (enviado.isBlank()) {
            enviado = req.exchange().getRequestHeaders().getFirst("X-CSRF-TOKEN");
        }
        String esperado = req.sessao().map(s -> s.csrf).orElse(null);
        if (esperado == null || enviado == null || !esperado.equals(enviado)) {
            throw new Erros.PermissaoNegadaException(
                    "Sessao invalida ou token CSRF ausente. Recarregue a pagina.");
        }
    }

    public static Dominio.Usuario exigirLogado(Http.Requisicao req, Model.Repositorios repo) {
        Http.Sessao s = req.sessao().orElseThrow(NaoAutenticado::new);
        Dominio.Usuario u = repo.usuario(s.usuarioId).orElseThrow(NaoAutenticado::new);
        if (!u.ativo()) {
            throw new Erros.PermissaoNegadaException("Conta sem acesso ativo.");
        }
        return u;
    }

    public static Dominio.Usuario exigirMediacao(Http.Requisicao req, Model.Repositorios repo) {
        Dominio.Usuario u = exigirLogado(req, repo);
        if (!mediacao(u)) {
            throw new Erros.PermissaoNegadaException("Area restrita a mediacao.");
        }
        return u;
    }

    public static String mensagemErro(Throwable t) {
        if (t instanceof Erros.ValidacaoException v) {
            return v.getMessage();
        }
        if (t instanceof Erros.EloVitalException e) {
            return e.getMessage();
        }
        return "Nao foi possivel concluir a operacao.";
    }
}
