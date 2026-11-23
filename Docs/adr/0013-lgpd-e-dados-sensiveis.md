# ADR 0013: LGPD, criptografia e dados sensíveis

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — RNF011, RS001–RS013
- **Proposta detalhada:** [Segurança e LGPD](../propostas/banco-supabase/03-seguranca-lgpd.md)
- **Relacionado:** [ADR 0007](0007-persistencia-postgresql-jpa-flyway.md), [ADR 0009](0009-conteudo-texto-puro-e-validacao.md), [ADR 0014](0014-auditoria-e-acesso-a-dados.md), [ADR 0017](0017-armazenamento-de-documentos.md), [ADR 0020](0020-observabilidade-e-operacao.md)

## Contexto

O EloVital trata dados pessoais sensíveis:

- **saúde:** condição crônica, descrição da necessidade de apoio, observações de cuidado, ocorrências;
- **identificação:** CPF, documento com foto, selfie, registro profissional;
- **contato:** telefone/WhatsApp, e-mail, endereço exato, contato de emergência;
- **conteúdo:** mensagens de chat, avaliações, denúncias.

A LGPD (Lei 13.709/2018) exige finalidade, base legal, minimização, consentimento específico para saúde (art. 11), segurança, prestação de contas e atendimento aos direitos do titular. O Marco Civil exige retenção de registros de acesso por 6 meses.

O risco principal não é o banco ser invadido: é o dado aparecer onde não devia — no catálogo, no log, no e-mail, na resposta de erro, na exportação ou no template.

## Decisão

### Classificação e tratamento

| Classe | Exemplos | Tratamento mínimo |
|---|---|---|
| Saúde (art. 11) | condição crônica, descrição, observações de cuidado, ocorrência | acesso às partes e à mediação; nunca no catálogo; nunca em log; consentimento específico |
| Identificação sensível | documento, selfie, registro profissional | bucket privado, URL assinada curta, acesso só mediação, expurgo |
| Identificação pessoal | nome, CPF, e-mail, telefone, nascimento, endereço, foto | CPF/telefone cifrados; hash para unicidade; exibição progressiva |
| Terceiro | contato de emergência | cifrado; acesso do Assistido e mediação |
| Credencial | hash de senha, token, sessão | hash forte; token de uso único; nunca em log |
| Conteúdo | chat, avaliação, denúncia | partes e mediação; sem indexação pública |
| Metadado | IP, user agent, data/hora, sessão | retenção 6 meses; hash quando possível |
| Operacional | parâmetros, catálogos, versão de termos | controle de alteração |

### CPF: hash + cifra

Duas representações, ambas obrigatórias:

```text
cpf_hash    = HMAC-SHA-256(pepper, cpf_normalizado)   → unicidade e busca
cpf_cifrado = "v1:" + base64(nonce || ciphertext || tag) → leitura autorizada
```

- Normalização: apenas 11 dígitos, com validação dos dígitos verificadores.
- O CPF em texto puro **nunca** é gravado, logado, exportado ou exibido no catálogo.
- Unicidade garantida por índice único em `cpf_hash`.
- Busca da mediação é por hash, nunca por `LIKE` no cifrado.
- Exibição descriptografa apenas para o titular e para a mediação **com motivo registrado**.
- Rotação do pepper: recalcula o hash a partir do `cpf_cifrado` (não é preciso pedir o dado ao titular). A rotação exige script, janela e backup.
- **Anonimização** zera `cpf_hash` (valor aleatório irreversível) e `cpf_cifrado`. É a única exceção à imutabilidade do CPF e precisa estar explícita no PRD e neste ADR (Decisão 34 do PRD).

```java
public record Cpf(String valor) {
    public Cpf {
        String digitos = valor.replaceAll("\\D", "");
        if (digitos.length() != 11 || !digitosValidos(digitos)) {
            throw new ValidacaoException("cpf", "CPF_INVALIDO");
        }
        valor = digitos;
    }
}
```

### Telefone e contato de emergência

- Cifrados com AES-256-GCM, mesmo formato versionado do CPF.
- `celular_cifrado` nunca é exibido ao outro usuário (RF007/RF008).
- Exibição apenas para o próprio titular e para a mediação com motivo.
- O contato de emergência é dado de terceiro: a política precisa informar origem e finalidade.
- Conversores JPA aplicam cifra/decifra de forma transparente, mas **não** permitem consulta pelo valor cifrado.

### Formato do campo cifrado

```text
v1:<base64(nonce(12) || ciphertext || tag(16))>
```

- `v1` permite rotação de chave sem reescrever tudo de uma vez.
- `AAD` amarra o valor ao campo e à linha: `elovital:usuario:<id>:cpf`. Copiar o ciphertext para outra linha falha na decifra.
- Nonce aleatório de 96 bits por operação; nunca reutilizado.
- DEK protegida por KEK no cofre; a aplicação recebe a DEK em memória.
- `CPF_HMAC_PEPPER` e `CPF_ENC_KEY` são diferentes entre si, diferentes por ambiente e rotacionáveis.
- Base64, ofuscação e hash simples **não** são criptografia.

```java
@Component
public class CpfCifradoConverter implements AttributeConverter<String, String> {
    // usa CifradorAesGcm com AAD por linha
}
```

### Minimização e liberação progressiva

| Etapa | Visível | Oculto |
|---|---|---|
| Catálogo (RF007) | primeiro nome, foto opcional, especialidades, selo, nota, apoios concluídos, bairro/município, distância aproximada | sobrenome, CPF, telefone, e-mail, endereço exato, condição, observações de cuidado |
| Após reserva (RF008) | nome completo, endereço do atendimento, observações necessárias, chat | telefone, CPF, documento |
| Mediação (RF015/RF016) | dados completos, documentos, histórico | senha, token, chave |

O catálogo é servido por **projeção** (ou view) que não seleciona coluna sensível. Adicionar coluna ao catálogo exige revisão de privacidade explícita.

### Endereço

- Endereço exato nunca aparece no catálogo; exibe-se bairro/município e distância arredondada.
- Depois da reserva, o endereço exato é liberado apenas para a contraparte e apenas se a modalidade for presencial.
- Em atendimento remoto, nem o endereço nem o local exato são liberados.
- A coordenada geográfica é usada para distância, não é exibida.

### Decisões editoriais do modelo

- `anonimizado` **não** é sexto status de produto: é um atributo de expurgo (`anonimizado_em`) sobre `DESATIVADA`.
- `status` de conta usa os cinco valores do RF001.
- Todo campo de texto livre pode conter dado sensível digitado pelo usuário; por isso o tratamento é "texto de usuário nunca vai para log" ([ADR 0020](0020-observabilidade-e-operacao.md)).

### Retenção e descarte

Prazos da proposta 03, seção 9, adotados como padrão (sujeitos à revisão jurídica):

| Dado | Prazo | Ação |
|---|---|---|
| Conta encerrada | 5 anos | anonimizar, manter id |
| Documento e selfie | 90 dias após decisão | excluir arquivo; manter resultado e hash |
| Condição crônica | enquanto o consentimento valer | excluir após revogação/encerramento |
| Solicitações e reservas | 5 anos | anonimizar texto livre; manter status e datas |
| Mensagens | 30 dias leitura + 2 anos arquivado | expurgar conteúdo; manter metadados |
| Notificações | 12 meses | excluir |
| Tentativas de login | 90 dias | excluir |
| IP/user agent | 6 meses | zerar os campos |
| Auditoria de negócio | 5 anos | manter |
| Logs de aplicação | 90 dias | excluir |

Regras:

- O prazo começa no evento que encerra a finalidade (conclusão, cancelamento, encerramento), não na criação.
- Expurgo é idempotente e registrado em `expurgo_log` ([ADR 0016](0016-rotinas-agendadas-e-expiracao.md)).
- **Backup não ressuscita dado expurgado:** toda restauração reaplica o expurgo antes de voltar ao ar.
- Bloqueio por determinação legal suspende o expurgo daquele registro, com justificativa.

### Direitos do titular

| Direito | Como é atendido |
|---|---|
| Confirmação e acesso | área "Meus dados" com exportação JSON/CSV (RF003) |
| Correção | edição de nome, endereço, foto e preferências; CPF imutável com contestação |
| Anonimização/eliminação | solicitação registrada; execução pela mediação nos prazos |
| Portabilidade | exportação estruturada |
| Revogação de consentimento | separada para saúde e documentos, com efeito imediato |
| Oposição | canal do Encarregado |
| Revisão de decisão automatizada | não há sanção automatizada; a mediação é humana |

O pedido é registrado; a resposta sai em até 15 dias (art. 19). O que não pode ser eliminado de imediato (registros de acesso, auditoria, processo em curso) é explicado na resposta.

### Segurança do tratamento

- TLS em trânsito; cifra de campo em repouso para CPF/telefone; cifra de disco do provedor.
- Privilégio mínimo no banco ([ADR 0007](0007-persistencia-postgresql-jpa-flyway.md)).
- Acesso da mediação a dado pessoal exige motivo e gera registro ([ADR 0014](0014-auditoria-e-acesso-a-dados.md)).
- Nenhum dado pessoal em log, métrica, rastreamento ou ferramenta de erro.
- Nenhum dado real em desenvolvimento ou homologação.

## Fluxo de usuário afetado

1. **Cadastro (RF001):** coleta mínima; consentimento de saúde separado, com versão, data e hora; CPF só em hash+cifra.
2. **Verificação (RF016):** documento em bucket privado; acesso exclusivo da mediação; expurgo.
3. **Perfil (RF003):** exportação, correção, revogação e pedido de eliminação.
4. **Catálogo (RF007):** apenas dados aproximados.
5. **Reserva (RF008):** liberação progressiva de nome completo, endereço e observações.
6. **Chat (RF011):** conteúdo acessível às partes e, em denúncia, à mediação.
7. **Avaliação (RF013):** comentário público no perfil, com moderação.
8. **Denúncia (RF014):** identidade do denunciante não revelada.
9. **Mediação (RF015):** acesso com motivo, registro e revisão periódica.
10. **Expurgo (RF017):** anonimização após os prazos.

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| CPF em texto puro com controle de acesso | Vazamento em backup/log/consulta é catastrófico. |
| Hash simples do CPF | Espaço pequeno (11 dígitos), quebrável por força bruta. |
| Criptografia no cliente | A chave ficaria no navegador. |
| Guardar só o hash do CPF | Impossibilita exibir à mediação e recalcular o hash na rotação. |
| Deletar fisicamente | Fere integridade referencial e auditoria. |
| Cifrar todo o banco com chave única | Não protege contra acesso da aplicação; campo a campo limita o dano. |

## Consequências

- O CPF não pode ser usado em busca por `LIKE`, ordenação ou relatório direto; a mediação busca por hash.
- A cifra por campo impede consultas que dependam do valor; o modelo precisa prever isso.
- A gestão de chaves (cofre, rotação, backup) vira obrigação operacional.
- Anonimização exige script cuidadoso para não quebrar chaves estrangeiras.
- A exportação de dados precisa de tratamento para não incluir dado de terceiro (contato de emergência).
- A revisão jurídica dos prazos e bases legais é bloqueante para produção.

## Critérios de aceite

- [ ] Nenhum CPF em texto puro no banco, em log, em exportação ou em resposta HTTP.
- [ ] CPF duplicado é recusado pelo índice único de `cpf_hash`.
- [ ] Alterar `cpf_hash`/`cpf_cifrado` é bloqueado para o papel da aplicação, exceto na anonimização.
- [ ] Catálogo não retorna sobrenome, e-mail, telefone, endereço exato, condição ou observações.
- [ ] Copiar o ciphertext de uma linha para outra falha na decifra (AAD).
- [ ] Documento não tem URL pública permanente.
- [ ] Exportação do titular não inclui dado de outra pessoa além do contato de emergência informado por ele.
- [ ] Expurgo é idempotente e registra em `expurgo_log`.
- [ ] Restauração de backup reaplica o expurgo.
- [ ] Nenhum dado real em `dev`/`staging`.

## Decisões pendentes

> **DECISÃO PENDENTE 69:** confirmar prazos de retenção com o jurídico, em especial mensagens (2 anos) e solicitações (5 anos).
>
> **DECISÃO PENDENTE 70:** aprovar a exceção de anonimização do CPF no texto do PRD (Decisão 34).
>
> **DECISÃO PENDENTE 71:** definir Encarregado (DPO), canal e política de privacidade antes de qualquer dado real.
>
> **DECISÃO PENDENTE 72:** confirmar se a foto de perfil pode ser exibida no catálogo (o RF007 diz opcional; o RF001 exige no cadastro). A decisão afeta reidentificação.
>
> **DECISÃO PENDENTE 73:** definir se o e-mail é liberado após a reserva (RF008) ou se o contato é exclusivamente pelo chat (RF011/RS003) — Decisão 19 do PRD.
