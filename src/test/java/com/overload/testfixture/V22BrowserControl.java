package com.overload.testfixture;

import com.sun.net.httpserver.HttpServer;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Local Node driver only. Fake delivery stays in memory; production authentication is never bypassed. */
final class V22BrowserControl implements AutoCloseable {
    private final HttpServer server;
    V22BrowserControl(EmailOtpFixtures.FakeSender sender, JdbcTemplate jdbc, Map<String, String> identity) throws Exception {
        var json = JsonMapper.builder().build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                if (!"POST".equals(exchange.getRequestMethod()) || exchange.getRequestHeaders().containsKey("Origin")) {
                    exchange.sendResponseHeaders(403, -1); return;
                }
                if ("/__v22_identity".equals(exchange.getRequestURI().getPath())) {
                    identity.forEach(exchange.getResponseHeaders()::set);
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                try {
                    var input = json.readTree(exchange.getRequestBody().readNBytes(1024));
                    Object result;
                    switch (exchange.getRequestURI().getPath()) {
                        case "/otp" -> {
                            var message = sender.messages.get(UUID.fromString(input.get("issuanceId").asString()));
                            if (message == null) { exchange.sendResponseHeaders(404, -1); return; }
                            result = Map.of("code", message.code());
                        }
                        case "/expire-session" -> {
                            UUID user = UUID.fromString(input.get("userId").asString());
                            result = Map.of("expired", jdbc.update("UPDATE spring_session SET last_access_time=0,expiry_time=0 WHERE principal_name=?", user.toString()));
                        }
                        default -> { exchange.sendResponseHeaders(404, -1); return; }
                    }
                    byte[] bytes = json.writeValueAsString(result).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception failure) { exchange.sendResponseHeaders(400, -1); }
            }
        });
        server.start();
    }
    String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); }
}
