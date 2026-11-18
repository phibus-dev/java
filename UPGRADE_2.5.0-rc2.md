# Upgrade / test guide — 2.5.0-rc2

`2.5.0-rc2` заменяет `2.5.0-rc1`: первый кандидат не запускается в режиме Agent из-за
coordinator-only зависимости в Spring-контексте. Не используйте rc1 для эталонного soak-теста.

## Подготовка

1. Не запускайте новые тесты и дождитесь завершения активных прогонов.
2. Сохраните bootstrap-файл, ключевые environment settings и полную резервную копию PostgreSQL.
3. Зафиксируйте текущие версии и image digests Coordinator/Agent для возможного отката.
4. Проверьте выделенный тестовый профиль, очистку тестовых объектов и локальную политику хранения
   приватного evidence.

## Обновление

1. Обновите Coordinator до `2.5.0-rc2` и запустите его с прежним `S3_PERF_BOOTSTRAP_KEY`.
2. При переходе с 2.4.0 дождитесь применения Flyway V25, V26 и V27. В
   `flyway_schema_history` последней должна быть V27; переход с rc1 не добавляет миграций.
3. Проверьте `/actuator/health/readiness`, `/actuator/prometheus` и доступность `/api/v1/openapi.yaml`.
4. Обновите все Agent до `2.5.0-rc2` и убедитесь, что они запускаются, регистрируются и сохраняют
   identity после рестарта Coordinator.
5. Проверьте общую `/history`, фильтры LOCAL/DISTRIBUTED, детали завершённого запуска и rerun.

## Автономный reference soak

Скачайте `evo-snt-offline-soak-2.5.0-rc2.tar.gz` на подключённой машине, проверьте его строку в
`SHA256SUMS` и передайте во внутренний контур разрешённым каналом. Подробная установка описана в
`M4_SOAK_TESTS.md` внутри архива.

Перед первым запуском:

1. задайте внутренний `EVO_SNT_URL` и CA bundle в `/etc/evo-snt/reference-soak.env`;
2. укажите существующий `profileId`, сценарий и workload в `/etc/evo-snt/reference-soak.json`;
3. поместите bearer token в `/etc/evo-snt/reference-soak.token` с владельцем `root:root` и mode
   `0600`;
4. проверьте units через `systemd-analyze verify`;
5. запустите `sudo systemctl start evo-snt-reference-soak.service` вручную.

Полный результат должен появиться в
`/var/lib/evo-snt-reference-soak/private/<UTC timestamp>`, а разрешённая сводка — в
`/var/lib/evo-snt-reference-soak/public/<UTC timestamp>`. Публичный каталог должен содержать только
`soak-summary.json` и `soak-summary.md`. Не переносите приватный каталог в GitHub artifacts.

После успешного ручного прогона включите расписание:

```bash
sudo systemctl enable --now evo-snt-reference-soak.timer
systemctl list-timers evo-snt-reference-soak.timer
```

По умолчанию тест стартует по воскресеньям в `01:23 UTC` (`04:23 MSK`) с задержкой до 15 минут.

## Проверки RC

1. Убедитесь, что release workflow опубликовал JAR, SBOM, checksums, offline bundle и Docker image.
2. Запустите `Post-release smoke` для `2.5.0-rc2` с expected migration `27` и durable registry
   enabled; проверка должна пройти этап запуска Agent и полный 180-секундный soak.
3. Выполните CLI flow `profiles → start → wait → export → assert` для каждого используемого движка.
4. Повторите один и тот же start с тем же idempotency key: должен вернуться прежний `runId`.
5. Выполните reference soak и проверьте per-window quality gates, drift и отсутствие идентификаторов
   во внешней сводке.
6. Перезапустите Coordinator во время распределённого теста и проверьте восстановление назначения,
   статуса и Agent identity.

## Откат

Остановите Coordinator и Agent, верните всем компонентам версию `2.4.0`, затем восстановите
PostgreSQL и bootstrap-файл из backup, созданного до применения V25–V27. Не запускайте 2.4.0 на
схеме, уже изменённой RC, без проверенной процедуры database rollback.
