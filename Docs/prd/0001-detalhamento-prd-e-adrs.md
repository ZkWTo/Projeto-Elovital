# PRD do EloVital — detalhamento alinhado ao Documento de Requisitos

- **Status:** Documento de trabalho para revisão — detalha o produto e os ADRs
- **Data:** 2026-11-20
- **Fonte oficial:** [Documento de Requisitos Funcionais do EloVital](../Documento_Requisitos_Funcionais_EloVital.pdf) (41 páginas, seções 1–10)
- **Documentos complementares:** [ADRs 0001–0004](../README.md), [Proposta de banco Supabase](../propostas/banco-supabase/README.md)
- **Objetivo:** reproduzir no PRD **todas as informações do Documento de Requisitos** (RF001–RF017, RNF001–RNF015, RS001–RS013, matrizes, pontos de função e protótipos), acrescentar detalhes verificáveis de implementação e registrar apenas as lacunas reais.

## Regra de precedência

1. **O Documento de Requisitos é a fonte de verdade.** Em qualquer divergência, vale o que está nele.
2. Todo detalhe que **não existe** na fonte é marcado com **`[PROPOSTA]`** e precisa de aprovação.
3. Onde a **própria fonte se contradiz ou não define** o comportamento, o ponto é marcado com **`> DECISÃO PENDENTE`** e não deve ser implementado por suposição.
4. O conteúdo deste arquivo foi conferido requisito por requisito contra o PDF em 2026-11-20. A seção 0.2 lista as divergências da fonte e a seção 9.4 as divergências dos artefatos derivados.

Legenda:

- **RF / RNF / RS:** identificadores do Documento de Requisitos.
- **PF:** pontos de função (seção 8.4).
- **`[PROPOSTA]`:** detalhe inferido, ausente na fonte; precisa de confirmação.
- **`> DECISÃO PENDENTE`:** ponto em aberto que muda comportamento ou escopo.
- **`[LACUNA]`:** requisito existe, mas falta detalhe para implementar.

> **Nota de stack (2026-11-23).** A produção do backend foi definida em **Java 21 + Spring Boot 3**. A Parte II deste documento (ADRs 0001–0004) reproduz a série escrita para a referência em Django e fica como histórico. A série vigente é a de **[ADRs 0005–0023](../adr/README.md)**, que cobre o mesmo fluxo de usuário com Java/Spring, incluindo o mapa etapa do fluxo → ADR. O comportamento descrito nas Partes I e III–IV continua sendo a fonte de verdade.

---

## 0. Resumo executivo

### 0.1 O que este documento contém

| Bloco | Conteúdo |
|---|---|
| Seção 1 | Visão, limites, personas e responsabilidades |
| Seção 2 | Jornadas ponta a ponta |
| Seção 3 | Máquinas de estado (conta, solicitação, horário, reserva, verificação, denúncia) |
| Seção 4 | RF001–RF017 com campos, fluxos, exceções, regras, saídas e critérios de aceite |
| Seção 5 | Matriz de permissões |
| Seção 6 | Parâmetros configuráveis (RNF010) |
| Seção 7 | RNF001–RNF015 com prioridade e critério verificável |
| Seção 8 | Rastreabilidade: RF × RF, RS001–RS013, matriz de risco, pontos de função e protótipos |
| Seção 9 | Regras transversais, escopo negativo, critérios de conclusão e divergências em artefatos derivados |
| Parte II | Detalhamento dos ADRs 0001–0004 |
| Parte III | ADRs sugeridos 0005–0011 |
| Parte IV | Checklist de revisão |
| Apêndice A | Perguntas frequentes de implementação |

### 0.2 Divergências e lacunas da fonte (a decidir)

| # | Achado | Onde na fonte | Impacto | Encaminhamento |
|---|---|---|---|---|
| 1 | RF002 nega acesso às contas **Em análise**, mas RF016 exige que o Assistido envie documento + selfie antes da primeira solicitação — o que exige login | RF002 × RF016 | Alto | Definir quando o Assistido envia documentos (cadastro, primeiro login liberado ou link por e-mail) |
| 2 | RF012 exige **duas confirmações**, mas o fluxo alternativo manda **concluir com uma** após 72 h registrando "confirmado por uma das partes" | RF012 | Alto | Escolher: expirar e abrir ocorrência, ou concluir com uma confirmação |
| 3 | RF005 diz que **não há limite** de solicitações ativas; RF009 e RNF010 citam **limite configurável** | RF005 × RF009 × RNF010 | Alto | Definir se o parâmetro existe e qual o valor |
| 4 | RF001 lista 5 status (Em análise, Ativa, Suspensa, Bloqueada, Desativada), mas não define para qual status vai a conta **recusada** | RF001, RF002 | Alto | Definir se "recusada" usa Bloqueada, Desativada ou um novo status |
| 5 | RF009 cita status **"Em andamento"**, que não aparece na lista de status de solicitação | RF009 | Médio | Confirmar que "Em andamento" = "Reservada" |
| 6 | RF004 cita status de horário **"Liberado"** sem definir quando ocorre | RF004 | Médio | Definir gatilho de "Liberado" |
| 7 | RF001 exige **foto de perfil**; RF007 diz foto **opcional** no catálogo | RF001 × RF007 | Baixo | Confirmar que a foto é obrigatória no cadastro e opcional apenas na exibição |
| 8 | RF005 exige **declaração de necessidade**; RF001 não a lista entre os aceites | RF001 × RF005 | Baixo | Confirmar que a declaração é por solicitação, não do cadastro |
| 9 | RF015 exige verificação prévia de **agendas**, mas RF004 publica o horário direto no catálogo | RF004 × RF015 | Médio | Definir se horário nasce Disponível ou passa por validação |
| 10 | RF008 libera **e-mail** ao Apoiador; RF011 trata o e-mail do perfil como contato de segurança e o chat como canal oficial | RF008 × RF011 | Médio | Definir se o e-mail é liberado após a reserva ou somente em caso de segurança |
| 11 | RF002 nega login a conta **Suspensa**, mas RF015 garante ao sancionado o direito de **contestar** | RF002 × RF015 | Médio | Definir canal de contestação (e-mail, formulário público ou login restrito) |
| 12 | RF007 cita "Apoiadores do próprio usuário" sem definir o conceito | RF007 | Baixo | Remover o termo ou definir (ex.: perfis com bloqueio) |
| 13 | RF006 ordena a fila por **data de envio**; o detalhamento anterior propunha prioridade + espera | RF006 | Baixo | Manter a fonte: data de envio |
| 14 | RF001 não define prazo/efeito do **link de confirmação expirado** além de "24 h / reenviar" | RF001 | Baixo | Corrigir: 24 h é o prazo citado no fluxo alternativo |
| 15 | RF017 cita rotina automática sem frequência, janela e tratamento de falha | RF017 | Médio | Definir cron, idempotência e alerta |
| 16 | Nenhum ADR cobre notificações, arquivos, observabilidade, testes, segurança web e parâmetros | ADRs | Médio | ADRs sugeridos na Parte III |

### 0.3 Conferência de cobertura (fonte → PRD)

| Item da fonte | Onde está neste documento |
|---|---|
| RF001–RF017 | Seção 4 (um bloco por RF) |
| RNF001–RNF015 | Seção 7 (com prioridade) |
| RS001–RS013 | Seção 8.2 |
| Matriz de rastreabilidade RF × RF | Seção 8.1 |
| Matriz de avaliação de risco | Seção 8.3 |
| Ponto de função (465 PF) | Seção 8.4 |
| Protótipos (Telas 01A–11) | Seção 8.5 |
| Regras transversais e status | Seções 2–3 |

---

# Parte I — PRD detalhado

## 1. Visão e limites do produto

### 1.1 Proposta de valor

O EloVital é uma plataforma web de rede de apoio a pessoas com doenças crônicas. Conecta **Assistidos** — pessoas que precisam de apoio cotidiano — a **Apoiadores** — pessoas dispostas a ajudar, inclusive profissionais voluntários —, reduzindo a falta de comunicação e a dificuldade de encontrar quem possa atender.

O escopo da primeira versão inclui: cadastro e verificação de identidade, agenda solidária, criação e validação de solicitações, catálogo de apoiadores, reserva de horário, chat, conclusão do apoio, avaliação, denúncia e mediação. O sistema é **mobile-first**, site responsivo, sem aplicativos nativos.

O sistema se relaciona com serviços externos de consulta de CEP e municípios (IBGE), geolocalização e mapas para cálculo de distância, envio de e-mail e, opcionalmente, notificações push. Não há integração de pagamento.

### 1.2 O que o produto é e o que não é

| É | Não é |
|---|---|
| Vitrine de disponibilidade com curadoria humana | Serviço de emergência ou substituto do SAMU (192) |
| Canal de combinação de apoio entre pessoas | Mecanismo de despacho automático ("próximo da fila") |
| Chat interno entre as partes de uma reserva | Canal de pagamentos, doações ou gorjetas |
| Registro auditável de decisões da mediação | Prontuário, sistema de saúde ou de conselho profissional |
| Plataforma gratuita para Assistidos e Apoiadores | Aplicativo nativo na primeira versão |
| Conta pessoal (uma por CPF) | Conta de ONG, clínica, agência de cuidadores ou instituição |

### 1.3 Objetivos mensuráveis `[PROPOSTA]`

| Objetivo | Métrica | Meta inicial |
|---|---|---|
| Reduzir tempo até o primeiro contato | Tempo entre aprovação da solicitação e primeira mensagem | ≤ 48 h (mediana) |
| Garantir curadoria | Solicitações analisadas dentro do prazo | ≥ 95% em 24 h úteis |
| Garantir confiabilidade da agenda | Reservas com dupla confirmação | ≥ 80% |
| Garantir segurança | Denúncias tratadas no prazo | ≥ 90% em 5 dias úteis |
| Garantir qualidade do apoio | Apoios concluídos sem ocorrência | ≥ 85% |

> **DECISÃO PENDENTE 1:** estas metas fazem parte do produto ou são apenas referência interna? A fonte não define metas numéricas além dos RNFs.

### 1.4 Personas e responsabilidades

| Perfil | Pode | Não pode |
|---|---|---|
| Visitante | Ler termos/política, iniciar cadastro, recuperar acesso | Ver catálogo, criar solicitação, acessar chat |
| Assistido | Criar solicitação, reservar, conversar, confirmar, avaliar, denunciar | Publicar solicitação sem mediação; ver dados completos antes da reserva |
| Familiar/responsável autorizado | Acompanhar a conta e criar solicitação em nome do Assistido, se autorizado no cadastro | Criar um novo tipo de usuário ou acessar dados sem autorização |
| Apoiador | Publicar agenda, receber reservas, conversar, confirmar, avaliar, denunciar | Oferecer apoio sem verificação; ver condição crônica do Assistido |
| Mediador | Validar contas/solicitações/agendas, tratar denúncias, aplicar sanções, gerir parâmetros e cadastros-base, consultar auditoria | Alterar avaliação, excluir registros, acessar dado sensível sem justificativa registrada |
| Operação técnica `[PROPOSTA]` | Infraestrutura, backup, monitoramento | Acessar dados de saúde sem justificativa e registro |

## 2. Jornadas ponta a ponta

### 2.1 Cadastro e aprovação — Assistido

1. Visitante aceita termos, política de privacidade e consentimento específico de saúde (LGPD art. 11, separado dos demais termos).
2. Informa dados pessoais, CPF, nascimento, contato, endereço, foto de perfil (obrigatória) e condições crônicas.
3. Conta criada em **Em análise**; e-mail de confirmação enviado; mediação notificada.
4. Mediador valida a identidade (RF016) e aprova ou recusa, com justificativa.
5. Aprovada, a conta passa a **Ativa**. Antes da primeira solicitação, a identidade do Assistido precisa estar verificada (documento + selfie).
6. Notificação em cada transição.

> **DECISÃO PENDENTE 2:** a fonte nega login à conta Em análise (RF002), mas exige documentos antes da primeira solicitação (RF016) e permite acompanhar o andamento. Definir como o Assistido envia os documentos e acompanha a análise.

### 2.2 Cadastro e verificação — Apoiador

1. Cadastro igual ao do Assistido, mais especialidades, formação/registro, modalidade, raio de atuação e aceite do Código de Conduta.
2. Envio obrigatório de foto de perfil (no cadastro) e, para verificação, documento com foto, selfie e registro profissional quando a especialidade for regulamentada.
3. Mediador valida documentos e conta; especialidades regulamentadas só ficam ativas após conferência do registro.
4. Apenas Apoiador **Ativo**, verificado ("selo Verificado") e com horário **Disponível** aparece no catálogo.
5. A agenda só pode ser publicada depois da aprovação da conta.

### 2.3 Solicitação e análise

1. Assistido (ou familiar autorizado) cria solicitação: tipo de apoio, título, descrição, dias e horários possíveis, duração estimada, local, modalidade, prioridade sugerida e declaração de necessidade.
2. Status inicial **Em análise**; nada é visível a Apoiadores.
3. Mediador aprova (com prioridade final), solicita ajuste ou recusa, com justificativa.
4. Aprovada → **Aberta**, visível no catálogo de solicitações; data de expiração definida.
5. Assistido pode contestar a recusa uma única vez; a contestação é analisada por outro mediador, quando possível.

### 2.4 Descoberta, reserva e contato

1. Assistido filtra apoiadores por especialidade, dia/período, modalidade, distância, avaliação mínima e selo de verificação.
2. Abre o perfil público (dados minimizados) e escolhe um horário.
3. Reserva atômica: horário sai do catálogo, solicitação passa a **Reservada**, dados permitidos são liberados.
4. Apoiador confirma ciência da reserva em até 24 h (não é nova aceitação).
5. Chat é aberto automaticamente e notificações são enviadas.

### 2.5 Atendimento, conclusão e avaliação

1. As partes combinam detalhes pelo chat.
2. Após o atendimento, uma parte registra "Apoio realizado"/"Recebi o apoio"; a solicitação passa a **Aguardando confirmação**.
3. A outra parte confirma em até 72 h; com as duas confirmações, a reserva e a solicitação viram **Concluída**.
4. Se a outra parte não responder em 72 h, o sistema envia lembretes e, esgotado o prazo, **conclui registrando "confirmado por uma das partes"** (conforme RF012).
5. Cada parte avalia uma vez em até 7 dias: nota 1–5, comentário opcional e critérios opcionais (pontualidade, comunicação, cuidado e respeito).
6. Notas 1–2 **com comentário** geram sinalização para a mediação.

> **DECISÃO PENDENTE 3:** o item 4 contradiz a regra "a conclusão exige confirmação das duas partes". Confirmar o comportamento no vencimento das 72 h.

### 2.6 Cancelamento, falta e denúncia

| Evento | Regra | Efeito |
|---|---|---|
| Cancelamento com ≥ 24 h | Automático | Horário volta a **Disponível**; solicitação volta a **Aberta**; registro com autor e motivo |
| Cancelamento com < 24 h | Análise da mediação | Horário fica **Bloqueado** até decisão; possível ocorrência |
| Em andamento | Só pela mediação, após ocorrência | Ocorrência registrada; mediação decide |
| Não comparecimento | Reportado pela contraparte | Ocorrência; afeta reputação; mediação decide |
| Suspensão preventiva (3 denunciantes distintos em 30 dias) | Automática e provisória | Conta fica **Suspensa**; horários futuros ficam **Bloqueados**; reservas ativas vão para a fila da mediação; partes são notificadas sem revelar denunciantes |
| Denúncia improcedente | Mediador encerra | Conta volta ao estado anterior; histórico preservado |

> **DECISÃO PENDENTE 4:** na suspensão preventiva, as reservas ativas são canceladas automaticamente ou aguardam decisão da mediação? A fonte não define. A sugestão acima preserva o atendimento e só bloqueia novos compromissos.

## 3. Máquinas de estado

### 3.1 Conta (`usuario.status`) — status oficiais da fonte

```text
Em análise ──aprovar──> Ativa ──suspender──> Suspensa ──reativar──> Ativa
     │                    │
     │                    └──desativar (próprio usuário)──> Desativada ──> eliminação/anonimização
     ├──recusar──> ? (status não definido na fonte)
     └──────────────────────────────────────────────> Bloqueada (definitivo)
```

Regras (fonte):

- **Em análise**: acesso negado com orientação para contatar a mediação (RF002).
- **Suspensa**: acesso negado com orientação para contatar a mediação (RF002); agenda oculta automaticamente (RF004).
- **Bloqueada**: acesso negado (RF002).
- **Desativada**: login bloqueado; dados mantidos até expurgo/anonimização (RF003).
- Cadastros não são excluídos fisicamente; usa-se o campo Status (RF001).
- `is_active` do Django não substitui `status` (ADR 0002).
- Toda transição grava ator, motivo, data/hora em auditoria (RF001/RF015/RNF012).

Detalhamento `[PROPOSTA]` (não altera os 5 status oficiais):

- Tratar **recusada** como registro de auditoria associado a Bloqueada, ou criar status próprio — ver Decisão 5.
- `em_correcao`/`reprovada`, usados em versões anteriores deste documento, **não existem na fonte** e ficam como extensão opcional, a aprovar.

> **DECISÃO PENDENTE 5:** para qual status vai a conta recusada pelo mediador? (Bloqueada, Desativada ou novo status "Recusada".)

> **DECISÃO PENDENTE 6:** como o usuário suspenso ou bloqueado contesta a sanção, se o login é negado? (RF015 garante contestação.)

### 3.2 Solicitação — status oficiais da fonte

```text
Em análise ──aprovar──> Aberta ──reservar──> Reservada ──registrar realização──> Aguardando confirmação
     │                    │                       │                                      │
     ├─solicitar ajuste──> Aguardando ajuste     ├─cancelar (≥24h)──> Aberta            ├─2ª confirmação──> Concluída
     │        └─reenviar──> Em análise            └─cancelar (<24h)──> análise           └─72h (RF012)──> Concluída com 1 confirmação
     ├─recusar──> Recusada                                                                    │
     └──────────────────────────────────────────────cancelar──> Cancelada                    └──> cancelada (mediação)
Aberta ──expirar──> Expirada
```

Regras (fonte):

- **Em análise**: não visível a Apoiadores; prazo de 24 h úteis com alerta, sem aprovação automática.
- **Aguardando ajuste**: Assistido recebe a orientação e reenvia.
- **Aberta**: pode ser cancelada ou ter horários/prioridade sugerida alterados; alteração relevante exige nova validação.
- **Reservada**: admite uma única reserva ativa; horário não pode ser editado, só cancelado ou remarcado.
- **Aguardando confirmação**: uma parte já registrou a realização.
- **Concluída, Cancelada, Expirada, Recusada**: não podem ser editadas.
- Status citados na fonte: Em análise, Aguardando ajuste, Aberta, Reservada, Aguardando confirmação, Concluída, Cancelada, Expirada, Recusada.
- "Em andamento" (RF009) não consta da lista; deve ser tratado como sinônimo de Reservada — ver Decisão 7.

> **DECISÃO PENDENTE 7:** RF009 cita "Em andamento" e a proposta de banco já o prevê como status (`em_andamento`), mas RF001/RF012 não o listam. Confirmar quando a solicitação entra nesse estado (ao iniciar o horário?) e qual o estado após as 72 h sem resposta (Concluída com uma confirmação × Expirada). Observação: "Aguardando confirmação" (RF012) não existe na proposta de banco e precisa ser adicionado.

> **DECISÃO PENDENTE 8:** granularidade de "dias e horários possíveis" (data + manhã/tarde/noite, faixa de horário ou horário exato).

### 3.3 Horário — status oficiais da fonte

```text
Disponível ──reservar──> Reservado ──realizado──> Realizado
     │                        │
     │                        └─cancelar (≥24h)──> Disponível
     ├─bloquear (mediação, com justificativa)──> Bloqueado
     └─(gatilho não definido)──> Liberado
```

- Status: **Disponível, Reservado, Realizado, Liberado, Bloqueado**.
- Horário Reservado sai do catálogo e não pode ser editado nem removido; só cancelado/remarcado (RF004/RF008/RF009).
- Bloqueado só é alterado pela mediação (RF004/RF015).
- Edição de horário futuro e não reservado é permitida ao Apoiador (RF004).
- Horário no passado não pode ser publicado; recorrência não gera datas passadas.
- "Liberado" não tem gatilho definido na fonte — ver Decisão 9.

> **DECISÃO PENDENTE 9:** quando um horário passa a "Liberado"? A proposta de banco sugere "liberado quando volta à lista" e admite também `cancelado`; a fonte só lista o status. Confirmar se "Liberado" é sinônimo de Disponível ou um estado transitório.

### 3.4 Reserva `[PROPOSTA]` (a fonte não formaliza a máquina)

```text
criada (ciência pendente, 24 h) ──ciência do Apoiador──> ativa
   │                                                        │
   ├─sem ciência em 24h: lembrete + mediação notificada,    ├─1ª confirmação──> aguardando_confirmação
   │ reserva mantida                                       │        ├─2ª confirmação──> concluida
   ├─cancelar (≥24h)──> cancelada                           │        └─72h──> concluida_uma_confirmacao
   ├─cancelar (<24h)──> em_analise_mediacao                 │
   └─não comparecimento──> falta_registrada                 └─remarcar──> nova reserva / solicitação aberta
```

- A reserva é imediata, atômica e não depende de aceite do Apoiador (RF008).
- Uma reserva ativa por solicitação; uma reserva não cancelada por horário (RF008).
- A ciência em 24 h não é nova aceitação (RF008).
- Sem ciência: o sistema lembra, notifica a mediação e **mantém a reserva** até decisão (RF008).
- Cancelamento ≥ 24 h devolve o horário e a solicitação (RF009).
- Cancelamento < 24 h fica em análise; o horário só volta à lista se a mediação autorizar (RF009).

### 3.5 Documento de verificação

`pendente → em análise → aprovado | reprovado → expurgado`

- A fonte exige armazenamento protegido e exclusão após o prazo necessário à verificação (RF016).
- Reprovação exige motivo; reenvio cria novo registro; o anterior é mantido para auditoria até o expurgo.
- O resultado da verificação (aprovado/reprovado) é preservado mesmo após o expurgo do arquivo `[PROPOSTA]`.

### 3.6 Denúncia, sanção e contestação

```text
denuncia: recebida/Em análise → procedente | improcedente → arquivada
sancao:   ativa → cumprida | revogada (por contestação)
contestacao: aberta → em análise → deferida | indeferida
```

- Sanção sempre com tipo, motivo, prazo, mediador e vínculo com a denúncia/ocorrência (RF015).
- Contestação analisada por outro mediador, quando possível (RF006/RF015).
- A identidade do denunciante não é revelada (RF014).

## 4. Requisitos funcionais detalhados

Formato: objetivo, atores, campos, fluxo, exceções, regras, saídas, critérios de aceite e protótipo. **Todo o conteúdo desta seção reproduz a fonte**; complementos estão marcados.

### RF001 — Cadastro de usuário

**Objetivo:** permitir que uma pessoa crie conta pessoal como Assistido ou Apoiador, com uma única conta por CPF. Não existem contas de ONG, clínica, agência de cuidadores ou instituição.

**Atores:** Visitante.
**Pré-condições:** termos e política disponíveis; e-mail e CPF ainda não cadastrados.

**Campos comuns:**

| Campo | Regra |
|---|---|
| Perfil | seleção única Assistido \| Apoiador, obrigatório |
| Nome completo | texto, até 120 caracteres, obrigatório, mínimo 3 |
| Foto de perfil | JPG ou PNG, **obrigatória**, usada na verificação de identidade e exibida no perfil |
| CPF | máscara XXX.XXX.XXX-XX, obrigatório, único, dígito verificador; não editável após o cadastro |
| Data de nascimento | dd/mm/aaaa, obrigatório; maior de 18 anos |
| E-mail | texto, até 200 caracteres, obrigatório, único, formato válido |
| Celular/WhatsApp | máscara (XX) 9XXXX-XXXX, obrigatório; usado apenas para validação e avisos de segurança; **não é exibido ao outro usuário** |
| Senha e confirmação | mínimo 8 caracteres, obrigatório; hash com sal |
| Endereço | CEP com busca automática, logradouro, número, bairro, município, estado, complemento opcional; obrigatório |
| Termos + consentimento de saúde | checkbox obrigatório, com registro de data, hora e versão; consentimento de saúde separado (LGPD art. 11) |

**Campos do Assistido:**

| Campo | Regra |
|---|---|
| Condições crônicas | seleção múltipla de lista editável (diabetes, hipertensão, doença renal crônica, câncer, doenças respiratórias, doenças neurológicas, outra), mínimo 1, obrigatório |
| Condição não listada | texto, 100 caracteres, habilitado em "Outra" |
| Limitações de mobilidade ou necessidades específicas | texto, 300 caracteres, opcional |
| Contato de emergência | nome e telefone, opcional e recomendado |
| Autorização de familiar/responsável | nome e vínculo, opcional; permite acompanhar a conta, sem criar novo tipo de usuário |

**Campos do Apoiador:**

| Campo | Regra |
|---|---|
| Especialidades/tipos de ajuda | seleção múltipla (fisioterapia, fonoaudiologia, enfermagem, acompanhamento em consulta, transporte, compra de medicamentos, compras e mercado, tarefas domésticas, companhia e visita, apoio emocional, orientação administrativa, outro), mínimo 1, obrigatório |
| Formação e registro profissional | texto e número de registro; obrigatórios quando a especialidade for privativa de profissão regulamentada; opcionais nos demais casos |
| Modalidade | presencial, remoto ou ambos, obrigatório |
| Raio de atuação | inteiro em km (padrão 10), obrigatório para presencial |
| Agenda solidária | cadastrada depois da criação da conta, no RF004 |
| Código de Conduta do Apoiador | aceite obrigatório |

**Fluxo principal:**

1. Visitante escolhe "Criar conta" e o perfil.
2. Sistema exibe o formulário correspondente.
3. Visitante preenche os dados, aceita os termos e o consentimento de saúde.
4. Sistema valida campos, unicidade de CPF/e-mail e busca o endereço pelo CEP.
5. Sistema grava com status **Em análise** e envia e-mail de confirmação.
6. Mediador valida a identidade (RF016) e aprova ou recusa, com justificativa.
7. Aprovada, a conta passa a **Ativa**; o Apoiador só pode publicar agenda depois da aprovação.

**Exceções e mensagens:**

| Situação | Resposta |
|---|---|
| Campos obrigatórios vazios | "Campo(s) obrigatório(s) não preenchido(s)", com destaque |
| Dados inválidos | "Campo preenchido incorretamente" |
| CPF já cadastrado | "CPF já cadastrado" |
| E-mail já cadastrado | "E-mail já cadastrado" |
| CEP não encontrado | Campos liberados para digitação; município/estado passam a seleção com dados do IBGE |
| Menor de 18 anos | Cadastro bloqueado, com orientação |
| Registro profissional não localizado | Conta fica **Em análise**; mediador solicita complemento |
| Link de confirmação expirado | Permitir reenvio (fonte cita 24 h no fluxo alternativo) |

**Regras:** uma conta por CPF; Apoiador não pode ter duas contas nem conta institucional; CPF não editável; endereço do Assistido nunca público (RF007); são obrigatórios dados pessoais, condição crônica (Assistido), e-mail, CPF, foto e aceites; consentimento de saúde separado; sem exclusão física; mediador acessa só o necessário.

**Saídas:** "Cadastro enviado para análise", e-mail de confirmação e, após aprovação, acesso ao painel do perfil.
**Protótipo:** Telas 01A e 01B (seção 8.5).

**Critérios de aceite:**

- Duas contas não podem ter o mesmo CPF nem o mesmo e-mail (e-mail sem diferenciar caixa `[PROPOSTA]`).
- CPF não aparece em tela, log ou resposta após o cadastro; exibição mascarada só para a mediação `[PROPOSTA]`.
- Conta Em análise não acessa o painel (RF002).
- Cadastro de menor é recusado com mensagem clara.
- Foto obrigatória aceita apenas JPG/PNG; arquivo inválido é descartado.

### RF002 — Autenticação e recuperação de acesso

**Objetivo:** permitir entrada/saída segura e recuperação de senha.

**Entradas e validações:**

| Item | Regra |
|---|---|
| E-mail | obrigatório, formato válido, até 200 caracteres |
| Senha | obrigatória, mínimo 8 caracteres; não exibida em texto claro |
| Recuperação | e-mail cadastrado + nova senha; link válido por 1 hora, de uso único |
| Sessão | expira após 30 minutos de inatividade; "Sair" encerra imediatamente |

**Fluxo:** usuário informa e-mail e senha → sistema valida credenciais e cria sessão → direciona ao painel do perfil.

**Exceções:**

- Credenciais inválidas: mensagem genérica "E-mail ou senha incorretos", sem indicar o campo.
- Após 5 tentativas consecutivas: bloqueio temporário de 15 minutos.
- "Esqueci minha senha": link válido por 1 hora, de uso único.
- Conta **Suspensa, Bloqueada ou Em análise**: acesso negado com orientação para contatar a mediação.

**Regras:** sessão expira em 30 min de inatividade; "Sair" encerra imediatamente; 2FA (e-mail/SMS) é desejável para versão posterior; o sistema não revela se o e-mail existe.

**Saídas:** acesso ao painel ou mensagem de erro.
**Protótipo:** Tela 02.

**Critérios de aceite:**

- Nenhuma resposta permite descobrir se um e-mail existe.
- Após 30 minutos sem requisição autenticada, a próxima ação exige novo login.
- 5 tentativas falhas bloqueiam temporariamente mesmo com senha correta.
- Logout invalida a sessão no servidor.

> **DECISÃO PENDENTE 10:** 2FA e "sair de todos os dispositivos" ficam na primeira versão ou no backlog? A fonte só exige 2FA como desejável futura.

### RF003 — Perfil, preferências e privacidade

**Objetivo:** consultar e atualizar dados, preferências, especialidades e privacidade; desativar a conta e solicitar eliminação de dados.

**Campos editáveis:**

| Grupo | Campos |
|---|---|
| Dados pessoais | nome, celular/WhatsApp, endereço e foto; **CPF não editável** |
| Assistido | condições crônicas, limitações, contato de emergência e autorização de familiar/responsável |
| Apoiador | especialidades, formação, registro profissional, modalidade, raio de atuação e preferências de notificação |
| Segurança | alteração de senha (senha atual + confirmação da nova); alteração de e-mail com confirmação por link |
| Privacidade | categorias de notificação e solicitação de eliminação de dados (LGPD) |

**Fluxo:** acessa "Meu perfil" → sistema exibe os dados (exceto senha) → altera campos permitidos e salva → sistema valida, grava e registra no histórico (quem, o quê, data e hora).

**Fluxos alternativos:** alterar senha exige senha atual e nova duas vezes; alterar e-mail exige confirmação por link no novo endereço; desativar conta pede confirmação e exige cancelar solicitações/reservas em andamento (RF009); pedido de eliminação é registrado e atendido pela mediação no prazo definido, mantendo o que a lei obrigar e anonimizando o restante.

**Regras:** CPF imutável; Assistido edita condições, necessidades, contato de emergência e autorização; Apoiador edita especialidades, formação, modalidade, raio e preferências (agenda no RF004); alterações de especialidade/registro passam por nova validação do mediador antes de voltar ao catálogo; todo histórico é mantido.

**Saídas:** "Dados atualizados com sucesso" ou mensagens de validação.

**Critérios de aceite:**

- Nenhuma rota permite alterar CPF após o cadastro.
- Toda alteração sensível gera auditoria com antes/depois mascarado `[PROPOSTA]`.
- Desativar a conta impede novos logins e remove a agenda do catálogo.

> **DECISÃO PENDENTE 11:** o usuário pode reativar a própria conta? Em quanto tempo? A fonte só prevê a desativação.

### RF004 — Agenda solidária do Apoiador

**Objetivo:** publicar dias/horários realmente disponíveis, com especialidade e modalidade de cada horário. Base do modelo "lista de disponibilidade", não despacho automático.

**Campos:**

| Campo | Regra |
|---|---|
| Data | dd/mm/aaaa, obrigatória, futura |
| Hora de início/fim | obrigatórias; duração mínima 30 min e máxima 4 h por horário (parâmetro) |
| Especialidade/tipo de ajuda | seleção única entre as cadastradas no perfil, obrigatória |
| Modalidade | presencial, remoto ou a combinar, obrigatória |
| Local de referência | endereço do perfil ou ponto de encontro, obrigatório para presencial |
| Raio de deslocamento | km, herdado do perfil, editável para o horário |
| Recorrência | única, semanal ou dias da semana selecionados, opcional |
| Observações | texto, **200 caracteres**, opcional (ex.: "levar documento com foto") |
| Status do horário | definido pelo sistema: Disponível, Reservado, Realizado, Liberado, Bloqueado |

**Fluxo:** acessa "Minha agenda" → "Adicionar horário" → informa data, início, fim, especialidade, modalidade e local → sistema valida conflitos e limites diários → grava como **Disponível** e publica no catálogo (RF007) → pode editar/remover horários futuros não reservados.

**Exceções:**

| Situação | Resposta |
|---|---|
| Conflito com outro horário | "Você já tem um horário nesse período" |
| Data no passado | "Informe uma data futura" |
| Horário já reservado | não pode ser editado nem removido; usar cancelamento (RF009) com justificativa |
| Limite diário atingido | sistema informa o limite configurado |
| Conta suspensa | agenda oculta automaticamente |

**Regras:** horário publicado é compromisso e sai da lista ao ser reservado; Apoiador pode bloquear horários futuros com justificativa, desde que não reservados; recorrência não cria horários no passado e pode ser encerrada a qualquer momento; agenda mostra Reservado/Realizado sem dados sensíveis antes da reserva; não é permitido publicar horário em especialidade que não possui.

**Saídas:** horário publicado, editado, bloqueado ou removido; catálogo atualizado.
**Protótipo:** Telas 08A e 08B.

**Critérios de aceite:**

- Criar recorrência de 4 semanas gera exatamente os horários esperados, sem datas passadas.
- O catálogo nunca mostra horário Reservado, Bloqueado ou com data passada.
- Dois dispositivos editando o mesmo horário: o segundo recebe conflito `[PROPOSTA: bloqueio otimista por versão]`.

> **DECISÃO PENDENTE 12:** a agenda passa por validação prévia da mediação (RF015 diz que agendas são verificadas antes da publicação) ou é publicada direto (RF004)? Definir o fluxo.

### RF005 — Criação de solicitação de apoio

**Objetivo:** registrar necessidade de apoio para validação do mediador e escolha de apoiador disponível.

**Campos:**

| Campo | Regra |
|---|---|
| Tipo de apoio | seleção única, obrigatório (fisioterapia, fonoaudiologia, enfermagem, acompanhamento em consulta, transporte, compra de medicamentos, compras e mercado, tarefas domésticas, companhia e visita, apoio emocional, orientação administrativa, outro) |
| Título | texto, 80 caracteres, obrigatório |
| Descrição da necessidade | texto, 1.000 caracteres, obrigatório; explicar o que precisa e por quê |
| Dias e horários possíveis | seleção de um ou mais períodos, obrigatório |
| Duração estimada | até 1 h, 1 a 3 h, mais de 3 h; opcional |
| Local | endereço do perfil ou outro endereço por CEP; obrigatório para presencial |
| Modalidade | presencial, remoto ou a combinar, obrigatório |
| Prioridade sugerida | Baixa, Média ou Alta, obrigatório; mediador confirma ou altera (RF006) |
| Observações de cuidado e acessibilidade | texto, 500 caracteres, opcional; visível ao Apoiador só após a reserva |
| Declaração de necessidade | checkbox confirmando que a informação é verdadeira, obrigatório |
| Status | definido pelo sistema (seção 3.2) |

**Fluxo:** seleciona "Pedir apoio" → sistema exibe formulário e aviso de que não é serviço de emergência → preenche e confirma a declaração → sistema valida, grava **Em análise** e define a data de expiração → notifica o mediador (RF010) → após aprovação (RF006) passa a **Aberta** e o Assistido pode consultar apoiadores.

**Exceções:** campos vazios/inválidos → mensagens de validação; data no passado → "Informe uma data futura"; texto com pedido de dinheiro/transferência/dados bancários → alerta e bloqueio preventivo; conta suspensa → não pode criar solicitação.

**Regras:** qualquer pessoa com doença crônica pode solicitar, sem vínculo com ONG/clínica/instituição; familiar autorizado pode acompanhar e criar em nome do Assistido; **não há limite máximo de solicitações ativas nem meta de atendimentos por Apoiador**; lista de tipos de apoio é cadastro-base do mediador (RF015); endereço exato e observações de cuidado só após a reserva (RF008); prioridade final é do mediador; solicitações não são excluídas (Cancelada/Expirada/Recusada preservam histórico); prioridade Alta exibe "Esta plataforma não substitui serviços de emergência. Em risco de vida, ligue 192 (SAMU)".

**Saídas:** "Solicitação enviada para análise" e registro em "Minhas solicitações" (RF017).
**Protótipo:** Telas 04A e 04B.

**Critérios de aceite:**

- Solicitação criada não aparece em nenhuma listagem pública antes da aprovação.
- Aviso do SAMU aparece em toda tela de prioridade Alta, inclusive na edição.
- Data de expiração é gravada na criação e ajustável pela mediação.
- Pedido de dinheiro identificado bloqueia a publicação e alerta a mediação.

> **DECISÃO PENDENTE 13:** existe limite de solicitações ativas por Assistido? RF005 diz que não; RF009 e RNF010 citam limite configurável. Resolver a contradição.

> **DECISÃO PENDENTE 14:** como identificar automaticamente "texto com pedido de dinheiro/dados bancários"? Regra simples, lista de termos ou revisão humana?

### RF006 — Análise e validação da solicitação pelo mediador

**Objetivo:** garantir que toda solicitação publicada corresponda a pessoa e necessidade reais e definir prioridade. É o controle central do fluxo.

**Campos da análise:**

| Campo | Regra |
|---|---|
| Parecer | Aprovar \| Solicitar ajuste \| Recusar |
| Prioridade | Baixa \| Média \| Alta, obrigatória na aprovação |
| Justificativa | texto, 500 caracteres, obrigatório em "Solicitar ajuste" e "Recusar"; opcional na aprovação |
| Observações internas | texto, 500 caracteres, visível apenas à mediação |
| Data de expiração | calculada pelo sistema e ajustável pelo mediador |

**Fluxo:** mediação acessa a fila "Solicitações em análise", **ordenada por data de envio** → confere conta ativa e identificação → confere coerência, ausência de pedido de dinheiro e compatibilidade com tipos de apoio → define prioridade e parecer → aprova (Aberta + notificação), solicita ajuste (Aguardando ajuste + orientação) ou recusa (Recusada + justificativa + orientação de recurso).

**Exceções:** dados inconsistentes → ajuste/recusa com motivo; suspeita de fraude/perfil falso → recusar, abrir ocorrência e, se necessário, suspender a conta (RF014/RF015); solicitação duplicada → orientar a acompanhar a existente; mediador ausente → fila permanece, prazo monitorado, **sem aprovação automática**.

**Regras:** nenhuma solicitação é publicada automaticamente; prioridade é decisão humana; prazo de 24 horas úteis com alerta quando excedido; toda decisão gera notificação e auditoria (quem, quando, parecer e justificativa); mediador não aprova conta suspensa/bloqueada; **Assistido pode contestar a recusa uma única vez, analisada por outro mediador quando possível**.

**Saídas:** solicitação aprovada, devolvida para ajuste ou recusada; notificações e auditoria.
**Protótipo:** Tela 11.

**Critérios de aceite:**

- Não existe caminho de código que publique solicitação sem aprovação humana.
- Mediação vê o histórico completo de análises anteriores.
- Solicitação fora do prazo aparece destacada.
- Contestação única é registrada e encaminhada a outro mediador.

> **DECISÃO PENDENTE 15:** o que acontece se o prazo de 24 h úteis estourar? Alerta apenas (regra da fonte), escalonamento ou aprovação tácita (não recomendada)?

> **DECISÃO PENDENTE 16:** calendário de "horas úteis": seg–sex, 9h–18h, feriados nacionais? A fonte não define.

### RF007 — Catálogo de apoiadores disponíveis e filtros

**Objetivo:** permitir que o Assistido encontre apoiadores compatíveis e escolha livremente um horário, sem escolha automática pelo sistema.

**Filtros:**

| Filtro | Regra |
|---|---|
| Tipo de apoio/especialidade | seleção múltipla, iniciada pelo tipo da solicitação |
| Dia e período | data específica, manhã, tarde ou noite; opção "compatível com meus horários" |
| Modalidade | presencial, remoto ou a combinar |
| Distância | raio em km a partir do endereço do Assistido (padrão 10 km) |
| Avaliação | nota mínima, opcional |
| Selo de verificação | somente verificados, opcional |

**Fluxo:** acessa "Apoiadores disponíveis" a partir da solicitação aprovada → sistema lista apenas apoiadores ativos, verificados, com horário Disponível compatível → aplica/altera filtros → lista atualiza em tempo real → abre perfil resumido e horário desejado → "Quero este horário" (RF008).

**Exceções:** nenhum resultado → "Nenhum apoiador disponível com esses filtros. Tente ampliar o raio ou o período"; horário reservado enquanto a tela estava aberta → sistema informa e atualiza; apoiador suspenso durante a consulta → perfil e horários desaparecem.

**Regras:** visíveis — primeiro nome, foto (opcional na exibição), especialidades, selo de verificação, nota média, número de apoios concluídos, dias/horários disponíveis, modalidade, bairro/município e distância aproximada; **não visíveis antes da reserva** — sobrenome, CPF, telefone, e-mail, endereço exato, condição crônica detalhada e observações de cuidado (após reserva, vale RF008); ordenação padrão — horário mais próximo, menor distância e melhor avaliação, alterável pelo Assistido; paginação de 10; o catálogo é vitrine, não despacho; perfis bloqueados não aparecem.

**Saídas:** lista filtrada e ordenada.
**Protótipo:** Telas 05 e 06.

**Critérios de aceite:**

- Busca vazia retorna mensagem orientativa, não erro.
- Nenhum dado proibido aparece no HTML do catálogo (teste automatizado por campo).
- Apoiador suspenso some imediatamente do catálogo.
- Distância é aproximada/arredondada (ex.: "cerca de 3 km") `[PROPOSTA]`.

> **DECISÃO PENDENTE 17:** o "selo de verificação" é concedido após RF016? A fonte diz que o perfil aprovado recebe o selo "Verificado" (RF016), mas não especifica critérios para profissionais.

> **DECISÃO PENDENTE 18:** "Apoiadores do próprio usuário não aparecem" — definir o que isso significa.

### RF008 — Reserva de horário e vínculo de apoio

**Objetivo:** formalizar o vínculo com o Apoiador, bloquear o horário e liberar os dados necessários ao atendimento.

**Dados liberados após a reserva:**

| Para | Dados |
|---|---|
| Apoiador | nome completo, **e-mail**, endereço exato (se presencial), observações de cuidado e canal de conversa |
| Assistido | nome completo, **e-mail**, especialidade, modalidade, local aproximado antes da confirmação e endereço/orientações depois da reserva |
| Nunca liberados | CPF, telefone e dados de saúde detalhados |

**Fluxo:** seleciona "Quero este horário" → sistema exibe resumo (apoiador, especialidade, data, hora, modalidade, local aproximado e o que será compartilhado) → Assistido confirma → **em uma única transação**, verifica disponibilidade, marca **Reservado**, altera a solicitação para **Reservada** e remove do catálogo → notifica o Apoiador imediatamente → libera dados e chat (RF011) → Apoiador confirma ciência em até 24 h (não é nova aceitação).

**Exceções:** horário indisponível no momento → "Este horário acabou de ser reservado por outra pessoa", lista atualizada, nada gravado parcialmente; Apoiador não confirma ciência em 24 h → sistema lembra, notifica a mediação e **mantém a reserva** até decisão, com o Assistido informado; Apoiador solicita cancelamento → RF009; Assistido desiste antes do atendimento → RF009.

**Regras:** reserva imediata e atômica; nunca duas reservas para o mesmo horário; uma reserva ativa por solicitação; telefone não é exibido, contato é exclusivamente pelo chat; liberação de dados só após a confirmação da reserva; se remoto, endereço não é liberado (apenas nome e orientações); horário reservado não pode ser editado, só cancelado ou remarcado (RF009); a reserva não gera pagamento.

**Saídas:** reserva confirmada, horário bloqueado, dados liberados, chat habilitado e notificações.
**Protótipo:** Telas 07A e 07B.

**Critérios de aceite:**

- Duas requisições simultâneas no mesmo horário: exatamente uma reserva.
- Reserva não é criada se a solicitação não pertence ao Assistido ou não está Aberta.
- Após a reserva, o horário desaparece do catálogo imediatamente.
- Sem ciência em 24 h, a reserva continua ativa e a mediação é notificada.

> **DECISÃO PENDENTE 19:** o e-mail é liberado após a reserva (RF008) ou o contato é exclusivamente pelo chat (RF011/RS003)? Definir a exceção de "contato de segurança".

### RF009 — Cancelamento e remarcação

**Objetivo:** permitir que Assistido ou Apoiador cancele ou remarque, devolvendo o horário quando possível e preservando o histórico.

**Campos:**

| Campo | Regra |
|---|---|
| Motivo | seleção obrigatória: imprevisto pessoal, problema de saúde, mudança de endereço, não consegui contato, outro |
| Justificativa | texto, 500 caracteres, obrigatório quando o motivo for "outro" ou o cancelamento ocorrer com menos de 24 h |
| Novo horário | seleção entre horários disponíveis do mesmo Apoiador ou de outros, na remarcação |

**Regras por situação:**

| Situação | Regra |
|---|---|
| Em análise | Assistido pode cancelar livremente; solicitação encerrada |
| Aberta (sem reserva) | Assistido pode cancelar ou alterar horários e prioridade sugerida; alteração relevante exige nova validação |
| Reservada | Assistido ou Apoiador cancelam com motivo; ≥ 24 h o horário volta a Disponível automaticamente; < 24 h o cancelamento é registrado e analisado pela mediação, e o horário só volta se a mediação autorizar |
| Em andamento | cancelamento só pela mediação, após ocorrência (RF017) |
| Concluída/Cancelada/Expirada/Recusada | não podem ser editadas |

**Fluxo de cancelamento:** seleciona "Cancelar" → sistema pede motivo e confirmação → status **Cancelada**, registra autor/data/hora/motivo → se houver reserva, libera o horário conforme a antecedência e notifica a contraparte → chat passa a somente leitura → mediação é notificada quando < 24 h ou reincidência.

**Fluxo de remarcação:** seleciona "Remarcar" → sistema mostra horários disponíveis do mesmo Apoiador ou de outros → escolha de novo horário segue RF008 e o anterior é liberado → se não houver horário compatível, a solicitação volta a **Aberta**.

**Regras:** o Assistido pode criar outra solicitação em outro dia/horário, respeitando o limite de solicitações ativas; cancelamentos repetidos em curto período são sinalizados à mediação; não há multa, taxa ou pagamento (consequência reputacional e de mediação); toda alteração mantém histórico com data, hora e autor; Apoiador que cancela com < 24 h sem justificativa aceitável recebe registro negativo e pode ter a agenda suspensa.

**Saídas:** solicitação cancelada/remarcada; horário liberado; notificações e auditoria.

**Critérios de aceite:**

- Cancelamento com 23h59min não libera o horário automaticamente.
- Histórico mostra quem cancelou, quando e por quê.
- Remarcação preserva o vínculo com a solicitação e não cria duplicidade.

> **DECISÃO PENDENTE 20:** a remarcação consome/transfere a reserva ativa ou cria uma nova? A fonte diz que a escolha segue o RF008, mas não define o estado intermediário.

> **DECISÃO PENDENTE 21:** "cancelamentos repetidos em curto período" precisa de limite numérico (sugestão: 3 em 90 dias) e de destino (sinalização × sanção).

### RF010 — Notificações

**Objetivo:** avisar sobre eventos relevantes, em especial reservas, cancelamentos e solicitações aguardando validação.

**Eventos que geram notificação:**

| Papel | Eventos |
|---|---|
| Apoiador | novo horário reservado, reserva cancelada ou remarcada, nova mensagem, solicitação de confirmação de ciência, lembrete de atendimento |
| Assistido | solicitação aprovada, devolvida ou recusada, horário reservado, cancelamento pelo Apoiador, nova mensagem, confirmação de conclusão pendente, solicitação prestes a expirar |
| Mediador | nova conta para validar, nova solicitação em análise, prazo de análise excedido, nova denúncia, ocorrência aberta, cancelamento tardio |

**Canais:** central interna (essencial), e-mail (importante), push no navegador (desejável).

**Regras:** reservas e cancelamentos notificados imediatamente; usuário ativa/desativa categorias, **exceto avisos obrigatórios de segurança, termos e mediação**; avisos de novos horários podem ser agrupados, reservas/cancelamentos nunca; cada notificação registra lida/não lida; notificações não substituem chat nem acompanhamento na tela de solicitações.

**Saídas:** notificações exibidas e/ou enviadas pelos canais habilitados.
**Protótipo:** Tela 03.

**Critérios de aceite:**

- Desativar uma categoria impede o envio, exceto obrigatórias.
- Notificação de reserva/cancelamento é gerada em até 1 minuto do evento (RNF004).
- Falha de e-mail não impede a central interna nem a reserva (envio assíncrono) `[PROPOSTA]`.

> **DECISÃO PENDENTE 22:** push na primeira versão ou somente backlog? E qual provedor de e-mail? (A fonte trata push como desejável.)

### RF011 — Canal de conversa (chat)

**Objetivo:** permitir que Assistido e Apoiador combinem detalhes dentro da plataforma, sem expor telefone ou redes sociais.

**Campos e limites:**

| Item | Regra |
|---|---|
| Mensagem | texto, até 1.000 caracteres, obrigatório para envio |
| Anexos | não permitidos na v1 (sem imagens, arquivos, áudios ou chamadas) |
| Histórico | ordem cronológica, com data e hora; indicador de lida |
| Ações por mensagem | denunciar (RF014) |
| Ações por conversa | bloquear usuário e registrar ocorrência |

**Fluxo:** acessa o chat a partir da solicitação → sistema exibe o histórico cronológico → usuário digita e envia (até 1.000) → sistema grava com data/hora e notifica o destinatário (RF010).

**Regras:** chat só existe entre as duas partes de uma reserva; após conclusão **ou cancelamento**, passa a somente leitura por 30 dias e depois é arquivado para auditoria; cada mensagem tem "Denunciar"; cada usuário pode bloquear o outro, com registro comunicado à mediação; imagens/arquivos/áudios/chamadas fora da v1; mensagens protegidas, lidas apenas pelas partes e, em caso de denúncia, pela mediação; é proibido compartilhar telefone/redes sociais/pedir dinheiro — o e-mail do perfil pode ser usado como contato de segurança; o sistema alerta e pode ocultar a mensagem; conteúdo não vai para log de aplicação `[PROPOSTA]`.

**Saídas:** mensagens exibidas e notificações de nova mensagem.
**Protótipo:** Tela 09.

**Critérios de aceite:**

- Nenhuma rota permite ler chat de reserva da qual o usuário não participa.
- Após 30 dias do fim, novas mensagens são recusadas e o histórico segue legível à mediação.
- Indicador de leitura reflete a última leitura da contraparte.
- Mensagem com pedido de dinheiro/telefone é alertada e pode ser ocultada.

### RF012 — Conclusão do apoio (dupla confirmação)

**Objetivo:** registrar que o apoio foi realizado, com confirmação das duas partes, e liberar a avaliação.

**Fluxo:** após o atendimento, Apoiador seleciona "Apoio realizado" ou Assistido seleciona "Recebi o apoio" → solicitação passa a **Aguardando confirmação** e a outra parte é notificada → a outra parte confirma → status **Concluída**, chat em modo leitura e avaliação liberada (RF013).

**Fluxos alternativos:**

- A outra parte discorda ou informa que o apoio não ocorreu → ocorrência para a mediação (RF017).
- A outra parte não responde em **72 horas** → sistema envia lembretes e, esgotado o prazo, **conclui registrando "confirmado por uma das partes"**.
- Atendimento não realizado por desistência/ausência → a parte presente registra ocorrência; a mediação decide.

**Regras:** a conclusão exige confirmação das duas partes; só atendimentos concluídos com confirmação entram no indicador de sucesso; prazo de 72 h é parâmetro configurável; conclusão não gera pagamento nem transferência.

**Saídas:** solicitação concluída e avaliação liberada.
**Protótipo:** Tela 10.

**Critérios de aceite:**

- Uma única confirmação não conclui a reserva **antes** de 72 h.
- Confirmação após o prazo é recusada com orientação para a mediação `[PROPOSTA]`.
- Conclusão dispara avaliação para as duas partes e atualiza contadores.
- Sem resposta em 72 h, o desfecho segue a decisão pendente 3.

> **DECISÃO PENDENTE 23 (crítica):** "exige duas confirmações" × "conclui com uma após 72 h". Escolher um comportamento e refletir em RF012, RF017, máquina de estados e indicadores.

> **DECISÃO PENDENTE 24:** o que conta como "sucesso" no perfil: apenas dupla confirmação ou também ausência de ocorrência?

### RF013 — Avaliação entre Assistido e Apoiador

**Objetivo:** construir confiança na rede por avaliações mútuas após cada atendimento.

**Campos:**

| Campo | Regra |
|---|---|
| Nota | inteiro de 1 a 5, obrigatório |
| Comentário | texto, 300 caracteres, opcional |
| Critérios | opcional: pontualidade, comunicação, cuidado e respeito |

**Fluxo:** sistema solicita avaliação após a conclusão → usuário informa nota e comentário e envia → sistema registra e atualiza média e total do avaliado.

**Regras:** cada parte avalia uma única vez por atendimento, em até **7 dias** após a conclusão; avaliação não pode ser editada após envio; perfil exibe média, total de avaliações e quantidade de apoios concluídos; avaliações com nota **1 ou 2 e comentário** são sinalizadas à mediação; comentários ofensivos podem ser ocultados (RF015); a avaliação compõe a reputação e é considerada nas decisões da mediação.

**Saídas:** avaliação registrada e reputação atualizada.

**Critérios de aceite:**

- Segunda avaliação da mesma parte é recusada.
- Avaliação fora do prazo é recusada.
- Ocultar comentário não altera a média.
- Nota 1–2 sem comentário não é sinalizada (conforme a fonte) — ver Decisão 25.

> **DECISÃO PENDENTE 25:** notas 1–2 sem comentário também devem ser sinalizadas? A fonte exige comentário para sinalizar.

### RF014 — Denúncia de usuário ou conteúdo

**Objetivo:** permitir reportar comportamento/conteúdo inadequado, perfil falso, pedido de dinheiro ou risco à segurança.

**Campos:**

| Campo | Regra |
|---|---|
| Alvo | perfil, solicitação, reserva ou mensagem (preenchido automaticamente) |
| Motivo | comportamento inadequado, assédio, solicitação de dinheiro ou golpe, perfil falso, conteúdo ofensivo, risco à segurança, outro |
| Descrição | texto, **500 caracteres**, opcional |

**Fluxo:** seleciona "Denunciar" no alvo → sistema exibe formulário já associado → confirma → registra denúncia com **protocolo** e status **Em análise**, notifica a mediação → **anexa automaticamente o trecho relevante** (mensagem e contexto).

**Regras:** denúncia não bloqueia a conta automaticamente (análise do mediador, RF015); exceção — denúncias de **3 ou mais pessoas diferentes em 30 dias** geram suspensão preventiva até a análise (parâmetro); denunciante informado do resultado final sem expor dados do denunciado; identidade do denunciante não é revelada; denúncias falsas e repetidas podem gerar sanção ao denunciante.

**Saídas:** número de protocolo e confirmação de envio.

**Critérios de aceite:**

- Terceira denúncia distinta em 30 dias suspende preventivamente e notifica a mediação.
- Denunciante recebe protocolo e resultado.
- Denúncia improcedente não deixa marca visível no perfil.
- Trecho da mensagem é anexado automaticamente quando o alvo é mensagem.

> **DECISÃO PENDENTE 26:** a suspensão preventiva é automática (fonte) ou proposta ao mediador? Automática pode ser abusada por denúncias coordenadas. A fonte já define automática; a decisão é mantê-la.

### RF015 — Gestão administrativa e mediação

**Objetivo:** gerenciar contas, solicitações, agendas, denúncias, ocorrências e cadastros-base, além de acompanhar indicadores.

**Funcionalidades:**

| Área | Funções |
|---|---|
| Verificação prévia | analisar e autorizar cadastros, perfis, solicitações, agendas e conteúdos antes da publicação; fiscalizar continuamente |
| Contas | buscar por nome, e-mail, CPF, perfil e status; consultar histórico; advertir, suspender, bloquear e reativar; validar identidade e registro profissional |
| Solicitações | fila de análise; aprovar, devolver, recusar e definir prioridade; consultar reservas e cancelamentos |
| Agendas | consultar horários, bloquear horários denunciados e suspender agendas |
| Denúncias | fila por gravidade; ver contexto; decidir procedente/improcedente; aplicar sanção; notificar as partes |
| Avaliações e mensagens | ocultar conteúdo ofensivo |
| Ocorrências | tratar desistência, não comparecimento, discordância de conclusão e falhas |
| Cadastros-base | tipos de apoio, condições crônicas, motivos de denúncia e status |
| Parâmetros | limites, prazos, raios e regras de antecedência |
| Indicadores | contas ativas, solicitações criadas/aprovadas/concluídas, tempo médio de validação, tempo até a reserva, taxa de cancelamento e denúncias abertas |

**Regras:** toda sanção exige justificativa e é registrada em auditoria; todo conteúdo publicado passa por verificação e autorização prévias; usuário sancionado é notificado e pode contestar (analisada por outro mediador, quando possível); mediador acessa dados pessoais no mínimo necessário; a mediação não altera conteúdo de avaliação, apenas oculta com justificativa; não há exclusão física de contas, solicitações ou mensagens — usa-se status e anonimização quando a lei exigir.

**Saídas:** ações executadas, usuários notificados e registros de auditoria.

**Critérios de aceite:**

- Mediador não consegue alterar nota de avaliação.
- Nenhuma tela oferece exclusão física de conta, solicitação ou mensagem.
- Acesso a CPF/endereço/documento/condição registra motivo obrigatório.
- Indicadores do painel conferem com consultas de auditoria.

> **DECISÃO PENDENTE 27:** indicadores obrigatórios da primeira versão e a periodicidade de atualização.

### RF016 — Verificação de identidade e de registro profissional

**Objetivo:** confirmar que o Apoiador é pessoa real e, quando profissional, que possui registro válido. O mediador também confirma a identidade do Assistido antes de publicar a solicitação.

**Documentos e evidências:**

| Item | Regra |
|---|---|
| Documento oficial com foto | enviado pelo usuário, acesso restrito à mediação |
| Selfie de verificação | comparada com a foto do documento |
| Registro profissional | número no conselho de classe e, quando possível, link de consulta pública; obrigatório para especialidades regulamentadas |
| Armazenamento | protegido, com exclusão após o prazo necessário à verificação |

**Fluxo:** usuário envia documento com foto e selfie → Apoiador profissional informa número de registro e, se possível, link público → sistema registra **Em análise** e notifica a mediação → mediador compara e aprova/reprova com justificativa → aprovado, o perfil recebe o selo **"Verificado"** e pode publicar agenda ou solicitar apoio.

**Regras:** documentos são dados pessoais — acesso restrito, armazenamento protegido e exclusão após o prazo necessário; verificação obrigatória para Apoiadores e para a **publicação da primeira solicitação do Assistido**; conferência do registro obrigatória para especialidades regulamentadas (fisioterapia, fonoaudiologia, enfermagem etc.); a plataforma não substitui a fiscalização do conselho, apenas confere a existência do registro.

**Saídas:** usuário verificado ou reprovado e notificado.

**Critérios de aceite:**

- Nenhuma URL pública permanente de documento.
- Documento expurgado não pode ser recuperado pela aplicação.
- Especialidade regulamentada sem registro conferido não fica ativa.
- Tentativa de acessar documento de outro usuário é negada e auditada.

> **DECISÃO PENDENTE 28:** o Assistido envia documento + selfie no cadastro ou no primeiro acesso após aprovação? (Ver Decisão 2.)

> **DECISÃO PENDENTE 29:** guardar o resultado da verificação após o expurgo do arquivo? Recomenda-se sim, sem o arquivo.

### RF017 — Histórico, ocorrências e expiração

**Objetivo:** acompanhar solicitações/reservas e definir o comportamento quando algo foge do fluxo normal.

**Fluxo de histórico:** usuário acessa "Minhas solicitações" (Assistido) ou "Minha agenda" (Apoiador) → sistema lista com status, data, tipo de apoio e contraparte, com filtros por status e período → detalhe com linha do tempo de status e acesso ao chat, quando disponível.

**Situações e tratamento:**

| Situação | Tratamento |
|---|---|
| Apoiador desiste após a reserva | informa motivo; solicitação volta a **Aberta** (se dentro do prazo), Assistido notificado, desistência registrada na reputação |
| Apoiador não comparece | Assistido registra "Apoiador não compareceu"; abre ocorrência; mediação decide |
| Assistido não responde | lembretes em 24 h e 48 h; sem ação até a expiração, solicitação passa a **Expirada** |
| Falha de compromisso | ocorrência no histórico das duas partes, considerada na reputação; mediação analisa e define consequência |
| Horário reservado e não realizado | mediação avalia, pode liberar o horário, registrar ocorrência e aplicar advertência |
| Solicitação sem reserva até a data desejada | sistema avisa o Assistido e oferece reabrir com nova data ou ampliar o raio |
| Falha técnica | operação revertida, usuário informado, nada gravado parcialmente |
| Conta suspensa | solicitações e horários ocultados automaticamente |

**Regras:** rotina automática verifica expirações, lembretes e confirmações vencidas; ocorrências ficam no histórico e são consideradas na avaliação/reputação; toda mudança de status é registrada; histórico visível só ao titular e à mediação; Assistido vê total de apoios recebidos; Apoiador vê total de apoios concluídos e avaliações; listagem paginada em 10, ordenada pela data mais recente.

**Saídas:** listas, detalhes, status atualizados, notificações e ocorrências.

**Critérios de aceite:**

- Rodar a rotina duas vezes seguidas não duplica efeitos.
- Solicitação expirada não pode ser reservada.
- Usuário não acessa histórico de terceiros por URL direta.

> **DECISÃO PENDENTE 30:** rotina automática em `pg_cron`, comando Django agendado ou ambos? Frequência, janela e tratamento de falha não estão definidos na fonte. Recomenda-se comando Django idempotente + agendador, com regra nos services (ADR 0001).

## 5. Matriz de permissões

| Ação | Visitante | Assistido | Familiar autorizado | Apoiador | Mediador |
|---|---|---|---|---|---|
| Ver termos/política | Sim | Sim | Sim | Sim | Sim |
| Cadastrar | Sim | — | — | — | — |
| Autenticar | Sim | Sim | Sim | Sim | Sim |
| Ver catálogo | Não | Sim | Sim (em nome do Assistido) | Não `[PROPOSTA]` | Sim (auditoria) |
| Criar solicitação | Não | Sim | Sim (autorizado) | Não | Não |
| Publicar agenda | Não | Não | Não | Sim | Não |
| Reservar horário | Não | Sim | Sim (autorizado) | Não | Não |
| Chat da reserva | Não | Participante | Participante autorizado `[PROPOSTA]` | Participante | Leitura auditada |
| Confirmar conclusão | Não | Participante | Não `[PROPOSTA]` | Participante | Não |
| Avaliar | Não | Participante | Não | Participante | Não |
| Denunciar | Não | Sim | Sim `[PROPOSTA]` | Sim | Não |
| Validar conta/solicitação | Não | Não | Não | Não | Sim |
| Aplicar sanção | Não | Não | Não | Não | Sim |
| Editar parâmetros/cadastros-base | Não | Não | Não | Não | Sim |
| Consultar auditoria | Não | Próprio histórico | Próprio histórico | Próprio histórico | Sim |
| Ver dado sensível | Não | Próprio | Próprio (se autorizado) | Próprio | Com motivo e registro |

## 6. Parâmetros configuráveis (RNF010)

| Parâmetro | Padrão | Faixa sugerida | Quem altera | Origem |
|---|---|---|---|---|
| `sessao_inatividade_min` | 30 | 5–120 | Mediação | RF002 |
| `bloqueio_tentativas` | 5 | 3–10 | Mediação | RF002 |
| `bloqueio_duracao_min` | 15 | 5–60 | Mediação | RF002 |
| `email_confirmacao_validade_h` | 24 | 1–72 | Mediação | RF001 |
| `solicitacao_prazo_analise_horas_uteis` | 24 | 4–72 | Mediação | RF006 |
| `solicitacao_validade_dias` | 30 | 7–90 | Mediação | `[PROPOSTA]` |
| `solicitacao_max_abertas` | **sem limite** | n/a (se aprovado, 1–10) | Mediação | RF005 × RF009/RNF010 (Decisão 13) |
| `horario_duracao_min_min` | 30 | 30–60 | Mediação | RF004 |
| `horario_duracao_max_h` | 4 | 2–8 | Mediação | RF004 |
| `horario_limite_diario` | `[PROPOSTA]` | 1–12 | Mediação | RF004 |
| `raio_padrao_km` | 10 | 1–50 | Mediação | RF001/RF007 |
| `raio_max_km` | 100 `[PROPOSTA]` | 10–200 | Mediação | RF001 |
| `reserva_antecedencia_cancelamento_h` | 24 | 12–72 | Mediação | RF009 |
| `reserva_ciencia_h` | 24 | 1–48 | Mediação | RF008 |
| `conclusao_prazo_h` | 72 | 24–168 | Mediação | RF012 |
| `avaliacao_prazo_dias` | 7 | 3–30 | Mediação | RF013 |
| `chat_somente_leitura_dias` | 30 | 15–90 | Mediação | RF011 |
| `denuncia_suspensao_qtd` | 3 | 2–10 | Mediação | RF014 |
| `denuncia_suspensao_janela_dias` | 30 | 15–90 | Mediação | RF014 |
| `documento_retencao_dias` | 90 `[PROPOSTA]` | 30–365 | Jurídico/mediação | RF016 |
| `mensagem_max_caracteres` | 1.000 | 200–2.000 | Produto | RF011 |
| `avaliacao_comentario_max` | 300 | 100–1.000 | Produto | RF013 |
| `catalogo_pagina` | 10 | 10–50 | Produto | RF007 |
| `historico_pagina` | 10 | 10–50 | Produto | RF017 |

Regras: alteração registrada em auditoria com valor anterior, novo valor, autor e justificativa; parâmetro não pode ser reduzido a ponto de invalidar registros existentes (ex.: duração de horário já publicado).

## 7. Requisitos não funcionais mensuráveis

Prioridades definidas na fonte (seção 1.2.2): **Essencial** — sem ele o sistema não entra em funcionamento; **Importante** — o sistema funciona, mas de forma não satisfatória sem ele; **Desejável** — pode ficar para versão posterior sem comprometer o básico.

| RNF | Requisito | Prioridade (fonte) | Critério verificável |
|---|---|---|---|
| RNF001 | Usabilidade | Importante | Interface simples, textos curtos, ajuda contextual e orientações claras, para usuários com pouca familiaridade e possível dor/limitação; teste com 5 usuários `[PROPOSTA]`: cadastro e reserva sem ajuda em ≤ 10 min |
| RNF002 | Acessibilidade | Importante | WCAG 2.1 AA: contraste mínimo, fontes ajustáveis, navegação por teclado, foco visível, leitores de tela, alvos ≥ 44×44 px; auditoria automatizada + manual |
| RNF003 | Compatibilidade | Essencial | Site responsivo, mobile-first; 2 últimas versões de Chrome, Firefox, Edge e Safari; apps nativos fora da v1 |
| RNF004 | Desempenho | Importante | p95 ≤ 3 s em telas e consultas; notificação de reserva/cancelamento em ≤ 1 min |
| RNF005 | Disponibilidade | Importante | 24 h/dia, 99% mensal; monitoramento externo e janela de manutenção anunciada `[PROPOSTA]` |
| RNF006 | Integridade | Essencial | Reservar, cancelar, remarcar e concluir são transacionais; impossível reservar o mesmo horário duas vezes; nenhuma escrita parcial |
| RNF007 | Mensagens de erro | Importante | pt-BR, claras, indicando o que ocorreu e como corrigir, com destaque dos campos com problema e `aria-invalid` |
| RNF008 | Backup | Importante | Backup automático diário; testes periódicos de restauração; retenção definida pela equipe; RPO ≤ 24 h e RTO ≤ 4 h `[PROPOSTA]` |
| RNF009 | Escalabilidade | Desejável | Crescimento sem reescrita; paginação, índices e consultas otimizadas para especialidade, data e distância |
| RNF010 | Parametrização | Importante | Limites, prazos e valores configuráveis pela mediação sem alteração de código (seção 6) |
| RNF011 | Conformidade legal | Essencial | LGPD: finalidade, consentimento específico, minimização, direitos do titular, política de privacidade; saúde = dado sensível (art. 11) |
| RNF012 | Auditabilidade | Essencial | Login, validação de conta, aprovação de solicitação, reserva, cancelamento, sanção, acesso da mediação a dados pessoais e mudança de status registrados com usuário, ação, data e hora; retenção conforme Marco Civil |
| RNF013 | Integrações externas | Importante | CEP, IBGE, geolocalização/mapas, e-mail e push opcional; sem integração de pagamento; falha externa não bloqueia o fluxo principal |
| RNF014 | Localização | Importante | pt-BR; datas dd/mm/aaaa; horários no fuso de Brasília |
| RNF015 | Gratuidade | Essencial | Sem cobrança, intermediação ou permissão de pagamentos, doações, gorjetas ou transferências; uso gratuito |

## 8. Rastreabilidade e dimensionamento

### 8.1 Matriz de rastreabilidade — Requisito Funcional (D = depende; R = relaciona-se)

| Requisito | Depende de (D) | Relaciona-se com (R) |
|---|---|---|
| RF001 | — | RF002, RF003, RF004, RF016 |
| RF002 | RF001 | RF003, RF005, RF007, RF014, RF015 |
| RF003 | RF001, RF002 | RF005 |
| RF004 | RF001, RF016 | RF007, RF008 |
| RF005 | RF002, RF003 | RF006, RF009, RF010, RF017 |
| RF006 | RF005, RF016 | RF007, RF015 |
| RF007 | RF002, RF004, RF006 | RF008 |
| RF008 | RF004, RF007 | RF009, RF010, RF011, RF012, RF017 |
| RF009 | RF005, RF008 | RF010 |
| RF010 | RF005, RF008, RF009, RF011 | — |
| RF011 | RF008 | RF010, RF012, RF014 |
| RF012 | RF008, RF011 | RF013, RF017 |
| RF013 | RF012 | — |
| RF014 | RF002, RF011 | RF015 |
| RF015 | RF002, RF006, RF014 | RF016 |
| RF016 | RF001, RF015 | RF004, RF006 |
| RF017 | RF005, RF008, RF012 | — |

### 8.2 Requisitos de segurança (RS001–RS013)

| ID | Perigo | Soluções | Requisitos relacionados |
|---|---|---|---|
| RS001 | Vazamento ou acesso indevido a dados de saúde e documentos (dados sensíveis, LGPD art. 11) | HTTPS/TLS; criptografia em repouso dos dados sensíveis e documentos; controle de acesso por perfil; coleta apenas do necessário; consentimento específico; registro de acessos; exclusão de documentos após o prazo de verificação | RF001, RF003, RF016 |
| RS002 | Acesso indevido a contas por terceiros (senha fraca, roubo de credenciais) | Hash com sal; política mínima de senha; bloqueio temporário após tentativas falhas; sessão com expiração; recuperação por link de uso único; 2FA desejável | RF002 |
| RS003 | Exposição de endereço exato, telefone, condição de saúde ou observações de cuidado antes da reserva | Exibir apenas dados aproximados no catálogo; liberar dados completos somente após a reserva (RF008); não exibir telefone; chat interno obrigatório | RF005, RF007, RF008, RF011 |
| RS004 | Perfis falsos ou apoiadores mal-intencionados que se aproveitam de pessoas fragilizadas | Verificação de identidade e de registro profissional; selo de verificação; avaliações e histórico visíveis; denúncia simples; suspensão preventiva; orientações de segurança | RF001, RF013, RF014, RF016 |
| RS005 | Golpes e solicitação de dinheiro, transferências ou dados bancários | Proibir pedidos financeiros nos Termos; alertar sobre termos suspeitos; aviso permanente no chat; denúncia com motivo específico e tratamento prioritário; ocultação preventiva de mensagens | RF005, RF011, RF014 |
| RS006 | Assédio, ofensas ou conteúdo inadequado em mensagens, solicitações ou avaliações | Denúncia por mensagem; bloqueio entre usuários; ocultação de conteúdo e sanções pela mediação; histórico preservado para análise | RF011, RF013, RF014, RF015 |
| RS007 | Abuso da plataforma: excesso de solicitações, spam, cancelamentos repetidos ou não comparecimento | Limites configuráveis de solicitações e horários; expiração automática; limite de requisições por usuário; sinalização de padrões de cancelamento e ausência à mediação; registro na reputação | RF004, RF005, RF009, RF017 |
| RS008 | Inconsistência de estado: duas reservas para o mesmo horário, ou horário cancelado ainda exibido como disponível | Validação no servidor; transação atômica com bloqueio; revalidação do status antes de cada operação crítica; atualização em tempo real do catálogo | RF004, RF007, RF008, RF009 |
| RS009 | Denúncia maliciosa ou sanção indevida a usuário inocente | Análise humana antes de sanções (exceto suspensão preventiva); justificativa obrigatória; canal de contestação; registro em auditoria; sanção a denúncias reconhecidamente falsas | RF014, RF015 |
| RS010 | Perda ou corrupção de dados (falha de infraestrutura, erro humano) | Backups diários com testes de restauração; histórico em vez de exclusão física; redundância conforme infraestrutura | RF001 a RF017 |
| RS011 | Uso da plataforma para emergência médica, gerando falsa sensação de socorro | Aviso permanente e visível na criação de solicitações e na prioridade Alta: não substitui o SAMU (192); nenhuma promessa de tempo de resposta | RF005, RF007 |
| RS012 | Uso indevido de privilégios por mediadores e moderadores | Perfis com permissões mínimas; log de auditoria de todo acesso a dados pessoais e sanções; justificativa obrigatória; revisão periódica de acessos | RF015, RF016 |
| RS013 | Profissional sem registro ou com credencial falsa atuando como fisioterapeuta, fonoaudiólogo ou enfermeiro | Conferência do registro no conselho de classe; solicitação de documento e selfie; selo apenas após validação; denúncia específica; suspensão imediata em caso de fraude | RF001, RF014, RF016 |

### 8.3 Matriz de avaliação de risco

| Segurança / probabilidade | Catastrófico (1) | Crítico (2) | Marginal (3) | Insignificante (4) |
|---|---|---|---|---|
| Frequente (A) | — | — | — | — |
| Provável (B) | — | RS011 | RS006, RS007 | — |
| Ocasional (C) | RS004, RS013 | RS002, RS003, RS005, RS008 | RS009 | — |
| Remoto (D) | RS001 | RS010, RS012 | — | — |
| Improvável (E) | — | — | — | — |
| Eliminado (F) | — | — | — | — |

### 8.4 Ponto de função — 465 PF

A contagem da fonte é para **projeto em desenvolvimento** e cobre os módulos internos, sem integrações externas. Componentes: ALI (Arquivo Lógico Interno), AIE (Arquivo de Interface Externa), SE (Saída Externa), EE (Entrada Externa) e CE (Consulta Externa).

| Requisito | Complexidade | ALI | AIE | SE | EE | CE | Total |
|---|---|---|---|---|---|---|---|
| RF001 | Alta | 30 | 7 | 5 | 6 | 0 | 48 |
| RF002 | Baixa | 0 | 0 | 4 | 6 | 0 | 10 |
| RF003 | Média | 10 | 0 | 0 | 4 | 4 | 18 |
| RF004 | Alta | 15 | 0 | 5 | 12 | 6 | 38 |
| RF005 | Alta | 15 | 7 | 7 | 6 | 6 | 41 |
| RF006 | Alta | 10 | 0 | 7 | 12 | 12 | 41 |
| RF007 | Alta | 0 | 7 | 7 | 0 | 12 | 26 |
| RF008 | Alta | 0 | 0 | 14 | 12 | 6 | 32 |
| RF009 | Média | 0 | 0 | 5 | 8 | 4 | 17 |
| RF010 | Média | 10 | 7 | 10 | 0 | 4 | 31 |
| RF011 | Alta | 15 | 0 | 7 | 6 | 6 | 34 |
| RF012 | Média | 0 | 0 | 5 | 4 | 0 | 9 |
| RF013 | Média | 10 | 0 | 5 | 4 | 4 | 23 |
| RF014 | Média | 10 | 0 | 5 | 4 | 0 | 19 |
| RF015 | Alta | 15 | 0 | 7 | 12 | 12 | 46 |
| RF016 | Média | 10 | 0 | 5 | 4 | 0 | 19 |
| RF017 | Média | 0 | 0 | 5 | 4 | 4 | 13 |
| **TOTAL** | — | **150** | **28** | **103** | **104** | **80** | **465** |

### 8.5 Protótipos da fonte

| Tela | Conteúdo | Requisito |
|---|---|---|
| 01A | Cadastro: perfil e dados | RF001 |
| 01B | Cadastro: endereço e consentimentos | RF001 |
| 02 | Login | RF002 |
| 08A | Minha agenda | RF004 |
| 08B | Adicionar horário | RF004 |
| 04A | Nova solicitação: tipo de apoio | RF005 |
| 04B | Nova solicitação: horários, local e prioridade | RF005 |
| 11 | Painel da mediação: fila de validação | RF006 |
| 05 | Apoiadores disponíveis | RF007 |
| 06 | Perfil e agenda do apoiador | RF007 |
| 07A | Confirmação da reserva | RF008 |
| 07B | Reserva confirmada | RF008 |
| 03 | Painel do assistido: central de notificações | RF010 |
| 09 | Conversa (chat) | RF011 |
| 10 | Conclusão e avaliação | RF012 |

### 8.6 RF × ADR × tabelas × teste mínimo

| Requisito | ADR principal | Tabelas | Teste mínimo |
|---|---|---|---|
| RF001 | 0002 | `usuario`, `endereco`, `termo_aceite`, perfis | Cadastro com CPF duplicado e menor de idade |
| RF002 | 0002 | `usuario`, `tentativa_login` | Bloqueio e recuperação de uso único |
| RF003 | 0002, 0003 | `usuario`, perfis, `termo_aceite` | CPF imutável; auditoria de alteração |
| RF004 | 0001, 0004 | `horario`, `recorrencia` | Recorrência sem data passada e sem sobreposição |
| RF005 | 0001, 0003 | `solicitacao`, `solicitacao_periodo` | Não publica sem mediação; pedido de dinheiro bloqueado |
| RF006 | 0001 | `analise_solicitacao`, `audit_log` | Recusa com justificativa, contestação única e auditoria |
| RF007 | 0001, 0004 | views de catálogo | Nenhum dado proibido no HTML |
| RF008 | 0001, 0004 | `reserva`, `horario` | Duas reservas concorrentes; ciência em 24 h |
| RF009 | 0001 | `reserva`, `ocorrencia` | 24 h vs. 23h59; remarcação |
| RF010 | 0005 (sugerida) | `notificacao` | Obrigatória não desativável; ≤ 1 min |
| RF011 | 0001, 0003 | `mensagem`, `bloqueio_usuario` | Acesso indevido ao chat; ocultação de mensagem |
| RF012 | 0001 | `reserva_confirmacao` | Uma confirmação não conclui antes de 72 h |
| RF013 | 0003 | `avaliacao` | Avaliação duplicada e fora do prazo |
| RF014 | 0001 | `denuncia`, `sancao` | Suspensão preventiva; anexo automático do trecho |
| RF015 | 0001, 0002 | `sancao`, `parametro`, auditoria | Mediação não altera nota |
| RF016 | 0004 | `documento_verificacao` | Documento sem URL pública |
| RF017 | 0001, 0004 | `ocorrencia`, logs | Rotina idempotente |

## 9. Regras transversais, escopo negativo e critérios de conclusão

### 9.1 Regras de negócio transversais

Valem para mais de um requisito e devem ser testadas em conjunto:

1. **Mediação humana antes da publicação:** contas e solicitações passam por verificação prévia; agendas e conteúdos também, conforme RF015 (ver Decisão 12 sobre a agenda do RF004).
2. **Uma conta por CPF:** o CPF é único, imutável e não pode ser reutilizado em outra conta.
3. **Maioridade:** somente pessoas com 18 anos ou mais podem se cadastrar.
4. **Minimização e liberação progressiva:** o catálogo mostra apenas dados aproximados; dados completos e observações de cuidado são liberados após a reserva; o telefone nunca é exibido.
5. **Sem pagamentos:** a plataforma não cobra, não intermedeia e não permite dinheiro, doações, gorjetas ou transferências entre usuários.
6. **Sem exclusão física:** contas, solicitações e mensagens usam status e anonimização; o histórico é preservado para auditoria.
7. **Emergência:** a plataforma não substitui o SAMU (192) e não promete tempo de resposta.
8. **Contestação:** sanções e recusas podem ser contestadas, preferencialmente por outro mediador.
9. **Transparência:** o denunciante é informado do resultado sem exposição dos dados do denunciado.
10. **Gratuidade:** o uso é gratuito para Assistidos e Apoiadores.

### 9.2 Fora do escopo desta versão

- Aplicativos nativos Android e iOS.
- Cobrança, pagamento, doação financeira, gorjeta ou intermediação de valores.
- Contas de ONG, clínica, agência de cuidadores ou instituição.
- Despacho automático de apoiadores e metas de atendimento por Apoiador.
- Anexos, imagens, áudios, chamadas de vídeo ou telefone dentro do chat.
- Editor visual, Markdown ou HTML em textos enviados por usuários.
- Autenticação em dois fatores (desejável para versão posterior, conforme RF002).
- Notificação push como requisito essencial (desejável na primeira versão, conforme RF010).
- API pública, integração com prontuários, sistemas de saúde ou conselhos profissionais.
- Moderação automática por IA e priorização automática de solicitações.
- Internacionalização, outros idiomas e outros fusos.
- Cadastro de menores de 18 anos.
- Promessa de tempo de resposta ou substituição de serviços de emergência.

### 9.3 Critérios de conclusão

1. Um Assistido completa o ciclo pelo navegador: cadastro, aprovação, solicitação, aprovação da solicitação, catálogo, reserva, chat, confirmação dupla e avaliação.
2. Um Apoiador completa o ciclo: cadastro, verificação, publicação de agenda, recebimento de reserva, chat, confirmação e avaliação.
3. Um mediador valida conta e solicitação, trata uma denúncia, aplica uma sanção com justificativa e consulta o registro de auditoria.
4. Os controles de segurança e LGPD estão implementados e testados: papéis e privilégios mínimos, criptografia de campos sensíveis, retenção e descarte, direitos do titular e ausência de dados pessoais em logs.
5. Os limites de desempenho e disponibilidade do RNF004 e do RNF005 são medidos e registrados.
6. Todos os requisitos funcionais têm pelo menos um teste automatizado, e a suíte passa por completo.
7. A proposta do banco Supabase foi aprovada antes de qualquer uso em produção.

### 9.4 Divergências encontradas em artefatos derivados

A conferência incluiu os ADRs e a proposta de banco. Estes pontos **não foram alterados** aqui, mas precisam ser corrigidos antes da implementação:

| Artefato | Divergência | Ajuste necessário |
|---|---|---|
| `propostas/banco-supabase/sql/001_schema.sql` | `usuario.status` usa `ativo/suspenso/bloqueado/desativado`; o produto usa os rótulos Ativa/Suspensa/Bloqueada/Desativada e a proposta acrescenta `anonimizado` | Documentar o mapeamento rótulo × valor de banco; decidir se `anonimizado` é status de produto |
| `propostas/banco-supabase/sql/001_schema.sql` | `usuario.foto_path` aceita nulo, mas RF001 exige foto obrigatória | Adicionar `not null` (ou regra equivalente) após definir o fluxo de upload |
| `propostas/banco-supabase/sql/001_schema.sql` | `solicitacao.modalidade` e `horario.modalidade` aceitam apenas `presencial` e `remoto`; RF004/RF005 preveem também "a combinar" | Incluir `a_combinar` nos dois CHECKs |
| `propostas/banco-supabase/sql/001_schema.sql` | `solicitacao.status` tem `ajuste` e `em_andamento`, mas não tem "Aguardando confirmação" (RF012) | Renomear/documentar e incluir o status de confirmação |
| `propostas/banco-supabase/sql/001_schema.sql` | `horario.status` inclui `cancelado` e `liberado`, que a fonte lista sem definir o gatilho | Resolver as decisões 7 e 9 e refletir no schema |
| `propostas/banco-supabase/sql/001_schema.sql` | `reserva.status` só prevê `ativa/cancelada/concluida/bloqueada`, sem o estado de ciência em 24 h e sem a confirmação parcial | Detalhar a máquina da reserva (seção 3.4) no schema |

### 9.5 Referências da fonte

1. Modelo de documento de requisitos fornecido pelo solicitante, usado como referência de organização e formato.
2. Documento de Requisitos do EloVital (este PDF).
3. Protótipos visuais — telas de alta fidelidade do EloVital (seção 8.5).
4. BRASIL. Lei nº 13.709, de 14 de agosto de 2018 — Lei Geral de Proteção de Dados Pessoais (LGPD).
5. BRASIL. Lei nº 12.965, de 24 de abril de 2014 — Marco Civil da Internet.
6. W3C. Web Content Accessibility Guidelines (WCAG) 2.1.
7. IFPUG. Counting Practices Manual — Análise por Pontos de Função.

---

# Parte II — ADRs detalhados

> **Histórico.** Os ADRs 0001–0004 abaixo foram escritos para Django/Python e estão substituídos. A implementação Java/Spring está em [ADRs 0005–0023](../adr/README.md). O mapeamento RF → ADR da seção 8.6 deve ser lido como: RF001–RF004 → 0006/0008; RF005–RF009 → 0009/0011/0012; RF010 → 0015; RF011–RF013 → 0009/0011; RF014–RF017 → 0014/0016/0023, além dos transversais 0013, 0018, 0019, 0020, 0021 e 0022.

## ADR 0001 — MVT com camada de service

### Contexto ampliado

As regras críticas (reserva atômica, dupla confirmação, liberação progressiva, sanções, auditoria) precisam ser testáveis sem HTTP e reutilizadas por mediação, Admin e rotinas. O risco é a regra vazar para views, forms e signals, criando comportamento divergente.

### Decisão detalhada `[PROPOSTA de implementação]`

**Estrutura de apps:**

| App | Responsabilidade |
|---|---|
| `contas` | Usuario, perfis, endereço, termos, verificação, login |
| `catalogo` | Especialidades, condições, parâmetros, municípios |
| `agenda` | Horários, recorrência, disponibilidade |
| `solicitacoes` | Solicitação, períodos, análise |
| `reservas` | Reserva, confirmação, cancelamento |
| `comunicacao` | Chat, notificações |
| `reputacao` | Avaliações, ocorrências |
| `moderacao` | Denúncias, sanções, contestações |
| `auditoria` | Registro e consulta de auditoria |

**Camadas por app:**

```text
app/
  models.py        campos, relações, constraints (sem regra)
  services/        regra de negócio e acesso ao ORM
  exceptions.py    exceções de domínio do app
  forms.py         formato e renderização
  views.py         classes que herdam de View
  urls.py
  templates/
```

**Contrato de service:**

- Recebe dados primitivos ou DTOs, nunca `request`.
- Retorna entidade/DTO ou lança exceção de domínio.
- Não conhece template, redirect, mensagem de UI.
- Usa `transaction.atomic()` nas operações compostas.
- Autorização recebe ator explícito (`usuario`) e valida papel/estado.

**Catálogo mínimo de services `[PROPOSTA]`:**

| Service | Operações |
|---|---|
| `contas.services.cadastrar_usuario` | validar, criar conta e termos, notificar |
| `contas.services.aprovar_conta` / `reprovar_conta` | transição e auditoria |
| `contas.services.alterar_perfil` | campos permitidos e revalidação |
| `contas.services.verificar_documento` | aprovar/reprovar e expurgar |
| `agenda.services.publicar_horario` | validação, recorrência, sobreposição |
| `agenda.services.editar_horario` | bloqueio otimista |
| `solicitacoes.services.criar_solicitacao` | limites e estado inicial |
| `solicitacoes.services.analisar_solicitacao` | parecer, prioridade, auditoria |
| `reservas.services.reservar` | transação e concorrência |
| `reservas.services.cancelar` | antecedência e efeitos |
| `reservas.services.confirmar_conclusao` | dupla confirmação |
| `comunicacao.services.enviar_mensagem` | participação e estado do chat |
| `comunicacao.services.notificar` | canais e preferências |
| `reputacao.services.avaliar` | prazo, unicidade, sinalização |
| `moderacao.services.registrar_denuncia` | protocolo e suspensão preventiva |
| `moderacao.services.aplicar_sancao` | justificativa e auditoria |
| `auditoria.services.registrar` | log imutável |

**Exceções de domínio `[PROPOSTA]`:**

```text
EloVitalError
├── ValidacaoError(campo, mensagem)
├── PermissaoNegadaError
├── EstadoInvalidoError(estado_atual, acao)
├── ConflitoError(recurso)         # reserva, edição concorrente
├── PrazoExpiradoError
├── LimiteExcedidoError
└── IntegracaoError(servico)       # CEP, e-mail, mapas
```

Mapeamento na view: `ValidacaoError` → form com erro; `PermissaoNegadaError` → 403; `EstadoInvalidoError`/`ConflitoError` → 409 ou mensagem com próxima ação; `PrazoExpiradoError` → 410/redirect informativo.

**Views:** apenas `View` + mixins de acesso. Tratamento de erro centralizado em mixin/base. Nenhuma consulta de negócio direto no `get_context_data`; usar service de leitura.

**Forms:** validam formato e limites; não gravam; `form.save()` desabilitado por convenção e por teste.

**Models:** `clean()` só com invariantes locais; sem chamadas a serviços; sem efeitos colaterais.

**Admin:** usa services; para operações críticas, formulários próprios ou ações com confirmação e justificativa.

**Signals:** proibidos para regra de negócio; permitidos apenas para efeitos técnicos (ex.: limpar cache) `[PROPOSTA]`.

### Testes de arquitetura `[PROPOSTA]`

1. Nenhuma view herda de generic view (`ListView`, `DetailView`, `CreateView`, `FormView` etc.), exceto o Admin.
2. Nenhum módulo em `services/` importa `HttpResponse`, `request` ou templates.
3. Nenhum `form.save()` no código de aplicação.
4. Nenhum model importa service.
5. Exceções de domínio não herdam de `Http404`/`PermissionDenied` do Django.

### Consequências e riscos

- Mais código de view e de formulário; ganho em testabilidade e reuso.
- Risco de services "gigantes": limitar por operação, não por entidade.
- Risco de views de leitura duplicarem consultas: padronizar services de leitura.

> **DECISÃO PENDENTE 31:** confirmar a lista de apps e o catálogo de services, ou manter apenas a convenção sem estrutura fixa.

> **DECISÃO PENDENTE 32:** `select_for_update` dentro do service ou em função de banco (ADR 0004 usa função SQL)? O ideal é uma única implementação; a proposta de banco já traz `reservar_horario` em SQL. Escolher uma fonte de verdade evita divergência.

## ADR 0002 — Usuário, papéis e autenticação

### Modelo `Usuario` detalhado `[PROPOSTA]`

| Campo | Tipo | Regra |
|---|---|---|
| `id` | UUID | chave exposta |
| `email` | citext único | login; normalizado |
| `password` | hash | Django; validadores |
| `nome_completo` | texto | 3–120 (fonte RF001) |
| `foto_perfil` | arquivo | JPG/PNG, obrigatória no cadastro; bucket privado |
| `cpf_hash` | texto único | HMAC-SHA-256 com pepper; nunca exposto |
| `cpf_cifrado` | binário/texto | AES-256-GCM; leitura auditada |
| `data_nascimento` | data | ≥ 18 anos; imutável após aprovação |
| `celular_cifrado` | texto | cifrado; usado para validação e avisos; nunca exibido ao outro usuário |
| `papel` | enum | `ASSISTIDO`, `APOIADOR`, `MEDIADOR` |
| `status` | enum | `EM_ANALISE`, `ATIVA`, `SUSPENSA`, `BLOQUEADA`, `DESATIVADA` (seção 3.1) |
| `condicoes` | relação | Assistido: mínimo 1; lista editável pela mediação |
| `condicao_outra` | texto | até 100 caracteres; habilitado em "Outra" |
| `limitacoes` | texto | até 300 caracteres; opcional |
| `contato_emergencia` | texto | nome + telefone cifrado; opcional/recomendado |
| `autorizacao_familiar` | texto | nome e vínculo; opcional; não cria novo tipo de usuário |
| `is_active` | booleano | controle técnico do Django, não é o status de produto |
| `is_staff` | booleano | exigido para mediação |
| `email_verificado_em` | data/hora | confirmação de e-mail |
| `ultimo_login_em` | data/hora | métrica e segurança |
| `criado_em` / `atualizado_em` | data/hora | automáticos |

**Constraints:** e-mail único sem caixa; CPF único por hash; data de nascimento compatível com maioridade; `papel`/`status` restritos por escolha.

**Autorização da mediação:** `papel = MEDIADOR` **e** `is_staff = True` **e** grupo `Mediacao`; permissões por grupo (contas, solicitações, denúncias, parâmetros, auditoria) `[PROPOSTA]`.

### Autenticação — fluxo detalhado

```text
POST /entrar
  → valida formato
  → consulta bloqueio (email_hash + ip_hash, janela)
  → autentica (mensagem genérica se falhar)
  → registra tentativa (sucesso/falha)
  → verifica status (fonte RF002)
     ├─ em_analise/suspensa/bloqueada/desativada → recusa com orientação para a mediação
     └─ ativa → sessão normal
  → aplica sessão (expiração por inatividade de 30 min)
```

**Recuperação:** pedido → sempre mesma resposta → token de uso único (1 h) → definição de nova senha → invalidação do token e das sessões existentes `[PROPOSTA]`.

**Senha:** mínimo 8 caracteres; hash com sal (fonte RF001/RF002); sem senhas comuns; sem semelhança com dados pessoais; sem sequências óbvias; confirmação; mensagens claras.

**Bloqueio por tentativas:** 5 tentativas consecutivas → 15 minutos (fonte RF002).

> **DECISÃO PENDENTE 33:** política de senha mais rígida (12 caracteres, histórico de 5, troca periódica)? A recomendação atual do NIST é comprimento mínimo e verificação contra vazamentos, sem troca periódica obrigatória.

### CPF — criptografia e ciclo de vida

- HMAC para unicidade/busca; cifra para exibição autorizada.
- Pepper e chave fora do banco (variável de ambiente/cofre).
- Rotação exige script de recifragem com janela e backup.
- CPF nunca em log, relatório, exportação ou tela do catálogo.
- Anonimização zera `cpf_hash` e `cpf_cifrado` — **única exceção** à imutabilidade; deve constar no PRD e no ADR.

> **DECISÃO PENDENTE 34:** confirmar a exceção de anonimização no texto do PRD/ADR, para eliminar a contradição com "CPF imutável".

### Consequências e riscos

- Troca de modelo de usuário depois da primeira migração é custosa → criar antes de migrar.
- Dependência de e-mail confiável → escolher provedor e tratar bounce.
- HMAC + cifra exigem gestão de segredos e plano de rotação.
- Bloqueio por tentativas exige limpeza periódica.
- 2FA fica fora da primeira versão.

### Critérios de aceite do ADR

- `AUTH_USER_MODEL` definido antes da primeira migração.
- Nenhum `import User` direto; uso de `get_user_model()`/`settings.AUTH_USER_MODEL`.
- CPF não aparece em nenhum log de teste de vazamento.
- Mediação sem o grupo correto não acessa telas de mediação.

## ADR 0003 — Conteúdo em texto puro

### Limites por campo `[PROPOSTA]`

| Campo | Limite | Onde valida |
|---|---|---|
| Título da solicitação | 80 | form + service + banco |
| Descrição da solicitação | 1.000 | form + service |
| Observações de cuidado | 500 | form + service |
| Mensagem de chat | 1.000 | form + service |
| Comentário de avaliação | 300 | form + service |
| Justificativa de mediação/recusa | 500 | form + service |
| Descrição de denúncia | 500 | form + service |
| Observações do horário | 200 | form + service |
| Nome do usuário | 120 | form + banco |
| E-mail | 200 | form + banco |
| Condição não listada | 100 | form + service |
| Limitações/necessidades do Assistido | 300 | form + service |
| Observações internas da mediação | 500 | form + service |

### Regras de renderização

- Autoescape do Django sempre ativo para conteúdo de usuário.
- `linebreaks`/`linebreaksbr` apenas para quebras de linha.
- Proibidos: `safe`, `mark_safe`, `autoescape off`, `|safe` em qualquer template que renderize conteúdo de usuário — teste automatizado que varre templates.
- URLs digitadas não viram links clicáveis; e-mails e telefones não são auto-linkados.
- Nenhum HTML/Markdown aceito, inclusive em campos internos da mediação.
- Normalização: preservar acentos e emojis; limitar caracteres de controle `[PROPOSTA]`.

### Arquivos (fora do texto puro, regras próprias)

- Foto de perfil e documentos: tipos permitidos, tamanho máximo, verificação de assinatura do arquivo, bucket privado, URL assinada de curta duração (ex.: 5 min), sem indexação.
- Documento nunca é servido por URL pública nem por `MEDIA_URL` estático.
- Nome original do arquivo não é exposto; usar caminho aleatório.

### Moderação

- Mediação pode ocultar conteúdo ofensivo com justificativa; original preservado para auditoria.
- Ocultar não altera nota nem apaga histórico.
- Conteúdo oculto mostra aviso genérico ao usuário comum.

### Critérios de aceite

- Payload `<script>alert(1)</script>` é exibido como texto, em todos os campos, inclusive chat e denúncia.
- Nenhum template de conteúdo de usuário usa `safe`.
- Upload de arquivo com extensão falsa é recusado.
- URL assinada expira e não pode ser reaproveitada.

> **DECISÃO PENDENTE 35:** permitir negrito/itálico em justificativas internas da mediação? Se sim, exige decisão separada e sanitização — hoje está fora do escopo.

## ADR 0004 — Supabase/PostgreSQL

### Configuração de conexão `[PROPOSTA]`

| Variável | Exemplo | Observação |
|---|---|---|
| `DATABASE_URL` | `postgres://elovital_app:...@...:5432/postgres` | modo sessão |
| `DB_SSLMODE` | `verify-full` | com CA do Supabase |
| `DB_CA_CERT` | caminho/segredo | não versionar |
| `DB_CONN_MAX_AGE` | `60` | ajustar ao pooler |
| `DB_STATEMENT_TIMEOUT` | `5000` | evita consulta presa |
| `CPF_HMAC_PEPPER` | segredo | rotação planejada |
| `CPF_ENC_KEY` | segredo | AES-256-GCM |
| `SUPABASE_URL` / `SUPABASE_SERVICE_KEY` | — | somente servidor; nunca no navegador |
| `STORAGE_BUCKET_DOCUMENTOS` | `documentos` | privado |
| `STORAGE_BUCKET_FOTOS` | `fotos` | privado ou público controlado |

**Ambientes:** desenvolvimento, homologação e produção separados; nenhum dado real em desenvolvimento.

### Papéis e privilégios

| Papel | Uso | Privilégios |
|---|---|---|
| `elovital_migrator` | migrations | DDL; único com exceção de IP na auditoria |
| `elovital_app` | aplicação | DML nas tabelas de domínio; sem DDL; sem `DELETE` em domínio |
| `elovital_readonly` | leitura operacional | views mascaradas |
| `anon` / `authenticated` | Data API | sem privilégios no schema da aplicação |

**RLS:** defesa em profundidade; a autorização real fica nos services. Testes de permissão continuam obrigatórios.

### Schema e migrations

- Schema `elovital`, separado de `public` e de `extensions`.
- Django é a fonte de verdade do schema; SQL da proposta é referência.
- Migrations destrutivas exigem janela, backup e revisão.
- Toda migration é testada em banco vazio e em cópia anonimizada.

### Extensões

`postgis` (distância), `pgcrypto` (apoio), `pg_trgm` e `unaccent` (busca), `pg_cron` (rotinas), `pgaudit`/logs (auditoria, conforme plano).

> **DECISÃO PENDENTE 36:** `pg_cron` executa funções SQL, mas a regra de negócio está nos services (ADR 0001). Definir se as rotinas chamam funções de banco (já validadas) ou comando Django agendado externamente.

### Backup, continuidade e custos

| Item | Proposta |
|---|---|
| Backup | diário + PITR 7 dias (plano Pro) |
| Teste de restauração | trimestral, com registro |
| RPO / RTO | 24 h (ou minutos com PITR) / 4 h `[PROPOSTA]` |
| Monitoramento | disponibilidade, conexões, latência, erros, uso de disco |
| Continuidade | plano para indisponibilidade do provedor; comunicação aos usuários |
| Custos | plano, backup, Storage, tráfego, e-mail, mapas — estimativa ausente |

> **DECISÃO PENDENTE 37:** existe orçamento mensal máximo? Sem isso, a decisão de plano não pode ser fechada.

### Desenvolvimento local

- Supabase CLI/Docker ou PostgreSQL 16 + PostGIS.
- Seed de dados sintéticos; nunca dados reais.
- Testes de integração usam banco efêmero.

### Critérios de aceite

- Aplicação não consegue executar DDL nem `DELETE` em tabelas de domínio.
- `anon`/`authenticated` não leem tabelas base.
- Reserva concorrente testada com duas conexões.
- Restauração de backup testada e documentada.
- Nenhum segredo no Git (verificação automatizada).

---

# Parte III — ADRs sugeridos (ainda não escritos)

Estas decisões existem no PRD ou na proposta de banco, mas não têm ADR próprio. Sugestão de numeração a partir de 0005.

## ADR 0005 — Notificações (central, e-mail, push)

- **Contexto:** RF010 e RNF004 exigem múltiplos canais, preferências e envio em até 1 minuto.
- **Decisão sugerida:** central interna como fonte de verdade; envio assíncrono com outbox; e-mail via provedor transacional; push fora da v1; templates versionados; retentativa com backoff; avisos obrigatórios não desativáveis; sem conteúdo sensível no e-mail.
- **Consequências:** exige worker/fila e monitoramento de bounce; notificação nunca bloqueia a operação principal.
- **Decidir:** provedor de e-mail, política de retentativa, retenção de notificações, texto dos e-mails.

## ADR 0006 — Geolocalização, CEP e busca

- **Contexto:** RF004, RF007, RNF009 e RNF013.
- **Decisão sugerida:** CEP via provedor com cache local; IBGE para municípios/UF; PostGIS para distância; distância arredondada; raio máximo; fallback quando CEP falha ou coordenada não existe.
- **Consequências:** dependência externa, custo de geocodificação, privacidade do endereço.
- **Decidir:** provedor de CEP/mapas, precisão da distância, o que fazer sem coordenada.

## ADR 0007 — Armazenamento de arquivos e documentos

- **Contexto:** RF001, RF003, RF016 e ADR 0004.
- **Decisão sugerida:** bucket privado, URL assinada curta, validação de tipo/tamanho/assinatura, expurgo programado, sem imagem pública permanente, sem conteúdo de documento no banco.
- **Consequências:** custo de Storage, rotina de expurgo, necessidade de teste de URL expirada.
- **Decidir:** tamanho máximo, tipos aceitos, antivírus, retenção por tipo, o que ocorre se o expurgo falhar.

## ADR 0008 — Observabilidade e logs sem dados pessoais

- **Contexto:** RNF012, RNF011 e ADR 0004.
- **Decisão sugerida:** logs estruturados com id de correlação; proibição de CPF/telefone/endereço/saúde/conteúdo de chat; retenção de logs; alertas de erro; métricas por fluxo; acesso a logs restrito.
- **Consequências:** depuração exige mascaramento; auditoria fica separada do log técnico.
- **Decidir:** ferramenta, retenção, alertas, integração com Sentry ou equivalente.

## ADR 0009 — Qualidade e testes

- **Contexto:** critério de conclusão 6 do PRD.
- **Decisão sugerida:** testes unitários de service, integração de views, concorrência de reserva, testes de arquitetura (ADR 0001), testes de acessibilidade automatizados, cobertura mínima `[PROPOSTA: 85% em services]`, suíte obrigatória em CI.
- **Consequências:** custo de manutenção; testes de concorrência exigem banco real.
- **Decidir:** cobertura mínima, ferramenta de CI, testes E2E ou não.

## ADR 0010 — Segurança da aplicação web

- **Contexto:** RNF011, RNF012 e dados sensíveis.
- **Decisão sugerida:** HTTPS obrigatório, HSTS, CSP, cookies `Secure`/`HttpOnly`/`SameSite`, CSRF, rate limit em login e recuperação, validação de upload, proteção contra enumeração de contas, dependências auditadas.
- **Consequências:** CSP pode exigir ajuste de assets; rate limit exige armazenamento.
- **Decidir:** política de CSP, limite de requisições, gestão de dependências.

## ADR 0011 — Parâmetros de produto e cadastros-base

- **Contexto:** RNF010 e RF015.
- **Decisão sugerida:** tabela `parametro` com chave, valor tipado, faixa, descrição, autor e auditoria; cadastros-base versionados; alteração nunca invalida registros existentes; tela de mediação com histórico.
- **Consequências:** exige cache e invalidação; valores inválidos precisam de validação forte.
- **Decidir:** quem aprova mudança crítica (ex.: prazo de conclusão), se há dupla aprovação.

---

# Parte IV — Checklist de revisão

## PRD

- [ ] Confirmar metas e indicadores (Decisão 1).
- [ ] Definir status da conta recusada (Decisão 5).
- [ ] Definir canal de contestação para conta suspensa/bloqueada (Decisão 6).
- [ ] Definir quando o Assistido envia documento + selfie (Decisões 2 e 28).
- [ ] Resolver a conclusão após 72 h com uma confirmação (Decisões 3, 7 e 23).
- [ ] Resolver o limite de solicitações ativas (Decisão 13).
- [ ] Definir "Em andamento" e o status "Liberado" (Decisões 7 e 9).
- [ ] Definir se a agenda tem validação prévia da mediação (Decisão 12).
- [ ] Definir granularidade dos períodos da solicitação (Decisão 8).
- [ ] Definir a regra de detecção de pedido de dinheiro (Decisão 14).
- [ ] Definir calendário de horas úteis e efeito do vencimento (Decisões 15 e 16).
- [ ] Confirmar liberação de e-mail após a reserva × chat exclusivo (Decisão 19).
- [ ] Definir remarcação e reincidência de cancelamentos (Decisões 20 e 21).
- [ ] Confirmar selo e o termo "apoiadores do próprio usuário" (Decisões 17 e 18).
- [ ] Definir suspensão preventiva e reservas ativas (Decisão 4).
- [ ] Definir efeito das 72 h no indicador de sucesso (Decisão 24).
- [ ] Definir sinalização de notas 1–2 sem comentário (Decisão 25).
- [ ] Aprovar o comportamento da suspensão automática (Decisão 26).
- [ ] Definir indicadores obrigatórios da v1 (Decisão 27).
- [ ] Definir reativação da própria conta (Decisão 11).
- [ ] Confirmar se a verificação é preservada após o expurgo do arquivo (Decisão 29).
- [ ] Definir rotina automática: frequência, janela e falha (Decisão 30).
- [ ] Confirmar se 2FA e push ficam fora da v1 (Decisões 10 e 22).
- [ ] Aprovar a tabela de parâmetros e valores padrão (seção 6).
- [ ] Aprovar critérios de aceite por RF.

## ADRs

- [ ] Aprovar estrutura de apps e catálogo de services (Decisão 31).
- [ ] Definir fonte de verdade da reserva atômica: service ou função SQL (Decisão 32).
- [ ] Aprovar política de senha e sessão (Decisão 33).
- [ ] Confirmar exceções do texto puro e limites por campo (Decisão 35).
- [ ] Registrar a exceção de anonimização do CPF (Decisão 34).
- [ ] Definir rotina automática: `pg_cron` ou comando Django (Decisão 36).
- [ ] Definir orçamento e plano Supabase (Decisão 37).
- [ ] Escolher provedor de e-mail, CEP/mapas e observabilidade.
- [ ] Decidir quais ADRs sugeridos entram na primeira versão.

## Promoção para os originais

- [ ] Mover os detalhes aprovados de RF001–RF017 para `Docs/specs/` ou para o PRD.
- [ ] Atualizar os ADRs 0001–0004 com as decisões fechadas.
- [ ] Criar os ADRs 0005+ aprovados em `Docs/adr/`.
- [ ] Atualizar a tabela de status em `Docs/README.md`.
- [ ] Remover deste documento o que já foi promovido, mantendo o histórico de decisões.

---

## Apêndice A — Perguntas frequentes de implementação

| Pergunta | Resposta curta | Onde está a regra |
|---|---|---|
| O Apoiador aprova a reserva? | Não; a reserva é imediata | RF008 |
| O telefone aparece em algum momento? | Não; contato pelo chat; o e-mail do perfil pode ser usado como contato de segurança | RF007/RF008/RF011 |
| O e-mail é liberado após a reserva? | RF008 diz que sim; RF011/RS003 restringem o contato ao chat — decisão pendente 19 | RF008/RF011 |
| O Assistido pode ver a condição crônica do Apoiador? | Não; e o Apoiador também não vê a do Assistido | RF007 |
| A mediação pode excluir uma conta? | Não; apenas status e anonimização | RF015/RNF006 |
| Uma solicitação pode ter duas reservas? | Não | RF008 |
| Um horário pode ser oferecido a duas pessoas? | Não | RF004/RF008 |
| Cancelar com 23h59 libera o horário? | Não; vai para a mediação | RF009 |
| Uma confirmação conclui o atendimento? | Não antes de 72 h; após esse prazo, RF012 manda concluir com "confirmado por uma das partes" (decisões 3 e 23) | RF012 |
| A nota pode ser editada? | Não; o comentário pode ser ocultado | RF013 |
| Denúncia bloqueia a conta? | Não; exceto suspensão preventiva (3 denunciantes distintos em 30 dias) | RF014 |
| Quem vê o histórico? | O titular e a mediação | RF017 |
| Quem pode criar solicitação? | O Assistido ou o familiar/responsável autorizado no cadastro | RF001/RF005 |
| Existe pagamento em qualquer etapa? | Não | RF008/RNF015 |
