package dev.phibus.s3.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class BootstrapSettingsStoreContextTest {

    @Test
    void createsStoreThroughSpringConstructorInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            Supplier<ObjectMapper> objectMapperSupplier = ObjectMapper::new;
            context.registerBean(ObjectMapper.class, objectMapperSupplier);
            context.register(BootstrapSettingsStore.class);
            context.refresh();

            BootstrapSettingsStore store = context.getBean(BootstrapSettingsStore.class);
            assertThat(store).isNotNull();
            assertThat(store.path()).isAbsolute();
        }
    }
}
