package View;

import java.util.List;
import java.util.Map;

/** ADR 0010: JSON minimo e erros no formato application/problem+json (RFC 9457). */
public final class Json {

    private Json() {}

    public static String de(Object valor) {
        StringBuilder sb = new StringBuilder();
        escrever(sb, valor);
        return sb.toString();
    }

    private static void escrever(StringBuilder sb, Object valor) {
        if (valor == null) {
            sb.append("null");
        } else if (valor instanceof String s) {
            string(sb, s);
        } else if (valor instanceof Number || valor instanceof Boolean) {
            sb.append(valor);
        } else if (valor instanceof Map<?, ?> mapa) {
            sb.append('{');
            boolean primeiro = true;
            for (Map.Entry<?, ?> e : mapa.entrySet()) {
                if (!primeiro) {
                    sb.append(',');
                }
                primeiro = false;
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                escrever(sb, e.getValue());
            }
            sb.append('}');
        } else if (valor instanceof List<?> lista) {
            sb.append('[');
            for (int i = 0; i < lista.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                escrever(sb, lista.get(i));
            }
            sb.append(']');
        } else if (valor instanceof Enum<?> e) {
            string(sb, e.name());
        } else if (valor instanceof java.time.temporal.TemporalAccessor t) {
            string(sb, t.toString());
        } else {
            string(sb, String.valueOf(valor));
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static String problema(int status, String titulo, String detalhe, Map<String, String> erros) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"about:blank\",\"title\":");
        string(sb, titulo);
        sb.append(",\"status\":").append(status).append(",\"detail\":");
        string(sb, detalhe == null ? "" : detalhe);
        if (erros != null && !erros.isEmpty()) {
            sb.append(",\"errors\":[");
            boolean primeiro = true;
            for (Map.Entry<String, String> e : erros.entrySet()) {
                if (!primeiro) {
                    sb.append(',');
                }
                primeiro = false;
                sb.append("{\"field\":");
                string(sb, e.getKey());
                sb.append(",\"message\":");
                string(sb, e.getValue());
                sb.append('}');
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }
}
