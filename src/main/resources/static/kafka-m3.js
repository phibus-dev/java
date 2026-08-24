(() => {
  const $ = id => document.getElementById(id);
  let activeRun = null;

  async function api(url, options={}) {
    const r = await fetch(url, {credentials:'same-origin', headers:{'Content-Type':'application/json', ...(options.headers||{})}, ...options});
    if (!r.ok) throw new Error((await r.text()) || `HTTP ${r.status}`);
    return r.status === 204 ? null : r.json();
  }

  async function loadProfiles(){
    const items = await api('/api/kafka/profiles');
    $('profile').innerHTML = items.map(p => `<option value="${p.id}" ${p.defaultProfile?'selected':''}>${esc(p.name)}</option>`).join('');
  }

  async function kraft(){
    try {
      $('kraftOutput').textContent='Проверка…';
      $('kraftOutput').textContent=JSON.stringify(await api(`/api/kafka/m3/kraft-health?profileId=${encodeURIComponent($('profile').value)}`),null,2);
    } catch(e){ $('kraftOutput').textContent=e.message; EvoUI?.notify(e.message,'error'); }
  }

  async function startReplication(){
    try {
      activeRun = await api('/api/kafka/m3/replication-tests',{method:'POST',body:JSON.stringify({profileId:$('profile').value,topic:$('repTopic').value.trim()||null})});
      poll();
    } catch(e){ EvoUI?.notify(e.message,'error'); }
  }

  async function startScaling(){
    try {
      const partitionCounts=$('partitions').value.split(',').map(v=>Number(v.trim())).filter(Number.isFinite);
      activeRun = await api('/api/kafka/m3/partition-scaling-tests',{method:'POST',body:JSON.stringify({
        profileId:$('profile').value, partitionCounts,
        messagesPerPoint:Number($('messages').value), messageSizeBytes:Number($('messageSize').value),
        replicationFactor:Number($('replicationFactor').value), acks:$('acks').value, compressionType:$('compression').value
      })});
      poll();
    } catch(e){ EvoUI?.notify(e.message,'error'); }
  }

  async function poll(){
    if(!activeRun) return;
    try{
      activeRun=await api(`/api/kafka/m3/runs/${activeRun.id}`);
      render(activeRun);
      await loadHistory();
      if(activeRun.status==='RUNNING') setTimeout(poll,1000);
    }catch(e){EvoUI?.notify(e.message,'error');}
  }

  function render(run){
    if(run.testType==='KAFKA_REPLICATION'){
      $('repStatus').textContent=run.status;
      $('repPartitions').textContent=run.partitionCount??0;
      $('repUnder').textContent=run.underReplicatedPartitions??0;
      $('repOffline').textContent=run.offlinePartitions??0;
      $('repMinIsr').textContent=run.minIsrViolations??0;
    }
    $('scaleRows').innerHTML=(run.scalingPoints||[]).map(p=>`<tr><td>${p.partitions}</td><td>${p.replicationFactor}</td><td>${p.completedMessages}</td><td>${p.errorCount}</td><td>${fmt(p.messagesPerSecond)}</td><td>${fmt(p.mibPerSecond)}</td><td>${fmt(p.durationSeconds)} s</td></tr>`).join('');
  }

  async function loadHistory(){
    const items=await api('/api/kafka/m3/runs?limit=100');
    $('history').innerHTML=items.map(r=>`<tr><td>${esc(r.startedAt||'')}</td><td>${esc(r.testType)}</td><td>${esc(r.topic||'—')}</td><td>${esc(r.status)}</td><td>${r.underReplicatedPartitions??0}</td><td>${r.offlinePartitions??0}</td><td>${esc(r.errorMessage||'')}</td></tr>`).join('');
  }

  function fmt(v){return Number(v||0).toLocaleString('ru-RU',{maximumFractionDigits:2});}
  function esc(v){return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}

  $('kraftCheck').addEventListener('click',kraft);
  $('repStart').addEventListener('click',startReplication);
  $('scaleStart').addEventListener('click',startScaling);
  Promise.all([loadProfiles(),loadHistory()]).catch(e=>EvoUI?.notify(e.message,'error'));
})();
