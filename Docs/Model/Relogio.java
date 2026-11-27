package Model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * ADR 0005 / 0006: todo prazo de negocio e lido de um Relogio injetavel.
 * Nunca usar Instant.now() ou LocalDateTime.now() dentro dos servicos.
 */
public interface Relogio {

    ZoneId FUSO_NEGOCIO = ZoneId.of("America/Sao_Paulo");

    Instant agora();

    default ZoneId fuso() {
        return FUSO_NEGOCIO;
    }

    default LocalDate hoje() {
        return LocalDate.ofInstant(agora(), fuso());
    }

    static Relogio sistema() {
        return new Sistema();
    }

    final class Sistema implements Relogio {
        @Override
        public Instant agora() {
            return Instant.now();
        }
    }
}
