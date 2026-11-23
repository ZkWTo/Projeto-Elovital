# ADR 0003: Conteúdo de texto puro

- **Status:** Substituído em 2026-11-23
- **Substituído por:** [ADR 0009 — Conteúdo em texto puro e validação](0009-conteudo-texto-puro-e-validacao.md)
- **Data:** 2026-11-16
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md)

> **Documento histórico.** A decisão de texto puro permanece e foi ampliada para validação centralizada, normalização e detecção de conteúdo proibido no [ADR 0009](0009-conteudo-texto-puro-e-validacao.md). As referências a `mark_safe` e ao autoescape do Django valem apenas como histórico.

## Contexto

O EloVital recebe texto de várias origens: descrição de solicitação, observações de cuidado, mensagens de chat, justificativas de mediação, comentários de avaliação e denúncias. Parte desse texto é exibida para outras pessoas e para a mediação.

Aceitar HTML ou Markdown permitiria formatação, mas exigiria sanitização e uma dependência adicional. Um erro de sanitização em conteúdo de saúde ou de denúncia pode gerar XSS, vazamento de dados ou conteúdo malicioso.

## Decisão

- Todo conteúdo enviado por usuários é armazenado e exibido como texto puro.
- A exibição usa o autoescape do Django e, quando necessário, o filtro `linebreaks` para transformar quebras de linha em parágrafos.
- `safe`, `mark_safe`, `autoescape off` e equivalentes não são usados em conteúdo de usuário.
- Os limites de tamanho do Documento de Requisitos são validados no formulário e no service: 120 para nome, 200 para e-mail, 100 para condição não listada, 300 para limitações e comentário de avaliação, 200 para observações do horário, 500 para observações de cuidado, justificativa e denúncia, 1.000 para descrição e mensagem, 80 para título de solicitação, conforme cada caso.
- Não há anexos, imagens, áudios ou chamadas no chat na primeira versão.
- Foto de perfil e documentos de verificação não são conteúdo rico: são arquivos validados por tipo e tamanho, armazenados em bucket privado, com acesso por URL assinada de curta duração.
- Links digitados pelo usuário não são transformados em âncoras clicáveis.
- Conteúdo ofensivo pode ser ocultado pela mediação, com justificativa e registro; o texto original é preservado para auditoria.

## Consequências

- O risco de XSS pelo conteúdo do usuário fica coberto pelo autoescape, sem sanitizador adicional.
- Os textos não têm títulos internos, negrito, links clicáveis, imagens ou outras formatações.
- A leitura de mensagens longas depende de quebras de linha simples.
- Adotar Markdown no futuro exigirá uma nova ADR com renderização e sanitização, mas não exigirá migrar os textos já gravados.
- A moderação precisa lidar com texto puro, sem recursos de renderização para esconder conteúdo.
- O armazenamento de arquivos segue regras próprias de segurança e retenção, fora do escopo desta decisão.
