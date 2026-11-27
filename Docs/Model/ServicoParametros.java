package Model;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** ADR 0019: parametros configuraveis e cadastros-base editaveis pela mediacao. */
public final class ServicoParametros {

    private final Repositorios repo;
    private final Relogio relogio;
    private final ServicoAuditoria auditoria;

    public ServicoParametros(Repositorios repo, Relogio relogio, ServicoAuditoria auditoria) {
        this.repo = repo;
        this.relogio = relogio;
        this.auditoria = auditoria;
    }

    public List<Dominio.Parametro> listar() {
        return repo.parametros.values().stream()
                .sorted(Comparator.comparing(p -> p.chave)).toList();
    }

    public String obter(String chave, String padrao) {
        return repo.parametro(chave, padrao);
    }

    public int obterInt(String chave, int padrao) {
        return repo.parametroInt(chave, padrao);
    }

    public void atualizar(UUID mediadorId, String chave, String valor, String ipHash) {
        Dominio.Parametro p = repo.parametros.get(chave);
        if (p == null) {
            throw new Erros.NaoEncontradoException("parametro");
        }
        String novo = Texto.exigir(valor, "valor", 120);
        if (chave.endsWith("_min") || chave.endsWith("_max") || chave.endsWith("_dias")
                || chave.endsWith("_h") || chave.equals("senha_minimo")) {
            if (!novo.matches("\\d+")) {
                throw new Erros.ValidacaoException("valor", "formato",
                        "Este parametro aceita apenas numero inteiro.");
            }
        }
        String antes = p.valor;
        p.valor = novo;
        auditoria.registrar(mediadorId, "ALTERAR_PARAMETRO", "parametro", null,
                "{\"" + chave + "\":\"" + antes + "\"}", "{\"" + chave + "\":\"" + novo + "\"}", ipHash);
    }

    public void adicionarEspecialidade(UUID mediadorId, String nome, boolean regulamentada,
            String ipHash) {
        String n = Texto.exigir(nome, "nome", 80);
        if (repo.especialidades.values().stream().anyMatch(e -> e.nome.equalsIgnoreCase(n))) {
            throw new Erros.ConflitoException("especialidade");
        }
        Dominio.Especialidade e = new Dominio.Especialidade();
        e.nome = n;
        e.regulamentada = regulamentada;
        repo.especialidades.put(e.id, e);
        auditoria.registrar(mediadorId, "CRIAR_ESPECIALIDADE", "especialidade", e.id,
                null, n, ipHash);
    }

    public void adicionarCondicao(UUID mediadorId, String nome, String ipHash) {
        String n = Texto.exigir(nome, "nome", 80);
        if (repo.condicoes.values().stream().anyMatch(c -> c.nome.equalsIgnoreCase(n))) {
            throw new Erros.ConflitoException("condicao");
        }
        Dominio.CondicaoCronica c = new Dominio.CondicaoCronica();
        c.nome = n;
        repo.condicoes.put(c.id, c);
        auditoria.registrar(mediadorId, "CRIAR_CONDICAO", "condicao", c.id, null, n, ipHash);
    }

    public void adicionarMunicipio(UUID mediadorId, String nome, String uf,
            double latitude, double longitude, String ipHash) {
        Dominio.Municipio m = new Dominio.Municipio();
        m.nome = Texto.exigir(nome, "nome", 80);
        m.uf = Texto.exigir(uf, "uf", 2).toUpperCase();
        m.latitude = latitude;
        m.longitude = longitude;
        repo.municipios.put(m.id, m);
        auditoria.registrar(mediadorId, "CRIAR_MUNICIPIO", "municipio", m.id, null, m.nome, ipHash);
    }

    public Relogio relogio() {
        return relogio;
    }
}
