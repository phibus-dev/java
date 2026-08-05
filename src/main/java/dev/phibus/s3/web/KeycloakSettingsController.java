package dev.phibus.s3.web;

import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.KeycloakSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class KeycloakSettingsController {
    private final KeycloakSettingsService service;

    public KeycloakSettingsController(KeycloakSettingsService service) {
        this.service = service;
    }

    @GetMapping("/settings/keycloak")
    public String page(Model model) {
        model.addAttribute("keycloak", service.load());
        return "keycloak-settings";
    }

    @GetMapping("/api/settings/keycloak")
    @ResponseBody
    public BootstrapSettings.KeycloakSettings get() {
        return service.load();
    }

    @PostMapping("/api/settings/keycloak")
    @ResponseBody
    public SaveResult save(@RequestBody KeycloakSettingsService.KeycloakSettingsForm form) {
        service.save(form);
        return new SaveResult(true, "Настройки Keycloak сохранены. Для применения требуется перезапуск приложения.");
    }

    @PostMapping("/api/settings/keycloak/test")
    @ResponseBody
    public KeycloakSettingsService.ConnectionTest test(@RequestBody KeycloakSettingsService.KeycloakSettingsForm form) {
        return service.test(form);
    }

    public record SaveResult(boolean saved, String message) { }
}
