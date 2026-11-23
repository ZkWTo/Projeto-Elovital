# ADR 0009: Conteúdo em texto puro e validação

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md)
- **Relacionado:** [ADR 0010](0010-entrega-web-e-api.md), [ADR 0022](0022-seguranca-da-aplicacao-web.md)
- **Substitui:** [ADR 0003](0003-conteudo-em-texto-puro.md)

## Contexto

O EloVital recebe texto de várias origens:

- descrição de solicitação (1.000 caracteres);
- título (80), observações de cuidado (500), observações do horário (200);
- mensagens de chat (1.000);
- comentários de avaliação (300);
- justificativas da mediação e de recusa (500);
- denúncias (500);
- nome (120), e-mail (200), condição não listada (100), limitações (300).

Parte desse conteúdo é exibida a outras pessoas e à mediação. Aceitar HTML ou Markdown exigiria sanitização, uma dependência adicional e um risco real de XSS — especialmente grave em conteúdo de saúde e de denúncia.

Além disso, dois requisitos tratam o **significado** do texto, não só o formato:

- RF005/RF011/RS005: pedido de dinheiro, dados bancários ou telefone deve ser alertado e pode ser ocultado;
- RF015: a mediação pode ocultar conteúdo ofensivo, com justificativa, preservando o original.

## Decisão

### Texto puro, sem exceção

- Todo conteúdo de usuário é armazenado como `text` e exibido como **texto puro**.
- Thymeleaf usa `th:text` (escapa HTML). **`th:utext` é proibido** em qualquer template que renderize conteúdo de usuário.
- Não há Markdown, HTML, negrito, itálico ou links gerados a partir do texto do usuário na v1.
- URLs digitadas **não** viram links clicáveis; e-mails e telefones **não** são auto-linkados.
- Quebras de linha são preservadas com CSS (`white-space: pre-wrap`) ou com `th:each` sobre linhas; não se usa `linebreaks` com HTML.
- A mediação também escreve texto puro: justificativas e observações internas não têm formatação (Decisão 35 do PRD mantida fora do escopo).

### Limites centralizados

Os limites ficam em uma única classe, usada por Bean Validation e pelo domínio:

```java
public final class LimitesTexto {
    public static final int NOME = 120;
    public static final int EMAIL = 200;
    public static final int TITULO_SOLICITACAO = 80;
    public static final int DESCRICAO_SOLICITACAO = 1_000;
    public static final int OBSERVACAO_CUIDADO = 500;
    public static final int OBSERVACAO_HORARIO = 200;
    public static final int MENSAGEM_CHAT = 1_000;
    public static final int COMENTARIO_AVALIACAO = 300;
    public static final int JUSTIFICATIVA = 500;
    public static final int DESCRICAO_DENUNCIA = 500;
    public static final int CONDICAO_OUTRA = 100;
    public static final int LIMITACOES = 300;
    public static final int OBSERVACAO_INTERNA = 500;
}
```

| Campo | Limite | Onde valida |
|---|---|---|
| Título da solicitação | 80 | form + service + banco (`varchar(80)`) |
| Descrição da solicitação | 1.000 | form + service |
| Observações de cuidado | 500 | form + service |
| Mensagem de chat | 1.000 | form + service |
| Comentário de avaliação | 300 | form + service |
| Justificativa de mediação/recusa | 500 | form + service |
| Descrição de denúncia | 500 | form + service |
| Observações do horário | 200 | form + service |
| Nome | 120 | form + service + banco |
| E-mail | 200 | form + service + banco |
| Condição não listada | 100 | form + service |
| Limitações | 300 | form + service |
| Observações internas | 500 | form + service |

O limite é contado em **code points** (não em bytes), para não cortar emoji ao meio. O banco recebe `varchar` com o mesmo limite quando a coluna é curta; campos longos são `text` com validação no service.

### Normalização

- Unicode **NFC** antes de gravar.
- Remoção de caracteres de controle (exceto `\n`, `\r`, `\t`).
- Preservação de acentos e emojis.
- `trim` nas pontas; não se colapsam espaços internos (pode haver formatação intencional).
- Não se converte aspas, travessões ou outros caracteres tipográficos.

### Detecção de conteúdo proibido

O RF005 e o RF011 exigem alerta para pedido de dinheiro, dados bancários e compartilhamento de telefone. A v1 usa **regra determinística**, não IA (a moderação por IA está fora do escopo):

```java
public interface DetectorConteudoSensivel {
    ResultadoDeteccao analisar(String texto, ContextoConteudo contexto);
}
```

- Lista de termos e expressões (Pix, transferência, conta bancária, agência, "me manda", "deposita", "CPF", "telefone", sequências de 10–11 dígitos, links).
- Normalização (sem acento, minúsculas) antes de comparar.
- Resultado: `LIMPO`, `SUSPEITO` (alerta ao usuário), `BLOQUEADO` (impede publicação e notifica a mediação).
- A lista é **cadastro-base** editável pela mediação ([ADR 0019](0019-parametros-e-cadastros-base.md)); não é constante no código.
- Falso positivo é preferível a deixar passar; o usuário pode ajustar o texto.
- Toda detecção bloqueante gera auditoria e notificação à mediação.

A detecção **não** substitui a análise humana da solicitação (RF006).

### Ocultação pela mediação

- Colunas `oculto_em`, `oculto_por`, `motivo_ocultacao` na tabela afetada (`mensagem`, `avaliacao`, `solicitacao`).
- O conteúdo original é preservado; a ocultação é lógica.
- Ocultar não altera nota de avaliação nem apaga histórico (RF013/RF015).
- Para o usuário comum, o conteúdo oculto aparece como aviso genérico ("Conteúdo em análise"), sem repetir o texto.
- A mediação continua vendo o original, com registro de acesso.

### Validação em camadas

| Camada | Responsabilidade |
|---|---|
| Formulário (Bean Validation) | obrigatoriedade, tamanho, formato, máscara |
| Caso de uso | regra de estado, autorização, detecção de conteúdo, normalização |
| Banco | `varchar(n)`, `CHECK` de enum, `NOT NULL` |

O formulário **não** decide regra de negócio; o caso de uso **não** revalida formato básico sem necessidade.

## Fluxo de usuário afetado

| Etapa | Como o texto puro aparece |
|---|---|
| Cadastro (RF001) | nome, condição "outra", limitações; sem formatação |
| Perfil (RF003) | edição de textos livres; histórico com antes/depois mascarado |
| Agenda (RF004) | observações de 200 caracteres, exibidas como texto |
| Solicitação (RF005) | título, descrição, observações de cuidado; detector de dinheiro bloqueia publicação |
| Análise (RF006) | justificativa e observações internas em texto puro |
| Catálogo (RF007) | nenhum texto livre do Assistido é exibido |
| Chat (RF011) | mensagem de até 1.000, sem anexos, com alerta de telefone/dinheiro |
| Conclusão (RF012) | sem texto livre relevante |
| Avaliação (RF013) | comentário de 300; ocultação sem alterar nota |
| Denúncia (RF014) | descrição de 500 + trecho anexado automaticamente |
| Mediação (RF015) | ocultação com justificativa; original preservado |

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Markdown com sanitização | Superfície de ataque e dependência extra sem requisito de produto. |
| Editor rico (WYSIWYG) | Fora do escopo; aumenta XSS e acessibilidade. |
| OWASP Java HTML Sanitizer | Só faria sentido se HTML fosse permitido; não é. |
| Moderação por IA | Fora do escopo declarado (PRD 9.2); custo e imprevisibilidade. |
| Limite em bytes | Corta emoji e acentos; o requisito fala em caracteres. |

## Consequências

- A interface é mais simples e acessível, mas menos expressiva.
- Textos longos dependem de quebras de linha; não há listas nem destaques.
- A detecção por lista de termos gera falsos positivos e exige manutenção do cadastro-base.
- A ocultação lógica exige que toda consulta pública filtre `oculto_em is null`.
- Adotar Markdown no futuro exigirá nova ADR e sanitização, sem migrar os textos já gravados.

## Critérios de aceite

- [ ] Payload `<script>alert(1)</script>` é exibido como texto em todos os campos, inclusive chat, denúncia e justificativa.
- [ ] Nenhum template usa `th:utext` com conteúdo de usuário (teste que varre `templates/`).
- [ ] Nenhum campo aceita mais que o limite definido, e a mensagem de erro indica o limite.
- [ ] Emoji e acento não são corrompidos ao gravar e reler.
- [ ] Mensagem com "Pix" ou sequência de telefone gera alerta/bloqueio conforme o contexto.
- [ ] Conteúdo oculto não aparece em listagem pública e continua visível para a mediação.
- [ ] Ocultar comentário não altera a média da avaliação.
- [ ] Nenhum texto de usuário aparece em log de aplicação.

## Decisões pendentes

> **DECISÃO PENDENTE 52:** confirmar a lista inicial de termos do detector de conteúdo proibido e se o bloqueio é automático ou apenas alerta (Decisão 14 do PRD).
>
> **DECISÃO PENDENTE 53:** confirmar se negrito/itálico em justificativas internas da mediação fica fora da v1 (Decisão 35 do PRD).
>
> **DECISÃO PENDENTE 54:** definir o texto do aviso ao usuário quando o conteúdo é ocultado, para não revelar detalhes da denúncia.
