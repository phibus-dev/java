# Обновление ЭВО.СНТ S3 до 2.2.0

## Поддерживаемый путь

Обновление выполняется с версии 2.1.0 на 2.2.0.

## Перед обновлением

1. Остановите Coordinator и агенты.
2. Создайте резервную копию PostgreSQL.
3. Скопируйте `bootstrap-settings.json` и каталог `config/backups`.
4. Сохраните значение `S3_PERF_BOOTSTRAP_KEY` без изменений.
5. Зафиксируйте текущие параметры systemd, Docker или Kubernetes.

## Обновление JAR

```bash
cp evo-snt-s3-2.2.0.jar /opt/s3perf/bin/application.jar
systemctl restart evo-snt-s3
systemctl status evo-snt-s3
```

## Обновление Docker

```bash
docker pull ghcr.io/phibus-dev/s3-performance-test-web:2.2.0
```

Пересоздайте контейнер с прежними volume и environment variables.

## Обновление Kubernetes

Измените image tag на:

```text
ghcr.io/phibus-dev/s3-performance-test-web:2.2.0
```

Проверьте rollout, readiness и liveness probes.

## Проверки после обновления

- `/settings` открывается и показывает текущую конфигурацию;
- `/monitoring` возвращает карточки компонентов;
- `/api/health/overview` отвечает HTTP 200;
- список бакетов отображается как выпадающий список;
- `/agents` не остаётся в состоянии «Загрузка…»;
- старые URL с `.html` перенаправляются;
- запуск локального и распределённого теста работает;
- HTML/PDF отчёты, API v2 и Grafana dashboards доступны.

## Данные и миграции

Дополнительная миграция PostgreSQL для обновления с 2.1.0 не требуется. Локальные сохранённые задания хранятся в `localStorage` браузера и не синхронизируются между рабочими местами.

## Откат

1. Остановите 2.2.0.
2. Верните JAR или image tag 2.1.0.
3. При необходимости восстановите bootstrap-файл из backup.
4. Запустите приложение и выполните smoke-test.

Не запускайте версии 2.1.0 и 2.2.0 одновременно с одним экземпляром bootstrap-файла.
