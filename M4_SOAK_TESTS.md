# M4: длительные проверки в эталонном окружении

Reference soak состоит из 3–48 последовательных окон одного benchmark-сценария. Каждое окно
создаётся отдельной идемпотентной командой, дожидается терминального состояния, экспортируется и
проверяется обычным quality gate. После серии CLI сравнивает медианы первой и последней трети
окон, чтобы обнаружить накопительную деградацию, которую одиночный прогон не показывает.

Для `S3_MIXED_STEADY` шесть окон дают около часа измеряемой нагрузки плюс warm-up и паузы. Для
других сценариев длительность определяется каталогом benchmark-сценариев и числом окон.

## Безопасная конфигурация

Начните с [config/soak-reference.example.json](config/soak-reference.example.json). Конфигурация
содержит только UUID уже сохранённого профиля, идентификаторы Agent и параметры нагрузки.
Credentials, token, password, access/secret key, authorization и private key запрещены на любой
глубине JSON. Endpoint с userinfo (`user:password@host`) также отклоняется. Неизвестные поля и
параметры, не относящиеся к выбранному движку, считаются ошибкой, поэтому опечатка не приводит к
тихому запуску с другим значением.

```bash
export EVO_SNT_URL=https://coordinator.example
export EVO_SNT_TOKEN="$TOKEN"

cp config/soak-reference.example.json target/reference-soak.json
# Укажите существующий profileId и уникальный idempotencyPrefix.
./bin/evo-snt runs soak \
  --config target/reference-soak.json \
  --output-dir target/reference-soak-evidence \
  --public-summary-dir target/reference-soak-public
```

Для `DISTRIBUTED` необходимо указать непустой `agentIds`; для `LOCAL` список должен быть пуст.
Политики `BASELINE_TOLERANT` и `BASELINE_STRICT` требуют `baselineRunId`. Один и тот же
`idempotencyPrefix` безопасно повторяет уже принятые окна; изменять параметры с прежним prefix
нельзя, поскольку Coordinator вернёт `IDEMPOTENCY_CONFLICT`.

## Критерии результата

Серия получает `PASS`, только если выполнены все условия:

1. исполнены все запрошенные окна и каждое завершилось статусом `COMPLETED`;
2. quality gate каждого окна вернул `PASS`;
3. доступно не менее трёх завершённых измерений;
4. снижение медианы operations/s между первой и последней третью не превышает порог;
5. для применимых метрик снижение MiB/s и рост p95 не превышают пороги;
6. суммарный error rate всех завершённых окон не превышает порог.

Значения по умолчанию: operations/s −20%, MiB/s −20%, p95 +25%, error rate 1%. Их можно изменить
в `driftThresholds`. Снижение и рост измеряются только в неблагоприятную сторону: улучшение
показателя даёт 0% деградации.

Exit codes сохраняют различие между результатом проверки и технической ошибкой:

| Код | Значение |
|---:|---|
| `0` | серия и все критерии прошли |
| `2` | ошибочная/небезопасная конфигурация или проблема локального файла |
| `3` | ошибка Coordinator/API |
| `7` | серия выполнена, но окно, gate, таймаут или drift не прошли |

## Приватные доказательства и обезличенная сводка

CLI не перезаписывает непустой каталог без `--force`. В каталоге сохраняются:

```text
resolved-config.json
window-001/start.json
window-001/state.json
window-001/report.json
window-001/quality-gate.json
window-001/window.json
...
soak-summary.json
soak-summary.md
```

При технической ошибке частичный summary и данные уже завершённых окон сохраняются до возврата
кода `3`. Каталог `--output-dir` является приватным: он содержит UUID профиля и запусков,
idempotency keys, ответы API и диагностические детали. Его нельзя публиковать в GitHub или
передавать за пределы внутреннего контура.

Если указан отдельный `--public-summary-dir`, CLI создаёт в нём только `soak-summary.json` и
`soak-summary.md`. Каталоги не могут совпадать, быть вложенными друг в друга или ссылаться друг на
друга через разрешённый путь. Публичная сводка формируется по белому списку и содержит только:

- общий verdict, тип и scope теста, идентификатор сценария и политики;
- время начала/окончания и количество окон;
- числовые метрики окон и агрегированные критерии drift;
- при технической ошибке — номер окна, HTTP status и машинный код без detail/request ID.

В публичную сводку не попадают URL, имя сценария, UUID профиля, Agent/baseline/run UUID,
idempotency keys, параметры нагрузки, replay/request ID и диагностические сообщения. Bearer token
не сохраняется ни в одном отчёте.

## Изолированный внутренний контур

Для сервера без доступа к GitHub предназначены
`scripts/reference-soak-local.sh` и systemd units из `deploy/systemd`. Тест общается только с
внутренним Coordinator. Полный evidence остаётся в
`/var/lib/evo-snt-reference-soak/private/<UTC timestamp>`, а разрешённая к передаче сводка — в
`/var/lib/evo-snt-reference-soak/public/<UTC timestamp>`. Автоматической отправки в GitHub нет.

Передайте `evo-snt-offline-soak-<version>.tar.gz` во внутренний контур через разрешённый канал,
сверьте SHA-256 и разверните его в `/opt/evo-snt`:

```bash
SOAK_PACKAGE=/secure/source/evo-snt-offline-soak-2.5.0-rc2.tar.gz
sudo install -d -o root -g root -m 0755 /opt/evo-snt
sudo tar -xzf "$SOAK_PACKAGE" \
  --strip-components=1 -C /opt/evo-snt
```

Затем выполните:

```bash
sudo useradd --system --home-dir /var/lib/evo-snt-reference-soak \
  --shell /usr/sbin/nologin evo-snt
sudo install -d -o root -g evo-snt -m 0750 /etc/evo-snt

sudo install -o root -g evo-snt -m 0640 \
  config/reference-soak.env.example /etc/evo-snt/reference-soak.env
sudo install -o root -g evo-snt -m 0640 \
  config/soak-reference.example.json /etc/evo-snt/reference-soak.json
# Создайте token-файл через утверждённый секретный канал; не помещайте token в env/json.
sudo install -o root -g root -m 0600 /secure/source/reference-soak.token \
  /etc/evo-snt/reference-soak.token

sudo install -o root -g root -m 0644 deploy/systemd/evo-snt-reference-soak.service \
  /etc/systemd/system/evo-snt-reference-soak.service
sudo install -o root -g root -m 0644 deploy/systemd/evo-snt-reference-soak.timer \
  /etc/systemd/system/evo-snt-reference-soak.timer
sudo systemd-analyze verify /etc/systemd/system/evo-snt-reference-soak.service \
  /etc/systemd/system/evo-snt-reference-soak.timer
sudo systemctl daemon-reload
```

Отредактируйте внутренний URL/CA в `reference-soak.env`, а существующий `profileId`, сценарий и
нагрузку — в `reference-soak.json`. Значение `idempotencyPrefix` из JSON при автономном запуске
заменяется уникальным UTC prefix. Token читается через systemd `LoadCredential`, а не через
аргументы процесса или environment.

Первый запуск выполните вручную и проверьте оба каталога:

```bash
sudo systemctl start evo-snt-reference-soak.service
sudo systemctl status evo-snt-reference-soak.service
sudo journalctl -u evo-snt-reference-soak.service --since today
sudo find /var/lib/evo-snt-reference-soak/public -maxdepth 2 -type f -print
```

Только после успешной проверки включите расписание:

```bash
sudo systemctl enable --now evo-snt-reference-soak.timer
systemctl list-timers evo-snt-reference-soak.timer
```

Timer стартует по воскресеньям в `01:23 UTC` (`04:23 MSK`) с задержкой до 15 минут и догоняет
пропущенный запуск после включения сервера. Автоматическое удаление evidence не выполняется:
срок хранения приватных данных должен задаваться локальной политикой внутреннего контура.

## GitHub Actions

Workflow `.github/workflows/reference-soak.yml` предназначен только для окружений, где GitHub
runner имеет сетевой доступ к Coordinator. Для изолированного внутреннего сервера он неприменим:
`SOAK_ENABLED` должен оставаться выключенным, а запуск выполняется systemd timer выше.

В подключённом окружении workflow поддерживает ручной запуск, `workflow_call` и еженедельное
расписание. Scheduled job пропускается, пока repository variable `SOAK_ENABLED` не равна `true`.
До включения расписания настройте:

| Тип | Имя | Назначение |
|---|---|---|
| Secret | `EVO_SNT_TOKEN` | bearer token с правом запуска и чтения истории |
| Variable | `SOAK_COORDINATOR_URL` | URL эталонного Coordinator |
| Variable | `SOAK_PROFILE_ID` | UUID сохранённого профиля |
| Variable | `SOAK_SCENARIO_ID` | сценарий, например `S3_MIXED_STEADY` |
| Variable | `SOAK_SCOPE` | `LOCAL` или `DISTRIBUTED` |
| Variable | `SOAK_AGENT_IDS` | UUID Agent через запятую для distributed |
| Variable | `SOAK_ITERATIONS` | число окон, минимум 3 |
| Variable | `SOAK_WORKLOAD_OVERRIDES_JSON` | JSON с object key, table или topic при необходимости |
| Variable | `SOAK_POLICY_ID` | политика quality gate |
| Variable | `SOAK_BASELINE_RUN_ID` | baseline для относительной политики |

Дополнительно поддерживаются `SOAK_COOLDOWN_SECONDS`, `SOAK_WAIT_TIMEOUT_SECONDS`,
`SOAK_POLL_INTERVAL_SECONDS`, `SOAK_REQUEST_TIMEOUT`, `SOAK_QUALITY_GATE_OVERRIDES_JSON` и
`SOAK_DRIFT_THRESHOLDS_JSON`. Workflow загружает на 90 дней только отдельный обезличенный каталог
`reference-soak-public` и выводит его Markdown summary в интерфейсе Actions. Полный evidence,
конфигурация, stdout и stderr CLI в artifact не включаются.

Перед `SOAK_ENABLED=true` выполните один ручной запуск, проверьте корректность профиля, очистку
тестовых данных, лимиты эталонного контура и полученный baseline. Профиль должен указывать только на
специально выделенное тестовое окружение.
