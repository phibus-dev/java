package dev.phibus.s3.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApplicationVersionProviderTest {

    @Test
    void usesConfiguredApplicationVersion() {
        assertThat(new ApplicationVersionProvider(" 2.4.0-rc12 ").version()).isEqualTo("2.4.0-rc12");
    }

    @Test
    void neverExposesLiteralNullAsVersion() {
        assertThat(new ApplicationVersionProvider("null").version()).isNotBlank().isNotEqualToIgnoringCase("null");
    }
}
