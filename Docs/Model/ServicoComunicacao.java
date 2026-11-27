package Model;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** ADR 0009 / 0010 / 0015: chat interno, alerta de conteudo e canal SSE. */
public final class ServicoComunicacao {

    private final Repositorios repo;
    private final Relogio relogio;
    private final ServicoAuditoria auditoria;
    private final ServicoNotificacao notificacao;
    private final ServicoReservas reservas;
    private final Eventos eventos;

    public ServicoComunicacao(Repositorios repo, Relogio relogio,
            ServicoAuditoria auditoria, ServicoNotificacao notificacao,
            ServicoReservas reservas) {
        this.repo = repo;
        this.relogio = relogio;
        this.auditoria = auditoria;
        this.notificacao = notificacao;
        this.reservas = reservas;
        this.eventos = new Eventos();
    }

    public record Envio(Dominio.Mensagem mensagem, Texto.Deteccao deteccao) {}

    public Envio enviar(UUID autorId, UUID reservaId, String texto, String ipHash) {
        Dominio.Reserva r = reservas.exigir(reservaId);
        if (!reservas.ehParte(r, autorId)) {
            throw new Erros.PermissaoNegadaException("Apenas as partes participam do chat.");
        }
        if (!r.ativa() && !r.concluida()) {
            throw new Erros.EstadoInvalidoException(r.status.name(), "enviar mensagem");
        }
        String conteudo = Texto.exigir(texto, "mensagem", Texto.MENSAGEM_CHAT);
        Texto.Deteccao deteccao = Texto.detectar(conteudo, false);
        if (deteccao.bloqueado()) {
            repo.pedidosBloqueados.incrementAndGet();
            auditoria.registrar(autorId, "MENSAGEM_BLOQUEADA", "mensagem", null,
                    null, "{\"termos\":\"" + String.join(",", deteccao.termos()) + "\"}", ipHash);
            throw new Erros.ValidacaoException("mensagem", "conteudo_proibido",
                    "Nao envie pedido de dinheiro ou dado bancario pelo chat.");
        }
        Dominio.Mensagem m = new Dominio.Mensagem();
        m.reservaId = reservaId;
        m.autorId = autorId;
        m.texto = conteudo;
        m.criadaEm = relogio.agora();
        repo.mensagens.put(m.id, m);

        UUID destino = autorId.equals(r.assistidoId) ? r.apoiadorId : r.assistidoId;
        notificacao.criar(destino, Dominio.TipoNotificacao.MENSAGEM,
                "Nova mensagem no chat da reserva.", "/reservas/" + reservaId + "/chat");
        eventos.publicar(reservaId, "mensagem");
        eventos.publicar(destino, "notificacao");
        auditoria.registrar(autorId, "ENVIAR_MENSAGEM", "mensagem", m.id, null, null, ipHash);
        return new Envio(m, deteccao);
    }

    public List<Dominio.Mensagem> listar(UUID atorId, UUID reservaId, boolean mediador) {
        Dominio.Reserva r = reservas.exigir(reservaId);
        if (!mediador && !reservas.ehParte(r, atorId)) {
            throw new Erros.PermissaoNegadaException("Sem acesso ao chat.");
        }
        return repo.mensagensDaReserva(reservaId);
    }

    public void ocultar(UUID mediadorId, UUID mensagemId, String motivo, String ipHash) {
        Dominio.Mensagem m = repo.mensagens.get(mensagemId);
        if (m == null) {
            throw new Erros.NaoEncontradoException("mensagem");
        }
        m.ocultoEm = relogio.agora();
        m.motivoOcultacao = Texto.exigir(motivo, "motivo", Texto.JUSTIFICATIVA);
        auditoria.registrar(mediadorId, "OCULTAR_MENSAGEM", "mensagem", m.id,
                null, m.motivoOcultacao, ipHash);
    }

    public Eventos eventos() {
        return eventos;
    }

    /** Pub/sub minimo: o SSE e entrega, nunca fonte de verdade. */
    public static final class Eventos {
        private final Map<UUID, CopyOnWriteArrayList<Consumer<String>>> inscritos =
                new ConcurrentHashMap<>();

        public Runnable inscrever(UUID chave, Consumer<String> consumidor) {
            CopyOnWriteArrayList<Consumer<String>> lista =
                    inscritos.computeIfAbsent(chave, k -> new CopyOnWriteArrayList<>());
            lista.add(consumidor);
            return () -> lista.remove(consumidor);
        }

        public void publicar(UUID chave, String evento) {
            CopyOnWriteArrayList<Consumer<String>> lista = inscritos.get(chave);
            if (lista == null) {
                return;
            }
            for (Consumer<String> c : lista) {
                try {
                    c.accept(evento);
                } catch (RuntimeException ignorada) {
                    // cliente desconectado nao derruba a operacao principal
                }
            }
        }

        public int conexoes(UUID chave) {
            CopyOnWriteArrayList<Consumer<String>> lista = inscritos.get(chave);
            return lista == null ? 0 : lista.size();
        }
    }
}
