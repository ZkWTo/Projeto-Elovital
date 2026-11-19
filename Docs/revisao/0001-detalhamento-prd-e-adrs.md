# Detalhamento do PRD 0001 e dos ADRs 0001–0004

- **Status:** Documento de revisão — não substitui os originais
- **Data:** 2026-11-18
- **Base:** [PRD 0001](../prd/0001-elovital.md) e [ADRs 0001–0004](../README.md)
- **Complementos consultados:** [Proposta de banco Supabase](../propostas/banco-supabase/README.md), [Modelo de dados](../propostas/banco-supabase/02-modelo-de-dados.md)
- **Objetivo:** transformar o PRD e os ADRs em especificações verificáveis (campos, estados, fluxos, regras, erros e critérios de aceite), apontar lacunas e registrar decisões que faltam, **sem alterar os documentos originais**.
0
## Como usar este documento

1. Leia a parte que interessa e edite diretamente os blocos marcados com **`> DECISÃO PENDENTE`**.
2. Onde houver **`[PROPOSTA]`**, o texto é uma sugestão minha, ainda não aprovada.
3. Depois da revisão, o conteúdo aprovado pode ser promovido para o PRD e para os ADRs originais (ou para specs em `Docs/specs/`).
4. Este arquivo é a fonte de trabalho; o PRD e os ADRs continuam sendo a fonte oficial até a promoção.

Legenda:

- **RF / RNF / RS:** identificadores herdados do Documento de Requisitos.
- **`[PROPOSTA]`**: detalhe inferido, precisa de confirmação.
- **`> DECISÃO PENDENTE`**: ponto em aberto que muda comportamento ou escopo.
- **`[LACUNA]`**: requisito existe no PRD, mas falta detalhe para implementar.

---

## 0. Resumo executivo da revisão

### 0.1 O que este documento acrescenta

| Bloco | O que foi detalhado |
|---|---|
| PRD | Jornadas ponta a ponta, máquinas de estado, campos e validações por RF, matriz de permissões, matriz de notificações, parâmetros com valores sugeridos, critérios de aceite por requisito, RNFs mensuráveis |
| ADR 0001 | Estrutura de apps, catálogo de services, exceções de domínio, regras de view/form/model, testes de arquitetura |
| ADR 0002 | Modelo de `Usuario` campo a campo, estados, política de senha/sessão/bloqueio, criptografia de CPF, fluxo de login e recuperação |
| ADR 0003 | Limites por campo, regras de renderização, o que nunca é convertido em link, tratamento de arquivos e moderação |
| ADR 0004 | Variáveis de ambiente, conexão, papéis, Storage, extensões, backup/RPO/RTO, desenvolvimento local e riscos |
| Novos | ADRs sugeridos (notificações, geolocalização, arquivos, observabilidade, testes, segurança web, parâmetros) |

### 0.2 Lacunas e inconsistências encontradas

| # | Achado | Onde | Impacto | Sugestão |
|---|---|---|---|---|
| 1 | RF016 exige verificação de identidade **também do Assistido** antes da primeira solicitação; o PRD só descreve o envio pelo Apoiador | RF016 | Alto | Detalhar o fluxo do Assistido (documento + selfie) e o estado da conta |
| 2 | "Selo" do Apoiador é citado no catálogo (RF007), mas nunca definido | RF007 | Médio | Definir critérios do selo ou remover o termo |
| 3 | CPF é "imutável" (RF001, ADR 0002) e ao mesmo tempo "zerado na anonimização" | RF001, ADR 0002 | Médio | Registrar a anonimização como única exceção explícita |
| 4 | Prioridade da solicitação: sugerida pelo Assistido e definida pelo mediador, sem régua objetiva | RF005/RF006 | Médio | Definir critérios de prioridade Alta/Média/Baixa |
| 5 | "Períodos possíveis" da solicitação não têm granularidade definida (manhã/tarde/noite ou faixa de horário) | RF005 | Alto | Escolher o modelo (sugestão: data + faixa) |
| 6 | Prazos são "configuráveis" (RNF010), mas não há catálogo de parâmetros nem valores padrão | RNF010 | Alto | Tabela de parâmetros da seção 3.6 |
| 7 | Suspensão preventiva com 3 denunciantes em 30 dias não define o que acontece com reservas ativas | RF014 | Alto | Regra de contingência (seção 2.6) |
| 8 | Prazo de 24 horas úteis para análise não define dias úteis, feriados e o que ocorre no vencimento | RF006 | Médio | Definir calendário e escalonamento |
| 9 | RF017 cita "rotina automática", sem frequência, janela e tratamento de falha | RF017 | Médio | Definir cron e idempotência |
| 10 | ADR 0004 cita "RS001" sem lista dos RS no PRD | ADR 0004 | Baixo | Referenciar a lista RS001–RS013 do documento de origem |
| 11 | Não há decisão registrada sobre notificações, geolocalização/CEP, Storage, logs e testes | ADRs | Alto | ADRs sugeridos na seção 4 |
| 12 | ADR 0001 proíbe generic views, mas não define o que fazer com `FormView`/`ListView` do Admin e de telas internas | ADR 0001 | Baixo | Explicitar exceção para o Admin do Django |
| 13 | Recuperação de senha depende de e-mail confiável, sem provedor escolhido | ADR 0002 | Médio | ADR/parametrização de e-mail |
| 14 | Não há regra de concorrência para edição de agenda (dois dispositivos) | RF004 | Médio | Bloqueio otimista com `versao` (já previsto no banco) |

---

# Parte I — PRD detalhado

## 1. Visão e limites do produto

### 1.1 Proposta de valor

O EloVital conecta pessoas com doenças crônicas (Assistidos) a voluntários e profissionais (Apoiadores) para apoio cotidiano não emergencial, com **mediação humana obrigatória** e **contato exclusivamente pelo chat interno**.

### 1.2 O que o produto é e o que não é

| É | Não é |
|---|---|
| Vitrine de disponibilidade com curadoria humana | Serviço de emergência ou substituto do SAMU (192) |
| Canal de combinação de apoio entre pessoas | Mecanismo de despacho automático |
| Chat interno entre as partes de uma reserva | Canal de pagamentos, doações ou gorjetas |
| Registro auditável de decisões da mediação | Prontuário, sistema de saúde ou de conselho profissional |
| Plataforma gratuita para Assistidos e Apoiadores | Aplicativo nativo na primeira versão |

### 1.3 Objetivos mensuráveis `[PROPOSTA]`

| Objetivo | Métrica | Meta inicial |
|---|---|---|
| Reduzir tempo até o primeiro contato | Tempo entre aprovação da solicitação e primeira mensagem | ≤ 48 h (mediana) |
| Garantir curadoria | Solicitações analisadas dentro do prazo | ≥ 95% em 24 h úteis |
| Garantir confiabilidade da agenda | Reservas com dupla confirmação | ≥ 80% |
| Garantir segurança | Denúncias tratadas no prazo | ≥ 90% em 5 dias úteis |
| Garantir qualidade do apoio | Apoios concluídos sem ocorrência | ≥ 85% |

> **DECISÃO PENDENTE 1:** estas metas fazem parte do produto ou são apenas referência interna? Se não houver medição na primeira versão, o bloco deve sair do PRD.

### 1.4 Personas e responsabilidades

| Perfil | Pode | Não pode |
|---|---|---|
| Visitante | Ler termos/política, iniciar cadastro, recuperar acesso | Ver catálogo, criar solicitação, acessar chat |
| Assistido | Criar solicitação, reservar, conversar, confirmar, avaliar, denunciar | Publicar solicitação sem mediação; ver dados completos antes da reserva |
| Apoiador | Publicar agenda, receber reservas, conversar, confirmar, avaliar, denunciar | Oferecer apoio sem verificação; ver condição crônica do Assistido |
| Mediador | Validar contas/solicitações/agendas, tratar denúncias, aplicar sanções, gerir parâmetros e cadastros-base, consultar auditoria | Alterar avaliação, excluir registros, acessar dado sensível sem justificativa registrada |
| Operação técnica `[PROPOSTA]` | Infraestrutura, backup, monitoramento | Acessar dados de saúde sem justificativa e registro |

## 2. Jornadas ponta a ponta

### 2.1 Cadastro e aprovação — Assistido

1. Visitante aceita termos, política de privacidade e consentimento específico de saúde.
2. Informa dados pessoais, CPF, nascimento, contato, endereço e condições crônicas.
3. Envia foto de perfil (opcional) e, na primeira solicitação, documento + selfie (RF016).
4. Conta criada em `em_analise`; login permitido, mas sem acesso a funcionalidades de apoio.
5. Mediador aprova (`ativa`), devolve para correção (`em_correcao`) ou reprova (`reprovada`).
6. Notificação em cada transição.

### 2.2 Cadastro e verificação — Apoiador

1. Cadastro igual ao do Assistido, mais especialidades, modalidade e raio de deslocamento.
2. Envio obrigatório de documento com foto, selfie e registro profissional quando a especialidade for regulamentada.
3. Mediador valida documentos e conta; especialidades regulamentadas só ficam ativas após conferência do registro.
4. Apenas Apoiador `ativo`, verificado e com horário `disponivel` aparece no catálogo.

### 2.3 Solicitação e análise

1. Assistido cria solicitação: tipo de apoio, título, descrição, períodos, local, modalidade, prioridade sugerida.
2. Status inicial `em_analise`; nada é visível a Apoiadores.
3. Mediador aprova (prioridade final), devolve para ajuste ou recusa com justificativa.
4. Aprovada → `aberta`, visível no catálogo de solicitações; expira no prazo configurado.

### 2.4 Descoberta, reserva e contato

1. Assistido filtra apoiadores por especialidade, modalidade, distância e disponibilidade.
2. Abre o perfil público (dados minimizados) e escolhe um horário.
3. Reserva atômica: horário sai do catálogo, solicitação passa a `reservada`, dados completos são liberados para as duas partes.
4. Chat é aberto automaticamente e notificações são enviadas.

### 2.5 Atendimento, conclusão e avaliação

1. As partes combinam detalhes pelo chat.
2. Após o atendimento, cada parte confirma a realização em até 72 h.
3. Com as duas confirmações, a reserva vira `concluida` e a solicitação `concluida`.
4. Cada parte avalia uma vez em até 7 dias: nota 1–5 e comentário opcional.
5. Notas 1–2 com comentário geram sinalização para a mediação.

### 2.6 Cancelamento, falta e denúncia

| Evento | Regra | Efeito |
|---|---|---|
| Cancelamento com ≥ 24 h | Automático | Horário volta a `disponivel`; solicitação volta a `aberta`; registro com autor e motivo |
| Cancelamento com < 24 h | Análise da mediação | Horário fica `bloqueado` até decisão; possível ocorrência |
| Não comparecimento | Reportado pela contraparte | Ocorrência; afeta reputação; mediação decide |
| Suspensão preventiva (3 denunciantes em 30 dias) | Automática e provisória | Conta fica `suspensa`; horários futuros ficam `bloqueados`; reservas ativas vão para a fila da mediação; partes são notificadas sem revelar denunciantes |
| Denúncia improcedente | Mediador encerra | Conta volta ao estado anterior; histórico preservado |

> **DECISÃO PENDENTE 2:** na suspensão preventiva, as reservas ativas são canceladas automaticamente ou aguardam decisão da mediação? A sugestão acima preserva o atendimento e só bloqueia novos compromissos.

> **DECISÃO PENDENTE 3:** quem pode remarcar? O PRD diz "Assistido ou Apoiador cancela ou remarca"; falta dizer se a remarcação gera nova reserva (novo horário) e se consome o limite de uma reserva ativa por solicitação.

## 3. Máquinas de estado

### 3.1 Conta (`usuario.status`)

```text
em_analise ──aprovar──> ativa ──suspender──> suspensa ──reativar──> ativa
    │                     │                     │
    ├─devolver──> em_correcao ──reenviar──> em_analise
    ├─reprovar──> reprovada
    └──────────────────────────────────────> bloqueada (definitivo)
ativa ──desativar (próprio usuário)──> desativada ──> (eliminação/anonimização)
```

Regras:

- `em_analise`/`em_correcao`: login permitido, funcionalidades de apoio bloqueadas.
- `suspensa`: login permitido para ver notificações e contestar; criar/editar/reservar bloqueado.
- `bloqueada`: login bloqueado; apenas leitura de notificações por e-mail `[PROPOSTA]`.
- `desativada`: login bloqueado; dados mantidos até expurgo/anonimização.
- `is_active` do Django não substitui `status` (ADR 0002).
- Toda transição grava ator, motivo, data/hora em auditoria.

### 3.2 Solicitação

```text
em_analise ──aprovar──> aberta ──reservar──> reservada ──concluir──> concluida
    │                     │                     │
    ├─devolver──> em_ajuste ──reenviar──> em_analise
    ├─recusar──> recusada
    └─────────────────────┴──cancelar──> cancelada
aberta ──expirar──> expirada
reservada ──cancelar reserva (≥24h)──> aberta
```

Regras:

- `em_ajuste` mostra ao Assistido o motivo e o prazo de reenvio `[PROPOSTA: 7 dias]`.
- `aberta` exige `expira_em` definido.
- `reservada` admite no máximo uma reserva não cancelada.
- `concluida`, `recusada`, `cancelada` e `expirada` são finais para edição de conteúdo.

### 3.3 Horário

```text
disponivel ──reservar──> reservado ──concluir──> realizado
    │                        │
    │                        └─cancelar (≥24h)──> disponivel
    └─expirar (passou da hora)──> expirado
qualquer estado (mediação) ──> bloqueado ──decisão──> disponivel | cancelado
```

Regras:

- Horário `reservado` não aparece no catálogo.
- `bloqueado` só é alterado pela mediação.
- Edição de horário publicado é permitida somente enquanto `disponivel` e sem reserva; usa bloqueio otimista (`versao`).
- Horário no passado não pode ser publicado nem reaberto.

### 3.4 Reserva

```text
ativa ──1ª confirmação──> confirmada_parcial ──2ª confirmação──> concluida
  │                              │
  ├─cancelar (≥24h)──> cancelada  └─prazo de 72h vencido──> expirada_sem_confirmacao
  ├─cancelar (<24h)──> em_analise_mediacao ──> cancelada | concluida | falta
  └─não comparecimento──> falta_registrada
```

### 3.5 Documento de verificação

`pendente → em_analise → aprovado | reprovado → expurgado`

- Reenvio cria novo registro; o anterior é mantido para auditoria até o expurgo.
- Reprovação exige motivo e permite novo envio `[PROPOSTA: até 3 tentativas]`.

### 3.6 Denúncia, sanção e contestação

```text
denuncia: recebida → em_analise → procedente | improcedente → arquivada
sancao:   ativa → cumprida | revogada (por contestação)
contestacao: aberta → em_analise → deferida | indeferida
```

- Sanção sempre com tipo, motivo, prazo, mediador e vínculo com a denúncia/ocorrência.
- Contestação preferencialmente analisada por outro mediador (regra 8 do PRD).

## 4. Requisitos funcionais detalhados

Formato de cada requisito: atores, pré-condições, fluxo, campos, regras, erros e critérios de aceite.

### RF001 — Cadastro de usuário

**Atores:** Visitante.
**Pré-condições:** termos e política disponíveis; e-mail e CPF ainda não cadastrados.

**Campos `[PROPOSTA]`:**

| Campo | Tipo | Obrigatório | Validação |
|---|---|---|---|
| Papel | escolha | Sim | `ASSISTIDO` ou `APOIADOR`; imutável após aprovação |
| Nome completo | texto | Sim | 3–150 caracteres; sem dígitos |
| E-mail | e-mail | Sim | único, sem diferenciar maiúsculas; confirmação por link |
| Senha | senha | Sim | mínimo 8, sem senha comum, sem dado pessoal, confirmação |
| CPF | texto | Sim | 11 dígitos, dígitos verificadores, único; imutável |
| Data de nascimento | data | Sim | ≥ 18 anos completos na data do cadastro |
| Celular | texto | Sim | formato brasileiro; armazenado cifrado |
| CEP | texto | Sim | 8 dígitos; consulta externa; preenche endereço |
| Logradouro, número, complemento | texto | Sim/Sim/Não | número pode ser "s/n" |
| Bairro, município, UF | texto | Sim | UF válida (IBGE) |
| Foto de perfil | arquivo | Não | JPG/PNG/WebP; ≤ 5 MB; conteúdo validado |
| Termos, privacidade, código de conduta | aceite | Sim | versão registrada com data/hora e IP hasheado |
| Consentimento de saúde | aceite | Sim (Assistido) | específico e destacado (dado sensível) |
| Condições crônicas | múltipla escolha | Sim (Assistido) | catálogo editável pela mediação |
| Contato de emergência | texto + telefone | Sim (Assistido) | telefone cifrado |
| Especialidades | múltipla escolha | Sim (Apoiador) | catálogo; indica quais exigem registro |
| Registro profissional | texto | Condicional | obrigatório se especialidade regulamentada |
| Modalidade e raio | escolha + número | Sim (Apoiador) | raio entre 1 e 100 km `[PROPOSTA]` |

**Fluxo principal:** preenche → valida → cria conta `em_analise` → envia e-mail de confirmação → notifica mediação.

**Exceções:**

| Situação | Resposta |
|---|---|
| CPF já cadastrado | Erro genérico de cadastro, sem revelar qual conta existe `[PROPOSTA]` |
| E-mail já cadastrado | Erro de "e-mail indisponível" (e-mail não é dado sensível) |
| Menor de 18 anos | Bloqueio explícito com orientação |
| Falha na consulta de CEP | Endereço pode ser preenchido manualmente |
| Upload inválido | Mensagem por campo; arquivo descartado |

**Critérios de aceite:**

- Duas contas não podem ter o mesmo CPF nem o mesmo e-mail (comparação sem caixa).
- O CPF não aparece em tela, log ou resposta de API após o cadastro; exibição apenas mascarada para a mediação.
- Conta recém-criada não acessa catálogo nem cria solicitação antes da aprovação.
- Toda tentativa de cadastro de menor é recusada com mensagem clara.

> **DECISÃO PENDENTE 4:** confirmar se o Assistido precisa de documento + selfie **no cadastro** ou apenas antes da primeira solicitação (o PRD diz a segunda opção). A escolha muda o estado da conta.

### RF002 — Autenticação e recuperação de acesso

**Fluxo de login:** e-mail + senha → verifica bloqueio → autentica → verifica `status` → inicia sessão.

| Regra | Valor sugerido |
|---|---|
| Expiração por inatividade | 30 minutos |
| Duração absoluta da sessão | 12 horas `[PROPOSTA]` |
| Tentativas falhas antes do bloqueio | 5 por e-mail e por IP |
| Duração do bloqueio | 15 minutos |
| Link de recuperação | uso único, 1 hora, invalidado após uso |
| Resposta de recuperação | sempre "se o e-mail existir, enviaremos instruções" |
| Encerramento | botão "sair" encerra a sessão atual; opção "sair de todos os dispositivos" `[PROPOSTA]` |

**Exceções:**

- Conta `em_analise`: login permitido, com aviso de pendência.
- Conta `suspensa`: login permitido, funcionalidades restritas.
- Conta `bloqueada`/`desativada`: login recusado com orientação de contato com a mediação.
- Token expirado ou já usado: mensagem genérica + opção de novo pedido.

**Critérios de aceite:**

- Nenhuma resposta permite descobrir se um e-mail existe (login, recuperação e cadastro).
- Após 30 minutos sem requisição autenticada, a próxima ação exige novo login.
- Tentativas falhas repetidas bloqueiam temporariamente mesmo com senha correta.
- Logout invalida a sessão no servidor.

> **DECISÃO PENDENTE 5:** 2FA está fora do escopo. A sessão absoluta de 12 h e o "sair de todos os dispositivos" ficam na primeira versão ou viram backlog?

### RF003 — Perfil, preferências e privacidade

**Seções do perfil:** dados pessoais, contato, endereço, foto, condições (Assistido), especialidades e registro (Apoiador), preferências de notificação, privacidade, segurança, consentimentos.

**Regras:**

- CPF exibido mascarado e sem edição.
- Alteração de e-mail exige confirmação no endereço novo e no antigo `[PROPOSTA]`.
- Alteração de especialidade ou registro volta o item para `em_analise`; o Apoiador continua ativo nas demais.
- Alteração de nome, foto e endereço não exige mediação; alteração de documento, sim.
- Desativação da conta: imediata, com aviso de efeitos (agenda suspensa, reservas futuras canceladas ou encaminhadas).
- Solicitação de eliminação: registrada, com prazo de resposta e efeito de anonimização, preservando obrigações legais e auditoria.
- Histórico de alterações visível ao usuário e à mediação `[LACUNA: definir quais campos entram no histórico]`.

**Critérios de aceite:**

- Nenhuma rota permite alterar CPF, papel ou data de nascimento após aprovação.
- Toda alteração sensível gera auditoria com antes/depois mascarado.
- Desativar a conta impede novos logins e remove a agenda do catálogo.

> **DECISÃO PENDENTE 6:** o usuário pode reativar a própria conta? Em quanto tempo? O PRD só fala em desativar.

### RF004 — Agenda solidária do Apoiador

**Campos do horário:**

| Campo | Regra |
|---|---|
| Especialidade | precisa estar ativa e verificada |
| Modalidade | presencial, remoto `[PROPOSTA: ou ambos]` |
| Local | endereço do Apoiador ou ponto de atendimento `[PROPOSTA]` |
| Data | hoje ou futura, no fuso de Brasília |
| Hora início / fim | duração entre 30 min e 4 h; fim > início |
| Raio de deslocamento | herdado do perfil, editável por horário `[PROPOSTA]` |
| Recorrência | dias da semana, início e fim; nunca gera data passada |
| Observações | texto puro, ≤ 500 caracteres `[PROPOSTA]` |

**Fluxo:** publica → horário `disponivel` → aparece no catálogo após validação (se exigida) → reserva → `reservado`.

**Regras e exceções:**

- Sobreposição de horários do mesmo Apoiador é recusada.
- Horário já iniciado não pode ser editado nem reservado.
- Recorrência que gere sobreposição falha por inteiro (transacional) e informa as datas conflitantes.
- Horário reservado não pode ser excluído; só cancelado com motivo.
- Edição concorrente usa `versao`; se outra pessoa alterou, o salvamento é recusado com recarga.

**Critérios de aceite:**

- Criar recorrência de 4 semanas gera exatamente os horários esperados, sem datas passadas.
- O catálogo nunca mostra horário `reservado`, `bloqueado` ou `expirado`.
- Dois dispositivos editando o mesmo horário: o segundo recebe conflito.

> **DECISÃO PENDENTE 7:** a agenda publicada pelo Apoiador passa por validação prévia da mediação (regra transversal 1) ou só por validação automática? O PRD cita "agendas" na lista de conteúdos verificados.

### RF005 — Criação de solicitação

**Campos:**

| Campo | Regra |
|---|---|
| Tipo de apoio | catálogo de especialidades |
| Título | 3–80 caracteres, texto puro |
| Descrição | 10–1.000 caracteres, texto puro |
| Períodos possíveis | de 1 a 5 opções de data + faixa (manhã/tarde/noite ou intervalo) |
| Local | endereço cadastrado ou outro endereço de atendimento |
| Modalidade | presencial ou remoto |
| Prioridade sugerida | baixa, média, alta |
| Observações de cuidado | ≤ 500 caracteres, acesso restrito após reserva |

**Regras:**

- Nasce `em_analise`; não é visível a Apoiadores.
- Prioridade Alta exibe aviso fixo: "O EloVital não substitui o SAMU (192)".
- Limite de solicitações abertas por Assistido `[PROPOSTA: 3]`.
- Não é permitido criar solicitação com data inteiramente no passado.
- Texto puro, sem links clicáveis (ADR 0003).

**Critérios de aceite:**

- Solicitação criada não aparece em nenhuma listagem pública antes da aprovação.
- Aviso do SAMU aparece em toda tela de prioridade Alta, inclusive na edição.
- Prazo de expiração é gravado na criação e pode ser ajustado pela mediação.

> **DECISÃO PENDENTE 8:** limite de solicitações abertas por Assistido: existe? Qual valor?

### RF006 — Análise e validação da solicitação

**Fila da mediação:** ordenada por prioridade sugerida e tempo de espera; mostra prazo restante.

**Decisões:** aprovar (com prioridade final), devolver para ajuste (com motivo) ou recusar (com justificativa).

**Regras:**

- Prazo de 24 horas úteis, contado em calendário definido `[PROPOSTA: seg–sex, 9h–18h, feriados nacionais]`.
- Aprovação define `expira_em`.
- Recusa e devolução exigem justificativa ≤ 500 caracteres.
- Nenhuma publicação automática, mesmo com prazo vencido; o vencimento gera alerta.
- Toda decisão gera auditoria com mediador, parecer e data/hora.

**Critérios de aceite:**

- Não existe caminho de código que publique solicitação sem `analise_solicitacao` aprovada.
- Mediação consegue ver o histórico completo de análises anteriores.
- Solicitação fora do prazo aparece destacada.

> **DECISÃO PENDENTE 9:** o que acontece se o prazo estourar? Alerta apenas, escalonamento para outro mediador ou aprovação tácita (não recomendada)?

### RF007 — Catálogo de apoiadores e filtros

**Dados exibidos antes da reserva:** primeiro nome, foto (se houver), especialidades, selo, nota média, total de avaliações, apoios concluídos, bairro/município, distância aproximada, modalidade, faixa de disponibilidade.

**Nunca exibidos antes da reserva:** sobrenome, CPF, telefone, e-mail, endereço exato, condição crônica, observações de cuidado.

**Filtros:** especialidade, modalidade, distância máxima, data/período, avaliação mínima `[PROPOSTA]`.

**Regras:**

- Somente Apoiador `ativo`, verificado e com ao menos um horário `disponivel`.
- Distância calculada por geolocalização, arredondada (ex.: "cerca de 3 km").
- Paginação de 10 registros, com ordenação estável e parâmetros na URL.
- Nenhum campo sensível em resposta de busca, inclusive em HTML e JSON.

**Critérios de aceite:**

- Busca vazia retorna mensagem orientativa, não erro.
- Nenhum dado proibido aparece no HTML do catálogo (teste automatizado por campo).
- Apoiador suspenso some imediatamente do catálogo.

> **DECISÃO PENDENTE 10:** selo: o que significa? Sugestões: (a) identidade verificada, (b) registro profissional conferido, (c) ambos. Hoje "selo" e "verificado" parecem a mesma coisa.

> **DECISÃO PENDENTE 11:** o catálogo lista **apoiadores** ou **horários**? A reserva é por horário, então a listagem por horário evita um clique e muda a paginação.

### RF008 — Reserva de horário

**Fluxo:** escolhe horário → confirma → transação bloqueia horário e solicitação → cria reserva → atualiza status → libera dados → abre chat → notifica.

**Regras:**

- Reserva imediata, sem aprovação do Apoiador.
- Uma reserva ativa por solicitação; uma reserva não cancelada por horário.
- Falha por concorrência retorna mensagem clara e horário alternativo.
- Dados liberados após a reserva: nome completo, endereço do atendimento, ponto de referência, observações de cuidado, modalidade. Telefone nunca.
- Sem pagamento em nenhuma etapa.

**Critérios de aceite:**

- Duas requisições simultâneas no mesmo horário: exatamente uma reserva.
- Reserva não é criada se a solicitação não pertence ao Assistido ou não está `aberta`.
- Após a reserva, o horário desaparece do catálogo imediatamente.

### RF009 — Cancelamento e remarcação

| Antecedência | Quem decide | Efeito no horário | Efeito na solicitação |
|---|---|---|---|
| ≥ 24 h | Automático | Volta a `disponivel` | Volta a `aberta` |
| < 24 h | Mediação | `bloqueado` até decisão | Permanece `reservada` até decisão |
| Após o início | Mediação | `bloqueado` | Ocorrência |

**Regras:** motivo obrigatório (lista + texto livre); autor e data/hora registrados; cancelamentos repetidos geram sinalização `[PROPOSTA: 3 em 90 dias]`; notificação para a contraparte e para a mediação.

**Critérios de aceite:**

- Cancelamento com 23h59min não libera o horário automaticamente.
- Histórico mostra quem cancelou, quando e por quê.
- Remarcação preserva o vínculo com a solicitação e não cria duplicidade.

> **DECISÃO PENDENTE 12:** remarcação é um cancelamento + nova reserva (com as mesmas regras de antecedência) ou um fluxo próprio que troca o horário sem liberar a solicitação?

### RF010 — Notificações

**Canais:** central interna (essencial), e-mail (importante), push (desejável).

**Categorias e obrigatoriedade:**

| Evento | Central | E-mail | Push | Obrigatória |
|---|---|---|---|---|
| Conta aprovada/reprovada/devolvida | Sim | Sim | Sim | Sim |
| Solicitação aprovada/recusada/devolvida | Sim | Sim | Sim | Sim |
| Nova reserva | Sim | Sim | Sim | Sim |
| Cancelamento | Sim | Sim | Sim | Sim |
| Nova mensagem | Sim | Não `[PROPOSTA]` | Sim | Não |
| Lembrete de atendimento (24 h / 2 h) | Sim | Sim | Sim | Não |
| Prazo de confirmação (72 h) | Sim | Sim | Sim | Sim |
| Prazo de avaliação (7 dias) | Sim | Não | Sim | Não |
| Denúncia recebida/resultado | Sim | Sim | Não | Sim |
| Sanção/contestação | Sim | Sim | Não | Sim |
| Termos/segurança | Sim | Sim | Não | Sim |

**Regras:** avisos obrigatórios não podem ser desativados; preferências por categoria (exceto obrigatórias); estado lida/não lida; central paginada `[PROPOSTA: 20]`; notificações antigas expurgadas após `[PROPOSTA: 180 dias]`.

**Critérios de aceite:**

- Desativar uma categoria impede o envio, exceto obrigatórias.
- Notificação de reserva é gerada em até 1 minuto do evento (RNF004).
- Falha de e-mail não impede a central interna nem a reserva (envio assíncrono).

> **DECISÃO PENDENTE 13:** push na primeira versão ou somente backlog? E qual provedor de e-mail?

### RF011 — Canal de conversa (chat)

**Regras:** existe somente entre as duas partes de uma reserva; 1.000 caracteres por mensagem; data, hora e indicador de leitura; sem anexos/áudio/chamada; fim do atendimento → somente leitura por 30 dias → arquivado para auditoria; denúncia de mensagem e bloqueio do outro usuário.

**Estados da mensagem:** `enviada` → `entregue` (opcional) → `lida`.
**Estados do chat:** `aberto` → `somente_leitura` → `arquivado`.

**Regras adicionais `[PROPOSTA]`:**

- Bloqueio entre usuários impede novas mensagens, mas não apaga o histórico nem impede a mediação.
- Mensagem não pode ser editada nem excluída pelo autor; pode ser ocultada pela mediação com registro.
- Conteúdo não vai para log de aplicação.
- Chat só abre após a reserva e fecha no cancelamento `[PROPOSTA: mantém leitura]`.

**Critérios de aceite:**

- Nenhuma rota permite ler chat de reserva da qual o usuário não participa.
- Após 30 dias do fim, novas mensagens são recusadas e o histórico segue legível à mediação.
- Indicador de leitura reflete a última leitura da contraparte.

> **DECISÃO PENDENTE 14:** se a reserva for cancelada, o chat continua aberto por quanto tempo? A regra de 30 dias conta do fim do atendimento ou do cancelamento?

### RF012 — Conclusão do apoio

**Regras:** confirmação das duas partes em até 72 h (configurável); somente atendimentos confirmados entram no indicador de sucesso; sem pagamento; se uma parte não confirmar, a reserva vira `expirada_sem_confirmacao` e a mediação é notificada.

**Critérios de aceite:**

- Uma única confirmação não conclui a reserva.
- Confirmação após o prazo é recusada com orientação para a mediação.
- Conclusão dispara avaliação para as duas partes e atualiza contadores.

> **DECISÃO PENDENTE 15:** o que conta como "sucesso" no perfil: apenas dupla confirmação ou também ausência de ocorrência?

### RF013 — Avaliação

**Campos:** nota (1–5), comentário opcional (≤ 300), critérios `[PROPOSTA: pontualidade, comunicação, cuidado]` — o banco prevê "critérios".

**Regras:** uma por parte por atendimento; prazo de 7 dias; imutável; exibida no perfil como média, total e apoios concluídos; notas 1–2 com comentário geram sinalização; mediação pode ocultar comentário, nunca alterar nota.

**Critérios de aceite:**

- Segunda avaliação da mesma parte é recusada.
- Avaliação fora do prazo é recusada.
- Ocultar comentário não altera a média.
- Média considera apenas avaliações visíveis `[PROPOSTA]`.

> **DECISÃO PENDENTE 16:** notas 1–2 sem comentário também são sinalizadas? O PRD exige comentário nesse caso?

### RF014 — Denúncia

**Campos:** alvo (perfil, solicitação, reserva, mensagem), motivo (lista), descrição (≤ 1.000), evidências `[PROPOSTA: nenhuma na v1]`, autor identificado apenas para a mediação.

**Fluxo:** denuncia → protocolo → `em_analise` → decisão → notificação do resultado.

**Regras:** denúncia não bloqueia automaticamente, exceto suspensão preventiva (3 denunciantes distintos em 30 dias); identidade do denunciante nunca revelada; denúncia contra a mesma pessoa pelo mesmo motivo em 24 h é agrupada `[PROPOSTA]`; prazo de resposta `[PROPOSTA: 5 dias úteis]`.

**Critérios de aceite:**

- Terceira denúncia distinta em 30 dias suspende preventivamente e notifica a mediação.
- Denunciante recebe protocolo e resultado.
- Denúncia improcedente não deixa marca visível no perfil.

> **DECISÃO PENDENTE 17:** a suspensão preventiva é automática ou proposta ao mediador? Automática pode ser abusada por denúncias coordenadas.

### RF015 — Gestão administrativa e mediação

**Telas/filas:** contas pendentes, documentos, solicitações, agendas, denúncias, ocorrências, avaliações sinalizadas, sanções, contestações, parâmetros, cadastros-base, auditoria, indicadores.

**Regras:** toda sanção exige justificativa e registro; sem exclusão física; acesso a dado pessoal exige motivo e gera `acesso_dado_pessoal`; Admin do Django usa os mesmos services (ADR 0001); permissões mínimas por grupo; dupla conferência para bloqueio definitivo `[PROPOSTA]`.

**Critérios de aceite:**

- Mediador não consegue alterar nota de avaliação.
- Nenhuma tela oferece exclusão física de conta, solicitação ou mensagem.
- Acesso a CPF/endereço/documento/condição registra motivo obrigatório.
- Indicadores do painel conferem com consultas de auditoria.

> **DECISÃO PENDENTE 18:** indicadores obrigatórios da primeira versão (ex.: solicitações por status, tempo médio de análise, reservas concluídas, denúncias abertas, cancelamentos por antecedência).

### RF016 — Verificação de identidade e registro profissional

**Documentos:** documento oficial com foto, selfie, registro profissional (quando regulamentado).

**Regras:** acesso restrito; bucket privado; URL assinada de curta duração; expurgo após o prazo necessário (90 dias após a verificação `[PROPOSTA]`); obrigatório para Apoiadores e para a primeira solicitação do Assistido; reprovação exige motivo; documentos nunca aparecem no catálogo.

**Critérios de aceite:**

- Nenhuma URL pública permanente de documento.
- Documento expurgado não pode ser recuperado pela aplicação.
- Especialidade regulamentada sem registro conferido não fica ativa.
- Tentativa de acessar documento de outro usuário é negada e auditada.

> **DECISÃO PENDENTE 19:** guardar o resultado da verificação (aprovado/reprovado) após o expurgo do arquivo? Recomendo sim, sem o arquivo.

### RF017 — Histórico, ocorrências e expiração

**Histórico:** solicitações e reservas do próprio usuário e da mediação; paginação de 10; toda mudança de status registrada; visível apenas ao titular e à mediação.

**Ocorrências:** desistência, não comparecimento, falta de resposta, falha operacional, discordância de conclusão.

**Rotina automática:** expirar solicitações, expirar reservas sem dupla confirmação, gerar lembretes, encerrar chat, expurgar documentos e notificações, calcular sinalizações.

**Regras:** rotina idempotente, com log de execução; falha não pode duplicar notificações; horário de execução `[PROPOSTA: a cada 15 min]`.

**Critérios de aceite:**

- Rodar a rotina duas vezes seguidas não duplica efeitos.
- Solicitação expirada não pode ser reservada.
- Usuário não acessa histórico de terceiros por URL direta.

> **DECISÃO PENDENTE 20:** a rotina roda em `pg_cron` (ADR 0004) ou em comando Django agendado? O ADR sugere `pg_cron`, mas a regra de negócio deve ficar nos services (ADR 0001). Recomendo comando Django idempotente + agendador.

## 5. Matriz de permissões

| Ação | Visitante | Assistido | Apoiador | Mediador |
|---|---|---|---|---|
| Ver termos/política | Sim | Sim | Sim | Sim |
| Cadastrar | Sim | — | — | — |
| Autenticar | Sim | Sim | Sim | Sim |
| Ver catálogo | Não | Sim | Não `[PROPOSTA]` | Sim (visão de auditoria) |
| Criar solicitação | Não | Sim | Não | Não |
| Publicar agenda | Não | Não | Sim | Não |
| Reservar horário | Não | Sim | Não | Não `[PROPOSTA]` |
| Chat da reserva | Não | Participante | Participante | Leitura auditada |
| Confirmar conclusão | Não | Participante | Participante | Não |
| Avaliar | Não | Participante | Participante | Não |
| Denunciar | Não | Sim | Sim | Não |
| Validar conta/solicitação | Não | Não | Não | Sim |
| Aplicar sanção | Não | Não | Não | Sim |
| Editar parâmetros/cadastros-base | Não | Não | Não | Sim |
| Consultar auditoria | Não | Próprio histórico | Próprio histórico | Sim |
| Ver dado sensível | Não | Próprio | Próprio | Com motivo e registro |

## 6. Parâmetros configuráveis (RNF010)

| Parâmetro | Padrão sugerido | Faixa aceitável | Quem altera |
|---|---|---|---|
| `sessao_inatividade_min` | 30 | 5–120 | Mediação |
| `bloqueio_tentativas` | 5 | 3–10 | Mediação |
| `bloqueio_duracao_min` | 15 | 5–60 | Mediação |
| `solicitacao_prazo_analise_horas_uteis` | 24 | 4–72 | Mediação |
| `solicitacao_validade_dias` | 30 | 7–90 | Mediação |
| `solicitacao_max_abertas` | 3 | 1–10 | Mediação |
| `horario_duracao_min_min` | 30 | 30–60 | Mediação |
| `horario_duracao_max_h` | 4 | 2–8 | Mediação |
| `reserva_antecedencia_cancelamento_h` | 24 | 12–72 | Mediação |
| `conclusao_prazo_h` | 72 | 24–168 | Mediação |
| `avaliacao_prazo_dias` | 7 | 3–30 | Mediação |
| `chat_somente_leitura_dias` | 30 | 15–90 | Mediação |
| `denuncia_suspensao_qtd` | 3 | 2–10 | Mediação |
| `denuncia_suspensao_janela_dias` | 30 | 15–90 | Mediação |
| `catálogo_pagina` | 10 | 10–50 | Produto |
| `historico_pagina` | 10 | 10–50 | Produto |
| `documento_retencao_dias` | 90 | 30–365 | Jurídico/mediação |
| `mensagem_max_caracteres` | 1.000 | 200–2.000 | Produto |
| `avaliacao_comentario_max` | 300 | 100–1.000 | Produto |

Regras: alteração registrada em auditoria com valor anterior, novo valor, autor e justificativa; parâmetro não pode ser reduzido a ponto de invalidar registros existentes (ex.: duração de horário já publicado).

## 7. Requisitos não funcionais mensuráveis

| RNF | Critério verificável |
|---|---|
| RNF001 usabilidade | Teste com 5 usuários `[PROPOSTA]`: concluir cadastro e reserva sem ajuda em ≤ 10 min; nenhuma tela com mais de 7 decisões |
| RNF002 acessibilidade | Auditoria WCAG 2.1 AA: contraste ≥ 4.5:1, foco visível, navegação completa por teclado, alvos ≥ 44×44 px, testes com leitor de tela |
| RNF003 compatibilidade | Responsivo de 320 px a 1920 px; matriz de testes nas 2 últimas versões de Chrome, Firefox, Edge e Safari |
| RNF004 desempenho | p95 ≤ 3 s em páginas e consultas; notificação de reserva/cancelamento em ≤ 60 s; catálogo ≤ 1 s `[PROPOSTA]` |
| RNF005 disponibilidade | 99% mensal, com janela de manutenção anunciada; monitoramento externo |
| RNF006 integridade | Testes de concorrência para reservar/cancelar/remarcar/concluir; nenhuma escrita parcial |
| RNF007 mensagens de erro | Revisão de texto: o que ocorreu, por que, como corrigir; campo destacado com `aria-invalid` |
| RNF008 backup | Diário + PITR; teste de restauração trimestral com registro; RPO ≤ 24 h (PITR reduz), RTO ≤ 4 h `[PROPOSTA]` |
| RNF009 escalabilidade | Índices do modelo de dados; paginação em toda listagem; consulta de distância no banco |
| RNF010 parametrização | Parâmetros da seção 6 editáveis sem deploy |
| RNF011 LGPD | Base legal por finalidade, consentimento de saúde, minimização, direitos do titular, política publicada, DPIA `[PROPOSTA]` |
| RNF012 auditabilidade | Login, validações, reservas, cancelamentos, sanções, acessos da mediação e status com ator, ação, data/hora; imutável |
| RNF013 integrações | CEP, IBGE, mapas, e-mail; push opcional; falha externa não bloqueia o fluxo principal |
| RNF014 localização | pt-BR, dd/mm/aaaa, fuso de Brasília; testes de horário de verão inexistente `[PROPOSTA]` |
| RNF015 gratuidade | Nenhuma tela coleta dado financeiro; revisão de conteúdo |

## 8. Rastreabilidade

| Requisito | ADR principal | Tabelas | Teste mínimo |
|---|---|---|---|
| RF001 | 0002 | `usuario`, `endereco`, `termo_aceite`, perfis | Cadastro com CPF duplicado e menor de idade |
| RF002 | 0002 | `usuario`, `tentativa_login` | Bloqueio e recuperação de uso único |
| RF003 | 0002, 0003 | `usuario`, perfis, `termo_aceite` | CPF imutável; auditoria de alteração |
| RF004 | 0001, 0004 | `horario`, `recorrencia` | Recorrência sem data passada e sem sobreposição |
| RF005 | 0001, 0003 | `solicitacao`, `solicitacao_periodo` | Não publica sem mediação |
| RF006 | 0001 | `analise_solicitacao`, `audit_log` | Recusa com justificativa e auditoria |
| RF007 | 0001, 0004 | views de catálogo | Nenhum dado proibido no HTML |
| RF008 | 0001, 0004 | `reserva`, `horario` | Duas reservas concorrentes |
| RF009 | 0001 | `reserva`, `ocorrencia` | 24 h vs. 23h59 |
| RF010 | 0005 (sugerida) | `notificacao` | Obrigatória não desativável |
| RF011 | 0001, 0003 | `mensagem`, `bloqueio_usuario` | Acesso indevido ao chat |
| RF012 | 0001 | `reserva_confirmacao` | Uma confirmação não conclui |
| RF013 | 0003 | `avaliacao` | Avaliação duplicada e fora do prazo |
| RF014 | 0001 | `denuncia`, `sancao` | Suspensão preventiva |
| RF015 | 0001, 0002 | `sancao`, `parametro`, auditoria | Mediação não altera nota |
| RF016 | 0004 | `documento_verificacao` | Documento sem URL pública |
| RF017 | 0001, 0004 | `ocorrencia`, logs | Rotina idempotente |

---

# Parte II — ADRs detalhados

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

> **DECISÃO PENDENTE 21:** confirmar a lista de apps e o catálogo de services, ou manter apenas a convenção sem estrutura fixa.

> **DECISÃO PENDENTE 22:** `select_for_update` dentro do service ou em função de banco (ADR 0004 usa função SQL)? O ideal é uma única implementação; a proposta de banco já traz `reservar_horario` em SQL. Escolher uma fonte de verdade evita divergência.

## ADR 0002 — Usuário, papéis e autenticação

### Modelo `Usuario` detalhado `[PROPOSTA]`

| Campo | Tipo | Regra |
|---|---|---|
| `id` | UUID | chave exposta |
| `email` | citext único | login; normalizado |
| `password` | hash | Django; validadores |
| `nome_completo` | texto | 3–150 |
| `cpf_hash` | texto único | HMAC-SHA-256 com pepper; nunca exposto |
| `cpf_cifrado` | binário/texto | AES-256-GCM; leitura auditada |
| `data_nascimento` | data | ≥ 18 anos; imutável após aprovação |
| `celular_cifrado` | texto | cifrado; exibido apenas ao titular e mediação |
| `papel` | enum | `ASSISTIDO`, `APOIADOR`, `MEDIADOR` |
| `status` | enum | estados da seção 3.1 |
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
  → verifica status
     ├─ bloqueada/desativada → recusa com orientação
     ├─ em_analise/em_correcao → sessão restrita
     └─ ativa/suspensa → sessão normal (suspensa com restrições)
  → aplica sessão (expiração por inatividade)
```

**Recuperação:** pedido → sempre mesma resposta → token de uso único (1 h) → definição de nova senha → invalidação do token e das sessões existentes `[PROPOSTA]`.

**Senha:** mínimo 8 caracteres; sem senhas comuns; sem semelhança com dados pessoais; sem sequências óbvias; confirmação; mensagens claras.

> **DECISÃO PENDENTE 23:** política de senha mais rígida (12 caracteres, histórico de 5, troca periódica)? A recomendação atual do NIST é comprimento mínimo e verificação contra vazamentos, sem troca periódica obrigatória.

### CPF — criptografia e ciclo de vida

- HMAC para unicidade/busca; cifra para exibição autorizada.
- Pepper e chave fora do banco (variável de ambiente/cofre).
- Rotação exige script de recifragem com janela e backup.
- CPF nunca em log, relatório, exportação ou tela do catálogo.
- Anonimização zera `cpf_hash` e `cpf_cifrado` — **única exceção** à imutabilidade; deve constar no PRD e no ADR.

> **DECISÃO PENDENTE 24:** confirmar a exceção de anonimização no texto do PRD/ADR, para eliminar a contradição com "CPF imutável".

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
| Descrição de denúncia | 1.000 | form + service |
| Observações do horário | 500 | form + service |
| Nome do usuário | 150 | form + banco |

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

> **DECISÃO PENDENTE 25:** permitir negrito/itálico em justificativas internas da mediação? Se sim, exige decisão separada e sanitização — hoje está fora do escopo.

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

> **DECISÃO PENDENTE 26:** `pg_cron` executa funções SQL, mas a regra de negócio está nos services (ADR 0001). Definir se as rotinas chamam funções de banco (já validadas) ou comando Django agendado externamente.

### Backup, continuidade e custos

| Item | Proposta |
|---|---|
| Backup | diário + PITR 7 dias (plano Pro) |
| Teste de restauração | trimestral, com registro |
| RPO / RTO | 24 h (ou minutos com PITR) / 4 h `[PROPOSTA]` |
| Monitoramento | disponibilidade, conexões, latência, erros, uso de disco |
| Continuidade | plano para indisponibilidade do provedor; comunicação aos usuários |
| Custos | plano, backup, Storage, tráfego, e-mail, mapas — estimativa ausente |

> **DECISÃO PENDENTE 27:** existe orçamento mensal máximo? Sem isso, a decisão de plano não pode ser fechada.

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
- [ ] Definir fluxo de verificação do Assistido (Decisão 4).
- [ ] Definir granularidade dos períodos da solicitação (Decisão 8).
- [ ] Definir o que é o "selo" (Decisão 10).
- [ ] Definir catálogo por apoiador ou por horário (Decisão 11).
- [ ] Definir remarcação (Decisão 12).
- [ ] Definir suspensão preventiva e reservas ativas (Decisão 2).
- [ ] Definir comportamento no vencimento do prazo de análise (Decisão 9).
- [ ] Definir prazo e efeito do bloqueio entre usuários (Decisão 14).
- [ ] Confirmar se 2FA e push ficam fora da v1 (Decisões 5 e 13).
- [ ] Aprovar a tabela de parâmetros e valores padrão (seção 6).
- [ ] Aprovar critérios de aceite por RF.
- [ ] Registrar a exceção de anonimização do CPF (Decisão 24).

## ADRs

- [ ] Aprovar estrutura de apps e catálogo de services (Decisão 21).
- [ ] Definir fonte de verdade da reserva atômica: service ou função SQL (Decisão 22).
- [ ] Aprovar política de senha e sessão (Decisão 23).
- [ ] Confirmar exceções do texto puro e limites por campo (Decisão 25).
- [ ] Definir rotina automática: `pg_cron` ou comando Django (Decisão 26).
- [ ] Definir orçamento e plano Supabase (Decisão 27).
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
| O telefone aparece em algum momento? | Não; contato só pelo chat | RF007/RF008 |
| O Assistido pode ver a condição crônica do Apoiador? | Não; e o Apoiador também não vê a do Assistido | RF007 |
| A mediação pode excluir uma conta? | Não; apenas status e anonimização | RF015/RNF006 |
| Uma solicitação pode ter duas reservas? | Não | RF008 |
| Um horário pode ser oferecido a duas pessoas? | Não | RF004/RF008 |
| Cancelar com 23h59 libera o horário? | Não; vai para a mediação | RF009 |
| Uma confirmação conclui o atendimento? | Não; exige as duas | RF012 |
| A nota pode ser editada? | Não; o comentário pode ser ocultado | RF013 |
| Denúncia bloqueia a conta? | Não; exceto suspensão preventiva | RF014 |
| Quem vê o histórico? | O titular e a mediação | RF017 |
| Existe pagamento em qualquer etapa? | Não | RF008/RNF015 |
