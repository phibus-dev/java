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
  --output-dir target/reference-soak-evidence
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

## Доказательства

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
кода `3`. Bearer token и credentials профиля в artifacts не попадают.

## GitHub Actions

Workflow `.github/workflows/reference-soak.yml` поддерживает ручной запуск, `workflow_call` и
еженедельное расписание. Scheduled job намеренно пропускается, пока repository variable
`SOAK_ENABLED` не равна `true`. До включения расписания настройте:

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
`SOAK_DRIFT_THRESHOLDS_JSON`. Workflow сохраняет полный каталог evidence на 90 дней и выводит
Markdown summary в интерфейсе Actions даже при `FAIL` или технической ошибке.

Перед `SOAK_ENABLED=true` выполните один ручной запуск, проверьте корректность профиля, очистку
тестовых данных, лимиты эталонного контура и полученный baseline. Профиль должен указывать только на
специально выделенное тестовое окружение.
