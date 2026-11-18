# PRD 0001: EloVital — rede de apoio a pessoas com doenças crônicas

- **Status:** Proposto
- **Data:** 2026-11-16
- **Documento de origem:** [Documento de Requisitos Funcionais do EloVital](../../../Entrega/alterado/Documento_Requisitos_Funcionais_EloVital_ALTERADO.docx)
- **ADRs:** [0001](../adr/0001-mvt-com-camada-de-service.md), [0002](../adr/0002-usuario-papeis-e-autenticacao.md), [0003](../adr/0003-conteudo-em-texto-puro.md), [0004](../adr/0004-supabase-postgresql.md)
- **Proposta de banco:** [Supabase/PostgreSQL](../propostas/banco-supabase/README.md)

## Objetivo

Oferecer uma plataforma web gratuita que conecta pessoas com doenças crônicas — os **Assistidos** — a pessoas dispostas a ajudar — os **Apoiadores**, inclusive profissionais voluntários —, reduzindo a falta de comunicação e a dificuldade de encontrar quem possa atender necessidades cotidianas de apoio.

A plataforma é uma vitrine de disponibilidade com mediação humana, não um serviço de emergência, não um mecanismo de despacho automático e não um canal de pagamentos. Toda solicitação é analisada por um mediador antes de ficar visível, e todo contato entre as partes acontece pelo chat interno.

O escopo da primeira versão inclui cadastro e verificação de identidade, agenda solidária, criação e validação de solicitações, catálogo de apoiadores, reserva de horário, chat, conclusão do apoio, avaliação, denúncia e mediação. Não há cobrança, pagamento, doação financeira ou intermediação de valores entre usuários.

## Perfis de uso

| Perfil | Quem é | O que precisa fazer |
|---|---|---|
| Visitante | Qualquer pessoa sem conta | Conhecer a proposta, ler termos e política de privacidade e iniciar o cadastro |
| Assistido | Pessoa maior de 18 anos com doença crônica, ou familiar/responsável autorizado na mesma conta | Criar solicitações, acompanhar a análise, reservar horários, conversar, confirmar a realização e avaliar |
| Apoiador | Pessoa maior de 18 anos, voluntária ou profissional, que oferece apoio | Publicar agenda, receber reservas, conversar, confirmar a realização e avaliar |
| Mediador | Equipe de mediação da plataforma | Validar contas, solicitações e agendas; analisar denúncias; aplicar sanções; manter cadastros-base e parâmetros |

O familiar/responsável autorizado não é um novo tipo de conta: ele acompanha a conta do Assistido, conforme o RF001 e o RF003. A operação técnica da infraestrutura não é um perfil de produto e não acessa dados sensíveis sem justificativa e registro.

## Requisitos funcionais

Os identificadores abaixo são os do Documento de Requisitos. Cada requisito descreve um resultado observável; campos, limites e mensagens exatas ficam nas specs de implementação.

### Cadastro, acesso e perfil

- **RF001 — Cadastro de usuário:** uma pessoa cria uma única conta como Assistido ou Apoiador, informando dados pessoais, foto, endereço e consentimentos; o CPF é único e não pode ser alterado; menores de 18 anos são bloqueados; a conta fica “Em análise” até a aprovação da mediação.
- **RF002 — Autenticação e recuperação de acesso:** o usuário entra e sai com e-mail e senha; a sessão expira após 30 minutos de inatividade; a recuperação usa link de uso único válido por 1 hora; o sistema não revela se um e-mail existe; tentativas repetidas de login sofrem bloqueio temporário.
- **RF003 — Perfil, preferências e privacidade:** o usuário consulta e atualiza os próprios dados, preferências, especialidades e privacidade; o CPF permanece imutável; alterações de especialidade ou registro profissional exigem nova validação; a conta pode ser desativada e a eliminação de dados pode ser solicitada; o histórico de alterações é preservado.

### Agenda e solicitações

- **RF004 — Agenda solidária do apoiador:** o Apoiador publica horários futuros com especialidade, modalidade, local e raio de deslocamento; cada horário dura no mínimo 30 minutos e no máximo 4 horas; a recorrência é opcional e não cria datas passadas; um horário reservado sai do catálogo e não pode ser oferecido a outra pessoa.
- **RF005 — Criação de solicitação de apoio:** o Assistido registra tipo de apoio, título, descrição, períodos possíveis, local, modalidade e prioridade sugerida; a solicitação nasce “Em análise” e só é publicada depois da aprovação humana; prioridade Alta exibe o aviso de que a plataforma não substitui o SAMU (192).
- **RF006 — Análise e validação da solicitação:** o mediador analisa toda solicitação em até 24 horas úteis, define a prioridade final e aprova, devolve para ajuste ou recusa com justificativa; nenhuma solicitação é publicada automaticamente; toda decisão é registrada em auditoria.

### Descoberta, reserva e cancelamento

- **RF007 — Catálogo de apoiadores e filtros:** o Assistido vê apenas apoiadores ativos, verificados e com horário disponível, com primeiro nome, foto opcional, especialidades, selo, nota média, quantidade de apoios concluídos, bairro/município e distância aproximada; sobrenome, CPF, telefone, e-mail, endereço exato, condição crônica e observações de cuidado não aparecem antes da reserva; a lista é paginada em 10 registros.
- **RF008 — Reserva de horário:** a reserva é imediata e atômica; um mesmo horário não pode ter duas reservas; cada solicitação tem no máximo uma reserva ativa; os dados completos são liberados somente após a confirmação; o telefone nunca é exibido e o contato acontece pelo chat; não há pagamento.
- **RF009 — Cancelamento e remarcação:** Assistido ou Apoiador cancela ou remarca com motivo; com 24 horas ou mais de antecedência, o horário volta automaticamente a “Disponível”; com menos de 24 horas, a mediação analisa; o histórico e o autor da alteração são preservados; cancelamentos repetidos são sinalizados.

### Comunicação, conclusão e reputação

- **RF010 — Notificações:** o sistema avisa sobre reservas, cancelamentos, mensagens, solicitações, prazos, denúncias e ocorrências; a central interna é essencial, o e-mail é importante e a notificação push é desejável; o usuário escolhe as categorias, exceto avisos obrigatórios de segurança, termos e mediação; cada notificação tem estado de lida ou não lida.
- **RF011 — Canal de conversa (chat):** o chat existe apenas entre as duas partes de uma reserva; mensagens têm até 1.000 caracteres, com data, hora e indicador de leitura; não há anexos, áudios ou chamadas na primeira versão; após o fim do atendimento, o chat fica somente leitura por 30 dias e depois é arquivado para auditoria; é possível denunciar mensagens e bloquear o outro usuário.
- **RF012 — Conclusão do apoio:** a conclusão exige confirmação das duas partes em até 72 horas (parâmetro configurável); somente atendimentos confirmados entram no indicador de sucesso; a conclusão não gera pagamento.
- **RF013 — Avaliação entre assistido e apoiador:** cada parte avalia uma única vez por atendimento, com nota de 1 a 5 e comentário opcional de até 300 caracteres, em até 7 dias após a conclusão; a avaliação não pode ser editada; o perfil exibe média, total de avaliações e apoios concluídos; notas 1 ou 2 com comentário são sinalizadas à mediação.

### Segurança, moderação e histórico

- **RF014 — Denúncia de usuário ou conteúdo:** o usuário denuncia perfil, solicitação, reserva ou mensagem com motivo e descrição; a denúncia não bloqueia a conta automaticamente, exceto a suspensão preventiva quando 3 ou mais pessoas diferentes denunciam o mesmo usuário em 30 dias; o denunciante recebe protocolo e é informado do resultado, sem que sua identidade seja revelada.
- **RF015 — Gestão administrativa e mediação:** a mediação verifica contas, perfis, solicitações, agendas e conteúdos antes da publicação; gerencia contas, solicitações, agendas, denúncias, avaliações, mensagens, ocorrências, cadastros-base e parâmetros; acompanha indicadores; toda sanção exige justificativa e registro em auditoria; não há exclusão física de contas, solicitações ou mensagens, apenas status e anonimização quando a lei exigir.
- **RF016 — Verificação de identidade e de registro profissional:** o Apoiador envia documento oficial com foto, selfie e, quando a especialidade for regulamentada, o número do registro profissional; a mediação confere e aprova ou reprova; os documentos têm acesso restrito e são excluídos após o prazo necessário; a verificação é obrigatória para Apoiadores e para a primeira solicitação do Assistido.
- **RF017 — Histórico, ocorrências e expiração:** o usuário acompanha solicitações e reservas; desistência, não comparecimento, falta de resposta e falhas geram ocorrência e são considerados na reputação; uma rotina automática verifica expirações, lembretes e confirmações vencidas; toda mudança de status é registrada; o histórico é visível apenas ao próprio usuário e à mediação, paginado em 10 registros.

## Regras de negócio transversais

Estas regras valem para mais de um requisito e devem ser testadas em conjunto:

1. **Mediação humana antes da publicação:** contas, solicitações, agendas e conteúdos passam por verificação prévia da mediação.
2. **Uma conta por CPF:** o CPF é único, imutável e não pode ser reutilizado em outra conta.
3. **Maioridade:** somente pessoas com 18 anos ou mais podem se cadastrar.
4. **Minimização e liberação progressiva:** o catálogo mostra apenas dados aproximados; dados completos e observações de cuidado são liberados após a reserva, nunca o telefone.
5. **Sem pagamentos:** a plataforma não cobra, não intermedeia e não permite dinheiro, doações, gorjetas ou transferências entre usuários.
6. **Sem exclusão física:** contas, solicitações e mensagens usam status e anonimização; o histórico é preservado para auditoria.
7. **Emergência:** a plataforma não substitui o SAMU (192) e não promete tempo de resposta.
8. **Contestação:** sanções e recusas podem ser contestadas, preferencialmente por outro mediador.
9. **Transparência:** o denunciante é informado do resultado sem exposição dos dados do denunciado.
10. **Gratuidade:** o uso é gratuito para Assistidos e Apoiadores.

## Requisitos não funcionais

- **RNF001 (usabilidade):** interface simples, textos curtos, ajuda contextual e orientações claras, considerando usuários com pouca familiaridade com tecnologia e possíveis limitações físicas.
- **RNF002 (acessibilidade):** conformidade com WCAG 2.1 nível AA, incluindo contraste, fontes ajustáveis, navegação por teclado, foco visível, leitores de tela e alvos de toque de pelo menos 44 × 44 px.
- **RNF003 (compatibilidade):** site responsivo, mobile-first, nas duas últimas versões de Chrome, Firefox, Edge e Safari; aplicativos nativos ficam para versão futura.
- **RNF004 (desempenho):** consultas e telas respondem em até 3 segundos para 95% das requisições; notificações de reserva e cancelamento são geradas em até 1 minuto após o evento.
- **RNF005 (disponibilidade):** disponibilidade de 24 horas por dia, com meta de 99% mensal.
- **RNF006 (integridade):** reservar, cancelar, remarcar e concluir são transacionais; ou concluem por inteiro, ou nada é gravado; é impossível reservar o mesmo horário duas vezes.
- **RNF007 (mensagens de erro):** mensagens em português do Brasil, claras, indicando o que ocorreu e como corrigir, com destaque dos campos com problema.
- **RNF008 (backup):** backup automático diário, com testes periódicos de restauração e retenção definida pela equipe.
- **RNF009 (escalabilidade):** arquitetura que permita crescer sem reescrita, com paginação, índices e consultas otimizadas para especialidade, data e distância.
- **RNF010 (parametrização):** limites, prazos, raios e regras de antecedência são configuráveis pela mediação, sem alteração de código.
- **RNF011 (conformidade legal):** tratamento de dados conforme a LGPD (Lei 13.709/2018), com finalidade, consentimento específico, minimização, direitos do titular e política de privacidade; dados de saúde são dados pessoais sensíveis (art. 11).
- **RNF012 (auditabilidade):** login, validação de conta, aprovação de solicitação, reserva, cancelamento, sanção, acesso da mediação a dados pessoais e mudança de status são registrados com usuário, ação, data e hora; registros de acesso seguem o Marco Civil da Internet (Lei 12.965/2014).
- **RNF013 (integrações externas):** integração com consulta de CEP e IBGE, geolocalização/mapas para cálculo de distância, envio de e-mail e, opcionalmente, notificações push; não há integração de pagamento.
- **RNF014 (localização):** interface, mensagens e e-mails em português do Brasil; datas em dd/mm/aaaa; horários no fuso de Brasília.
- **RNF015 (gratuidade):** a plataforma não cobra, não intermedeia e não permite pagamentos, doações, gorjetas ou transferências entre usuários.

## Fora do escopo desta versão

- Aplicativos nativos Android e iOS.
- Cobrança, pagamento, doação financeira, gorjeta ou intermediação de valores.
- Contas de ONG, clínica, agência de cuidadores ou instituição.
- Despacho automático de apoiadores e metas de atendimento por Apoiador.
- Anexos, imagens, áudios, chamadas de vídeo ou telefone dentro do chat.
- Editor visual, Markdown ou HTML em textos enviados por usuários.
- Autenticação em dois fatores (desejável para versão posterior).
- Notificação push como requisito essencial (desejável na primeira versão).
- API pública, integração com prontuários, sistemas de saúde ou conselhos profissionais.
- Moderação automática por IA e priorização automática de solicitações.
- Internacionalização, outros idiomas e outros fusos.
- Cadastro de menores de 18 anos.
- Promessa de tempo de resposta ou substituição de serviços de emergência.

## Critérios de conclusão

1. Um Assistido completa o ciclo pelo navegador: cadastro, aprovação, solicitação, aprovação da solicitação, catálogo, reserva, chat, confirmação dupla e avaliação.
2. Um Apoiador completa o ciclo: cadastro, verificação, publicação de agenda, recebimento de reserva, chat, confirmação e avaliação.
3. Um mediador valida conta e solicitação, trata uma denúncia, aplica uma sanção com justificativa e consulta o registro de auditoria.
4. Os controles de segurança e LGPD estão implementados e testados: papéis e privilégios mínimos, criptografia de campos sensíveis, retenção e descarte, direitos do titular e ausência de dados pessoais em logs.
5. Os limites de desempenho e disponibilidade do RNF004 e do RNF005 são medidos e registrados.
6. Todos os requisitos funcionais têm pelo menos um teste automatizado, e a suíte passa por completo.
7. A proposta do banco Supabase foi aprovada antes de qualquer uso em produção.
