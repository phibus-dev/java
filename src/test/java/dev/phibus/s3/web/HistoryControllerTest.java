package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import dev.phibus.s3.history.AdvancedHistoryStore;
import dev.phibus.s3.history.HistoryTrendService;
import dev.phibus.s3.lifecycle.RunScope;
import dev.phibus.s3.lifecycle.UnifiedRunHistoryStore;
import dev.phibus.s3.lifecycle.UnifiedRunRerunService;
import dev.phibus.s3.test.TestRunService;
import dev.phibus.s3.test.TestType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HistoryControllerTest {
    private final UnifiedRunHistoryStore history = mock(UnifiedRunHistoryStore.class);
    private final HistoryController controller = new HistoryController(mock(AdvancedHistoryStore.class),
            mock(TestRunService.class), mock(HistoryTrendService.class), history,
            mock(UnifiedRunRerunService.class));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    @Test
    void historyPageAcceptsUnifiedFiltersAndUtcDateTimeLocalValues() throws Exception {
        when(history.search(any())).thenReturn(new UnifiedRunHistoryStore.Page(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/history")
                        .param("scope", "local")
                        .param("testType", "kafka")
                        .param("from", "2026-11-11T10:15")
                        .param("to", "2026-11-11T11:30"))
                .andExpect(status().isOk())
                .andExpect(view().name("history"));

        ArgumentCaptor<UnifiedRunHistoryStore.Filter> filter =
                ArgumentCaptor.forClass(UnifiedRunHistoryStore.Filter.class);
        verify(history).search(filter.capture());
        assertThat(filter.getValue().scope()).isEqualTo(RunScope.LOCAL);
        assertThat(filter.getValue().testType()).isEqualTo(TestType.KAFKA);
        assertThat(filter.getValue().from()).isEqualTo(Instant.parse("2026-11-11T10:15:00Z"));
        assertThat(filter.getValue().to()).isEqualTo(Instant.parse("2026-11-11T11:30:00Z"));
    }

    @Test
    void historyPageRejectsInvalidFilterValues() throws Exception {
        mvc.perform(get("/history").param("scope", "remote"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/history").param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
    }
}
