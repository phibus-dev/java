(() => {
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
  const headers = {'Content-Type': 'application/json'};
  if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;
  let activeId = null;
  let activeProfileId = null;
  let pollTimer = null;
  let replicationTimer = null;

  const n = id => Number(document.getElementById(id).value || 0);
  const text = (id, value) => { const el = document.getElementById(id); if (el) el.textContent = value; };
  const fmt = (value, digits = 2) => Number(value || 0).toFixed(digits);

  document.getElementById('clickhouse-test-form')?.addEventListener('submit', async event => {
    event.preventDefault();
    const request = {
      profileId: document.getElementById('profileId').value,
      endpoint: document.getElementById('endpoint').value || null,
      table: document.getElementById('table').value,
      operation: document.getElementById('operation').value,
      concurrency: n('concurrency'), batchSize: n('batchSize'), rowCount: n('rowCount'),
      durationSeconds: n('durationSeconds'), warmupSeconds: n('warmupSeconds'), payloadBytes: n('payloadBytes'),
      autoCreateTable: document.getElementById('autoCreateTable').checked
    };
    try {
      const response = await fetch('/api/clickhouse/tests', {method: 'POST', headers, body: JSON.stringify(request)});
      if (!response.ok) throw new Error(await response.text());
      const run = await response.json();
      activeId = run.id;
      activeProfileId = request.profileId;
      document.getElementById('active-test').hidden = false;
      renderRun(run);
      clearInterval(pollTimer);
      clearInterval(replicationTimer);
      pollTimer = setInterval(pollRun, 1000);
      replicationTimer = setInterval(pollReplication, 3000);
      pollReplication();
      text('run-message', 'Тест запущен');
    } catch (e) { text('run-message', e.message); }
  });

  document.getElementById('cancel-test')?.addEventListener('click', async () => {
    if (!activeId) return;
    await fetch(`/api/clickhouse/tests/${activeId}`, {method: 'DELETE', headers});
    await pollRun();
  });

  async function pollRun() {
    if (!activeId) return;
    const response = await fetch(`/api/clickhouse/tests/${activeId}`);
    if (!response.ok) return;
    const run = await response.json();
    renderRun(run);
    if (['COMPLETED','FAILED','CANCELLED'].includes(run.status)) {
      clearInterval(pollTimer); pollTimer = null;
      clearInterval(replicationTimer); replicationTimer = null;
      await pollReplication();
      activeId = null; activeProfileId = null;
      setTimeout(() => location.reload(), 800);
    }
  }

  async function pollReplication() {
    if (!activeProfileId) return;
    try {
      const response = await fetch(`/api/clickhouse/replication?profileId=${encodeURIComponent(activeProfileId)}`);
      if (!response.ok) throw new Error(await response.text());
      const snapshot = await response.json();
      renderReplication(snapshot);
    } catch (e) {
      text('rep-detail', `Replication snapshot error: ${e.message}`);
    }
  }

  function renderReplication(snapshot) {
    const nodes = snapshot.nodes || [];
    const rank = {OK: 0, WARNING: 1, CRITICAL: 2};
    const worst = nodes.reduce((value, node) => rank[node.health?.status] > rank[value] ? node.health.status : value, 'OK');
    const max = (field) => Math.max(0, ...nodes.map(node => Number(node.health?.[field] || 0)));
    const sum = (field) => nodes.reduce((total, node) => total + Number(node.health?.[field] || 0), 0);
    text('rep-health', worst);
    text('rep-delay', `${max('maxAbsoluteDelaySeconds')} s`);
    text('rep-log-lag', max('maxLogLag'));
    text('rep-queue', sum('queueSize'));
    text('rep-readonly', sum('readonlyReplicas'));
    text('rep-inactive', sum('inactiveReplicas'));
    const reachable = nodes.filter(node => node.reachable).length;
    text('rep-detail', `${snapshot.database} · nodes ${reachable}/${nodes.length} · snapshot ${new Date(snapshot.collectedAt).toLocaleTimeString()}`);
  }

  function renderRun(run) {
    text('run-status', run.status); text('run-percent', `${fmt(run.percent, 1)}%`);
    text('run-rows-rate', fmt(run.rowsPerSecond)); text('run-mib-rate', fmt(run.mibPerSecond));
    text('run-qps', fmt(run.queriesPerSecond)); text('run-p95', `${fmt(run.p95LatencyMs, 1)} ms`);
    text('run-p99', `${fmt(run.p99LatencyMs, 1)} ms`); text('run-errors', run.errors);
    text('run-detail', `${run.operation} · ${run.endpoint} · ${run.table} · ${run.message || ''}`);
  }

  function selectedIds() { return [...document.querySelectorAll('.compare-check:checked')].map(x => x.value); }
  document.querySelectorAll('.compare-check').forEach(box => box.addEventListener('change', () => {
    const selected = selectedIds();
    if (selected.length > 2) { box.checked = false; return; }
    document.getElementById('compare-selected').disabled = selectedIds().length !== 2;
  }));
  document.getElementById('compare-selected')?.addEventListener('click', () => {
    const ids = selectedIds();
    if (ids.length === 2) location.href = `/clickhouse/compare?left=${encodeURIComponent(ids[0])}&right=${encodeURIComponent(ids[1])}`;
  });

  document.getElementById('load-trends')?.addEventListener('click', loadTrends);
  async function loadTrends() {
    const op = document.getElementById('trend-operation').value;
    const table = document.getElementById('trend-table').value;
    const params = new URLSearchParams({limit: '50'});
    if (op) params.set('operation', op);
    if (table) params.set('table', table);
    const response = await fetch(`/api/clickhouse/history/trends?${params}`);
    if (!response.ok) return;
    drawTrends(await response.json());
  }

  function drawTrends(points) {
    const host = document.getElementById('trend-chart');
    if (!points.length) { host.innerHTML = '<p class="muted">Нет данных для выбранного фильтра.</p>'; return; }
    const width = 1000, height = 260, pad = 45;
    const values = points.map(p => p.rowsPerSecond);
    const max = Math.max(1, ...values);
    const x = i => pad + (points.length === 1 ? 0 : i * (width - pad * 2) / (points.length - 1));
    const y = v => height - pad - v * (height - pad * 2) / max;
    const polyline = points.map((p,i) => `${x(i)},${y(p.rowsPerSecond)}`).join(' ');
    let grid = '';
    for (let i=0;i<=5;i++) { const gy=pad+i*(height-pad*2)/5; const val=max*(5-i)/5; grid += `<line x1="${pad}" y1="${gy}" x2="${width-pad}" y2="${gy}" stroke="currentColor" opacity=".12"/><text x="4" y="${gy+4}" font-size="12">${val.toFixed(0)}</text>`; }
    host.innerHTML = `<svg viewBox="0 0 ${width} ${height}" role="img" aria-label="Rows per second trend" style="width:100%;height:260px">${grid}<polyline points="${polyline}" fill="none" stroke="currentColor" stroke-width="3"/>${points.map((p,i)=>`<circle cx="${x(i)}" cy="${y(p.rowsPerSecond)}" r="4"><title>${new Date(p.createdAt).toLocaleString()} · ${p.rowsPerSecond.toFixed(2)} rows/s · P95 ${p.p95LatencyMs.toFixed(1)} ms</title></circle>`).join('')}<text x="${width/2}" y="${height-8}" text-anchor="middle" font-size="13">Запуски →</text><text x="14" y="${height/2}" font-size="13" transform="rotate(-90 14 ${height/2})">rows/sec</text></svg>`;
  }

  loadTrends();
})();
