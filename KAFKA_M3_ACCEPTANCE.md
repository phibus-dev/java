# Kafka 2.4.0-M3 Acceptance

## KRaft Health
- выбранный Kafka profile успешно получает clusterId, controllerId и broker topology;
- отсутствие controller переводит KRaft status в DEGRADED;
- ошибки подключения сохраняются/отображаются вызывающему API.

## Replication Test
- для topic фиксируются partitions, replicas, ISR, under-replicated partitions, offline partitions и min.insync.replicas violations;
- результат сохраняется в `kafka_m3_run`;
- runtime status меняется RUNNING -> COMPLETED/FAILED;
- ошибка сохраняется в history.

## Partition Scaling
- тест использует только временные topics вида `evo-m3-<run>-p<N>`;
- рабочий topic не изменяется;
- для каждой точки partitions фиксируются msg/s, MiB/s, duration и error count;
- replication factor автоматически ограничивается числом доступных brokers;
- временный topic удаляется после каждой точки;
- результаты сохраняются в history как JSON и доступны через `/api/kafka/m3/runs/{id}`.

## Security / UI
- запуск M3 API доступен ADMIN/OPERATOR через существующее правило `/api/kafka/**`;
- VIEWER может просматривать UI, но не запускать API;
- страница `/kafka/m3` использует общий корпоративный UI.
