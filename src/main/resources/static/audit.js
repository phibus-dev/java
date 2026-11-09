(() => {
  const byId = id => document.getElementById(id);
  const rows = byId('rows');
  const message = byId('audit-message');

  async function getJson(url) {
    const response = await fetch(url, {credentials: 'same-origin'});
    if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
    return response.json();
  }

  async function loadSummary() {
    const summary = await getJson('/api/audit/summary');
    byId('total').textContent = String(summary.total ?? 0);
    byId('errors').textContent = String(summary.errors ?? 0);
  }

  function appendCell(row, value, className) {
    const cell = document.createElement('td');
    cell.textContent = value == null ? '' : String(value);
    if (className) cell.className = className;
    row.appendChild(cell);
  }

  async function loadAudit() {
    const parameters = new URLSearchParams({limit: '200'});
    for (const id of ['username', 'method', 'status']) {
      const value = byId(id).value.trim();
      if (value) parameters.set(id, value);
    }
    for (const id of ['from', 'to']) {
      const value = byId(id).value;
      if (value) parameters.set(id, new Date(value).toISOString());
    }
    const events = await getJson(`/api/audit?${parameters}`);
    rows.replaceChildren();
    for (const event of events) {
      const row = document.createElement('tr');
      appendCell(row, event.occurredAt);
      appendCell(row, event.username);
      appendCell(row, event.action);
      appendCell(row, event.httpMethod);
      appendCell(row, event.requestPath);
      appendCell(row, event.responseStatus, Number(event.responseStatus) >= 400 ? 'error' : 'ok');
      appendCell(row, event.remoteAddress);
      appendCell(row, event.durationMs);
      rows.appendChild(row);
    }
    if (!events.length) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 8;
      cell.textContent = 'События не найдены';
      row.appendChild(cell);
      rows.appendChild(row);
    }
  }

  async function refresh() {
    message.textContent = '';
    try {
      await Promise.all([loadSummary(), loadAudit()]);
    } catch (error) {
      message.textContent = `Не удалось загрузить аудит: ${error.message}`;
    }
  }

  byId('apply-filters').addEventListener('click', refresh);
  refresh();
  const timer = window.setInterval(refresh, 30000);
  window.addEventListener('pagehide', () => window.clearInterval(timer), {once: true});
})();
