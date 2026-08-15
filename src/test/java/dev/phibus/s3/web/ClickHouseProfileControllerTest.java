package dev.phibus.s3.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.phibus.s3.clickhouse.ClickHouseProfileService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ClickHouseProfileControllerTest {
    @Test
    void deleteReturnsNoContentWithoutJsonBody() throws Exception {
        ClickHouseProfileService profiles = mock(ClickHouseProfileService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ClickHouseProfileController(profiles)).build();
        UUID id = UUID.randomUUID();

        mvc.perform(delete("/settings/clickhouse-profiles/api/{id}", id))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(profiles).delete(id);
    }
}
