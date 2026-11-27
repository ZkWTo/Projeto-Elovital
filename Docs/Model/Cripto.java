package Model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ADR 0008 / 0013: senha com PBKDF2 (equivalente funcional ao BCrypt no prototipo),
 * CPF com HMAC-SHA-256 + AES-256-GCM e tokens com hash SHA-256.
 * Em producao as chaves vem de variavel de ambiente (CPF_HMAC_PEPPER, CPF_ENC_KEY).
 */
public final class Cripto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERACOES = 210_000;
    private static final int TAMANHO_CHAVE = 256;

    private static final byte[] PEPPER = segredo("CPF_HMAC_PEPPER", "dev-pepper-nao-usar-em-producao");
    private static final byte[] CHAVE_CPF = chaveAes();

    private Cripto() {}

    private static byte[] segredo(String nome, String padrao) {
        String valor = System.getenv(nome);
        if (valor == null || valor.isBlank()) {
            valor = padrao;
        }
        return valor.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] chaveAes() {
        String env = System.getenv("CPF_ENC_KEY");
        if (env != null && !env.isBlank()) {
            try {
                byte[] decodificada = Base64.getDecoder().decode(env);
                if (decodificada.length == 32) {
                    return decodificada;
                }
            } catch (IllegalArgumentException ignorada) {
                // cai no derivado de desenvolvimento
            }
        }
        return sha256("dev-aes-key-nao-usar-em-producao".getBytes(StandardCharsets.UTF_8));
    }

    public static String hashSenha(String senha) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(senha.toCharArray(), salt, ITERACOES);
        return "pbkdf2$" + ITERACOES + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verificarSenha(String senha, String armazenado) {
        if (senha == null || armazenado == null) {
            return false;
        }
        String[] partes = armazenado.split("\\$");
        if (partes.length != 4) {
            return false;
        }
        int iteracoes = Integer.parseInt(partes[1]);
        byte[] salt = Base64.getDecoder().decode(partes[2]);
        byte[] esperado = Base64.getDecoder().decode(partes[3]);
        byte[] calculado = pbkdf2(senha.toCharArray(), salt, iteracoes);
        return MessageDigest.isEqual(esperado, calculado);
    }

    private static byte[] pbkdf2(char[] senha, byte[] salt, int iteracoes) {
        try {
            PBEKeySpec spec = new PBEKeySpec(senha, salt, iteracoes, TAMANHO_CHAVE);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao derivar senha.", e);
        }
    }

    public static String hmacCpf(String cpf) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(PEPPER, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(digitos(cpf).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao gerar HMAC do CPF.", e);
        }
    }

    public static String cifrarCpf(String cpf) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(CHAVE_CPF, "AES"), new GCMParameterSpec(128, iv));
            byte[] cifrado = cipher.doFinal(digitos(cpf).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(cifrado);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao cifrar CPF.", e);
        }
    }

    public static String decifrarCpf(String valor) {
        try {
            String[] partes = valor.split(":");
            byte[] iv = Base64.getDecoder().decode(partes[0]);
            byte[] cifrado = Base64.getDecoder().decode(partes[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(CHAVE_CPF, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cifrado), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao decifrar CPF.", e);
        }
    }

    public static String sha256Hex(String valor) {
        return HexFormat.of().formatHex(sha256(valor.getBytes(StandardCharsets.UTF_8)));
    }

    public static String tokenAleatorio() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String ipHash(String ip) {
        return sha256Hex("ip:" + (ip == null ? "desconhecido" : ip));
    }

    public static String digitos(String documento) {
        return documento == null ? "" : documento.replaceAll("\\D", "");
    }

    public static String mascararCpf(String cpf) {
        String d = digitos(cpf);
        if (d.length() != 11) {
            return "***.***.***-**";
        }
        return "***." + d.substring(3, 6) + "." + d.substring(6, 9) + "-**";
    }

    private static byte[] sha256(byte[] entrada) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(entrada);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 indisponivel.", e);
        }
    }

    public static SecretKey chaveSessao() {
        return new SecretKeySpec(sha256(segredo("SESSION_KEY", "dev-session-key").clone()), "AES");
    }
}
