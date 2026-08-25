(() => {
  const $ = id => document.getElementById(id);
  const fields = ['name','bootstrapServers','securityProtocol','saslMechanism','username','credentialsSource','vaultSecretPath','passwordField','passwordEnv','password','caCertificatePath','defaultTopic','clientIdPrefix','sessionTimeoutMs'];
  let items = [];
  let selectedProfileId = '';

  function isSasl(){ return ($('securityProtocol').value || '').startsWith('SASL_'); }
  function normalizedSource(){
    const raw = ($('credentialsSource').value || 'PLAIN').toUpperCase();
    return raw === 'PROFILE' ? 'PLAIN' : raw;
  }
  function setVisible(id, visible){ const el=$(id); if(el) el.hidden=!visible; }
  function refreshCredentialFields(){
    const sasl=isSasl();
    const source=normalizedSource();
    setVisible('saslMechanismWrap', sasl);
    setVisible('usernameWrap', sasl);
    setVisible('credentialsSourceWrap', sasl);
    setVisible('plainPasswordWrap', sasl && source==='PLAIN');
    setVisible('passwordEnvWrap', sasl && source==='ENVIRONMENT');
    setVisible('vaultSecretPathWrap', sasl && source==='VAULT');
    setVisible('passwordFieldWrap', sasl && source==='VAULT');
  }
  function payload(){
    const data = Object.fromEntries(fields.map(k=>[k,$(k)?.value?.trim?.() ?? '']));
    if(!isSasl()){
      data.saslMechanism=''; data.username=''; data.credentialsSource='NONE';
      data.password=''; data.passwordEnv=''; data.vaultSecretPath='';
    } else {
      data.credentialsSource=normalizedSource();
      if(data.credentialsSource!=='PLAIN') data.password='';
      if(data.credentialsSource!=='ENVIRONMENT') data.passwordEnv='';
      if(data.credentialsSource!=='VAULT'){ data.vaultSecretPath=''; data.passwordField='password'; }
    }
    return {...data, sessionTimeoutMs:Number(data.sessionTimeoutMs), customProperties:readCustomProperties(), defaultProfile:$('defaultProfile').checked};
  }
  function addCustomProperty(key='',value=''){
    const row=document.createElement('div'); row.className='grid two kafka-property-row';
    row.innerHTML=`<label>Параметр<input class="kafka-property-key" placeholder="fetch.min.bytes" value="${esc(key)}"></label><label>Значение<div class="inline-field"><input class="kafka-property-value" placeholder="1" value="${esc(value)}"><button type="button" class="secondary remove-kafka-property">Удалить</button></div></label>`;
    $('customProperties').appendChild(row);
  }
  function renderCustomProperties(values={}){
    $('customProperties').innerHTML='';
    Object.entries(values||{}).forEach(([key,value])=>addCustomProperty(key,value));
  }
  function readCustomProperties(){
    const result={};
    document.querySelectorAll('.kafka-property-row').forEach(row=>{
      const key=row.querySelector('.kafka-property-key').value.trim();
      const value=row.querySelector('.kafka-property-value').value.trim();
      if(!key&&!value)return;
      if(!key)throw new Error('Укажите название дополнительного параметра Kafka');
      if(Object.prototype.hasOwnProperty.call(result,key))throw new Error(`Параметр Kafka ${key} указан несколько раз`);
      result[key]=value;
    });
    return result;
  }
  function reset(){
    $('id').value=''; selectedProfileId=''; $('formTitle').textContent='Новый профиль';
    fields.forEach(k=>{ if($(k)) $(k).value=''; });
    $('securityProtocol').value='PLAINTEXT'; $('credentialsSource').value='PLAIN'; $('passwordField').value='password'; $('clientIdPrefix').value='evo-snt'; $('sessionTimeoutMs').value='10000'; renderCustomProperties(); $('defaultProfile').checked=false; $('diagnostic').textContent='';
    refreshCredentialFields();
  }
  function edit(p){
    if(!p)return;
    $('id').value=p.id; selectedProfileId=p.id; $('formTitle').textContent=`Редактирование: ${p.name}`;
    fields.forEach(k=>{ if($(k) && k!=='password') $(k).value=p[k]??''; });
    $('password').value='';
    if((p.credentialsSource||'').toUpperCase()==='PROFILE') $('credentialsSource').value='PLAIN';
    if(!isSasl()) $('credentialsSource').value='PLAIN';
    renderCustomProperties(p.customProperties);
    $('defaultProfile').checked=!!p.defaultProfile;
    refreshCredentialFields();
    window.scrollTo({top:0,behavior:'smooth'});
  }
  function csrfHeaders(){
    const token=document.querySelector('meta[name="_csrf"]')?.content;
    const header=document.querySelector('meta[name="_csrf_header"]')?.content;
    return token&&header?{[header]:token}:{};
  }
  async function request(url, options={}){
    const method=(options.method||'GET').toUpperCase();
    const headers={'Content-Type':'application/json',...(method==='GET'||method==='HEAD'?{}:csrfHeaders()),...(options.headers||{})};
    const r=await fetch(url,{credentials:'same-origin',...options,headers});
    if(!r.ok){throw new Error((await r.text())||`HTTP ${r.status}`);}
    return r.status===204?null:r.json();
  }
  function validateForm(){
    const sessionTimeout=Number($('sessionTimeoutMs').value);
    if(!Number.isInteger(sessionTimeout)||sessionTimeout<1000||sessionTimeout>300000) throw new Error('session.timeout.ms должен быть от 1000 до 300000 мс');
    if(!isSasl()) return;
    if(!$('saslMechanism').value) throw new Error('Выберите SASL mechanism');
    if(!$('username').value.trim()) throw new Error('Укажите Username для SASL');
    const source=normalizedSource();
    if(source==='PLAIN' && !$('password').value && !$('id').value) throw new Error('Укажите пароль пользователя Kafka');
    if(source==='ENVIRONMENT' && !$('passwordEnv').value.trim()) throw new Error('Укажите имя переменной окружения с паролем Kafka');
    if(source==='VAULT' && !$('vaultSecretPath').value.trim()) throw new Error('Укажите Vault secret path');
  }
  async function load(){
    items=await request('/api/kafka/profiles'); const body=$('profiles'); body.innerHTML='';
    items.forEach(p=>{ const tr=document.createElement('tr'); const source=(p.credentialsSource==='PROFILE'?'PLAIN':p.credentialsSource); tr.innerHTML=`<td>${esc(p.name)}</td><td>${esc(p.bootstrapServers)}</td><td>${esc(p.securityProtocol)}${p.saslMechanism?` / ${esc(p.saslMechanism)}`:''}</td><td>${esc(source)}</td><td>${esc(p.defaultTopic||'—')}</td><td>${esc(p.sessionTimeoutMs)} мс</td><td>${p.defaultProfile?'Да':'Нет'}</td><td><button type="button" data-edit="${p.id}" class="secondary">Изменить</button> <button type="button" data-default="${p.id}" class="secondary">По умолчанию</button> <button type="button" data-delete="${p.id}" class="danger">Удалить</button></td>`; body.appendChild(tr); });
  }
  async function save(){
    try{ validateForm(); const id=$('id').value; const saved=await request(id?`/api/kafka/profiles/${id}`:'/api/kafka/profiles',{method:id?'PUT':'POST',body:JSON.stringify(payload())}); selectedProfileId=saved?.id||id||selectedProfileId; EvoUI?.notify('Профиль Kafka сохранён','success'); await load(); const savedProfile=items.find(p=>p.id===selectedProfileId); if(savedProfile){edit(savedProfile);} }
    catch(e){EvoUI?.notify(e.message,'error');}
  }
  async function check(){
    try{ validateForm(); const id=$('id').value||selectedProfileId; if(!id) throw new Error('Выберите сохранённый профиль кнопкой «Изменить» или сохраните новый профиль'); $('diagnostic').textContent='Проверка…'; const v=await request(`/api/kafka/profiles/${id}/check`,{method:'POST'}); $('diagnostic').textContent=JSON.stringify(v,null,2); }
    catch(e){$('diagnostic').textContent=e.message;EvoUI?.notify(e.message,'error');}
  }
  $('profiles').addEventListener('click',async e=>{ try{ const b=e.target.closest('button'); if(!b)return; if(b.dataset.edit)edit(items.find(p=>p.id===b.dataset.edit)); if(b.dataset.default){await request(`/api/kafka/profiles/${b.dataset.default}/default`,{method:'POST'});await load();} if(b.dataset.delete){if(confirm('Удалить профиль Kafka?')){await request(`/api/kafka/profiles/${b.dataset.delete}`,{method:'DELETE'});if(selectedProfileId===b.dataset.delete)reset();await load();}} }catch(err){EvoUI?.notify(err.message,'error');} });
  function esc(v){return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
  $('securityProtocol').addEventListener('change',refreshCredentialFields);
  $('credentialsSource').addEventListener('change',refreshCredentialFields);
  $('addCustomProperty').addEventListener('click',()=>addCustomProperty());
  $('customProperties').addEventListener('click',e=>{const button=e.target.closest('.remove-kafka-property');if(button)button.closest('.kafka-property-row').remove();});
  $('save').addEventListener('click',save); $('check').addEventListener('click',check); $('reset').addEventListener('click',reset);
  refreshCredentialFields(); load().catch(e=>EvoUI?.notify(e.message,'error'));
})();
