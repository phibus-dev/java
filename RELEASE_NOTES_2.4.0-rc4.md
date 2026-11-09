# ЭВО.СНТ S3 2.4.0-rc4

Четвёртый release candidate версии 2.4.0 для тестирования Kafka-нагрузки.

## Изменения относительно rc3
- исправлена проверка подключения Kafka Profile после сохранения/редактирования профиля;
- исправлена backend-обработка SASL credentials;
- добавлена фактическая поддержка пароля, сохранённого в профиле;
- состав полей Kafka Profile теперь зависит от выбранного источника credentials;
- источники credentials приведены к понятной модели: `PLAIN`, `ENVIRONMENT`, `VAULT`;
- для `PLAIN` пароль задаётся в профиле и хранится зашифрованным;
- для `ENVIRONMENT` задаётся имя переменной окружения ОС;
- для `VAULT` задаются путь секрета и поле пароля;
- для `PLAINTEXT` и `SSL` блок SASL credentials скрывается;
- некорректная комбинация `SASL_* + NONE` больше не допускается;
- сохранена обратная совместимость со старыми профилями `PROFILE`;
- сохранены исправления Kafka UI, CSRF, Docker build и совместимости M1-M4 из предыдущих RC.

## Kafka 2.4.0
- Producer Load Test;
- Consumer Load Test и consumer lag;
- Producer→Consumer E2E и consistency;
- KRaft Health и Replication Test;
- Partition Scaling;
- Failover Scenario и оценка времени восстановления;
- distributed Kafka load и агрегирование показателей агентов.

## Тестирование
Используйте `KAFKA_M3_ACCEPTANCE.md` и `KAFKA_M4_ACCEPTANCE.md`.
