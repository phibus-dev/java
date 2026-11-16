package dev.phibus.s3.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.lifecycle.UnifiedRunHistoryStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

@Service
public class UnifiedRunExportService {
    private static final MediaType CSV = MediaType.parseMediaType("text/csv");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(ZoneId.systemDefault());
    private static final float MARGIN = 36;
    private static final float LINE_HEIGHT = 15;

    private final ObjectMapper objectMapper;
    private final String configuredFontPath;

    public UnifiedRunExportService(ObjectMapper objectMapper,
                                   @Value("${s3perf.reports.pdf-font-path:}") String configuredFontPath) {
        this.objectMapper = objectMapper;
        this.configuredFontPath = configuredFontPath == null ? "" : configuredFontPath.trim();
    }

    public Export export(UnifiedRunHistoryStore.RunHistoryRow run, String suppliedFormat) {
        if (run == null) throw new IllegalArgumentException("Run history entry is required");
        Format format = Format.parse(suppliedFormat);
        byte[] body = switch (format) {
            case JSON -> json(run);
            case CSV -> csv(run);
            case HTML -> html(run);
            case PDF -> pdf(run);
        };
        return new Export(body, format.mediaType, "evo-snt-report-" + run.id() + "." + format.extension);
    }

    private byte[] json(UnifiedRunHistoryStore.RunHistoryRow run) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(run);
        } catch (JsonProcessingException error) {
            throw new ReportExportException("Не удалось сформировать JSON-отчёт", error);
        }
    }

    private static byte[] csv(UnifiedRunHistoryStore.RunHistoryRow run) {
        List<String> headers = List.of("id", "testType", "scope", "status", "createdAt", "startedAt",
                "finishedAt", "durationMillis", "name", "operation", "target", "initiator",
                "completedOperations", "rowsProcessed", "bytesTransferred", "operationsPerSecond",
                "throughputMiBps", "p50LatencyMs", "p95LatencyMs", "p99LatencyMs", "errors",
                "stopReason", "message", "participants", "latencyScope", "rerunnable");
        List<Object> values = List.of(run.id(), run.testType(), run.scope(), run.status(),
                value(run.createdAt()), value(run.startedAt()), value(run.finishedAt()), run.durationMillis(),
                value(run.name()), value(run.operation()), value(run.target()), value(run.initiator()),
                run.completedOperations(), run.rowsProcessed(), run.bytesTransferred(),
                number(run.operationsPerSecond()), number(run.throughputMiBps()), number(run.p50LatencyMs()),
                number(run.p95LatencyMs()), number(run.p99LatencyMs()), run.errors(),
                value(run.stopReason()), value(run.message()), run.participants(), value(run.latencyScope()),
                run.rerunnable());
        StringBuilder out = new StringBuilder(1024);
        appendCsvRow(out, headers);
        appendCsvRow(out, values);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] html(UnifiedRunHistoryStore.RunHistoryRow run) {
        StringBuilder out = new StringBuilder(4096);
        out.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>ЭВО.СНТ — отчёт ").append(escape(run.id().toString())).append("</title>")
                .append("<style>body{font-family:Arial,sans-serif;color:#17233c;margin:32px}")
                .append("h1{color:#00539b}.brand{border-bottom:4px solid #d71920;padding-bottom:12px}")
                .append("table{width:100%;border-collapse:collapse;margin-top:20px}")
                .append("th,td{border:1px solid #cbd5e1;padding:8px;text-align:left}th{background:#e8f0f8}</style>")
                .append("</head><body><header class=\"brand\"><h1>ЭВО.СНТ</h1>")
                .append("<p>Унифицированный отчёт о нагрузочном тестировании</p></header><table><tbody>");
        row(out, "ID", run.id());
        row(out, "Тип / область", run.testType() + " / " + run.scope());
        row(out, "Статус", run.status());
        row(out, "Название", run.name());
        row(out, "Операция", run.operation());
        row(out, "Цель", run.target());
        row(out, "Начат", run.startedAt());
        row(out, "Завершён", run.finishedAt());
        row(out, "Продолжительность, ms", run.durationMillis());
        row(out, "Операций", run.completedOperations());
        row(out, "Строк", run.rowsProcessed());
        row(out, "Байт", run.bytesTransferred());
        row(out, "Операций/с", number(run.operationsPerSecond()));
        row(out, "MiB/s", number(run.throughputMiBps()));
        row(out, "p50 / p95 / p99, ms", number(run.p50LatencyMs()) + " / "
                + number(run.p95LatencyMs()) + " / " + number(run.p99LatencyMs()));
        row(out, "Ошибки", run.errors());
        row(out, "Участники", run.participants());
        row(out, "Область latency", run.latencyScope());
        row(out, "Причина завершения", run.stopReason());
        row(out, "Сообщение", run.message());
        out.append("</tbody></table><footer><small>Секреты и credentials в отчёт не включаются.</small>")
                .append("</footer></body></html>");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] pdf(UnifiedRunHistoryStore.RunHistoryRow run) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDFont font = PDType0Font.load(document, resolveFont().toFile());
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN;
                for (String line : pdfLines(run)) {
                    stream.beginText();
                    stream.setFont(font, line.startsWith("# ") ? 16 : line.startsWith("## ") ? 12 : 9);
                    stream.newLineAtOffset(MARGIN, y);
                    stream.showText(sanitize(line.replaceFirst("^#{1,2}\\s+", "")));
                    stream.endText();
                    y -= line.startsWith("# ") ? 24 : line.startsWith("## ") ? 20 : LINE_HEIGHT;
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new ReportExportException("Не удалось сформировать PDF-отчёт", error);
        }
    }

    private static List<String> pdfLines(UnifiedRunHistoryStore.RunHistoryRow run) {
        List<String> lines = new ArrayList<>();
        lines.add("# ЭВО.СНТ — отчёт о нагрузочном тестировании");
        lines.add("## Запуск");
        lines.add("ID: " + run.id());
        lines.add("Тип: " + run.testType() + "    Область: " + run.scope() + "    Статус: " + run.status());
        lines.add("Название: " + value(run.name()));
        lines.add("Операция: " + value(run.operation()) + "    Цель: " + value(run.target()));
        lines.add("Начат: " + format(run.startedAt()) + "    Завершён: " + format(run.finishedAt()));
        lines.add("Продолжительность: " + run.durationMillis() + " ms    Участники: " + run.participants());
        lines.add("## Результаты");
        lines.add("Операций: " + run.completedOperations() + "    Строк: " + run.rowsProcessed()
                + "    Байт: " + run.bytesTransferred());
        lines.add("Операций/с: " + number(run.operationsPerSecond())
                + "    Скорость: " + number(run.throughputMiBps()) + " MiB/s");
        lines.add("Latency " + value(run.latencyScope()) + " p50/p95/p99: "
                + number(run.p50LatencyMs()) + " / " + number(run.p95LatencyMs()) + " / "
                + number(run.p99LatencyMs()) + " ms");
        lines.add("Ошибки: " + run.errors() + "    Причина: " + value(run.stopReason()));
        if (run.message() != null && !run.message().isBlank()) {
            lines.add("Сообщение: " + run.message());
        }
        lines.add("Секреты и credentials в отчёт не включаются.");
        return lines;
    }

    private Path resolveFont() throws IOException {
        List<Path> candidates = new ArrayList<>();
        if (!configuredFontPath.isBlank()) candidates.add(Path.of(configuredFontPath));
        candidates.add(Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"));
        candidates.add(Path.of("/usr/share/fonts/dejavu/DejaVuSans.ttf"));
        candidates.add(Path.of("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"));
        candidates.add(Path.of("/Library/Fonts/Arial Unicode.ttf"));
        candidates.add(Path.of("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"));
        candidates.add(Path.of("C:/Windows/Fonts/arial.ttf"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) return candidate;
        }
        throw new IOException("Не найден Unicode TTF-шрифт");
    }

    private static void appendCsvRow(StringBuilder out, List<?> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) out.append(',');
            String text = String.valueOf(values.get(index));
            out.append('"').append(text.replace("\"", "\"\"")).append('"');
        }
        out.append('\n');
    }

    private static void row(StringBuilder out, String label, Object value) {
        out.append("<tr><th>").append(escape(label)).append("</th><td>")
                .append(escape(value(value))).append("</td></tr>");
    }

    private static String format(java.time.Instant value) {
        return value == null ? "—" : DATE_TIME.format(value);
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String sanitize(String value) {
        String normalized = value(value).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return normalized.length() <= 150 ? normalized : normalized.substring(0, 147) + "...";
    }

    private static String escape(Object value) {
        return value(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private enum Format {
        JSON("json", MediaType.APPLICATION_JSON),
        CSV("csv", UnifiedRunExportService.CSV),
        HTML("html", MediaType.TEXT_HTML),
        PDF("pdf", MediaType.APPLICATION_PDF);

        private final String extension;
        private final MediaType mediaType;

        Format(String extension, MediaType mediaType) {
            this.extension = extension;
            this.mediaType = mediaType;
        }

        private static Format parse(String value) {
            if (value == null) throw new IllegalArgumentException("Export format is required");
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Unsupported export format: " + value, error);
            }
        }
    }

    public record Export(byte[] body, MediaType mediaType, String filename) { }

    public static final class ReportExportException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ReportExportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
