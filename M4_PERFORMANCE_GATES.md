# M4: performance quality gates

Quality gate преобразует метрики завершённого запуска в воспроизводимый результат `PASS` или
`FAIL`. Проверки работают одинаково для S3, ClickHouse и Kafka, локальных и распределённых
запусков. Результат содержит каждый проверенный критерий, фактическое значение, оператор и порог.

## Политики

| Политика | Baseline | Назначение |
|---|---:|---|
| `SMOKE` | нет | `COMPLETED`, не менее одной операции и одного участника, без ошибок |
| `LATENCY_GUARD` | нет | стартовый абсолютный SLO: p95 ≤ 5 s, p99 ≤ 10 s, error rate ≤ 1% |
| `BASELINE_TOLERANT` | да | throughput не ниже baseline более чем на 15%, p95/p99 не выше на 20%/25% |
| `BASELINE_STRICT` | да | только `COMPLETED`, без ошибок; throughput −5%, p95/p99 +10% |

Абсолютные пороги можно переопределить для конкретного сервиса и операции. Относительные политики
требуют baseline того же `testType`, `scope` и `operation`. Baseline должен находиться в терминальном
состоянии. Сравнивать запуск с самим собой нельзя.

Порог снижения throughput вычисляется как
`baseline × (1 − allowedDropPercent / 100)`, порог роста latency — как
`baseline × (1 + allowedIncreasePercent / 100)`. Error rate равен
`errors / (completedOperations + errors) × 100`.

## API и CLI

```text
GET  /api/v1/quality-gates/policies
GET  /api/v1/benchmark-scenarios?testType=S3
GET  /api/v1/benchmark-scenarios/{id}
POST /api/v1/run-history/{id}/quality-gate
```

```bash
./bin/evo-snt benchmarks list --type s3
./bin/evo-snt benchmarks show S3_UPLOAD_THROUGHPUT --json

./bin/evo-snt runs assert "$RUN_ID" --policy SMOKE
./bin/evo-snt runs assert "$RUN_ID" \
  --policy BASELINE_TOLERANT --baseline "$BASELINE_RUN_ID" --json
./bin/evo-snt runs assert "$RUN_ID" --policy LATENCY_GUARD \
  --min-mibps 100 --max-p95-ms 750 --max-error-rate 0.1
```

`runs assert` возвращает `0` для `PASS`, `6` для `FAIL`, `2` для ошибки аргументов и `3` для
ошибки API. Поэтому отрицательный gate штатно останавливает CI job и отличается от технической
ошибки вызова.

Тело запроса с переопределением порогов:

```json
{
  "policyId": "BASELINE_TOLERANT",
  "baselineRunId": "11111111-2222-4333-8444-555555555555",
  "overrides": {
    "minThroughputMiBps": 100,
    "maxP95LatencyMs": 750,
    "maxErrorRatePercent": 0.1
  }
}
```

## GitHub Actions

`.github/workflows/performance-gate.yml` можно запускать вручную или вызывать из другого workflow.
Передайте `coordinator_url`, `run_id`, `policy`, при необходимости `baseline_run_id`, и secret
`EVO_SNT_TOKEN`. Workflow всегда сохраняет `quality-gate.json` как artifact, включая случай `FAIL`.

```yaml
jobs:
  performance:
    uses: phibus-dev/java/.github/workflows/performance-gate.yml@main
    with:
      coordinator_url: https://coordinator.example
      run_id: ${{ needs.load-test.outputs.run_id }}
      policy: BASELINE_TOLERANT
      baseline_run_id: ${{ vars.PERFORMANCE_BASELINE_RUN_ID }}
    secrets:
      EVO_SNT_TOKEN: ${{ secrets.EVO_SNT_TOKEN }}
```

## Эксплуатационный процесс

1. Выберите сценарий и зафиксируйте неизменные профиль, операцию, scope и окружение.
2. Выполните несколько прогревочных запусков; не используйте их как baseline.
3. Назначьте стабильный завершённый запуск baseline и сохраните его ID в защищённой переменной CI.
4. Сначала применяйте `BASELINE_TOLERANT`, затем ужесточайте абсолютными SLO по накопленной истории.
5. При `FAIL` сохраните JSON gate и полный экспорт запуска; меняйте baseline только после осознанного
   изменения инфраструктуры или подтверждённого улучшения.

Каталог сценариев задаёт воспроизводимые стартовые параметры, но не содержит endpoint, credentials,
ID профиля, bucket, table или topic. Эти значения остаются настройками конкретного окружения.
