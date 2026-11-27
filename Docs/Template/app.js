(function () {
  "use strict";

  var contador = document.getElementById("contador");
  var marcadorNotificacoes = document.querySelector("[data-evento-notificacoes]");

  function ligarNotificacoes() {
    if (!marcadorNotificacoes || typeof EventSource === "undefined") {
      return;
    }
    try {
      var fonte = new EventSource("/eventos/notificacoes");
      fonte.addEventListener("notificacao", function () {
        var atual = contador ? parseInt(contador.textContent || "0", 10) : 0;
        if (contador) {
          contador.textContent = String((isNaN(atual) ? 0 : atual) + 1);
        }
      });
      // EventSource reconecta sozinho; o polling de fallback e o recarregamento manual.
    } catch (e) {
      // sem SSE: a central continua funcionando por navegacao normal
    }
  }

  function ligarChat() {
    var marcador = document.querySelector("[data-evento-chat]");
    if (!marcador || typeof EventSource === "undefined") {
      return;
    }
    var reserva = marcador.getAttribute("data-evento-chat");
    if (!reserva) {
      return;
    }
    try {
      var fonte = new EventSource("/eventos/chat/" + encodeURIComponent(reserva));
      fonte.addEventListener("mensagem", function () {
        window.location.reload();
      });
    } catch (e) {
      // fallback: recarregar a pagina manualmente
    }
  }

  function cep() {
    var campo = document.querySelector("[data-cep]");
    if (!campo || typeof fetch === "undefined") {
      return;
    }
    campo.addEventListener("blur", function () {
      var valor = (campo.value || "").replace(/\D/g, "");
      if (valor.length !== 8) {
        return;
      }
      fetch("/api/v1/cep/" + valor, { headers: { "Accept": "application/json" } })
        .then(function (resposta) { return resposta.ok ? resposta.json() : null; })
        .then(function (dados) {
          if (!dados) {
            return;
          }
          var bairro = document.querySelector("[data-bairro]");
          var municipio = document.querySelector("[data-municipio]");
          var uf = document.querySelector("[data-uf]");
          if (bairro && !bairro.value) { bairro.value = dados.bairro || ""; }
          if (municipio && !municipio.value) { municipio.value = dados.municipio || ""; }
          if (uf && !uf.value) { uf.value = dados.uf || ""; }
        })
        .catch(function () { /* preenchimento manual continua disponivel */ });
    });
  }

  ligarNotificacoes();
  ligarChat();
  cep();
})();
