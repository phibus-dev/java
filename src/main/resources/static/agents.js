(() => {
    'use strict';

    const body = document.getElementById('agents');
    const result = document.getElementById('result');
    const refresh = document.getElementById('refresh');
    const REQUEST_TIMEOUT_MS = 10_000;
    const REFRESH_INTERVAL_MS = 10_000;
    let refreshTimer;
    let activeController;

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>"']/g, character => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;'
        })[character]);
    }

    function formatDate(value) {
        if (!value) {
            return '—';
        }
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
    }

    function renderAgent(agent) {
        const update = agent.updateRequested
            ? `Ожидается ${escapeHtml(agent.desiredVersion || 'версия не указана')}`
            : (agent.updateCompletedAt ? `Завершено ${formatDate(agent.updateCompletedAt)}` : '—');
        const memoryGiB = Number(agent.memoryBytes || 0) / 1_073_741_824;

        return `<tr>
            <td>${escapeHtml(agent.name)}</td>
            <td>${escapeHtml(agent.hostname)}<br><small>${escapeHtml(agent.address)}</small></td>
            <td>${escapeHtml(agent.version || '—')}</td>
            <td>${Number(agent.cpuCount || 0)} CPU<br>${memoryGiB.toFixed(1)} GiB</td>
            <td><strong>${escapeHtml(agent.status)}</strong></td>
            <td>${update}</td>
            <td>${formatDate(agent.lastSeenAt)}</td>
            <td>
                <button type="button" class="secondary" data-action="toggle" data-id="${escapeHtml(agent.id)}" data-enabled="${String(!agent.enabled)}">${agent.enabled ? 'Отключить' : 'Включить'}</button>
                <button type="button" class="secondary" data-action="update" data-id="${escapeHtml(agent.id)}" data-version="${escapeHtml(agent.version || '')}">Обновить</button>
                <button type="button" class="danger" data-action="revoke" data-id="${escapeHtml(agent.id)}">Отозвать identity</button>
            </td>
        </tr>`;
    }

    async function request(url, options = {}) {
        activeController?.abort();
        const controller = new AbortController();
        activeController = controller;
        const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
        try {
            const response = await fetch(url, {...options, signal: controller.signal});
            if (!response.ok) {
                throw new Error((await response.text()) || `HTTP ${response.status}`);
            }
            return response;
        } catch (error) {
            if (error.name === 'AbortError') {
                throw new Error('Превышено время ожидания ответа сервера');
            }
            throw error;
        } finally {
            window.clearTimeout(timeout);
            if (activeController === controller) {
                activeController = undefined;
            }
        }
    }

    async function load({showLoading = false} = {}) {
        if (showLoading) {
            body.innerHTML = '<tr><td colspan="8">Получение списка агентов…</td></tr>';
        }
        refresh.disabled = true;
        try {
            const response = await request('/api/agents');
            const agents = await response.json();
            body.innerHTML = Array.isArray(agents) && agents.length
                ? agents.map(renderAgent).join('')
                : '<tr><td colspan="8">Агенты не зарегистрированы</td></tr>';
            result.textContent = '';
        } catch (error) {
            body.innerHTML = `<tr><td colspan="8">Не удалось получить список агентов: ${escapeHtml(error.message)}</td></tr>`;
        } finally {
            refresh.disabled = false;
        }
    }

    async function performAction(button, url, options) {
        const buttons = body.querySelectorAll('button');
        buttons.forEach(item => { item.disabled = true; });
        button.disabled = true;
        result.textContent = 'Выполнение операции…';
        try {
            await request(url, options);
            result.textContent = 'Операция выполнена';
            await load();
        } catch (error) {
            result.textContent = error.message;
        } finally {
            buttons.forEach(item => { item.disabled = false; });
        }
    }

    body.addEventListener('click', async event => {
        const button = event.target.closest('button[data-action]');
        if (!button) {
            return;
        }

        const id = button.dataset.id;
        switch (button.dataset.action) {
            case 'toggle':
                await performAction(button, `/api/agents/${encodeURIComponent(id)}/enabled?value=${encodeURIComponent(button.dataset.enabled)}`, {method: 'POST'});
                break;
            case 'update': {
                const version = window.prompt('Желаемая версия агента', button.dataset.version || '');
                if (version?.trim()) {
                    await performAction(button, `/api/agents/${encodeURIComponent(id)}/update`, {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({desiredVersion: version.trim()})
                    });
                }
                break;
            }
            case 'revoke':
                if (window.confirm('Отозвать identity агента? Агент должен повторно зарегистрироваться.')) {
                    await performAction(button, `/api/agents/${encodeURIComponent(id)}/identity`, {method: 'DELETE'});
                }
                break;
            default:
                break;
        }
    });

    refresh.addEventListener('click', () => load({showLoading: true}));

    function startPolling() {
        window.clearInterval(refreshTimer);
        refreshTimer = window.setInterval(() => {
            if (!document.hidden) {
                load();
            }
        }, REFRESH_INTERVAL_MS);
    }

    document.addEventListener('visibilitychange', () => {
        if (!document.hidden) {
            load();
        }
    });
    window.addEventListener('beforeunload', () => {
        window.clearInterval(refreshTimer);
        activeController?.abort();
    });

    load({showLoading: true});
    startPolling();
})();
