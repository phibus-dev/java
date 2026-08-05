# S3 Performance Test Web

Java 21 / Spring Boot приложение для функционального и нагрузочного тестирования AWS S3, MinIO и других S3-совместимых хранилищ.

## Возможности

- Web UI и REST API;
- параллельная multipart-загрузка;
- p50/p95/p99 latency;
- JSON/CSV-отчёты;
- автоматическое удаление тестовых объектов;
- bootstrap-режим настройки внешних PostgreSQL, Vault и S3;
- endpoint состояния `/actuator/health`;
- Prometheus-метрики и готовый Grafana Dashboard.

## Bootstrap mode

При первом запуске или при недоступном PostgreSQL приложение разрешает только:

```text
/settings
/api/settings/**
/actuator/health
/static/**
```

Остальные страницы перенаправляются в `/settings`. После успешной проверки PostgreSQL приложение переходит в состояние `READY`. Изменение JDBC-настроек применяется после перезапуска.

## Внешний PostgreSQL

В Web UI используется готовая JDBC-строка, например:

```text
jdbc:postgresql://postgres.example.org:5432/s3_performance?sslmode=require
```

PostgreSQL не включён в приложение и не запускается через Docker Compose.

## HashiCorp Vault

Заявлена поддержка **HashiCorp Vault Community Edition 2.0.x**. Поддерживаются механизмы аутентификации Token и AppRole.

Настраиваются:

- Vault address;
- метод аутентификации Token или AppRole;
- token либо Role ID / Secret ID;
- KV mount;
- secret prefix;
- TLS verification;
- путь к CA certificate;
- путь и имена полей секрета для профиля S3.

## Bootstrap-хранилище

По умолчанию настройки сохраняются в:

```text
config/bootstrap-settings.json
```

Путь можно изменить:

```bash
export S3_PERF_BOOTSTRAP_FILE=/opt/s3-performance/config/bootstrap-settings.json
```

Пароли, Vault token, AppRole Secret ID и ручные S3 credentials шифруются AES-GCM. До сохранения секретов задайте мастер-фразу:

```bash
export S3_PERF_BOOTSTRAP_KEY='use-a-long-random-secret-value'
```

Мастер-фраза не записывается в bootstrap-файл.

## Запуск

```bash
mvn clean verify
java -jar target/s3-multipart-uploader-1.3.0-SNAPSHOT.jar
```

Откройте:

```text
http://localhost:8080/settings
```

## Docker

```bash
docker build -t s3-performance-test-web:1.3.0 .
docker run --rm -p 8080:8080 \
  -e S3_PERF_BOOTSTRAP_KEY='use-a-long-random-secret-value' \
  -v "$PWD/config:/app/config" \
  s3-performance-test-web:1.3.0
```

Приложение подключается к внешним PostgreSQL, Vault и S3/MinIO по адресам, указанным в Web UI.

## Prometheus

Метрики публикуются через Spring Boot Actuator:

```text
GET /actuator/prometheus
```

Health endpoints:

```text
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
```

Все метрики приложения содержат общий тег:

```text
application="s3-performance-test"
```

### Основные метрики приложения

| Метрика | Тип | Описание |
|---|---|---|
| `s3_test_active` | Gauge | Количество локальных тестов со статусом `QUEUED` или `RUNNING`. |
| `s3_test_runs` | Gauge | Общее количество запусков, находящихся в runtime-реестре приложения. |
| `s3_test_transferred_bytes` | Gauge | Суммарное количество байтов, переданных активными и завершёнными тестами. |
| `s3_test_throughput_mibps` | Gauge | Суммарная средняя пропускная способность тестов в MiB/s. |
| `s3_test_operations_per_second` | Gauge | Суммарное количество S3-операций в секунду. |
| `s3_test_errors` | Gauge | Суммарное количество ошибочных операций. |
| `s3_test_p95_latency_milliseconds` | Gauge | Максимальное текущее значение p95 latency среди тестов, мс. |
| `s3_agents_online` | Gauge | Количество распределённых агентов со статусом `ONLINE`. |
| `s3_agents_busy` | Gauge | Количество распределённых агентов со статусом `BUSY`. |
| `s3_agents_offline` | Gauge | Количество распределённых агентов со статусом `OFFLINE`. |

Дополнительно доступны стандартные JVM, process, disk, HTTP и Spring Boot Actuator-метрики, включая:

```text
jvm_memory_used_bytes
jvm_threads_live_threads
process_cpu_usage
system_cpu_usage
process_uptime_seconds
http_server_requests_seconds_count
http_server_requests_seconds_sum
http_server_requests_seconds_bucket
```

HTTP histogram позволяет рассчитывать p95 и p99 latency:

```promql
histogram_quantile(
  0.95,
  sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri, method)
)
```

```promql
histogram_quantile(
  0.99,
  sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri, method)
)
```

### Пример scrape-конфигурации Prometheus

Готовый пример находится в:

```text
deploy/prometheus/s3-performance-scrape.yml
```

Минимальная конфигурация:

```yaml
scrape_configs:
  - job_name: s3-performance-test
    metrics_path: /actuator/prometheus
    scrape_interval: 10s
    static_configs:
      - targets:
          - s3-performance-test:8080
```

Для одиночного запуска на localhost:

```yaml
scrape_configs:
  - job_name: s3-performance-test
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['localhost:8080']
```

## Grafana Dashboard

Готовый dashboard находится в файле:

```text
deploy/grafana/s3-performance-dashboard.json
```

Импорт в Grafana:

1. Откройте **Dashboards → New → Import**.
2. Загрузите файл `deploy/grafana/s3-performance-dashboard.json` либо вставьте JSON-код.
3. Выберите Prometheus data source.
4. Нажмите **Import**.

Dashboard содержит панели:

- Active tests;
- Online agents;
- Busy agents;
- Errors;
- Throughput, MiB/s;
- Operations per second;
- P95 latency, ms;
- Transferred bytes;
- HTTP latency p95.

### Код Grafana Dashboard

```json
{
  "annotations": {"list": []},
  "editable": true,
  "graphTooltip": 1,
  "panels": [
    {
      "type": "stat",
      "title": "Active tests",
      "id": 1,
      "gridPos": {"h": 4, "w": 4, "x": 0, "y": 0},
      "targets": [{"expr": "s3_test_active", "refId": "A"}]
    },
    {
      "type": "stat",
      "title": "Online agents",
      "id": 2,
      "gridPos": {"h": 4, "w": 4, "x": 4, "y": 0},
      "targets": [{"expr": "s3_agents_online", "refId": "A"}]
    },
    {
      "type": "stat",
      "title": "Busy agents",
      "id": 3,
      "gridPos": {"h": 4, "w": 4, "x": 8, "y": 0},
      "targets": [{"expr": "s3_agents_busy", "refId": "A"}]
    },
    {
      "type": "stat",
      "title": "Errors",
      "id": 4,
      "gridPos": {"h": 4, "w": 4, "x": 12, "y": 0},
      "targets": [{"expr": "s3_test_errors", "refId": "A"}]
    },
    {
      "type": "timeseries",
      "title": "Throughput, MiB/s",
      "id": 5,
      "gridPos": {"h": 8, "w": 12, "x": 0, "y": 4},
      "targets": [{
        "expr": "s3_test_throughput_mibps",
        "legendFormat": "Throughput",
        "refId": "A"
      }]
    },
    {
      "type": "timeseries",
      "title": "Operations per second",
      "id": 6,
      "gridPos": {"h": 8, "w": 12, "x": 12, "y": 4},
      "targets": [{
        "expr": "s3_test_operations_per_second",
        "legendFormat": "OPS",
        "refId": "A"
      }]
    },
    {
      "type": "timeseries",
      "title": "P95 latency, ms",
      "id": 7,
      "gridPos": {"h": 8, "w": 12, "x": 0, "y": 12},
      "targets": [{
        "expr": "s3_test_p95_latency_milliseconds",
        "legendFormat": "P95",
        "refId": "A"
      }]
    },
    {
      "type": "timeseries",
      "title": "Transferred bytes",
      "id": 8,
      "gridPos": {"h": 8, "w": 12, "x": 12, "y": 12},
      "targets": [{
        "expr": "s3_test_transferred_bytes",
        "legendFormat": "Bytes",
        "refId": "A"
      }]
    },
    {
      "type": "timeseries",
      "title": "HTTP latency p95",
      "id": 9,
      "gridPos": {"h": 8, "w": 24, "x": 0, "y": 20},
      "targets": [{
        "expr": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri, method))",
        "legendFormat": "{{method}} {{uri}}",
        "refId": "A"
      }]
    }
  ],
  "refresh": "10s",
  "schemaVersion": 41,
  "tags": ["s3", "performance", "load-testing"],
  "templating": {"list": []},
  "time": {"from": "now-1h", "to": "now"},
  "timezone": "browser",
  "title": "S3 Performance Test",
  "uid": "s3-performance-test",
  "version": 1
}
```
