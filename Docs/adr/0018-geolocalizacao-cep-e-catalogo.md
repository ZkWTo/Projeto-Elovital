# ADR 0018: CEP, geolocalização e catálogo por distância

- **Status:** Proposto
- **Data:** 2026-11-23
- **PRD:** [PRD 0001](../prd/0001-detalhamento-prd-e-adrs.md) — RF001, RF004, RF007, RNF009, RNF013
- **Relacionado:** [ADR 0007](0007-persistencia-postgresql-jpa-flyway.md), [ADR 0013](0013-lgpd-e-dados-sensiveis.md), [ADR 0019](0019-parametros-e-cadastros-base.md)

## Contexto

O fluxo exige:

- **cadastro (RF001):** CEP com busca automática; município/estado vindos do IBGE quando o CEP não é encontrado;
- **agenda (RF004):** local de referência obrigatório para presencial e raio de deslocamento;
- **catálogo (RF007):** filtro por distância a partir do endereço do Assistido (padrão 10 km), exibindo distância aproximada ("cerca de 3 km");
- **RNF009:** consultas otimizadas para especialidade, data e distância;
- **RNF013:** falha de integração externa não pode bloquear o fluxo principal.

Os riscos são: expor endereço exato, depender de um provedor externo lento ou fora do ar, e calcular distância em Java sobre a tabela inteira.

## Decisão

### CEP: provedor externo com cache local

- Porta `ConsultaCep` em `application/port`, adapter em `infrastructure`.
- Provedor principal: um serviço público de CEP (ViaCEP ou BrasilAPI), escolhido por disponibilidade e termos; a decisão fica registrada em [ADR 0019](0019-parametros-e-cadastros-base.md) como configuração, não no código.
- Timeout curto (2 s), *circuit breaker* (Resilience4j) e fallback para digitação manual.
- Cache em tabela `cep_cache (cep, logradouro, bairro, municipio, uf, atualizado_em)` com TTL de 30 dias; não há cache de coordenada por pessoa.
- O CEP é normalizado para 8 dígitos antes de consultar.

```java
public interface ConsultaCep {
    ResultadoCep consultar(String cep); // nunca lança para "não encontrado"
}
```

Regras:

- "CEP não encontrado" libera os campos para digitação e transforma município/estado em seleção com dados do IBGE (RF001).
- Falha do provedor **não** impede o cadastro; o usuário preenche manualmente.
- Nenhuma chamada externa dentro de transação de banco.
- O CEP nunca é a única fonte do endereço; o usuário confirma.

### IBGE: tabela local, não dependência em tempo real

- `municipio (codigo_ibge, nome, uf)` é carregada por rotina periódica (mensal) a partir da API do IBGE.
- A aplicação **lê do banco**, não chama o IBGE em cada requisição.
- Se a sincronização falhar, a tabela anterior continua válida.

Motivo: o IBGE muda pouco e não pode ser gargalo de cadastro.

### Geocodificação

- O endereço é convertido em coordenada no momento do cadastro/edição, de forma **assíncrona** ou no máximo fora da transação principal.
- Se não houver coordenada, o usuário é cadastrado e o endereço fica marcado como "sem geocodificação"; o catálogo o exclui do filtro por distância ou usa o município como aproximação.
- A coordenada é guardada em `endereco.localizacao geography(Point, 4326)`.
- A coordenada nunca é exibida; só a distância arredondada.
- Provedor de geocodificação é decisão pendente; pode ser o mesmo do mapa.

### Distância: PostGIS, não Java

```sql
select a.id,
       round(st_distance(a.localizacao, h.localizacao) / 1000.0) as distancia_km
  from elovital.horario h
  join elovital.endereco a on ...
 where h.status = 'DISPONIVEL'
   and h.especialidade_id in (:especialidades)
   and h.data between :inicio and :fim
   and st_dwithin(a.localizacao, h.localizacao, :raioMetros)
 order by h.data, h.hora_inicio, distancia_km;
```

- `geography` calcula em metros sobre a esfera; `ST_DWithin` usa índice GiST.
- A distância é **arredondada** e exibida como texto aproximado.
- O raio vem de `raio_padrao_km`/`raio_max_km` ([ADR 0019](0019-parametros-e-cadastros-base.md)).
- Nenhum cálculo de distância em laço Java.

### Catálogo: projeção pública

O catálogo lê uma projeção/view (`v_catalogo_apoiador`) que contém apenas:

```text
usuario_id, primeiro_nome, foto_path, selo_verificado,
nota_media, total_avaliacoes, total_apoios_concluidos,
especialidades, modalidade, bairro, municipio, distancia_aproximada
```

- Não contém sobrenome, CPF, telefone, e-mail, endereço exato, condição crônica nem observações de cuidado.
- A view é o **contrato de leitura pública**; adicionar coluna exige revisão de privacidade.
- Filtros: especialidade, data/período, modalidade, distância, avaliação mínima, selo.
- Ordenação padrão: horário mais próximo, menor distância, melhor avaliação; alterável.
- Paginação de 10 (parâmetro `catalogo_pagina`), no servidor.

### Regras de visibilidade

| Situação | Catálogo |
|---|---|
| Apoiador `ATIVA`, verificado, com horário `DISPONIVEL` futuro | aparece |
| Apoiador suspenso/bloqueado/em análise | não aparece |
| Horário `RESERVADO`, `BLOQUEADO`, `REALIZADO` ou passado | não aparece |
| Assistido sem assinatura de solicitação aprovada | não acessa o catálogo |
| Apoiador consultando catálogo | não (matriz de permissões) |

- Uma suspensão remove o perfil e os horários imediatamente (critério de aceite).
- O horário reservado desaparece no commit da reserva.

### Falha e resiliência

| Falha | Comportamento |
|---|---|
| CEP fora do ar | cadastro manual; aviso discreto |
| Geocodificação fora do ar | endereço sem coordenada; catálogo usa município |
| IBGE fora do ar | tabela local continua |
| PostGIS indisponível | filtro por distância desabilitado com aviso; demais filtros funcionam |
| Provedor de mapa lento | timeout e fallback |

- Toda integração externa tem timeout, retry limitado e *circuit breaker*.
- Nenhuma integração é chamada dentro de transação.
- Falha externa é logada sem dado pessoal.

### Privacidade

- O endereço exato só é usado para calcular distância e para a reserva.
- A distância é arredondada para reduzir reidentificação.
- O bairro/município é exibido; o logradouro não.
- A coordenada não aparece em nenhuma resposta HTTP.

## Fluxo de usuário afetado

1. **Cadastro (RF001):** CEP → endereço; se falhar, digitação + IBGE.
2. **Perfil (RF003):** alteração de endereço dispara nova geocodificação.
3. **Agenda (RF004):** local de referência e raio; horário presencial exige local.
4. **Solicitação (RF005):** local do perfil ou outro CEP.
5. **Catálogo (RF007):** filtros, ordenação, distância aproximada e paginação.
6. **Reserva (RF008):** endereço exato liberado só após a reserva e só se presencial.
7. **Mediação (RF015):** pode ver endereço com motivo (auditado).

## Alternativas consideradas

| Alternativa | Motivo do descarte |
|---|---|
| Calcular distância em Java | Carrega a tabela inteira e ignora índice. |
| Guardar latitude/longitude em `double` sem PostGIS | Sem índice espacial e com cálculo manual. |
| Chamar IBGE em cada requisição | Latência e dependência desnecessária. |
| Bloquear cadastro quando CEP falha | Viola RNF013. |
| Exibir distância exata | Facilita reidentificação. |

## Consequências

- O modelo depende de PostGIS e de extensão disponível no plano Supabase.
- Endereço sem coordenada reduz a precisão do catálogo; precisa de indicador para a mediação.
- O cache de CEP exige limpeza e pode ficar desatualizado.
- A view de catálogo precisa ser mantida junto com as migrations.
- A ordenação por distância em `geography` pode ser custosa em bases grandes; índices e limites de raio mitigam.

## Critérios de aceite

- [ ] CEP inválido/não encontrado libera digitação e seleção de município/UF.
- [ ] Falha do provedor de CEP não impede o cadastro.
- [ ] Distância é calculada no banco com `ST_DWithin` e índice GiST.
- [ ] Catálogo nunca retorna endereço exato, coordenada, telefone, e-mail ou condição.
- [ ] Apoiador suspenso desaparece imediatamente do catálogo.
- [ ] Horário reservado desaparece no commit.
- [ ] Busca vazia retorna mensagem orientativa, não erro.
- [ ] Paginação de 10 funciona no servidor.
- [ ] Nenhuma chamada externa dentro de transação.
- [ ] Endereço sem coordenada não quebra o catálogo.

## Decisões pendentes

> **DECISÃO PENDENTE 93:** escolher provedor de CEP e de geocodificação/mapas, com termos de uso e DPA.
>
> **DECISÃO PENDENTE 94:** definir precisão e arredondamento da distância exibida.
>
> **DECISÃO PENDENTE 95:** decidir o que fazer quando o endereço não tem coordenada: excluir do filtro por distância ou usar o centro do município.
>
> **DECISÃO PENDENTE 96:** confirmar o raio máximo (100 km é proposta) e se ele varia por especialidade.
>
> **DECISÃO PENDENTE 97:** confirmar a regra "apoiadores do próprio usuário não aparecem" (Decisão 18 do PRD).
