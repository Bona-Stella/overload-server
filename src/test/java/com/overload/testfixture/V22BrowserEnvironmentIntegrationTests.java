package com.overload.testfixture;

import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
class V22BrowserEnvironmentIntegrationTests {
    @Test void currentSchemaCatalogAndRealOtpSmokeUseOnlyFreshDatabaseAndCloseIt() throws Exception {
        UUID run = UUID.randomUUID();
        String container;
        Path directory;
        try (var environment = V22BrowserEnvironment.start(run, false)) {
            var evidence = environment.evidence();
            directory = environment.directory();
            container = (String) evidence.get("containerId");
            assertThat(evidence).containsEntry("migrations", 28).containsEntry("initialUsers", 0)
                    .containsEntry("preparedUsers", 1).containsEntry("httpSmokePassed", true)
                    .containsEntry("schemaValidated", true).containsEntry("noDeveloperEnv", true)
                    .containsEntry("freshDatabase", true).containsEntry("loopbackDatabase", true)
                    .containsEntry("tmpfsOnly", true).containsEntry("fakeEmailSender", true);
            assertThat(Files.exists(directory.resolve("ready.json"))).isTrue();
            try (var client = HttpClient.newHttpClient()) {
                for (var channel : new String[]{"backendOrigin", "browserControlOrigin"}) {
                    var request = HttpRequest.newBuilder(URI.create(evidence.get(channel) + "/__v22_identity"))
                            .POST(HttpRequest.BodyPublishers.noBody());
                    var proof = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
                    assertThat(proof.statusCode()).isEqualTo(204);
                    assertThat(proof.headers().firstValue("X-V22-Run")).contains(run.toString());
                    assertThat(proof.headers().firstValue("X-V22-Instance")).contains((String) evidence.get("instanceId"));
                    assertThat(proof.headers().firstValue("X-V22-Pid")).contains(evidence.get("backendPid").toString());
                    assertThat(proof.headers().firstValue("X-V22-Backend")).contains((String) evidence.get("backendOrigin"));
                    assertThat(client.send(request.header("Origin", "http://127.0.0.1:3000").build(),
                            HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
                }
            }
            // Reusing a previous ready directory is rejected before it can create another database.
            assertThatThrownBy(() -> V22BrowserEnvironment.start(run, false)).isInstanceOf(FileAlreadyExistsException.class);
            assertThat(Files.exists(directory.resolve("ready.json"))).isTrue();
        }
        assertThat(Files.exists(directory.resolve("ready.json"))).isFalse();
        var result = JsonMapper.builder().build().readTree(Files.readString(directory.resolve("result.json")));
        assertThat(result.get("status").asString()).isEqualTo("STOPPED");
        assertThat(result.has("instanceId")).isFalse();
        assertThat(result.get("applicationStopped").asBoolean()).isTrue();
        assertThat(result.get("containerStopped").asBoolean()).isTrue();
        assertThatThrownBy(() -> DockerClientFactory.instance().client().inspectContainerCmd(container).exec()).isInstanceOf(NotFoundException.class);
    }

    @Test void failureAfterMigrationAndHttpPreparationClosesApplicationAndOnlyItsOwnContainer() throws Exception {
        UUID run = UUID.randomUUID();
        assertThatThrownBy(() -> V22BrowserEnvironment.start(run, true)).isInstanceOf(IllegalStateException.class);
        var directory = Path.of("build", "verification-v22", run.toString());
        assertThat(Files.exists(directory.resolve("ready.json"))).isFalse();
        var result = JsonMapper.builder().build().readTree(Files.readString(directory.resolve("result.json")));
        assertThat(result.get("status").asString()).isEqualTo("FAILED");
        assertThat(result.get("migrations").asInt()).isEqualTo(28);
        assertThat(result.get("httpSmokePassed").asBoolean()).isTrue();
        assertThat(result.get("applicationStopped").asBoolean()).isTrue();
        assertThat(result.get("containerStopped").asBoolean()).isTrue();
        String container = result.get("containerId").asString();
        assertThatThrownBy(() -> DockerClientFactory.instance().client().inspectContainerCmd(container).exec()).isInstanceOf(NotFoundException.class);
    }
}
