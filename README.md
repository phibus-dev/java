# ЭВО.СНТ S3

Java 21 / Spring Boot платформа для функционального, нагрузочного, распределённого, регрессионного и отказоустойчивого тестирования S3-совместимых хранилищ и ClickHouse.

> **Текущая версия:** `2.2.3-rc1`<br>
> **Статус:** Release Candidate<br>
> **Ветка релиза:** `release/2.2.3-rc1`<br>
> **Последний стабильный релиз:** `2.2.2`

## Что нового в 2.2.3-rc1

- добавлен универсальный `LoadTestEngine` API с движками S3 и ClickHouse;
- реализованы профили ClickHouse с несколькими endpoint, проверкой соединения и discovery кластера;
- добавлены ClickHouse-нагрузки `INSERT`, `SELECT` и `INSERT_SELECT`, локальное и распределённое выполнение;
- реализованы история ClickHouse, сравнение запусков и тренды;
- добавлен мониторинг `Replicated*`, состояния реплик и ClickHouse Keeper;
- реализованы сценарии `REPLICATED_INSERT`, `REPLICATION_CATCHUP` и `REPLICA_CONSISTENCY`;
- добавлены подготовка ReplicatedMergeTree-таблиц и управляемый failover-тест с внешним внесением отказа;
- сохранена обратная совместимость существующих S3-тестов и API.

## Возможности

- корпоративный Web UI «ЭВО.СНТ S3»;
- разделы «Задания», «История», «Мониторинг», «Агенты», «Расписания» и «Настройки»;
- локальные и распределённые S3-тесты;
- локальные и распределённые ClickHouse-тесты;
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

PostgreSQL, Vault, Keycloak, S3 и ClickHouse являются внешними сервисами и не поднимаются приложением в production.

## Требования

| Компонент | Версия |
|---|---|
| Java | 21+ |
| PostgreSQL | 15–18 |
| Vault Community Edition | 2.0.x |
| Keycloak | 26+ |
| ClickHouse | HTTP(S) endpoint; версия определяется при discovery |
| Kubernetes | 1.28+ |
| Linux | systemd-based |
| S3 | AWS S3, MinIO и совместимые реализации |

## Быстрый запуск

```bash
export S3_PERF_BOOTSTRAP_FILE=/opt/s3perf/config/bootstrap-settings.json
export S3_PERF_BOOTSTRAP_KEY='replace-with-long-random-secret'
java -jar evo-snt-s3-2.2.3-rc1.jar
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
  ghcr.io/phibus-dev/s3-performance-test-web:2.2.3-rc1
```

Для проверки RC используйте фиксированный тег `2.2.3-rc1`. Для production без RC-функций используйте последний стабильный тег `2.2.2`.

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

## Сборка

```bash
mvn clean verify
java -jar target/s3-multipart-uploader-2.2.3-rc1.jar
```

Интеграционные тесты используют Testcontainers и требуют доступного Docker daemon.

## Обновление с 2.2.2

См. `UPGRADE_2.2.3-rc1.md`. При старте Coordinator Flyway применяет миграции V9–V13 для профилей, истории, репликации, сценариев и failover ClickHouse. До обновления сохраните bootstrap-файл и резервную копию PostgreSQL.

## Релиз

```bash
git tag -a v2.2.3-rc1 -m "ЭВО.СНТ 2.2.3-rc1"
git push origin v2.2.3-rc1
```

Release workflow публикует executable JAR, sources, Javadoc, CycloneDX SBOM, SHA256SUMS, README, release notes, upgrade guide и Docker image.
