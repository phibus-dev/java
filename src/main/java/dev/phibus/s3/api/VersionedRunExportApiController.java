package dev.phibus.s3.api;

import dev.phibus.s3.lifecycle.UnifiedRunHistoryStore;
import dev.phibus.s3.lifecycle.UnifiedRunRerunService;
import dev.phibus.s3.report.UnifiedRunExportService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name = "s3perf.application-mode", havingValue = "COORDINATOR", matchIfMissing = true)
public class VersionedRunExportApiController {
    private final UnifiedRunHistoryStore history;
    private final UnifiedRunExportService exports;

    public VersionedRunExportApiController(UnifiedRunHistoryStore history, UnifiedRunExportService exports) {
        this.history = history;
        this.exports = exports;
    }

    @GetMapping("/run-history/{id}/export/{format}")
    public ResponseEntity<byte[]> export(@PathVariable UUID id, @PathVariable String format) {
        UnifiedRunHistoryStore.RunHistoryRow run = history.get(id);
        if (run == null) throw new UnifiedRunRerunService.RunHistoryNotFoundException(id);
        UnifiedRunExportService.Export report = exports.export(run, format);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(report.mediaType());
        headers.setContentLength(report.body().length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(report.filename(), StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(report.body(), headers, HttpStatus.OK);
    }
}
