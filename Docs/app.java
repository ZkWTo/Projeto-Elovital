import View.Aplicacao;
import java.util.concurrent.CountDownLatch;

/**
 * EloVital — prototipo executavel dos ADRs 0005 a 0023.
 *
 * Executar a partir da raiz do repositorio:
 *     java Docs/app.java
 *
 * Variaveis opcionais:
 *     SERVER_PORT (padrao 8080)
 *     ELOVITAL_PERFIL_DEMO (true/false, padrao true)
 */
public final class app {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--test".equals(args[0])) {
            System.exit(View.Testes.executar());
        }
        int porta = porta();
        Aplicacao aplicacao = new Aplicacao();
        aplicacao.iniciar(porta);
        CountDownLatch bloqueio = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(bloqueio::countDown, "app-encerramento"));
        bloqueio.await();
    }

    private static int porta() {
        String configurada = System.getenv("SERVER_PORT");
        if (configurada == null || configurada.isBlank()) {
            return 8080;
        }
        try {
            int valor = Integer.parseInt(configurada.trim());
            return valor > 0 && valor < 65536 ? valor : 8080;
        } catch (NumberFormatException e) {
            return 8080;
        }
    }
}
