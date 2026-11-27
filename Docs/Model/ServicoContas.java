package Model;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ADR 0008 / 0009 / 0013 / 0016 / 0017: cadastro, autenticacao, papeis,
 * recuperacao de senha, perfil, verificacao de identidade e anonimizacao.
 */
public final class ServicoContas {

    private static final Set<String> SENHAS_COMUNS = Set.of(
            "1234567890", "123456789", "senha12345", "password", "qwerty1234",
            "elovital123", "admin12345", "12345678", "senha123");

    private final Repositorios repo;
    private final Relogio relogio;
    private final ServicoAuditoria auditoria;
    private final ServicoNotificacao notificacao;

    public ServicoContas(Repositorios repo, Relogio relogio,
            ServicoAuditoria auditoria, ServicoNotificacao notificacao) {
        this.repo = repo;
        this.relogio = relogio;
        this.auditoria = auditoria;
        this.notificacao = notificacao;
    }

    public record CadastroComando(
            String nome, String email, String senha, String confirmacaoSenha,
            String cpf, String celular, LocalDate nascimento, Dominio.Papel papel,
            boolean aceiteTermos, boolean consentimentoSaude,
            String cep, String bairro, String municipio, String uf,
            String condicoes, double raioKm) {}

    public synchronized Dominio.Usuario cadastrar(CadastroComando c, String ipHash) {
        String nome = Texto.exigir(c.nome(), "nome", Texto.NOME);
        String email = Texto.exigir(c.email(), "email", Texto.EMAIL).toLowerCase();
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new Erros.ValidacaoException("email", "formato", "Informe um e-mail valido.");
        }
        if (!c.aceiteTermos()) {
            throw new Erros.ValidacaoException("termos", "obrigatorio",
                    "E preciso aceitar os termos de uso.");
        }
        if (!c.consentimentoSaude() && c.papel() == Dominio.Papel.ASSISTIDO) {
            throw new Erros.ValidacaoException("consentimento", "obrigatorio",
                    "O consentimento para dados de saude e obrigatorio.");
        }
        exigirSenhaForte(c.senha(), c.confirmacaoSenha(), email, nome);
        String cpf = Cripto.digitos(c.cpf());
        if (!cpfValido(cpf)) {
            throw new Erros.ValidacaoException("cpf", "formato", "Informe um CPF valido.");
        }
        if (c.nascimento() == null || Period.between(c.nascimento(), relogio.hoje()).getYears() < 18) {
            throw new Erros.ValidacaoException("nascimento", "idade",
                    "O cadastro exige 18 anos ou mais.");
        }
        if (repo.usuarioPorEmail(email).isPresent()) {
            throw new Erros.ConflitoException("e-mail");
        }
        String cpfHash = Cripto.hmacCpf(cpf);
        if (repo.usuarios.values().stream().anyMatch(u -> cpfHash.equals(u.cpfHash))) {
            throw new Erros.ConflitoException("CPF");
        }

        Dominio.Usuario u = new Dominio.Usuario();
        u.nomeCompleto = nome;
        u.email = email;
        u.senhaHash = Cripto.hashSenha(c.senha());
        u.cpfHash = cpfHash;
        u.cpfCifrado = Cripto.cifrarCpf(cpf);
        u.celularCifrado = Cripto.cifrarCpf(Cripto.digitos(c.celular()));
        u.dataNascimento = c.nascimento();
        u.papel = c.papel() == Dominio.Papel.MEDIADOR ? Dominio.Papel.ASSISTIDO : c.papel();
        u.status = Dominio.StatusConta.EM_ANALISE;
        u.criadoEm = relogio.agora();
        u.atualizadoEm = u.criadoEm;
        repo.usuarios.put(u.id, u);

        if (u.papel == Dominio.Papel.ASSISTIDO) {
            Dominio.PerfilAssistido p = new Dominio.PerfilAssistido();
            p.usuarioId = u.id;
            p.condicoes = Texto.opcional(c.condicoes(), "condicoes", Texto.OBSERVACAO_CUIDADO);
            p.cep = Texto.opcional(c.cep(), "cep", 9);
            p.bairro = Texto.opcional(c.bairro(), "bairro", 80);
            p.municipio = Texto.opcional(c.municipio(), "municipio", 80);
            p.uf = Texto.opcional(c.uf(), "uf", 2).toUpperCase();
            p.raioKm = c.raioKm() <= 0 ? 10 : (int) Math.round(c.raioKm());
            repo.perfisAssistido.put(p.id, p);
        } else {
            Dominio.PerfilApoiador p = new Dominio.PerfilApoiador();
            p.usuarioId = u.id;
            p.cep = Texto.opcional(c.cep(), "cep", 9);
            p.bairro = Texto.opcional(c.bairro(), "bairro", 80);
            p.municipio = Texto.opcional(c.municipio(), "municipio", 80);
            p.uf = Texto.opcional(c.uf(), "uf", 2).toUpperCase();
            p.raioKm = c.raioKm() <= 0 ? 10 : (int) Math.round(c.raioKm());
            repo.perfisApoiador.put(p.id, p);
        }

        auditoria.registrar(u.id, "CADASTRO", "usuario", u.id, null,
                "{\"email\":\"" + email + "\",\"papel\":\"" + u.papel + "\"}", ipHash);
        notificacao.criar(u.id, Dominio.TipoNotificacao.CONTA,
                "Cadastro recebido. Sua conta esta em analise pela mediacao.", "/painel");
        notificacao.paraMediacao(Dominio.TipoNotificacao.CONTA,
                "Nova conta em analise: " + nome + ".", "/mediacao/contas");
        return u;
    }

    private void exigirSenhaForte(String senha, String confirmacao, String email, String nome) {
        if (senha == null || senha.length() < repo.parametroInt("senha_minimo", 10)) {
            throw new Erros.ValidacaoException("senha", "curta",
                    "A senha deve ter pelo menos " + repo.parametroInt("senha_minimo", 10) + " caracteres.");
        }
        if (!senha.equals(confirmacao)) {
            throw new Erros.ValidacaoException("confirmacaoSenha", "diferente",
                    "A confirmacao nao confere.");
        }
        String dobra = Texto.dobra(senha);
        if (SENHAS_COMUNS.contains(dobra) || dobra.contains(Texto.dobra(email))
                || dobra.contains(Texto.dobra(nome))) {
            throw new Erros.ValidacaoException("senha", "fraca",
                    "Escolha uma senha menos previsivel.");
        }
    }

    public Dominio.Usuario autenticar(String email, String senha, String ip) {
        String emailNormalizado = email == null ? "" : email.trim().toLowerCase();
        String emailHash = Cripto.sha256Hex(emailNormalizado);
        String ipHash = Cripto.ipHash(ip);
        if (bloqueado(emailHash, ipHash)) {
            throw new Erros.LimiteExcedidoException("tentativas de login", "15 minutos");
        }
        Optional<Dominio.Usuario> encontrado = repo.usuarioPorEmail(emailNormalizado);
        boolean ok = encontrado.isPresent() && Cripto.verificarSenha(senha, encontrado.get().senhaHash);
        if (!ok) {
            registrarTentativa(emailHash, ipHash, false);
            repo.loginsFalhos.incrementAndGet();
            throw new Erros.PermissaoNegadaException("E-mail ou senha incorretos.");
        }
        Dominio.Usuario u = encontrado.get();
        if (u.papel == Dominio.Papel.MEDIADOR && !u.mediacaoVinculo) {
            registrarTentativa(emailHash, ipHash, false);
            throw new Erros.PermissaoNegadaException("Conta sem vinculo ativo de mediacao.");
        }
        if (!u.ativo()) {
            registrarTentativa(emailHash, ipHash, true);
            throw new Erros.PermissaoNegadaException(mensagemStatus(u.status));
        }
        registrarTentativa(emailHash, ipHash, true);
        repo.loginsOk.incrementAndGet();
        u.ultimoLoginEm = relogio.agora();
        auditoria.registrar(u.id, "LOGIN", "usuario", u.id, null, null, ipHash);
        return u;
    }

    private String mensagemStatus(Dominio.StatusConta status) {
        return switch (status) {
            case EM_ANALISE -> "Sua conta ainda esta em analise pela mediacao.";
            case SUSPENSA -> "Sua conta esta suspensa. Consulte a mediacao.";
            case BLOQUEADA -> "Sua conta esta bloqueada. Consulte a mediacao.";
            case DESATIVADA -> "Sua conta esta desativada.";
            case ATIVA -> "Conta indisponivel.";
        };
    }

    private boolean bloqueado(String emailHash, String ipHash) {
        Instant limite = relogio.agora().minus(
                Duration.ofMinutes(repo.parametroInt("tentativas_login_janela_min", 15)));
        long falhas = repo.tentativasLogin.values().stream()
                .filter(t -> !t.sucesso)
                .filter(t -> emailHash.equals(t.emailHash) && ipHash.equals(t.ipHash))
                .filter(t -> t.criadaEm.isAfter(limite))
                .count();
        return falhas >= repo.parametroInt("tentativas_login_max", 5);
    }

    private void registrarTentativa(String emailHash, String ipHash, boolean sucesso) {
        Dominio.TentativaLogin t = new Dominio.TentativaLogin();
        t.emailHash = emailHash;
        t.ipHash = ipHash;
        t.sucesso = sucesso;
        t.criadaEm = relogio.agora();
        repo.tentativasLogin.put(t.id, t);
        if (sucesso) {
            repo.tentativasLogin.values().removeIf(x -> emailHash.equals(x.emailHash)
                    && ipHash.equals(x.ipHash) && !x.sucesso);
        }
    }

    /** Sempre a mesma resposta: nao revela se o e-mail existe. */
    public Optional<String> solicitarRecuperacao(String email, String ipHash) {
        Optional<Dominio.Usuario> u = repo.usuarioPorEmail(email);
        if (u.isEmpty()) {
            return Optional.empty();
        }
        repo.tokensRecuperacao.values().stream()
                .filter(t -> t.usuarioId.equals(u.get().id) && t.usadoEm == null)
                .forEach(t -> t.usadoEm = relogio.agora());
        String token = Cripto.tokenAleatorio();
        Dominio.TokenRecuperacao t = new Dominio.TokenRecuperacao();
        t.usuarioId = u.get().id;
        t.tokenHash = Cripto.sha256Hex(token);
        t.expiraEm = relogio.agora().plus(
                Duration.ofMinutes(repo.parametroInt("recuperacao_validade_min", 60)));
        repo.tokensRecuperacao.put(t.id, t);
        auditoria.registrar(u.get().id, "SOLICITAR_RECUPERACAO", "usuario", u.get().id,
                null, null, ipHash);
        notificacao.criar(u.get().id, Dominio.TipoNotificacao.CONTA,
                "Pedido de recuperacao de senha registrado. O link vale 1 hora.", "/entrar");
        return Optional.of(token);
    }

    public void redefinirSenha(String token, String novaSenha, String confirmacao, String ipHash) {
        String hash = Cripto.sha256Hex(token == null ? "" : token);
        Dominio.TokenRecuperacao t = repo.tokensRecuperacao.values().stream()
                .filter(x -> hash.equals(x.tokenHash))
                .findFirst()
                .orElseThrow(() -> new Erros.PrazoExpiradoException("link de recuperacao"));
        if (t.usadoEm != null) {
            throw new Erros.PrazoExpiradoException("link de recuperacao");
        }
        if (t.expiraEm.isBefore(relogio.agora())) {
            throw new Erros.PrazoExpiradoException("link de recuperacao");
        }
        Dominio.Usuario u = repo.usuario(t.usuarioId).orElseThrow();
        exigirSenhaForte(novaSenha, confirmacao, u.email, u.nomeCompleto);
        u.senhaHash = Cripto.hashSenha(novaSenha);
        u.atualizadoEm = relogio.agora();
        u.versao++;
        t.usadoEm = relogio.agora();
        auditoria.registrar(u.id, "REDEFINIR_SENHA", "usuario", u.id, null, null, ipHash);
    }

    public void atualizarPerfil(UUID usuarioId, String nome, String celular, String cep,
            String bairro, String municipio, String uf, String condicoes, String limitacoes,
            Dominio.Modalidade modalidade, int raioKm) {
        Dominio.Usuario u = repo.usuario(usuarioId).orElseThrow();
        u.nomeCompleto = Texto.exigir(nome, "nome", Texto.NOME);
        if (celular != null && !celular.isBlank()) {
            u.celularCifrado = Cripto.cifrarCpf(Cripto.digitos(celular));
        }
        u.atualizadoEm = relogio.agora();
        u.versao++;
        repo.perfilAssistidoPorUsuario(usuarioId).ifPresent(p -> {
            p.cep = Texto.opcional(cep, "cep", 9);
            p.bairro = Texto.opcional(bairro, "bairro", 80);
            p.municipio = Texto.opcional(municipio, "municipio", 80);
            p.uf = Texto.opcional(uf, "uf", 2).toUpperCase();
            p.condicoes = Texto.opcional(condicoes, "condicoes", Texto.OBSERVACAO_CUIDADO);
            p.limitacoes = Texto.opcional(limitacoes, "limitacoes", Texto.LIMITACOES);
            p.raioKm = raioKm <= 0 ? p.raioKm : raioKm;
        });
        repo.perfilApoiadorPorUsuario(usuarioId).ifPresent(p -> {
            p.cep = Texto.opcional(cep, "cep", 9);
            p.bairro = Texto.opcional(bairro, "bairro", 80);
            p.municipio = Texto.opcional(municipio, "municipio", 80);
            p.uf = Texto.opcional(uf, "uf", 2).toUpperCase();
            p.modalidade = modalidade == null ? p.modalidade : modalidade;
            p.raioKm = raioKm <= 0 ? p.raioKm : raioKm;
        });
        auditoria.registrar(usuarioId, "ATUALIZAR_PERFIL", "usuario", usuarioId,
                null, null, null);
    }

    public Dominio.DocumentoVerificacao enviarDocumento(UUID usuarioId, String tipo,
            String nomeArquivo) {
        if (nomeArquivo == null || nomeArquivo.isBlank()) {
            throw new Erros.ValidacaoException("arquivo", "obrigatorio", "Selecione um arquivo.");
        }
        String extensao = nomeArquivo.contains(".")
                ? nomeArquivo.substring(nomeArquivo.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!List.of("pdf", "jpg", "jpeg", "png").contains(extensao)) {
            throw new Erros.ValidacaoException("arquivo", "formato",
                    "Envie PDF, JPG ou PNG.");
        }
        Dominio.DocumentoVerificacao d = new Dominio.DocumentoVerificacao();
        d.usuarioId = usuarioId;
        d.tipo = tipo == null ? "DOCUMENTO" : tipo;
        d.caminhoArquivo = "privado/" + usuarioId + "/" + UUID.randomUUID() + "." + extensao;
        d.hash = Cripto.sha256Hex(d.caminhoArquivo);
        d.enviadoEm = relogio.agora();
        repo.documentos.put(d.id, d);
        auditoria.registrar(usuarioId, "ENVIAR_DOCUMENTO", "documento_verificacao", d.id,
                null, "{\"tipo\":\"" + d.tipo + "\"}", null);
        notificacao.paraMediacao(Dominio.TipoNotificacao.CONTA,
                "Novo documento de verificacao aguardando analise.", "/mediacao/contas");
        return d;
    }

    public void decidirDocumento(UUID mediadorId, UUID documentoId, boolean aprovado,
            String justificativa, String motivoAcesso) {
        Dominio.DocumentoVerificacao d = repo.documentos.get(documentoId);
        if (d == null) {
            throw new Erros.NaoEncontradoException("documento");
        }
        if (d.status != Dominio.StatusDocumento.EM_ANALISE && d.status != Dominio.StatusDocumento.PENDENTE) {
            throw new Erros.EstadoInvalidoException(d.status.name(), "decidir documento");
        }
        if (!aprovado) {
            d.justificativa = Texto.exigir(justificativa, "justificativa", Texto.JUSTIFICATIVA);
        }
        d.status = aprovado ? Dominio.StatusDocumento.APROVADO : Dominio.StatusDocumento.REPROVADO;
        d.analisadoEm = relogio.agora();
        auditoria.registrarAcessoDadoPessoal(mediadorId, d.usuarioId, "documento",
                motivoAcesso == null || motivoAcesso.isBlank() ? "Analise de verificacao" : motivoAcesso);
        auditoria.registrar(mediadorId, aprovado ? "APROVAR_DOCUMENTO" : "REPROVAR_DOCUMENTO",
                "documento_verificacao", d.id, null, null, null);
        notificacao.criar(d.usuarioId, Dominio.TipoNotificacao.CONTA,
                aprovado ? "Seu documento foi aprovado." : "Seu documento foi reprovado. Voce pode reenviar.",
                "/verificacao");
        repo.perfilApoiadorPorUsuario(d.usuarioId).ifPresent(p -> p.verificado = aprovado);
    }

    public void aprovarConta(UUID mediadorId, UUID usuarioId, String ipHash) {
        Dominio.Usuario u = exigirUsuario(usuarioId);
        u.aprovar(mediadorId, relogio.agora());
        auditoria.registrar(mediadorId, "APROVAR_CONTA", "usuario", u.id,
                "EM_ANALISE", "ATIVA", ipHash);
        notificacao.criar(u.id, Dominio.TipoNotificacao.CONTA,
                "Sua conta foi aprovada. Voce ja pode entrar.", "/entrar");
    }

    public void recusarConta(UUID mediadorId, UUID usuarioId, String motivo, String ipHash) {
        Dominio.Usuario u = exigirUsuario(usuarioId);
        String justificativa = Texto.exigir(motivo, "motivo", Texto.JUSTIFICATIVA);
        u.recusar(mediadorId, relogio.agora());
        auditoria.registrar(mediadorId, "RECUSAR_CONTA", "usuario", u.id,
                "EM_ANALISE", "BLOQUEADA: " + justificativa, ipHash);
        notificacao.criar(u.id, Dominio.TipoNotificacao.CONTA,
                "Sua conta foi recusada. Motivo: " + justificativa, "/termos");
    }

    public void suspenderConta(UUID mediadorId, UUID usuarioId, String motivo, String ipHash) {
        Dominio.Usuario u = exigirUsuario(usuarioId);
        String justificativa = Texto.exigir(motivo, "motivo", Texto.JUSTIFICATIVA);
        u.suspender(mediadorId, relogio.agora());
        auditoria.registrar(mediadorId, "SUSPENDER_CONTA", "usuario", u.id,
                "ATIVA", "SUSPENSA: " + justificativa, ipHash);
        notificacao.criar(u.id, Dominio.TipoNotificacao.CONTA,
                "Sua conta foi suspensa. Motivo: " + justificativa, "/termos");
    }

    public void reativarConta(UUID mediadorId, UUID usuarioId, String ipHash) {
        Dominio.Usuario u = exigirUsuario(usuarioId);
        u.reativar(mediadorId, relogio.agora());
        auditoria.registrar(mediadorId, "REATIVAR_CONTA", "usuario", u.id,
                "SUSPENSA/BLOQUEADA", "ATIVA", ipHash);
        notificacao.criar(u.id, Dominio.TipoNotificacao.CONTA, "Sua conta foi reativada.", "/entrar");
    }

    public void desativarPropriaConta(UUID usuarioId, String ipHash) {
        if (repo.solicitacoesDoAssistido(usuarioId).stream()
                .anyMatch(s -> s.status == Dominio.StatusSolicitacao.RESERVADA)) {
            throw new Erros.EstadoInvalidoException("RESERVADA", "desativar conta em andamento");
        }
        Dominio.Usuario u = exigirUsuario(usuarioId);
        u.desativar(relogio.agora());
        auditoria.registrar(usuarioId, "DESATIVAR_CONTA", "usuario", u.id, "ATIVA",
                "DESATIVADA", ipHash);
    }

    public void anonimizar(UUID usuarioId, String ipHash) {
        Dominio.Usuario u = exigirUsuario(usuarioId);
        u.nomeCompleto = "Usuario anonimizado";
        u.email = "anonimizado+" + u.id + "@elovital.invalid";
        u.senhaHash = "!";
        u.cpfHash = null;
        u.cpfCifrado = null;
        u.celularCifrado = null;
        u.fotoPath = null;
        u.anonimizado = true;
        u.status = Dominio.StatusConta.DESATIVADA;
        auditoria.registrar(null, "ANONIMIZAR_LGPD", "usuario", u.id, null, null, ipHash);
    }

    public List<Dominio.Usuario> contasEmAnalise() {
        return repo.usuarios.values().stream()
                .filter(u -> u.status == Dominio.StatusConta.EM_ANALISE)
                .sorted(Comparator.comparing((Dominio.Usuario u) -> u.criadoEm))
                .toList();
    }

    private Dominio.Usuario exigirUsuario(UUID id) {
        return repo.usuario(id).orElseThrow(() -> new Erros.NaoEncontradoException("usuario"));
    }

    private boolean cpfValido(String cpf) {
        if (cpf == null || cpf.length() != 11 || cpf.chars().distinct().count() == 1) {
            return false;
        }
        int soma = 0;
        for (int i = 0; i < 9; i++) {
            soma += (cpf.charAt(i) - '0') * (10 - i);
        }
        int d1 = 11 - (soma % 11);
        d1 = d1 >= 10 ? 0 : d1;
        if (d1 != cpf.charAt(9) - '0') {
            return false;
        }
        soma = 0;
        for (int i = 0; i < 10; i++) {
            soma += (cpf.charAt(i) - '0') * (11 - i);
        }
        int d2 = 11 - (soma % 11);
        d2 = d2 >= 10 ? 0 : d2;
        return d2 == cpf.charAt(10) - '0';
    }
}
