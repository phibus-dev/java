(() => {
  const token=document.querySelector('meta[name="_csrf"]')?.content;
  const header=document.querySelector('meta[name="_csrf_header"]')?.content;
  const headers={'Content-Type':'application/json'}; if(token&&header) headers[header]=token;
  let activeId=null, timer=null;
  const v=id=>document.getElementById(id)?.value;
  const n=id=>Number(v(id)||0);
  const t=(id,value)=>{const e=document.getElementById(id);if(e)e.textContent=value;};

  document.getElementById('provision-form')?.addEventListener('submit',async e=>{
    e.preventDefault();
    const body={profileId:v('provisionProfile'),table:v('provisionTable'),keeperPath:v('keeperPath'),replicaMacro:v('replicaMacro'),dropExisting:document.getElementById('dropExisting').checked};
    try{const r=await fetch('/api/clickhouse/replicated-tables',{method:'POST',headers,body:JSON.stringify(body)});const x=await r.json();if(!r.ok)throw new Error(JSON.stringify(x));t('provision-message',x.success?'Таблица создана на всех endpoints':'Есть ошибки: '+x.nodes.filter(n=>!n.success).map(n=>n.endpoint+': '+n.error).join('; '));}catch(err){t('provision-message',err.message);}
  });

  document.getElementById('failover-form')?.addEventListener('submit',async e=>{
    e.preventDefault();
    const body={profileId:v('profileId'),table:v('table'),sourceEndpoint:v('sourceEndpoint')||null,batchSize:n('batchSize'),payloadBytes:n('payloadBytes'),baselineSeconds:n('baselineSeconds'),faultConfirmationTimeoutSeconds:n('faultConfirmationTimeoutSeconds'),faultObservationSeconds:n('faultObservationSeconds'),recoveryConfirmationTimeoutSeconds:n('recoveryConfirmationTimeoutSeconds'),recoveryTimeoutSeconds:n('recoveryTimeoutSeconds'),pollIntervalMs:n('pollIntervalMs')};
    try{const r=await fetch('/api/clickhouse/failover-tests',{method:'POST',headers,body:JSON.stringify(body)});if(!r.ok)throw new Error(await r.text());const x=await r.json();activeId=x.id;document.getElementById('active').hidden=false;render(x);clearInterval(timer);timer=setInterval(poll,1000);}catch(err){t('message',err.message);}
  });

  document.getElementById('fault-applied')?.addEventListener('click',()=>signal('fault-applied'));
  document.getElementById('recovery-started')?.addEventListener('click',()=>signal('recovery-started'));
  async function signal(action){if(!activeId)return;const r=await fetch(`/api/clickhouse/failover-tests/${activeId}/${action}`,{method:'POST',headers});if(r.ok)render(await r.json());else t('detail',await r.text());}
  async function poll(){if(!activeId)return;const r=await fetch(`/api/clickhouse/failover-tests/${activeId}`);if(!r.ok)return;const x=await r.json();render(x);if(['COMPLETED','FAILED'].includes(x.status)){clearInterval(timer);timer=null;activeId=null;setTimeout(()=>location.reload(),1200);}}
  function render(x){t('status',x.status);t('rows',x.rowsWritten);t('errors',x.failedOperations);t('interruption',`${x.serviceInterruptionMs} ms`);t('recovery',`${x.recoveryTimeMs} ms`);t('delay',`${x.maxReplicationDelaySeconds} s`);t('queue',x.maxReplicationQueue);t('lag',x.maxLogLag);t('consistency',x.consistencyPassed==null?'—':x.consistencyPassed?'PASS':'FAIL');t('detail',x.message||'');document.getElementById('fault-applied').disabled=x.status!=='WAITING_FOR_FAULT';document.getElementById('recovery-started').disabled=x.status!=='WAITING_FOR_RECOVERY';}
})();
