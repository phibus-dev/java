(() => {
    const STORAGE_KEY = 'evo.snt.s3.test-presets.v1';
    const SENSITIVE_FIELDS = new Set(['accessKey', 'secretKey']);
    const FIELD_IDS = [
        'profileId', 'scenario', 'operation', 'executionMode', 'durationSeconds', 'warmupSeconds',
        'endpoint', 'region', 'bucket', 'objectKey', 'objectCount', 'objectSizeMiB', 'partSizeMiB',
        'parallelism', 'pathStyleAccess', 'deleteAfterTest'
    ];

    const byId = id => document.getElementById(id);
    const select = byId('preset-select');
    const nameInput = byId('preset-name');
    const status = byId('preset-status');

    if (!select || !nameInput || !status) return;

    function readPresets() {
        try {
            const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]');
            return Array.isArray(parsed) ? parsed.filter(item => item && typeof item.name === 'string') : [];
        } catch (_) {
            return [];
        }
    }

    function writePresets(presets) {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(presets));
    }

    function captureConfiguration() {
        const configuration = {};
        FIELD_IDS.forEach(id => {
            if (SENSITIVE_FIELDS.has(id)) return;
            const element = byId(id);
            if (!element) return;
            configuration[id] = element.type === 'checkbox' ? element.checked : element.value;
        });
        return configuration;
    }

    function assignField(id, storedValue, dispatchChange = false) {
        if (SENSITIVE_FIELDS.has(id)) return;
        const element = byId(id);
        if (!element) return;
        if (element.type === 'checkbox') element.checked = Boolean(storedValue);
        else element.value = String(storedValue ?? '');
        if (dispatchChange) element.dispatchEvent(new Event('change', {bubbles: true}));
    }

    function syncBucketSelector(bucket) {
        const input = byId('bucket');
        const bucketSelect = byId('bucket-select');
        if (!input || !bucketSelect) return;
        const value = String(bucket ?? '');
        input.value = value;
        if (bucketSelect.hidden) return;
        const optionExists = [...bucketSelect.options].some(option => option.value === value);
        if (optionExists) {
            bucketSelect.value = value;
        } else {
            bucketSelect.value = '';
            bucketSelect.hidden = true;
            input.hidden = false;
        }
    }

    function applyConfiguration(configuration) {
        const config = configuration || {};

        // Profile and scenario have change handlers in app.js which can overwrite dependent fields.
        // Apply them first, then restore the exact values saved in the preset.
        if (Object.prototype.hasOwnProperty.call(config, 'profileId')) assignField('profileId', config.profileId, true);
        if (Object.prototype.hasOwnProperty.call(config, 'scenario')) assignField('scenario', config.scenario, true);

        FIELD_IDS.forEach(id => {
            if (id === 'profileId' || id === 'scenario' || id === 'bucket') return;
            if (!Object.prototype.hasOwnProperty.call(config, id)) return;
            assignField(id, config[id], id === 'executionMode');
        });

        if (Object.prototype.hasOwnProperty.call(config, 'bucket')) syncBucketSelector(config.bucket);

        const accessKey = byId('accessKey');
        const secretKey = byId('secretKey');
        if (accessKey) accessKey.value = '';
        if (secretKey) secretKey.value = '';
    }

    function render(selectedName = '') {
        const presets = readPresets().sort((a, b) => a.name.localeCompare(b.name, 'ru', {sensitivity: 'base'}));
        select.replaceChildren(new Option('Выберите сохранённое задание', ''));
        presets.forEach(preset => select.add(new Option(preset.name, preset.name)));
        select.value = presets.some(item => item.name === selectedName) ? selectedName : '';
        byId('load-preset').disabled = !select.value;
        byId('delete-preset').disabled = !select.value;
    }

    function selectedPreset() {
        return readPresets().find(item => item.name === select.value);
    }

    function loadSelectedPreset() {
        const preset = selectedPreset();
        if (!preset) return false;
        applyConfiguration(preset.configuration);
        nameInput.value = preset.name;
        status.textContent = 'Параметры задания загружены. Проверьте профиль, bucket и credentials перед запуском.';
        return true;
    }

    byId('save-preset').addEventListener('click', () => {
        const name = nameInput.value.trim();
        if (!name) {
            status.textContent = 'Укажите название задания.';
            return;
        }
        if (name.length > 100) {
            status.textContent = 'Название не должно превышать 100 символов.';
            return;
        }
        const presets = readPresets().filter(item => item.name !== name);
        presets.push({name, updatedAt: new Date().toISOString(), configuration: captureConfiguration()});
        writePresets(presets);
        render(name);
        status.textContent = 'Задание сохранено в браузере. Секретные ключи не сохраняются.';
    });

    byId('load-preset').addEventListener('click', loadSelectedPreset);

    byId('delete-preset').addEventListener('click', () => {
        const name = select.value;
        if (!name) return;
        writePresets(readPresets().filter(item => item.name !== name));
        render();
        nameInput.value = '';
        status.textContent = 'Сохранённое задание удалено.';
    });

    select.addEventListener('change', () => {
        const hasSelection = Boolean(select.value);
        byId('load-preset').disabled = !hasSelection;
        byId('delete-preset').disabled = !hasSelection;
        if (!hasSelection) {
            nameInput.value = '';
            status.textContent = '';
            return;
        }
        loadSelectedPreset();
    });

    render();
})();
