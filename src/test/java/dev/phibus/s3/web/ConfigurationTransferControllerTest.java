package dev.phibus.s3.web;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.phibus.s3.settings.ConfigurationTransferService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConfigurationTransferControllerTest {
    @Test
    void exportAcceptsPasswordOnlyInPostBodyAndDisablesLegacyGetEndpoint() throws Exception {
        ConfigurationTransferService service = mock(ConfigurationTransferService.class);
        when(service.exportConfiguration(eq(true), eq(true), argThat(value ->
                value != null && "long-enough-export-password".contentEquals(new String(value)))))
                .thenReturn(new ConfigurationTransferService.ExportedConfiguration(
                        "payload".getBytes(StandardCharsets.UTF_8), "application/octet-stream", "config.evos3"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConfigurationTransferController(service)).build();

        mvc.perform(get("/api/config/export").param("password", "long-enough-export-password"))
                .andExpect(status().isMethodNotAllowed());

        mvc.perform(post("/api/config/export")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("includeSecrets", "true")
                        .param("encrypted", "true")
                        .param("password", "long-enough-export-password"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(service).exportConfiguration(eq(true), eq(true), argThat(value ->
                value != null && "long-enough-export-password".contentEquals(new String(value))));
    }
}
