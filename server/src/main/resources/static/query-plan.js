/*
 * Parent-side glue for the /ux/query-plan page's PEV2 iframe. Waits for the
 * pev2-frame.html document to signal it has mounted ({type: 'pev2-ready'}),
 * then posts the captured plan/sql text to it. Keeping the handshake
 * message-based (rather than an HTML attribute or inline JS string) avoids
 * any escaping concerns with large/arbitrary captured plan text.
 */
(function () {
  const frame = document.getElementById('pev2-frame');
  const dataEl = document.getElementById('plan-data');
  if (!frame || !dataEl) {
    return;
  }

  const data = JSON.parse(dataEl.textContent);

  window.addEventListener('message', function (evt) {
    if (evt.source !== frame.contentWindow || !evt.data || evt.data.type !== 'pev2-ready') {
      return;
    }
    frame.contentWindow.postMessage({type: 'pev2-plan', plan: data.plan || '', query: data.sql || ''}, '*');
  });
})();
