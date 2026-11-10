package com.overload.overload_server.catalog;

import com.overload.overload_server.OverloadServerApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opt-in runner on the test classpath. It takes an input file, never a database destination. */
public final class ExerciseDbIntakeCommand {
    private ExerciseDbIntakeCommand() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) throw new IllegalArgumentException(
                "Usage: exerciseDbIntake --args='<exercises.json> [--sha256=<digest>] [--store-isolated]'");
        String expected = null;
        boolean store = false;
        for (int index = 1; index < args.length; index++) {
            String arg = args[index];
            if (arg.startsWith("--sha256=") && expected == null && arg.substring(9).matches("[0-9a-f]{64}")) {
                expected = arg.substring(9);
            } else if ("--store-isolated".equals(arg) && !store) {
                store = true;
            } else {
                throw new IllegalArgumentException("Invalid option: " + arg);
            }
        }
        var batch = ExerciseDbV1.parse(Files.readAllBytes(Path.of(args[0])), 1500, expected);
        var examples = new java.util.EnumMap<ExerciseDbV1.Flag, List<String>>(ExerciseDbV1.Flag.class);
        for (var flag : ExerciseDbV1.Flag.values()) {
            examples.put(flag, batch.rows().stream().filter(row -> row.findings().flags().contains(flag))
                    .map(ExerciseDbV1.Candidate::sourceId).limit(8).toList());
        }
        System.out.println(ExerciseDbV1.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "mode", store ? "STORE_ISOLATED" : "PREVIEW", "source", "EXERCISEDB_V1",
                "sha256", batch.sha256(), "summary", batch.summary(), "exampleIds", examples)));
        if (store) {
            if (expected == null) throw new IllegalArgumentException("--store-isolated requires the expected --sha256");
            verifyStorage(batch);
        }
    }

    private static void verifyStorage(ExerciseDbV1.Batch batch) {
        // Suppress externally supplied application profiles, datasource, and Flyway options.
        if (System.getenv().keySet().stream().anyMatch(key -> key.toUpperCase(java.util.Locale.ROOT).startsWith("SPRING_"))
                || System.getProperties().stringPropertyNames().stream().anyMatch(key -> key.startsWith("spring."))) {
            throw new IllegalStateException("Remove Spring configuration overrides for isolated intake");
        }
        try (var postgres = new PostgreSQLContainer("postgres:18.6-alpine")
                .withDatabaseName("overload_exercisedb_intake_test").withUsername("overload_test")
                .withPassword(UUID.randomUUID().toString())) {
            postgres.start();
            if (postgres.getMappedPort(5432) == 5432 || postgres.getMappedPort(5432) == 5433) {
                throw new IllegalStateException("Unexpected isolated PostgreSQL port");
            }
            try (var context = new SpringApplicationBuilder(OverloadServerApplication.class).web(WebApplicationType.NONE)
                    .run("--spring.profiles.active=integration", "--spring.config.location=classpath:/",
                            "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.flyway.url=" + postgres.getJdbcUrl(),
                            "--spring.datasource.username=" + postgres.getUsername(), "--spring.flyway.user=" + postgres.getUsername(),
                            "--spring.datasource.password=" + postgres.getPassword(), "--spring.flyway.password=" + postgres.getPassword(),
                            "--spring.jpa.hibernate.ddl-auto=validate", "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
                var service = context.getBean(ExerciseDbIntakeService.class);
                var first = service.stage(batch);
                var replay = service.stage(batch);
                var jdbc = context.getBean(JdbcTemplate.class);
                int standardRows = jdbc.queryForObject("SELECT count(*) FROM standard_exercise", Integer.class);
                int sourceLinks = jdbc.queryForObject("SELECT count(*) FROM exercise_source", Integer.class);
                if (first.insertedRows() != batch.rows().size() || replay.insertedRows() != 0 || standardRows != 0 || sourceLinks != 0) {
                    throw new IllegalStateException("Unexpected isolated intake result");
                }
                System.out.println(ExerciseDbV1.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("first", first, "replay", replay, "standardExercises", standardRows, "sourceLinks", sourceLinks)));
            }
        }
    }
}
