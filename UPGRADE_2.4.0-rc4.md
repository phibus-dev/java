# Upgrade / test guide — 2.4.0-rc4

1. Сделайте резервную копию PostgreSQL конфигурационной БД приложения.
2. Разверните JAR или Docker image 2.4.0-rc4.
3. Убедитесь, что применены Kafka migrations V16-V20.
4. На странице Kafka Profile выберите security protocol.
5. Для `SASL_PLAINTEXT` или `SASL_SSL` выберите источник credentials:
   - `PLAIN` — username + пароль в профиле; требуется `S3_PERF_BOOTSTRAP_KEY`;
   - `ENVIRONMENT` — username + имя переменной окружения ОС с паролем;
   - `VAULT` — username + Vault secret path + имя поля пароля.
6. Для `PLAINTEXT`/`SSL` SASL credentials не требуются.
7. Сохраните профиль и нажмите «Проверить подключение» — должен возвращаться cluster/controller/nodes/topics без HTTP 500.
8. Проверьте Producer, Consumer & E2E, Replication & KRaft и Failover сценарии.
9. Проверьте distributed Kafka test на нескольких агентах.
10. Для старых профилей с credentials source `PROFILE` поддерживается обратная совместимость; при редактировании рекомендуется пересохранить их как `PLAIN`.

Подробные критерии: `KAFKA_M3_ACCEPTANCE.md` и `KAFKA_M4_ACCEPTANCE.md`.
