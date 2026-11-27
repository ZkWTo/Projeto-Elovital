package View;

import Model.Cripto;
import Model.Dominio;
import Model.Relogio;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** ADR 0008 / 0010 / 0022: sessao no servidor, cookies, formularios e respostas. */
public final class Http {

    private Http() {}

    public static final String COOKIE = "ELOVITAL_SESSAO";

    public record Requisicao(
            HttpExchange exchange,
            String caminho,
            Map<String, String> query,
            Map<String, String> form,
            Map<String, String> cookies,
            Optional<Sessao> sessao,
            String ip,
            String ipHash,
            boolean json) {

        public String param(String nome) {
            return form.getOrDefault(nome, query.getOrDefault(nome, ""));
        }

        public String query(String nome) {
            return query.getOrDefault(nome, "");
        }

        public int inteiro(String nome, int padrao) {
            try {
                return Integer.parseInt(param(nome));
            } catch (NumberFormatException e) {
                return padrao;
            }
        }

        public double decimal(String nome, double padrao) {
            try {
                return Double.parseDouble(param(nome).replace(",", "."));
            } catch (NumberFormatException e) {
                return padrao;
            }
        }

        public boolean flag(String nome) {
            return "on".equals(param(nome)) || "true".equals(param(nome)) || "1".equals(param(nome));
        }

        public UUID uuid(String nome) {
            try {
                String valor = param(nome);
                return valor.isBlank() ? null : UUID.fromString(valor);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        public String cookie(String nome) {
            return cookies.get(nome);
        }
    }

    public static final class Sessao {
        public final String token;
        public final UUID usuarioId;
        public final String csrf;
        public final Instant criadaEm;
        public Instant ultimoAcesso;
        public final boolean mediador;
        public String flash;

        public Sessao(String token, UUID usuarioId, boolean mediador) {
            this.token = token;
            this.usuarioId = usuarioId;
            this.mediador = mediador;
            this.csrf = Cripto.tokenAleatorio();
            this.criadaEm = Instant.now();
            this.ultimoAcesso = this.criadaEm;
        }
    }

    public static final class Sessoes {
        private final Map<String, Sessao> sessoes = new ConcurrentHashMap<>();
        private final Relogio relogio;

        public Sessoes(Relogio relogio) {
            this.relogio = relogio;
        }

        public Sessao criar(UUID usuarioId, boolean mediador) {
            Sessao s = new Sessao(Cripto.tokenAleatorio(), usuarioId, mediador);
            s.ultimoAcesso = relogio.agora();
            sessoes.put(s.token, s);
            return s;
        }

        public Optional<Sessao> buscar(String token, int timeoutMin) {
            if (token == null) {
                return Optional.empty();
            }
            Sessao s = sessoes.get(token);
            if (s == null) {
                return Optional.empty();
            }
            if (s.ultimoAcesso.plus(Duration.ofMinutes(timeoutMin)).isBefore(relogio.agora())) {
                sessoes.remove(token);
                return Optional.empty();
            }
            s.ultimoAcesso = relogio.agora();
            return Optional.of(s);
        }

        public void invalidar(String token) {
            if (token != null) {
                sessoes.remove(token);
            }
        }

        public void invalidarUsuario(UUID usuarioId) {
            sessoes.values().removeIf(s -> s.usuarioId.equals(usuarioId));
        }

        public void limparExpiradas(int timeoutMin) {
            Instant limite = relogio.agora().minus(Duration.ofMinutes(timeoutMin));
            sessoes.values().removeIf(s -> s.ultimoAcesso.isBefore(limite));
        }
    }

    public static Requisicao ler(HttpExchange ex, Sessoes sessoes, int timeoutMin) throws IOException {
        String caminho = ex.getRequestURI().getPath();
        String queryString = ex.getRequestURI().getRawQuery();
        Map<String, String> query = parseUrlEncoded(queryString);

        Map<String, String> form = new HashMap<>();
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String tipo = ex.getRequestHeaders().getFirst("Content-Type");
            if (tipo == null || tipo.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
                try (InputStream in = ex.getRequestBody()) {
                    form = parseUrlEncoded(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        Map<String, String> cookies = parseCookies(ex.getRequestHeaders().getFirst("Cookie"));
        String ip = ip(ex);
        String ipHash = Cripto.ipHash(ip);
        Optional<Sessao> sessao = sessoes.buscar(cookies.get(COOKIE), timeoutMin);
        boolean json = caminho.startsWith("/api/") || aceitaJson(ex);
        return new Requisicao(ex, caminho, query, form, cookies, sessao, ip, ipHash, json);
    }

    public static boolean aceitaJson(HttpExchange ex) {
        String aceito = ex.getRequestHeaders().getFirst("Accept");
        return aceito != null && aceito.contains("application/json");
    }

    public static Map<String, String> parseUrlEncoded(String texto) {
        Map<String, String> mapa = new LinkedHashMap<>();
        if (texto == null || texto.isBlank()) {
            return mapa;
        }
        for (String par : texto.split("&")) {
            if (par.isEmpty()) {
                continue;
            }
            int igual = par.indexOf('=');
            String chave = igual >= 0 ? par.substring(0, igual) : par;
            String valor = igual >= 0 ? par.substring(igual + 1) : "";
            mapa.put(decode(chave), decode(valor));
        }
        return mapa;
    }

    private static String decode(String valor) {
        return URLDecoder.decode(valor, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseCookies(String header) {
        Map<String, String> mapa = new HashMap<>();
        if (header == null) {
            return mapa;
        }
        for (String par : header.split(";")) {
            int igual = par.indexOf('=');
            if (igual > 0) {
                mapa.put(par.substring(0, igual).trim(), par.substring(igual + 1).trim());
            }
        }
        return mapa;
    }

    public static String ip(HttpExchange ex) {
        String encaminhado = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (encaminhado != null && !encaminhado.isBlank()) {
            return encaminhado.split(",")[0].trim();
        }
        return ex.getRemoteAddress() == null ? "desconhecido"
                : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    public static void html(HttpExchange ex, int status, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void texto(HttpExchange ex, int status, String tipo, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void bytes(HttpExchange ex, int status, String tipo, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void redirect(HttpExchange ex, String destino) throws IOException {
        ex.getResponseHeaders().set("Location", destino);
        ex.sendResponseHeaders(303, -1);
    }

    public static void cookieSessao(HttpExchange ex, String token, int segundos) {
        String valor = COOKIE + "=" + token
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + segundos;
        ex.getResponseHeaders().add("Set-Cookie", valor);
    }

    public static void apagarCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie",
                COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
    }

    public static String[] partes(HttpExchange ex) {
        String caminho = ex.getRequestURI().getPath();
        String semBarra = caminho.startsWith("/") ? caminho.substring(1) : caminho;
        return semBarra.isEmpty() ? new String[0] : semBarra.split("/");
    }

    /** Identificador de rota invalido vira 404, nunca 500. */
    public static UUID uuid(String valor) {
        try {
            return UUID.fromString(valor);
        } catch (IllegalArgumentException e) {
            throw new Model.Erros.NaoEncontradoException("identificador");
        }
    }

    public static String mensagem(String chave) {
        return switch (chave) {
            case "cadastro_ok" -> "Cadastro recebido. Aguarde a analise da mediacao.";
            case "login_ok" -> "Bem-vindo de volta.";
            case "perfil_ok" -> "Perfil atualizado.";
            case "senha_ok" -> "Senha redefinida. Entre com a nova senha.";
            case "solicitacao_ok" -> "Solicitacao enviada para analise.";
            case "reserva_ok" -> "Reserva criada com sucesso.";
            case "avaliacao_ok" -> "Avaliacao registrada.";
            case "denuncia_ok" -> "Denuncia registrada. A mediacao vai analisar.";
            case "documento_ok" -> "Documento enviado para analise.";
            case "parametro_ok" -> "Parametro atualizado.";
            case "mediacao_ok" -> "Acao registrada.";
            default -> chave;
        };
    }
}
