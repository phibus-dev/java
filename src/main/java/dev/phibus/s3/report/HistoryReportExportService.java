package dev.phibus.s3.report;

import dev.phibus.s3.history.AdvancedHistoryStore;
import dev.phibus.s3.test.PartResult;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import org.springframework.stereotype.Service;

@Service
public class HistoryReportExportService {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withLocale(Locale.forLanguageTag("ru-RU")).withZone(ZoneId.systemDefault());
    private static final float MARGIN = 36;
    private static final float LINE_HEIGHT = 15;

    private final String configuredFontPath;

    public HistoryReportExportService(@Value("${s3perf.reports.pdf-font-path:}") String configuredFontPath) {
        this.configuredFontPath = configuredFontPath == null ? "" : configuredFontPath.trim();
    }

    public byte[] html(AdvancedHistoryStore.Detail detail) {
        AdvancedHistoryStore.RunRow run = detail.run();
        StringBuilder out = new StringBuilder(8192);
        out.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>ЭВО.СНТ — отчёт ").append(escape(run.id().toString())).append("</title>")
                .append("<style>body{font-family:Arial,sans-serif;color:#17233c;margin:32px}h1{color:#00539b}")
                .append(".brand{border-bottom:4px solid #d71920;padding-bottom:12px;margin-bottom:24px}")
                .append(".grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.card{border:1px solid #cbd5e1;padding:12px}")
                .append("table{width:100%;border-collapse:collapse;margin-top:20px;font-size:12px}th,td{border:1px solid #cbd5e1;padding:6px;text-align:left}")
                .append("th{background:#e8f0f8}.ok{color:#087f23}.error{color:#b00020}@media print{body{margin:10mm}.no-print{display:none}}</style></head><body>")
                .append("<header class=\"brand\"><h1>ЭВО.СНТ S3</h1><p>ТПП ЭВОПЛАТФОРМА — отчёт о нагрузочном тестировании</p></header>")
                .append("<h2>Запуск ").append(escape(run.id().toString())).append("</h2><div class=\"grid\">");
        card(out, "Операция", run.operation());
        card(out, "Статус", run.status());
        card(out, "Создан", format(run.createdAt()));
        card(out, "Средняя скорость", number(run.averageSpeedMiBps()) + " MiB/s");
        card(out, "p50 / p95 / p99", number(run.p50LatencyMs()) + " / " + number(run.p95LatencyMs()) + " / " + number(run.p99LatencyMs()) + " ms");
        card(out, "Ошибки", Integer.toString(run.failedParts()));
        card(out, "Передано", number(run.bytesTransferred() / 1048576.0) + " MiB");
        card(out, "Продолжительность", duration(run));
        card(out, "Очистка", run.cleanupSuccessful() ? "Успешно" : "Не выполнена / ошибка");
        out.append("</div><h2>Параметры</h2><table><tbody>");
        row(out, "Endpoint", run.endpoint()); row(out, "Bucket", run.bucket()); row(out, "Region", run.region());
        row(out, "Object key", run.objectKey()); row(out, "Размер объекта", run.objectSizeMiB() + " MiB");
        row(out, "Размер части", run.partSizeMiB() + " MiB"); row(out, "Parallelism", Integer.toString(run.parallelism()));
        row(out, "Количество объектов", Integer.toString(run.objectCount())); row(out, "Удалять после теста", Boolean.toString(run.deleteAfterTest()));
        if (run.message() != null && !run.message().isBlank()) row(out, "Сообщение", run.message());
        out.append("</tbody></table><h2>Операции / части</h2><table><thead><tr><th>Объект</th><th>Часть</th><th>Байты</th><th>Длительность, ms</th><th>Скорость, MiB/s</th><th>Статус</th><th>Ошибка</th></tr></thead><tbody>");
        for (PartResult part : detail.parts()) {
            out.append("<tr><td>").append(part.objectNumber()).append("</td><td>").append(part.partNumber())
                    .append("</td><td>").append(part.bytes()).append("</td><td>").append(part.durationMillis())
                    .append("</td><td>").append(number(part.speedMiBps())).append("</td><td>").append(escape(part.status()))
                    .append("</td><td>").append(escape(part.error())).append("</td></tr>");
        }
        out.append("</tbody></table><p class=\"no-print\"><button onclick=\"window.print()\">Печать</button></p>")
                .append("<footer><small>Сформировано ").append(escape(DATE_TIME.format(java.time.Instant.now())))
                .append(". Секретные данные и credentials в отчёт не включаются.</small></footer></body></html>");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] pdf(AdvancedHistoryStore.Detail detail) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDFont font = PDType0Font.load(document, resolveFont().toFile());
            List<String> lines = pdfLines(detail);
            PDPage page = null;
            PDPageContentStream stream = null;
            float y = 0;
            try {
                for (String line : lines) {
                    if (stream == null || y < MARGIN + LINE_HEIGHT) {
                        if (stream != null) stream.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        stream = new PDPageContentStream(document, page);
                        y = page.getMediaBox().getHeight() - MARGIN;
                    }
                    stream.beginText();
                    stream.setFont(font, line.startsWith("# ") ? 16 : line.startsWith("## ") ? 12 : 9);
                    stream.newLineAtOffset(MARGIN, y);
                    stream.showText(sanitize(line.replaceFirst("^#{1,2}\\s+", "")));
                    stream.endText();
                    y -= line.startsWith("# ") ? 24 : line.startsWith("## ") ? 20 : LINE_HEIGHT;
                }
            } finally {
                if (stream != null) stream.close();
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new ReportExportException("Не удалось сформировать PDF-отчёт", e);
        }
    }

    private List<String> pdfLines(AdvancedHistoryStore.Detail detail) {
        AdvancedHistoryStore.RunRow run = detail.run();
        List<String> lines = new ArrayList<>();
        lines.add("# ЭВО.СНТ S3 — отчёт о нагрузочном тестировании");
        lines.add("ТПП ЭВОПЛАТФОРМА");
        lines.add("## Основные сведения");
        lines.add("ID: " + run.id()); lines.add("Операция: " + run.operation() + "    Статус: " + run.status());
        lines.add("Создан: " + format(run.createdAt()) + "    Начат: " + format(run.startedAt()) + "    Завершён: " + format(run.finishedAt()));
        lines.add("Endpoint: " + value(run.endpoint())); lines.add("Bucket: " + value(run.bucket()) + "    Region: " + value(run.region()));
        lines.add("Object key: " + value(run.objectKey()));
        lines.add("## Результаты");
        lines.add("Средняя скорость: " + number(run.averageSpeedMiBps()) + " MiB/s    Передано: " + number(run.bytesTransferred() / 1048576.0) + " MiB");
        lines.add("Latency p50/p95/p99: " + number(run.p50LatencyMs()) + " / " + number(run.p95LatencyMs()) + " / " + number(run.p99LatencyMs()) + " ms");
        lines.add("Успешных операций: " + run.successfulParts() + "    Ошибок: " + run.failedParts() + "    Продолжительность: " + duration(run));
        lines.add("## Параметры");
        lines.add("Объект: " + run.objectSizeMiB() + " MiB    Часть: " + run.partSizeMiB() + " MiB    Parallelism: " + run.parallelism() + "    Objects: " + run.objectCount());
        lines.add("Path style: " + run.pathStyleAccess() + "    Удаление после теста: " + run.deleteAfterTest() + "    Очистка: " + run.cleanupSuccessful());
        if (run.message() != null && !run.message().isBlank()) lines.add("Сообщение: " + run.message());
        lines.add("## Операции / части");
        lines.add("Объект | Часть | Байты | Время ms | MiB/s | Статус | Ошибка");
        for (PartResult part : detail.parts()) {
            lines.add(part.objectNumber() + " | " + part.partNumber() + " | " + part.bytes() + " | " + part.durationMillis()
                    + " | " + number(part.speedMiBps()) + " | " + value(part.status()) + " | " + value(part.error()));
        }
        lines.add("Сформировано: " + DATE_TIME.format(java.time.Instant.now()) + ". Credentials и секреты не включены.");
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
        for (Path candidate : candidates) if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) return candidate;
        throw new IOException("Не найден Unicode TTF-шрифт. Укажите s3perf.reports.pdf-font-path или S3PERF_REPORTS_PDF_FONT_PATH");
    }

    private static void card(StringBuilder out, String name, String value) {
        out.append("<div class=\"card\"><small>").append(escape(name)).append("</small><br><strong>")
                .append(escape(value)).append("</strong></div>");
    }
    private static void row(StringBuilder out, String name, String value) {
        out.append("<tr><th>").append(escape(name)).append("</th><td>").append(escape(value)).append("</td></tr>");
    }
    private static String format(java.time.Instant value) { return value == null ? "—" : DATE_TIME.format(value); }
    private static String duration(AdvancedHistoryStore.RunRow run) {
        if (run.startedAt() == null || run.finishedAt() == null) return "—";
        Duration d = Duration.between(run.startedAt(), run.finishedAt());
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", d.toHours(), d.toMinutesPart(), d.toSecondsPart(), d.toMillisPart());
    }
    private static String number(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static String value(String value) { return value == null || value.isBlank() ? "—" : value; }
    private static String sanitize(String value) { return value(value).replace('\n', ' ').replace('\r', ' ').replace('\t', ' '); }
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static class ReportExportException extends RuntimeException {
        public ReportExportException(String message, Throwable cause) { super(message, cause); }
    }
}
