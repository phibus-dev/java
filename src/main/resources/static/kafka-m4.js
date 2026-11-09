(() => {
  const $ = id => document.getElementById(id);
  let active = null;
  const fmt = v => Number(v || 0).toLocaleString('ru-RU', {maximumFractionDigits: 2});
  async function json(url, options) {
    const r = await fetch(url, options);
    if (!r.ok) throw new Error((await r.text()) || `${r.status} ${r.statusText}`);
    return r.status === 204 ? null : r.json();
  }
  async function profiles() {
    const rows = await json('/api/kafka/profiles');
    $('profile').innerHTML = rows.map(p => `<option value="${p.id}" ${p.default?'selected':''}>${p.name}</option>`).join('');
    const p = rows.find(x => x.default) || rows[0]; if (p && !$('topic').value) $('topic').value = p.defaultTopic || '';
  }
  function render(r) {
    $('status').textContent=r.status; $('sent').textContent=fmt(r.sentMessages); $('avg').textContent=`${fmt(r.avgMessagesPerSec)} msg/s`;
    $('min').textContent=`${fmt(r.minMessagesPerSec)} msg/s`; $('controllers').textContent=fmt(r.controllerChanges); $('leaders').textContent=fmt(r.leaderChanges);
    $('urp').textContent=fmt(r.maxUnderReplicated); $('offline').textContent=fmt(r.maxOfflinePartitions);
    $('recovery').textContent=r.recoveryTimeMs==null?'—':`${fmt(r.recoveryTimeMs/1000)} s`; $('consistency').textContent=r.consistencyStatus||'—';
    if(r.failureDetectedAt && !r.recoveredAt) $('message').textContent=`Отказ обнаружен: ${r.failureDetectedAt}. Ожидается восстановление Kafka.`;
    else if(r.recoveredAt) $('message').textContent=`Kafka восстановилась: ${r.recoveredAt}.`;
  }
  async function poll() { if(!active) return; try { const r=await json(`/api/kafka/m4/failover-tests/${active}`); render(r); if(r.status==='RUNNING') setTimeout(poll,1000); else {active=null; history();} } catch(e){$('message').textContent=e.message;} }
  async function start() {
    $('message').textContent='Warm-up. После его завершения выполните отказ broker/controller.';
    const body={profileId:$('profile').value,topic:$('topic').value,warmupSeconds:+$('warmup').value,observationSeconds:+$('observe').value,messageSizeBytes:+$('size').value,targetMessagesPerSec:+$('rate').value,acks:$('acks').value};
    try { const r=await json('/api/kafka/m4/failover-tests',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}); active=r.id; render(r); poll(); } catch(e){$('message').textContent=e.message;}
  }
  async function history(){try{const rows=await json('/api/kafka/m4/failover-tests?limit=50');$('history').innerHTML=rows.map(r=>`<tr><td>${r.startedAt||''}</td><td>${r.topic}</td><td>${r.status}</td><td>${r.recoveryTimeMs==null?'—':fmt(r.recoveryTimeMs/1000)+' s'}</td><td>${r.leaderChanges}</td><td>${r.producerErrors}</td><td>${r.consistencyStatus||'—'}</td></tr>`).join('');EvoUI?.refreshHistoryPagination?.();}catch(e){$('message').textContent=e.message;}}
  async function distributed(){try{const rows=await json('/api/distributed-tests');$('distributed').innerHTML=rows.filter(r=>r.testType==='KAFKA').map(r=>`<tr><td>${r.name||''}</td><td>${r.status}</td><td>${(r.agents||[]).length}</td><td>${fmt(r.completedOperations)}</td><td>${fmt(r.operationsPerSecond)}</td><td>${fmt(r.throughputMiBps)}</td><td>${fmt(r.p99LatencyMs)}</td><td>${fmt(r.errors)}</td></tr>`).join('');}catch(e){$('distributed').innerHTML='<tr><td colspan="8">Distributed coordinator недоступен</td></tr>';}}
  $('start').addEventListener('click', start); profiles().then(history).then(distributed).catch(e => $('message').textContent=e.message); setInterval(distributed,5000);
})();
