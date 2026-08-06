package dev.phibus.s3.web;

import dev.phibus.s3.history.AdvancedHistoryStore;
import dev.phibus.s3.report.HistoryReportExportService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/history")
public class HistoryReportExportController {
    private final AdvancedHistoryStore historyStore;
    private final HistoryReportExportService reportService;

    public HistoryReportExportController(AdvancedHistoryStore historyStore,
                                         HistoryReportExportService reportService) {
        this.historyStore = historyStore;
        this.reportService = reportService;
    }

    @GetMapping(value = "/{id}/export/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> html(@PathVariable UUID id) {
        AdvancedHistoryStore.Detail detail = requireDetail(id);
        return download(reportService.html(detail), MediaType.TEXT_HTML,
                "evo-snt-s3-report-" + id + ".html");
    }

    @GetMapping(value = "/{id}/export/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        AdvancedHistoryStore.Detail detail = requireDetail(id);
        try {
            return download(reportService.pdf(detail), MediaType.APPLICATION_PDF,
                    "evo-snt-s3-report-" + id + ".pdf");
        } catch (HistoryReportExportService.ReportExportException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
    }

    private AdvancedHistoryStore.Detail requireDetail(UUID id) {
        AdvancedHistoryStore.Detail detail = historyStore.get(id);
        if (detail == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "History item not found");
        return detail;
    }

    private static ResponseEntity<byte[]> download(byte[] body, MediaType contentType, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.setContentLength(body.length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }
}
