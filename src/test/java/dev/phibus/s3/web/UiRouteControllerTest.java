package dev.phibus.s3.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

class UiRouteControllerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        InternalResourceViewResolver resolver = new InternalResourceViewResolver();
        resolver.setPrefix("/templates/");
        resolver.setSuffix(".html");
        mvc = MockMvcBuilders.standaloneSetup(new UiRouteController())
                .setViewResolvers(resolver)
                .build();
    }

    @Test
    void exposesHomeDashboard() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(forwardedUrl("/templates/home.html"));
    }

    @Test
    void exposesCanonicalSchedulesRoute() throws Exception {
        mvc.perform(get("/schedules"))
                .andExpect(status().isOk())
                .andExpect(view().name("schedules"))
                .andExpect(forwardedUrl("/templates/schedules.html"));
    }

    @Test
    void redirectsLegacyHtmlRoutes() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));
        mvc.perform(get("/history.html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/history"));
        mvc.perform(get("/agents.html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/agents"));
        mvc.perform(get("/distributed-tests.html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/distributed-tests"));
        mvc.perform(get("/schedules.html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/schedules"));
    }
}
