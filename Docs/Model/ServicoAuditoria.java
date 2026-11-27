package Model;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** ADR 0014: trilha de auditoria imutavel e consulta para a mediacao. */
public final class ServicoAuditoria {

    private final Repositorios repo;
    private final Relogio relogio;

    public ServicoAuditoria(Repositorios repo, Relogio relogio) {
        this.repo = repo;
        this.relogio = relogio;
    }

    public void registrar(java.util.UUID atorId, String acao, String entidade,
            java.util.UUID entidadeId, String antes, String depois, String ipHash) {
        Dominio.Auditoria a = new Dominio.Auditoria();
        a.atorId = atorId;
        a.acao = acao;
        a.entidade = entidade;
        a.entidadeId = entidadeId;
        a.antes = mascarar(antes);
        a.depois = mascarar(depois);
        a.ipHash = ipHash;
        a.criadaEm = relogio.agora();
        repo.auditoria.put(a.id, a);
    }

    public List<Dominio.Auditoria> listar(int limite) {
        return repo.auditoria.values().stream()
                .sorted(Comparator.comparing((Dominio.Auditoria a) -> a.criadaEm).reversed())
                .limit(limite)
                .toList();
    }

    public List<Dominio.Auditoria> daEntidade(java.util.UUID entidadeId) {
        return repo.auditoria.values().stream()
                .filter(a -> entidadeId.equals(a.entidadeId))
                .sorted(Comparator.comparing(a -> a.criadaEm))
                .toList();
    }

    public void registrarAcessoDadoPessoal(java.util.UUID mediadorId, java.util.UUID alvoId,
            String campo, String motivo) {
        if (motivo == null || motivo.isBlank()) {
            throw new Erros.DadoSensivelException();
        }
        Dominio.AcessoDadoPessoal a = new Dominio.AcessoDadoPessoal();
        a.mediadorId = mediadorId;
        a.alvoUsuarioId = alvoId;
        a.campo = campo;
        a.motivo = Texto.normalizar(motivo);
        a.criadoEm = relogio.agora();
        repo.acessosDadoPessoal.put(a.id, a);
        registrar(mediadorId, "ACESSO_DADO_PESSOAL", "usuario", alvoId,
                null, "{\"campo\":\"" + campo + "\"}", null);
    }

    public List<Dominio.AcessoDadoPessoal> acessos() {
        return repo.acessosDadoPessoal.values().stream()
                .sorted(Comparator.comparing((Dominio.AcessoDadoPessoal a) -> a.criadoEm).reversed())
                .toList();
    }

    private String mascarar(String json) {
        if (json == null) {
            return null;
        }
        return json.replaceAll("\\b\\d{11}\\b", "***.***.***-**")
                .replaceAll("\\b\\d{10,11}\\b", "***********");
    }

    public Instant agora() {
        return relogio.agora();
    }
}
