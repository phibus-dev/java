package dev.phibus.s3.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VaultAuthServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void cachesAppRoleTokenAcrossKvReads() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        server = server();
        server.createContext("/v1/auth/approle/login", exchange -> {
            logins.incrementAndGet();
            json(exchange, 200, "{\"auth\":{\"client_token\":\"token-1\",\"lease_duration\":300,\"renewable\":false}}");
        });
        server.createContext("/v1/secret/data/s3/test", exchange ->
                json(exchange, 200, "{\"data\":{\"data\":{\"accessKey\":\"aaa\",\"secretKey\":\"bbb\"}}}"));
        server.start();

        VaultAuthService service = new VaultAuthService(mock(BootstrapSecretCodec.class), new ObjectMapper());
        BootstrapSettings.VaultSettings settings = settings();

        JsonNode first = service.readKvV2(settings, "s3/test");
        JsonNode second = service.readKvV2(settings, "s3/test");

        assertEquals("aaa", first.path("accessKey").asText());
        assertEquals("bbb", second.path("secretKey").asText());
        assertEquals(1, logins.get());
    }

    @Test
    void retriesKvReadWithNewTokenAfterForbidden() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        server = server();
        server.createContext("/v1/auth/approle/login", exchange -> {
            int attempt = logins.incrementAndGet();
            json(exchange, 200, "{\"auth\":{\"client_token\":\"token-" + attempt
                    + "\",\"lease_duration\":300,\"renewable\":false}}");
        });
        server.createContext("/v1/secret/data/s3/test", exchange -> {
            reads.incrementAndGet();
            String token = exchange.getRequestHeaders().getFirst("X-Vault-Token");
            if ("token-1".equals(token)) json(exchange, 403, "{\"errors\":[\"permission denied\"]}");
            else json(exchange, 200, "{\"data\":{\"data\":{\"accessKey\":\"new\",\"secretKey\":\"value\"}}}");
        });
        server.start();

        VaultAuthService service = new VaultAuthService(mock(BootstrapSecretCodec.class), new ObjectMapper());
        JsonNode data = service.readKvV2(settings(), "s3/test");

        assertEquals("new", data.path("accessKey").asText());
        assertEquals(2, logins.get());
        assertEquals(2, reads.get());
    }

    private BootstrapSettings.VaultSettings settings() {
        return new BootstrapSettings.VaultSettings(
                "http://127.0.0.1:" + server.getAddress().getPort(), "APPROLE", "", "approle",
                "role-id", "secret-id", "secret", "s3", true, "");
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
