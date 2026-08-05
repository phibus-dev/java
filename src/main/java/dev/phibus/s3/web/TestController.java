package dev.phibus.s3.web;

import dev.phibus.s3.test.PartResult;
import dev.phibus.s3.test.TestRequest;
import dev.phibus.s3.test.TestRun;
import dev.phibus.s3.test.TestRunService;
import dev.phibus.s3.test.TestStatus;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
public class TestController {
    private static final String DEVELOPMENT_VERSION = "2.1.0-SNAPSHOT";
    private final TestRunService service;
    public TestController(TestRunService service) { this.service = service; }

    @GetMapping({"/", "/tasks"})
    public String tasks(Model model) {
        model.addAttribute("applicationVersion", applicationVersion());
        return "index";
    }

    private static String applicationVersion() {
        String version = TestController.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? DEVELOPMENT_VERSION : version;
    }

    @PostMapping(path = "/api/tests", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseBody @ResponseStatus(HttpStatus.ACCEPTED)
    public TestRun.Snapshot create(@Valid @RequestBody TestRequest request) { return service.create(request).snapshot(); }
    @GetMapping("/api/tests") @ResponseBody public List<TestRun.Snapshot> list() { return service.list(); }
    @GetMapping("/api/tests/{id}") @ResponseBody public TestRun.Snapshot get(@PathVariable UUID id) { return service.get(id).snapshot(); }
    @PostMapping("/api/tests/{id}/cancel") @ResponseBody public TestRun.Snapshot cancel(@PathVariable UUID id) { service.cancel(id); return service.get(id).snapshot(); }
    @PostMapping(path = "/api/buckets", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseBody
    public List<String> buckets(@Valid @RequestBody BucketListRequest request) {
        return service.listBuckets(request.toTestRequest());
    }

    @GetMapping(path = "/api/tests/{id}/report.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TestRun.Snapshot> jsonReport(@PathVariable UUID id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"s3-test-" + id + ".json\"")
                .body(service.get(id).snapshot());
    }

    @GetMapping(path = "/api/tests/{id}/report.csv", produces = "text/csv")
    public ResponseEntity<byte[]> csvReport(@PathVariable UUID id) {
        TestRun.Snapshot run = service.get(id).snapshot();
        StringBuilder csv = new StringBuilder("object,part,bytes,duration_ms,speed_mib_s,status,etag,error\n");
        for (PartResult part : run.parts()) {
            csv.append(part.objectNumber()).append(',').append(part.partNumber()).append(',').append(part.bytes()).append(',')
                    .append(part.durationMillis()).append(',').append(String.format(java.util.Locale.ROOT, "%.3f", part.speedMiBps()))
                    .append(',').append(part.status()).append(',').append(quote(part.eTag())).append(',').append(quote(part.error())).append('\n');
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"s3-test-" + id + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String value) { return value == null ? "" : "\"" + value.replace("\"", "\"\"") + "\""; }

    @GetMapping(path = "/api/tests/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id) {
        SseEmitter emitter = new SseEmitter(0L);
        Thread.ofVirtual().name("sse-" + id).start(() -> stream(id, emitter));
        return emitter;
    }

    private void stream(UUID id, SseEmitter emitter) {
        try {
            while (true) {
                TestRun.Snapshot snapshot = service.get(id).snapshot();
                emitter.send(SseEmitter.event().name("progress").data(snapshot));
                if (snapshot.status() == TestStatus.COMPLETED || snapshot.status() == TestStatus.FAILED || snapshot.status() == TestStatus.CANCELLED) {
                    emitter.complete(); return;
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); emitter.completeWithError(e); }
        catch (IOException | RuntimeException e) { emitter.completeWithError(e); }
    }

    @ExceptionHandler(TestRunService.TestNotFoundException.class) @ResponseBody @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(RuntimeException error) { return error.getMessage(); }
}
