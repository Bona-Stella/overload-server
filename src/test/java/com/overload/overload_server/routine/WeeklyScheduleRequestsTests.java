package com.overload.overload_server.routine;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class WeeklyScheduleRequestsTests {
    private static final String ROUTINE = "11111111-1111-4111-8111-111111111111";
    private static final String VALID = """
            {"dataGeneration":"0","expectedStateVersion":"0","expectedPresetVersion":"0","timeZone":"Asia/Seoul","assignments":[]}
            """;

    @Test void canonicalizesDaysAndPermitsTheSameRoutineOnDifferentDays() {
        var request = WeeklyScheduleRequests.edit(VALID.replace("[]", "[" + assignment(7) + "," + assignment(1) + "]"));
        assertThat(request.assignments()).extracting(WeeklySchedule.Assignment::dayOfWeek).containsExactly(1, 7);
        assertThat(WeeklyScheduleRequests.edit(VALID.replace("\"timeZone\":\"Asia/Seoul\",", "")).timeZone()).isNull();
    }

    @Test void rejectsInvalidFieldsCountersZonesAndDays() {
        for (String raw : new String[]{
                VALID.replace("\"0\"", "0"), VALID.replace("\"0\"", "\"9223372036854775808\""),
                VALID.replace("Asia/Seoul", "bogus"), VALID.replace("\"Asia/Seoul\"", "null"),
                VALID.replace("[]", "null"),
                VALID.replace("\"assignments\":[]", "\"assignments\":[],\"userId\":\"fake\""),
                VALID.replace("\"assignments\":[]", "\"assignments\":[],\"dataGeneration\":\"0\""),
                VALID + "{}", VALID.replace("[]", "[" + assignment(1) + "," + assignment(1) + "]"),
                VALID.replace("[]", "[" + assignment(0) + "]"), VALID.replace("[]", "[" + assignment(8) + "]"),
                VALID.replace("[]", "[" + assignment(1).replace(ROUTINE, "bad") + "]")}) {
            assertThatThrownBy(() -> WeeklyScheduleRequests.edit(raw))
                    .isInstanceOfSatisfying(RoutineFailure.class, f -> assertThat(f.reason).isEqualTo(RoutineFailure.Reason.INVALID_REQUEST));
        }
    }

    @Test void enforcesEachCommandShape() {
        String config = "\"dataGeneration\":\"2\",\"expectedStateVersion\":\"3\"";
        assertThat(WeeklyScheduleRequests.create("{" + config + "}")).isEqualTo(new WeeklySchedule.Configuration(2, 3));
        assertThat(WeeklyScheduleRequests.activate("{" + config + ",\"presetId\":\"" + ROUTINE + "\"}").presetId()).hasToString(ROUTINE);
        assertThat(WeeklyScheduleRequests.delete("{" + config + ",\"expectedPresetVersion\":\"4\"}").expectedPresetVersion()).isEqualTo(4);
        assertThatThrownBy(() -> WeeklyScheduleRequests.create("{" + config + ",\"assignments\":[]}")).isInstanceOf(RoutineFailure.class);
        assertThatThrownBy(() -> WeeklyScheduleRequests.activate("{" + config + "}")).isInstanceOf(RoutineFailure.class);
        assertThatThrownBy(() -> WeeklyScheduleRequests.delete("{" + config + "}")).isInstanceOf(RoutineFailure.class);
    }

    @Test void validatesRenameSeparatelyFromAssignments() {
        String request = """
                {"dataGeneration":"0","expectedStateVersion":"1","expectedPresetVersion":"2","name":%s}
                """;
        assertThat(WeeklyScheduleRequests.rename(request.formatted("\"  상체 훈련 🏋  \"")))
                .isEqualTo(new WeeklySchedule.Rename(new WeeklySchedule.Configuration(0, 1), 2, "상체 훈련 🏋"));
        assertThat(WeeklyScheduleRequests.rename(request.formatted("\"" + "🏋".repeat(100) + "\"")).name())
                .isEqualTo("🏋".repeat(100));
        for (String invalid : List.of("\"\"", "\"　\u00a0\"", "null", "123", "\"A\\nB\"", "\"\\u0000\"",
                "\"\\uD800\"", "\"" + "가".repeat(101) + "\"", "\"name\",\"assignments\":[]", "\"A\",\"name\":\"B\"")) {
            assertThatThrownBy(() -> WeeklyScheduleRequests.rename(request.formatted(invalid)))
                    .isInstanceOfSatisfying(RoutineFailure.class, f -> assertThat(f.reason).isEqualTo(RoutineFailure.Reason.INVALID_REQUEST));
        }
    }

    private static String assignment(int day) {
        return "{\"dayOfWeek\":" + day + ",\"routineId\":\"" + ROUTINE + "\"}";
    }
}
