const components = document.getElementById('health-components');
const refreshButton = document.getElementById('refresh-health');
const errorBox = document.getElementById('health-error');
let timer;

function text(id, value) {
    document.getElementById(id).textContent = value;
}

function formatDuration(milliseconds) {
    const seconds = Math.floor(milliseconds / 1000);
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return `${days} д ${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`;
}

function statusClass(status) {
    if (status === 'UP' || status === 'READY') return 'health-ok';
    if (status === 'NOT_CONFIGURED') return 'health-warn';
    return 'health-error';
}

function card(component) {
    const article = document.createElement('article');
    article.className = `panel health-card ${statusClass(component.status)}`;

    const heading = document.createElement('div');
    heading.className = 'title-row';
    const title = document.createElement('h2');
    title.textContent = component.name;
    const status = document.createElement('span');
    status.className = 'status';
    status.textContent = component.status;
    heading.append(title, status);

    const target = document.createElement('p');
    const targetLabel = document.createElement('strong');
    targetLabel.textContent = 'Адрес: ';
    target.append(targetLabel, document.createTextNode(component.target || '—'));

    const details = document.createElement('p');
    details.className = 'muted';
    details.textContent = component.details || '';
    article.append(heading, target, details);
    return article;
}

async function loadHealth() {
    refreshButton.disabled = true;
    errorBox.hidden = true;
    try {
        const response = await fetch('/api/health/overview', {credentials: 'same-origin'});
        if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
        const health = await response.json();
        text('application-state', health.applicationState);
        text('checked-at', new Date(health.checkedAt).toLocaleString());
        text('uptime', formatDuration(health.uptimeMillis));
        components.replaceChildren(...health.components.map(card));
    } catch (error) {
        errorBox.textContent = `Не удалось получить состояние: ${error.message}`;
        errorBox.hidden = false;
    } finally {
        refreshButton.disabled = false;
    }
}

refreshButton.addEventListener('click', loadHealth);
document.addEventListener('visibilitychange', () => {
    if (!document.hidden) loadHealth();
});
window.addEventListener('pagehide', () => clearInterval(timer));
loadHealth();
timer = setInterval(() => {
    if (!document.hidden) loadHealth();
}, 30000);
