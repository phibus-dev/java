# S3 Performance Test Web

Java 21 / Spring Boot приложение для функционального, нагрузочного и регрессионного тестирования AWS S3, MinIO и других S3-совместимых объектных хранилищ.

Приложение поддерживает локальное и распределённое выполнение тестов, хранение истории во внешнем PostgreSQL, получение секретов из HashiCorp Vault, аутентификацию через Keycloak, планирование запусков, baseline-сравнение и мониторинг через Prometheus/Grafana.

## Основные возможности

- Web UI и REST API;
- выбор S3-профиля, endpoint, региона и bucket;
- multipart upload и параллельное выполнение операций;
- тесты загрузки, чтения, удаления и смешанной нагрузки;
- выполнение по количеству операций или продолжительности;
- warm-up перед измеряемой фазой;
- текущие OPS, throughput, объём, ошибки и p50/p95/p99 latency;
- остановка активного теста;
- история результатов во внешнем PostgreSQL;
- экспорт результатов в JSON и CSV;
- повторный запуск теста;
- baseline и выявление регрессии производительности;
- постоянный scheduler с восстановлением после перезапуска;
- распределённое тестирование через coordinator и автономные агенты;
- промежуточная статистика и агрегация результатов агентов;
- несколько постоянных S3-профилей;
- Vault Token и AppRole;
- опциональная аутентификация Keycloak OIDC и RBAC;
- аудит действий в PostgreSQL и страница `/audit.html`;
- Prometheus, Grafana, liveness и readiness endpoints;
- CycloneDX SBOM, CodeQL, SpotBugs, JaCoCo и интеграционные тесты Testcontainers.

## Архитектура

```text
Web browser / REST client
          |
          v
S3 Performance Test Coordinator
  |       |        |         |
  |       |        |         +--> Keycloak OIDC
  |       |        +------------> HashiCorp Vault
  |       +---------------------> внешний PostgreSQL
  +-----------------------------> S3 / MinIO
          |
          +----------------------> Distributed agents --> S3 / MinIO
```

PostgreSQL, Vault, Keycloak и S3/MinIO являются внешними сервисами. Приложение не разворачивает их в production автоматически.

## Режимы приложения

### Coordinator

Основной режим с Web UI, REST API, scheduler, историей и управлением агентами.

### Agent

Один и тот же JAR может запускаться как автономный агент:

```bash
export S3PERF_APPLICATION_MODE=AGENT
export S3PERF_AGENT_COORDINATOR_URL=https://coordinator.example.org
export S3PERF_AGENT_REGISTRATION_TOKEN=registration-token
export S3PERF_AGENT_NAME=agent-01
export S3PERF_AGENT_ADDRESS=10.10.10.21
export S3PERF_AGENT_IDENTITY_FILE=/opt/s3perf/config/agent-identity.json

java -jar s3-multipart-uploader-1.3.0-SNAPSHOT.jar
```

Агент автоматически регистрируется, сохраняет identity и token, отправляет heartbeat, получает задания, передаёт промежуточную статистику и финальный результат.

## Bootstrap mode

При первом запуске или недоступном PostgreSQL доступны только:

```text
/settings
/api/settings/**
/actuator/health/**
/static/**
```

После успешной проверки PostgreSQL приложение переходит в normal mode. Изменение JDBC-настроек применяется после перезапуска.

## Внешний PostgreSQL

Пример JDBC URL:

```text
jdbc:postgresql://postgres.example.org:5432/s3_performance?sslmode=require
```

В PostgreSQL сохраняются:

- история тестов и результаты;
- baseline;
- scheduler;
- распределённые тесты и агенты;
- аудит безопасности;
- S3-профили.

Flyway автоматически создаёт и обновляет схему БД.

## Bootstrap-хранилище

По умолчанию настройки сохраняются в:

```text
config/bootstrap-settings.json
```

Изменение пути:

```bash
export S3_PERF_BOOTSTRAP_FILE=/opt/s3-performance/config/bootstrap-settings.json
```

Секреты шифруются AES-GCM. Перед их сохранением задайте мастер-фразу:

```bash
export S3_PERF_BOOTSTRAP_KEY='use-a-long-random-secret-value'
```

Мастер-фраза в bootstrap-файл не записывается.

## S3-профили

Страница управления:

```text
/settings/s3-profiles
```

Поддерживаются:

- несколько именованных профилей;
- один профиль по умолчанию;
- создание, изменение, клонирование и удаление;
- endpoint, region, bucket и path-style access;
- отдельный Vault secret path;
- поля access key, secret key и session token;
- собственный CA certificate path;
- источники credentials `VAULT`, `ENVIRONMENT`, `MANUAL`.

`profileId` передаётся в запросе запуска. Если он не указан, используется профиль по умолчанию. Endpoint, region, bucket, path-style и Vault path автоматически подставляются в S3 engine.

## HashiCorp Vault

Заявлена поддержка **HashiCorp Vault Community Edition 2.0.x**.

Методы аутентификации:

```text
TOKEN
APPROLE
```

Настраиваются:

- Vault address;
- token либо Role ID / Secret ID;
- KV mount;
- secret prefix;
- TLS verification;
- CA certificate;
- путь и имена полей секрета для каждого S3-профиля.

## Keycloak OIDC и RBAC

Безопасность опциональна и по умолчанию отключена:

```bash
export S3PERF_SECURITY_ENABLED=true
export S3PERF_SECURITY_AUDIT_ENABLED=true
```

Настройки Keycloak доступны через:

```text
/settings/keycloak
```

Поддерживаются OAuth2 Login для Web UI и JWT Resource Server для REST API. Роли читаются из `realm_access.roles`.

| Роль | Назначение |
|---|---|
| `ADMIN` | Настройки, S3-профили, Vault, Keycloak, аудит и все операции. |
| `OPERATOR` | Запуск и остановка тестов, scheduler и distributed tests. |
| `VIEWER` | Просмотр UI, истории и результатов. |

Audit events сохраняют пользователя, действие, HTTP method, path, status, remote address и duration. Просмотр доступен на `/audit.html`.

## Запуск теста

Основные страницы:

```text
/                     основной Web UI
/history.html         история запусков
/schedules.html       scheduler
/agents.html          агенты
/distributed-tests.html распределённые тесты
/audit.html           аудит
/settings             bootstrap-настройки
/settings/s3-profiles S3-профили
/settings/keycloak    Keycloak
```

Пример REST-запроса:

```json
{
  "profileId": "2f724f2d-5db8-4f03-8f69-77de672cd62e",
  "operation": "UPLOAD",
  "objectSizeBytes": 1073741824,
  "partSizeBytes": 67108864,
  "concurrency": 8,
  "operationCount": 10,
  "durationSeconds": 0,
  "warmupSeconds": 10,
  "cleanup": true
}
```

## Scheduler

Расписания хранятся в PostgreSQL и восстанавливаются после перезапуска. Для предотвращения двойного запуска несколькими экземплярами coordinator используется атомарный claim задания и lease.

Сохраняются:

```text
last_run_at
next_run_at
last_test_run_id
last_error
```

## Baseline и регрессия

Завершённый тест можно назначить baseline. Последующие совместимые запуски сравниваются с ним по throughput, OPS, latency и errors. Результат сравнения сохраняется в истории и отображается в Web UI.

## Сборка и запуск

```bash
mvn clean verify
java -jar target/s3-multipart-uploader-1.3.0-SNAPSHOT.jar
```

Интеграционные тесты поднимают временные PostgreSQL и MinIO Testcontainers исключительно на время `mvn verify`. Для локального запуска интеграционных тестов требуется Docker Engine.

## Docker

```bash
docker build -t s3-performance-test-web:1.3.0 .
docker run --rm -p 8080:8080 \
  -e S3_PERF_BOOTSTRAP_KEY='use-a-long-random-secret-value' \
  -v "$PWD/config:/app/config" \
  s3-performance-test-web:1.3.0
```

Контейнер запускает приложение непривилегированным пользователем. Для HTTPS-развёртываний рекомендуется:

```bash
export S3PERF_SESSION_COOKIE_SECURE=true
```

## Health и Prometheus

```text
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /actuator/prometheus
```

Все пользовательские метрики содержат тег:

```text
application="s3-performance-test"
```

### Основные метрики

| Метрика | Тип | Описание |
|---|---|---|
| `s3_test_active` | Gauge | Активные локальные тесты. |
| `s3_test_runs` | Gauge | Число запусков в runtime-реестре. |
| `s3_test_transferred_bytes` | Gauge | Переданный объём данных. |
| `s3_test_throughput_mibps` | Gauge | Суммарный throughput, MiB/s. |
| `s3_test_operations_per_second` | Gauge | Суммарные операции в секунду. |
| `s3_test_errors` | Gauge | Количество ошибок. |
| `s3_test_p95_latency_milliseconds` | Gauge | Максимальная текущая p95 latency. |
| `s3_agents_online` | Gauge | Агенты `ONLINE`. |
| `s3_agents_busy` | Gauge | Агенты `BUSY`. |
| `s3_agents_offline` | Gauge | Агенты `OFFLINE`. |

Также доступны JVM, process, disk и HTTP metrics:

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

Пример p95 HTTP latency:

```promql
histogram_quantile(
  0.95,
  sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri, method)
)
```

## Prometheus scrape

Готовый файл:

```text
deploy/prometheus/s3-performance-scrape.yml
```

```yaml
scrape_configs:
  - job_name: s3-performance-test
    metrics_path: /actuator/prometheus
    scrape_interval: 10s
    static_configs:
      - targets: ['s3-performance-test:8080']
```

## Grafana Dashboard

Готовый dashboard:

```text
deploy/grafana/s3-performance-dashboard.json
```

Импорт: **Dashboards → New → Import**, загрузить JSON и выбрать Prometheus data source.

Dashboard содержит:

- Active tests;
- Online, Busy и Offline agents;
- Errors;
- Throughput;
- Operations per second;
- P95 latency;
- Transferred bytes;
- HTTP latency p95.

Минимальный код dashboard:

```json
{
  "editable": true,
  "refresh": "10s",
  "schemaVersion": 41,
  "tags": ["s3", "performance", "load-testing"],
  "time": {"from": "now-1h", "to": "now"},
  "timezone": "browser",
  "title": "S3 Performance Test",
  "uid": "s3-performance-test",
  "panels": [
    {"type":"stat","title":"Active tests","targets":[{"expr":"s3_test_active"}]},
    {"type":"stat","title":"Online agents","targets":[{"expr":"s3_agents_online"}]},
    {"type":"stat","title":"Busy agents","targets":[{"expr":"s3_agents_busy"}]},
    {"type":"stat","title":"Errors","targets":[{"expr":"s3_test_errors"}]},
    {"type":"timeseries","title":"Throughput, MiB/s","targets":[{"expr":"s3_test_throughput_mibps"}]},
    {"type":"timeseries","title":"Operations per second","targets":[{"expr":"s3_test_operations_per_second"}]},
    {"type":"timeseries","title":"P95 latency, ms","targets":[{"expr":"s3_test_p95_latency_milliseconds"}]},
    {"type":"timeseries","title":"Transferred bytes","targets":[{"expr":"s3_test_transferred_bytes"}]},
    {"type":"timeseries","title":"HTTP latency p95","targets":[{"expr":"histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri, method))"}]}
  ]
}
```

## Безопасность

Приложение включает:

- CSRF protection с cookie-based token repository;
- исключения CSRF только для token-based agent API;
- Content-Security-Policy;
- `X-Frame-Options: DENY`;
- `Referrer-Policy: no-referrer`;
- Permissions Policy;
- HSTS при HTTPS;
- `HttpOnly` и `SameSite=Lax` session cookies;
- скрытие stack trace и внутренних сообщений в HTTP errors;
- CodeQL и Dependency Review;
- SpotBugs;
- CycloneDX SBOM.

SBOM формируется при сборке и публикуется CI как artifact:

```text
target/classes/META-INF/sbom/application.cdx.json
```

## CI

GitHub Actions выполняет:

```text
unit tests
PostgreSQL integration tests
MinIO S3 compatibility tests
Flyway migration tests
JaCoCo report
SpotBugs
CodeQL
Dependency Review
CycloneDX SBOM
executable JAR verification
```

## Текущая версия

В ветке `main` используется версия:

```text
1.3.0-SNAPSHOT
```

Подготовка релиза `2.0.0` выполняется отдельно и после слияния release-изменений README и команды запуска должны использовать финальное имя JAR `s3-multipart-uploader-2.0.0.jar`.
