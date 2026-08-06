(() => {
  const selected = () => [...document.querySelectorAll('.compare-id:checked')].map(x => x.value);
  const byId = id => document.getElementById(id);
  const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

  function compareSelected() {
    const ids = selected();
    if (ids.length !== 2) { alert('Выберите ровно два запуска'); return; }
    location.href = '/api/history/compare?left=' + encodeURIComponent(ids[0]) + '&right=' + encodeURIComponent(ids[1]);
  }

  async function buildTrends() {
    const ids = selected();
    if (ids.length < 2 || ids.length > 20) {
      alert('Для тренда выберите от 2 до 20 запусков');
      return;
    }
    const response = await fetch('/api/history/trends?' + ids.map(id => 'ids=' + encodeURIComponent(id)).join('&'));
    if (!response.ok) throw new Error(await response.text());
    const report = await response.json();
    byId('trend-panel').hidden = false;
    byId('trend-summary').innerHTML = [
      ['Throughput', report.throughputChangePercent, '%'],
      ['OPS', report.operationsChangePercent, '%'],
      ['p95', report.p95ChangePercent, '%'],
      ['p99', report.p99ChangePercent, '%'],
      ['Ошибки', report.errorDifference, '']
    ].map(x => `<div class="metric"><span>${escapeHtml(x[0])}</span><strong>${Number(x[1]).toFixed(x[2] ? 1 : 0)}${x[2]}</strong></div>`).join('');
    byId('trend-warning').textContent = report.grouping.homogeneous ? '' :
      'Выбраны запуски с разными endpoint, bucket или операциями. Сравнение отображается, но интерпретировать тренд следует с осторожностью.';
    draw('throughput-chart', report.points, 'throughputMiBps', 'MiB/s');
    draw('ops-chart', report.points, 'operationsPerSecond', 'OPS');
    draw('latency-chart', report.points, 'p95LatencyMs', 'p95 ms');
    draw('errors-chart', report.points, 'errors', 'Ошибки');
    byId('trend-table').innerHTML = report.points.map(p => `<tr><td>${new Date(p.createdAt).toLocaleString()}</td><td>${escapeHtml(p.operation)}</td><td>${Number(p.throughputMiBps).toFixed(2)}</td><td>${Number(p.operationsPerSecond).toFixed(2)}</td><td>${Number(p.p50LatencyMs).toFixed(1)}</td><td>${Number(p.p95LatencyMs).toFixed(1)}</td><td>${Number(p.p99LatencyMs).toFixed(1)}</td><td>${p.errors}</td></tr>`).join('');
  }

  function draw(id, points, field, label) {
    const canvas = byId(id), ctx = canvas.getContext('2d');
    const width = canvas.clientWidth || 600, height = 220, dpr = window.devicePixelRatio || 1;
    canvas.width = width * dpr; canvas.height = height * dpr; ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, width, height);
    const values = points.map(p => Number(p[field]) || 0);
    const max = Math.max(1, ...values), min = Math.min(0, ...values);
    const pad = 32, span = Math.max(1, max - min);
    ctx.strokeStyle = '#d8dde5'; ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(pad, 10); ctx.lineTo(pad, height-pad); ctx.lineTo(width-10, height-pad); ctx.stroke();
    ctx.fillStyle = '#334155'; ctx.font = '12px sans-serif'; ctx.fillText(label, pad + 4, 18);
    ctx.strokeStyle = '#004b8d'; ctx.lineWidth = 2; ctx.beginPath();
    values.forEach((v, i) => {
      const x = pad + (width-pad-14) * (points.length === 1 ? 0 : i/(points.length-1));
      const y = height-pad - ((v-min)/span) * (height-pad-18);
      if (i === 0) ctx.moveTo(x,y); else ctx.lineTo(x,y);
    });
    ctx.stroke();
    ctx.fillStyle = '#c8102e';
    values.forEach((v, i) => {
      const x = pad + (width-pad-14) * (points.length === 1 ? 0 : i/(points.length-1));
      const y = height-pad - ((v-min)/span) * (height-pad-18);
      ctx.beginPath(); ctx.arc(x,y,3,0,Math.PI*2); ctx.fill();
    });
  }

  byId('compare-selected')?.addEventListener('click', compareSelected);
  byId('build-trends')?.addEventListener('click', () => buildTrends().catch(e => alert(e.message)));
})();
