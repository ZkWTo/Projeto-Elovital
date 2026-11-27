package Model;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** ADR 0011 / RF005 / RF006: criacao e analise humana da solicitacao. */
public final class ServicoSolicitacoes {

    private final Repositorios repo;
    private final Relogio relogio;
    private final ServicoAuditoria auditoria;
    private final ServicoNotificacao notificacao;

    public ServicoSolicitacoes(Repositorios repo, Relogio relogio,
            ServicoAuditoria auditoria, ServicoNotificacao notificacao) {
        this.repo = repo;
        this.relogio = relogio;
        this.auditoria = auditoria;
        this.notificacao = notificacao;
    }

    public Dominio.Solicitacao criar(UUID assistidoId, String titulo, String descricao,
            String observacaoCuidado, Dominio.Prioridade prioridade, String tipoApoio,
            String cep, String bairro, String municipio, String uf, double raioKm, String ipHash) {
        Dominio.Usuario u = repo.usuario(assistidoId).orElseThrow();
        if (!u.ativo() || u.papel != Dominio.Papel.ASSISTIDO) {
            throw new Erros.PermissaoNegadaException("Apenas Assistido ativo cria solicitacao.");
        }
        if (repo.perfilAssistidoPorUsuario(assistidoId).isEmpty()) {
            throw new Erros.ValidacaoException("perfil", "obrigatorio",
                    "Complete seu perfil de Assistido antes de solicitar apoio.");
        }
        long ativas = repo.solicitacoesDoAssistido(assistidoId).stream()
                .filter(s -> s.status == Dominio.StatusSolicitacao.EM_ANALISE
                        || s.status == Dominio.StatusSolicitacao.ABERTA
                        || s.status == Dominio.StatusSolicitacao.AGUARDANDO_AJUSTE
                        || s.status == Dominio.StatusSolicitacao.RESERVADA)
                .count();
        int limite = repo.parametroInt("solicitacoes_ativas_max", 3);
        if (ativas >= limite) {
            throw new Erros.LimiteExcedidoException("solicitacoes ativas", String.valueOf(limite));
        }

        String tituloFinal = Texto.exigir(titulo, "titulo", Texto.TITULO_SOLICITACAO);
        String descricaoFinal = Texto.exigir(descricao, "descricao", Texto.DESCRICAO_SOLICITACAO);
        String obsFinal = Texto.opcional(observacaoCuidado, "observacao", Texto.OBSERVACAO_CUIDADO);

        Texto.Deteccao deteccao = Texto.detectar(tituloFinal + "\n" + descricaoFinal + "\n" + obsFinal, true);
        if (deteccao.bloqueado()) {
            repo.pedidosBloqueados.incrementAndGet();
            auditoria.registrar(assistidoId, "CONTEUDO_BLOQUEADO", "solicitacao", null,
                    null, "{\"termos\":\"" + String.join(",", deteccao.termos()) + "\"}", ipHash);
            notificacao.paraMediacao(Dominio.TipoNotificacao.DENUNCIA,
                    "Solicitacao bloqueada pelo detector de conteudo.", "/mediacao/solicitacoes");
            throw new Erros.ValidacaoException("descricao", "conteudo_proibido",
                    "O texto contem pedido de dinheiro ou dado de contato. Ajuste antes de enviar.");
        }

        Dominio.Solicitacao s = new Dominio.Solicitacao();
        s.assistidoId = assistidoId;
        s.titulo = tituloFinal;
        s.descricao = descricaoFinal;
        s.observacaoCuidado = obsFinal;
        s.prioridade = prioridade == null ? Dominio.Prioridade.MEDIA : prioridade;
        s.tipoApoio = Texto.opcional(tipoApoio, "tipoApoio", 60);
        s.cep = Texto.opcional(cep, "cep", 9);
        s.bairro = Texto.opcional(bairro, "bairro", 80);
        s.municipio = Texto.opcional(municipio, "municipio", 80);
        s.uf = Texto.opcional(uf, "uf", 2).toUpperCase();
        s.raioKm = raioKm <= 0 ? 10 : raioKm;
        s.criadaEm = relogio.agora();
        s.atualizadaEm = s.criadaEm;
        s.expiraEm = s.criadaEm.plus(Duration.ofDays(repo.parametroInt("solicitacao_validade_dias", 7)));
        s.historico.add(new Dominio.Transicao(null, "EM_ANALISE", assistidoId,
                "solicitacao criada", s.criadaEm));
        repo.solicitacoes.put(s.id, s);

        if (deteccao.suspeito()) {
            notificacao.criar(assistidoId, Dominio.TipoNotificacao.SOLICITACAO,
                    "Atencao: o texto contem dado de contato. Evite compartilhar telefone.", "/solicitacoes");
        }
        notificacao.paraMediacao(Dominio.TipoNotificacao.SOLICITACAO,
                "Nova solicitacao aguardando analise: " + s.titulo, "/mediacao/solicitacoes");
        auditoria.registrar(assistidoId, "CRIAR_SOLICITACAO", "solicitacao", s.id,
                null, "{\"titulo\":\"" + s.titulo + "\"}", ipHash);
        return s;
    }

    public void reenviar(UUID atorId, UUID solicitacaoId, String titulo, String descricao,
            String observacao, String ipHash) {
        Dominio.Solicitacao s = exigir(solicitacaoId);
        if (!s.podeReenviar(atorId)) {
            throw new Erros.EstadoInvalidoException(s.status.name(), "reenviar");
        }
        Texto.Deteccao d = Texto.detectar(titulo + "\n" + descricao + "\n" + observacao, true);
        if (d.bloqueado()) {
            throw new Erros.ValidacaoException("descricao", "conteudo_proibido",
                    "O texto contem pedido de dinheiro ou dado de contato.");
        }
        s.titulo = Texto.exigir(titulo, "titulo", Texto.TITULO_SOLICITACAO);
        s.descricao = Texto.exigir(descricao, "descricao", Texto.DESCRICAO_SOLICITACAO);
        s.observacaoCuidado = Texto.opcional(observacao, "observacao", Texto.OBSERVACAO_CUIDADO);
        s.reenviar(relogio.agora());
        auditoria.registrar(atorId, "REENVIAR_SOLICITACAO", "solicitacao", s.id,
                "AGUARDANDO_AJUSTE", "EM_ANALISE", ipHash);
        notificacao.paraMediacao(Dominio.TipoNotificacao.SOLICITACAO,
                "Solicitacao reenviada: " + s.titulo, "/mediacao/solicitacoes");
    }

    public void aprovar(UUID mediadorId, UUID solicitacaoId, Dominio.Prioridade prioridade,
            String observacao, String ipHash) {
        Dominio.Solicitacao s = exigir(solicitacaoId);
        if (s.status != Dominio.StatusSolicitacao.EM_ANALISE) {
            throw new Erros.EstadoInvalidoException(s.status.name(), "aprovar");
        }
        s.aprovar(mediadorId, prioridade == null ? s.prioridade : prioridade,
                Texto.opcional(observacao, "observacao", Texto.OBSERVACAO_INTERNA), relogio.agora());
        auditoria.registrar(mediadorId, "APROVAR_SOLICITACAO", "solicitacao", s.id,
                "EM_ANALISE", "ABERTA", ipHash);
        notificacao.criar(s.assistidoId, Dominio.TipoNotificacao.SOLICITACAO,
                "Sua solicitacao foi aprovada e esta visivel para apoiadores.", "/solicitacoes");
    }

    public void solicitarAjuste(UUID mediadorId, UUID solicitacaoId, String justificativa, String ipHash) {
        Dominio.Solicitacao s = exigir(solicitacaoId);
        String motivo = Texto.exigir(justificativa, "justificativa", Texto.JUSTIFICATIVA);
        s.solicitarAjuste(mediadorId, motivo, relogio.agora());
        auditoria.registrar(mediadorId, "SOLICITAR_AJUSTE", "solicitacao", s.id,
                "EM_ANALISE", "AGUARDANDO_AJUSTE: " + motivo, ipHash);
        notificacao.criar(s.assistidoId, Dominio.TipoNotificacao.SOLICITACAO,
                "A mediacao pediu um ajuste: " + motivo, "/solicitacoes");
    }

    public void recusar(UUID mediadorId, UUID solicitacaoId, String justificativa, String ipHash) {
        Dominio.Solicitacao s = exigir(solicitacaoId);
        String motivo = Texto.exigir(justificativa, "justificativa", Texto.JUSTIFICATIVA);
        s.recusar(mediadorId, motivo, relogio.agora());
        auditoria.registrar(mediadorId, "RECUSAR_SOLICITACAO", "solicitacao", s.id,
                "EM_ANALISE", "RECUSADA: " + motivo, ipHash);
        notificacao.criar(s.assistidoId, Dominio.TipoNotificacao.SOLICITACAO,
                "Sua solicitacao foi recusada. Motivo: " + motivo, "/solicitacoes");
    }

    public void cancelar(UUID atorId, UUID solicitacaoId, String motivo, String ipHash) {
        Dominio.Solicitacao s = exigir(solicitacaoId);
        s.cancelar(atorId, Texto.opcional(motivo, "motivo", Texto.JUSTIFICATIVA), relogio.agora());
        auditoria.registrar(atorId, "CANCELAR_SOLICITACAO", "solicitacao", s.id,
                null, "CANCELADA", ipHash);
        notificacao.criar(s.assistidoId, Dominio.TipoNotificacao.SOLICITACAO,
                "Solicitacao cancelada.", "/solicitacoes");
    }

    public void alterarRelevante(UUID atorId, UUID solicitacaoId, String titulo, String descricao,
            Dominio.Prioridade prioridade, String ipHash) {
        Dominio.Solicitacao s = exigir(solicitacaoId);
        if (!s.assistidoId.equals(atorId) || s.status != Dominio.StatusSolicitacao.ABERTA) {
            throw new Erros.EstadoInvalidoException(s.status.name(), "alterar");
        }
        s.titulo = Texto.exigir(titulo, "titulo", Texto.TITULO_SOLICITACAO);
        s.descricao = Texto.exigir(descricao, "descricao", Texto.DESCRICAO_SOLICITACAO);
        if (prioridade != null) {
            s.prioridade = prioridade;
        }
        s.alterarRelevante(relogio.agora());
        auditoria.registrar(atorId, "ALTERAR_SOLICITACAO", "solicitacao", s.id,
                "ABERTA", "EM_ANALISE", ipHash);
        notificacao.paraMediacao(Dominio.TipoNotificacao.SOLICITACAO,
                "Solicitacao alterada e precisa de nova analise.", "/mediacao/solicitacoes");
    }

    public int expirarVencidas() {
        int total = 0;
        for (Dominio.Solicitacao s : repo.solicitacoes.values()) {
            if (s.status == Dominio.StatusSolicitacao.ABERTA && s.expiraEm.isBefore(relogio.agora())) {
                s.expirar(relogio.agora());
                notificacao.criar(s.assistidoId, Dominio.TipoNotificacao.SOLICITACAO,
                        "Sua solicitacao expirou.", "/solicitacoes");
                total++;
            }
        }
        return total;
    }

    public List<Dominio.Solicitacao> filaAnalise() {
        return repo.solicitacoes.values().stream()
                .filter(s -> s.status == Dominio.StatusSolicitacao.EM_ANALISE
                        || s.status == Dominio.StatusSolicitacao.AGUARDANDO_AJUSTE)
                .sorted(Comparator.comparing((Dominio.Solicitacao s) -> s.criadaEm))
                .toList();
    }

    public List<Dominio.Solicitacao> publicas() {
        return repo.solicitacoes.values().stream()
                .filter(s -> s.status == Dominio.StatusSolicitacao.ABERTA)
                .filter(s -> s.ocultoEm == null)
                .sorted(Comparator.comparing((Dominio.Solicitacao s) -> s.prioridade).reversed()
                        .thenComparing(s -> s.criadaEm))
                .toList();
    }

    public Dominio.Solicitacao exigir(UUID id) {
        Dominio.Solicitacao s = repo.solicitacoes.get(id);
        if (s == null) {
            throw new Erros.NaoEncontradoException("solicitacao");
        }
        return s;
    }
}
