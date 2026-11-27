package Model;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** ADR 0011 / 0023 / RF014 / RF015: denuncia, sancao, contestacao e ocultacao. */
public final class ServicoModeracao {

    private final Repositorios repo;
    private final Relogio relogio;
    private final ServicoAuditoria auditoria;
    private final ServicoNotificacao notificacao;
    private final ServicoContas contas;
    private final ServicoComunicacao comunicacao;
    private final ServicoReputacao reputacao;

    public ServicoModeracao(Repositorios repo, Relogio relogio,
            ServicoAuditoria auditoria, ServicoNotificacao notificacao,
            ServicoContas contas, ServicoComunicacao comunicacao,
            ServicoReputacao reputacao) {
        this.repo = repo;
        this.relogio = relogio;
        this.auditoria = auditoria;
        this.notificacao = notificacao;
        this.contas = contas;
        this.comunicacao = comunicacao;
        this.reputacao = reputacao;
    }

    public Dominio.Denuncia denunciar(UUID denuncianteId, UUID alvoId, UUID reservaId,
            String descricao, String ipHash) {
        if (denuncianteId.equals(alvoId)) {
            throw new Erros.ValidacaoException("alvo", "invalido", "Nao e possivel denunciar a si mesmo.");
        }
        repo.usuario(alvoId).orElseThrow(() -> new Erros.NaoEncontradoException("usuario"));
        Dominio.Denuncia d = new Dominio.Denuncia();
        d.denuncianteId = denuncianteId;
        d.alvoUsuarioId = alvoId;
        d.reservaId = reservaId;
        d.descricao = Texto.exigir(descricao, "descricao", Texto.DESCRICAO_DENUNCIA);
        d.criadaEm = relogio.agora();
        repo.denuncias.put(d.id, d);
        auditoria.registrar(denuncianteId, "REGISTRAR_DENUNCIA", "denuncia", d.id,
                null, null, ipHash);
        notificacao.paraMediacao(Dominio.TipoNotificacao.DENUNCIA,
                "Nova denuncia registrada.", "/mediacao/denuncias");
        avaliarSuspensaoPreventiva(alvoId);
        return d;
    }

    private void avaliarSuspensaoPreventiva(UUID alvoId) {
        Instant limite = relogio.agora().minus(Duration.ofDays(30));
        long denunciantes = repo.denuncias.values().stream()
                .filter(d -> d.alvoUsuarioId.equals(alvoId))
                .filter(d -> d.criadaEm.isAfter(limite))
                .map(d -> d.denuncianteId)
                .distinct()
                .count();
        if (denunciantes < 3) {
            return;
        }
        repo.usuario(alvoId).ifPresent(u -> {
            if (u.status == Dominio.StatusConta.ATIVA) {
                contas.suspenderConta(null, alvoId,
                        "Suspensao preventiva: 3 denunciantes distintos em 30 dias.", null);
                notificacao.paraMediacao(Dominio.TipoNotificacao.DENUNCIA,
                        "Suspensao preventiva aplicada automaticamente.", "/mediacao/contas");
            }
        });
    }

    public void decidirDenuncia(UUID mediadorId, UUID denunciaId, boolean procedente,
            String justificativa, String ipHash) {
        Dominio.Denuncia d = exigirDenuncia(denunciaId);
        d.decidir(procedente, mediadorId, relogio.agora());
        auditoria.registrar(mediadorId, "DECIDIR_DENUNCIA", "denuncia", d.id,
                "EM_ANALISE", d.status.name(), ipHash);
        notificacao.criar(d.denuncianteId, Dominio.TipoNotificacao.DENUNCIA,
                "Sua denuncia foi analisada: " + d.status.name() + ".", "/notificacoes");
        if (procedente) {
            notificacao.criar(d.alvoUsuarioId, Dominio.TipoNotificacao.DENUNCIA,
                    "Uma denuncia contra voce foi considerada procedente.", "/termos");
        }
    }

    public Dominio.Sancao aplicarSancao(UUID mediadorId, UUID usuarioId, Dominio.TipoSancao tipo,
            String motivo, int dias, UUID denunciaId, String ipHash) {
        String justificativa = Texto.exigir(motivo, "motivo", Texto.JUSTIFICATIVA);
        Dominio.Sancao s = new Dominio.Sancao();
        s.usuarioId = usuarioId;
        s.tipo = tipo;
        s.motivo = justificativa;
        s.inicio = relogio.agora();
        s.fim = dias > 0 ? s.inicio.plus(Duration.ofDays(dias)) : null;
        s.mediadorId = mediadorId;
        s.denunciaId = denunciaId;
        repo.sancoes.put(s.id, s);

        if (tipo == Dominio.TipoSancao.SUSPENSAO) {
            repo.usuario(usuarioId).ifPresent(u -> {
                if (u.status == Dominio.StatusConta.ATIVA) {
                    contas.suspenderConta(mediadorId, usuarioId, justificativa, ipHash);
                }
            });
        } else if (tipo == Dominio.TipoSancao.BLOQUEIO) {
            repo.usuario(usuarioId).ifPresent(u -> {
                if (u.status == Dominio.StatusConta.ATIVA || u.status == Dominio.StatusConta.SUSPENSA) {
                    u.bloquear(mediadorId, relogio.agora());
                    auditoria.registrar(mediadorId, "BLOQUEAR_CONTA", "usuario", u.id,
                            null, justificativa, ipHash);
                }
            });
        }
        auditoria.registrar(mediadorId, "APLICAR_SANCAO", "sancao", s.id,
                null, tipo.name() + ": " + justificativa, ipHash);
        notificacao.criar(usuarioId, Dominio.TipoNotificacao.DENUNCIA,
                "Uma sancao foi aplicada a sua conta: " + justificativa, "/termos");
        return s;
    }

    public Dominio.Contestacao contestar(UUID usuarioId, UUID sancaoId, String texto, String ipHash) {
        if (!repo.sancoes.containsKey(sancaoId)) {
            throw new Erros.NaoEncontradoException("sancao");
        }
        Dominio.Contestacao c = new Dominio.Contestacao();
        c.sancaoId = sancaoId;
        c.usuarioId = usuarioId;
        c.texto = Texto.exigir(texto, "texto", Texto.JUSTIFICATIVA);
        c.criadaEm = relogio.agora();
        repo.contestacoes.put(c.id, c);
        auditoria.registrar(usuarioId, "ABRIR_CONTESTACAO", "contestacao", c.id,
                null, null, ipHash);
        notificacao.paraMediacao(Dominio.TipoNotificacao.DENUNCIA,
                "Nova contestacao de sancao.", "/mediacao/denuncias");
        return c;
    }

    public void decidirContestacao(UUID mediadorId, UUID contestacaoId, boolean deferida,
            String justificativa, String ipHash) {
        Dominio.Contestacao c = repo.contestacoes.get(contestacaoId);
        if (c == null) {
            throw new Erros.NaoEncontradoException("contestacao");
        }
        if (c.status == Dominio.StatusContestacao.ABERTA) {
            c.iniciarAnalise();
        }
        c.decidir(deferida, mediadorId, relogio.agora());
        if (deferida) {
            Dominio.Sancao s = repo.sancoes.get(c.sancaoId);
            if (s != null && s.status == Dominio.StatusSancao.ATIVA) {
                s.revogar();
            }
            contas.reativarConta(mediadorId, c.usuarioId, ipHash);
        }
        auditoria.registrar(mediadorId, "DECIDIR_CONTESTACAO", "contestacao", c.id,
                "EM_ANALISE", c.status.name(), ipHash);
        notificacao.criar(c.usuarioId, Dominio.TipoNotificacao.DENUNCIA,
                "Sua contestacao foi " + (deferida ? "deferida." : "indeferida."), "/notificacoes");
    }

    public void ocultar(UUID mediadorId, String tipo, UUID id, String motivo, String ipHash) {
        switch (tipo) {
            case "mensagem" -> comunicacao.ocultar(mediadorId, id, motivo, ipHash);
            case "avaliacao" -> reputacao.ocultar(mediadorId, id, motivo, ipHash);
            case "solicitacao" -> {
                Dominio.Solicitacao s = repo.solicitacoes.get(id);
                if (s == null) {
                    throw new Erros.NaoEncontradoException("solicitacao");
                }
                s.ocultoEm = relogio.agora();
                s.motivoOcultacao = Texto.exigir(motivo, "motivo", Texto.JUSTIFICATIVA);
                auditoria.registrar(mediadorId, "OCULTAR_SOLICITACAO", "solicitacao", id,
                        null, s.motivoOcultacao, ipHash);
            }
            default -> throw new Erros.ValidacaoException("tipo", "invalido",
                    "Tipo de conteudo desconhecido.");
        }
    }

    public List<Dominio.Denuncia> denunciasEmAnalise() {
        return repo.denuncias.values().stream()
                .filter(d -> d.status == Dominio.StatusDenuncia.EM_ANALISE)
                .sorted(Comparator.comparing(d -> d.criadaEm))
                .toList();
    }

    public List<Dominio.Contestacao> contestacoesAbertas() {
        return repo.contestacoes.values().stream()
                .filter(c -> c.status != Dominio.StatusContestacao.DEFERIDA
                        && c.status != Dominio.StatusContestacao.INDEFERIDA)
                .sorted(Comparator.comparing(c -> c.criadaEm))
                .toList();
    }

    public Dominio.Denuncia exigirDenuncia(UUID id) {
        Dominio.Denuncia d = repo.denuncias.get(id);
        if (d == null) {
            throw new Erros.NaoEncontradoException("denuncia");
        }
        return d;
    }
}
