package dev.phibus.s3.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ApplicationVersionProvider {
    private final String configuredVersion;

    public ApplicationVersionProvider(@Value("${info.app.version:}") String configuredVersion) {
        this.configuredVersion = configuredVersion;
    }

    public String version() {
        if (configuredVersion != null && !configuredVersion.isBlank() && !"null".equalsIgnoreCase(configuredVersion)) {
            return configuredVersion.trim();
        }
        String implementationVersion = ApplicationVersionProvider.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank() ? "dev" : implementationVersion;
    }
}
