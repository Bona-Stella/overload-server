package com.overload.testfixture;

import com.overload.overload_server.auth.email.OtpEmailSender;
import com.overload.overload_server.auth.email.OtpPurpose;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class V22BrowserControlTests {
    @Test void fakeDeliveryIsMemoryOnlyAndUnavailableToBrowserOrigins() throws Exception {
        var sender = new EmailOtpFixtures.FakeSender();
        var jdbc = mock(JdbcTemplate.class);
        UUID issuance = UUID.randomUUID();
        sender.messages.put(issuance, new OtpEmailSender.Message(issuance, "synthetic@example.invalid", "123456", OtpPurpose.LOGIN));
        try (var control = new V22BrowserControl(sender, jdbc, java.util.Map.of()); var client = HttpClient.newHttpClient()) {
            String body = "{\"issuanceId\":\"" + issuance + "\"}";
            var request = HttpRequest.newBuilder(URI.create(control.origin() + "/otp")).POST(HttpRequest.BodyPublishers.ofString(body));
            var delivered = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(delivered.statusCode()).isEqualTo(200);
            assertThat(delivered.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(delivered.body()).contains("123456");
            assertThat(client.send(request.header("Origin", "http://127.0.0.1:3000").build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
            assertThat(client.send(HttpRequest.newBuilder(URI.create(control.origin() + "/otp")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
            verifyNoInteractions(jdbc);
        }
    }

    @Test void unsupportedActionsCannotExecuteArbitrarySql() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        try (var control = new V22BrowserControl(new EmailOtpFixtures.FakeSender(), jdbc, java.util.Map.of()); var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(control.origin() + "/sql"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"sql\":\"DELETE FROM users\"}")).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
            verifyNoInteractions(jdbc);
        }
    }

    @Test void expiryFixtureChangesLastAccessAsWellAsTheCleanupIndex() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        UUID user = UUID.randomUUID();
        try (var control = new V22BrowserControl(new EmailOtpFixtures.FakeSender(), jdbc, java.util.Map.of()); var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(control.origin() + "/expire-session"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":\"" + user + "\"}")).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            verify(jdbc).update("UPDATE spring_session SET last_access_time=0,expiry_time=0 WHERE principal_name=?", user.toString());
        }
    }
}
