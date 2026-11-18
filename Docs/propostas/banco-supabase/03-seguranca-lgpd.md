# 03 — Segurança e LGPD

- **Status:** Rascunho para aprovação
- **Data:** 2026-11-16
- **Relacionado:** [ADR 0002](../../adr/0002-usuario-papeis-e-autenticacao.md), [ADR 0003](../../adr/0003-conteudo-em-texto-puro.md), [ADR 0004](../../adr/0004-supabase-postgresql.md)
- **Modelo de dados:** [02 — Modelo de dados](02-modelo-de-dados.md)
- **Políticas SQL:** [`sql/002_seguranca.sql`](sql/002_seguranca.sql)
- **Requisitos de origem:** RNF011, RNF012, RS001 a RS013

> Este documento não substitui a política de privacidade nem a revisão jurídica. Ele descreve o que o banco e a aplicação precisam implementar para que a política seja verdadeira.

## 1. Princípios

1. **Finalidade:** cada dado tem uma finalidade declarada e nenhum dado é reutilizado para outra finalidade sem nova base legal.
2. **Minimização:** o sistema coleta e exibe o mínimo necessário em cada etapa; o catálogo não mostra o que só faz sentido depois da reserva.
3. **Necessidade:** dados sensíveis só entram no fluxo quando indispensáveis — condição crônica para apoiar o cuidado, documento para verificar identidade.
4. **Transparência:** o usuário sabe o que é coletado, por quê, por quanto tempo e com quem é compartilhado.
5. **Segurança desde a modelagem:** criptografia, privilégio mínimo e auditoria são requisitos de schema, não ajustes posteriores.
6. **Prestação de contas:** o controlador precisa demonstrar conformidade, e a trilha de auditoria é parte dessa prova.

## 2. Papéis e responsabilidades

| Papel LGPD | Quem exerce no EloVital | Responsabilidade |
|---|---|---|
| Controlador | Pessoa jurídica responsável pela plataforma | Decide finalidades, bases legais, retenção e atendimento ao titular |
| Operador | Supabase (banco e Storage), provedor de e-mail, provedor de mapas, provedor de push | Trata dados conforme instrução do controlador e contrato |
| Encarregado (DPO) | Pessoa indicada antes do lançamento | Canal com titulares, orientação interna e comunicação com a ANPD |
| Suboperadores | Subcontratados do Supabase (infraestrutura AWS) | Tratamento técnico, sob a cadeia de contratos do operador |
| Equipe de mediação | Usuários `MEDIADOR` | Acesso a dados pessoais apenas no mínimo necessário, com registro |
| Titular | Assistido, Apoiador, familiar autorizado | Exercício dos direitos do art. 18 |

O Encarregado e o encarregado substituto precisam estar definidos, com canal publicado, **antes** de qualquer dado real entrar no sistema. Enquanto não houver essa definição, a proposta não está pronta para produção.

## 3. Classificação dos dados

| Classe | Exemplos no EloVital | Tratamento mínimo |
|---|---|---|
| **Dado pessoal sensível — saúde** (art. 11) | Condição crônica declarada, descrição da solicitação, observações de cuidado, ocorrências com informação de saúde, contato de emergência | Acesso restrito às partes e à mediação; nunca no catálogo público; nunca em log; consentimento específico e destacado |
| **Dado pessoal sensível — identificação** | Documento oficial com foto, selfie de verificação, registro profissional | Bucket privado, URL assinada de curta duração, acesso só pela mediação, expurgo programado |
| **Dado pessoal de identificação** | Nome completo, CPF, e-mail, telefone, data de nascimento, endereço exato, foto de perfil | Cifra em repouso para CPF e telefone; hash para unicidade; exibição progressiva |
| **Dado pessoal de terceiro** | Nome e telefone do contato de emergência | Cifra; informar a origem ao titular; acesso restrito ao próprio Assistido e à mediação |
| **Credencial** | Hash de senha, identificador de sessão, token de recuperação | Hash forte ou token de uso único; nunca reversível; nunca em log |
| **Conteúdo de comunicação** | Mensagens de chat, comentários de avaliação, denúncias, justificativas | Acesso pelas partes e pela mediação; sem indexação pública; sem conteúdo em log |
| **Metadado e registro de acesso** | IP, user agent, data e hora de login, identificador de sessão | Retenção de 6 meses; acesso restrito; hash quando a finalidade não exige o valor original |
| **Dado operacional sem dado pessoal** | Parâmetros, catálogos de especialidade, versão de termos | Sem restrição especial, mas com controle de alteração |

Regras transversais:

- Texto livre é a maior fonte de vazamento acidental. Descrição, observações e chat podem conter CPF, telefone, endereço ou diagnóstico; por isso o conteúdo nunca é enviado a log, métrica ou ferramenta de erro.
- Foto de perfil é opcional e não pode ser usada para reidentificação no catálogo antes da reserva.
- O telefone do Apoiador **nunca** é exibido, em nenhuma etapa (RF008).
- A condição crônica do Assistido **nunca** aparece no catálogo (RF007).

## 4. Bases legais e finalidades

| Finalidade | Dados | Base legal | Observação |
|---|---|---|---|
| Criar e manter a conta | Nome, e-mail, senha, CPF, nascimento, endereço, foto | Execução de contrato (art. 7, V) | CPF é necessário para a regra de uma conta por pessoa |
| Verificar identidade e registro profissional | Documento, selfie, registro, especialidade | Consentimento específico (art. 11, I) e execução de contrato | Sem verificação não há publicação de agenda ou primeira solicitação |
| Declarar condição crônica e observações de cuidado | Condição, texto de cuidado | Consentimento específico e destacado (art. 11, I) | Consentimento separado do aceite geral, com possibilidade de revogação |
| Conectar Assistido e Apoiador | Especialidade, agenda, local aproximado, distância | Execução de contrato (art. 7, V) | Distância calculada a partir do endereço, sem expor coordenada exata |
| Reservar e executar o apoio | Reserva, chat, confirmações, ocorrências | Execução de contrato (art. 7, V) | O chat é o canal oficial e evita exposição de telefone |
| Reputação e segurança da rede | Avaliações, ocorrências, denúncias, sanções | Legítimo interesse (art. 7, IX) com teste de balanceamento | Não se aplica a dado sensível; para sensível, usar consentimento ou exercício de direitos |
| Prevenir fraude e abuso | Tentativas de login, IP, padrões de cancelamento | Legítimo interesse (art. 7, IX) e segurança | Registrar o teste de balanceamento e permitir oposição |
| Cumprir obrigação legal e regulatória | Registros de acesso, dados fiscais se houver | Obrigação legal (art. 7, II) | Marco Civil art. 15 para registros de acesso |
| Exercer direitos em processo | Auditoria, denúncias, sanções, histórico | Exercício regular de direitos (art. 7, VI e art. 11, II, d) | Base para retenção após o encerramento da conta |
| Enviar comunicações operacionais | E-mail, notificações | Execução de contrato | Avisos de segurança, termos e mediação não podem ser desativados |

Pontos que exigem atenção jurídica:

- **Dado de saúde não pode ser justificado apenas por legítimo interesse.** O EloVital não é serviço de saúde nem profissional de saúde; a hipótese de "tutela da saúde" do art. 11, II, f não deve ser usada como base padrão. A base principal é o consentimento específico, com registro de versão, data e finalidade.
- **Consentimento não é base para tudo.** Cadastro, reserva e execução do apoio têm base contratual; pedir consentimento para o que é obrigatório torna o consentimento inválido.
- **Revogação do consentimento de saúde** deve ser possível sem encerrar a conta, mas pode impedir a continuidade de solicitações que dependam desse dado. O fluxo precisa dizer isso de forma clara.
- **Contato de emergência** é dado de terceiro: a política deve informar como ele foi obtido e qual o fundamento.
- **Menores de 18 anos** estão fora do escopo (RF001), o que dispensa o regime do art. 14, mas exige bloqueio efetivo no cadastro.

## 5. Minimização e liberação progressiva

| Etapa | O que é visível | O que continua oculto |
|---|---|---|
| Catálogo (RF007) | Primeiro nome, foto opcional, especialidades, selo, nota média, apoios concluídos, bairro/município, distância aproximada | Sobrenome, CPF, telefone, e-mail, endereço exato, condição crônica, observações de cuidado |
| Após a reserva confirmada (RF008) | Nome completo, endereço do atendimento, observações de cuidado necessárias, chat liberado | Telefone, CPF, documento, dados de pagamento (inexistentes) |
| Mediação (RF015, RF016) | Dados completos, documentos, histórico | Somente com motivo e registro em `acesso_dado_pessoal` |
| Registro de acesso | IP, data e hora | Conteúdo de chat, diagnóstico, documento |

O catálogo é servido por uma view que não expõe colunas sensíveis (`elovital.v_catalogo_apoiador`). A view é o contrato de leitura pública; novas colunas só entram nela com revisão de privacidade.

## 6. Criptografia e gestão de segredos

### 6.1 Em trânsito

- TLS obrigatório entre navegador, aplicação e banco; `sslmode=verify-full` com CA do Supabase.
- HSTS habilitado no domínio de produção.
- Nenhuma conexão direta do navegador ao banco: a Data API fica desabilitada para o schema `elovital`.
- URLs assinadas do Storage expiram em 60 segundos e são geradas no servidor.

### 6.2 Em repouso

| Camada | Proteção |
|---|---|
| Disco do Supabase | Criptografia gerenciada pelo provedor (AES-256) |
| Campos sensíveis de busca | CPF em HMAC-SHA-256 com pepper (`usuario.cpf_hash`) |
| Campos sensíveis de leitura | CPF, telefone e contato de emergência em AES-256-GCM no application side |
| Documentos | Bucket privado, criptografia do provedor, hash SHA-256 conferido no download |
| Backups | Criptografados pelo provedor; dump lógico mensal cifrado antes de sair do ambiente |
| Segredos | Cofre do ambiente, nunca no Git, nunca em imagem de container |

### 6.3 Formato do campo cifrado

```
v1:<base64(nonce||ciphertext||tag)>
```

- `v1` permite rotação de chave sem reescrever tudo de uma vez.
- O AAD amarra o valor ao campo e à linha: `elovital:usuario:<id>:cpf`.
- Copiar o ciphertext de uma linha para outra falha na decifra.
- O nonce é aleatório de 96 bits por operação; nunca reutilizado.
- A chave de dados (DEK) é protegida por uma chave mestra (KEK) no cofre; a aplicação recebe a DEK descriptografada em memória.
- `APP_ENCRYPTION_KEY` e `CPF_HMAC_PEPPER` são diferentes entre si, diferentes por ambiente e rotacionáveis.

### 6.4 CPF

- Gravação: normaliza para 11 dígitos, valida dígitos verificadores, calcula `cpf_hash = HMAC-SHA-256(pepper, cpf)` e `cpf_cifrado = AES-GCM(cpf)`.
- Unicidade: índice único em `cpf_hash`.
- Busca da mediação: por `cpf_hash`, nunca por `LIKE` no valor cifrado.
- Exibição: descriptografa apenas para o próprio titular e para a mediação, com registro em `acesso_dado_pessoal`.
- Anonimização: `cpf_hash` recebe um valor aleatório irreversível e `cpf_cifrado` é zerado; a linha permanece para integridade referencial.
- Rotação de pepper exige recadastro do hash: guardar o CPF cifrado permite recalcular o hash sem pedir o dado ao titular.

### 6.5 Senhas e sessões

- Hash de senha com Argon2id (`PASSWORD_HASHERS` do Django), com fallback de atualização no login.
- Política mínima de 10 caracteres, sem regra de composição obrigatória, com bloqueio por listas de senhas comuns.
- Sessão expira em 30 minutos de inatividade (RF002); cookie `Secure`, `HttpOnly`, `SameSite=Lax`.
- Bloqueio temporário por tentativas falhas registrado em `tentativa_login`, sem revelar se o e-mail existe.
- Recuperação por token de uso único, hasheado no banco, válido por 1 hora e invalidado após uso.

### 6.6 O que não é criptografia

- Base64, ofuscação, hash reversível e "campo escondido no formulário" não protegem dado.
- Hash simples de CPF é quebrável por força bruta (o espaço é pequeno); por isso o HMAC com pepper.
- Criptografia no cliente não resolve: a chave ficaria no navegador.

## 7. Controle de acesso

### 7.1 Camadas

1. **Rede:** banco sem exposição pública; acesso apenas pelo pooler com TLS.
2. **Papéis do banco:** `elovital_migrator` (DDL), `elovital_app` (DML), `elovital_readonly` (leitura mascarada); `anon` e `authenticated` sem privilégio.
3. **Autorização no service:** toda consulta filtra pelo dono e pelo estado; é a barreira principal (ADR 0001).
4. **RLS:** defesa em profundidade para papéis sem acesso amplo (ver 7.3).
5. **Mascaramento:** views e templates não recebem colunas que não devem exibir.
6. **Auditoria:** acesso da mediação a dado pessoal gera registro.

### 7.2 Matriz de acesso

| Recurso | Assistido | Apoiador | Mediação | `elovital_app` |
|---|---|---|---|---|
| Própria conta | Ler/atualizar, não o CPF | Ler/atualizar, não o CPF | Ler, sancionar, anonimizar | DML |
| Conta de outra pessoa | Não | Não | Mediante motivo, com log | DML |
| Documento de verificação | Próprio, durante o envio | Próprio, durante o envio | Ler, aprovar, reprovar | DML em `storage_path` e `status` |
| Condição crônica | Própria | Não antes da reserva | Com motivo, com log | DML |
| Endereço exato | Próprio | Do atendimento reservado | Com motivo, com log | DML |
| Chat | Das reservas em que participa | Das reservas em que participa | Somente em denúncia ou ocorrência | DML |
| Avaliação | Escreve uma vez | Escreve uma vez | Oculta com justificativa | DML |
| Parâmetros | Não | Não | Ler/atualizar | DML |
| Auditoria | Não | Não | Ler | `INSERT`, sem `UPDATE`/`DELETE` |

Regras:

- A mediação não vê senha, token nem chave.
- A mediação vê CPF, endereço, documento ou condição somente pela tela que registra o motivo; consulta genérica não é permitida.
- `elovital_readonly` usa views mascaradas, com CPF, telefone, endereço e documento omitidos ou truncados.
- A aplicação não é dona das tabelas; o dono é `elovital_migrator`.
- `elovital_app` não tem `DELETE` em `audit_log`, `acesso_dado_pessoal` e `expurgo_log`, nem `UPDATE` em `cpf_hash`/`cpf_cifrado`.

### 7.3 RLS: o que ela cobre e o que não cobre

A RLS é habilitada em todas as tabelas do schema `elovital`, com `FORCE ROW LEVEL SECURITY` para que o dono também seja submetido a ela em consultas da aplicação.

Limites que precisam estar claros na aprovação:

- **Superusuário e papéis com `BYPASSRLS` ignoram a RLS.** O papel de migração e o acesso administrativo do Supabase não são contidos por política.
- **O papel da aplicação tem acesso amplo por design.** Se um service esquecer o filtro de dono, a RLS genérica não impede o vazamento; ela só reduz o dano quando existe política por usuário.
- **A política depende do contexto da sessão.** A aplicação define `elovital.usuario_id` e `elovital.papel` no início da transação; se a variável não for definida, a política deve negar (`USING (false)`), não liberar.
- **Pooler em modo sessão preserva o contexto;** o modo transação exigiria definir a variável em cada transação. Trocar de modo é uma decisão de segurança, não só de desempenho.
- **`anon` e `authenticated` não têm `USAGE` no schema.** Mesmo que a Data API seja habilitada por engano, não há caminho de leitura.
- **RLS não protege contra SQL injetado com privilégio do dono.** Consultas parametrizadas e ORM continuam obrigatórios.
- **Testes de RLS são obrigatórios no CI:** uma sessão como usuário A não lê a linha de B; uma sessão sem contexto não lê nada.

## 8. Auditoria e registros

### 8.1 Eventos obrigatórios (RNF012)

| Evento | Onde registrar |
|---|---|
| Login, falha de login, logout, recuperação de senha | `audit_log` e `tentativa_login` |
| Validação ou recusa de conta | `audit_log` |
| Aprovação, devolução ou recusa de solicitação | `audit_log` e `analise_solicitacao` |
| Reserva, cancelamento, remarcação, conclusão | `audit_log`, `reserva`, `ocorrencia` |
| Sanção, advertência, suspensão, reativação | `audit_log` e `sancao` |
| Acesso da mediação a CPF, endereço, documento ou condição | `acesso_dado_pessoal` |
| Mudança de status de conta, solicitação, horário, reserva, denúncia | `audit_log` |
| Expurgo e anonimização | `expurgo_log` |
| Alteração de parâmetro | `audit_log` |

### 8.2 Conteúdo do registro

Campos mínimos: `id`, `criado_em` (`timestamptz`), `ator_id`, `ator_papel`, `acao`, `entidade`, `entidade_id`, `antes` (jsonb, somente campos permitidos), `depois` (jsonb), `ip_hash`, `user_agent_hash`, `motivo`, `correlacao_id`.

Regras:

- `antes`/`depois` guardam apenas os campos alterados e nunca valores de CPF, telefone, documento ou conteúdo de chat; quando o campo é sensível, registra-se `"[alterado]"`.
- `audit_log`, `acesso_dado_pessoal` e `expurgo_log` são somente inserção; trigger e privilégio bloqueiam `UPDATE` e `DELETE`. A única exceção é a zeragem de `ip_hash` e `user_agent_hash` após 6 meses, feita pelo migrator e limitada a esses dois campos.
- O relógio é do banco, em UTC; a exibição converte para Brasília.
- A trilha é consultável pela mediação e exportável para auditoria externa.
- Logs de aplicação, pooler e provedor não substituem a trilha de negócio.

### 8.3 Registros de acesso (Marco Civil, art. 15)

- IP e porta lógica, data e hora de conexão, identificador de sessão: retidos por **6 meses**, em ambiente controlado.
- Guardados como hash quando a finalidade não exige o valor original (por exemplo, correlação de abuso).
- Acesso a esses registros é restrito e auditado.

## 9. Retenção e descarte

Prazos propostos; a revisão jurídica pode ajustá-los. O descarte é sempre por anonimização ou remoção do dado, com registro em `expurgo_log`.

| Dado | Tabela / local | Prazo | Ação ao vencer |
|---|---|---|---|
| Conta ativa | `usuario`, `endereco`, perfis | Enquanto durar a relação | — |
| Conta encerrada | `usuario` e relacionados | 5 anos após o encerramento | Anonimizar, mantendo o identificador |
| CPF | `usuario.cpf_hash`, `cpf_cifrado` | Junto com a conta | Zerar e substituir o hash |
| Documento e selfie | `documento_verificacao`, Storage | 90 dias após a decisão de verificação | Excluir o arquivo; manter tipo, resultado e hash |
| Registro profissional | `usuario_especialidade` | Enquanto a especialidade estiver ativa | Remover número e link; manter o resultado |
| Condição crônica | `usuario_condicao` | Enquanto o consentimento valer | Excluir após revogação ou encerramento |
| Contato de emergência | `perfil_assistido` | Enquanto a conta existir | Cifrado; excluído na anonimização |
| Solicitações e reservas | `solicitacao`, `reserva` | 5 anos após a conclusão ou expiração | Anonimizar texto livre; manter status e datas |
| Mensagens | `mensagem` | 30 dias somente leitura + 2 anos arquivado | Expurgar conteúdo; manter metadados |
| Avaliações | `avaliacao` | Enquanto o perfil existir | Anonimizar autor após encerramento |
| Ocorrências e sanções | `ocorrencia`, `sancao` | 5 anos | Anonimizar detalhes; manter decisão |
| Denúncias e contestações | `denuncia`, `contestacao` | 5 anos | Anonimizar denunciante |
| Notificações | `notificacao` | 12 meses | Excluir |
| Tentativas de login | `tentativa_login` | 90 dias | Excluir |
| Registros de acesso (IP) | `audit_log.ip_hash`, `user_agent_hash` | 6 meses | Zerar os dois campos; o restante do registro permanece |
| Auditoria de negócio | `audit_log` | 5 anos | Manter; é prova de conformidade |
| Acesso a dado pessoal | `acesso_dado_pessoal` | 5 anos | Manter |
| Expurgo | `expurgo_log` | 5 anos | Manter |
| Logs de aplicação | Provedor de log | 90 dias | Excluir |
| Backup diário | Supabase | 30 dias | Expirar |
| PITR | Supabase | 7 dias | Expirar |
| Dump lógico mensal | Fora do Supabase | 12 meses | Destruir de forma segura |

Regras:

- **Backup não ressuscita dado expurgado.** Toda restauração passa por reaplicação do expurgo antes de voltar ao ar.
- O expurgo é idempotente e roda por `pg_cron`, com registro de linhas afetadas.
- O prazo começa na data do evento que encerra a finalidade (conclusão, cancelamento, encerramento da conta), não na data de criação.
- Bloqueio de conta por determinação legal suspende o expurgo daquele registro, com justificativa registrada.

## 10. Direitos do titular

| Direito (art. 18) | Como o EloVital atende | Onde |
|---|---|---|
| Confirmação e acesso | Área "Meus dados" com exportação em JSON/CSV | RF003 |
| Correção | Edição de nome, endereço, foto e preferências; CPF imutável com fluxo de contestação | RF003 |
| Anonimização, bloqueio ou eliminação | Solicitação de eliminação; anonimização após os prazos legais | RF003, RF015 |
| Portabilidade | Exportação estruturada dos próprios dados | RF003 |
| Informação sobre compartilhamento | Seção da política de privacidade e resposta ao titular | Política |
| Revogação do consentimento | Revogação separada para saúde e documentos, com efeito imediato | RF003 |
| Oposição | Canal do Encarregado, com análise | Política |
| Revisão de decisão automatizada | O EloVital não decide reputação ou sanção de forma automatizada; a mediação é humana | RF015 |

Fluxo de atendimento:

1. Pedido pelo canal oficial ou pela conta, sem exigir dado além do necessário para identificar o titular.
2. Triagem em até 2 dias úteis e resposta em até 15 dias (art. 19), com possibilidade de prorrogação justificada.
3. Verificação de identidade proporcional: para pedidos na conta autenticada, não pedir documento de novo.
4. Execução: exportação, correção, anonimização ou justificativa fundamentada de recusa.
5. Registro do pedido, da decisão e da execução em `expurgo_log` ou `audit_log`.
6. Resposta clara, em português, com o que foi feito e o que permanece retido por obrigação legal.

O que não pode ser eliminado de imediato: registros de acesso (6 meses), auditoria, sanções e denúncias com prazo de defesa, e dados necessários a processo em curso. A resposta deve explicar a base da retenção.

## 11. Terceiros, subprocessadores e transferência internacional

| Fornecedor | Finalidade | Dados | Localização | Salvaguarda |
|---|---|---|---|---|
| Supabase | Banco e Storage | Todo o schema `elovital` e documentos | AWS `sa-east-1` (São Paulo) | DPA, cláusulas contratuais, região brasileira |
| AWS (suboperador) | Infraestrutura do Supabase | Backups e disco | `sa-east-1` | Cadeia contratual do Supabase |
| Provedor de e-mail | Recuperação de senha, notificações | E-mail, nome, conteúdo mínimo da notificação | Definir antes do lançamento | DPA e preferência por região brasileira |
| Provedor de mapas/geocodificação | Cálculo de distância e CEP | CEP, endereço aproximado | Definir | Minimizar: enviar CEP, não o endereço completo |
| Provedor de push | Notificações no navegador | Identificador de assinatura, título da notificação | Definir | DPA; notificação sem dado sensível |
| Observabilidade/erros | Diagnóstico técnico | Metadados técnicos | Definir | Sem CPF, telefone, endereço, saúde ou conteúdo |

Regras:

- Nenhum dado é vendido, alugado ou usado para publicidade.
- O Supabase é operador, não controlador; o contrato precisa refletir isso.
- A sede da empresa contratada não define a localização do dado: o que importa é onde ele é armazenado e processado. A região `sa-east-1` reduz a transferência internacional, mas acesso de suporte e metadados de conta ainda exigem salvaguarda contratual.
- Suporte do provedor não acessa dados de produção sem autorização e registro.
- Novo fornecedor com dado pessoal exige atualização da política e desta tabela.
- A política de privacidade deve listar categorias de compartilhamento, não necessariamente cada suboperador, mas o registro interno precisa estar completo.

## 12. Segurança operacional

- **Acesso humano:** MFA no Supabase e no repositório; menor número possível de owners; revisão trimestral de acessos (RS012).
- **Produção:** sem dados reais em desenvolvimento ou homologação; seed sintético; dumps anonimizados quando inevitáveis.
- **Deploy:** migrations revisadas, backup antes de mudança destrutiva, janela combinada, rollback testado.
- **Dependências:** varredura automatizada de vulnerabilidades e atualização regular.
- **Código:** revisão obrigatória, sem segredo no Git, sem `DEBUG` em produção, sem `csrf_exempt`.
- **Entrada:** validação no form e no service, consultas parametrizadas, limite de tamanho e de requisições por usuário (RS007).
- **Saída:** autoescape do Django, sem `safe` em conteúdo de usuário (ADR 0003), cabeçalhos de segurança.
- **Sessão:** expiração, rotação no login, invalidação no logout, cookie restrito.
- **Testes:** RLS, grants, criptografia, expurgo, restauração e concorrência de reserva entram no CI.
- **Capacidade de resposta:** contatos, plantão e acesso de emergência documentados antes do piloto.

## 13. Resposta a incidentes

1. **Detecção:** alerta de acesso anômalo, falha de expurgo, vazamento em log ou denúncia de usuário.
2. **Contenção:** revogar credenciais, bloquear sessões, isolar o projeto, preservar evidências.
3. **Avaliação:** quais dados, quantos titulares, risco ou dano relevante, causa raiz.
4. **Erradicação e recuperação:** corrigir, restaurar, reaplicar expurgos, validar integridade.
5. **Comunicação:** ANPD e titulares afetados em prazo razoável — a Resolução CD/ANPD nº 15/2024 trabalha com **3 dias úteis** para incidentes relevantes; a avaliação jurídica confirma o enquadramento.
6. **Registro:** data, causa, dados afetados, medidas, comunicação e lições aprendidas, mantido por 5 anos.
7. **Pós-incidente:** revisão de controles, atualização de testes e, se necessário, nova ADR.

Contatos e responsabilidades (Encarregado, jurídico, técnico e comunicação) precisam estar definidos e testados em simulação antes do lançamento. Sem isso, o plano é apenas intenção.

## 14. Riscos residuais

| Risco | Por que permanece | Mitigação |
|---|---|---|
| Mediação vê demais | O papel precisa de acesso amplo para verificar e mediar | Views por tarefa, motivo obrigatório, log de acesso, revisão periódica |
| Erro de service expõe linha de outro usuário | A autorização principal está no código | Testes de permissão por service, RLS como apoio, revisão de código |
| Chave de criptografia comprometida | A aplicação precisa da chave em memória | Cofre, rotação, menor privilégio, alerta de acesso |
| Texto livre com dado sensível | O usuário pode digitar CPF ou diagnóstico | Aviso no formulário, mascaramento em log, moderação, expurgo |
| Provedor fora do ar | Dependência de serviço gerenciado | Erro controlado, backup, plano de continuidade, RTO de 4 h |
| Acesso de suporte do provedor | Controle do fornecedor | DPA, cláusulas, auditoria e princípio da região |
| Restauração traz dado expurgado | Backup é cópia histórica | Reaplicar expurgo após restauração |
| Vazamento por log de terceiro | Ferramentas de observabilidade | Sanitização na origem, proibição de conteúdo, retenção curta |
| Revisão jurídica incompleta | Documento técnico não é parecer | Aprovação jurídica antes do lançamento |
| Uso de dado para finalidade nova | Facilidade de reutilizar tabela | Revisão de finalidade e nova base legal |

## 15. Checklist de segurança e LGPD

- [ ] Encarregado definido e canal publicado.
- [ ] Política de privacidade e termos revisados por jurídico.
- [ ] Base legal registrada por finalidade, incluindo consentimento de saúde separado.
- [ ] Consentimento com versão, data, finalidade e revogação.
- [ ] CPF em HMAC + cifra; telefone e contato de emergência cifrados.
- [ ] Chaves e pepper no cofre, com rotação documentada.
- [ ] TLS `verify-full` e Data API desabilitada para o schema.
- [ ] Papéis com privilégio mínimo e `anon`/`authenticated` sem acesso.
- [ ] RLS habilitada com `FORCE` e política que nega sem contexto.
- [ ] Views mascaradas para catálogo e leitura operacional.
- [ ] Auditoria imutável e acesso a dado pessoal registrado com motivo.
- [ ] Retenção e expurgo agendados, com `expurgo_log`.
- [ ] Reaplicação de expurgo após restauração testada.
- [ ] Direitos do titular com fluxo, prazo e registro.
- [ ] Fornecedores com DPA e localização documentada.
- [ ] Logs sem dado pessoal, com sanitização na origem.
- [ ] Plano de incidentes com contatos e simulação.
- [ ] Testes de RLS, grants, criptografia e expurgo no CI.
- [ ] Revisão jurídica da retenção e da transferência internacional.
