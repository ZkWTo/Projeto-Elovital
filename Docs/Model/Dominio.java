package Model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ADR 0007 / 0011: agregados e estados oficiais do EloVital.
 * Estados sao gravados como string; toda transicao passa por um metodo de negocio
 * que valida a origem e registra o historico.
 */
public final class Dominio {

    private Dominio() {}

    public enum Papel { ASSISTIDO, APOIADOR, MEDIADOR }

    public enum StatusConta { EM_ANALISE, ATIVA, SUSPENSA, BLOQUEADA, DESATIVADA }

    public enum StatusSolicitacao {
        EM_ANALISE, AGUARDANDO_AJUSTE, ABERTA, RESERVADA,
        AGUARDANDO_CONFIRMACAO, CONCLUIDA, CONCLUIDA_UMA_CONFIRMACAO,
        CANCELADA, EXPIRADA, RECUSADA
    }

    public enum StatusHorario { DISPONIVEL, RESERVADO, REALIZADO, LIBERADO, BLOQUEADO }

    public enum StatusReserva {
        CIENCIA_PENDENTE, ATIVA, AGUARDANDO_CONFIRMACAO,
        CONCLUIDA, CONCLUIDA_UMA_CONFIRMACAO,
        CANCELADA, EM_ANALISE_MEDIACAO, FALTA_REGISTRADA, REMARCADA
    }

    public enum StatusDocumento { PENDENTE, EM_ANALISE, APROVADO, REPROVADO, EXPURGADO }

    public enum StatusDenuncia { EM_ANALISE, PROCEDENTE, IMPROCEDENTE, ARQUIVADA }

    public enum StatusSancao { ATIVA, CUMPRIDA, REVOGADA }

    public enum StatusContestacao { ABERTA, EM_ANALISE, DEFERIDA, INDEFERIDA }

    public enum Prioridade { BAIXA, MEDIA, ALTA }

    public enum Modalidade { PRESENCIAL, REMOTO, AMBOS }

    public enum TipoSancao { ADVERTENCIA, SUSPENSAO, BLOQUEIO }

    public enum TipoNotificacao {
        CONTA, SOLICITACAO, RESERVA, MENSAGEM, AVALIACAO, DENUNCIA, SISTEMA
    }

    public record Transicao(String de, String para, UUID ator, String motivo, Instant em) {}

    // ------------------------------------------------------------------ Usuario

    public static final class Usuario {
        public UUID id = UUID.randomUUID();
        public String email;
        public String senhaHash;
        public String nomeCompleto;
        public String cpfHash;
        public String cpfCifrado;
        public String celularCifrado;
        public String fotoPath;
        public LocalDate dataNascimento;
        public Papel papel;
        public StatusConta status = StatusConta.EM_ANALISE;
        public boolean emailVerificado;
        public boolean mediacaoVinculo;
        public boolean anonimizado;
        public Instant emailVerificadoEm;
        public Instant ultimoLoginEm;
        public Instant criadoEm;
        public Instant atualizadoEm;
        public long versao;

        public boolean ativo() {
            return status == StatusConta.ATIVA && !anonimizado;
        }

        public void aprovar(UUID mediadorId, Instant agora) {
            exigir(status == StatusConta.EM_ANALISE, "aprovar");
            status = StatusConta.ATIVA;
            atualizadoEm = agora;
            versao++;
        }

        public void recusar(UUID mediadorId, Instant agora) {
            exigir(status == StatusConta.EM_ANALISE, "recusar");
            status = StatusConta.BLOQUEADA;
            atualizadoEm = agora;
            versao++;
        }

        public void suspender(UUID mediadorId, Instant agora) {
            exigir(status == StatusConta.ATIVA, "suspender");
            status = StatusConta.SUSPENSA;
            atualizadoEm = agora;
            versao++;
        }

        public void reativar(UUID mediadorId, Instant agora) {
            exigir(status == StatusConta.SUSPENSA || status == StatusConta.BLOQUEADA, "reativar");
            status = StatusConta.ATIVA;
            atualizadoEm = agora;
            versao++;
        }

        public void bloquear(UUID mediadorId, Instant agora) {
            exigir(status == StatusConta.ATIVA || status == StatusConta.SUSPENSA, "bloquear");
            status = StatusConta.BLOQUEADA;
            atualizadoEm = agora;
            versao++;
        }

        public void desativar(Instant agora) {
            exigir(status == StatusConta.ATIVA, "desativar");
            status = StatusConta.DESATIVADA;
            atualizadoEm = agora;
            versao++;
        }

        private void exigir(boolean condicao, String acao) {
            if (!condicao) {
                throw new Erros.EstadoInvalidoException(status.name(), acao);
            }
        }
    }

    public static final class PerfilAssistido {
        public UUID id = UUID.randomUUID();
        public UUID usuarioId;
        public String condicoes = "";
        public String condicaoOutra = "";
        public String limitacoes = "";
        public String contatoEmergenciaCifrado;
        public String autorizacaoFamiliar = "";
        public String cep;
        public String bairro;
        public String municipio;
        public String uf;
        public double latitude;
        public double longitude;
        public double raioKm = 10;
    }

    public static final class PerfilApoiador {
        public UUID id = UUID.randomUUID();
        public UUID usuarioId;
        public String especialidades = "";
        public String registroProfissional = "";
        public Modalidade modalidade = Modalidade.PRESENCIAL;
        public int raioKm = 10;
        public String cep;
        public String bairro;
        public String municipio;
        public String uf;
        public double latitude;
        public double longitude;
        public double notaMedia;
        public int totalAvaliacoes;
        public boolean verificado;
    }

    // -------------------------------------------------------------- Solicitacao

    public static final class Solicitacao {
        public UUID id = UUID.randomUUID();
        public UUID assistidoId;
        public String titulo;
        public String descricao;
        public String observacaoCuidado;
        public Prioridade prioridade = Prioridade.MEDIA;
        public Prioridade prioridadeFinal;
        public String tipoApoio = "Companhia";
        public StatusSolicitacao status = StatusSolicitacao.EM_ANALISE;
        public String cep;
        public String bairro;
        public String municipio;
        public String uf;
        public double raioKm = 10;
        public Instant criadaEm;
        public Instant abertaEm;
        public Instant expiraEm;
        public Instant atualizadaEm;
        public Instant ocultoEm;
        public String motivoOcultacao;
        public final List<Transicao> historico = new ArrayList<>();

        public boolean terminal() {
            return status == StatusSolicitacao.CONCLUIDA
                    || status == StatusSolicitacao.CONCLUIDA_UMA_CONFIRMACAO
                    || status == StatusSolicitacao.CANCELADA
                    || status == StatusSolicitacao.EXPIRADA
                    || status == StatusSolicitacao.RECUSADA;
        }

        public boolean podeCancelar(UUID atorId) {
            return atorId != null && atorId.equals(assistidoId)
                    && (status == StatusSolicitacao.EM_ANALISE
                    || status == StatusSolicitacao.AGUARDANDO_AJUSTE
                    || status == StatusSolicitacao.ABERTA
                    || status == StatusSolicitacao.RESERVADA);
        }

        public boolean podeReenviar(UUID atorId) {
            return atorId != null && atorId.equals(assistidoId)
                    && status == StatusSolicitacao.AGUARDANDO_AJUSTE;
        }

        public void aprovar(UUID mediadorId, Prioridade prioridade, String observacao, Instant agora) {
            mudar(StatusSolicitacao.ABERTA, mediadorId, observacao, agora);
            this.prioridadeFinal = prioridade;
            this.abertaEm = agora;
        }

        public void solicitarAjuste(UUID mediadorId, String motivo, Instant agora) {
            mudar(StatusSolicitacao.AGUARDANDO_AJUSTE, mediadorId, motivo, agora);
        }

        public void recusar(UUID mediadorId, String motivo, Instant agora) {
            mudar(StatusSolicitacao.RECUSADA, mediadorId, motivo, agora);
        }

        public void reenviar(Instant agora) {
            mudar(StatusSolicitacao.EM_ANALISE, assistidoId, "reenvio pelo assistido", agora);
        }

        public void cancelar(UUID atorId, String motivo, Instant agora) {
            if (!podeCancelar(atorId)) {
                throw new Erros.EstadoInvalidoException(status.name(), "cancelar");
            }
            mudar(StatusSolicitacao.CANCELADA, atorId, motivo, agora);
        }

        public void expirar(Instant agora) {
            mudar(StatusSolicitacao.EXPIRADA, null, "expiracao automatica", agora);
        }

        public void marcarReservada(Instant agora) {
            mudar(StatusSolicitacao.RESERVADA, null, "reserva criada", agora);
        }

        public void registrarRealizacao(Instant agora) {
            mudar(StatusSolicitacao.AGUARDANDO_CONFIRMACAO, null, "realizacao registrada", agora);
        }

        public void concluir(Instant agora) {
            mudar(StatusSolicitacao.CONCLUIDA, null, "dupla confirmacao", agora);
        }

        public void concluirUmaConfirmacao(Instant agora) {
            mudar(StatusSolicitacao.CONCLUIDA_UMA_CONFIRMACAO, null, "prazo de 72h", agora);
        }

        public void reabrir(Instant agora) {
            mudar(StatusSolicitacao.ABERTA, null, "cancelamento com antecedencia", agora);
        }

        public void alterarRelevante(Instant agora) {
            mudar(StatusSolicitacao.EM_ANALISE, assistidoId, "alteracao relevante", agora);
        }

        private void mudar(StatusSolicitacao destino, UUID ator, String motivo, Instant agora) {
            if (terminal()) {
                throw new Erros.EstadoInvalidoException(status.name(), "alterar estado terminal");
            }
            historico.add(new Transicao(status.name(), destino.name(), ator, motivo, agora));
            status = destino;
            atualizadaEm = agora;
        }
    }

    // ------------------------------------------------------------------ Horario

    public static final class Horario {
        public UUID id = UUID.randomUUID();
        public UUID apoiadorId;
        public LocalDate data;
        public LocalTime inicio;
        public LocalTime fim;
        public Modalidade modalidade = Modalidade.PRESENCIAL;
        public String observacoes = "";
        public StatusHorario status = StatusHorario.DISPONIVEL;
        public String bairro;
        public String municipio;
        public String uf;
        public Instant criadoEm;
        public long versao;

        public boolean futuro(Instant agora) {
            Instant inicioHorario = data.atTime(inicio).atZone(Relogio.FUSO_NEGOCIO).toInstant();
            return inicioHorario.isAfter(agora);
        }

        public void reservar() {
            if (status != StatusHorario.DISPONIVEL) {
                throw new Erros.ConflitoException("horario");
            }
            status = StatusHorario.RESERVADO;
            versao++;
        }

        public void liberar() {
            if (status != StatusHorario.RESERVADO && status != StatusHorario.BLOQUEADO) {
                throw new Erros.EstadoInvalidoException(status.name(), "liberar");
            }
            status = StatusHorario.DISPONIVEL;
            versao++;
        }

        public void bloquear() {
            if (status != StatusHorario.DISPONIVEL && status != StatusHorario.RESERVADO) {
                throw new Erros.EstadoInvalidoException(status.name(), "bloquear");
            }
            status = StatusHorario.BLOQUEADO;
            versao++;
        }

        public void realizar() {
            if (status != StatusHorario.RESERVADO) {
                throw new Erros.EstadoInvalidoException(status.name(), "registrar realizacao");
            }
            status = StatusHorario.REALIZADO;
            versao++;
        }
    }

    // ------------------------------------------------------------------ Reserva

    public static final class Reserva {
        public UUID id = UUID.randomUUID();
        public UUID solicitacaoId;
        public UUID horarioId;
        public UUID assistidoId;
        public UUID apoiadorId;
        public StatusReserva status = StatusReserva.CIENCIA_PENDENTE;
        public Instant criadaEm;
        public Instant cienciaEm;
        public Instant confirmacaoAssistidoEm;
        public Instant confirmacaoApoiadorEm;
        public Instant canceladaEm;
        public String motivoCancelamento = "";
        public UUID reservaSubstitutaId;
        public String ocorrencia = "";

        public boolean concluida() {
            return status == StatusReserva.CONCLUIDA || status == StatusReserva.CONCLUIDA_UMA_CONFIRMACAO;
        }

        public boolean ativa() {
            return status == StatusReserva.CIENCIA_PENDENTE
                    || status == StatusReserva.ATIVA
                    || status == StatusReserva.AGUARDANDO_CONFIRMACAO;
        }

        public void registrarCiencia(UUID atorId, Instant agora) {
            if (!atorId.equals(assistidoId) && !atorId.equals(apoiadorId)) {
                throw new Erros.PermissaoNegadaException("Apenas as partes registram ciencia.");
            }
            if (status != StatusReserva.CIENCIA_PENDENTE) {
                throw new Erros.EstadoInvalidoException(status.name(), "registrar ciencia");
            }
            cienciaEm = agora;
            status = StatusReserva.ATIVA;
        }

        public void registrarRealizacao(Instant agora) {
            if (status != StatusReserva.ATIVA) {
                throw new Erros.EstadoInvalidoException(status.name(), "registrar realizacao");
            }
            status = StatusReserva.AGUARDANDO_CONFIRMACAO;
        }

        public void confirmar(UUID atorId, Instant agora) {
            if (status != StatusReserva.ATIVA && status != StatusReserva.AGUARDANDO_CONFIRMACAO) {
                throw new Erros.EstadoInvalidoException(status.name(), "confirmar");
            }
            if (atorId.equals(assistidoId)) {
                confirmacaoAssistidoEm = agora;
            } else if (atorId.equals(apoiadorId)) {
                confirmacaoApoiadorEm = agora;
            } else {
                throw new Erros.PermissaoNegadaException("Apenas as partes confirmam.");
            }
            if (confirmacaoAssistidoEm != null && confirmacaoApoiadorEm != null) {
                status = StatusReserva.CONCLUIDA;
            } else if (status == StatusReserva.ATIVA) {
                status = StatusReserva.AGUARDANDO_CONFIRMACAO;
            }
        }

        public void expirarConfirmacao(Instant agora) {
            if (status == StatusReserva.AGUARDANDO_CONFIRMACAO) {
                status = StatusReserva.CONCLUIDA_UMA_CONFIRMACAO;
                ocorrencia = "Prazo de 72h sem a segunda confirmacao.";
            }
        }

        public void cancelar(boolean antecedenciaSuficiente, String motivo, Instant agora) {
            if (!ativa()) {
                throw new Erros.EstadoInvalidoException(status.name(), "cancelar");
            }
            canceladaEm = agora;
            motivoCancelamento = motivo;
            status = antecedenciaSuficiente ? StatusReserva.CANCELADA : StatusReserva.EM_ANALISE_MEDIACAO;
        }

        public void discordar(String ocorrencia) {
            if (status != StatusReserva.AGUARDANDO_CONFIRMACAO) {
                throw new Erros.EstadoInvalidoException(status.name(), "registrar discordancia");
            }
            this.ocorrencia = ocorrencia;
            status = StatusReserva.EM_ANALISE_MEDIACAO;
        }

        public void registrarFalta() {
            if (status != StatusReserva.ATIVA) {
                throw new Erros.EstadoInvalidoException(status.name(), "registrar falta");
            }
            status = StatusReserva.FALTA_REGISTRADA;
        }

        public void remarcar(UUID novaReservaId) {
            if (!ativa()) {
                throw new Erros.EstadoInvalidoException(status.name(), "remarcar");
            }
            reservaSubstitutaId = novaReservaId;
            status = StatusReserva.REMARCADA;
        }
    }

    // -------------------------------------------------------------- Comunicacao

    public static final class Mensagem {
        public UUID id = UUID.randomUUID();
        public UUID reservaId;
        public UUID autorId;
        public String texto;
        public Instant criadaEm;
        public Instant ocultoEm;
        public String motivoOcultacao;
    }

    public static final class Notificacao {
        public UUID id = UUID.randomUUID();
        public UUID usuarioId;
        public TipoNotificacao tipo = TipoNotificacao.SISTEMA;
        public String texto;
        public String link = "/painel";
        public boolean lida;
        public Instant criadaEm;
    }

    public static final class Avaliacao {
        public UUID id = UUID.randomUUID();
        public UUID reservaId;
        public UUID autorId;
        public UUID alvoId;
        public int nota;
        public String comentario = "";
        public Instant criadaEm;
        public Instant ocultoEm;
        public String motivoOcultacao;
    }

    // --------------------------------------------------------------- Moderacao

    public static final class DocumentoVerificacao {
        public UUID id = UUID.randomUUID();
        public UUID usuarioId;
        public String tipo = "DOCUMENTO";
        public String caminhoArquivo;
        public String hash;
        public StatusDocumento status = StatusDocumento.PENDENTE;
        public Instant enviadoEm;
        public Instant analisadoEm;
        public Instant expurgadoEm;
        public String justificativa = "";
    }

    public static final class Denuncia {
        public UUID id = UUID.randomUUID();
        public UUID denuncianteId;
        public UUID alvoUsuarioId;
        public UUID reservaId;
        public String descricao;
        public StatusDenuncia status = StatusDenuncia.EM_ANALISE;
        public Instant criadaEm;
        public Instant decididaEm;
        public UUID mediadorId;

        public void decidir(boolean procedente, UUID mediadorId, Instant agora) {
            if (status != StatusDenuncia.EM_ANALISE) {
                throw new Erros.EstadoInvalidoException(status.name(), "decidir denuncia");
            }
            status = procedente ? StatusDenuncia.PROCEDENTE : StatusDenuncia.IMPROCEDENTE;
            this.mediadorId = mediadorId;
            decididaEm = agora;
        }

        public void arquivar() {
            if (status == StatusDenuncia.EM_ANALISE) {
                throw new Erros.EstadoInvalidoException(status.name(), "arquivar");
            }
            status = StatusDenuncia.ARQUIVADA;
        }
    }

    public static final class Sancao {
        public UUID id = UUID.randomUUID();
        public UUID usuarioId;
        public TipoSancao tipo;
        public String motivo;
        public Instant inicio;
        public Instant fim;
        public StatusSancao status = StatusSancao.ATIVA;
        public UUID mediadorId;
        public UUID denunciaId;

        public void cumprir() {
            if (status != StatusSancao.ATIVA) {
                throw new Erros.EstadoInvalidoException(status.name(), "cumprir sancao");
            }
            status = StatusSancao.CUMPRIDA;
        }

        public void revogar() {
            if (status != StatusSancao.ATIVA) {
                throw new Erros.EstadoInvalidoException(status.name(), "revogar sancao");
            }
            status = StatusSancao.REVOGADA;
        }
    }

    public static final class Contestacao {
        public UUID id = UUID.randomUUID();
        public UUID sancaoId;
        public UUID usuarioId;
        public String texto;
        public StatusContestacao status = StatusContestacao.ABERTA;
        public Instant criadaEm;
        public Instant decididaEm;
        public UUID mediadorId;

        public void iniciarAnalise() {
            if (status != StatusContestacao.ABERTA) {
                throw new Erros.EstadoInvalidoException(status.name(), "iniciar analise");
            }
            status = StatusContestacao.EM_ANALISE;
        }

        public void decidir(boolean deferida, UUID mediadorId, Instant agora) {
            if (status != StatusContestacao.EM_ANALISE) {
                throw new Erros.EstadoInvalidoException(status.name(), "decidir contestacao");
            }
            status = deferida ? StatusContestacao.DEFERIDA : StatusContestacao.INDEFERIDA;
            this.mediadorId = mediadorId;
            decididaEm = agora;
        }
    }

    public static final class Ocorrencia {
        public UUID id = UUID.randomUUID();
        public UUID reservaId;
        public UUID registradaPor;
        public String descricao;
        public Instant criadaEm;
    }

    // -------------------------------------------------------------- Operacionais

    public static final class Auditoria {
        public UUID id = UUID.randomUUID();
        public UUID atorId;
        public String acao;
        public String entidade;
        public UUID entidadeId;
        public String antes;
        public String depois;
        public String ipHash;
        public Instant criadaEm;
    }

    public static final class AcessoDadoPessoal {
        public UUID id = UUID.randomUUID();
        public UUID mediadorId;
        public UUID alvoUsuarioId;
        public String campo;
        public String motivo;
        public Instant criadoEm;
    }

    public static final class TentativaLogin {
        public UUID id = UUID.randomUUID();
        public String emailHash;
        public String ipHash;
        public boolean sucesso;
        public Instant criadaEm;
    }

    public static final class TokenRecuperacao {
        public UUID id = UUID.randomUUID();
        public UUID usuarioId;
        public String tokenHash;
        public Instant expiraEm;
        public Instant usadoEm;
    }

    public static final class Parametro {
        public String chave;
        public String valor;
        public String descricao;

        public Parametro() {}

        public Parametro(String chave, String valor, String descricao) {
            this.chave = chave;
            this.valor = valor;
            this.descricao = descricao;
        }
    }

    public static final class Municipio {
        public UUID id = UUID.randomUUID();
        public String nome;
        public String uf;
        public double latitude;
        public double longitude;
    }

    public static final class Especialidade {
        public UUID id = UUID.randomUUID();
        public String nome;
        public boolean regulamentada;
    }

    public static final class CondicaoCronica {
        public UUID id = UUID.randomUUID();
        public String nome;
    }
}
