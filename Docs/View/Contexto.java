package View;

import Model.Armazenamento;
import Model.Dominio;
import Model.Metricas;
import Model.Relogio;
import Model.Repositorios;
import Model.ServicoAgenda;
import Model.ServicoAuditoria;
import Model.ServicoCatalogo;
import Model.ServicoComunicacao;
import Model.ServicoContas;
import Model.ServicoModeracao;
import Model.ServicoNotificacao;
import Model.ServicoParametros;
import Model.ServicoReputacao;
import Model.ServicoReservas;
import Model.ServicoRotinas;
import Model.ServicoSolicitacoes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Composicao dos servicos e traducao de entidades para view models. */
public final class Contexto {

    public final Repositorios repo;
    public final Relogio relogio;
    public final ServicoAuditoria auditoria;
    public final ServicoNotificacao notificacao;
    public final ServicoContas contas;
    public final ServicoSolicitacoes solicitacoes;
    public final ServicoAgenda agenda;
    public final ServicoCatalogo catalogo;
    public final ServicoReservas reservas;
    public final ServicoComunicacao comunicacao;
    public final ServicoReputacao reputacao;
    public final ServicoModeracao moderacao;
    public final ServicoParametros parametros;
    public final ServicoRotinas rotinas;
    public final Armazenamento armazenamento;
    public final Metricas metricas;
    public final TemplateEngine templates;
    public final Http.Sessoes sessoes;
    public final Limitador limitador;

    public Contexto(Relogio relogio) {
        this.relogio = relogio;
        this.repo = new Repositorios(relogio);
        this.auditoria = new ServicoAuditoria(repo, relogio);
        this.notificacao = new ServicoNotificacao(repo, relogio);
        this.contas = new ServicoContas(repo, relogio, auditoria, notificacao);
        this.solicitacoes = new ServicoSolicitacoes(repo, relogio, auditoria, notificacao);
        this.agenda = new ServicoAgenda(repo, relogio, auditoria, notificacao);
        this.catalogo = new ServicoCatalogo(repo, relogio);
        this.reservas = new ServicoReservas(repo, relogio, auditoria, notificacao);
        this.comunicacao = new ServicoComunicacao(repo, relogio, auditoria, notificacao, reservas);
        this.reputacao = new ServicoReputacao(repo, relogio, auditoria, notificacao, reservas);
        this.moderacao = new ServicoModeracao(repo, relogio, auditoria, notificacao,
                contas, comunicacao, reputacao);
        this.parametros = new ServicoParametros(repo, relogio, auditoria);
        this.rotinas = new ServicoRotinas(repo, relogio, solicitacoes, reservas, contas, auditoria);
        this.armazenamento = new Armazenamento(relogio);
        this.metricas = new Metricas(repo, relogio);
        this.templates = new TemplateEngine();
        this.sessoes = new Http.Sessoes(relogio);
        this.limitador = new Limitador(relogio);
        repo.semearSeVazio();
    }

    public Map<String, Object> base(Http.Requisicao req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logado", false);
        m.put("ehMediacao", false);
        m.put("naoLidas", 0L);
        m.put("usuarioNome", "");
        m.put("usuarioPapel", "");
        m.put("csrf", req.sessao().map(s -> s.csrf).orElse(""));
        m.put("ano", relogio.hoje().getYear());
        m.put("flash", req.query("ok").isBlank() ? "" : Http.mensagem(req.query("ok")));
        m.put("erroFlash", req.query("erro"));
        req.sessao().ifPresent(s -> {
            repo.usuario(s.usuarioId).ifPresent(u -> {
                m.put("logado", true);
                m.put("usuarioId", u.id);
                m.put("usuarioNome", u.nomeCompleto);
                m.put("usuarioPapel", Formatador.titulo(u.papel.name()));
                m.put("ehMediacao", SegurancaWeb.mediacao(u));
                m.put("naoLidas", notificacao.naoLidas(u.id));
            });
        });
        return m;
    }

    public Map<String, Object> pagina(Http.Requisicao req, String titulo,
            Map<String, Object> extras) {
        Map<String, Object> m = base(req);
        m.put("titulo", titulo);
        if (extras != null) {
            m.putAll(extras);
        }
        return m;
    }

    public static Map<String, Object> m(Object... pares) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pares.length; i += 2) {
            mapa.put(String.valueOf(pares[i]), pares[i + 1]);
        }
        return mapa;
    }

    public Map<String, Object> solicitacaoView(Dominio.Solicitacao s, UUID atorId) {
        return m("id", s.id, "titulo", s.titulo, "descricao", s.descricao,
                "observacao", s.observacaoCuidado == null ? "" : s.observacaoCuidado,
                "status", s.status.name(), "statusLabel", Formatador.status(s.status),
                "prioridade", s.prioridade.name(), "prioridadeLabel",
                Formatador.prioridade(s.prioridadeFinal == null ? s.prioridade : s.prioridadeFinal),
                "tipo", s.tipoApoio, "local", local(s.bairro, s.municipio, s.uf),
                "criadaEm", Formatador.dataHora(s.criadaEm),
                "expiraEm", Formatador.dataHora(s.expiraEm),
                "podeCancelar", s.podeCancelar(atorId),
                "podeReenviar", s.podeReenviar(atorId),
                "oculto", s.ocultoEm != null,
                "assistidoNome", repo.usuario(s.assistidoId).map(u -> u.nomeCompleto).orElse("-"));
    }

    public Map<String, Object> horarioView(Dominio.Horario h) {
        String nome = repo.usuario(h.apoiadorId).map(u -> u.nomeCompleto).orElse("-");
        return m("id", h.id, "apoiadorId", h.apoiadorId, "apoiadorNome", nome,
                "data", Formatador.data(h.data), "inicio", Formatador.hora(h.inicio),
                "fim", Formatador.hora(h.fim),
                "periodo", Formatador.periodo(h.data, h.inicio, h.fim),
                "modalidade", h.modalidade.name(), "modalidadeLabel", Formatador.titulo(h.modalidade.name()),
                "status", h.status.name(), "statusLabel", Formatador.status(h.status),
                "observacoes", h.observacoes, "local", local(h.bairro, h.municipio, h.uf),
                "disponivel", h.status == Dominio.StatusHorario.DISPONIVEL,
                "reservado", h.status == Dominio.StatusHorario.RESERVADO);
    }

    public Map<String, Object> reservaView(Dominio.Reserva r, UUID atorId) {
        Dominio.Horario h = repo.horarios.get(r.horarioId);
        Dominio.Solicitacao s = repo.solicitacoes.get(r.solicitacaoId);
        boolean jaAvaliou = repo.avaliacoes.values().stream()
                .anyMatch(a -> a.reservaId.equals(r.id) && a.autorId.equals(atorId));
        boolean parte = reservas.ehParte(r, atorId);
        return m("id", r.id, "status", r.status.name(),
                "statusLabel", Formatador.status(r.status),
                "criadaEm", Formatador.dataHora(r.criadaEm),
                "periodo", h == null ? "-" : Formatador.periodo(h.data, h.inicio, h.fim),
                "local", h == null ? "-" : local(h.bairro, h.municipio, h.uf),
                "modalidade", h == null ? "-" : Formatador.titulo(h.modalidade.name()),
                "solicitacaoId", r.solicitacaoId,
                "solicitacaoTitulo", s == null ? "-" : s.titulo,
                "assistidoNome", repo.usuario(r.assistidoId).map(u -> u.nomeCompleto).orElse("-"),
                "apoiadorNome", repo.usuario(r.apoiadorId).map(u -> u.nomeCompleto).orElse("-"),
                "cienciaPendente", r.status == Dominio.StatusReserva.CIENCIA_PENDENTE,
                "ativa", r.ativa(), "concluida", r.concluida(),
                "emAnalise", r.status == Dominio.StatusReserva.EM_ANALISE_MEDIACAO,
                "podeCancelar", parte && r.ativa(),
                "podeRealizar", parte && r.status == Dominio.StatusReserva.ATIVA,
                "podeConfirmar", parte && (r.status == Dominio.StatusReserva.ATIVA
                        || r.status == Dominio.StatusReserva.AGUARDANDO_CONFIRMACAO),
                "minhaConfirmacao", atorId.equals(r.assistidoId)
                        ? r.confirmacaoAssistidoEm != null : r.confirmacaoApoiadorEm != null,
                "confirmacaoAssistido", r.confirmacaoAssistidoEm != null,
                "confirmacaoApoiador", r.confirmacaoApoiadorEm != null,
                "podeAvaliar", parte && r.concluida() && !jaAvaliou,
                "jaAvaliou", jaAvaliou,
                "outraParteId", atorId.equals(r.assistidoId) ? r.apoiadorId : r.assistidoId,
                "outraParteNome", atorId.equals(r.assistidoId)
                        ? repo.usuario(r.apoiadorId).map(u -> u.nomeCompleto).orElse("-")
                        : repo.usuario(r.assistidoId).map(u -> u.nomeCompleto).orElse("-"),
                "motivoCancelamento", r.motivoCancelamento,
                "ocorrencia", r.ocorrencia);
    }

    public Map<String, Object> notificacaoView(Dominio.Notificacao n) {
        return m("id", n.id, "texto", n.texto, "link", n.link,
                "lida", n.lida, "criadaEm", Formatador.dataHora(n.criadaEm),
                "tipo", Formatador.titulo(n.tipo.name()));
    }

    public Map<String, Object> mensagemView(Dominio.Mensagem msg, UUID atorId, boolean mediador) {
        String autor = repo.usuario(msg.autorId).map(u -> u.nomeCompleto).orElse("-");
        boolean oculta = msg.ocultoEm != null;
        return m("id", msg.id, "autorNome", autor, "minha", msg.autorId.equals(atorId),
                "texto", oculta && !mediador ? "Conteúdo em análise." : msg.texto,
                "oculta", oculta, "criadaEm", Formatador.dataHora(msg.criadaEm),
                "motivo", oculta && mediador ? msg.motivoOcultacao : "");
    }

    public Map<String, Object> avaliacaoView(Dominio.Avaliacao a) {
        return m("id", a.id, "nota", a.nota, "comentario",
                a.ocultoEm != null ? "Conteúdo em análise." : a.comentario,
                "autorNome", repo.usuario(a.autorId).map(u -> u.nomeCompleto).orElse("-"),
                "criadaEm", Formatador.dataHora(a.criadaEm), "oculta", a.ocultoEm != null,
                "motivo", a.motivoOcultacao == null ? "" : a.motivoOcultacao);
    }

    public Map<String, Object> apoiadorView(ServicoCatalogo.ApoiadorPublico a) {
        return m("usuarioId", a.usuarioId(), "nome", a.primeiroNome(),
                "nomeCompleto", repo.usuario(a.usuarioId()).map(u -> u.nomeCompleto).orElse(a.primeiroNome()),
                "bairro", a.bairro(), "local", local(a.bairro(), a.municipio(), a.uf()),
                "distancia", Formatador.distancia(a.distanciaKm()),
                "especialidades", a.especialidades(),
                "modalidade", Formatador.titulo(a.modalidade()),
                "nota", Formatador.nota(a.notaMedia(), a.totalAvaliacoes()),
                "verificado", a.verificado());
    }

    public Map<String, Object> documentoView(Dominio.DocumentoVerificacao d) {
        return m("id", d.id, "tipo", d.tipo, "status", d.status.name(),
                "statusLabel", Formatador.titulo(d.status.name()),
                "enviadoEm", Formatador.dataHora(d.enviadoEm),
                "justificativa", d.justificativa == null ? "" : d.justificativa,
                "usuarioNome", repo.usuario(d.usuarioId).map(u -> u.nomeCompleto).orElse("-"),
                "usuarioId", d.usuarioId);
    }

    public Map<String, Object> denunciaView(Dominio.Denuncia d) {
        return m("id", d.id, "descricao", d.descricao, "status", d.status.name(),
                "statusLabel", Formatador.titulo(d.status.name()),
                "criadaEm", Formatador.dataHora(d.criadaEm),
                "denunciante", repo.usuario(d.denuncianteId).map(u -> u.nomeCompleto).orElse("-"),
                "alvo", repo.usuario(d.alvoUsuarioId).map(u -> u.nomeCompleto).orElse("-"),
                "alvoId", d.alvoUsuarioId, "reservaId", d.reservaId);
    }

    public Map<String, Object> sancaoView(Dominio.Sancao s) {
        return m("id", s.id, "usuario", repo.usuario(s.usuarioId).map(u -> u.nomeCompleto).orElse("-"),
                "usuarioId", s.usuarioId, "tipo", Formatador.titulo(s.tipo.name()),
                "motivo", s.motivo, "status", Formatador.titulo(s.status.name()),
                "inicio", Formatador.dataHora(s.inicio),
                "fim", s.fim == null ? "-" : Formatador.dataHora(s.fim));
    }

    public Map<String, Object> contestacaoView(Dominio.Contestacao c) {
        return m("id", c.id, "texto", c.texto, "status", c.status.name(),
                "statusLabel", Formatador.titulo(c.status.name()),
                "criadaEm", Formatador.dataHora(c.criadaEm),
                "usuario", repo.usuario(c.usuarioId).map(u -> u.nomeCompleto).orElse("-"));
    }

    public Map<String, Object> usuarioView(Dominio.Usuario u) {
        return m("id", u.id, "nome", u.nomeCompleto, "email", u.email,
                "papel", Formatador.titulo(u.papel.name()),
                "status", u.status.name(), "statusLabel", Formatador.status(u.status),
                "criadoEm", Formatador.dataHora(u.criadoEm),
                "verificado", u.emailVerificado);
    }

    public Map<String, Object> parametroView(Dominio.Parametro p) {
        return m("chave", p.chave, "valor", p.valor, "descricao", p.descricao);
    }

    public Map<String, Object> auditoriaView(Dominio.Auditoria a) {
        return m("id", a.id, "acao", a.acao, "entidade", a.entidade,
                "ator", repo.usuario(a.atorId).map(u -> u.nomeCompleto).orElse("sistema"),
                "antes", a.antes == null ? "" : a.antes,
                "depois", a.depois == null ? "" : a.depois,
                "criadaEm", Formatador.dataHora(a.criadaEm));
    }

    public List<Map<String, Object>> solicitacoesView(List<Dominio.Solicitacao> lista, UUID atorId) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Dominio.Solicitacao s : lista) {
            views.add(solicitacaoView(s, atorId));
        }
        return views;
    }

    public List<Map<String, Object>> horariosView(List<Dominio.Horario> lista) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Dominio.Horario h : lista) {
            views.add(horarioView(h));
        }
        return views;
    }

    public List<Map<String, Object>> reservasView(List<Dominio.Reserva> lista, UUID atorId) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Dominio.Reserva r : lista) {
            views.add(reservaView(r, atorId));
        }
        return views;
    }

    public List<Map<String, Object>> notificacoesView(List<Dominio.Notificacao> lista) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Dominio.Notificacao n : lista) {
            views.add(notificacaoView(n));
        }
        return views;
    }

    public String local(String bairro, String municipio, String uf) {
        String b = bairro == null ? "" : bairro;
        String m = municipio == null ? "" : municipio;
        String u = uf == null ? "" : uf;
        String texto = (b + (b.isEmpty() || m.isEmpty() ? "" : " · ") + m
                + (u.isEmpty() ? "" : "/" + u)).trim();
        return texto.isEmpty() ? "local não informado" : texto;
    }

    public Instant agora() {
        return relogio.agora();
    }
}
