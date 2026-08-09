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
  if (!dataEl) {
    return;
  }

  const data = JSON.parse(dataEl.textContent);
  const copyButton = document.getElementById('copy-plan');
  const copyStatus = document.getElementById('copy-plan-status');

  if (copyButton) {
    copyButton.addEventListener('click', function () {
      if (!navigator.clipboard) {
        if (copyStatus) {
          copyStatus.textContent = 'Clipboard unavailable';
        }
        return;
      }
      navigator.clipboard.writeText(data.plan || '')
        .then(function () {
          if (copyStatus) {
            copyStatus.textContent = 'Copied';
          }
        })
        .catch(function () {
          if (copyStatus) {
            copyStatus.textContent = 'Copy failed';
          }
        });
    });
  }

  if (!frame) {
    return;
  }
  window.addEventListener('message', function (evt) {
    if (evt.source !== frame.contentWindow || !evt.data || evt.data.type !== 'pev2-ready') {
      return;
    }
    frame.contentWindow.postMessage({type: 'pev2-plan', plan: data.plan || '', query: data.sql || ''}, '*');
  });
})();
