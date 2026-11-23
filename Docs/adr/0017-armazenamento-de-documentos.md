# ADR 0017: Armazenamento de fotos e documentos

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — RF001, RF003, RF016
- **Relacionado:** [ADR 0007](0007-persistencia-postgresql-jpa-flyway.md), [ADR 0013](0013-lgpd-e-dados-sensiveis.md), [ADR 0016](0016-rotinas-agendadas-e-expiracao.md), [ADR 0022](0022-seguranca-da-aplicacao-web.md)

## Contexto

O EloVital recebe dois tipos de arquivo com finalidades e riscos diferentes:

| Arquivo | Origem | Finalidade | Exibição |
|---|---|---|---|
| Foto de perfil | cadastro e perfil (RF001/RF003) | identificação visual e confiança | perfil público, opcional no catálogo (RF007) |
| Documento com foto + selfie | verificação (RF016) | conferência de identidade pela mediação | nunca pública |
| Registro profissional | verificação (RF016) | conferência de especialidade regulamentada | nunca público |

O RF016 exige armazenamento protegido e exclusão após o prazo necessário. O RS001 exige criptografia em repouso e registro de acesso. O PRD proíbe anexos no chat (RF011) e conteúdo rico em texto ([ADR 0009](0009-conteudo-texto-puro-e-validacao.md)).

O risco é o de sempre: uma URL pública "temporária" que fica indexada, um arquivo com extensão falsa, ou um documento que sobrevive ao prazo.

## Decisão

### Buckets separados e privados

| Bucket | Conteúdo | Acesso |
|---|---|---|
| `fotos-perfil` | foto de perfil | privado, servido por URL assinada curta |
| `documentos` | documento, selfie, comprovante de registro | privado, servido apenas à mediação, URL assinada de 60 s |

- Nenhum bucket é público.
- Nenhuma URL permanente é gravada no banco; grava-se apenas o **path** (`storage_path`).
- Nenhuma imagem é servida por `MEDIA_URL` estático ou por rota que apenas copie o path.
- O nome original do arquivo não é usado; o path é aleatório.

### Porta de armazenamento

```java
public interface ArmazenamentoArquivos {
    String salvar(Bucket bucket, ConteudoArquivo arquivo);      // devolve o path
    URL urlAssinada(Bucket bucket, String path, Duration validade);
    void remover(Bucket bucket, String path);
    boolean existe(Bucket bucket, String path);
}
```

- Implementação principal: **Supabase Storage** (server-side, com chave de serviço).
- Em teste: implementação em memória ou MinIO/S3 local no Testcontainers.
- Nenhum controller conhece o SDK do Storage; só a porta.

### Validação de upload

Validado em três níveis:

| Nível | Verificação |
|---|---|
| Borda | `max-file-size` e `max-request-size` do Spring (5 MB por arquivo) |
| Aplicação | tipo declarado, extensão, **assinatura real (magic bytes)**, dimensões |
| Pós-processamento | remoção de metadados EXIF, reencode da imagem |

Tipos aceitos:

| Uso | Tipos | Tamanho |
|---|---|---|
| Foto de perfil | JPEG, PNG | ≤ 5 MB |
| Documento | JPEG, PNG, PDF | ≤ 10 MB |
| Selfie | JPEG, PNG | ≤ 10 MB |
| Registro profissional | JPEG, PNG, PDF | ≤ 10 MB |

Regras:

- A extensão e o `Content-Type` declarados **não** bastam: a assinatura do arquivo é conferida.
- Arquivo com extensão falsa é recusado e o evento é registrado.
- Imagem é decodificada e reencodada (remove payload embutido e EXIF com GPS).
- Dimensão mínima e máxima para evitar imagem degenerada ou "bomba" de descompressão.
- PDF é aceito apenas em documento e registro; é lido apenas como binário, nunca renderizado no servidor.
- Antivírus (ClamAV) fica como decisão futura; na v1, a validação de assinatura e o reencode cobrem o risco mais comum.

```java
@Component
public class ValidadorArquivo {
    public ConteudoArquivo validar(MultipartFile arquivo, TipoArquivo tipo) {
        byte[] bytes = arquivo.getBytes();
        TipoReal real = AssinaturaArquivo.detectar(bytes); // magic bytes
        if (!tipo.aceita(real)) throw new ValidacaoException("arquivo", "TIPO_INVALIDO");
        // dimensões, reencode, hash
    }
}
```

### Path e metadados no banco

```text
documento_verificacao
  id, usuario_id, tipo, storage_path, mime_type, tamanho_bytes,
  hash_sha256, status, enviado_em, decidido_em, decidido_por,
  motivo_reprovacao, expurgo_em, expurgado_em
```

- `hash_sha256` permite detectar reenvio do mesmo arquivo e conferir integridade no download.
- `expurgo_em` é calculado na decisão (padrão 90 dias).
- O path é `documentos/{usuarioId}/{uuid}.{ext}` — sem nome, sem CPF, sem data legível.
- O resultado da verificação (`APROVADO`/`REPROVADO`) é preservado após o expurgo do arquivo.

### URL assinada

- Gerada **no servidor**, no momento da exibição, com validade de **60 segundos** (documento) ou **5 minutos** (foto).
- Não é cacheada em HTML público, não vai para log e não é reutilizada.
- Cada acesso a documento gera registro em `acesso_dado_pessoal` com motivo ([ADR 0014](0014-auditoria-e-acesso-a-dados.md)).
- A URL assinada é de uso único na prática: expira rápido e não é compartilhável.

### Foto de perfil

- Obrigatória no cadastro (RF001), embora o RF007 trate a exibição como opcional.
- No catálogo, a foto é exibida por URL assinada curta, com `Referrer-Policy: no-referrer`.
- Existe opção de "não exibir foto" no catálogo? **Decisão pendente** (afeta reidentificação).
- A foto não é usada para busca facial nem para reconhecimento; a comparação com selfie é humana, feita pela mediação.

### Expurgo

- Rotina diária ([ADR 0016](0016-rotinas-agendadas-e-expiracao.md)) lista `expurgo_em <= hoje` e `status in (APROVADO, REPROVADO)`.
- Remove o objeto do Storage e marca `EXPURGADO` + `expurgado_em`.
- Se o Storage falhar, o registro permanece pendente e a rotina retenta; nenhum path fica "esquecido".
- O expurgo registra em `expurgo_log`.
- Backup do Storage segue a política do provedor; restauração reaplica expurgo.

### Acesso

| Ator | Foto de perfil | Documento |
|---|---|---|
| Próprio usuário | sim | sim, durante o envio |
| Outro usuário | sim, no catálogo/perfil (se exibida) | não |
| Mediação | sim | sim, com motivo registrado |
| Operação técnica | não, salvo incidente com registro | não, salvo incidente com registro |

- Nenhuma rota serve o arquivo diretamente a partir do path do banco.
- Teste automatizado tenta acessar documento de outro usuário e espera 403/404 auditado.

## Fluxo de usuário afetado

1. **Cadastro (RF001):** upload da foto obrigatória; validação e armazenamento; erro claro se inválido.
2. **Perfil (RF003):** troca de foto; a anterior é removida após o sucesso.
3. **Verificação (RF016):** envio de documento + selfie; status `PENDENTE`; mediação analisa; aprovação/reprovação com justificativa; expurgo.
4. **Catálogo (RF007):** foto exibida por URL assinada curta; nenhum dado de documento.
5. **Mediação (RF015/RF016):** visualização com motivo registrado.
6. **Expurgo (RF016):** remoção após 90 dias.

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Guardar arquivo no banco (bytea) | Backup maior, difícil de servir, sem CDN. |
| Bucket público com URL não listada | "Não listada" não é controle de acesso. |
| Servir arquivo pela aplicação | Consome banda e memória; o Storage já resolve com URL assinada. |
| Guardar documento em base64 no texto | Aumenta o payload e o risco de vazar em log/exportação. |
| Sem EXIF stripping | Vaza GPS e modelo do aparelho. |

## Consequências

- A dependência do Supabase Storage aumenta; indisponibilidade impede envio e exibição de foto.
- URL assinada exige geração por requisição; cache de imagem precisa respeitar expiração.
- O reencode consome CPU e pode reduzir qualidade; precisa de limite de dimensão.
- O expurgo precisa de monitoramento; falha silenciosa é risco de LGPD.
- PDFs exigem validação extra e não podem ser renderizados no servidor.

## Critérios de aceite

- [ ] Nenhuma URL pública permanente de documento.
- [ ] Upload com extensão falsa é recusado.
- [ ] Imagem com EXIF de GPS é reencodada sem o metadado.
- [ ] URL assinada expira e não pode ser reaproveitada.
- [ ] Documento expurgado não pode ser recuperado pela aplicação.
- [ ] Tentativa de acessar documento de outro usuário é negada e auditada.
- [ ] Foto inválida é descartada e o cadastro não é gravado parcialmente.
- [ ] Especialidade regulamentada sem registro conferido não fica ativa.
- [ ] Nenhum nome original de arquivo é exposto.
- [ ] Nenhum arquivo é servido por `MEDIA_URL`.

## Decisões pendentes

> **DECISÃO PENDENTE 88:** confirmar limites de tamanho (5 MB foto / 10 MB documento) e tipos aceitos.
>
> **DECISÃO PENDENTE 89:** definir se haverá antivírus (ClamAV) na v1.
>
> **DECISÃO PENDENTE 90:** confirmar se o usuário pode optar por não exibir a foto no catálogo (RF007 diz opcional; RF001 exige no cadastro).
>
> **DECISÃO PENDENTE 91:** confirmar o prazo de expurgo (90 dias é proposta) e o que acontece se o Storage falhar repetidamente.
>
> **DECISÃO PENDENTE 92:** definir se o Apoiador pode trocar a selfie/documento sem nova análise completa.
