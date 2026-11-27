package View;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR 0010 / 0009: renderizacao server-side. O motor aceita apenas texto puro
 * (variaveis escapadas), secoes, listas e parciais. Nao existe th:utext: o
 * conteudo de usuario e sempre escapado.
 */
public final class TemplateEngine {

    private final Path base;

    public TemplateEngine() {
        this.base = localizarBase();
    }

    private static Path localizarBase() {
        String custom = System.getProperty("elovital.templates");
        Path[] candidatos = {
            custom == null ? null : Path.of(custom),
            Path.of("Docs", "Template"),
            Path.of("Template"),
            Path.of("..", "Docs", "Template"),
            Path.of("..", "Template")
        };
        for (Path p : candidatos) {
            if (p != null && Files.isDirectory(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return Path.of("Docs", "Template").toAbsolutePath().normalize();
    }

    public String render(String nome, Map<String, Object> contexto) {
        return renderTexto(carregar(nome), contexto);
    }

    public Path base() {
        return base;
    }

    private String carregar(String nome) {
        Path arquivo = base.resolve(nome + ".html");
        try {
            return Files.readString(arquivo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Template nao encontrado: " + arquivo, e);
        }
    }

    private String renderTexto(String tpl, Map<String, Object> ctx) {
        StringBuilder out = new StringBuilder(tpl.length());
        int i = 0;
        while (i < tpl.length()) {
            int abre = tpl.indexOf("{{", i);
            if (abre < 0) {
                out.append(tpl, i, tpl.length());
                break;
            }
            out.append(tpl, i, abre);
            int fecha = tpl.indexOf("}}", abre + 2);
            if (fecha < 0) {
                out.append(tpl.substring(abre));
                break;
            }
            String tag = tpl.substring(abre + 2, fecha).trim();
            i = fecha + 2;

            if (tag.startsWith(">")) {
                out.append(renderTexto(carregar(tag.substring(1).trim()), ctx));
            } else if (tag.startsWith("#") || tag.startsWith("^")) {
                boolean invertida = tag.startsWith("^");
                String chave = tag.substring(1).trim();
                String fechamento = "{{/" + chave + "}}";
                int fim = tpl.indexOf(fechamento, i);
                if (fim < 0) {
                    out.append("{{").append(tag).append("}}");
                    continue;
                }
                String corpo = tpl.substring(i, fim);
                i = fim + fechamento.length();
                Object valor = valor(ctx, chave);
                if (invertida) {
                    if (vazio(valor)) {
                        out.append(renderTexto(corpo, ctx));
                    }
                } else if (valor instanceof List<?> lista) {
                    for (Object item : lista) {
                        if (item instanceof Map<?, ?> mapa) {
                            out.append(renderTexto(corpo, converter(mapa, ctx)));
                        } else {
                            Map<String, Object> novo = new HashMap<>(ctx);
                            novo.putAll(beanMap(item));
                            novo.put("item", item);
                            out.append(renderTexto(corpo, novo));
                        }
                    }
                } else if (valor instanceof Boolean b) {
                    if (b) {
                        out.append(renderTexto(corpo, ctx));
                    }
                } else if (valor instanceof Map<?, ?> mapa) {
                    out.append(renderTexto(corpo, converter(mapa, ctx)));
                } else if (!vazio(valor)) {
                    out.append(renderTexto(corpo, ctx));
                }
            } else if (tag.startsWith("&")) {
                Object v = valor(ctx, tag.substring(1).trim());
                out.append(v == null ? "" : v);
            } else {
                Object v = valor(ctx, tag);
                out.append(escapar(v == null ? "" : String.valueOf(v)));
            }
        }
        return out.toString();
    }

    private Map<String, Object> converter(Map<?, ?> mapa, Map<String, Object> base) {
        Map<String, Object> novo = new HashMap<>(base);
        for (Map.Entry<?, ?> e : mapa.entrySet()) {
            novo.put(String.valueOf(e.getKey()), e.getValue());
        }
        return novo;
    }

    /** Expoe campos publicos e componentes de record para uso no template. */
    private Map<String, Object> beanMap(Object item) {
        Map<String, Object> mapa = new HashMap<>();
        if (item == null) {
            return mapa;
        }
        for (java.lang.reflect.Field campo : item.getClass().getFields()) {
            try {
                mapa.put(campo.getName(), campo.get(item));
            } catch (IllegalAccessException ignorada) {
                // campo inacessivel fica fora do template
            }
        }
        if (item.getClass().isRecord()) {
            for (java.lang.reflect.RecordComponent componente : item.getClass().getRecordComponents()) {
                try {
                    mapa.put(componente.getName(),
                            componente.getAccessor().invoke(item));
                } catch (ReflectiveOperationException ignorada) {
                    // componente inacessivel fica fora do template
                }
            }
        }
        return mapa;
    }

    private boolean vazio(Object valor) {
        return valor == null
                || (valor instanceof String s && s.isBlank())
                || (valor instanceof List<?> l && l.isEmpty())
                || (valor instanceof Boolean b && !b);
    }

    @SuppressWarnings("unchecked")
    private Object valor(Map<String, Object> ctx, String chave) {
        if (ctx.containsKey(chave)) {
            return ctx.get(chave);
        }
        if (chave.contains(".")) {
            String[] partes = chave.split("\\.");
            Object atual = ctx.get(partes[0]);
            for (int i = 1; i < partes.length && atual != null; i++) {
                if (atual instanceof Map<?, ?> m) {
                    atual = m.get(partes[i]);
                } else {
                    return null;
                }
            }
            return atual;
        }
        return null;
    }

    public static String escapar(String valor) {
        StringBuilder sb = new StringBuilder(valor.length() + 16);
        for (int i = 0; i < valor.length(); i++) {
            char c = valor.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
