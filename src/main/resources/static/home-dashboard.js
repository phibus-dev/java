(() => {
  const health = document.getElementById('home-health');
  const activity = document.getElementById('recent-activity');
  const build = document.getElementById('build-info');
  const searchForm = document.getElementById('home-search');
  const searchInput = document.getElementById('home-search-input');

  async function getJson(url) {
    const response = await fetch(url, {credentials: 'same-origin'});
    if (!response.ok) throw new Error(`${response.status}`);
    return response.json();
  }

  function statusCard(label, value, state, href) {
    const cls = state ? ` health-${state.toLowerCase()}` : '';
    const content = href ? `<a href="${href}">${value}</a>` : value;
    return `<div class="health-card${cls}"><span>${label}</span><strong>${content}</strong></div>`;
  }

  async function loadHealth() {
    let appState = 'UNKNOWN';
    let session = null;
    try {
      const app = await getJson('/actuator/health');
      appState = app.status || 'UNKNOWN';
    } catch (_) {
      appState = 'UNAVAILABLE';
    }
    try { session = await getJson('/api/session'); } catch (_) { /* non-fatal */ }
    const keycloak = session && session.securityEnabled ? 'ENABLED' : 'NOT CONFIGURED';
    if (build && session) build.textContent = `Версия ${session.version || 'dev'} · запуск ${new Date(session.startedAt).toLocaleString('ru-RU')}`;
    if (health) health.innerHTML = [
      statusCard('Приложение', appState, appState === 'UP' ? 'ok' : 'error', '/actuator/health'),
      statusCard('Keycloak', keycloak, keycloak === 'ENABLED' ? 'ok' : 'neutral', '/settings/keycloak'),
      statusCard('S3', 'Профили и тесты', 'neutral', '/tasks'),
      statusCard('ClickHouse', 'Профили и HA', 'neutral', '/clickhouse'),
      statusCard('Агенты', 'Открыть состояние', 'neutral', '/agents')
    ].join('');
  }

  async function loadActivity() {
    if (!activity) return;
    try {
      const response = await fetch('/history', {credentials: 'same-origin'});
      if (!response.ok) throw new Error();
      activity.innerHTML = '<div class="activity-item"><strong>История тестов доступна</strong><span>Используйте общий поиск или откройте полную историю.</span><a href="/history">Перейти →</a></div><div class="activity-item"><strong>ClickHouse history</strong><span>Результаты Load, Replicated и Failover доступны в разделе ClickHouse.</span><a href="/clickhouse">Перейти →</a></div>';
    } catch (_) {
      activity.innerHTML = '<p class="muted">История временно недоступна.</p>';
    }
  }

  if (searchForm) searchForm.addEventListener('submit', event => {
    event.preventDefault();
    const q = searchInput.value.trim();
    if (q) location.href = `/history?search=${encodeURIComponent(q)}`;
  });

  loadHealth();
  loadActivity();
})();
