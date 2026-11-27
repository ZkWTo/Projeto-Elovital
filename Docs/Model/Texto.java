package Model;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * ADR 0009: conteudo sempre em texto puro, limites em code points,
 * normalizacao NFC e detector deterministico de conteudo proibido.
 */
public final class Texto {

    private Texto() {}

    public static final int NOME = 120;
    public static final int EMAIL = 200;
    public static final int TITULO_SOLICITACAO = 80;
    public static final int DESCRICAO_SOLICITACAO = 1_000;
    public static final int OBSERVACAO_CUIDADO = 500;
    public static final int OBSERVACAO_HORARIO = 200;
    public static final int MENSAGEM_CHAT = 1_000;
    public static final int COMENTARIO_AVALIACAO = 300;
    public static final int JUSTIFICATIVA = 500;
    public static final int DESCRICAO_DENUNCIA = 500;
    public static final int CONDICAO_OUTRA = 100;
    public static final int LIMITACOES = 300;
    public static final int OBSERVACAO_INTERNA = 500;

    public enum ResultadoDeteccao { LIMPO, SUSPEITO, BLOQUEADO }

    public record Deteccao(ResultadoDeteccao resultado, List<String> termos) {
        public boolean bloqueado() {
            return resultado == ResultadoDeteccao.BLOQUEADO;
        }

        public boolean suspeito() {
            return resultado == ResultadoDeteccao.SUSPEITO;
        }
    }

    public static String normalizar(String valor) {
        if (valor == null) {
            return "";
        }
        String nfc = Normalizer.normalize(valor, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(nfc.length());
        for (int i = 0; i < nfc.length(); i++) {
            char c = nfc.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || !Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    public static String exigir(String valor, String campo, int limite) {
        String texto = normalizar(valor);
        if (texto.isEmpty()) {
            throw new Erros.ValidacaoException(campo, "obrigatorio", "Preencha o campo " + campo + ".");
        }
        exigirLimite(texto, campo, limite);
        return texto;
    }

    public static String opcional(String valor, String campo, int limite) {
        String texto = normalizar(valor);
        exigirLimite(texto, campo, limite);
        return texto;
    }

    public static void exigirLimite(String texto, String campo, int limite) {
        if (texto.codePointCount(0, texto.length()) > limite) {
            throw new Erros.ValidacaoException(
                    campo, "limite", "O campo " + campo + " aceita no maximo " + limite + " caracteres.");
        }
    }

    /** Remove acentos e caixa para comparacao do detector. */
    public static String dobra(String valor) {
        String semAcento = Normalizer.normalize(valor, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return semAcento.toLowerCase(Locale.ROOT);
    }

    private static final List<String> TERMOS_DINHEIRO = List.of(
            "pix", "transferencia", "conta bancaria", "agencia", "deposita",
            "deposito", "me manda", "dinheiro", "empresta", "pagamento", "boleto");

    private static final List<String> TERMOS_CONTATO = List.of(
            "telefone", "whatsapp", "zap", "email pessoal", "me liga", "cpf");

    /**
     * Regra deterministica (nao IA). Pedido de dinheiro/dados bancarios bloqueia;
     * telefone e links geram alerta. A lista e cadastro-base (ADR 0019).
     */
    public static Deteccao detectar(String texto, boolean contextoPublico) {
        String t = dobra(normalizar(texto));
        java.util.ArrayList<String> achados = new java.util.ArrayList<>();

        for (String termo : TERMOS_DINHEIRO) {
            if (t.contains(termo)) {
                achados.add(termo);
            }
        }
        if (!achados.isEmpty()) {
            return new Deteccao(ResultadoDeteccao.BLOQUEADO, List.copyOf(achados));
        }

        for (String termo : TERMOS_CONTATO) {
            if (t.contains(termo)) {
                achados.add(termo);
            }
        }
        if (t.matches(".*\\b\\d{10,11}\\b.*")) {
            achados.add("sequencia de 10-11 digitos");
        }
        if (t.matches(".*https?://\\S+.*") || t.matches(".*www\\.\\S+.*")) {
            achados.add("link");
        }
        if (!achados.isEmpty()) {
            return new Deteccao(
                    contextoPublico ? ResultadoDeteccao.BLOQUEADO : ResultadoDeteccao.SUSPEITO,
                    List.copyOf(achados));
        }
        return new Deteccao(ResultadoDeteccao.LIMPO, List.of());
    }

    public static String mascarar(String valor) {
        if (valor == null || valor.isEmpty()) {
            return "";
        }
        int n = valor.length();
        if (n <= 4) {
            return "*".repeat(n);
        }
        return "*".repeat(n - 4) + valor.substring(n - 4);
    }
}
