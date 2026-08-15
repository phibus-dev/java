(() => {
    'use strict';

    const agents = document.getElementById('agent-list');
    const runs = document.getElementById('runs');
    const message = document.getElementById('message');
    const startButton = document.getElementById('start');
    const refreshButton = document.getElementById('refresh');
    const engine = document.getElementById('engine');
    const requestEditor = document.getElementById('request');
    const endpointMapField = document.getElementById('endpoint-map-field');
    const endpointMap = document.getElementById('endpoint-map');
    const engineHelp = document.getElementById('engine-help');
    const name = document.getElementById('name');
    const REQUEST_TIMEOUT_MS = 10_000;
    const REFRESH_INTERVAL_MS = 5_000;
    let refreshTimer;
    let knownAgents = [];

    const templates = {
        S3: {
            name: 'Distributed S3 test',
            request: {
                endpoint: 'http://localhost:9000', bucket: 'test', region: 'us-east-1', accessKey: null, secretKey: null,
                pathStyleAccess: true, objectKey: 'distributed-test/object.bin', objectSizeMiB: 64, partSizeMiB: 16,
                parallelism: 4, objectCount: 1, deleteAfterTest: true, operation: 'UPLOAD', executionMode: 'TIME_DURATION',
                durationSeconds: 60, warmupSeconds: 5, workloadProfile: 'CUSTOM', workloadWeights: {},
                targetOperationsPerSecond: 0, operationThreads: {}
            }
        },
        CLICKHOUSE: {
            name: 'Distributed ClickHouse test',
            request: {
                profileId: '00000000-0000-0000-0000-000000000000', endpoint: '', table: 'evo_snt_perf_load', operation: 'INSERT',
                concurrency: 8, batchSize: 10000, rowCount: 1000000, durationSeconds: 0, warmupSeconds: 0,
                payloadBytes: 128, autoCreateTable: true
            }
        }
    };

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>"']/g, character => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[character]);
    }

    async function request(url, options = {}) {
        const controller = new AbortController();
        const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
        try {
            const response = await fetch(url, {...options, signal: controller.signal});
            if (!response.ok) throw new Error((await response.text()) || `HTTP ${response.status}`);
            return response.status === 204 ? null : response.json();
        } catch (error) {
            if (error.name === 'AbortError') throw new Error('Превышено время ожидания ответа сервера');
            throw error;
        } finally { window.clearTimeout(timeout); }
    }

    function capabilities(agent) {
        if (Array.isArray(agent.capabilities)) return agent.capabilities.map(String);
        return String(agent.capabilities || '').split(',').map(v => v.trim()).filter(Boolean);
    }

    function renderAgents() {
        const required = engine.value;
        const available = knownAgents.filter(agent => agent.status !== 'OFFLINE' && agent.status !== 'DISABLED' && capabilities(agent).includes(required));
        agents.innerHTML = available.length ? available.map(agent => `<label class="check"><input type="checkbox" name="agent" value="${escapeHtml(agent.id)}"> <strong>${escapeHtml(agent.name)}</strong> — ${escapeHtml(agent.status)} <span class="muted">[${escapeHtml(capabilities(agent).join(', '))}]</span></label>`).join('') : `Нет доступных агентов с capability ${required}`;
    }

    async function loadAgents({showLoading = false} = {}) {
        if (showLoading) agents.textContent = 'Получение списка агентов…';
        try { knownAgents = await request('/api/agents'); renderAgents(); }
        catch (error) { agents.textContent = `Не удалось получить список агентов: ${error.message}`; }
    }

    function applyEngine() {
        const type = engine.value;
        name.value = templates[type].name;
        requestEditor.value = JSON.stringify(templates[type].request, null, 2);
        endpointMapField.hidden = type !== 'CLICKHOUSE';
        engineHelp.textContent = type === 'CLICKHOUSE'
            ? 'Показываются только агенты с capability CLICKHOUSE. profileId должен ссылаться на профиль Coordinator; endpoint можно оставить пустым для первого endpoint профиля.'
            : 'Показываются только агенты с capability S3.';
        renderAgents();
    }

    function agentRows(agentRuns, type) {
        const items = Array.isArray(agentRuns) ? agentRuns : [];
        return items.map(agent => `<tr><td>${escapeHtml(agent.agentId)}</td><td>${escapeHtml(agent.status)}</td><td>${Number(agent.completedOperations || 0)}</td><td>${type === 'CLICKHOUSE' ? Number(agent.rowsProcessed || 0) : '—'}</td><td>${Number(agent.throughputMiBps || 0).toFixed(2)}</td><td>${Number(agent.p95LatencyMs || 0).toFixed(1)}</td><td>${Number(agent.errors || 0)}</td></tr>`).join('');
    }

    function renderRun(run) {
        const agentRuns = Array.isArray(run.agents) ? run.agents : [];
        const type = String(run.testType || 'S3');
        return `<article class="panel"><div class="title-row"><strong>${escapeHtml(run.name)}</strong><span><span class="badge">${escapeHtml(type)}</span> <span class="status">${escapeHtml(run.status)}</span></span></div><div class="metrics"><article><span>Агенты</span><strong>${agentRuns.length}</strong></article><article><span>${type === 'CLICKHOUSE' ? 'Rows/s' : 'OPS'}</span><strong>${Number(type === 'CLICKHOUSE' ? run.rowsPerSecond : run.operationsPerSecond || 0).toFixed(2)}</strong></article><article><span>Throughput</span><strong>${Number(run.throughputMiBps || 0).toFixed(2)} MiB/s</strong></article><article><span>p95</span><strong>${Number(run.p95LatencyMs || 0).toFixed(1)} ms</strong></article><article><span>Ошибки</span><strong>${Number(run.errors || 0)}</strong></article></div><div class="table-wrap"><table><thead><tr><th>Agent ID</th><th>Статус</th><th>Операции</th><th>Rows</th><th>MiB/s</th><th>p95</th><th>Ошибки</th></tr></thead><tbody>${agentRows(agentRuns, type)}</tbody></table></div></article>`;
    }

    async function loadRuns({showLoading = false} = {}) {
        if (showLoading) runs.textContent = 'Получение распределённых запусков…';
        refreshButton.disabled = true;
        try {
            const list = await request('/api/distributed-tests');
            runs.innerHTML = Array.isArray(list) && list.length ? list.map(renderRun).join('') : 'Распределённые запуски отсутствуют';
        } catch (error) { runs.textContent = `Не удалось получить распределённые запуски: ${error.message}`; }
        finally { refreshButton.disabled = false; }
    }

    async function startDistributedTest() {
        startButton.disabled = true;
        message.textContent = 'Создание распределённого теста…';
        try {
            const ids = [...document.querySelectorAll('input[name=agent]:checked')].map(input => input.value);
            if (!ids.length) throw new Error('Выберите хотя бы одного агента');
            const testRequest = JSON.parse(requestEditor.value);
            const payload = {name: name.value, agentIds: ids, testRequest};
            let url = '/api/distributed-tests';
            if (engine.value === 'CLICKHOUSE') {
                url += '/clickhouse';
                payload.endpointByAgent = JSON.parse(endpointMap.value || '{}');
                if (!testRequest.profileId || /^0{8}-0{4}-0{4}-0{4}-0{12}$/.test(testRequest.profileId)) throw new Error('Укажите реальный profileId ClickHouse из настроек Coordinator');
            }
            await request(url, {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload)});
            message.textContent = `Распределённый ${engine.value} тест создан`;
            await Promise.all([loadRuns(), loadAgents()]);
        } catch (error) { message.textContent = error.message; }
        finally { startButton.disabled = false; }
    }

    engine.addEventListener('change', applyEngine);
    startButton.addEventListener('click', startDistributedTest);
    refreshButton.addEventListener('click', () => loadRuns({showLoading:true}));
    document.addEventListener('visibilitychange', () => { if (!document.hidden) { loadRuns(); loadAgents(); } });
    window.addEventListener('pagehide', () => window.clearInterval(refreshTimer));

    applyEngine();
    loadAgents({showLoading:true});
    loadRuns({showLoading:true});
    refreshTimer = window.setInterval(() => { if (!document.hidden) loadRuns(); }, REFRESH_INTERVAL_MS);
})();