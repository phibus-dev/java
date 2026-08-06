package dev.phibus.s3.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class BootstrapSettingsStoreContextTest {

    @Test
    void createsStoreThroughSpringConstructorInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, ObjectMapper::new);
            context.register(BootstrapSettingsStore.class);
            context.refresh();

            BootstrapSettingsStore store = context.getBean(BootstrapSettingsStore.class);
            assertThat(store).isNotNull();
            assertThat(store.path()).isAbsolute();
        }
    }
}
