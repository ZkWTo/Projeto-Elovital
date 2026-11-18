# 04 — Migração e operação

- **Status:** Rascunho para aprovação
- **Data:** 2026-11-16
- **Depende de:** [01 — Configuração](01-configuracao.md), [02 — Modelo de dados](02-modelo-de-dados.md), [03 — Segurança e LGPD](03-seguranca-lgpd.md)

## 1. Ponto de partida

O EloVital ainda não tem banco de produção nem dados reais. A "migração" não é a conversão de uma base existente: é a **adoção inicial** do PostgreSQL no lugar do SQLite do projeto de referência.

Isso simplifica o plano:

- não há janela de conversão de dados;
- não há necessidade de compatibilidade com schema antigo;
- não há risco de perda de produção;
- o rollback é recriar o ambiente, não desfazer dados.

O que continua sendo trabalho real: criar os projetos, escrever migrations, testar concorrência, permissões, criptografia, expurgo, backup e restauração **antes** do primeiro usuário.

## 2. Fases

### Fase 0 — Aprovação

- Ler e aprovar os documentos 01 a 05.
- Confirmar região, plano, retenção e revisão jurídica.
- Definir Encarregado e responsáveis técnicos.
- Congelar o escopo da primeira versão.

**Saída:** checklist assinado e ADR 0004 aceita.

### Fase 1 — Ambientes e fundação

- Criar `elovital-dev`, `elovital-staging` e `elovital-prod` em `sa-east-1`.
- Aplicar MFA, papéis, limites de sessão e Data API desabilitada.
- Configurar TLS com CA do Supabase.
- Criar schema `elovital`, extensões e papéis.
- Configurar `.env.example`, cofre de segredos e pipeline de CI.

**Saída:** aplicação Django conecta em `dev` e roda `migrate` do zero.

### Fase 2 — Schema e migrations

- Escrever as migrations na ordem: contas → catálogos → agenda → solicitações → reservas → chat → reputação → moderação → auditoria.
- Aplicar constraints, índices e triggers por migration.
- Criar views mascaradas e funções de reserva, cancelamento, expiração e anonimização.
- Rodar `sqlmigrate` e revisar o SQL gerado.

**Saída:** banco vazio criado do zero em CI, sem passo manual.

### Fase 3 — Segurança e LGPD no código

- Implementar criptografia de CPF, telefone e contato de emergência.
- Implementar HMAC do CPF, unicidade e fluxo de anonimização.
- Implementar auditoria e registro de acesso a dado pessoal.
- Implementar expurgo por `pg_cron`.
- Implementar views e telas com dados mascarados.
- Configurar logs sem dado pessoal e sanitização.

**Saída:** testes de criptografia, auditoria e expurgo passando.

### Fase 4 — Homologação

- Carga sintética proporcional ao primeiro ano.
- Testes de concorrência da reserva.
- Testes de RLS, grants e permissões por papel.
- Teste de restauração e de reaplicação de expurgo.
- Teste de desempenho do catálogo e do chat.
- Revisão de acessibilidade (RNF002) nas telas que exibem dados sensíveis.

**Saída:** relatório de homologação com resultados medidos, não estimados.

### Fase 5 — Piloto controlado

- Grupo pequeno de usuários reais, com termo e consentimento corretos.
- Mediação acompanhando diariamente.
- Alertas de erro, conexão, expurgo e backup ativos.
- Revisão semanal de incidentes e de acessos da mediação.

**Saída:** critérios de saída do piloto atendidos e registrados.

### Fase 6 — Produção geral

- Abertura gradual, com monitoramento de capacidade.
- Revisão dos parâmetros (limites, prazos, raios) com dados reais.
- Primeiro ciclo de revisão de acessos e de teste de restauração.

**Saída:** operação estável e próxima revisão agendada.

## 3. Ordem de implementação das migrations

| Ordem | App / grupo | Depende de |
|---|---|---|
| 1 | `contas` (usuário, endereço, termos, perfis, tentativas) | — |
| 2 | `catalogos` (condição, especialidade, parâmetro) | `contas` |
| 3 | `verificacao` (documentos, especialidades do usuário) | `contas` |
| 4 | `agenda` (recorrência, horário) | `contas`, `catalogos` |
| 5 | `solicitacoes` (solicitação, períodos, análise) | `contas`, `catalogos` |
| 6 | `reservas` (reserva, confirmação, ocorrência) | `agenda`, `solicitacoes` |
| 7 | `comunicacao` (mensagem, bloqueio, notificação) | `reservas` |
| 8 | `reputacao` (avaliação) | `reservas` |
| 9 | `moderacao` (denúncia, sanção, contestação) | `contas`, `reservas` |
| 10 | `auditoria` (log, acesso, expurgo) | todas |
| 11 | Funções, triggers, views e `pg_cron` | todas |

Regras:

- Cada migration que cria tabela com dado pessoal inclui comentário, grants e retenção.
- `RunSQL` só é usado quando o ORM não expressa a operação (índice parcial, `FORCE RLS`, trigger, `pg_cron`), com `reverse_sql` explícito quando possível.
- Migrations não destrutivas primeiro; renomeação em duas etapas.
- Índices grandes com `AddIndexConcurrently` e `atomic = False`.
- Nenhuma migration depende de dado existente.

## 4. Testes de aceite

### 4.1 Reserva concorrente (RF008, RNF006, RS008)

Objetivo: provar que duas requisições simultâneas não criam duas reservas para o mesmo horário.

Procedimento:

1. Criar um horário `disponivel` e uma solicitação `aberta`.
2. Abrir duas conexões independentes.
3. Executar `elovital.reservar_horario` nas duas, ao mesmo tempo.
4. Verificar: uma sucesso, uma falha de negócio; uma linha ativa em `reserva`; horário com status `reservado`.

Critério: nenhuma execução termina com duas reservas ativas, e a falha é uma exceção de domínio tratada, não erro 500.

### 4.2 Reserva duplicada por caminho alternativo

- Tentar inserir reserva direto por SQL como `elovital_app`: o índice parcial único precisa barrar.
- Tentar reservar horário de Apoiador suspenso: serviço e função recusam.
- Tentar reservar solicitação de outro Assistido: recusa e não revela a existência.

### 4.3 Conclusão e avaliação (RF012, RF013)

- Confirmar apenas um lado: não conclui.
- Confirmar os dois dentro do prazo: conclui e libera avaliação.
- Avaliar duas vezes: segunda recusada.
- Avaliar após 7 dias: recusada.
- Editar avaliação: sem caminho de escrita.

### 4.4 Cancelamento (RF009)

- Cancelar com 24 h ou mais: horário volta a `disponivel`.
- Cancelar com menos de 24 h: horário vai para `bloqueado` e mediação decide.
- Cancelar solicitação em análise: encerra sem reserva.
- Cancelar reserva já concluída: recusado.

### 4.5 RLS e privilégios

| Teste | Esperado |
|---|---|
| Sessão como usuário A lê linha de B | 0 linhas |
| Sessão sem `elovital.usuario_id` | 0 linhas |
| `anon` faz `SELECT` em `elovital.usuario` | erro de permissão |
| `authenticated` faz `SELECT` em `elovital.reserva` | erro de permissão |
| `elovital_app` faz `UPDATE` em `audit_log` | erro de permissão |
| `elovital_app` faz `DELETE` em `audit_log` | erro de permissão |
| `elovital_app` faz `UPDATE` em `usuario.cpf_hash` | erro de permissão |
| `elovital_readonly` lê view mascarada | sem CPF, telefone ou endereço |
| `elovital_readonly` lê tabela base | erro de permissão |

### 4.6 Criptografia

- CPF gravado não aparece em texto puro em `pg_dump` nem em consulta direta.
- Mesmo CPF gera mesmo hash; CPF diferente gera hash diferente.
- Ciphertext copiado para outra linha falha na decifra (AAD).
- Rotação de chave: dado antigo continua legível e é reescrito na versão nova.
- Busca por CPF na mediação funciona por hash, sem varrer a tabela.

### 4.7 Expurgo e retenção

- Conta encerrada há 5 anos é anonimizada na rotina.
- Documento com `expurgo_em` vencido é removido do Storage e o registro mantém apenas o resultado.
- Mensagem arquivada há mais de 2 anos tem conteúdo expurgado e metadados preservados.
- `expurgo_log` registra quantidade, regra e data.
- Rotina é idempotente: rodar duas vezes não duplica nem falha.

### 4.8 Backup e restauração

- Restaurar o backup diário em projeto separado.
- Comparar contagens e uma amostra de registros.
- Reaplicar expurgo após a restauração e conferir que dado expurgado não voltou a circular.
- Medir o tempo de restauração e comparar com o RTO de 4 horas.
- Registrar o resultado e as correções necessárias.

### 4.9 Desempenho (RNF004, RNF009)

- Catálogo com 10 registros por página em até 3 s no p95, com base sintética de pelo menos 10 mil horários e 2 mil apoiadores.
- Chat com 1.000 mensagens por reserva abre em até 3 s.
- Consulta por especialidade, data e distância usa índice, confirmado por `EXPLAIN`.
- Notificação de reserva e cancelamento é criada em até 1 minuto.

### 4.10 Segurança operacional

- `DEBUG = False`, `ALLOWED_HOSTS` restrito, cookies seguros.
- Erro de aplicação não exibe traceback nem SQL.
- Log de requisição não contém CPF, telefone, endereço, saúde ou conteúdo.
- Data API sem acesso ao schema `elovital`.
- MFA obrigatório no painel do Supabase.

## 5. Rollback

### 5.1 Rollback de código

- Deploy anterior mantido pronto para reativação.
- Migrations compatíveis com a versão anterior sempre que possível (adicionar antes de remover).
- Feature flag para funcionalidades novas que dependem de schema novo.

### 5.2 Rollback de migration

- Migration reversível: `python manage.py migrate app nome_anterior`.
- Migration irreversível: restaurar backup em projeto novo, validar e trocar a connection string.
- Nunca usar `--fake` para "resolver" divergência em produção.
- Antes de migration destrutiva: backup manual, revisão do SQL e janela combinada.

### 5.3 Rollback de ambiente

Como não há produção no início, o rollback da fase 1 é descartar o projeto e recriar. Depois do piloto, o rollback passa a exigir backup e plano de comunicação.

### 5.4 Critérios para acionar rollback

- Erro de dados ou perda de integridade.
- Reserva duplicada em produção.
- Exposição de dado pessoal.
- Indisponibilidade acima do aceitável para o piloto.
- Migration quebrada sem correção rápida.

## 6. Operação corrente

| Rotina | Frequência | Responsável | Registro |
|---|---|---|---|
| Verificar backup e alertas | Diária | Operação | Painel |
| Revisar erros e consultas lentas | Semanal | Técnico | `pg_stat_statements` |
| Revisar fila de expurgo | Semanal | Técnico | `expurgo_log` |
| Revisar acessos da mediação | Mensal | Encarregado | `acesso_dado_pessoal` |
| Revisar acessos humanos e chaves | Trimestral | Operação | Registro interno |
| Teste de restauração | Trimestral | Técnico | Relatório |
| Revisão de retenção e política | Anual ou por mudança | Jurídico + Encarregado | Política |
| Revisão de capacidade e custo | Trimestral | Operação | Painel |

Runbooks mínimos, escritos antes do piloto:

- restaurar backup e reaplicar expurgo;
- rotacionar senha de papel e chave de criptografia;
- responder a vazamento de credencial;
- liberar horário preso em `bloqueado`;
- reprocessar expiração que falhou;
- investigar reserva duplicada;
- desabilitar conta e revogar sessões.

## 7. Riscos da migração

| Risco | Probabilidade | Impacto | Mitigação |
|---|---|---|---|
| Reserva duplicada por falha na função | Baixa | Alto | Teste concorrente no CI, índice único parcial, revisão de SQL |
| Migration divergente entre ambientes | Média | Médio | CI cria do zero, `sqlmigrate` revisado, nenhum passo manual |
| Vazamento de dado em log | Média | Alto | Sanitização na origem, revisão de logging, teste de log |
| Chave de criptografia perdida | Baixa | Crítico | Cofre, backup da chave, procedimento de recuperação testado |
| Expurgo não roda | Média | Médio | Alerta de falha, `expurgo_log`, verificação semanal |
| Pool de conexões esgotado | Média | Médio | Pooler em modo sessão, `CONN_MAX_AGE` curto, monitoramento |
| Custo acima do previsto | Média | Baixo | Orçamento e alerta, revisão trimestral |
| Dependência do provedor | Média | Alto | Backup fora do Supabase, plano de continuidade, PostgreSQL padrão |
| RLS mal configurada dá falsa segurança | Média | Alto | Testes de RLS, autorização no service, revisão de política |
| Documento não expurgado no Storage | Média | Alto | Job com alerta, verificação por amostragem, hash e data |
| Retenção maior que o necessário | Média | Médio | Revisão jurídica, rotina por prazo, registro de exceção |

## 8. Critérios de saída do piloto

1. Nenhuma reserva duplicada registrada.
2. Nenhum incidente de dado pessoal aberto.
3. Expurgo rodando sem falha por 30 dias consecutivos.
4. Restauração testada com sucesso e tempo dentro do RTO.
5. p95 do catálogo e do chat dentro de 3 s.
6. Nenhum acesso da mediação sem motivo registrado.
7. Direitos do titular atendidos dentro do prazo em todos os pedidos do período.
8. Revisão jurídica concluída e política publicada.

## 9. Decisões abertas

| Decisão | Opções | Quem decide | Prazo |
|---|---|---|---|
| Região definitiva | `sa-east-1` ou outra | Aprovação + técnico | Antes da fase 1 |
| Plano e PITR | Pro com 7 dias ou superior | Aprovação + custo | Antes da fase 1 |
| Retenção de conta encerrada | 5 anos ou prazo menor | Jurídico | Antes da fase 3 |
| Retenção de mensagens arquivadas | 2 anos ou prazo menor | Jurídico | Antes da fase 3 |
| Provedor de e-mail | Definir | Técnico + jurídico | Antes da fase 4 |
| Provedor de mapas | Definir | Técnico + jurídico | Antes da fase 4 |
| Encarregado e canal | Definir | Aprovação | Antes da fase 5 |
| Uso de Supabase Auth no futuro | Manter Django ou migrar | Aprovação | Depois do piloto |
| Notificação push | Incluir ou adiar | Produto | Antes da fase 5 |
| Política de senha | 10 caracteres ou mais | Segurança | Antes da fase 3 |

## 10. Checklist desta etapa

- [ ] Fases e critérios de saída aceitos.
- [ ] Ordem de migrations revisada.
- [ ] Teste de reserva concorrente especificado e automatizado.
- [ ] Testes de RLS e grants no CI.
- [ ] Testes de criptografia e rotação de chave.
- [ ] Testes de expurgo e de reaplicação após restauração.
- [ ] Teste de restauração agendado com responsável.
- [ ] Runbooks escritos antes do piloto.
- [ ] Critérios de rollback acordados.
- [ ] Decisões abertas com responsável e prazo.
