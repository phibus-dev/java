package dev.phibus.s3.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Tracing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "s3perf.security.enabled=false",
                "s3perf.distributed.recovery-scan-initial-delay-ms=3600000",
                "s3perf.distributed.recovery-scan-interval-ms=3600000"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrowserE2ETest {
    private static final Path ARTIFACTS = Path.of("target", "playwright");

    @LocalServerPort
    private int port;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private String artifactName;
    private List<String> pageErrors;

    @BeforeAll
    void launchBrowser() throws IOException {
        Files.createDirectories(ARTIFACTS);
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of("--disable-dev-shm-usage")));
    }

    @AfterAll
    void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void createPage(TestInfo testInfo) {
        artifactName = testInfo.getTestMethod().orElseThrow().getName();
        pageErrors = new CopyOnWriteArrayList<>();
        context = browser.newContext(new Browser.NewContextOptions()
                .setLocale("ru-RU")
                .setTimezoneId("Europe/Moscow")
                .setViewportSize(1440, 1000));
        context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));
        page = context.newPage();
        page.setDefaultTimeout(15_000);
        page.onPageError(pageErrors::add);
    }

    @AfterEach
    void saveArtifacts() {
        try {
            if (page != null && !page.isClosed()) {
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(ARTIFACTS.resolve(artifactName + ".png"))
                        .setFullPage(true));
            }
        } finally {
            if (context != null) {
                context.tracing().stop(new Tracing.StopOptions()
                        .setPath(ARTIFACTS.resolve(artifactName + "-trace.zip")));
                context.close();
            }
        }
    }

    @Test
    void commonShellAndNavigationAreConsistent() {
        navigate("/");
        page.locator(".app-user-tools").waitFor();

        assertEquals("Главная — ЭВО.СНТ", page.title());
        assertEquals("ru", page.locator("html").getAttribute("lang"));
        assertEquals("ЭВО.СНТ", page.locator(".brand-title h1").textContent().trim());
        assertTrue(page.locator(".corporate-logo").isVisible());
        assertEquals("page", page.locator(".primary-nav a[href='/']").getAttribute("aria-current"));
        assertTrue(page.locator(".app-user-tools").isVisible());
        assertEquals(0, page.locator(".breadcrumbs").count());

        page.locator(".primary-nav a[href='/tasks']").click();
        page.waitForURL("**/tasks");
        page.locator(".app-user-tools").waitFor();

        assertEquals("Задания S3 — ЭВО.СНТ", page.title());
        assertEquals("page", page.locator(".primary-nav a[href='/tasks']").getAttribute("aria-current"));
        assertEquals("page", page.locator(".section-nav a[href='/tasks']").getAttribute("aria-current"));
        assertTrue(page.locator(".breadcrumbs").isVisible());
        assertTrue(page.locator(".breadcrumbs").textContent().contains("Главная"));
        assertTrue(page.locator(".breadcrumbs").textContent().contains("Задания"));
        assertTrue(page.locator("#test-form").isVisible());
        assertNoPageErrors();
    }

    @Test
    void monitoringLoadsCapacityAndSupportsManualRefresh() {
        navigate("/monitoring");
        page.waitForFunction("() => document.querySelector('#runtime-runs').textContent !== '—'");

        assertEquals("Мониторинг — ЭВО.СНТ", page.title());
        assertFalse(page.locator("#application-state").textContent().contains("Загрузка"));
        assertTrue(page.locator("#runtime-runs").textContent().matches("\\d+ / \\d+"));
        assertTrue(page.locator("#runtime-workers").textContent().matches("\\d+ / \\d+"));
        assertEquals(6, page.locator("#runtime-capacity tr").count());
        assertTrue(page.locator("#runtime-capacity").textContent().contains("GLOBAL"));
        assertTrue(page.locator("#runtime-capacity").textContent().contains("Движок: S3"));
        assertTrue(page.locator("#runtime-capacity").textContent().contains("Scope: LOCAL"));
        assertTrue(page.locator("#health-error").isHidden());

        Response refreshed = page.waitForResponse(
                response -> response.url().endsWith("/api/health/overview"),
                () -> page.locator("#refresh-health").click());
        assertEquals(200, refreshed.status());
        page.waitForFunction("() => !document.querySelector('#refresh-health').disabled");
        assertFalse(page.locator("#refresh-health").isDisabled());
        assertNoPageErrors();
    }

    @Test
    void qualityGatesRemainUsableOnMobileViewport() {
        page.setViewportSize(390, 844);
        navigate("/quality-gates");
        page.locator(".app-user-tools").waitFor();

        assertEquals("page", page.locator(".primary-nav a[href='/quality-gates']")
                .getAttribute("aria-current"));
        assertTrue(page.locator(".breadcrumbs").isVisible());
        assertTrue(page.locator(".breadcrumbs").textContent().contains("Quality gates"));
        assertEquals(2, page.locator(".table-wrap").count());
        assertEquals(2, page.locator("table caption.sr-only").count());
        assertTrue(page.locator("tbody tr").count() >= 3);

        Number horizontalOverflow = (Number) page.evaluate(
                "() => Math.max(0, document.documentElement.scrollWidth - document.documentElement.clientWidth)");
        assertTrue(horizontalOverflow.doubleValue() <= 1,
                () -> "Страница шире мобильного viewport на " + horizontalOverflow + " px");
        assertNoPageErrors();
    }

    @Test
    void legacyIndexRedirectsToCanonicalHome() {
        navigate("/index.html");

        assertEquals(baseUrl() + "/", page.url());
        assertEquals("Главная — ЭВО.СНТ", page.title());
        assertNoPageErrors();
    }

    private void navigate(String path) {
        Response response = page.navigate(baseUrl() + path);
        assertNotNull(response, "Document navigation must return an HTTP response");
        assertTrue(response.ok(), () -> "HTTP " + response.status() + " for " + response.url());
        page.locator(".brand-header").waitFor();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private void assertNoPageErrors() {
        assertTrue(pageErrors.isEmpty(), () -> "Unhandled browser errors: " + pageErrors);
    }
}
