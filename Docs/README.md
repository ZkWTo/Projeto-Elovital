# Documentação do EloVital

- **Status geral:** proposta para revisão
- **Data:** 2026-11-16
- **Documento de origem:** [Documento de Requisitos Funcionais do EloVital](../../Entrega/alterado/Documento_Requisitos_Funcionais_EloVital_ALTERADO.docx)

Esta pasta reúne a documentação de produto e de arquitetura do EloVital, escrita a partir do Documento de Requisitos e das convenções do projeto de referência. Nada aqui altera código ou banco em produção: tudo está em revisão até a aprovação da pessoa responsável.

## O que ler primeiro

1. **[PRD 0001 — EloVital](prd/0001-elovital.md):** o que o produto faz, para quem, com quais requisitos e o que fica fora da primeira versão.
2. **[ADR 0001 — MVT com camada de service](adr/0001-mvt-com-camada-de-service.md):** como o código se organiza.
3. **[ADR 0002 — Usuário, papéis e autenticação](adr/0002-usuario-papeis-e-autenticacao.md):** como as contas e permissões funcionam.
4. **[ADR 0003 — Conteúdo em texto puro](adr/0003-conteudo-em-texto-puro.md):** como textos, chat e avaliações são tratados.
5. **[ADR 0004 — Supabase (PostgreSQL) como banco](adr/0004-supabase-postgresql.md):** a decisão de trocar o SQLite por PostgreSQL gerenciado.
6. **[Proposta do banco Supabase](propostas/banco-supabase/README.md):** configuração, modelo de dados, segurança, LGPD e plano de migração. **Este pacote está separado justamente para leitura e aprovação.**

## O que está proposto e o que está aceito

| Documento | Status | Observação |
|---|---|---|
| PRD 0001 | Proposto | Aguarda aprovação dos requisitos |
| ADR 0001 | Proposto | Convenção herdada do projeto de referência |
| ADR 0002 | Proposto | Depende da aprovação do modelo de contas |
| ADR 0003 | Proposto | Depende da aprovação do escopo de conteúdo |
| ADR 0004 | Proposto | Depende da aprovação do pacote Supabase |
| Proposta Supabase | Rascunho para aprovação | Nada é aplicado antes do aceite |

## Regra de separação

- Os documentos em `adr/` e `prd/` descrevem o produto e as decisões de arquitetura.
- O pacote `propostas/banco-supabase/` concentra **tudo que muda por causa do Supabase**: configuração de conexão, papéis, schema, RLS, criptografia, retenção, LGPD, migração e checklist de aprovação.
- A proposta não foi aplicada em código, migrations ou no projeto Supabase. Ela é um documento de decisão.

## Como aprovar

1. Leia o PRD e confirme o escopo da primeira versão.
2. Leia os ADRs 0001 a 0003 e confirme as convenções.
3. Leia o pacote Supabase na ordem sugerida no [README da proposta](propostas/banco-supabase/README.md).
4. Marque o [checklist de aprovação](propostas/banco-supabase/05-checklist-aprovacao.md).
5. Depois do aceite, os status passam de `Proposto` para `Aceito` e as migrations podem ser escritas.

## Rastreabilidade com o documento de origem

- **RF001 a RF017:** requisitos funcionais.
- **RNF001 a RNF015:** requisitos não funcionais.
- **RS001 a RS013:** requisitos de segurança e riscos.

Os identificadores foram preservados exatamente como estão no Documento de Requisitos, para que PRD, ADRs, proposta de banco e futuras specs falem a mesma língua. O PRD resume o comportamento; os detalhes de campos, limites e regras continuam no documento de origem e deverão ser detalhados nas specs de implementação.
