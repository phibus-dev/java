# Мониторинг ЭВО.СНТ

Документ актуален для версии `2.2.3-rc1`. Поставляемые dashboards покрывают S3, распределённые агенты, HTTP и JVM. Состояние ClickHouse replication/Keeper в этой версии доступно в Web UI `/clickhouse/replication` и `/clickhouse/ha`; отдельный Grafana dashboard для ClickHouse пока не поставляется.

## Dashboards

- `s3-performance-overview.json` — общая доступность, состояние тестов, throughput, OPS, latency, HTTP и JVM.
- `s3-agents-dashboard.json` — состояние распределённых агентов и показатели распределённой нагрузки.
- `s3-runtime-dashboard.json` — JVM, GC, CPU, threads, file descriptors, HTTP и executor queues.
- `s3-performance-dashboard.json` — исходный компактный dashboard, сохранённый для обратной совместимости.

Все новые dashboards используют переменную `instance`; overview также поддерживает переменную `job`.

## Импорт в Grafana

1. Откройте **Dashboards → New → Import**.
2. Загрузите нужный JSON-файл.
3. Выберите Prometheus data source.
4. Сохраните dashboard.

Для автоматического provisioning скопируйте JSON-файлы в каталог, указанный в Grafana dashboard provider.

## Prometheus

Scrape-конфигурация находится в `../prometheus/s3-performance-scrape.yml`.

Alert rules:

```yaml
rule_files:
  - /etc/prometheus/rules/s3-performance-alerts.yml
```

Файл правил: `../prometheus/s3-performance-alerts.yml`.

После изменения конфигурации проверьте правила и перечитайте конфигурацию:

```bash
promtool check rules /etc/prometheus/rules/s3-performance-alerts.yml
curl -X POST http://localhost:9090/-/reload
```

## Основные метрики приложения

- `s3_test_active`
- `s3_test_errors`
- `s3_test_throughput_mibps`
- `s3_test_operations_per_second`
- `s3_test_p95_latency_milliseconds`
- `s3_test_transferred_bytes`
- `s3_agents_online`
- `s3_agents_busy`
- `s3_agents_offline`

Также используются стандартные метрики Spring Boot Actuator/Micrometer: `http_server_requests_seconds_*`, `jvm_*`, `process_*` и `executor_*`.

Некоторые панели показывают `No data`, если конкретная версия Micrometer/JVM не публикует соответствующую метрику. Это не влияет на остальные панели.

## Проверка после обновления

После перехода на 2.2.3-rc1 убедитесь, что Prometheus продолжает собирать `/actuator/prometheus`, а переменные `job` и `instance` соответствуют scrape-конфигурации. Обновление не переименовывает существующие `s3_*` метрики и dashboards можно импортировать поверх прежних копий.
