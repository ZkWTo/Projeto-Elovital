package Model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** ADR 0011 / 0018: agenda do apoiador, conflito de horario e publicacao. */
public final class ServicoAgenda {

    private final Repositorios repo;
    private final Relogio relogio;
    private final ServicoAuditoria auditoria;
    private final ServicoNotificacao notificacao;

    public ServicoAgenda(Repositorios repo, Relogio relogio,
            ServicoAuditoria auditoria, ServicoNotificacao notificacao) {
        this.repo = repo;
        this.relogio = relogio;
        this.auditoria = auditoria;
        this.notificacao = notificacao;
    }

    public Dominio.Horario publicar(UUID apoiadorId, LocalDate data, LocalTime inicio,
            LocalTime fim, Dominio.Modalidade modalidade, String observacoes, String ipHash) {
        Dominio.Usuario u = repo.usuario(apoiadorId).orElseThrow();
        if (!u.ativo() || u.papel != Dominio.Papel.APOIADOR) {
            throw new Erros.PermissaoNegadaException("Apenas Apoiador ativo publica agenda.");
        }
        Dominio.PerfilApoiador p = repo.perfilApoiadorPorUsuario(apoiadorId)
                .orElseThrow(() -> new Erros.ValidacaoException("perfil", "obrigatorio",
                        "Complete seu perfil de Apoiador."));
        if (data == null || inicio == null || fim == null) {
            throw new Erros.ValidacaoException("horario", "obrigatorio", "Informe data e horario.");
        }
        if (!fim.isAfter(inicio)) {
            throw new Erros.ValidacaoException("fim", "intervalo", "O fim deve ser depois do inicio.");
        }
        var inicioInstant = data.atTime(inicio).atZone(Relogio.FUSO_NEGOCIO).toInstant();
        if (!inicioInstant.isAfter(relogio.agora())) {
            throw new Erros.ValidacaoException("data", "passado", "Nao publique horario no passado.");
        }
        for (Dominio.Horario h : repo.horariosDoApoiador(apoiadorId)) {
            if (h.status == Dominio.StatusHorario.BLOQUEADO || h.data.equals(data)) {
                boolean sobrepoe = h.inicio.isBefore(fim) && inicio.isBefore(h.fim);
                if (h.data.equals(data) && sobrepoe) {
                    throw new Erros.ConflitoException("horario da agenda");
                }
            }
        }
        Dominio.Horario h = new Dominio.Horario();
        h.apoiadorId = apoiadorId;
        h.data = data;
        h.inicio = inicio;
        h.fim = fim;
        h.modalidade = modalidade == null ? p.modalidade : modalidade;
        h.observacoes = Texto.opcional(observacoes, "observacoes", Texto.OBSERVACAO_HORARIO);
        h.bairro = p.bairro;
        h.municipio = p.municipio;
        h.uf = p.uf;
        h.criadoEm = relogio.agora();
        repo.horarios.put(h.id, h);
        auditoria.registrar(apoiadorId, "PUBLICAR_HORARIO", "horario", h.id,
                null, data + " " + inicio + "-" + fim, ipHash);
        return h;
    }

    public void bloquear(UUID atorId, UUID horarioId, String motivo, String ipHash) {
        Dominio.Horario h = exigir(horarioId);
        Dominio.Usuario ator = repo.usuario(atorId).orElseThrow();
        boolean dono = h.apoiadorId.equals(atorId);
        boolean mediador = ator.papel == Dominio.Papel.MEDIADOR;
        if (!dono && !mediador) {
            throw new Erros.PermissaoNegadaException("Sem permissao para bloquear horario.");
        }
        String justificativa = Texto.exigir(motivo, "motivo", Texto.JUSTIFICATIVA);
        h.bloquear();
        auditoria.registrar(atorId, "BLOQUEAR_HORARIO", "horario", h.id,
                "DISPONIVEL", "BLOQUEADO: " + justificativa, ipHash);
        if (mediador && !dono) {
            notificacao.criar(h.apoiadorId, Dominio.TipoNotificacao.RESERVA,
                    "Um horario seu foi bloqueado pela mediacao.", "/agenda");
        }
    }

    public void liberar(UUID mediadorId, UUID horarioId, String ipHash) {
        Dominio.Horario h = exigir(horarioId);
        h.liberar();
        auditoria.registrar(mediadorId, "LIBERAR_HORARIO", "horario", h.id,
                "BLOQUEADO", "DISPONIVEL", ipHash);
        notificacao.criar(h.apoiadorId, Dominio.TipoNotificacao.RESERVA,
                "Seu horario foi liberado pela mediacao.", "/agenda");
    }

    public List<Dominio.Horario> agendaDoApoiador(UUID apoiadorId) {
        return repo.horariosDoApoiador(apoiadorId);
    }

    public List<Dominio.Horario> disponiveis() {
        return repo.horarios.values().stream()
                .filter(h -> h.status == Dominio.StatusHorario.DISPONIVEL)
                .filter(h -> h.futuro(relogio.agora()))
                .sorted(Comparator.comparing((Dominio.Horario h) -> h.data)
                        .thenComparing(h -> h.inicio))
                .toList();
    }

    public Dominio.Horario exigir(UUID id) {
        Dominio.Horario h = repo.horarios.get(id);
        if (h == null) {
            throw new Erros.NaoEncontradoException("horario");
        }
        return h;
    }
}
