(() => {
  const byId = id => document.getElementById(id);
  const status = byId('status');
  const password = id => byId(id).value;

  async function responseJson(response) {
    const text = await response.text();
    if (!response.ok) throw new Error(text || `HTTP ${response.status}`);
    return text ? JSON.parse(text) : {};
  }

  async function exportConfig() {
    const encrypted = byId('encrypted').checked;
    const includeSecrets = byId('secrets').checked;
    const exportPassword = password('exportPassword');
    if (includeSecrets && !encrypted) throw new Error('Экспорт секретов возможен только с шифрованием');
    if (encrypted && exportPassword.length < 12) throw new Error('Пароль должен содержать не менее 12 символов');
    const parameters = new URLSearchParams({encrypted, includeSecrets});
    if (encrypted) parameters.set('password', exportPassword);
    const response = await fetch(`/api/config/export?${parameters}`);
    if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
    const url = URL.createObjectURL(await response.blob());
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = (response.headers.get('Content-Disposition') || '').includes('.evos3')
      ? 'evo-snt-config.evos3' : 'evo-snt-config.json';
    anchor.click();
    URL.revokeObjectURL(url);
  }

  function formData() {
    const file = byId('file').files[0];
    if (!file) throw new Error('Выберите файл');
    const data = new FormData();
    data.append('file', file);
    const importPassword = password('importPassword');
    if (importPassword) data.append('password', importPassword);
    return data;
  }

  async function run(action) {
    status.textContent = 'Выполнение…';
    try {
      const result = await action();
      if (result !== undefined) status.textContent = typeof result === 'string' ? result : JSON.stringify(result, null, 2);
    } catch (error) {
      status.textContent = error.message;
      window.EvoUI?.notify(error.message, 'error');
    }
  }

  byId('export-config').addEventListener('click', () => run(async () => {
    await exportConfig();
    window.EvoUI?.notify('Экспорт конфигурации сформирован', 'success');
    return 'Файл экспорта сформирован.';
  }));
  byId('validate-config').addEventListener('click', () => run(async () => responseJson(await fetch('/api/config/validate', {method: 'POST', body: formData()}))));
  byId('import-config').addEventListener('click', () => {
    if (!confirm('Импортировать конфигурацию и заменить текущие настройки?')) return;
    run(async () => responseJson(await fetch('/api/config/import', {method: 'POST', body: formData()})));
  });
})();
