package dev.phibus.s3.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BootstrapModeInterceptorTest {
    private ApplicationStateService stateService;
    private BootstrapModeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        stateService = mock(ApplicationStateService.class);
        when(stateService.current()).thenReturn(ApplicationStateService.State.CONFIGURATION_REQUIRED);
        interceptor = new BootstrapModeInterceptor(stateService);
    }

    @Test
    void allowsSettingsShellAssetsAndSessionMetadataDuringBootstrap() throws Exception {
        List<String> paths = List.of(
                "/settings",
                "/api/settings",
                "/api/session",
                "/application.css",
                "/app.css",
                "/app-ui.css",
                "/app-ui.js",
                "/settings.js",
                "/favicon.svg",
                "/manifest.webmanifest",
                "/transneft-logo.svg");

        for (String path : paths) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThat(interceptor.preHandle(request, response, new Object())).as(path).isTrue();
        }
    }

    @Test
    void redirectsOtherPagesToSettingsDuringBootstrap() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/history");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getRedirectedUrl()).isEqualTo("/settings");
    }

    @Test
    void rejectsOtherApiRequestsWithServiceUnavailableDuringBootstrap() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/runs");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("PostgreSQL is not configured");
    }
}
