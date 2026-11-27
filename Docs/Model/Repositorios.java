package Model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ADR 0007: no prototipo executavel o adaptador de persistencia e em memoria,
 * mantendo as mesmas invariantes e nomes do schema (usuario, solicitacao, horario,
 * reserva, mensagem, avaliacao, denuncia, sancao, auditoria...).
 * O schema real e versionado em Docs/sql e nas migrations Flyway (V1..V5).
 */
public final class Repositorios {

    public final Map<UUID, Dominio.Usuario> usuarios = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.PerfilAssistido> perfisAssistido = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.PerfilApoiador> perfisApoiador = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Solicitacao> solicitacoes = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Horario> horarios = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Reserva> reservas = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Mensagem> mensagens = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Notificacao> notificacoes = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Avaliacao> avaliacoes = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.DocumentoVerificacao> documentos = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Denuncia> denuncias = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Sancao> sancoes = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Contestacao> contestacoes = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Ocorrencia> ocorrencias = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Auditoria> auditoria = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.AcessoDadoPessoal> acessosDadoPessoal = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.TentativaLogin> tentativasLogin = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.TokenRecuperacao> tokensRecuperacao = new ConcurrentHashMap<>();
    public final Map<String, Dominio.Parametro> parametros = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Municipio> municipios = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.Especialidade> especialidades = new ConcurrentHashMap<>();
    public final Map<UUID, Dominio.CondicaoCronica> condicoes = new ConcurrentHashMap<>();

    public final AtomicLong loginsOk = new AtomicLong();
    public final AtomicLong loginsFalhos = new AtomicLong();
    public final AtomicLong reservasCriadas = new AtomicLong();
    public final AtomicLong pedidosBloqueados = new AtomicLong();

    private final Relogio relogio;
    private boolean semeado;

    public Repositorios(Relogio relogio) {
        this.relogio = relogio;
    }

    public synchronized void semearSeVazio() {
        if (semeado || !usuarios.isEmpty()) {
            return;
        }
        semeado = true;

        Dominio.Parametro[] base = {
            new Dominio.Parametro("sessao_inatividade_min", "30", "Minutos de inatividade da sessao"),
            new Dominio.Parametro("tentativas_login_max", "5", "Falhas antes do bloqueio temporario"),
            new Dominio.Parametro("tentativas_login_janela_min", "15", "Janela do contador de falhas"),
            new Dominio.Parametro("recuperacao_validade_min", "60", "Validade do link de recuperacao"),
            new Dominio.Parametro("solicitacao_validade_dias", "7", "Validade da solicitacao aberta"),
            new Dominio.Parametro("cancelamento_antecedencia_h", "24", "Antecedencia minima sem mediacao"),
            new Dominio.Parametro("conclusao_prazo_h", "72", "Prazo para a segunda confirmacao"),
            new Dominio.Parametro("documento_retencao_dias", "30", "Retencao do arquivo de documento"),
            new Dominio.Parametro("mediacao_sessao_min", "15", "Inatividade da sessao de mediacao"),
            new Dominio.Parametro("senha_minimo", "10", "Tamanho minimo da senha")
        };
        for (Dominio.Parametro p : base) {
            parametros.put(p.chave, p);
        }

        Dominio.Municipio saoPaulo = municipio("Sao Paulo", "SP", -23.5505, -46.6333);
        municipio("Campinas", "SP", -22.9099, -47.0626);
        municipio("Santos", "SP", -23.9608, -46.3336);

        especialidade("Companhia", false);
        especialidade("Enfermagem", true);
        especialidade("Fisioterapia", true);
        especialidade("Psicologia", true);
        especialidade("Nutricao", true);
        especialidade("Transporte", false);

        condicao("Diabetes");
        condicao("Hipertensao");
        condicao("Cancer");
        condicao("Doenca renal cronica");
        condicao("Fibromialgia");
        condicao("Outra");

        Instant agora = relogio.agora();
        String senha = Cripto.hashSenha("EloVital@2026");

        Dominio.Usuario assistido = usuario(
                "Ana Assistida", "assistido@elovital.org", senha,
                Dominio.Papel.ASSISTIDO, "12345678901", LocalDate.of(1972, 4, 12));
        Dominio.PerfilAssistido pa = new Dominio.PerfilAssistido();
        pa.usuarioId = assistido.id;
        pa.condicoes = "Diabetes,Hipertensao";
        pa.limitacoes = "Precisa de companhia para consultas e compras.";
        pa.cep = "01001-000";
        pa.bairro = "Se";
        pa.municipio = "Sao Paulo";
        pa.uf = "SP";
        pa.latitude = saoPaulo.latitude;
        pa.longitude = saoPaulo.longitude;
        pa.raioKm = 15;
        perfisAssistido.put(pa.id, pa);

        Dominio.Usuario apoiador = usuario(
                "Bruno Apoiador", "apoiador@elovital.org", senha,
                Dominio.Papel.APOIADOR, "98765432100", LocalDate.of(1985, 9, 3));
        Dominio.PerfilApoiador pb = new Dominio.PerfilApoiador();
        pb.usuarioId = apoiador.id;
        pb.especialidades = "Companhia,Transporte";
        pb.modalidade = Dominio.Modalidade.AMBOS;
        pb.raioKm = 20;
        pb.cep = "01001-000";
        pb.bairro = "Se";
        pb.municipio = "Sao Paulo";
        pb.uf = "SP";
        pb.latitude = saoPaulo.latitude;
        pb.longitude = saoPaulo.longitude;
        pb.verificado = true;
        perfisApoiador.put(pb.id, pb);

        Dominio.Usuario mediador = usuario(
                "Carla Mediadora", "mediador@elovital.org", senha,
                Dominio.Papel.MEDIADOR, "11122233344", LocalDate.of(1980, 1, 20));
        mediador.mediacaoVinculo = true;

        Dominio.Usuario apoiadora2 = usuario(
                "Dora Apoiadora", "dora@elovital.org", senha,
                Dominio.Papel.APOIADOR, "55566677788", LocalDate.of(1990, 6, 15));
        apoiadora2.status = Dominio.StatusConta.ATIVA;
        Dominio.PerfilApoiador pd = new Dominio.PerfilApoiador();
        pd.usuarioId = apoiadora2.id;
        pd.especialidades = "Enfermagem,Companhia";
        pd.modalidade = Dominio.Modalidade.REMOTO;
        pd.raioKm = 30;
        pd.bairro = "Pinheiros";
        pd.municipio = "Sao Paulo";
        pd.uf = "SP";
        pd.latitude = -23.5615;
        pd.longitude = -46.7020;
        pd.verificado = true;
        perfisApoiador.put(pd.id, pd);

        LocalDate amanha = relogio.hoje().plusDays(1);
        horario(apoiador, amanha, LocalTime.of(14, 0), LocalTime.of(15, 0),
                Dominio.Modalidade.PRESENCIAL, "Posso acompanhar em consultas.", saoPaulo);
        horario(apoiador, amanha.plusDays(1), LocalTime.of(9, 0), LocalTime.of(10, 0),
                Dominio.Modalidade.REMOTO, "Conversa por video.", saoPaulo);
        horario(apoiadora2, amanha, LocalTime.of(16, 0), LocalTime.of(17, 0),
                Dominio.Modalidade.REMOTO, "Orientacao de enfermagem.", saoPaulo);

        Dominio.Solicitacao s = new Dominio.Solicitacao();
        s.assistidoId = assistido.id;
        s.titulo = "Companhia para consulta de rotina";
        s.descricao = "Preciso de companhia para ir ao ambulatorio na proxima semana. "
                + "A consulta dura cerca de uma hora.";
        s.observacaoCuidado = "Uso muletas e prefiro elevador.";
        s.prioridade = Dominio.Prioridade.MEDIA;
        s.status = Dominio.StatusSolicitacao.ABERTA;
        s.bairro = "Se";
        s.municipio = "Sao Paulo";
        s.uf = "SP";
        s.cep = "01001-000";
        s.raioKm = 15;
        s.criadaEm = agora.minusSeconds(3600);
        s.abertaEm = agora.minusSeconds(1800);
        s.expiraEm = agora.plusSeconds(7 * 86400);
        s.atualizadaEm = agora.minusSeconds(1800);
        s.prioridadeFinal = Dominio.Prioridade.MEDIA;
        s.historico.add(new Dominio.Transicao("EM_ANALISE", "ABERTA", mediador.id,
                "aprovada na carga inicial", agora.minusSeconds(1800)));
        solicitacoes.put(s.id, s);

        notificacao(assistido.id, Dominio.TipoNotificacao.CONTA,
                "Bem-vinda ao EloVital. Sua conta esta ativa.", "/painel", false, agora);
        notificacao(apoiador.id, Dominio.TipoNotificacao.CONTA,
                "Bem-vindo ao EloVital. Seu perfil foi verificado.", "/painel", false, agora);
    }

    private Dominio.Municipio municipio(String nome, String uf, double lat, double lon) {
        Dominio.Municipio m = new Dominio.Municipio();
        m.nome = nome;
        m.uf = uf;
        m.latitude = lat;
        m.longitude = lon;
        municipios.put(m.id, m);
        return m;
    }

    private void especialidade(String nome, boolean regulamentada) {
        Dominio.Especialidade e = new Dominio.Especialidade();
        e.nome = nome;
        e.regulamentada = regulamentada;
        especialidades.put(e.id, e);
    }

    private void condicao(String nome) {
        Dominio.CondicaoCronica c = new Dominio.CondicaoCronica();
        c.nome = nome;
        condicoes.put(c.id, c);
    }

    private Dominio.Usuario usuario(String nome, String email, String senhaHash,
            Dominio.Papel papel, String cpf, LocalDate nascimento) {
        Dominio.Usuario u = new Dominio.Usuario();
        u.nomeCompleto = nome;
        u.email = email.toLowerCase();
        u.senhaHash = senhaHash;
        u.papel = papel;
        u.cpfHash = Cripto.hmacCpf(cpf);
        u.cpfCifrado = Cripto.cifrarCpf(cpf);
        u.dataNascimento = nascimento;
        u.status = Dominio.StatusConta.ATIVA;
        u.emailVerificado = true;
        u.emailVerificadoEm = relogio.agora();
        u.criadoEm = relogio.agora();
        u.atualizadoEm = relogio.agora();
        usuarios.put(u.id, u);
        return u;
    }

    private void horario(Dominio.Usuario apoiador, LocalDate data, LocalTime inicio,
            LocalTime fim, Dominio.Modalidade modalidade, String obs, Dominio.Municipio m) {
        Dominio.PerfilApoiador p = perfilApoiadorPorUsuario(apoiador.id).orElseThrow();
        Dominio.Horario h = new Dominio.Horario();
        h.apoiadorId = apoiador.id;
        h.data = data;
        h.inicio = inicio;
        h.fim = fim;
        h.modalidade = modalidade;
        h.observacoes = obs;
        h.bairro = p.bairro;
        h.municipio = p.municipio;
        h.uf = p.uf;
        h.criadoEm = relogio.agora();
        horarios.put(h.id, h);
    }

    private void notificacao(UUID usuarioId, Dominio.TipoNotificacao tipo, String texto,
            String link, boolean lida, Instant agora) {
        Dominio.Notificacao n = new Dominio.Notificacao();
        n.usuarioId = usuarioId;
        n.tipo = tipo;
        n.texto = texto;
        n.link = link;
        n.lida = lida;
        n.criadaEm = agora;
        notificacoes.put(n.id, n);
    }

    public Optional<Dominio.Usuario> usuario(UUID id) {
        return Optional.ofNullable(id == null ? null : usuarios.get(id));
    }

    public Optional<Dominio.Usuario> usuarioPorEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String alvo = email.trim().toLowerCase();
        return usuarios.values().stream().filter(u -> alvo.equals(u.email)).findFirst();
    }

    public Optional<Dominio.PerfilAssistido> perfilAssistidoPorUsuario(UUID usuarioId) {
        return perfisAssistido.values().stream()
                .filter(p -> p.usuarioId.equals(usuarioId)).findFirst();
    }

    public Optional<Dominio.PerfilApoiador> perfilApoiadorPorUsuario(UUID usuarioId) {
        return perfisApoiador.values().stream()
                .filter(p -> p.usuarioId.equals(usuarioId)).findFirst();
    }

    public List<Dominio.Horario> horariosDoApoiador(UUID apoiadorId) {
        return horarios.values().stream()
                .filter(h -> h.apoiadorId.equals(apoiadorId))
                .sorted(Comparator.comparing((Dominio.Horario h) -> h.data)
                        .thenComparing(h -> h.inicio))
                .toList();
    }

    public Optional<Dominio.Reserva> reservaNaoCanceladaDoHorario(UUID horarioId) {
        return reservas.values().stream()
                .filter(r -> r.horarioId.equals(horarioId))
                .filter(r -> r.status != Dominio.StatusReserva.CANCELADA
                        && r.status != Dominio.StatusReserva.REMARCADA)
                .findFirst();
    }

    public Optional<Dominio.Reserva> reservaAtivaDaSolicitacao(UUID solicitacaoId) {
        return reservas.values().stream()
                .filter(r -> r.solicitacaoId.equals(solicitacaoId))
                .filter(Dominio.Reserva::ativa)
                .findFirst();
    }

    public List<Dominio.Notificacao> notificacoesDoUsuario(UUID usuarioId) {
        return notificacoes.values().stream()
                .filter(n -> n.usuarioId.equals(usuarioId))
                .sorted(Comparator.comparing((Dominio.Notificacao n) -> n.criadaEm).reversed())
                .toList();
    }

    public long naoLidas(UUID usuarioId) {
        return notificacoesDoUsuario(usuarioId).stream().filter(n -> !n.lida).count();
    }

    public List<Dominio.Mensagem> mensagensDaReserva(UUID reservaId) {
        return mensagens.values().stream()
                .filter(m -> m.reservaId.equals(reservaId))
                .sorted(Comparator.comparing(m -> m.criadaEm))
                .toList();
    }

    public List<Dominio.Avaliacao> avaliacoesDoAlvo(UUID alvoId) {
        return avaliacoes.values().stream()
                .filter(a -> a.alvoId.equals(alvoId))
                .filter(a -> a.ocultoEm == null)
                .toList();
    }

    public List<Dominio.Solicitacao> solicitacoesDoAssistido(UUID assistidoId) {
        return solicitacoes.values().stream()
                .filter(s -> s.assistidoId.equals(assistidoId))
                .sorted(Comparator.comparing((Dominio.Solicitacao s) -> s.criadaEm).reversed())
                .toList();
    }

    public String parametro(String chave, String padrao) {
        Dominio.Parametro p = parametros.get(chave);
        return p == null ? padrao : p.valor;
    }

    public int parametroInt(String chave, int padrao) {
        try {
            return Integer.parseInt(parametro(chave, String.valueOf(padrao)));
        } catch (NumberFormatException e) {
            return padrao;
        }
    }

    public List<Dominio.Usuario> usuariosOrdenados() {
        return new ArrayList<>(usuarios.values()).stream()
                .sorted(Comparator.comparing(u -> u.nomeCompleto))
                .toList();
    }
}
