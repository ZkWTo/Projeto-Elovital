package View;

import Model.Relogio;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** ADR 0005 / 0020: composicao e ciclo de vida do monolito modular. */
public final class Aplicacao {

    private final Contexto contexto;
    private HttpServer servidor;
    private ScheduledExecutorService agendador;
    private int porta;

    public Aplicacao() {
        this.contexto = new Contexto(Relogio.sistema());
    }

    public void iniciar(int porta) throws IOException {
        this.porta = porta;
        servidor = HttpServer.create(new InetSocketAddress(porta), 0);
        servidor.createContext("/", new Rotas(contexto));
        servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        servidor.start();

        agendador = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "elovital-rotinas");
            t.setDaemon(true);
            return t;
        });
        agendador.scheduleAtFixedRate(() -> {
            try {
                contexto.rotinas.executar();
                contexto.sessoes.limparExpiradas(30);
                contexto.limitador.limpar(java.time.Duration.ofHours(2));
            } catch (RuntimeException e) {
                System.err.println("[rotinas] falha controlada: " + e.getMessage());
            }
        }, 30, 60, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::parar, "elovital-shutdown"));

        System.out.println();
        System.out.println("  EloVital iniciado em http://localhost:" + porta);
        System.out.println("  Perfis de demonstracao (senha: EloVital@2026)");
        System.out.println("    Assistido  assistido@elovital.org");
        System.out.println("    Apoiador   apoiador@elovital.org");
        System.out.println("    Mediacao   mediador@elovital.org");
        System.out.println("  Health: /health   Metricas: /actuator/prometheus");
        System.out.println();
    }

    public void parar() {
        if (servidor != null) {
            servidor.stop(0);
            servidor = null;
        }
        if (agendador != null) {
            agendador.shutdownNow();
            agendador = null;
        }
    }

    public Contexto contexto() {
        return contexto;
    }

    public int porta() {
        return porta;
    }
}
