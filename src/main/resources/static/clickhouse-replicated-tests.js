(() => {
  const token = document.querySelector('meta[name="_csrf"]')?.content;
  const header = document.querySelector('meta[name="_csrf_header"]')?.content;
  const headers = {'Content-Type':'application/json'}; if (token && header) headers[header] = token;
  let activeId = null, timer = null;
  const v = id => document.getElementById(id).value;
  const n = id => Number(v(id) || 0);
  const text = (id, value) => { const e=document.getElementById(id); if(e)e.textContent=value; };
  const errorMessage = async response => { try { const body=await response.json(); return body.message||body.error||`HTTP ${response.status}`; } catch (_) { return `HTTP ${response.status}`; } };
  document.getElementById('scenario-form')?.addEventListener('submit', async e => {
    e.preventDefault();
    const body = {profileId:v('profileId'),scenario:v('scenario'),table:v('table'),sourceEndpoint:v('sourceEndpoint')||null,
      rows:n('rows'),batchSize:n('batchSize'),payloadBytes:n('payloadBytes'),catchupTimeoutSeconds:n('catchupTimeoutSeconds'),pollIntervalMs:n('pollIntervalMs')};
    try {
      const r=await fetch('/api/clickhouse/replicated-tests',{method:'POST',headers,body:JSON.stringify(body)});
      if(!r.ok) throw new Error(await errorMessage(r));
      const run=await r.json(); activeId=run.id; document.getElementById('active').hidden=false; render(run);
      clearInterval(timer); timer=setInterval(poll,1000); text('message','Сценарий запущен');
    } catch(err){text('message',err.message);}
  });
  async function poll(){ if(!activeId)return; const r=await fetch(`/api/clickhouse/replicated-tests/${activeId}`); if(!r.ok)return; const run=await r.json(); render(run);
    if(['COMPLETED','FAILED','CANCELLED'].includes(run.status)){clearInterval(timer);timer=null;activeId=null;setTimeout(()=>location.reload(),800);}}
  function render(run){text('status',run.status);text('rowsRate',Number(run.insertRowsPerSecond||0).toFixed(1));text('catchup',`${run.replicationCatchupMs||0} ms`);
    text('delay',`${run.maxReplicationDelaySeconds||0} s`);text('queue',run.maxReplicationQueue||0);text('lag',run.maxLogLag||0);
    text('consistency',run.consistencyPassed===null?'—':run.consistencyPassed?'PASS':'FAIL');text('replicas',run.replicaCount||0);text('detail',run.message||'');}
})();
