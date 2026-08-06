package dev.phibus.s3.web;

import dev.phibus.s3.settings.ConfigurationTransferService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class ConfigurationTransferController {
    private final ConfigurationTransferService service;

    public ConfigurationTransferController(ConfigurationTransferService service) {
        this.service = service;
    }

    @GetMapping("/settings/configuration")
    public String page() { return "settings-configuration"; }

    @GetMapping("/api/config/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "false") boolean includeSecrets,
                                         @RequestParam(defaultValue = "false") boolean encrypted,
                                         @RequestParam(required = false) String password) {
        ConfigurationTransferService.ExportedConfiguration result = service.exportConfiguration(
                includeSecrets, encrypted, chars(password));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(result.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment().filename(result.filename(), StandardCharsets.UTF_8).build());
        headers.setCacheControl(CacheControl.noStore());
        return ResponseEntity.ok().headers(headers).body(result.content());
    }

    @PostMapping(value = "/api/config/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ConfigurationTransferService.ValidationResult validate(@RequestParam("file") MultipartFile file,
                                                                    @RequestParam(required = false) String password)
            throws java.io.IOException {
        return service.validate(file.getBytes(), chars(password));
    }

    @PostMapping(value = "/api/config/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ConfigurationTransferService.ImportResult importConfiguration(@RequestParam("file") MultipartFile file,
                                                                          @RequestParam(required = false) String password)
            throws java.io.IOException {
        return service.importConfiguration(file.getBytes(), chars(password));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    private static char[] chars(String value) { return value == null ? null : value.toCharArray(); }
}
