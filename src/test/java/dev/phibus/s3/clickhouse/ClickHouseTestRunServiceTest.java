package dev.phibus.s3.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class ClickHouseTestRunServiceTest {
    private final ClickHouseLoadTestEngine engine = mock(ClickHouseLoadTestEngine.class);
    private final ClickHouseConnectionProvider connections = mock(ClickHouseConnectionProvider.class);
    private final ClickHouseHistoryStore historyStore = mock(ClickHouseHistoryStore.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ClickHouseHistoryStore> history = mock(ObjectProvider.class);
    private final Executor directExecutor = Runnable::run;

    @Test
    void createsHistoryBeforeExecutionAndUpdatesItAfterCompletion() {
        ClickHouseTestRequest request = request();
        when(connections.endpoint(request.profileId(), request.endpoint())).thenReturn("http://clickhouse:8123");
        when(history.getIfAvailable()).thenReturn(historyStore);
        doAnswer(invocation -> {
            ClickHouseTestRun run = invocation.getArgument(0);
            run.start();
            run.operationCompleted(request.rowCount(), request.rowCount() * request.payloadBytes(), 10);
            run.complete();
            return null;
        }).when(engine).execute(any());

        ClickHouseTestRun run = service().create(request);

        ArgumentCaptor<ClickHouseTestRun.Snapshot> snapshots = ArgumentCaptor.forClass(ClickHouseTestRun.Snapshot.class);
        verify(historyStore, org.mockito.Mockito.times(2)).save(snapshots.capture(), org.mockito.Mockito.same(request));
        assertThat(snapshots.getAllValues()).extracting(ClickHouseTestRun.Snapshot::status)
                .containsExactly(ClickHouseTestRun.Status.QUEUED, ClickHouseTestRun.Status.COMPLETED);
        assertThat(run.snapshot().status()).isEqualTo(ClickHouseTestRun.Status.COMPLETED);
    }

    @Test
    void persistsEndpointResolutionFailureInHistory() {
        ClickHouseTestRequest request = request();
        when(history.getIfAvailable()).thenReturn(historyStore);
        when(connections.endpoint(request.profileId(), request.endpoint()))
                .thenThrow(new IllegalStateException("profile endpoint missing"));

        ClickHouseTestRun run = service().create(request);

        assertThat(run.snapshot().status()).isEqualTo(ClickHouseTestRun.Status.FAILED);
        assertThat(run.snapshot().errors()).isEqualTo(1);
        assertThat(run.snapshot().message()).contains("profile endpoint missing");
        verify(historyStore).save(org.mockito.ArgumentMatchers.argThat(snapshot ->
                        snapshot.status() == ClickHouseTestRun.Status.FAILED
                                && snapshot.message().contains("profile endpoint missing")),
                org.mockito.Mockito.same(request));
        verifyNoInteractions(engine);
    }

    @Test
    void persistsExecutorSubmissionFailureInHistory() {
        ClickHouseTestRequest request = request();
        when(connections.endpoint(request.profileId(), request.endpoint())).thenReturn("http://clickhouse:8123");
        when(history.getIfAvailable()).thenReturn(historyStore);
        Executor rejectingExecutor = command -> { throw new IllegalStateException("executor rejected task"); };

        ClickHouseTestRun run = new ClickHouseTestRunService(engine, connections, history, rejectingExecutor).create(request);

        assertThat(run.snapshot().status()).isEqualTo(ClickHouseTestRun.Status.FAILED);
        assertThat(run.snapshot().message()).contains("executor rejected task");
        verify(historyStore, org.mockito.Mockito.times(2)).save(any(), org.mockito.Mockito.same(request));
        verifyNoInteractions(engine);
    }

    @Test
    void doesNotStartTestWhenInitialHistoryCannotBeCreated() {
        ClickHouseTestRequest request = request();
        when(connections.endpoint(request.profileId(), request.endpoint())).thenReturn("http://clickhouse:8123");
        when(history.getIfAvailable()).thenReturn(historyStore);
        doThrow(new RuntimeException("database unavailable")).when(historyStore).save(any(), org.mockito.Mockito.same(request));

        assertThatThrownBy(() -> service().create(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test was not started");
        verifyNoInteractions(engine);
    }

    private ClickHouseTestRunService service() {
        return new ClickHouseTestRunService(engine, connections, history, directExecutor);
    }

    private static ClickHouseTestRequest request() {
        return new ClickHouseTestRequest(UUID.randomUUID(), null, "evo_snt_perf_load", "INSERT",
                2, 50, 100, 0, 0, 128, true);
    }
}
