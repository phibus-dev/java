# M4: расширенные лимиты ресурсов

Расширенные runtime-лимиты защищают Coordinator и Agent от одновременного запуска нагрузки,
которая превышает доступные CPU, память, соединения или размер тестового окна. Ограничения
применяются до начала нагрузки и дополняют валидацию параметров конкретного движка.

## Модель ограничений

Для прогонов и worker-задач одновременно действуют три уровня квот:

1. глобальная квота процесса;
2. квота движка `S3`, `CLICKHOUSE` или `KAFKA`;
3. квота области выполнения `LOCAL` или `DISTRIBUTED`.

Эффективная квота движка или области никогда не превышает глобальную. Если значение категории
не задано, оно наследует глобальный лимит. Получение разрешений выполняется без ожидания. При
отказе на нижнем уровне уже полученные разрешения освобождаются, поэтому неуспешный запуск не
уменьшает последующую ёмкость процесса.

`COORDINATOR` применяет квоты `LOCAL`, а `AGENT` — `DISTRIBUTED`. Все сценарии с префиксами
`S3`, `CLICKHOUSE` и `KAFKA`, включая failover-сценарии, учитываются в квоте соответствующего
движка.

## Настройки

| Переменная | По умолчанию | Назначение |
|---|---:|---|
| `S3PERF_RUNTIME_MAX_CONCURRENT_RUNS` | `8` | Глобальное число одновременных прогонов |
| `S3PERF_RUNTIME_MAX_WORKER_TASKS` | `64` | Глобальное число worker-задач |
| `S3PERF_RUNTIME_MAX_WORKER_TASKS_PER_RUN` | глобальный worker-лимит | Worker-задачи одного прогона |
| `S3PERF_RUNTIME_MAX_RUN_DURATION_SECONDS` | `86400` | Warm-up и измеряемое окно одного прогона |
| `S3PERF_RUNTIME_MAX_CONCURRENT_RUNS_S3` | глобальный run-лимит | Одновременные S3-прогоны |
| `S3PERF_RUNTIME_MAX_CONCURRENT_RUNS_CLICKHOUSE` | глобальный run-лимит | Одновременные ClickHouse-прогоны |
| `S3PERF_RUNTIME_MAX_CONCURRENT_RUNS_KAFKA` | глобальный run-лимит | Одновременные Kafka-прогоны |
| `S3PERF_RUNTIME_MAX_CONCURRENT_RUNS_LOCAL` | глобальный run-лимит | Прогоны Coordinator |
| `S3PERF_RUNTIME_MAX_CONCURRENT_RUNS_DISTRIBUTED` | глобальный run-лимит | Прогоны Agent |
| `S3PERF_RUNTIME_MAX_WORKER_TASKS_S3` | глобальный worker-лимит | Worker-задачи S3 |
| `S3PERF_RUNTIME_MAX_WORKER_TASKS_CLICKHOUSE` | глобальный worker-лимит | Worker-задачи ClickHouse |
| `S3PERF_RUNTIME_MAX_WORKER_TASKS_KAFKA` | глобальный worker-лимит | Worker-задачи Kafka |
| `S3PERF_RUNTIME_MAX_WORKER_TASKS_LOCAL` | глобальный worker-лимит | Worker-задачи Coordinator |
| `S3PERF_RUNTIME_MAX_WORKER_TASKS_DISTRIBUTED` | глобальный worker-лимит | Worker-задачи Agent |
| `S3PERF_RUNTIME_MAX_S3_BYTES_PER_RUN` | `2251799813685248` | Суммарные байты count-based S3-прогона |
| `S3PERF_RUNTIME_MAX_CLICKHOUSE_ROWS_PER_RUN` | `Long.MAX_VALUE` | Строки ClickHouse INSERT-прогона |
| `S3PERF_RUNTIME_MAX_CLICKHOUSE_QUERIES_PER_RUN` | `10000000` | Запросы ClickHouse SELECT-прогона |
| `S3PERF_RUNTIME_MAX_KAFKA_MESSAGES_PER_RUN` | `Long.MAX_VALUE` | Сообщения Kafka producer-прогона |

Значения меньше единицы нормализуются до `1`. Категорийные значения выше глобального
ограничиваются глобальной квотой. Это исключает конфигурацию, которую процесс всё равно не может
исполнить.

## Расчёт запроса

- S3 учитывает `parallelism`, сумму warm-up и duration, а для count-based операций — рассчитанный
  объём передачи. Для `LIFECYCLE` учитывается загрузка и последующее чтение объекта.
- ClickHouse учитывает concurrency INSERT-фазы, строки INSERT, число SELECT-запросов и сумму
  warm-up и duration. Обычный SELECT использует одну worker-задачу.
- Kafka учитывает `producerThreads` и `messageCount`.

Превышение размера, длительности или worker-лимита одного запроса возвращает HTTP `400` и не
создаёт прогон. Если при admission занята квота прогонов, API возвращает HTTP `429` с
`Retry-After: 5`. Worker-разрешения получаются непосредственно перед созданием нагрузки; если
ёмкость была занята уже после admission, прогон завершается с диагностикой capacity exceeded.
Версионированный API использует коды `INVALID_REQUEST` и `CAPACITY_EXCEEDED` соответственно.

## Наблюдаемость

`GET /api/health/overview` содержит `runtimeCapacity`: глобальную ёмкость, значения по движкам и
областям выполнения. Те же данные отображаются на странице `/monitoring`.

Prometheus публикует:

- `s3perf_runtime_runs_active` и `s3perf_runtime_runs_available`;
- `s3perf_runtime_workers_active` и `s3perf_runtime_workers_available`;
- `s3perf_runtime_type_runs_*` и `s3perf_runtime_type_workers_*` с label `test_type`;
- `s3perf_runtime_scope_runs_*` и `s3perf_runtime_scope_workers_*` с label `scope`;
- `s3perf_runtime_limit_rejections_total` с labels `resource`, `constraint`, `test_type`, `scope`;
- `s3perf_runtime_rejections_total` для обратной совместимости.

## Подбор значений

Начните с небольших категорийных квот и увеличивайте их по одной, наблюдая CPU, heap, file
descriptors, JDBC/Kafka/S3 connection pools и p95/p99. `MAX_WORKER_TASKS` должен соответствовать
реальной ёмкости `workloadExecutor`; слишком большой лимит переносит очередь внутрь executor и
ухудшает latency. Для Agent задавайте distributed-квоты с учётом ресурсов конкретного узла.

После изменения ограничений перезапустите процесс, проверьте `/monitoring`, затем выполните короткий
эталонный прогон и только после этого запускайте длительный soak-тест.
