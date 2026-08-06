# ЭВО.СНТ S3 2.2.1

Patch-релиз исправляет аварийное завершение приложения 2.2.0 при создании Spring bean `BootstrapSettingsStore`.

## Исправлено

- Spring теперь однозначно использует конструктор `BootstrapSettingsStore(ObjectMapper)`;
- устранена ошибка запуска `No default constructor found`;
- восстановлено создание цепочки зависимостей `SecurityAuditFilter -> SettingsService -> BootstrapSettingsStore`;
- добавлен regression-тест создания компонента через реальный Spring ApplicationContext;
- исправлена неоднозначная перегрузка `registerBean` в regression-тесте.

## Совместимость

- полная совместимость с конфигурацией и данными 2.2.0;
- миграция PostgreSQL не требуется;
- формат `bootstrap-settings.json` не изменён.

Пользователям версии 2.2.0 рекомендуется обновиться до 2.2.1.
