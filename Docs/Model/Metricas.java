package Model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** ADR 0020: metricas sem dado pessoal, no formato Prometheus. */
public final class Metricas {

    private final Repositorios repo;
    private final Relogio relogio;
    private final Instant inicio;
    private final AtomicLong requisicoes = new AtomicLong();
    private final AtomicLong erros = new AtomicLong();
    private final AtomicLong bloqueiosCsrf = new AtomicLong();
    private final AtomicLong conexoesSse = new AtomicLong();

    public Metricas(Repositorios repo, Relogio relogio) {
        this.repo = repo;
        this.relogio = relogio;
        this.inicio = relogio.agora();
    }

    public void requisicao() {
        requisicoes.incrementAndGet();
    }

    public void erro() {
        erros.incrementAndGet();
    }

    public void csrfBloqueado() {
        bloqueiosCsrf.incrementAndGet();
    }

    public void sseAberta() {
        conexoesSse.incrementAndGet();
    }

    public void sseFechada() {
        conexoesSse.decrementAndGet();
    }

    public long requisicoes() {
        return requisicoes.get();
    }

    public String prometheus() {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP elovital_up Aplicacao no ar\n");
        sb.append("# TYPE elovital_up gauge\n");
        sb.append("elovital_up 1\n");
        sb.append("# HELP elovital_requisicoes_total Requisicoes HTTP\n");
        sb.append("# TYPE elovital_requisicoes_total counter\n");
        sb.append("elovital_requisicoes_total ").append(requisicoes.get()).append('\n');
        sb.append("# HELP elovital_erros_total Respostas 4xx/5xx\n");
        sb.append("# TYPE elovital_erros_total counter\n");
        sb.append("elovital_erros_total ").append(erros.get()).append('\n');
        sb.append("# HELP elovital_csrf_bloqueados_total POSTs sem CSRF\n");
        sb.append("# TYPE elovital_csrf_bloqueados_total counter\n");
        sb.append("elovital_csrf_bloqueados_total ").append(bloqueiosCsrf.get()).append('\n');
        sb.append("# HELP elovital_usuarios_total Contas por status\n");
        sb.append("# TYPE elovital_usuarios_total gauge\n");
        for (Dominio.StatusConta status : Dominio.StatusConta.values()) {
            long total = repo.usuarios.values().stream().filter(u -> u.status == status).count();
            sb.append("elovital_usuarios_total{status=\"").append(status).append("\"} ")
                    .append(total).append('\n');
        }
        sb.append("# HELP elovital_reservas_total Reservas por status\n");
        sb.append("# TYPE elovital_reservas_total gauge\n");
        for (Dominio.StatusReserva status : Dominio.StatusReserva.values()) {
            long total = repo.reservas.values().stream().filter(r -> r.status == status).count();
            sb.append("elovital_reservas_total{status=\"").append(status).append("\"} ")
                    .append(total).append('\n');
        }
        sb.append("# HELP elovital_logins_total Tentativas de login\n");
        sb.append("# TYPE elovital_logins_total counter\n");
        sb.append("elovital_logins_total{resultado=\"ok\"} ").append(repo.loginsOk.get()).append('\n');
        sb.append("elovital_logins_total{resultado=\"falha\"} ").append(repo.loginsFalhos.get()).append('\n');
        sb.append("# HELP elovital_conteudo_bloqueado_total Conteudos bloqueados pelo detector\n");
        sb.append("# TYPE elovital_conteudo_bloqueado_total counter\n");
        sb.append("elovital_conteudo_bloqueado_total ").append(repo.pedidosBloqueados.get()).append('\n');
        sb.append("# HELP elovital_sse_conexoes Conexoes SSE abertas\n");
        sb.append("# TYPE elovital_sse_conexoes gauge\n");
        sb.append("elovital_sse_conexoes ").append(conexoesSse.get()).append('\n');
        long uptime = relogio.agora().getEpochSecond() - inicio.getEpochSecond();
        sb.append("# HELP elovital_uptime_segundos Tempo desde o inicio\n");
        sb.append("# TYPE elovital_uptime_segundos gauge\n");
        sb.append("elovital_uptime_segundos ").append(uptime).append('\n');
        return sb.toString();
    }
}
