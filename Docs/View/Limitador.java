package View;

import Model.Erros;
import Model.Relogio;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADR 0022: rate limiting em memoria por chave (IP ou usuario). A chave nunca
 * contem e-mail ou CPF; usa hash/IP ou UUID.
 */
public final class Limitador {

    private final Relogio relogio;
    private final Map<String, Deque<Instant>> janelas = new ConcurrentHashMap<>();

    public Limitador(Relogio relogio) {
        this.relogio = relogio;
    }

    public void exigir(String chave, int limite, Duration janela) {
        Instant agora = relogio.agora();
        Deque<Instant> marcas = janelas.computeIfAbsent(chave, k -> new ArrayDeque<>());
        synchronized (marcas) {
            Instant limiteTemporal = agora.minus(janela);
            while (!marcas.isEmpty() && marcas.peekFirst().isBefore(limiteTemporal)) {
                marcas.pollFirst();
            }
            if (marcas.size() >= limite) {
                throw new Erros.LimiteExcedidoException(chave, String.valueOf(limite));
            }
            marcas.addLast(agora);
        }
    }

    public void limpar(Duration retencao) {
        Instant limite = relogio.agora().minus(retencao);
        janelas.entrySet().removeIf(e -> {
            Deque<Instant> marcas = e.getValue();
            synchronized (marcas) {
                return marcas.isEmpty() || marcas.peekLast().isBefore(limite);
            }
        });
    }
}
