(() => {
  const byId = (id) => document.getElementById(id);
  const status = byId('status');
  const testButton = byId('test-keycloak');
  const saveButton = byId('save-keycloak');

  function payload() {
    return {
      enabled: byId('enabled').checked,
      issuerUri: byId('issuerUri').value,
      clientId: byId('clientId').value,
      clientSecret: byId('clientSecret').value,
      scopes: byId('scopes').value,
      roleSource: byId('roleSource').value,
      adminRole: byId('adminRole').value,
      operatorRole: byId('operatorRole').value,
      viewerRole: byId('viewerRole').value
    };
  }

  function csrfToken() {
    const meta = document.querySelector('meta[name="_csrf"]');
    if (meta?.content) return meta.content;
    const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : '';
  }

  function csrfHeader() {
    const meta = document.querySelector('meta[name="_csrf_header"]');
    return meta?.content || 'X-XSRF-TOKEN';
  }

  async function call(url) {
    const headers = {'Content-Type': 'application/json'};
    const token = csrfToken();
    if (token) headers[csrfHeader()] = token;

    const response = await fetch(url, {
      method: 'POST',
      headers,
      credentials: 'same-origin',
      body: JSON.stringify(payload())
    });
    const text = await response.text();
    if (!response.ok) throw new Error(text || `HTTP ${response.status}`);
    return text ? JSON.parse(text) : {};
  }

  async function testConnection() {
    status.textContent = 'Проверка...';
    testButton.disabled = true;
    try {
      const result = await call('/api/settings/keycloak/test');
      status.textContent = `${result.success ? 'OK: ' : 'Ошибка: '}${result.message || ''}${result.latencyMs == null ? '' : ` (${result.latencyMs} мс)`}`;
    } catch (error) {
      status.textContent = error.message || String(error);
    } finally {
      testButton.disabled = false;
    }
  }

  async function saveSettings() {
    status.textContent = 'Сохранение...';
    saveButton.disabled = true;
    try {
      const result = await call('/api/settings/keycloak');
      status.textContent = result.message || 'Настройки сохранены';
      byId('clientSecret').value = '';
    } catch (error) {
      status.textContent = error.message || String(error);
    } finally {
      saveButton.disabled = false;
    }
  }

  testButton?.addEventListener('click', testConnection);
  saveButton?.addEventListener('click', saveSettings);
})();
