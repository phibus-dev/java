(() => {
  const csrf = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
  const headers = {'Content-Type': 'application/json'};
  if (csrf && csrfHeader) headers[csrfHeader] = csrf;

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
    if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
    return response.status === 204 ? null : response.json();
  }

  document.getElementById('ch-test')?.addEventListener('click', async () => {
    const out = document.getElementById('ch-test-result');
    out.textContent = 'Проверка...';
    try { out.textContent = JSON.stringify(await call('/settings/clickhouse-profiles/api/test', {method:'POST', body:JSON.stringify(request())}), null, 2); }
    catch (e) { out.textContent = e.message; }
  });

  document.getElementById('ch-save')?.addEventListener('click', async () => {
    try {
      await call('/settings/clickhouse-profiles/api', {method:'POST', body:JSON.stringify(request())});
      location.reload();
    } catch (e) { alert(e.message); }
  });

  document.querySelectorAll('[data-profile-id]').forEach(card => {
    const id = card.dataset.profileId;
    card.querySelector('.ch-discover')?.addEventListener('click', async () => {
      const out = card.querySelector('.ch-discovery-result');
      out.textContent = 'Discovery...';
      try { out.textContent = JSON.stringify(await call(`/settings/clickhouse-profiles/api/${id}/discover`, {method:'POST'}), null, 2); }
      catch (e) { out.textContent = e.message; }
    });
    card.querySelector('.ch-make-default')?.addEventListener('click', async () => {
      try { await call(`/settings/clickhouse-profiles/api/${id}/default`, {method:'POST'}); location.reload(); }
      catch (e) { alert(e.message); }
    });
    card.querySelector('.ch-delete')?.addEventListener('click', async () => {
      if (!confirm('Удалить профиль ClickHouse?')) return;
      try { await call(`/settings/clickhouse-profiles/api/${id}`, {method:'DELETE'}); location.reload(); }
      catch (e) { alert(e.message); }
    });
  });
})();
