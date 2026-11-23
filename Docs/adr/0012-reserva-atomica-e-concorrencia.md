# ADR 0012: Reserva atômica, concorrência e idempotência

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — RF008, RF009, RF012, RNF006, RS008
- **Relacionado:** [ADR 0007](0007-persistencia-postgresql-jpa-flyway.md), [ADR 0011](0011-maquinas-de-estado-do-fluxo.md), [ADR 0021](0021-testes-qualidade-e-ci.md)

## Contexto

O RF008 exige que a reserva seja **imediata e atômica**: em uma única transação, o sistema verifica disponibilidade, marca o horário como `RESERVADO`, muda a solicitação para `RESERVADA`, remove do catálogo, notifica o Apoiador e libera dados/chat. O RNF006 é explícito: é **impossível** reservar o mesmo horário duas vezes e **nenhuma escrita parcial** pode acontecer. O RS008 lista como risco duas reservas para o mesmo horário ou horário cancelado ainda exibido como disponível.

O cenário problemático é clássico: dois Assistidos veem o mesmo horário e clicam em "Quero este horário" quase ao mesmo tempo. Sem controle, ambos leem `DISPONIVEL` e ambos gravam.

Além da reserva, cancelamento, remarcação e conclusão também são transacionais e podem ser disparados duas vezes (duplo clique, retry de rede, rotina automática).

## Decisão

### Uma única fonte de verdade: o caso de uso Java

A reserva é implementada **em Java**, em um caso de uso transacional. As funções SQL `reservar_horario`, `cancelar_reserva` e `confirmar_conclusao` da proposta de banco **não** são a fonte de verdade (Decisão 32 do PRD). Elas podem existir como documentação ou como barreira de segurança, mas a regra vive em um lugar só.

Motivo: manter a regra no código Java evita divergência entre duas implementações e permite testar sem banco, além de concentrar auditoria e eventos no mesmo ponto.

### Três barreiras em camadas

1. **Bloqueio pessimista** (`SELECT ... FOR UPDATE`) no horário e na solicitação.
2. **Constraint única parcial** no banco, como última barreira.
3. **Verificação de estado** no agregado, antes de qualquer escrita.

```java
@Repository
public interface HorarioRepository extends JpaRepository<Horario, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Horario h where h.id = :id")
    Optional<Horario> buscarParaReserva(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Solicitacao s where s.id = :id")
    Optional<Solicitacao> buscarParaReserva(@Param("id") UUID id);
}
```

```java
@Service
public class ReservarHorarioService {

    @Transactional
    public ReservaCriada executar(ReservarHorarioCommand cmd) {
        Horario horario = horarioRepository.buscarParaReserva(cmd.horarioId())
                .orElseThrow(() -> new ValidacaoException("horario", "HORARIO_INEXISTENTE"));

        Solicitacao solicitacao = solicitacaoRepository.buscarParaReserva(cmd.solicitacaoId())
                .orElseThrow(() -> new ValidacaoException("solicitacao", "SOLICITACAO_INEXISTENTE"));

        autorizacao.exigirDono(solicitacao, cmd.atorId());
        solicitacao.exigirReservavel();      // ABERTA
        horario.exigirDisponivel();          // DISPONIVEL, futuro, apoiador ativo/verificado

        horario.reservar();
        solicitacao.reservar();
        Reserva reserva = Reserva.criar(solicitacao, horario, relogio.agora());

        reservaRepository.save(reserva);     // flush ocorre no commit

        events.publishEvent(new ReservaCriadaEvent(reserva.getId()));
        return ReservaCriada.de(reserva);
    }
}
```

Ordem de aquisição de locks: **sempre horário → solicitação**. Ordem consistente evita deadlock entre duas reservas concorrentes.

### Constraint de banco

```sql
-- uma reserva não cancelada por horário
create unique index ux_reserva_horario_ativa
    on elovital.reserva (horario_id)
    where status not in ('cancelada', 'remarcada');

-- uma reserva ativa por solicitação
create unique index ux_reserva_solicitacao_ativa
    on elovital.reserva (solicitacao_id)
    where status not in ('cancelada', 'remarcada');
```

Se a constraint disparar, o service traduz `DataIntegrityViolationException` em `ConflitoException("HORARIO_INDISPONIVEL")`, que a borda devolve como 409 com a mensagem "Este horário acabou de ser reservado por outra pessoa" e a lista atualizada.

Nunca se engole a exceção de integridade: ela indica uma corrida que precisa ser reportada e medida (métrica de conflito).

### Isolamento

- `READ COMMITTED` (padrão do PostgreSQL) **com bloqueio explícito** é suficiente e mais previsível que subir o isolamento global.
- **Não** se usa `SERIALIZABLE` como padrão: aumenta retrabalho e erros de serialização sem necessidade.
- O bloqueio é curto: nenhuma chamada externa, nenhum cálculo pesado e nenhum envio de e-mail dentro da transação. A notificação é publicada como evento **após o commit** ([ADR 0015](0015-notificacoes-assincronas.md)).
- `lock_timeout` no papel `elovital_app` (3 s) evita requisição presa; se estourar, retorna 409 com orientação.

### Idempotência

O duplo clique e o retry de rede são reais em mobile. Por isso:

- Endpoints de escrita crítica aceitam `Idempotency-Key` (UUID gerado pelo formulário).
- Tabela `idempotencia` guarda `chave`, `usuario_id`, `operacao`, `hash_payload`, `resposta_id`, `criado_em`, com unicidade por `(chave, usuario_id)`.
- Se a mesma chave chega de novo com o mesmo payload, a resposta original é devolvida sem executar de novo.
- Se chega com payload diferente, responde 422 ("requisição reutilizada com dados diferentes").
- Retenção: 24 horas; limpeza na rotina ([ADR 0016](0016-rotinas-agendadas-e-expiracao.md)).

Aplicável a: reservar, cancelar, remarcar, confirmar conclusão, avaliar, denunciar.

Complementos:

- `POST` sempre seguido de redirect (PRG) no fluxo HTML.
- Botões desabilitados após o primeiro clique (progressive enhancement).
- Rotinas automáticas são idempotentes por estado: rodar duas vezes não conclui duas vezes ([ADR 0016](0016-rotinas-agendadas-e-expiracao.md)).

### Cancelamento e antecedência

O cancelamento recalcula a regra das 24 h com relógio injetável:

```java
@Transactional
public Cancelamento executar(CancelarReservaCommand cmd) {
    Reserva reserva = reservaRepository.buscarParaAtualizacao(cmd.reservaId());
    autorizacao.exigirParte(reserva, cmd.atorId());

    Duration antecedencia = Duration.between(relogio.agora(), reserva.inicio());
    boolean tardio = antecedencia.compareTo(parametros.antecedenciaCancelamento()) < 0;

    if (tardio) {
        reserva.enviarParaMediacao(cmd.motivo(), cmd.justificativa());
        horario.bloquear("CANCELAMENTO_TARDIO");
    } else {
        reserva.cancelar(cmd.motivo());
        horario.liberar();
        solicitacao.reabrir();
    }
    // auditoria + evento após commit
}
```

Regras verificadas:

- **23h59 não libera** o horário automaticamente; vai para a mediação (critério de aceite do RF009).
- O limite é lido de `reserva_antecedencia_cancelamento_h`, nunca de constante.
- O cálculo usa o **início do horário**, não a data.
- Cancelamento tardio bloqueia o horário; só a mediação o libera.

### Remarcação

- A remarcação cria uma reserva nova em estado `CIENCIA_PENDENTE`, vinculada à anterior (`reserva_origem_id`), e marca a anterior como `REMARCADA`.
- A operação inteira é uma transação: reserva nova + liberação/bloqueio do horário antigo + status da solicitação.
- Se não houver horário compatível, a solicitação volta a `ABERTA` e a reserva antiga é cancelada conforme a antecedência.
- A constraint única parcial não considera `remarcada` como ativa, permitindo a nova reserva no mesmo horário apenas se ele voltou a `DISPONIVEL`.

### Conclusão (dupla confirmação)

- `reserva_confirmacao` tem unicidade por `(reserva_id, parte)`. A segunda confirmação não pode ser duplicada pela mesma parte.
- A transição para `CONCLUIDA` ocorre na segunda confirmação; a de 72 h é feita pela rotina, também com verificação de estado.
- Tentar confirmar depois do prazo retorna `PrazoExpiradoException` com orientação para a mediação.

### Consulta de disponibilidade

- O catálogo **não** decide disponibilidade; ele mostra o que está `DISPONIVEL` naquele instante.
- Entre a leitura e o clique, o horário pode ser reservado: a resposta 409 é esperada e tratada na interface.
- A listagem do catálogo é atualizada por SSE/polling e após qualquer conflito.

### Observabilidade da concorrência

- Métrica `reserva_conflito_total` (quantas vezes a constraint ou o `lock_timeout` disparou).
- Métrica `reserva_duracao_segundos` (p95 da transação).
- Alerta se conflito passar de um limiar, porque indica catálogo desatualizado ou excesso de disputa.

## Fluxo de usuário afetado

1. **Catálogo (RF007):** o Assistido vê horários disponíveis; a reserva pode falhar por concorrência.
2. **Reserva (RF008):** transação única, mensagem clara em caso de conflito, dados liberados apenas após o commit.
3. **Ciência (RF008):** 24 h para o Apoiador; sem ciência, reserva mantida e mediação notificada.
4. **Cancelamento (RF009):** ≥ 24 h libera; < 24 h vai para mediação.
5. **Remarcação (RF009):** nova reserva vinculada, sem duplicidade.
6. **Conclusão (RF012):** duas confirmações ou 72 h; idempotente.

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| `SERIALIZABLE` global | Retrabalho e erros de serialização em todo o sistema. |
| Só constraint única, sem lock | Funciona, mas gera mais exceções de integridade e mensagens menos claras. |
| Função SQL como fonte de verdade | Duas implementações (Java e SQL) e regra invisível ao código. |
| Fila/Redis lock distribuído | Infraestrutura extra; o banco já resolve com lock de linha. |
| Reserva otimista com `@Version` apenas | Duas requisições concorrentes podem falhar de forma confusa; o lock pessimista no horário é mais direto. |

## Consequências

- A transação mantém locks por alguns milissegundos; consultas lentas dentro dela afetam outros usuários.
- O `lock_timeout` precisa ser calibrado; curto demais gera 409 em pico, longo demais enfileira.
- A tabela de idempotência adiciona escrita e limpeza.
- A ordem de locks (horário → solicitação) precisa ser documentada e seguida em todo código novo.
- Testes de concorrência exigem banco real (Testcontainers), não H2.

## Critérios de aceite

- [ ] Duas requisições simultâneas no mesmo horário produzem exatamente uma reserva.
- [ ] A segunda recebe 409 com mensagem "Este horário acabou de ser reservado por outra pessoa" e nenhuma escrita parcial.
- [ ] O horário sai do catálogo imediatamente após o commit.
- [ ] Reserva não é criada se a solicitação não pertence ao Assistido ou não está `ABERTA`.
- [ ] Reserva não é criada se o horário está `RESERVADO`, `BLOQUEADO`, no passado ou de Apoiador não ativo/verificado.
- [ ] Reenvio com a mesma `Idempotency-Key` devolve a mesma reserva e não duplica.
- [ ] Cancelamento com 23h59 não libera o horário automaticamente.
- [ ] Cancelamento com ≥ 24 h libera o horário e reabre a solicitação.
- [ ] Duas confirmações da mesma parte não concluem duas vezes.
- [ ] Nenhuma chamada externa ocorre dentro da transação.
- [ ] Teste de concorrência com duas conexões roda no CI.

## Decisões pendentes

> **DECISÃO PENDENTE 65:** manter ou remover as funções SQL de reserva/cancelamento/conclusão da proposta de banco. Se mantidas, precisam ser marcadas como "não usadas pela aplicação" para não virarem fonte paralela.
>
> **DECISÃO PENDENTE 66:** remarcação cria nova reserva vinculada (recomendado) ou reutiliza a mesma linha?
>
> **DECISÃO PENDENTE 67:** limite de tentativas de reserva por usuário/minuto, para evitar abuso do catálogo (RS007).
>
> **DECISÃO PENDENTE 68:** política de retenção da tabela de idempotência (24 h é sugestão).
