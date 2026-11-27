package View;

import Model.Dominio;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** ADR 0010 / 0009: formatacao pt-BR na borda, sempre como texto. */
public final class Formatador {

    private Formatador() {}

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale BR = Locale.forLanguageTag("pt-BR");

    public static String data(Instant i) {
        return i == null ? "-" : DATA.format(i.atZone(FUSO));
    }

    public static String dataHora(Instant i) {
        return i == null ? "-" : DATA_HORA.format(i.atZone(FUSO));
    }

    public static String data(LocalDate d) {
        return d == null ? "-" : DATA.format(d);
    }

    public static String hora(LocalTime h) {
        return h == null ? "-" : HORA.format(h);
    }

    public static String periodo(LocalDate d, LocalTime inicio, LocalTime fim) {
        return data(d) + " · " + hora(inicio) + " às " + hora(fim);
    }

    public static String idade(LocalDate nascimento) {
        if (nascimento == null) {
            return "-";
        }
        return java.time.Period.between(nascimento, LocalDate.now(FUSO)).getYears() + " anos";
    }

    public static String distancia(double km) {
        if (km <= 0) {
            return "distância não informada";
        }
        return "cerca de " + Math.max(1, Math.round(km)) + " km";
    }

    public static String titulo(String status) {
        if (status == null) {
            return "-";
        }
        return switch (status) {
            case "EM_ANALISE" -> "Em análise";
            case "AGUARDANDO_AJUSTE" -> "Aguardando ajuste";
            case "ABERTA" -> "Aberta";
            case "RESERVADA" -> "Reservada";
            case "AGUARDANDO_CONFIRMACAO" -> "Aguardando confirmação";
            case "CONCLUIDA" -> "Concluída";
            case "CONCLUIDA_UMA_CONFIRMACAO" -> "Concluída com uma confirmação";
            case "CANCELADA" -> "Cancelada";
            case "EXPIRADA" -> "Expirada";
            case "RECUSADA" -> "Recusada";
            case "DISPONIVEL" -> "Disponível";
            case "RESERVADO" -> "Reservado";
            case "REALIZADO" -> "Realizado";
            case "LIBERADO" -> "Liberado";
            case "BLOQUEADO" -> "Bloqueado";
            case "CIENCIA_PENDENTE" -> "Ciência pendente";
            case "ATIVA" -> "Ativa";
            case "EM_ANALISE_MEDIACAO" -> "Em análise pela mediação";
            case "FALTA_REGISTRADA" -> "Falta registrada";
            case "REMARCADA" -> "Remarcada";
            case "SUSPENSA" -> "Suspensa";
            case "BLOQUEADA" -> "Bloqueada";
            case "DESATIVADA" -> "Desativada";
            case "PENDENTE" -> "Pendente";
            case "APROVADO" -> "Aprovado";
            case "REPROVADO" -> "Reprovado";
            case "EXPURGADO" -> "Expurgado";
            case "PROCEDENTE" -> "Procedente";
            case "IMPROCEDENTE" -> "Improcedente";
            case "ARQUIVADA" -> "Arquivada";
            case "CUMPRIDA" -> "Cumprida";
            case "REVOGADA" -> "Revogada";
            case "DEFERIDA" -> "Deferida";
            case "INDEFERIDA" -> "Indeferida";
            case "BAIXA" -> "Baixa";
            case "MEDIA" -> "Média";
            case "ALTA" -> "Alta";
            case "PRESENCIAL" -> "Presencial";
            case "REMOTO" -> "Remoto";
            case "AMBOS" -> "Presencial e remoto";
            case "ASSISTIDO" -> "Assistido";
            case "APOIADOR" -> "Apoiador";
            case "MEDIADOR" -> "Mediação";
            default -> status.toLowerCase(BR).replace('_', ' ');
        };
    }

    public static String nota(double nota, int total) {
        if (total == 0) {
            return "sem avaliações";
        }
        return String.format(BR, "%.1f (%d)", nota, total);
    }

    public static String plural(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    public static String status(Dominio.StatusSolicitacao s) {
        return titulo(s.name());
    }

    public static String status(Dominio.StatusReserva s) {
        return titulo(s.name());
    }

    public static String status(Dominio.StatusHorario s) {
        return titulo(s.name());
    }

    public static String status(Dominio.StatusConta s) {
        return titulo(s.name());
    }

    public static String prioridade(Dominio.Prioridade p) {
        return titulo(p.name());
    }
}
