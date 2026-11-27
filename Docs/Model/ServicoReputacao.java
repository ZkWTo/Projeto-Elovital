package Model;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** ADR 0009 / 0011 / RF013: avaliacao mutua apos a dupla confirmacao. */
public final class ServicoReputacao {

    private final Repositorios repo;
    private final Relogio relogio;
    private final ServicoAuditoria auditoria;
    private final ServicoNotificacao notificacao;
    private final ServicoReservas reservas;

    public ServicoReputacao(Repositorios repo, Relogio relogio,
            ServicoAuditoria auditoria, ServicoNotificacao notificacao,
            ServicoReservas reservas) {
        this.repo = repo;
        this.relogio = relogio;
        this.auditoria = auditoria;
        this.notificacao = notificacao;
        this.reservas = reservas;
    }

    public Dominio.Avaliacao avaliar(UUID autorId, UUID reservaId, int nota, String comentario,
            String ipHash) {
        Dominio.Reserva r = reservas.exigir(reservaId);
        if (!reservas.ehParte(r, autorId)) {
            throw new Erros.PermissaoNegadaException("Apenas as partes avaliam.");
        }
        if (!r.concluida()) {
            throw new Erros.EstadoInvalidoException(r.status.name(), "avaliar");
        }
        if (nota < 1 || nota > 5) {
            throw new Erros.ValidacaoException("nota", "intervalo", "A nota vai de 1 a 5.");
        }
        boolean jaAvaliou = repo.avaliacoes.values().stream()
                .anyMatch(a -> a.reservaId.equals(reservaId) && a.autorId.equals(autorId));
        if (jaAvaliou) {
            throw new Erros.ConflitoException("avaliacao");
        }
        UUID alvo = autorId.equals(r.assistidoId) ? r.apoiadorId : r.assistidoId;
        Dominio.Avaliacao a = new Dominio.Avaliacao();
        a.reservaId = reservaId;
        a.autorId = autorId;
        a.alvoId = alvo;
        a.nota = nota;
        a.comentario = Texto.opcional(comentario, "comentario", Texto.COMENTARIO_AVALIACAO);
        a.criadaEm = relogio.agora();
        repo.avaliacoes.put(a.id, a);
        recalcularMedia(alvo);
        auditoria.registrar(autorId, "CRIAR_AVALIACAO", "avaliacao", a.id,
                null, "{\"nota\":" + nota + "}", ipHash);
        notificacao.criar(alvo, Dominio.TipoNotificacao.AVALIACAO,
                "Voce recebeu uma avaliacao.", "/perfil");
        return a;
    }

    private void recalcularMedia(UUID alvoId) {
        List<Dominio.Avaliacao> visiveis = repo.avaliacoesDoAlvo(alvoId);
        repo.perfilApoiadorPorUsuario(alvoId).ifPresent(p -> {
            p.totalAvaliacoes = visiveis.size();
            p.notaMedia = visiveis.isEmpty() ? 0
                    : Math.round(visiveis.stream().mapToInt(a -> a.nota).average().orElse(0) * 10.0) / 10.0;
        });
    }

    public List<Dominio.Avaliacao> daReserva(UUID reservaId) {
        return repo.avaliacoes.values().stream()
                .filter(a -> a.reservaId.equals(reservaId))
                .sorted(Comparator.comparing(a -> a.criadaEm))
                .toList();
    }

    /** Ocultar nao altera a nota nem apaga o historico (RF013/RF015). */
    public void ocultar(UUID mediadorId, UUID avaliacaoId, String motivo, String ipHash) {
        Dominio.Avaliacao a = repo.avaliacoes.get(avaliacaoId);
        if (a == null) {
            throw new Erros.NaoEncontradoException("avaliacao");
        }
        a.ocultoEm = relogio.agora();
        a.motivoOcultacao = Texto.exigir(motivo, "motivo", Texto.JUSTIFICATIVA);
        auditoria.registrar(mediadorId, "OCULTAR_AVALIACAO", "avaliacao", a.id,
                null, a.motivoOcultacao, ipHash);
        notificacao.criar(a.autorId, Dominio.TipoNotificacao.AVALIACAO,
                "Seu comentario de avaliacao foi ocultado pela mediacao.", "/perfil");
    }
}
