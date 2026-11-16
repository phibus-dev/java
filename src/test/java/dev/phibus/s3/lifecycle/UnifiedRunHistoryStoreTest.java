package dev.phibus.s3.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class UnifiedRunHistoryStoreTest {
    @Test
    @SuppressWarnings("unchecked")
    void largePageUsesLongOffsetWithoutIntegerOverflow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        UnifiedRunHistoryStore store = new UnifiedRunHistoryStore(jdbc);
        store.search(new UnifiedRunHistoryStore.Filter(
                null, null, null, null, null, null, Integer.MAX_VALUE, 200));

        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(contains("OFFSET ?"), any(RowMapper.class), parameters.capture());
        assertThat(parameters.getValue()).containsExactly(200, 429_496_729_400L);
    }
}
