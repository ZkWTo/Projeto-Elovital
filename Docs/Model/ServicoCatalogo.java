package Model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADR 0018 / RF007: catalogo por distancia, perfil publico e busca de CEP. */
public final class ServicoCatalogo {

    private final Repositorios repo;
    private final Relogio relogio;

    public ServicoCatalogo(Repositorios repo, Relogio relogio) {
        this.repo = repo;
        this.relogio = relogio;
    }

    public record ApoiadorPublico(
            UUID usuarioId, String primeiroNome, String bairro, String municipio,
            String uf, double distanciaKm, String especialidades, String modalidade,
            double notaMedia, int totalAvaliacoes, boolean verificado) {}

    public record Endereco(String cep, String bairro, String municipio, String uf,
            double latitude, double longitude) {}

    public List<ApoiadorPublico> listar(String especialidade, String modalidade,
            String municipio, Double distanciaMaxima) {
        List<ApoiadorPublico> resultado = new ArrayList<>();
        for (Dominio.PerfilApoiador p : repo.perfisApoiador.values()) {
            Dominio.Usuario u = repo.usuarios.get(p.usuarioId);
            if (u == null || !u.ativo()) {
                continue;
            }
            if (especialidade != null && !especialidade.isBlank()
                    && !p.especialidades.toLowerCase().contains(especialidade.toLowerCase())) {
                continue;
            }
            if (modalidade != null && !modalidade.isBlank()
                    && !p.modalidade.name().equalsIgnoreCase(modalidade)
                    && p.modalidade != Dominio.Modalidade.AMBOS) {
                continue;
            }
            if (municipio != null && !municipio.isBlank()
                    && !municipio.equalsIgnoreCase(p.municipio)) {
                continue;
            }
            double distancia = distanciaEstimada(p);
            if (distanciaMaxima != null && distancia > distanciaMaxima) {
                continue;
            }
            resultado.add(new ApoiadorPublico(u.id, primeiroNome(u.nomeCompleto), p.bairro,
                    p.municipio, p.uf, distancia, p.especialidades, p.modalidade.name(),
                    p.notaMedia, p.totalAvaliacoes, p.verificado));
        }
        resultado.sort(Comparator.comparingDouble(ApoiadorPublico::distanciaKm));
        return resultado;
    }

    /** Projecao publica: nunca expoe CPF, endereco exato, telefone ou e-mail. */
    public Optional<ApoiadorPublico> perfilPublico(UUID apoiadorId) {
        return listar(null, null, null, null).stream()
                .filter(a -> a.usuarioId().equals(apoiadorId))
                .findFirst();
    }

    public List<ApoiadorPublico> paraSolicitacao(Dominio.Solicitacao s) {
        return listar(null, null, null, s.raioKm);
    }

    public Endereco consultarCep(String cep) {
        String digitos = Cripto.digitos(cep);
        if (digitos.length() != 8) {
            throw new Erros.ValidacaoException("cep", "formato", "Informe um CEP com 8 digitos.");
        }
        String prefixo = digitos.substring(0, 5);
        if (prefixo.startsWith("01") || prefixo.startsWith("02") || prefixo.startsWith("03")) {
            return new Endereco(digitos, "Se", "Sao Paulo", "SP", -23.5505, -46.6333);
        }
        if (prefixo.startsWith("13")) {
            return new Endereco(digitos, "Centro", "Campinas", "SP", -22.9099, -47.0626);
        }
        if (prefixo.startsWith("11")) {
            return new Endereco(digitos, "Centro", "Santos", "SP", -23.9608, -46.3336);
        }
        throw new Erros.IntegracaoException("consulta de CEP");
    }

    public List<Dominio.Especialidade> especialidades() {
        return repo.especialidades.values().stream()
                .sorted(Comparator.comparing(e -> e.nome)).toList();
    }

    public List<Dominio.CondicaoCronica> condicoes() {
        return repo.condicoes.values().stream()
                .sorted(Comparator.comparing(c -> c.nome)).toList();
    }

    public List<Dominio.Municipio> municipios() {
        return repo.municipios.values().stream()
                .sorted(Comparator.comparing(m -> m.nome)).toList();
    }

    public double distanciaEstimada(Dominio.PerfilApoiador p) {
        Dominio.PerfilAssistido assistido = repo.perfisAssistido.values().stream()
                .findFirst().orElse(null);
        if (assistido == null || p.latitude == 0 || assistido.latitude == 0) {
            return 0;
        }
        return Math.round(haversine(assistido.latitude, assistido.longitude,
                p.latitude, p.longitude) * 10.0) / 10.0;
    }

    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    public static String primeiroNome(String nomeCompleto) {
        if (nomeCompleto == null || nomeCompleto.isBlank()) {
            return "Apoiador";
        }
        return nomeCompleto.trim().split("\\s+")[0];
    }

    public String distanciaTexto(double km) {
        if (km <= 0) {
            return "distancia nao informada";
        }
        return "cerca de " + Math.max(1, Math.round(km)) + " km";
    }

    public Instant agora() {
        return relogio.agora();
    }
}
