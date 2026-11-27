package View;

import Model.Cripto;
import Model.Dominio;
import Model.Erros;
import Model.Relogio;
import Model.Texto;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADR 0006 / 0010 / 0011 / 0021: verificacoes de contrato executaveis sem
 * infraestrutura externa. Rodar com: java Docs/app.java --test
 */
public final class Testes {

    private final Contexto ctx;
    private final List<String> falhas = new ArrayList<>();
    private int total;

    private Testes(Contexto ctx) {
        this.ctx = ctx;
    }

    public static void main(String[] args) {
        System.exit(executar());
    }

    public static int executar() {
        Testes t = new Testes(new Contexto(Relogio.sistema()));
        t.rodar();
        System.out.println();
        if (t.falhas.isEmpty()) {
            System.out.println("  OK: " + t.total + " verificacoes passaram.");
            return 0;
        }
        System.out.println("  FALHAS: " + t.falhas.size() + " de " + t.total + " verificacoes.");
        t.falhas.forEach(f -> System.out.println("   - " + f));
        return 1;
    }

    private void rodar() {
        credenciais();
        cpfProtegido();
        limitesDeTexto();
        fluxoSolicitacaoEReserva();
        duplaConfirmacaoEAvaliacao();
        moderacaoEContestacao();
        lgpdEAuditoria();
        metricasETemplates();
    }

    // ------------------------------------------------------------------ testes

    private void credenciais() {
        Dominio.Usuario u = ctx.contas.autenticar(
                "assistido@elovital.org", "EloVital@2026", "127.0.0.1");
        checar("login do Assistido semeado", u.papel == Dominio.Papel.ASSISTIDO);
        try {
            ctx.contas.autenticar("assistido@elovital.org", "senha-errada", "127.0.0.2");
            checar("senha incorreta rejeitada", false);
        } catch (Erros.PermissaoNegadaException esperado) {
            checar("senha incorreta rejeitada", true);
        }
        checar("hash de senha nao guarda texto puro",
                !u.senhaHash.contains("EloVital@2026"));
    }

    private void cpfProtegido() {
        Dominio.Usuario u = ctx.repo.usuarioPorEmail("assistido@elovital.org").orElseThrow();
        String cpf = Cripto.decifrarCpf(u.cpfCifrado);
        checar("CPF decifravel apenas com a chave de aplicacao", cpf.equals("12345678901"));
        checar("CPF mascarado na borda",
                Cripto.mascararCpf(cpf).equals("***.456.789-**")
                        && !Cripto.mascararCpf(cpf).contains("123"));
        checar("CPF possui hash para busca exata",
                u.cpfHash.equals(Cripto.hmacCpf("12345678901")));
    }

    private void limitesDeTexto() {
        checar("texto vazio e recusado", lancou(() ->
                Texto.exigir("   ", "titulo", 80)));
        checar("limite de code points aplicado", lancou(() ->
                Texto.exigir("a".repeat(81), "titulo", 80)));
        checar("pedido de dinheiro bloqueado", Texto.detectar(
                "Preciso de ajuda com um pix urgente", true).bloqueado());
        checar("telefone em contexto publico bloqueado", Texto.detectar(
                "meu telefone e 11999998888", true).bloqueado());
        checar("texto limpo aceito", !Texto.detectar(
                "Gostaria de companhia para uma caminhada.", true).bloqueado());
    }

    private void fluxoSolicitacaoEReserva() {
        Dominio.Usuario assistido = usuario("assistido@elovital.org");
        Dominio.Usuario mediador = usuario("mediador@elovital.org");
        Dominio.Usuario apoiador = usuario("apoiador@elovital.org");

        Dominio.Solicitacao s = ctx.solicitacoes.criar(assistido.id,
                "Apoio para ir ao laboratorio", "Preciso de companhia para uma coleta de exames.",
                "Uso bengala.", Dominio.Prioridade.MEDIA, "Companhia",
                "01001-000", "Se", "Sao Paulo", "SP", 15, "teste");
        checar("solicitacao nasce em analise", s.status == Dominio.StatusSolicitacao.EM_ANALISE);

        ctx.solicitacoes.aprovar(mediador.id, s.id, Dominio.Prioridade.ALTA,
                "Prioridade ajustada.", "teste");
        checar("mediacao publica solicitacao", s.status == Dominio.StatusSolicitacao.ABERTA);
        checar("prioridade final registrada", s.prioridadeFinal == Dominio.Prioridade.ALTA);

        Dominio.Horario h = ctx.agenda.disponiveis().stream()
                .filter(x -> x.apoiadorId.equals(apoiador.id))
                .findFirst().orElseThrow();

        Dominio.Reserva r = ctx.reservas.reservar(assistido.id, s.id, h.id, "teste");
        checar("reserva criada com ciencia pendente",
                r.status == Dominio.StatusReserva.CIENCIA_PENDENTE);
        checar("horario fica reservado", h.status == Dominio.StatusHorario.RESERVADO);
        checar("solicitacao fica reservada", s.status == Dominio.StatusSolicitacao.RESERVADA);

        checar("segunda reserva no mesmo horario e bloqueada", lancou(() ->
                ctx.reservas.reservar(assistido.id, s.id, h.id, "teste")));

        ctx.reservas.registrarCiencia(assistido.id, r.id, "teste");
        checar("ciencia ativa a reserva", r.status == Dominio.StatusReserva.ATIVA);
    }

    private void duplaConfirmacaoEAvaliacao() {
        Dominio.Usuario assistido = usuario("assistido@elovital.org");
        Dominio.Usuario apoiador = usuario("apoiador@elovital.org");
        Dominio.Reserva r = ctx.reservas.doUsuario(assistido.id).stream()
                .filter(x -> x.status == Dominio.StatusReserva.ATIVA)
                .findFirst().orElseThrow();

        ctx.reservas.registrarRealizacao(assistido.id, r.id, "teste");
        checar("realizacao aguarda confirmacao",
                r.status == Dominio.StatusReserva.AGUARDANDO_CONFIRMACAO);

        ctx.reservas.confirmar(assistido.id, r.id, "teste");
        checar("uma confirmacao nao conclui", !r.concluida());
        ctx.reservas.confirmar(apoiador.id, r.id, "teste");
        checar("dupla confirmacao conclui", r.status == Dominio.StatusReserva.CONCLUIDA);

        Dominio.Avaliacao a = ctx.reputacao.avaliar(assistido.id, r.id, 5,
                "Apoio atencioso e pontual.", "teste");
        checar("avaliacao registrada", a.nota == 5);
        checar("avaliacao duplicada bloqueada", lancou(() ->
                ctx.reputacao.avaliar(assistido.id, r.id, 4, "de novo", "teste")));
        ctx.reputacao.ocultar(usuario("mediador@elovital.org").id, a.id,
                "Comentario com dado pessoal.", "teste");
        checar("ocultacao preserva a avaliacao", a.ocultoEm != null);
    }

    private void moderacaoEContestacao() {
        Dominio.Usuario assistido = usuario("assistido@elovital.org");
        Dominio.Usuario mediador = usuario("mediador@elovital.org");
        Dominio.Usuario dora = usuario("dora@elovital.org");

        Dominio.Denuncia d = ctx.moderacao.denunciar(assistido.id, dora.id, null,
                "Relato de conduta inadequada durante o contato.", "teste");
        checar("denuncia entra em analise", d.status == Dominio.StatusDenuncia.EM_ANALISE);
        checar("denunciar a si mesmo bloqueado", lancou(() ->
                ctx.moderacao.denunciar(assistido.id, assistido.id, null, "teste", "teste")));

        ctx.moderacao.decidirDenuncia(mediador.id, d.id, true, "Fatos confirmados.", "teste");
        checar("denuncia procedente", d.status == Dominio.StatusDenuncia.PROCEDENTE);

        Dominio.Sancao s = ctx.moderacao.aplicarSancao(mediador.id, dora.id,
                Dominio.TipoSancao.SUSPENSAO, "Suspensao cautelar.", 7, d.id, "teste");
        checar("sancao suspende a conta", dora.status == Dominio.StatusConta.SUSPENSA);

        Dominio.Contestacao c = ctx.moderacao.contestar(dora.id, s.id,
                "Nao houve conduta inadequada; peco revisao.", "teste");
        ctx.moderacao.decidirContestacao(mediador.id, c.id, true,
                "Contestacao procedente.", "teste");
        checar("contestacao deferida revoga sancao", s.status == Dominio.StatusSancao.REVOGADA);
        checar("contestacao deferida reativa conta", dora.status == Dominio.StatusConta.ATIVA);
    }

    private void lgpdEAuditoria() {
        Dominio.Usuario mediador = usuario("mediador@elovital.org");
        Dominio.Usuario assistido = usuario("assistido@elovital.org");

        checar("acesso a dado sensivel exige motivo", lancou(() ->
                ctx.auditoria.registrarAcessoDadoPessoal(mediador.id, assistido.id, "cpf", "")));
        ctx.auditoria.registrarAcessoDadoPessoal(mediador.id, assistido.id, "cpf",
                "Conferencia de documento enviado.");
        checar("acesso sensivel auditado", ctx.auditoria.acessos().size() == 1);

        checar("trilha registra acoes de dominio",
                ctx.auditoria.listar(500).stream().anyMatch(a -> a.acao.equals("CRIAR_RESERVA")));
        checar("trilha mascara sequencia de CPF",
                ctx.auditoria.listar(500).stream()
                        .filter(a -> a.depois != null)
                        .noneMatch(a -> a.depois.matches(".*\\b\\d{11}\\b.*")));

        Dominio.Usuario dora = usuario("dora@elovital.org");
        ctx.contas.anonimizar(dora.id, "teste");
        checar("anonimizacao desativa o acesso", !dora.ativo());
        checar("anonimizacao remove e-mail identificavel",
                dora.email.contains("anonimizado") || dora.email.isBlank());
    }

    private void metricasETemplates() {
        ctx.metricas.requisicao();
        ctx.metricas.erro();
        String prometheus = ctx.metricas.prometheus();
        checar("metricas no formato Prometheus", prometheus.contains("elovital_requisicoes_total"));
        checar("metricas nao expoem e-mail", !prometheus.contains("@"));

        Map<String, Object> mapa = Contexto.m("titulo", "<script>alert(1)</script>",
                "lista", List.of(Contexto.m("nome", "Ana & Bia")));
        String html = ctx.templates.render("home", mapa);
        checar("template renderiza layout", html.contains("EloVital"));
        checar("template escapa HTML de usuario",
                !html.contains("<script>alert(1)</script>") && html.contains("&lt;script&gt;"));
        checar("template mantem acentuacao",
                html.contains("solidária") || html.contains("solid&aacute;ria"));
    }

    // ------------------------------------------------------------------- apoio

    private Dominio.Usuario usuario(String email) {
        return ctx.repo.usuarioPorEmail(email).orElseThrow();
    }

    private void checar(String nome, boolean condicao) {
        total++;
        if (!condicao) {
            falhas.add(nome);
        }
    }

    private boolean lancou(Runnable acao) {
        try {
            acao.run();
            return false;
        } catch (RuntimeException esperado) {
            return true;
        }
    }
}
