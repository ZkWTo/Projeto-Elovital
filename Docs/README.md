# Documentação do EloVital

- **Status geral:** proposta para revisão
- **Data:** 2026-11-23
- **Documento de origem:** [Documento de Requisitos Funcionais do EloVital](Documento_Requisitos_Funcionais_EloVital_versãopdf.pdf)

Esta pasta reúne a documentação de produto e de arquitetura do EloVital, escrita a partir do Documento de Requisitos e das convenções do projeto de referência. Nada aqui altera código ou banco em produção: tudo está em revisão até a aprovação da pessoa responsável.

## O que está proposto e o que está aceito

| Documento | Status | Observação |
|---|---|---|
| PRD 0001 | Proposto | Aguarda aprovação dos requisitos |
| ADRs 0001–0004 | Substituídos | Escritos para a referência em Django; mantidos como histórico |
| ADRs 0005–0023 | Propostos | Série Java 21 + Spring Boot 3, alinhada ao fluxo do PRD |
| Proposta Supabase | Rascunho para aprovação | Nada é aplicado antes do aceite |

## Regra de separação

- Os documentos em `adr/` e `prd/` descrevem o produto e as decisões de arquitetura.
- O pacote `propostas/banco-supabase/` concentra **tudo que muda por causa do Supabase**: configuração de conexão, papéis, schema, RLS, criptografia, retenção, LGPD, migração e checklist de aprovação.
- A proposta não foi aplicada em código, migrations ou no projeto Supabase. Ela é um documento de decisão.



## Mudança de stack registrada

- **Backend:** Java 21 + Spring Boot 3 (antes: Django/Python).
- **Persistência:** Spring Data JPA + Flyway sobre PostgreSQL/Supabase (antes: ORM e migrations do Django).
- **Autenticação:** Spring Security com sessão no servidor (antes: autenticação do Django).
- **Frontend:** páginas renderizadas no servidor com Thymeleaf + HTMX e SSE (ver [ADR 0010](adr/0010-entrega-web-e-api.md)).
- **Banco:** a decisão do [ADR 0004](adr/0004-supabase-postgresql.md) permanece; muda o cliente.

## Rastreabilidade com o documento de origem

- **RF001 a RF017:** requisitos funcionais.
- **RNF001 a RNF015:** requisitos não funcionais.
- **RS001 a RS013:** requisitos de segurança e riscos.

Os identificadores foram preservados exatamente como estão no Documento de Requisitos, para que PRD, ADRs, proposta de banco e futuras specs falem a mesma língua. O PRD agora reproduz os campos, limites, regras, matrizes, pontos de função e protótipos do documento de origem; as decisões ainda abertas estão marcadas nele como `DECISÃO PENDENTE`.
