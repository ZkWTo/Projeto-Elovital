package Model;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

/** ADR 0016: rotinas agendadas, expiracao, cumprimento de sancao e expurgo. */
public final class ServicoRotinas {

    private final Repositorios repo;
    private final Relogio relogio;
    private final ServicoSolicitacoes solicitacoes;
    private final ServicoReservas reservas;
    private final ServicoContas contas;
    private final ServicoAuditoria auditoria;

    private Instant ultimaExecucao;
    private Resultado ultimoResultado = new Resultado(0, 0, 0, 0, 0, 0);

    public ServicoRotinas(Repositorios repo, Relogio relogio,
            ServicoSolicitacoes solicitacoes, ServicoReservas reservas,
            ServicoContas contas, ServicoAuditoria auditoria) {
        this.repo = repo;
        this.relogio = relogio;
        this.solicitacoes = solicitacoes;
        this.reservas = reservas;
        this.contas = contas;
        this.auditoria = auditoria;
    }

    public record Resultado(int solicitacoesExpiradas, int conclusoesExpiradas,
            int documentosExpurgados, int sancoesCumpridas,
            int tentativasLimpas, int tokensLimpios) {}

    public synchronized Resultado executar() {
        int expiradas = solicitacoes.expirarVencidas();
        int conclusoes = reservas.expirarConfirmacoesPendentes();
        int documentos = expurgarDocumentos();
        int sancoes = cumprirSancoes();
        int tentativas = limparTentativas();
        int tokens = limparTokens();
        ultimaExecucao = relogio.agora();
        ultimoResultado = new Resultado(expiradas, conclusoes, documentos,
                sancoes, tentativas, tokens);
        auditoria.registrar(null, "ROTINA_EXECUTADA", "sistema", null, null,
                ultimoResultado.toString(), null);
        return ultimoResultado;
    }

    private int expurgarDocumentos() {
        int dias = repo.parametroInt("documento_retencao_dias", 30);
        Instant limite = relogio.agora().minus(Duration.ofDays(dias));
        int total = 0;
        for (Dominio.DocumentoVerificacao d : repo.documentos.values()) {
            if (d.status != Dominio.StatusDocumento.EXPURGADO
                    && d.enviadoEm.isBefore(limite)) {
                d.caminhoArquivo = null;
                d.status = Dominio.StatusDocumento.EXPURGADO;
                d.expurgadoEm = relogio.agora();
                total++;
            }
        }
        return total;
    }

    private int cumprirSancoes() {
        int total = 0;
        for (Dominio.Sancao s : repo.sancoes.values()) {
            if (s.status == Dominio.StatusSancao.ATIVA && s.fim != null
                    && s.fim.isBefore(relogio.agora())) {
                s.cumprir();
                repo.usuario(s.usuarioId).ifPresent(u -> {
                    if (u.status == Dominio.StatusConta.SUSPENSA) {
                        contas.reativarConta(null, u.id, null);
                    }
                });
                total++;
            }
        }
        return total;
    }

    private int limparTentativas() {
        Instant limite = relogio.agora().minus(Duration.ofHours(24));
        int antes = repo.tentativasLogin.size();
        repo.tentativasLogin.values().removeIf(t -> t.criadaEm.isBefore(limite));
        return antes - repo.tentativasLogin.size();
    }

    private int limparTokens() {
        Instant limite = relogio.agora().minus(Duration.ofDays(1));
        int antes = repo.tokensRecuperacao.size();
        repo.tokensRecuperacao.values().removeIf(t -> t.expiraEm.isBefore(limite));
        return antes - repo.tokensRecuperacao.size();
    }

    public Resultado ultimoResultado() {
        return ultimoResultado;
    }

    public Instant ultimaExecucao() {
        return ultimaExecucao;
    }

    public String resumo() {
        return "Solicitacoes expiradas: " + ultimoResultado.solicitacoesExpiradas()
                + " · Conclusoes: " + ultimoResultado.conclusoesExpiradas()
                + " · Documentos: " + ultimoResultado.documentosExpurgados()
                + " · Sancoes: " + ultimoResultado.sancoesCumpridas();
    }

    public static Comparator<Dominio.Sancao> porInicio() {
        return Comparator.comparing((Dominio.Sancao s) -> s.inicio).reversed();
    }
}
