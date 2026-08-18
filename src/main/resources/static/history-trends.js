(() => {
  const selected = () => [...document.querySelectorAll('.compare-id:checked')].map(x => x.value);
  const byId = id => document.getElementById(id);
  const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const trendCharts = {};
  const nextPaint = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));

  function compareSelected() {
    const ids = selected();
    if (ids.length !== 2) { alert('Выберите ровно два запуска'); return; }
    location.href = '/history/compare?left=' + encodeURIComponent(ids[0]) + '&right=' + encodeURIComponent(ids[1]);
  }

  function metricCard(name, value, unit, lowerIsBetter = false) {
    const numeric = Number(value) || 0;
    const improved = lowerIsBetter ? numeric < 0 : numeric > 0;
    const degraded = lowerIsBetter ? numeric > 0 : numeric < 0;
    const css = improved ? 'trend-good' : degraded ? 'trend-bad' : 'trend-neutral';
    const arrow = numeric > 0 ? '▲' : numeric < 0 ? '▼' : '●';
    return `<div class="metric ${css}"><span>${escapeHtml(name)}</span><strong>${arrow} ${numeric > 0 ? '+' : ''}${numeric.toFixed(unit ? 1 : 0)}${unit}</strong></div>`;
  }

  function chart(id, options) {
    if (!trendCharts[id]) trendCharts[id] = new EvoPerformanceChart(byId(id), options);
    return trendCharts[id];
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
      metricCard('Throughput', report.throughputChangePercent, '%'),
      metricCard('OPS', report.operationsChangePercent, '%'),
      metricCard('p95', report.p95ChangePercent, '%', true),
      metricCard('p99', report.p99ChangePercent, '%', true),
      metricCard('Ошибки', report.errorDifference, '', true)
    ].join('');

    const warnings = [];
    if (!report.grouping.homogeneous) warnings.push('Выбраны запуски с разными endpoint, bucket или операциями. Сравнение отображается, но интерпретировать тренд следует с осторожностью.');
    const first = report.points?.[0];
    if (first && (Number(first.throughputMiBps) === 0 || Number(first.operationsPerSecond) === 0)) {
      warnings.push('Для метрики с нулевым исходным значением переход к ненулевому значению отображается как ±100%, поскольку обычное процентное изменение от нуля не определено.');
    }
    byId('trend-warning').textContent = warnings.join(' ');

    await nextPaint();
    const common = {height:320,xTitle:'Запуски',xLabel:p=>new Date(p.createdAt).toLocaleString()};
    chart('throughput-chart',{...common,yUnit:'MiB/s'}).setData(report.points,[{name:'Throughput',unit:'MiB/s',value:p=>p.throughputMiBps}]);
    chart('ops-chart',{...common,yUnit:'OPS'}).setData(report.points,[{name:'Operations/sec',unit:'OPS',value:p=>p.operationsPerSecond}]);
    chart('latency-chart',{...common,yUnit:'ms'}).setData(report.points,[
      {name:'p50',unit:'ms',value:p=>p.p50LatencyMs},
      {name:'p95',unit:'ms',value:p=>p.p95LatencyMs},
      {name:'p99',unit:'ms',value:p=>p.p99LatencyMs}
    ]);
    chart('errors-chart',{...common,yUnit:'ошибок'}).setData(report.points,[{name:'Ошибки',unit:'',value:p=>p.errors}]);
    requestAnimationFrame(() => Object.values(trendCharts).forEach(item => item.draw()));

    byId('trend-table').innerHTML = report.points.map(p => `<tr><td>${new Date(p.createdAt).toLocaleString()}</td><td>${escapeHtml(p.operation)}</td><td>${Number(p.throughputMiBps).toFixed(2)}</td><td>${Number(p.operationsPerSecond).toFixed(2)}</td><td>${Number(p.p50LatencyMs).toFixed(1)}</td><td>${Number(p.p95LatencyMs).toFixed(1)}</td><td>${Number(p.p99LatencyMs).toFixed(1)}</td><td>${p.errors}</td></tr>`).join('');
    byId('trend-panel').scrollIntoView({behavior:'smooth',block:'start'});
  }

  byId('compare-selected')?.addEventListener('click', compareSelected);
  byId('build-trends')?.addEventListener('click', () => buildTrends().catch(e => alert(e.message)));
})();
