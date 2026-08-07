(() => {
    'use strict';

    const agents = document.getElementById('agent-list');
    const runs = document.getElementById('runs');
    const message = document.getElementById('message');
    const startButton = document.getElementById('start');
    const refreshButton = document.getElementById('refresh');
    const REQUEST_TIMEOUT_MS = 10_000;
    const REFRESH_INTERVAL_MS = 5_000;
    let refreshTimer;

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>"']/g, character => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;'
        })[character]);
    }

    async function request(url, options = {}) {
        const controller = new AbortController();
        const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
        try {
            const response = await fetch(url, {...options, signal: controller.signal});
            if (!response.ok) {
                throw new Error((await response.text()) || `HTTP ${response.status}`);
            }
            return response.status === 204 ? null : response.json();
        } catch (error) {
            if (error.name === 'AbortError') {
                throw new Error('Превышено время ожидания ответа сервера');
            }
            throw error;
        } finally {
            window.clearTimeout(timeout);
        }
    }

    function renderAgents(list) {
        const available = Array.isArray(list)
            ? list.filter(agent => agent.status !== 'OFFLINE' && agent.status !== 'DISABLED')
            : [];
        agents.innerHTML = available.length
            ? available.map(agent => `<label class="check"><input type="checkbox" name="agent" value="${escapeHtml(agent.id)}"> ${escapeHtml(agent.name)} — ${escapeHtml(agent.status)}</label>`).join('')
            : 'Нет доступных агентов';
    }

    async function loadAgents({showLoading = false} = {}) {
        if (showLoading) agents.textContent = 'Получение списка агентов…';
        try {
            renderAgents(await request('/api/agents'));
        } catch (error) {
            agents.textContent = `Не удалось получить список агентов: ${error.message}`;
        }
    }

    function agentRows(agentRuns) {
        const items = Array.isArray(agentRuns) ? agentRuns : [];
        return items.map(agent => `<tr>
            <td>${escapeHtml(agent.agentId)}</td>
            <td>${escapeHtml(agent.status)}</td>
            <td>${Number(agent.completedOperations || 0)}</td>
            <td>${Number(agent.throughputMiBps || 0).toFixed(2)}</td>
            <td>${Number(agent.p95LatencyMs || 0).toFixed(1)}</td>
            <td>${Number(agent.errors || 0)}</td>
        </tr>`).join('');
    }

    function renderRun(run) {
        const agentRuns = Array.isArray(run.agents) ? run.agents : [];
        return `<article class="panel">
            <div class="title-row"><strong>${escapeHtml(run.name)}</strong><span class="status">${escapeHtml(run.status)}</span></div>
            <div class="metrics">
                <article><span>Агенты</span><strong>${agentRuns.length}</strong></article>
                <article><span>OPS</span><strong>${Number(run.operationsPerSecond || 0).toFixed(2)}</strong></article>
                <article><span>Throughput</span><strong>${Number(run.throughputMiBps || 0).toFixed(2)} MiB/s</strong></article>
                <article><span>p95</span><strong>${Number(run.p95LatencyMs || 0).toFixed(1)} ms</strong></article>
                <article><span>Ошибки</span><strong>${Number(run.errors || 0)}</strong></article>
            </div>
            <div class="table-wrap"><table>
                <thead><tr><th>Agent ID</th><th>Статус</th><th>Операции</th><th>MiB/s</th><th>p95</th><th>Ошибки</th></tr></thead>
                <tbody>${agentRows(agentRuns)}</tbody>
            </table></div>
        </article>`;
    }

    async function loadRuns({showLoading = false} = {}) {
        if (showLoading) runs.textContent = 'Получение истории распределённых запусков…';
        refreshButton.disabled = true;
        try {
            const list = await request('/api/distributed-tests');
            runs.innerHTML = Array.isArray(list) && list.length
                ? list.map(renderRun).join('')
                : 'Распределённые запуски отсутствуют';
        } catch (error) {
            runs.textContent = `Не удалось получить историю распределённых запусков: ${error.message}`;
        } finally {
            refreshButton.disabled = false;
        }
    }

    async function startDistributedTest() {
        startButton.disabled = true;
        message.textContent = 'Создание распределённого теста…';
        try {
            const ids = [...document.querySelectorAll('input[name=agent]:checked')].map(input => input.value);
            if (!ids.length) throw new Error('Выберите хотя бы одного агента');
            const payload = {
                name: document.getElementById('name').value,
                agentIds: ids,
                testRequest: JSON.parse(document.getElementById('request').value)
            };
            await request('/api/distributed-tests', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(payload)
            });
            message.textContent = 'Распределённый тест создан';
            await Promise.all([loadRuns(), loadAgents()]);
        } catch (error) {
            message.textContent = error.message;
        } finally {
            startButton.disabled = false;
        }
    }

    startButton.addEventListener('click', startDistributedTest);
    refreshButton.addEventListener('click', () => loadRuns({showLoading: true}));
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden) {
            loadRuns();
            loadAgents();
        }
    });
    window.addEventListener('pagehide', () => window.clearInterval(refreshTimer));

    loadAgents({showLoading: true});
    loadRuns({showLoading: true});
    refreshTimer = window.setInterval(() => {
        if (!document.hidden) loadRuns();
    }, REFRESH_INTERVAL_MS);
})();
