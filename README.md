# ЭВО.СНТ S3

Java 21 / Spring Boot платформа для функционального, нагрузочного, распределённого, регрессионного и отказоустойчивого тестирования S3-совместимых хранилищ, ClickHouse и Kafka.

> **Текущая версия:** `2.4.0`<br>
> **Статус:** стабильный выпуск<br>
> **Релизный тег:** `v2.4.0`<br>
> **Последний стабильный релиз:** `2.4.0`<br>
> **Ветка разработки:** `2.5.0-SNAPSHOT` — см. [ROADMAP_2.5.md](ROADMAP_2.5.md)

## Что нового в 2.4.0

- общий admission control ограничивает число одновременных прогонов и суммарное число S3/ClickHouse/Kafka worker-задач;
- не принятый из-за перегрузки запуск возвращает HTTP 429, а превышение глобального дедлайна завершает прогон как `CANCELLED`;
- собственные неограниченные пулы multipart, ClickHouse и Kafka заменены общим bounded executor;
- агенты передают координатору доступную/занятую ёмкость и сведения об использовании памяти;
- PostgreSQL использует reloadable HikariCP с метриками, connection/query/transaction timeout и атомарной сменой пула после изменения bootstrap-настроек;
- Maven CI выполняет реальные Testcontainers-проверки Kafka и ClickHouse с Keeper;
- добавлены регрессионные тесты admission control, освобождения permit по дедлайну и горячей смены PostgreSQL-пула.

## Возможности

- корпоративный Web UI «ЭВО.СНТ S3»;
- разделы «Задания», «История», «Мониторинг», «Агенты», «Расписания» и «Настройки»;
- локальные и распределённые S3-тесты;
- локальные и распределённые ClickHouse-тесты;
- локальные и распределённые Kafka producer-тесты, consumer/E2E, replication, scaling и failover;
- ClickHouse profiles, topology discovery, history, comparison и trends;
- наблюдаемость репликации, Keeper HA и сценарии replica failover;
- S3-профили с автоматическим применением endpoint, region, bucket и Vault path;
- UPLOAD, DOWNLOAD, HEAD, LIST, DELETE, LIFECYCLE и MIXED;
- multipart upload, parallelism, warm-up и ограничение OPS;
- throughput, OPS, bytes, errors, p50, p95 и p99;
- история, baseline, регрессия и тренды 2–20 запусков;
- экспорт результатов в JSON, CSV, HTML и PDF;
- Vault Token и production-ready AppRole;
- управление агентами: включение, отключение, отзыв identity и обновление;
- Keycloak OIDC и RBAC;
- REST API v2;
- импорт/экспорт конфигурации, включая защищённый `.evos3`;
- Prometheus, расширенные Grafana dashboards и alert rules;
- Flyway, audit, CSRF, security headers, SpotBugs, JaCoCo, CodeQL и CycloneDX SBOM.

## Архитектура

```text
Browser / REST client
        |
      HTTPS
        |
+----------------------------------+
| ЭВО.СНТ S3 Coordinator           |
| Web UI / API v2 / Scheduler      |
| History / Baseline / Trends      |
| Monitoring / Audit / RBAC        |
+----------------------------------+
   | JDBC       | HTTPS       | Agent API
PostgreSQL    Vault/Keycloak   Agents 1..N
                                   |
                          S3 / ClickHouse API
                              |         |
                         S3 / MinIO  ClickHouse
```

PostgreSQL, Vault, Keycloak, S3, ClickHouse и Kafka являются внешними сервисами и не поднимаются приложением в production.

## Требования

| Компонент | Версия |
|---|---|
| Java | 21+ |
| PostgreSQL | 15–18 |
| Vault Community Edition | 2.0.x |
| Keycloak | 26+ |
| ClickHouse | HTTP(S) endpoint; версия определяется при discovery |
| Kafka | Доступный bootstrap servers; Kafka/KRaft topology |
| Kubernetes | 1.28+ |
| Linux | systemd-based |
| S3 | AWS S3, MinIO и совместимые реализации |

## Быстрый запуск

```bash
export S3_PERF_BOOTSTRAP_FILE=/opt/s3perf/config/bootstrap-settings.json
export S3_PERF_BOOTSTRAP_KEY='replace-with-long-random-secret'
java -jar evo-snt-s3-2.4.0.jar
```

Откройте `http://localhost:8080/settings`. После первого сохранения PostgreSQL-настроек перезапустите приложение.

## Основные страницы

```text
/tasks                       задания, запуск и локальные шаблоны
/history                     история, сравнение и тренды
/monitoring                  Health Dashboard
/agents                      управление агентами
/distributed-tests           распределённые тесты
/schedules                   расписания
/baselines                   baseline и регрессия
/settings                    PostgreSQL и Vault
/settings/keycloak           Keycloak
/settings/s3-profiles        S3-профили
/settings/clickhouse-profiles профили и discovery ClickHouse
/clickhouse                  ClickHouse-тесты и история
/clickhouse/replication      состояние репликации
/clickhouse/replicated-tests сценарии ReplicatedMergeTree
/clickhouse/failover-tests   управляемые failover-тесты
/clickhouse/ha               ClickHouse Keeper и HA
/kafka                       Kafka Producer load test
/kafka/m2                    Kafka Consumer и producer-to-consumer E2E
/kafka/m3                    replication и partition scaling
/kafka/m4                    failover/resilience и распределённая нагрузка
/settings/kafka-profiles     профили Kafka
/settings/configuration      импорт и экспорт конфигурации
```

Старые адреса с `.html` перенаправляются на канонические маршруты.

## Bootstrap и секреты

Приложение запускается, если bootstrap-файл отсутствует, пуст или содержит только пробелы. Перед заменой существующей конфигурации создаётся backup в каталоге `config/backups`.

Секреты шифруются с использованием `S3_PERF_BOOTSTRAP_KEY`. Сохранённые задания браузера не содержат Access Key и Secret Key.

## Vault

Поддерживаются `TOKEN` и `APPROLE`. AppRole включает кеширование client token, lease-aware renewal, повторный login после 401/403, TLS verification и пользовательский CA. `secret_id` хранится только в зашифрованном bootstrap-файле.

## Импорт и экспорт конфигурации

Раздел `/settings/configuration` поддерживает открытый JSON без секретов и защищённый `.evos3` с AES-256-GCM, PBKDF2-HMAC-SHA256, 210 000 итераций, уникальными salt/IV и автоматическим backup перед импортом.

## Docker

```bash
docker run --rm -p 8080:8080 \
  -e S3_PERF_BOOTSTRAP_KEY='replace-with-long-random-secret' \
  -e S3_PERF_BOOTSTRAP_FILE=/app/config/bootstrap-settings.json \
  -v "$PWD/config:/app/config" \
  ghcr.io/phibus-dev/s3-performance-test-web:2.4.0
```

Для production рекомендуется фиксированный тег `2.4.0`. Теги `2.4` и `latest` публикуются тем же стабильным release workflow, но фиксированная версия обеспечивает воспроизводимое развёртывание.

## ClickHouse

Перед запуском ClickHouse-тестов настройте PostgreSQL, затем создайте профиль в `/settings/clickhouse-profiles`. Endpoint должен начинаться с `http://` или `https://`; можно указать несколько адресов через запятую или с новой строки. Пароль шифруется ключом `S3_PERF_BOOTSTRAP_KEY`.

Базовый движок поддерживает `INSERT`, `SELECT` и `INSERT_SELECT`, concurrency 1–64, пакетную вставку, режим по числу строк или длительности, warm-up и автоматическое создание тестовой MergeTree-таблицы.

Для ReplicatedMergeTree доступны discovery реплик, lag/queue/readonly/session health, проверка Keeper, подготовка тестовой таблицы, проверка консистентности и управляемый failover. Отказ и восстановление реплики выполняются оператором вне приложения и подтверждаются в UI.

## Systemd

```ini
[Service]
User=s3perf
Group=s3perf
WorkingDirectory=/opt/s3perf
EnvironmentFile=/etc/s3perf/s3perf.env
ExecStart=/usr/bin/java -Xms512m -Xmx2g -XX:+ExitOnOutOfMemoryError -jar /opt/s3perf/bin/application.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
```

## Kubernetes

Используйте внешний PostgreSQL, Vault, Keycloak и S3, Secret для `S3_PERF_BOOTSTRAP_KEY`, PVC для bootstrap-файла, HTTPS Ingress и probes:

```text
/actuator/health/liveness
/actuator/health/readiness
```

## Monitoring

```text
/monitoring
GET /api/health/overview
GET /actuator/prometheus
```

Grafana dashboards:

```text
deploy/grafana/s3-performance-overview.json
deploy/grafana/s3-agents-dashboard.json
deploy/grafana/s3-runtime-dashboard.json
```

Prometheus alerts:

```text
deploy/prometheus/s3-performance-alerts.yml
```

## Лимиты выполнения и PostgreSQL pool

Основные настройки можно передать через environment variables:

| Переменная | По умолчанию | Назначение |
|---|---:|---|
| `S3PERF_RUNTIME_MAX_CONCURRENT_RUNS` | `8` | Одновременные локальные/agent-side прогоны |
| `S3PERF_RUNTIME_MAX_WORKER_TASKS` | `64` | Суммарная concurrency worker-задач |
| `S3PERF_RUNTIME_MAX_RUN_DURATION_SECONDS` | `86400` | Жёсткий дедлайн одного прогона |
| `S3PERF_RUNTIME_EXECUTOR_QUEUE_CAPACITY` | `32` | Очередь верхнеуровневых задач |
| `S3PERF_POSTGRESQL_POOL_MAXIMUM_SIZE` | `16` | Максимальный размер HikariCP |
| `S3PERF_POSTGRESQL_CONNECTION_TIMEOUT_MS` | `10000` | Ожидание JDBC connection |
| `S3PERF_POSTGRESQL_QUERY_TIMEOUT_SECONDS` | `30` | Таймаут JdbcTemplate query |
| `S3PERF_POSTGRESQL_TRANSACTION_TIMEOUT_SECONDS` | `60` | Таймаут Spring transaction |
| `S3PERF_DISTRIBUTED_RECOVERY_TIMEOUT_SECONDS` | `300` | Отмена восстановленного запуска без новых отчётов Agent |
| `S3PERF_DISTRIBUTED_RECOVERY_SCAN_INTERVAL_MS` | `30000` | Интервал reconciliation распределённых запусков |

Prometheus экспортирует `s3perf_runtime_*` и `hikaricp_*`. Актуальная ёмкость агента также доступна в его heartbeat tags.

## Сборка

```bash
mvn clean verify
java -jar target/s3-multipart-uploader-2.5.0-SNAPSHOT.jar
```

Интеграционные тесты используют Testcontainers и требуют доступного Docker daemon.

## Обновление с 2.3.0 или 2.4.0-rcN

См. `UPGRADE_2.4.0.md`. Стабильный выпуск использует ту же схему V24, что и rc18. До обновления сохраните bootstrap-файл и резервную копию PostgreSQL; Coordinator и Agent рекомендуется обновлять одновременно, затем подобрать лимиты по ресурсам узлов.

## Релиз

```bash
gh workflow run release.yml --ref main -f version=v2.4.0
```

Release workflow публикует executable JAR, sources, Javadoc, CycloneDX SBOM, SHA256SUMS, README, release notes, upgrade guide и Docker images с тегами `2.4.0`, `2.4` и `latest`.
