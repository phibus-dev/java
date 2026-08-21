(() => {
  function csrfHeaders() {
    const token = document.querySelector('meta[name="_csrf"]')?.content || '';
    const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-XSRF-TOKEN';
    return token ? {[header]: token} : {};
  }

  const jsonHeaders = () => ({'Content-Type':'application/json', ...csrfHeaders()});
  const value = id => document.getElementById(id).value;
  const checked = id => document.getElementById(id).checked;
  const esc = s => String(s ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));

  function message(text, type='info') {
    const host = document.getElementById('message');
    host.textContent = text;
    host.classList.toggle('error', type === 'error');
    if (window.EvoUI) window.EvoUI.notify(text, type);
  }

  async function call(url, method, body) {
    const response = await fetch(url, {
      method,
      credentials:'same-origin',
      headers: jsonHeaders(),
      body: method === 'DELETE' ? undefined : JSON.stringify(body)
    });
    if (!response.ok) {
      const text = await response.text();
      message(text || `HTTP ${response.status}`, 'error');
      throw new Error(text);
    }
    message('Изменения сохранены', 'success');
    return response;
  }

  async function load() {
    const tbody = document.getElementById('profiles');
    try {
      const response = await fetch('/api/s3-profiles', {credentials:'same-origin'});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const profiles = await response.json();
      if (!profiles.length) {
        tbody.innerHTML = '<tr><td colspan="7" class="muted">Сохранённых профилей нет.</td></tr>';
        return;
      }
      tbody.innerHTML = profiles.map(p => `<tr class="${p.defaultProfile ? 'default' : ''}" data-id="${esc(p.id)}">
        <td><strong>${esc(p.name)}</strong>${p.defaultProfile ? ' <span class="badge">По умолчанию</span>' : ''}</td>
        <td>${esc(p.endpoint)}</td><td>${esc(p.region)}</td><td>${esc(p.bucket || '—')}</td>
        <td>${esc(p.credentialsSource)}</td><td>${esc(p.vaultSecretPath || '—')}</td>
        <td><div class="actions compact">
          ${p.defaultProfile ? '' : '<button type="button" class="secondary make-default">Сделать основным</button>'}
          <button type="button" class="secondary clone-profile">Клонировать</button>
          ${p.defaultProfile ? '' : '<button type="button" class="danger delete-profile">Удалить</button>'}
        </div></td></tr>`).join('');
    } catch (error) {
      tbody.innerHTML = `<tr><td colspan="7" class="error">Не удалось загрузить профили: ${esc(error.message)}</td></tr>`;
    }
  }

  async function createProfile() {
    const body = {
      name:value('name'), endpoint:value('endpoint'), region:value('region'), bucket:value('bucket'),
      pathStyleAccess:checked('pathStyleAccess'), credentialsSource:value('credentialsSource'),
      vaultSecretPath:value('vaultSecretPath'), accessKeyField:'accessKey', secretKeyField:'secretKey',
      sessionTokenField:'sessionToken', caCertificatePath:value('caCertificatePath'),
      defaultProfile:checked('defaultProfile')
    };
    await call('/api/s3-profiles', 'POST', body);
    await load();
  }

  document.getElementById('create-profile').addEventListener('click', () => createProfile().catch(()=>{}));
  document.getElementById('reload-profiles').addEventListener('click', load);
  document.getElementById('profiles').addEventListener('click', async event => {
    const row = event.target.closest('tr[data-id]'); if (!row) return;
    const id = row.dataset.id;
    try {
      if (event.target.closest('.make-default')) {
        await call(`/api/s3-profiles/${id}/default`, 'POST', {});
      } else if (event.target.closest('.clone-profile')) {
        const currentName = row.querySelector('td strong')?.textContent || 'profile';
        const cloneName = prompt('Имя копии', `${currentName} copy`);
        if (!cloneName) return;
        await call(`/api/s3-profiles/${id}/clone`, 'POST', {name:cloneName});
      } else if (event.target.closest('.delete-profile')) {
        if (!confirm('Удалить профиль?')) return;
        await call(`/api/s3-profiles/${id}`, 'DELETE');
      } else return;
      await load();
    } catch (_) { /* message already displayed */ }
  });

  load();
})();
