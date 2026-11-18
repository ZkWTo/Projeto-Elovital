# ADR 0001: MVT com camada de service e views baseadas em `View`

- **Status:** Proposto
- **Data:** 2026-11-16
- **PRD:** [PRD 0001](../prd/0001-elovital.md)

## Contexto

O EloVital tem regras de negócio que não podem ficar espalhadas entre views, formulários, models e signals: aprovação humana de contas e solicitações, reserva atômica de horário, liberação progressiva de dados, dupla confirmação, sanções e auditoria. Essas regras precisam ser testadas sem HTTP e reutilizadas pela mediação.

As generic views do Django reduzem código, mas escondem o fluxo em herança e costumam levar regra de negócio para métodos sobrescritos.

## Decisão

O projeto segue MVT, com todas as regras de negócio em uma camada de service que não conhece HTTP:

- Toda view é uma classe que herda diretamente de `django.views.View` e implementa apenas os métodos HTTP necessários.
- Mixins de controle de acesso, como `LoginRequiredMixin`, são permitidos.
- A view recebe a requisição, valida a entrada pelo form, chama o service e devolve template ou redirect.
- O service concentra regra de negócio e consulta ao ORM, não recebe `request` e não retorna `HttpResponse`.
- Falhas de negócio são exceções de domínio, convertidas em resposta HTTP pela view.
- Formulários validam formato e renderizam campos; a gravação é feita pelo service, sem `form.save()`.
- Models definem campos, relacionamentos e constraints, sem regra de negócio.
- O Admin chama os mesmos services e não ignora as regras.

## Consequências

- As regras podem ser testadas sem cliente HTTP e sem banco de dados real quando a dependência for isolada.
- A mediação e o Admin reaproveitam os mesmos serviços.
- Há mais código repetitivo nas views, principalmente em formulários.
- O `ModelForm.save()` não é usado.
- Um teste automatizado pode verificar que nenhuma view herda de generic view e que nenhum service importa `HttpResponse`.
- Ações que dependem da sessão HTTP, como `login()` e `logout()`, permanecem na view.
