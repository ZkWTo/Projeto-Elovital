package Model;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * ADR 0015: notificacao interna persistida e entregue depois do commit.
 * No prototipo o "depois do commit" e representado pela chamada apos a transicao.
 */
public final class ServicoNotificacao {

    private final Repositorios repo;
    private final Relogio relogio;

    public ServicoNotificacao(Repositorios repo, Relogio relogio) {
        this.repo = repo;
        this.relogio = relogio;
    }

    public Dominio.Notificacao criar(UUID usuarioId, Dominio.TipoNotificacao tipo,
            String texto, String link) {
        Dominio.Notificacao n = new Dominio.Notificacao();
        n.usuarioId = usuarioId;
        n.tipo = tipo;
        n.texto = Texto.normalizar(texto);
        n.link = link == null ? "/painel" : link;
        n.criadaEm = relogio.agora();
        repo.notificacoes.put(n.id, n);
        return n;
    }

    public void paraMediacao(Dominio.TipoNotificacao tipo, String texto, String link) {
        repo.usuarios.values().stream()
                .filter(u -> u.papel == Dominio.Papel.MEDIADOR && u.ativo())
                .forEach(u -> criar(u.id, tipo, texto, link));
    }

    public List<Dominio.Notificacao> listar(UUID usuarioId) {
        return repo.notificacoesDoUsuario(usuarioId);
    }

    public long naoLidas(UUID usuarioId) {
        return repo.naoLidas(usuarioId);
    }

    public void marcarLida(UUID usuarioId, UUID notificacaoId) {
        Dominio.Notificacao n = repo.notificacoes.get(notificacaoId);
        if (n == null || !n.usuarioId.equals(usuarioId)) {
            throw new Erros.PermissaoNegadaException("Notificacao de outro usuario.");
        }
        n.lida = true;
    }

    public void marcarTodas(UUID usuarioId) {
        repo.notificacoes.values().stream()
                .filter(n -> n.usuarioId.equals(usuarioId))
                .forEach(n -> n.lida = true);
    }

    public List<Dominio.Notificacao> ultimasParaMediacao() {
        return repo.notificacoes.values().stream()
                .filter(n -> {
                    Dominio.Usuario u = repo.usuarios.get(n.usuarioId);
                    return u != null && u.papel == Dominio.Papel.MEDIADOR;
                })
                .sorted(Comparator.comparing((Dominio.Notificacao n) -> n.criadaEm).reversed())
                .limit(10)
                .toList();
    }
}
