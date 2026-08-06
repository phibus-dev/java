# ЭВО.СНТ S3

Java 21 / Spring Boot приложение для функционального, нагрузочного, распределённого и регрессионного тестирования AWS S3, MinIO и других S3-совместимых объектных хранилищ.

> **Текущий стабильный релиз:** `2.2.0`  
> **Статус:** Stable / Production Ready  
> **Ветка релиза:** `release/2.2.0`

## Что нового в 2.2.0

- усилен bootstrap-режим: отсутствующий, пустой или содержащий пробелы `bootstrap-settings.json` больше не блокирует запуск;
- добавлены резервные копии bootstrap-конфигурации и безопасная атомарная запись;
- унифицированы Web UI маршруты без обязательного суффикса `.html`;
- исправлена CSP-совместимость страницы агентов и зависавшее состояние «Загрузка…»;
- получение S3-бакетов переведено на сортированный выпадающий список без автоматического выбора первого элемента;
- переработан единый центр настроек и диагностика PostgreSQL/Vault;
- добавлен раздел `/monitoring` с Health Dashboard базовых компонентов;
- добавлены локальные сохранённые задания без хранения Access Key и Secret Key.

## Возможности

- корпоративный Web UI «ЭВО.СНТ S3»;
- разделы «Задания», «История», «Мониторинг», «Агенты», «Расписания» и «Настройки»;
- локальные и распределённые S3-тесты;
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
                                 S3 API
                                   |
                              S3 / MinIO
```

PostgreSQL, Vault, Keycloak и S3 являются внешними сервисами и не поднимаются приложением в production.

## Требования

| Компонент | Версия |
|---|---|
| Java | 21+ |
| PostgreSQL | 15–18 |
| Vault Community Edition | 2.0.x |
| Keycloak | 26+ |
| Kubernetes | 1.28+ |
| Linux | systemd-based |
| S3 | AWS S3, MinIO и совместимые реализации |

## Быстрый запуск

```bash
export S3_PERF_BOOTSTRAP_FILE=/opt/s3perf/config/bootstrap-settings.json
export S3_PERF_BOOTSTRAP_KEY='replace-with-long-random-secret'
java -jar evo-snt-s3-2.2.0.jar
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
  ghcr.io/phibus-dev/s3-performance-test-web:2.2.0
```

Для production используйте фиксированный тег `2.2.0`.

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
java -jar target/s3-multipart-uploader-2.2.0.jar
```

## Обновление с 2.1.0

См. `UPGRADE_2.2.0.md`. Миграция PostgreSQL не требуется. До обновления сохраните bootstrap-файл и резервную копию PostgreSQL.

## Релиз

```bash
git tag -a v2.2.0 -m "ЭВО.СНТ S3 2.2.0"
git push origin v2.2.0
```

Release workflow публикует executable JAR, sources, Javadoc, CycloneDX SBOM, SHA256SUMS, README, release notes, upgrade guide и Docker image.
