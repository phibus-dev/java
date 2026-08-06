# Обновление ЭВО.СНТ S3 с 2.0.5 до 2.1.0

## 1. Резервное копирование

Остановите приложение и сохраните:

- bootstrap-файл, заданный `S3_PERF_BOOTSTRAP_FILE`;
- базу PostgreSQL приложения;
- конфигурацию reverse proxy, systemd, Docker или Kubernetes;
- пользовательские Grafana dashboards.

```bash
systemctl stop s3perf
cp /opt/s3perf/config/bootstrap-settings.json /opt/s3perf/config/bootstrap-settings.json.2.0.5.bak
pg_dump -Fc -d s3perf -f s3perf-2.0.5.dump
```

## 2. Проверка требований

- Java 21+;
- доступ к внешнему PostgreSQL;
- постоянное значение `S3_PERF_BOOTSTRAP_KEY`;
- права записи в каталог bootstrap-файла и `backups`;
- установленный Unicode TTF-шрифт для PDF либо заданный `S3PERF_REPORTS_PDF_FONT_PATH`.

## 3. Обновление JAR/systemd

```bash
cp evo-snt-s3-2.1.0.jar /opt/s3perf/bin/application.jar
chown s3perf:s3perf /opt/s3perf/bin/application.jar
systemctl start s3perf
journalctl -u s3perf -f
```

Flyway применит новые миграции автоматически. Не запускайте одновременно 2.0.5 и 2.1.0 с одной базой.

## 4. Docker

```bash
docker pull ghcr.io/phibus-dev/s3-performance-test-web:2.1.0
```

Сохраните прежний volume `/app/config` и тот же `S3_PERF_BOOTSTRAP_KEY`.

## 5. Kubernetes

Обновите image tag на `2.1.0`, не изменяя Secret с bootstrap key и PVC. Выполняйте rolling update только для stateless coordinator replicas, учитывая scheduler и активные тесты. Перед обновлением завершите текущие задания.

## 6. Vault AppRole

Для AppRole проверьте:

- auth mount;
- `role_id` и `secret_id`;
- политику read для KV v2 path;
- возможность token renewal, если token renewable;
- системный CA либо `vaultCaCertificatePath`.

Token mode остаётся поддерживаемым.

## 7. Конфигурационный экспорт

После обновления доступен `/settings/configuration`.

- открытый JSON всегда экспортируйте без секретов;
- для переноса секретов используйте `.evos3`;
- пароль `.evos3` должен содержать не менее 12 символов;
- храните пароль отдельно от файла;
- перед импортом приложение автоматически создаёт backup bootstrap-файла.

## 8. Проверка

```bash
curl -f http://127.0.0.1:8080/actuator/health/readiness
curl -f http://127.0.0.1:8080/actuator/prometheus
```

Проверьте Web UI, PostgreSQL, Vault, Keycloak, S3-профили, локальный и распределённый запуск, историю, тренды, HTML/PDF export и конфигурационный export/import.

## 9. Откат

1. Остановите 2.1.0.
2. Восстановите базу PostgreSQL из backup, если миграции уже применены.
3. Восстановите bootstrap-файл 2.0.5.
4. Верните JAR или Docker image `2.0.5`.
5. Запустите приложение и проверьте readiness.

Откат бинарного файла без восстановления базы после новых Flyway-миграций не рекомендуется.
