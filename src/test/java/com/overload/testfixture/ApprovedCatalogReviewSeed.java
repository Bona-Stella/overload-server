package com.overload.testfixture;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;

/** Restores the approved catalog and weekly plan into the dedicated browser fixture. */
final class ApprovedCatalogReviewSeed {
    private static final Path INPUT = Path.of("docs/dev-data/overload-31-catalog");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCHEDULE = "/api/v1/weekly-schedule";
    private static final String ROUTINES = "/api/v1/routines";
    private static final List<Integer> RUN_SECONDS = List.of(2100, 1200, 2100, 1200);

    private record Exercise(String id, String activityId) { }

    static Map<String, Object> apply(DataSource dataSource, JdbcTemplate jdbc, String origin,
                                      EmailOtpFixtures.FakeSender sender) throws Exception {
        var input = JSON.readTree(Files.readString(INPUT.resolve("routine-plans.local.json")));
        var approval = JSON.readTree(Files.readString(INPUT.resolve("handoff.local.json")));
        require("USER_APPROVED".equals(approval.path("approval_context").path("catalog_muscle_and_split_review").asString("")),
                "Local handoff must carry the user's catalog approval");
        String email = input.path("targetAccount").asString("");
        require(input.path("formatVersion").asInt() == 1 && email.contains("@") && input.path("grantGifAccess").asBoolean(),
                "Local review account and GIF grant must be configured");
        String zone = ZoneId.of(input.get("timeZone").asString()).getId();
        var plans = input.get("routines");
        require(plans.valueStream().map(row -> row.get("day").asString()).toList()
                        .equals(List.of("MON", "TUE", "WED", "THU", "FRI", "SAT"))
                        && plans.valueStream().mapToInt(row -> row.get("weightPlacements").size()).sum() == 33
                        && plans.valueStream().flatMap(row -> row.get("cardioPlacements").valueStream())
                                .map(row -> row.get("targetSeconds").asInt()).toList().equals(RUN_SECONDS),
                "Expected Monday to Saturday, 33 weight placements and the four approved running times");

        var catalog = loadCatalog(dataSource);
        require(catalog.equals(loadCatalog(dataSource)), "Catalog restore must be repeatable");
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            var csrf = get(client, origin, "/api/v1/auth/csrf");
            var issued = JSON.readTree(V22BrowserEnvironment.send(client, origin, "/api/v1/auth/email-otp/request",
                    "POST", Map.of("email", email), csrf, 202).body());
            var challenge = UUID.fromString(issued.get("challengeId").asString());
            var message = sender.messages.get(challenge);
            require(message != null && email.equals(message.recipient()), "Test OTP delivery did not match the review account");
            V22BrowserEnvironment.send(client, origin, "/api/v1/auth/email-otp/verify", "POST",
                    Map.of("email", email, "challengeId", challenge, "code", message.code()), csrf, 204);
            var settings = get(client, origin, "/api/v1/workout-settings");
            UUID owner = UUID.fromString(settings.get("userId").asString());
            String generation = settings.get("dataGeneration").asString();
            csrf = get(client, origin, "/api/v1/auth/csrf");
            require(get(client, origin, "/api/v1/standard-exercises/search-data").get("exercises").size() == 28
                    && get(client, origin, "/api/v1/standard-exercises/cardio-choices").get("activities").size() == 2,
                    "The approved catalog was not visible through the normal API");

            var assignments = new ArrayList<Map<String, Object>>();
            for (var day : plans) {
                String dayKey = day.get("day").asString();
                String key = email + "/" + generation + "/" + dayKey;
                String routineId = id(key);
                int weekday = assignments.size() + 1;
                var weights = new ArrayList<Map<String, Object>>();
                for (var row : day.get("weightPlacements")) {
                    var exercise = exercise(catalog, row);
                    var weight = new LinkedHashMap<String, Object>();
                    weight.put("id", id(key + "/weight/" + weights.size()));
                    weight.put("standardExerciseId", exercise.id());
                    weight.put("manufacturerId", manufacturer(jdbc, row.get("manufacturer").asString(null)));
                    for (String field : List.of("loadType", "setCount", "minReps", "maxReps", "initialWeightKg")) {
                        weight.put(field, row.get(field));
                    }
                    weights.add(weight);
                }
                var cardio = new ArrayList<Map<String, Object>>();
                for (var row : day.get("cardioPlacements")) {
                    var exercise = exercise(catalog, row);
                    require(Objects.equals(exercise.activityId(), row.get("activityId").asString()), "Running activity mismatch");
                    var plan = new LinkedHashMap<String, Object>();
                    for (String field : List.of("kind", "name", "targetSeconds", "targetDistanceKm", "intensity",
                            "inclinePercent", "resistance")) {
                        plan.put(field, row.get(field));
                    }
                    plan.put("activityId", exercise.activityId());
                    plan.put("standardExerciseId", exercise.id());
                    cardio.add(Map.of("id", id(key + "/cardio/" + cardio.size()), "plan", plan));
                }
                String name = day.get("name").asString();
                V22BrowserEnvironment.send(client, origin, ROUTINES, "POST", Map.of("id", routineId,
                        "name", name, "dataGeneration", generation, "weightPlacements", weights, "cardioPlacements", cardio), csrf, 201);
                var routine = get(client, origin, ROUTINES + "/" + routineId);
                require(name.equals(routine.get("name").asString())
                                && includesPlacements(routine.get("weightPlacements"), weights)
                                && includesPlacements(routine.get("cardioPlacements"), cardio),
                        "Saved routine differs from approved input for " + dayKey);
                assignments.add(Map.of("dayOfWeek", weekday, "routineId", routineId));
            }
            var state = get(client, origin, SCHEDULE);
            var preset = state.get("presets").get(0);
            require(state.get("presets").size() == 1 && preset.get("assignments").isEmpty(), "Fresh schedule preset required");
            V22BrowserEnvironment.send(client, origin, SCHEDULE + "/presets/" + preset.get("id").asString() + "/assignments",
                    "PUT", Map.of("dataGeneration", generation, "expectedStateVersion", state.get("stateVersion"),
                            "expectedPresetVersion", preset.get("presetVersion"), "assignments", assignments, "timeZone", zone), csrf, 200);
            var saved = get(client, origin, SCHEDULE);
            require(saved.get("presets").get(0).get("assignments").equals(JSON.valueToTree(assignments))
                            && !saved.get("appliedScheduleVersion").isNull(), "Active weekly schedule was not applied");

            jdbc.update("INSERT INTO exercise_gif_access(user_id) VALUES (?) ON CONFLICT DO NOTHING", owner);
            require(jdbc.queryForObject("SELECT count(*) FROM exercise_gif_access WHERE user_id=?", Integer.class, owner) == 1
                            && jdbc.queryForObject("SELECT count(*) FROM standard_exercise WHERE representative_gif_key IS NOT NULL",
                            Integer.class) == 0
                            && jdbc.queryForObject("SELECT count(*) FROM workout_sessions", Integer.class) == 0,
                    "GIF grant or test history verification failed");
            V22BrowserEnvironment.send(client, origin, "/api/v1/auth/logout", "POST", null, csrf, 204);
            // Clear only the already consumed setup challenge's cooldown in this empty test database.
            require(jdbc.update("""
                    UPDATE email_otp_limits SET last_issued_at = NULL
                    WHERE purpose = 'LOGIN' AND active_challenge_id = ?
                      AND EXISTS (SELECT 1 FROM email_otp_challenges WHERE id = ? AND state = 'CONSUMED')
                    """, challenge, challenge) == 1, "Setup OTP cooldown could not be cleared");
            sender.messages.remove(challenge);
            return Map.of("userId", owner.toString(), "approvedExercises", catalog.size(), "routines", assignments.size(),
                    "weightPlacements", 33, "runningSeconds", RUN_SECONDS, "gifAccessGranted", true,
                    "appliedScheduleVersion", saved.get("appliedScheduleVersion").asString());
        }
    }

    private static Map<String, Exercise> loadCatalog(DataSource dataSource) throws Exception {
        String payload = Files.readString(INPUT.resolve("catalog.json")).replace("'", "''");
        String script = Files.readString(INPUT.resolve("catalog-plan.sql"))
                .replace("/* CATALOG_JSON */", "'" + payload + "'::jsonb")
                .replace("/* REUSE_JSON */", "'{}'::jsonb")
                .replace("/* APPROVE */", "true");
        var result = new HashMap<String, Exercise>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            boolean hasResult = statement.execute(script);
            while (!hasResult && statement.getUpdateCount() != -1) hasResult = statement.getMoreResults();
            require(hasResult, "Catalog SQL did not return resolved exercise IDs");
            try (var rows = statement.getResultSet()) {
                while (rows.next()) {
                    require(rows.getBoolean("muscle_targets_reviewed") && rows.getBoolean("splits_reviewed"),
                            "Catalog SQL left a selected exercise unreviewed");
                    result.put(rows.getString("key"), new Exercise(rows.getString("standard_exercise_id"),
                            rows.getString("cardio_activity_id")));
                }
            }
            require(result.size() == 30, "Exactly 30 standardized catalog exercises required");
            connection.commit();
        }
        return result;
    }

    private static String manufacturer(JdbcTemplate jdbc,String name) {
        if(name==null)return null;
        var ids=jdbc.queryForList("SELECT id FROM manufacturer WHERE name=? AND is_active",UUID.class,name);
        require(ids.size()==1,"Routine manufacturer must resolve independently of standard exercise");
        return ids.getFirst().toString();
    }

    private static Exercise exercise(Map<String, Exercise> catalog, JsonNode row) {
        var exercise = catalog.get(row.get("exerciseKey").asString());
        require(exercise != null && exercise.id().equals(row.get("standardExerciseIdCandidate").asString()),
                "Routine references a different catalog exercise");
        return exercise;
    }

    private static boolean includesPlacements(JsonNode saved, List<Map<String, Object>> expected) {
        if (saved.size() != expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            for (var field : expected.get(i).entrySet()) {
                if (!JSON.valueToTree(field.getValue()).equals(saved.get(i).get(field.getKey()))) return false;
            }
        }
        return true;
    }

    private static JsonNode get(HttpClient client, String origin, String path) throws Exception {
        return JSON.readTree(V22BrowserEnvironment.send(client, origin, path, "GET", null, null, 200).body());
    }

    private static String id(String key) {
        return UUID.nameUUIDFromBytes(("overload-approved-31/" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
