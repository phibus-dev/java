package dev.phibus.s3.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class CommonModelAttributes {
    private final ApplicationVersionProvider versions;

    public CommonModelAttributes(ApplicationVersionProvider versions) {
        this.versions = versions;
    }

    @ModelAttribute("applicationVersion")
    public String applicationVersion() {
        return versions.version();
    }
}
