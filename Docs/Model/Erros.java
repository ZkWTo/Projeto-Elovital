package Model;

/** ADR 0006: hierarquia unica de excecoes de dominio, com codigo estavel. */
public final class Erros {

    private Erros() {}

    public static class EloVitalException extends RuntimeException {
        private final String codigo;

        public EloVitalException(String codigo, String mensagem) {
            super(mensagem);
            this.codigo = codigo;
        }

        public String codigo() {
            return codigo;
        }
    }

    public static class ValidacaoException extends EloVitalException {
        private final String campo;

        public ValidacaoException(String campo, String codigo, String mensagem) {
            super(codigo, mensagem);
            this.campo = campo;
        }

        public String campo() {
            return campo;
        }
    }

    public static class PermissaoNegadaException extends EloVitalException {
        public PermissaoNegadaException(String mensagem) {
            super("permissao_negada", mensagem);
        }
    }

    public static class EstadoInvalidoException extends EloVitalException {
        public EstadoInvalidoException(String estadoAtual, String acao) {
            super("estado_invalido", "Nao e possivel " + acao + " no estado " + estadoAtual + ".");
        }
    }

    public static class ConflitoException extends EloVitalException {
        public ConflitoException(String recurso) {
            super("conflito", "O recurso " + recurso + " ja foi alterado. Recarregue a tela.");
        }
    }

    public static class PrazoExpiradoException extends EloVitalException {
        public PrazoExpiradoException(String prazo) {
            super("prazo_expirado", "O prazo de " + prazo + " expirou.");
        }
    }

    public static class LimiteExcedidoException extends EloVitalException {
        public LimiteExcedidoException(String parametro, String limite) {
            super("limite_excedido", "Limite de " + parametro + " atingido (" + limite + ").");
        }
    }

    public static class IntegracaoException extends EloVitalException {
        public IntegracaoException(String servico) {
            super("integracao", "O servico " + servico + " esta indisponivel. Tente novamente.");
        }
    }

    public static class DadoSensivelException extends EloVitalException {
        public DadoSensivelException() {
            super("motivo_obrigatorio", "Informe o motivo do acesso a dado pessoal.");
        }
    }

    public static class NaoEncontradoException extends EloVitalException {
        public NaoEncontradoException(String recurso) {
            super("nao_encontrado", recurso + " nao encontrado.");
        }
    }
}
