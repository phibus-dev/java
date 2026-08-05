# S3 Performance Test Web

Java 21 / Spring Boot приложение для функционального, нагрузочного, распределённого и регрессионного тестирования AWS S3, MinIO и других S3-совместимых объектных хранилищ.

> **Стабильный релиз:** `2.0.5`  
> **Статус:** Stable / Production Ready  
> **Ветка сопровождения:** `release/2.0.x`  
> **Текущая ветка разработки:** `main` (`2.1.0-SNAPSHOT`)

## Статус версии 2.0.5

Версия **2.0.5** зафиксирована как первый стабильный промышленный patch-релиз приложения. Релиз прошёл ручную приёмочную проверку, в ходе которой неполадок в реализованных функциях не выявлено.

Проверены:

- первый запуск и bootstrap mode;
- сохранение bootstrap-настроек;
- подключение к внешнему PostgreSQL и применение Flyway-миграций;
- запуск JAR непосредственно и через `systemd`;
- настройка внешнего HashiCorp Vault;
- Vault Token и AppRole;
- управление S3-профилями;
- использование `profileId` при запуске теста;
- получение списка S3-бакетов;
- CSRF-защита Web UI;
- локальные и распределённые тесты;
- регистрация агентов, heartbeat и агрегация статистики;
- история, scheduler, baseline и регрессия производительности;
- аудит, Prometheus и Grafana.

Новые функции разрабатываются в `main` с версией `2.1.0-SNAPSHOT`. Исправления для стабильной линии 2.0 выпускаются из ветки `release/2.0.x`.

## Основные возможности

- Web UI и REST API;
- режимы Coordinator и Agent;
- распределённое выполнение тестов;
- несколько S3-профилей во внешнем PostgreSQL;
- автоматическое применение endpoint, region, bucket, path-style и Vault path выбранного профиля;
- операции UPLOAD/PUT, DOWNLOAD/GET, HEAD, LIST, DELETE, LIFECYCLE и MIXED;
- multipart upload и параллельная обработка;
- выполнение по числу операций или времени;
- warm-up, ограничение OPS и отдельные потоки по операциям;
- throughput, OPS, объём, ошибки и p50/p95/p99 latency;
- история, экспорт JSON/CSV и повторный запуск;
- baseline и обнаружение регрессии;
- постоянный scheduler в PostgreSQL;
- Vault Token и AppRole;
- опциональный Keycloak OIDC и RBAC;
- аудит безопасности в PostgreSQL;
- Prometheus, Grafana, liveness и readiness;
- CSRF, security headers, CodeQL, SpotBugs, JaCoCo и CycloneDX SBOM.

## Архитектура

```text
Web browser / REST client
          |
        HTTPS
          |
+----------------------------------+
| S3 Performance Test Coordinator  |
| Web UI / REST API                |
| Scheduler / History / Baseline   |
| Audit / RBAC                     |
| Distributed Test Control         |
+----------------------------------+
     |          |           |
   JDBC       HTTPS       Agent API
     |          |           |
PostgreSQL   Vault /     Agent 1..N
             Keycloak        |
                           S3 API
                              |
                       S3 / MinIO
```

PostgreSQL, Vault, Keycloak и S3/MinIO являются внешними сервисами и не поднимаются приложением в production.

## Поддерживаемые компоненты

| Компонент | Поддерживаемая версия |
|---|---|
| Java | 21+ |
| PostgreSQL | 15–18 |
| HashiCorp Vault Community Edition | 2.0.x |
| Docker | актуальная стабильная версия |
| Kubernetes | 1.28+ |
| Linux | дистрибутив с `systemd` |
| S3 API | AWS S3, MinIO и совместимые реализации |

## Быстрый запуск стабильной версии

```bash
export S3_PERF_BOOTSTRAP_FILE=/opt/s3perf/config/bootstrap-settings.json
export S3_PERF_BOOTSTRAP_KEY='replace-with-long-random-secret'
java -jar s3-performance-test-web-2.0.5.jar
```

При первом запуске откройте:

```text
http://localhost:8080/settings
```

Без настроенного PostgreSQL приложение работает в bootstrap mode. После сохранения параметров внешнего PostgreSQL перезапустите приложение.

## Bootstrap mode

В bootstrap mode доступны:

```text
/settings
/api/settings/**
/actuator/health/**
/static/**
```

После подключения PostgreSQL, применения миграций и перезапуска приложение переходит в normal mode.

## PostgreSQL

Пример JDBC URL:

```text
jdbc:postgresql://postgres.example.org:5432/s3perf?sslmode=require
```

В PostgreSQL хранятся:

- история и результаты тестов;
- scheduler;
- baseline и результаты сравнения;
- S3-профили без секретных ключей;
- агенты и распределённые задания;
- аудит безопасности.

## S3-профили

Управление профилями доступно на:

```text
/settings/s3-profiles
```

Профиль содержит endpoint, region, bucket, path-style, источник credentials и Vault secret path. При передаче `profileId` S3 engine автоматически использует параметры выбранного профиля. Если `profileId` отсутствует, используется профиль по умолчанию.

S3 access key, secret key и session token не сохраняются в PostgreSQL.

## HashiCorp Vault

Поддерживаются методы:

```text
TOKEN
APPROLE
```

Настраиваются Vault address, auth mount, Token либо Role ID/Secret ID, KV mount, secret prefix, TLS verification и CA certificate.

## Keycloak и роли

Keycloak OIDC включается опционально. Поддерживаются OAuth2 Login для Web UI и JWT Resource Server для REST API.

| Роль | Права |
|---|---|
| `ADMIN` | Настройки, профили, аудит и все операции |
| `OPERATOR` | Запуск/остановка тестов, scheduler и distributed tests |
| `VIEWER` | Просмотр интерфейса, истории и результатов |

## Основные страницы

```text
/                         тестирование
/history.html             история
/schedules.html           scheduler
/agents.html              агенты
/distributed-tests.html   распределённые тесты
/audit.html               аудит
/settings                 bootstrap-настройки
/settings/s3-profiles     S3-профили
/settings/keycloak        Keycloak
```

## Сборка

Стабильная версия находится в `release/2.0.x`:

```bash
git checkout release/2.0.x
mvn clean verify
java -jar target/s3-multipart-uploader-2.0.5.jar
```

Текущая разработка:

```bash
git checkout main
mvn clean verify
```

Артефакт из `main` имеет версию `2.1.0-SNAPSHOT` и не должен заменять стабильную 2.0.5 в production без отдельной приёмки.

## Docker

```bash
docker run --rm -p 8080:8080 \
  -e S3_PERF_BOOTSTRAP_KEY='replace-with-long-random-secret' \
  -e S3_PERF_BOOTSTRAP_FILE=/app/config/bootstrap-settings.json \
  -v "$PWD/config:/app/config" \
  ghcr.io/phibus-dev/s3-performance-test-web:2.0.5
```

Для production фиксируйте тег `2.0.5`, а не `latest`.

## Kubernetes

Рекомендуется использовать:

- отдельный Deployment Coordinator;
- отдельный Deployment или StatefulSet для Agent;
- HTTPS Ingress;
- Secret для `S3_PERF_BOOTSTRAP_KEY`;
- PVC для bootstrap-файла;
- внешние PostgreSQL, Vault, Keycloak и S3;
- readiness probe `/actuator/health/readiness`;
- liveness probe `/actuator/health/liveness`.

## Systemd

Пример ключевых директив:

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

## Health и Prometheus

```text
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /actuator/prometheus
```

Основные пользовательские метрики:

```text
s3_test_active
s3_test_runs
s3_test_transferred_bytes
s3_test_throughput_mibps
s3_test_operations_per_second
s3_test_errors
s3_test_p95_latency_milliseconds
s3_agents_online
s3_agents_busy
s3_agents_offline
```

Grafana dashboard:

```text
deploy/grafana/s3-performance-dashboard.json
```

Prometheus scrape config:

```text
deploy/prometheus/s3-performance-scrape.yml
```

## Ветвление и сопровождение

- `release/2.0.x` — стабильная линия 2.0.5 и будущие patch-исправления 2.0.x;
- `main` — разработка версии 2.1.0-SNAPSHOT;
- production-развёртывания должны использовать release artifact или Docker image с фиксированным тегом;
- изменения из `main` переносятся в стабильную ветку только выборочным backport после проверки.

## CI/CD и безопасность поставки

CI выполняет:

- Maven unit и integration tests;
- Testcontainers-проверки;
- JaCoCo;
- SpotBugs;
- CodeQL;
- Dependency Review;
- CycloneDX SBOM;
- сборку JAR и Docker image.

Релиз включает исполняемый JAR, sources, Javadoc, SBOM, SHA256SUMS, README, release notes и upgrade guide.
