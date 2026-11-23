# Projeto-Elovital

 = Detalhamento do projeto =

## Como o projeto foi construído

O EloVital é uma plataforma web gratuita de rede de apoio a pessoas com doenças
crônicas. O sistema aproxima Assistidos — pessoas que precisam de apoio cotidiano —
de Apoiadores — voluntários e profissionais que oferecem tempo e conhecimento —,
sempre com mediação humana e com o contato acontecendo pelo canal interno da
plataforma. O projeto não é um serviço de emergência, não realiza despacho automático
de voluntários e não intermedeia pagamentos, doações ou qualquer valor financeiro.

A construção partiu de um Documento de Requisitos elaborado especificamente para o
EloVital. Esse documento reúne a visão geral do sistema, o escopo da primeira versão e
as dezessete funcionalidades principais descritas como casos de uso, com atores, campos,
limites, fluxos principal e alternativo, regras de negócio e saídas esperadas. Cada
requisito recebeu identificador próprio e classificação de prioridade — essencial,
importante ou desejável —, o que orientou o recorte da versão inicial e a ordem de
implementação.

Além das funcionalidades, o documento serviu de base para os quinze requisitos não
funcionais, que tratam de usabilidade, acessibilidade, compatibilidade, desempenho,
disponibilidade, integridade das operações, backup, escalabilidade, parametrização,
conformidade legal, auditabilidade, integrações externas, localização e gratuidade.
A acessibilidade segue a WCAG 2.1 nível AA; o tratamento de dados segue a LGPD
(Lei nº 13.709/2018), incluindo consentimento específico para dados de saúde; e os
registros de acesso seguem o Marco Civil da Internet (Lei nº 12.965/2014).

O projeto também foi guiado por treze requisitos de segurança derivados de uma análise
de riscos, que relaciona cada perigo identificado — como exposição indevida de dados
sensíveis, perfis falsos, golpes financeiros e inconsistência de estado nas reservas — às
soluções adotadas e aos requisitos afetados. Para manter a coerência entre as partes do
documento, foi utilizada uma matriz de rastreabilidade entre os requisitos e, para
dimensionar o esforço de desenvolvimento, uma estimativa por Análise de Pontos de
Função, com contagem de arquivos lógicos, interfaces, entradas, saídas e consultas
externas.

O desenvolvimento foi acompanhado por protótipos de alta fidelidade das principais
telas, usados como referência de navegação, de hierarquia de informação e de linguagem
visual. As integrações externas previstas são consulta de CEP, base de municípios do
IBGE, geolocalização e mapas para cálculo de distância e envio de e-mail. A primeira
versão é um site responsivo, mobile-first, sem aplicativos nativos.

Ficaram explicitamente fora do escopo desta versão: aplicativos nativos, cobrança ou
qualquer forma de pagamento, contas institucionais, despacho automático, anexos e
chamadas no chat, autenticação em dois fatores, API pública, moderação automática por
inteligência artificial e internacionalização. O uso é gratuito para Assistidos e
Apoiadores.

Por se tratar de um domínio sensível, este repositório apresenta apenas a visão pública
do projeto. A documentação interna de implementação, as decisões técnicas em aberto e os
detalhes de segurança não são reproduzidos aqui.


* Arquitetura usada
  *Documentação por Requisitos
  
    *Introduçao
  
  *Desenvolvimento da ideia do projeto
  
  *Construção de Documentação de Requisitos Funcionais
 •	RF001 – Cadastro e Login
        •	RF002 -  Autenticação e recuperação de acesso
        •	RF003 – Perfil, preferências e privacidade
        •	RF004 - Agenda solidária do apoiador
        •	RF005 - Permitir que o Assistido registre uma necessidade de apoio
        •	RF006 - Análise e validação da solicitação pelo mediador
        •	RF007 – Catalogo de apoiadores disponíveis e filtros
        •	RF008 - Reserva de horário e vínculo de apoio
        •	RF009 - Cancelamento e remarcação
        •	RF010 – Notificações
        •	RF011 - Canal de conversa (chat)
        •	RF012 - Conclusão do apoio (dupla confirmação)
        •	RF013 - Avaliação entre assistido e apoiador
        •	RF014 - Denúncia de usuário ou conteúdo
        •	RF015 - administrativa e mediação
        •	RF016 – Verificação de identidade e de registro profissional
        •	RF017 - Histórico, ocorrências e expiração
  
  
    *Requisitos não-Funcionais 
   * WCAG 2.1 Nivel AA

       * LGPD 13.709/2018
         
        * Marco Civel Da Internet 12.965/2014
          
          * Politicas de Privacidade (Constituição Art. 11)
   
    * Requisitos de Segurança
      * Politica contra Vazamento de Dados
        
      *  Acesso Bloqueado a Terceiros
        
      *  Bloqueio  A Informações sensíveis
        
      *  Proteção Contra Medidas Abusivas

  *Matriz de Avaliação de Riscos
    * Catastróficos = RS004 RS013 RS001
    * Críticos = RS011 RS002 RS003 RS005 RS008 RS010 RS012
    * Marginal = RS006 RS007 RS009
    * Insignificante =  

* Pontos de Função - Elovital

PONTO DE FUNÇÃO – ELOVITAL 
Requisito	Complexida de	ALI	AIE	SE	EE	CE	Total
RF001	Alta	30	7	5	6	0	48

RF002	Baixa	0	0	4	6	0	10

RF003	Média	10	0	0	4	4	18

RF004	Alta	15	0	5	12	6	38

RF005	Alta	15	7	7	6	6	41

RF006	Alta	10	0	7	12	12	41

RF007	Alta	0	7	7	0	12	26

RF008	Alta	0	0	14	12	6	32

RF009	Média	0	0	5	8	4	17

RF010	Média	10	7	10	0	4	31

RF011	Alta	15	0	7	6	6	34

RF012	Média	0	0	5	4	0	9

RF013	Média	10	0	5	4	4	23

RF014	Média	10	0	5	4	0	19

RF015	Alta	15	0	7	12	12	46

RF016	Média	10	0	5	4	0	19

RF017	Média	0	0	5	4	4	13

TOTAL	—	150	28	103	104	80	465


****
      
        
