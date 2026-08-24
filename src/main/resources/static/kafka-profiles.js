(() => {
  const $ = id => document.getElementById(id);
  const fields = ['name','bootstrapServers','securityProtocol','saslMechanism','username','credentialsSource','vaultSecretPath','passwordField','passwordEnv','caCertificatePath','defaultTopic','clientIdPrefix'];
  let items = [];

  function payload(){
    return Object.fromEntries(fields.map(k=>[k,$(k).value.trim()])).constructor === Object
      ? {...Object.fromEntries(fields.map(k=>[k,$(k).value.trim()])), defaultProfile:$('defaultProfile').checked}
      : {};
  }
  function reset(){ $('id').value=''; $('formTitle').textContent='Новый профиль'; fields.forEach(k=>$(k).value=''); $('securityProtocol').value='PLAINTEXT'; $('credentialsSource').value='NONE'; $('passwordField').value='password'; $('clientIdPrefix').value='evo-snt'; $('defaultProfile').checked=false; $('diagnostic').textContent=''; }
  function edit(p){ $('id').value=p.id; $('formTitle').textContent=`Редактирование: ${p.name}`; fields.forEach(k=>$(k).value=p[k]??''); $('defaultProfile').checked=!!p.defaultProfile; window.scrollTo({top:0,behavior:'smooth'}); }
  async function request(url, options={}){ const r=await fetch(url,{credentials:'same-origin',headers:{'Content-Type':'application/json',...(options.headers||{})},...options}); if(!r.ok){throw new Error((await r.text())||`HTTP ${r.status}`);} return r.status===204?null:r.json(); }
  async function load(){ items=await request('/api/kafka/profiles'); const body=$('profiles'); body.innerHTML=''; items.forEach(p=>{ const tr=document.createElement('tr'); tr.innerHTML=`<td>${esc(p.name)}</td><td>${esc(p.bootstrapServers)}</td><td>${esc(p.securityProtocol)}${p.saslMechanism?` / ${esc(p.saslMechanism)}`:''}</td><td>${esc(p.credentialsSource)}</td><td>${esc(p.defaultTopic||'—')}</td><td>${p.defaultProfile?'Да':'Нет'}</td><td><button type="button" data-edit="${p.id}" class="secondary">Изменить</button> <button type="button" data-default="${p.id}" class="secondary">По умолчанию</button> <button type="button" data-delete="${p.id}" class="danger">Удалить</button></td>`; body.appendChild(tr); }); }
  async function save(){ try{ const id=$('id').value; await request(id?`/api/kafka/profiles/${id}`:'/api/kafka/profiles',{method:id?'PUT':'POST',body:JSON.stringify(payload())}); EvoUI?.notify('Профиль Kafka сохранён','success'); reset(); await load(); }catch(e){EvoUI?.notify(e.message,'error');} }
  async function check(){ try{ const id=$('id').value; if(!id) throw new Error('Сначала сохраните профиль'); $('diagnostic').textContent='Проверка…'; const v=await request(`/api/kafka/profiles/${id}/check`,{method:'POST'}); $('diagnostic').textContent=JSON.stringify(v,null,2); }catch(e){$('diagnostic').textContent=e.message;EvoUI?.notify(e.message,'error');} }
  $('profiles').addEventListener('click',async e=>{ try{ const b=e.target.closest('button'); if(!b)return; if(b.dataset.edit)edit(items.find(p=>p.id===b.dataset.edit)); if(b.dataset.default){await request(`/api/kafka/profiles/${b.dataset.default}/default`,{method:'POST'});await load();} if(b.dataset.delete){if(confirm('Удалить профиль Kafka?')){await request(`/api/kafka/profiles/${b.dataset.delete}`,{method:'DELETE'});await load();}} }catch(err){EvoUI?.notify(err.message,'error');} });
  function esc(v){return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
  $('save').addEventListener('click',save); $('check').addEventListener('click',check); $('reset').addEventListener('click',reset); load().catch(e=>EvoUI?.notify(e.message,'error'));
})();
