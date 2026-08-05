package dev.phibus.s3.web;

import dev.phibus.s3.settings.ApplicationStateService;
import dev.phibus.s3.settings.BootstrapSettings;
import dev.phibus.s3.settings.ExternalServiceDiagnostics;
import dev.phibus.s3.settings.SettingsForm;
import dev.phibus.s3.settings.SettingsService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class SettingsController {
    private final SettingsService settingsService;
    private final ApplicationStateService stateService;

    public SettingsController(SettingsService settingsService, ApplicationStateService stateService) {
        this.settingsService = settingsService;
        this.stateService = stateService;
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        BootstrapSettings settings = settingsService.load();
        model.addAttribute("settings", settings);
        model.addAttribute("state", stateService.current());
        model.addAttribute("bootstrapPath", settingsService.bootstrapPath());
        model.addAttribute("encryptionReady", settingsService.encryptionReady());
        return "settings";
    }

    @PostMapping(path = "/api/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public SaveResult save(@Valid @RequestBody SettingsForm form, BindingResult validation) {
        if (validation.hasErrors()) {
            throw new IllegalArgumentException(validation.getAllErrors().getFirst().getDefaultMessage());
        }
        settingsService.save(form);
        return new SaveResult(true, "Настройки сохранены. Для применения PostgreSQL требуется перезапуск приложения.", stateService.current());
    }

    @PostMapping(path = "/api/settings/test/postgresql", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ExternalServiceDiagnostics.DiagnosticResult testPostgreSql(@RequestBody SettingsForm form) {
        return settingsService.testPostgreSql(form);
    }

    @PostMapping(path = "/api/settings/test/vault", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ExternalServiceDiagnostics.DiagnosticResult testVault(@RequestBody SettingsForm form) {
        return settingsService.testVault(form);
    }

    public record SaveResult(boolean saved, String message, ApplicationStateService.State state) {}
}
