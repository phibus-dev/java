(() => {
  const byId = id => document.getElementById(id);
  const rows = byId('rows');
  const message = byId('message');
  const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

  async function request(url, options) {
    const response = await fetch(url, options);
    if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
    if (response.status === 204) return null;
    return response.json();
  }

  async function load() {
    try {
      const data = await request('/api/schedules');
      rows.innerHTML = data.length ? data.map(schedule => `
        <tr>
          <td>${escapeHtml(schedule.name)}</td>
          <td><code>${escapeHtml(schedule.cronExpression)}</code></td>
          <td>${escapeHtml(schedule.timeZone)}</td>
          <td>${schedule.enabled ? 'ENABLED' : 'DISABLED'}</td>
          <td>${escapeHtml(schedule.nextRunAt || '—')}</td>
          <td>${escapeHtml(schedule.lastRunAt || '—')}${schedule.lastError ? `<div class="error">${escapeHtml(schedule.lastError)}</div>` : ''}</td>
          <td>
            <button type="button" data-action="run" data-id="${schedule.id}">Запустить</button>
            <button type="button" class="secondary" data-action="toggle" data-id="${schedule.id}" data-enabled="${!schedule.enabled}">${schedule.enabled ? 'Отключить' : 'Включить'}</button>
            <button type="button" class="danger" data-action="delete" data-id="${schedule.id}">Удалить</button>
          </td>
        </tr>`).join('') : '<tr><td colspan="7">Расписания не созданы</td></tr>';
    } catch (error) {
      rows.innerHTML = `<tr><td colspan="7">Не удалось загрузить расписания: ${escapeHtml(error.message)}</td></tr>`;
    }
  }

  async function createSchedule() {
    try {
      const payload = {
        name: byId('name').value,
        enabled: byId('enabled').checked,
        cronExpression: byId('cron').value,
        timeZone: byId('zone').value,
        testRequest: JSON.parse(byId('request').value)
      };
      await request('/api/schedules', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload)});
      message.textContent = 'Расписание создано';
      await load();
    } catch (error) {
      message.textContent = error.message;
    }
  }

  rows.addEventListener('click', async event => {
    const button = event.target.closest('button[data-action]');
    if (!button) return;
    const id = button.dataset.id;
    button.disabled = true;
    try {
      if (button.dataset.action === 'run') await request(`/api/schedules/${id}/run`, {method:'POST'});
      if (button.dataset.action === 'toggle') await request(`/api/schedules/${id}/enabled`, {method:'PATCH', headers:{'Content-Type':'application/json'}, body:JSON.stringify({enabled:button.dataset.enabled === 'true'})});
      if (button.dataset.action === 'delete') await request(`/api/schedules/${id}`, {method:'DELETE'});
      await load();
    } catch (error) {
      message.textContent = error.message;
    } finally {
      button.disabled = false;
    }
  });

  byId('create-schedule').addEventListener('click', createSchedule);
  load();
  const timer = window.setInterval(load, 5000);
  window.addEventListener('pagehide', () => window.clearInterval(timer), {once:true});
})();
