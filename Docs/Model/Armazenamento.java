package Model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADR 0017: bucket privado. O prototipo guarda metadados e conteudo em memoria,
 * mas mantem as regras: sem URL publica permanente, tipos permitidos, limite de
 * tamanho, URL assinada de curta duracao e expurgo logico.
 */
public final class Armazenamento {

    public static final long TAMANHO_MAXIMO = 5L * 1024 * 1024;

    private final Relogio relogio;
    private final Map<String, Arquivo> arquivos = new ConcurrentHashMap<>();
    private final Map<String, Assinatura> assinaturas = new ConcurrentHashMap<>();

    public Armazenamento(Relogio relogio) {
        this.relogio = relogio;
    }

    public record Arquivo(String caminho, String nomeOriginal, String mime,
            long tamanho, UUID donoId, byte[] conteudo, Instant criadoEm) {}

    private record Assinatura(String caminho, UUID solicitante, Instant expiraEm) {}

    public String salvar(UUID donoId, String nomeOriginal, String mime, byte[] conteudo) {
        if (conteudo == null || conteudo.length == 0) {
            throw new Erros.ValidacaoException("arquivo", "vazio", "O arquivo esta vazio.");
        }
        if (conteudo.length > TAMANHO_MAXIMO) {
            throw new Erros.ValidacaoException("arquivo", "tamanho",
                    "O arquivo deve ter no maximo 5 MB.");
        }
        String tipo = mime == null ? "" : mime.toLowerCase();
        if (!tipo.equals("application/pdf") && !tipo.equals("image/jpeg") && !tipo.equals("image/png")) {
            throw new Erros.ValidacaoException("arquivo", "formato", "Envie PDF, JPG ou PNG.");
        }
        String caminho = "privado/" + donoId + "/" + UUID.randomUUID();
        arquivos.put(caminho, new Arquivo(caminho, nomeOriginal, tipo,
                conteudo.length, donoId, conteudo.clone(), relogio.agora()));
        return caminho;
    }

    public String urlAssinada(String caminho, UUID solicitante, int minutos) {
        Arquivo a = arquivos.get(caminho);
        if (a == null) {
            throw new Erros.NaoEncontradoException("arquivo");
        }
        if (!a.donoId().equals(solicitante)) {
            throw new Erros.PermissaoNegadaException(
                    "Acesso somente pelo dono ou pela mediacao com motivo registrado.");
        }
        String token = Cripto.tokenAleatorio();
        assinaturas.put(token, new Assinatura(caminho, solicitante,
                relogio.agora().plus(Duration.ofMinutes(minutos))));
        return "/arquivos/" + token;
    }

    public byte[] ler(String token) {
        Assinatura a = assinaturas.get(token);
        if (a == null || a.expiraEm().isBefore(relogio.agora())) {
            assinaturas.remove(token);
            throw new Erros.PrazoExpiradoException("URL do arquivo");
        }
        Arquivo arquivo = arquivos.get(a.caminho());
        if (arquivo == null) {
            throw new Erros.NaoEncontradoException("arquivo");
        }
        return arquivo.conteudo();
    }

    public void expurgar(String caminho) {
        arquivos.remove(caminho);
        assinaturas.values().removeIf(a -> a.caminho().equals(caminho));
    }

    public int total() {
        return arquivos.size();
    }
}
