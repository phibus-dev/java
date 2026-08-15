(() => {
  const csrf = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
  const headers = {'Content-Type': 'application/json'};
  if (csrf && csrfHeader) headers[csrfHeader] = csrf;
  let editingId = null;

  const value = id => document.getElementById(id)?.value ?? '';
  const request = () => ({
    name: value('ch-name'),
    endpoints: value('ch-endpoints'),
    database: value('ch-database'),
    username: value('ch-username'),
    password: value('ch-password'),
    connectionTimeoutMs: Number(value('ch-connection-timeout')),
    queryTimeoutSeconds: Number(value('ch-query-timeout')),
    defaultProfile: document.getElementById('ch-default')?.checked === true
  });

  async function call(url, options = {}) {
    const response = await fetch(url, {...options, headers: {...headers, ...(options.headers || {})}});
    const body = await response.text();
    if (!response.ok) {
      try { const problem = JSON.parse(body); throw new Error(problem.message || problem.detail || problem.error || `HTTP ${response.status}`); }
      catch (error) { if (error instanceof SyntaxError) throw new Error(body || `HTTP ${response.status}`); throw error; }
    }
    return body ? JSON.parse(body) : null;
  }

  const field = (id, value) => { const element = document.getElementById(id); if (element) element.value = value ?? ''; };
  function resetForm() {
    editingId = null;
    field('ch-name', 'default'); field('ch-database', 'default'); field('ch-endpoints', '');
    field('ch-username', 'default'); field('ch-password', ''); field('ch-connection-timeout', 5000);
    field('ch-query-timeout', 30); document.getElementById('ch-default').checked = false;
    document.getElementById('ch-form-title').textContent = 'Новый профиль';
    document.getElementById('ch-save').textContent = 'Создать профиль';
    document.getElementById('ch-cancel-edit').hidden = true;
    document.getElementById('ch-test-result').textContent = '';
  }

  function editForm(profile) {
    editingId = profile.id;
    field('ch-name', profile.name); field('ch-database', profile.database);
    field('ch-endpoints', (profile.endpoints || []).join('\n')); field('ch-username', profile.username);
    field('ch-password', ''); field('ch-connection-timeout', profile.connectionTimeoutMs);
    field('ch-query-timeout', profile.queryTimeoutSeconds);
    document.getElementById('ch-default').checked = profile.defaultProfile === true;
    document.getElementById('ch-form-title').textContent = `Редактирование профиля «${profile.name}»`;
    document.getElementById('ch-save').textContent = 'Сохранить изменения';
    document.getElementById('ch-cancel-edit').hidden = false;
    document.getElementById('ch-test-result').textContent = 'Оставьте пароль пустым, чтобы сохранить текущий.';
    document.getElementById('ch-form-title').scrollIntoView({behavior: 'smooth', block: 'start'});
  }

  document.getElementById('ch-test')?.addEventListener('click', async () => {
    const out = document.getElementById('ch-test-result');
    out.textContent = 'Проверка...';
    try { out.textContent = JSON.stringify(await call('/settings/clickhouse-profiles/api/test', {method:'POST', body:JSON.stringify(request())}), null, 2); }
    catch (e) { out.textContent = e.message; }
  });

  document.getElementById('ch-save')?.addEventListener('click', async () => {
    try {
      const url = editingId ? `/settings/clickhouse-profiles/api/${editingId}` : '/settings/clickhouse-profiles/api';
      await call(url, {method: editingId ? 'PUT' : 'POST', body:JSON.stringify(request())});
      location.reload();
    } catch (e) { document.getElementById('ch-test-result').textContent = e.message; }
  });
  document.getElementById('ch-cancel-edit')?.addEventListener('click', resetForm);

  document.querySelectorAll('[data-profile-id]').forEach(card => {
    const id = card.dataset.profileId;
    card.querySelector('.ch-edit')?.addEventListener('click', async () => {
      try { editForm(await call(`/settings/clickhouse-profiles/api/${id}`)); }
      catch (e) { card.querySelector('.ch-discovery-result').textContent = e.message; }
    });
    card.querySelector('.ch-discover')?.addEventListener('click', async () => {
      const out = card.querySelector('.ch-discovery-result');
      out.textContent = 'Discovery...';
      try { out.textContent = JSON.stringify(await call(`/settings/clickhouse-profiles/api/${id}/discover`, {method:'POST'}), null, 2); }
      catch (e) { out.textContent = e.message; }
    });
    card.querySelector('.ch-make-default')?.addEventListener('click', async () => {
      try { await call(`/settings/clickhouse-profiles/api/${id}/default`, {method:'POST'}); location.reload(); }
      catch (e) { card.querySelector('.ch-discovery-result').textContent = e.message; }
    });
    card.querySelector('.ch-delete')?.addEventListener('click', async () => {
      if (!confirm('Удалить профиль ClickHouse?')) return;
      try { await call(`/settings/clickhouse-profiles/api/${id}`, {method:'DELETE'}); location.reload(); }
      catch (e) { card.querySelector('.ch-discovery-result').textContent = e.message; }
    });
  });
})();
