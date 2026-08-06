const form = document.getElementById('settings-form');

function element(id) {
    return document.getElementById(id);
}

function value(id) {
    return element(id).value;
}

function checked(id) {
    return element(id).checked;
}

function updateVaultFields() {
    const approle = value('vaultAuthMethod') === 'APPROLE';
    document.querySelectorAll('.vault-approle-field').forEach(item => item.hidden = !approle);
    document.querySelectorAll('.vault-token-field').forEach(item => item.hidden = approle);
}

function payload() {
    return {
        jdbcUrl: value('jdbcUrl'),
        postgresUsername: value('postgresUsername'),
        postgresPassword: value('postgresPassword'),
        vaultAddress: value('vaultAddress'),
        vaultAuthMethod: value('vaultAuthMethod'),
        vaultToken: value('vaultToken'),
        vaultAuthMount: value('vaultAuthMount'),
        vaultRoleId: value('vaultRoleId'),
        vaultSecretId: value('vaultSecretId'),
        vaultKvMount: value('vaultKvMount'),
        vaultSecretPrefix: value('vaultSecretPrefix'),
        vaultTlsVerify: checked('vaultTlsVerify'),
        vaultCaCertificatePath: value('vaultCaCertificatePath'),
        s3ProfileName: value('s3ProfileName'),
        s3Endpoint: value('s3Endpoint'),
        s3Region: value('s3Region'),
        s3Bucket: value('s3Bucket'),
        s3PathStyleAccess: checked('s3PathStyleAccess'),
        s3CredentialsSource: value('s3CredentialsSource'),
        s3VaultSecretPath: value('s3VaultSecretPath'),
        s3AccessKeyField: value('s3AccessKeyField'),
        s3SecretKeyField: value('s3SecretKeyField'),
        s3AccessKey: value('s3AccessKey'),
        s3SecretKey: value('s3SecretKey')
    };
}

function csrfHeaders() {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
    return token && header ? {[header]: token} : {};
}

async function post(url) {
    const response = await fetch(url, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            ...csrfHeaders()
        },
        body: JSON.stringify(payload())
    });
    const text = await response.text();
    if (!response.ok) {
        throw new Error(text || `HTTP ${response.status}`);
    }
    return text ? JSON.parse(text) : {};
}

function setBusy(button, busy, busyText) {
    if (!button) return;
    if (busy) {
        button.dataset.originalText = button.textContent;
        button.textContent = busyText;
    } else if (button.dataset.originalText) {
        button.textContent = button.dataset.originalText;
        delete button.dataset.originalText;
    }
    button.disabled = busy;
}

function setConnectionState(id, state, text) {
    const target = element(id);
    if (!target) return;
    target.className = `connection-state ${state}`;
    target.textContent = text;
}

function formatDiagnostic(response, elapsedMs) {
    const result = {...response};
    if (result.latencyMs == null) result.clientElapsedMs = elapsedMs;
    return JSON.stringify(result, null, 2);
}

async function runConnectionTest({buttonId, resultId, stateId, url, progressText}) {
    const button = element(buttonId);
    const target = element(resultId);
    setBusy(button, true, 'Проверка…');
    setConnectionState(stateId, 'checking', 'Проверяется');
    target.textContent = progressText;
    const started = performance.now();
    try {
        const response = await post(url);
        const elapsed = Math.round(performance.now() - started);
        target.textContent = formatDiagnostic(response, elapsed);
        const success = response.success !== false;
        setConnectionState(stateId, success ? 'success' : 'failure', success ? 'Доступно' : 'Ошибка');
    } catch (error) {
        target.textContent = error.message;
        setConnectionState(stateId, 'failure', 'Ошибка');
    } finally {
        setBusy(button, false);
    }
}

form.addEventListener('submit', async event => {
    event.preventDefault();
    const button = element('save-settings');
    const target = element('save-result');
    setBusy(button, true, 'Сохранение…');
    target.className = 'save-result pending';
    target.textContent = 'Проверка и запись bootstrap-конфигурации…';
    try {
        const response = await post('/api/settings');
        target.className = 'save-result success';
        target.textContent = response.message || 'Настройки сохранены';
        ['postgresPassword', 'vaultToken', 'vaultSecretId', 's3AccessKey', 's3SecretKey']
            .forEach(id => element(id).value = '');
    } catch (error) {
        target.className = 'save-result failure';
        target.textContent = error.message;
    } finally {
        setBusy(button, false);
    }
});

element('test-postgres').addEventListener('click', () => runConnectionTest({
    buttonId: 'test-postgres',
    resultId: 'postgres-result',
    stateId: 'postgres-state',
    url: '/api/settings/test/postgresql',
    progressText: 'Установка соединения и выполнение диагностического запроса…'
}));

element('test-vault').addEventListener('click', () => runConnectionTest({
    buttonId: 'test-vault',
    resultId: 'vault-result',
    stateId: 'vault-state',
    url: '/api/settings/test/vault',
    progressText: 'Проверка Vault health, TLS и выбранного механизма аутентификации…'
}));

element('vaultAuthMethod').addEventListener('change', updateVaultFields);

function updateSystemInformation() {
    element('system-platform').textContent = navigator.userAgentData?.platform || navigator.platform || 'Не определено';
    element('system-language').textContent = navigator.language || 'Не определено';
    element('system-cpu').textContent = navigator.hardwareConcurrency || 'Не определено';
    element('system-online').textContent = navigator.onLine ? 'Подключено' : 'Нет подключения';
}

window.addEventListener('online', updateSystemInformation);
window.addEventListener('offline', updateSystemInformation);
updateVaultFields();
updateSystemInformation();
