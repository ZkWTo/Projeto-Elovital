package Model;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * ADR 0011 / 0012 / RF008 / RF009 / RF012.
 * A reserva e a unica operacao que exige atomicidade forte: o metodo
 * sincronizado + a checagem de reserva nao cancelada simulam a constraint
 * unica parcial e o SELECT ... FOR UPDATE do banco.
 */
public final class ServicoReservas {

    private final Repositorios repo;
    private final Relogio relogio;
    private final ServicoAuditoria auditoria;
    private final ServicoNotificacao notificacao;

    public ServicoReservas(Repositorios repo, Relogio relogio,
            ServicoAuditoria auditoria, ServicoNotificacao notificacao) {
        this.repo = repo;
        this.relogio = relogio;
        this.auditoria = auditoria;
        this.notificacao = notificacao;
    }

    public synchronized Dominio.Reserva reservar(UUID assistidoId, UUID solicitacaoId,
            UUID horarioId, String ipHash) {
        Dominio.Solicitacao s = repo.solicitacoes.get(solicitacaoId);
        if (s == null) {
            throw new Erros.NaoEncontradoException("solicitacao");
        }
        if (!s.assistidoId.equals(assistidoId)) {
            throw new Erros.PermissaoNegadaException("Solicitacao de outro Assistido.");
        }
        if (s.status != Dominio.StatusSolicitacao.ABERTA) {
            throw new Erros.EstadoInvalidoException(s.status.name(), "reservar");
        }
        Dominio.Horario h = repo.horarios.get(horarioId);
        if (h == null) {
            throw new Erros.NaoEncontradoException("horario");
        }
        if (h.status != Dominio.StatusHorario.DISPONIVEL) {
            throw new Erros.ConflitoException("horario");
        }
        if (repo.reservaNaoCanceladaDoHorario(horarioId).isPresent()) {
            throw new Erros.ConflitoException("horario");
        }
        if (!h.futuro(relogio.agora())) {
            throw new Erros.PrazoExpiradoException("horario");
        }
        Dominio.Usuario apoiador = repo.usuario(h.apoiadorId).orElseThrow();
        if (!apoiador.ativo()) {
            throw new Erros.ConflitoException("apoiador");
        }

        Dominio.Reserva r = new Dominio.Reserva();
        r.solicitacaoId = s.id;
        r.horarioId = h.id;
        r.assistidoId = assistidoId;
        r.apoiadorId = h.apoiadorId;
        r.criadaEm = relogio.agora();
        repo.reservas.put(r.id, r);

        h.reservar();
        s.marcarReservada(relogio.agora());
        repo.reservasCriadas.incrementAndGet();

        auditoria.registrar(assistidoId, "CRIAR_RESERVA", "reserva", r.id,
                null, "{\"horario\":\"" + h.id + "\"}", ipHash);
        notificacao.criar(r.apoiadorId, Dominio.TipoNotificacao.RESERVA,
                "Nova reserva de apoio para " + h.data + " as " + h.inicio + ".", "/reservas/" + r.id);
        notificacao.criar(r.assistidoId, Dominio.TipoNotificacao.RESERVA,
                "Reserva criada. Registre ciencia em ate 24 horas.", "/reservas/" + r.id);
        notificacao.paraMediacao(Dominio.TipoNotificacao.RESERVA,
                "Nova reserva criada.", "/mediacao");
        return r;
    }

    public void registrarCiencia(UUID atorId, UUID reservaId, String ipHash) {
        Dominio.Reserva r = exigir(reservaId);
        r.registrarCiencia(atorId, relogio.agora());
        auditoria.registrar(atorId, "CIENCIA_RESERVA", "reserva", r.id,
                "CIENCIA_PENDENTE", "ATIVA", ipHash);
    }

    public void cancelar(UUID atorId, UUID reservaId, String motivo, String ipHash) {
        Dominio.Reserva r = exigir(reservaId);
        if (!ehParte(r, atorId)) {
            throw new Erros.PermissaoNegadaException("Apenas as partes cancelam a reserva.");
        }
        Dominio.Horario h = repo.horarios.get(r.horarioId);
        boolean antecedencia = antecedenciaSuficiente(h);
        String motivoFinal = Texto.opcional(motivo, "motivo", Texto.JUSTIFICATIVA);
        r.cancelar(antecedencia, motivoFinal, relogio.agora());
        Dominio.Solicitacao s = repo.solicitacoes.get(r.solicitacaoId);

        if (antecedencia) {
            h.liberar();
            if (s != null && s.status == Dominio.StatusSolicitacao.RESERVADA) {
                s.reabrir(relogio.agora());
            }
        } else {
            h.bloquear();
            notificacao.paraMediacao(Dominio.TipoNotificacao.RESERVA,
                    "Cancelamento com menos de 24h: reserva em analise.", "/mediacao");
        }
        auditoria.registrar(atorId, antecedencia ? "CANCELAR_RESERVA" : "CANCELAR_RESERVA_TARDIO",
                "reserva", r.id, "ATIVA", r.status.name() + ": " + motivoFinal, ipHash);
        notificacao.criar(outraParte(r, atorId), Dominio.TipoNotificacao.RESERVA,
                "A reserva foi cancelada. Motivo: " + motivoFinal, "/reservas/" + r.id);
    }

    public void registrarRealizacao(UUID atorId, UUID reservaId, String ipHash) {
        Dominio.Reserva r = exigir(reservaId);
        if (!ehParte(r, atorId)) {
            throw new Erros.PermissaoNegadaException("Apenas as partes registram realizacao.");
        }
        r.registrarRealizacao(relogio.agora());
        Dominio.Solicitacao s = repo.solicitacoes.get(r.solicitacaoId);
        if (s != null) {
            s.registrarRealizacao(relogio.agora());
        }
        auditoria.registrar(atorId, "REGISTRAR_REALIZACAO", "reserva", r.id,
                "ATIVA", "AGUARDANDO_CONFIRMACAO", ipHash);
        notificacao.criar(outraParte(r, atorId), Dominio.TipoNotificacao.RESERVA,
                "A realizacao foi registrada. Confirme a conclusao.", "/reservas/" + r.id);
    }

    public void confirmar(UUID atorId, UUID reservaId, String ipHash) {
        Dominio.Reserva r = exigir(reservaId);
        if (!ehParte(r, atorId)) {
            throw new Erros.PermissaoNegadaException("Apenas as partes confirmam.");
        }
        r.confirmar(atorId, relogio.agora());
        if (r.concluida()) {
            Dominio.Horario h = repo.horarios.get(r.horarioId);
            if (h != null) {
                h.realizar();
            }
            Dominio.Solicitacao s = repo.solicitacoes.get(r.solicitacaoId);
            if (s != null) {
                s.concluir(relogio.agora());
            }
            notificacao.criar(r.assistidoId, Dominio.TipoNotificacao.AVALIACAO,
                    "Apoio concluido. Avalie a experiencia.", "/reservas/" + r.id);
            notificacao.criar(r.apoiadorId, Dominio.TipoNotificacao.AVALIACAO,
                    "Apoio concluido. Avalie a experiencia.", "/reservas/" + r.id);
        }
        auditoria.registrar(atorId, "CONFIRMAR_CONCLUSAO", "reserva", r.id,
                null, r.status.name(), ipHash);
    }

    public void discordar(UUID atorId, UUID reservaId, String ocorrencia, String ipHash) {
        Dominio.Reserva r = exigir(reservaId);
        if (!ehParte(r, atorId)) {
            throw new Erros.PermissaoNegadaException("Apenas as partes discordam.");
        }
        r.discordar(Texto.exigir(ocorrencia, "ocorrencia", Texto.JUSTIFICATIVA));
        Dominio.Ocorrencia o = new Dominio.Ocorrencia();
        o.reservaId = r.id;
        o.registradaPor = atorId;
        o.descricao = r.ocorrencia;
        o.criadaEm = relogio.agora();
        repo.ocorrencias.put(o.id, o);
        auditoria.registrar(atorId, "DISCORDAR_CONCLUSAO", "reserva", r.id,
                "AGUARDANDO_CONFIRMACAO", "EM_ANALISE_MEDIACAO", ipHash);
        notificacao.paraMediacao(Dominio.TipoNotificacao.RESERVA,
                "Reserva com discordancia na conclusao.", "/mediacao");
    }

    public void registrarFalta(UUID atorId, UUID reservaId, String ipHash) {
        Dominio.Reserva r = exigir(reservaId);
        if (!ehParte(r, atorId)) {
            throw new Erros.PermissaoNegadaException("Apenas as partes registram falta.");
        }
        r.registrarFalta();
        auditoria.registrar(atorId, "REGISTRAR_FALTA", "reserva", r.id,
                "ATIVA", "FALTA_REGISTRADA", ipHash);
        notificacao.paraMediacao(Dominio.TipoNotificacao.RESERVA,
                "Falta registrada em reserva.", "/mediacao");
    }

    public synchronized void remarcar(UUID atorId, UUID reservaId, UUID novoHorarioId, String ipHash) {
        Dominio.Reserva antiga = exigir(reservaId);
        if (!ehParte(antiga, atorId)) {
            throw new Erros.PermissaoNegadaException("Apenas as partes remarcam.");
        }
        Dominio.Horario novo = repo.horarios.get(novoHorarioId);
        if (novo == null || novo.status != Dominio.StatusHorario.DISPONIVEL) {
            throw new Erros.ConflitoException("horario");
        }
        if (repo.reservaNaoCanceladaDoHorario(novoHorarioId).isPresent()) {
            throw new Erros.ConflitoException("horario");
        }
        Dominio.Reserva nova = new Dominio.Reserva();
        nova.solicitacaoId = antiga.solicitacaoId;
        nova.horarioId = novo.id;
        nova.assistidoId = antiga.assistidoId;
        nova.apoiadorId = novo.apoiadorId;
        nova.criadaEm = relogio.agora();
        repo.reservas.put(nova.id, nova);
        novo.reservar();
        antiga.remarcar(nova.id);
        auditoria.registrar(atorId, "REMARCAR_RESERVA", "reserva", antiga.id,
                "ATIVA", "REMARCADA -> " + nova.id, ipHash);
        notificacao.criar(outraParte(antiga, atorId), Dominio.TipoNotificacao.RESERVA,
                "A reserva foi remarcada para " + novo.data + " as " + novo.inicio + ".",
                "/reservas/" + nova.id);
    }

    /** Rotina de 72h: conclui com uma confirmacao e libera avaliacao. */
    public int expirarConfirmacoesPendentes() {
        int total = 0;
        Instant limite = relogio.agora().minus(
                Duration.ofHours(repo.parametroInt("conclusao_prazo_h", 72)));
        for (Dominio.Reserva r : repo.reservas.values()) {
            if (r.status != Dominio.StatusReserva.AGUARDANDO_CONFIRMACAO) {
                continue;
            }
            Instant referencia = r.confirmacaoAssistidoEm != null ? r.confirmacaoAssistidoEm
                    : r.confirmacaoApoiadorEm;
            if (referencia != null && referencia.isBefore(limite)) {
                r.expirarConfirmacao(relogio.agora());
                Dominio.Solicitacao s = repo.solicitacoes.get(r.solicitacaoId);
                if (s != null) {
                    s.concluirUmaConfirmacao(relogio.agora());
                }
                notificacao.criar(r.assistidoId, Dominio.TipoNotificacao.RESERVA,
                        "Conclusao registrada com uma confirmacao apos 72h.", "/reservas/" + r.id);
                notificacao.criar(r.apoiadorId, Dominio.TipoNotificacao.RESERVA,
                        "Conclusao registrada com uma confirmacao apos 72h.", "/reservas/" + r.id);
                total++;
            }
        }
        return total;
    }

    /** Decisao da mediacao sobre reserva em EM_ANALISE_MEDIACAO. */
    public void decidirMediacao(UUID mediadorId, UUID reservaId, boolean cancelar,
            String motivo, String ipHash) {
        Dominio.Reserva r = exigir(reservaId);
        if (r.status != Dominio.StatusReserva.EM_ANALISE_MEDIACAO) {
            throw new Erros.EstadoInvalidoException(r.status.name(), "decidir reserva");
        }
        String justificativa = Texto.opcional(motivo, "motivo", Texto.JUSTIFICATIVA);
        Dominio.Horario h = repo.horarios.get(r.horarioId);
        Dominio.Solicitacao s = repo.solicitacoes.get(r.solicitacaoId);
        if (cancelar) {
            r.status = Dominio.StatusReserva.CANCELADA;
            r.canceladaEm = relogio.agora();
            r.motivoCancelamento = justificativa;
            if (h != null) {
                h.liberar();
            }
            if (s != null) {
                s.reabrir(relogio.agora());
            }
        } else {
            r.status = Dominio.StatusReserva.CONCLUIDA;
            if (h != null && h.status == Dominio.StatusHorario.RESERVADO) {
                h.realizar();
            }
            if (s != null) {
                s.concluir(relogio.agora());
            }
        }
        auditoria.registrar(mediadorId, "DECIDIR_RESERVA", "reserva", r.id,
                "EM_ANALISE_MEDIACAO", r.status.name() + ": " + justificativa, ipHash);
        notificacao.criar(r.assistidoId, Dominio.TipoNotificacao.RESERVA,
                "A mediacao decidiu sobre a reserva: " + r.status.name(), "/reservas/" + r.id);
        notificacao.criar(r.apoiadorId, Dominio.TipoNotificacao.RESERVA,
                "A mediacao decidiu sobre a reserva: " + r.status.name(), "/reservas/" + r.id);
    }

    public List<Dominio.Reserva> doUsuario(UUID usuarioId) {
        return repo.reservas.values().stream()
                .filter(r -> r.assistidoId.equals(usuarioId) || r.apoiadorId.equals(usuarioId))
                .sorted(Comparator.comparing((Dominio.Reserva r) -> r.criadaEm).reversed())
                .toList();
    }

    public boolean ehParte(Dominio.Reserva r, UUID usuarioId) {
        return r.assistidoId.equals(usuarioId) || r.apoiadorId.equals(usuarioId);
    }

    public Dominio.Reserva exigir(UUID id) {
        Dominio.Reserva r = repo.reservas.get(id);
        if (r == null) {
            throw new Erros.NaoEncontradoException("reserva");
        }
        return r;
    }

    public boolean antecedenciaSuficiente(Dominio.Horario h) {
        if (h == null) {
            return false;
        }
        Instant inicio = h.data.atTime(h.inicio).atZone(Relogio.FUSO_NEGOCIO).toInstant();
        Instant limite = relogio.agora().plus(
                Duration.ofHours(repo.parametroInt("cancelamento_antecedencia_h", 24)));
        return !inicio.isBefore(limite);
    }

    private UUID outraParte(Dominio.Reserva r, UUID atorId) {
        return atorId.equals(r.assistidoId) ? r.apoiadorId : r.assistidoId;
    }
}
