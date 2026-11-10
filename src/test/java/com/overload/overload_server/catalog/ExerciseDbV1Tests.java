package com.overload.overload_server.catalog;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.overload.overload_server.catalog.ExerciseDbV1.Flag.*;
import static org.assertj.core.api.Assertions.*;

class ExerciseDbV1Tests {
    @Test void preservesCaseAndOriginalValuesAndSeparatesCandidateEvidence() {
        var steps = List.of("Step:1  Move slowly. ", "Step:2 Repeat for the desired repetitions.");
        var first = raw("Case123", "bench press", "pectorals", List.of("triceps"), steps);
        first.put("extra", "preserved");
        var batch = parse(List.of(first, raw("case123", " BENCH PRESS ", "triceps", List.of("triceps"), steps),
                raw("third01", "bench press (side pov)", "pectorals", List.of("triceps"), steps)));
        assertThat(batch.summary().uniqueIds()).isEqualTo(3);
        assertThat(batch.summary().groups()).containsEntry(EXACT_NAME, new ExerciseDbV1.GroupCount(0, 0))
                .containsEntry(NORMALIZED_NAME, new ExerciseDbV1.GroupCount(1, 2))
                .containsEntry(IDENTICAL_INSTRUCTIONS_DIFFERENT_NAME, new ExerciseDbV1.GroupCount(1, 3))
                .containsEntry(VARIANT_NAME, new ExerciseDbV1.GroupCount(1, 3));
        assertThat(batch.rows().getFirst().original()).isEqualTo(ExerciseDbV1.JSON.valueToTree(first));
        assertThat(batch.rows().getFirst().instructions()).containsExactlyElementsOf(steps);
        assertThat(batch.rows().getFirst().findings().peers().get(PEER_MUSCLE_DISAGREEMENT)).containsExactly("case123");
        assertThat(batch.rows().get(1).findings().flags()).contains(SOURCE_ROLE_OVERLAP);
        assertThat(batch.rows().getFirst().findings().primaryHints().getFirst().possibleMuscleIds()).hasSize(3);
        assertThat(batch.rows().getFirst().findings().recordMethodHint()).isEqualTo(StandardExercise.RecordMethod.SETS_REPS);
        assertThat(batch.summary().gifUrlsMatchingId()).isEqualTo(3);
    }

    @Test void movementAndRecordingHintsAreIndependentAndUnclearRowsStayUnspecified() {
        var batch = parse(List.of(
                raw("Cardio1", "stationary bike", "cardiovascular system", List.of("quads"), List.of("Step:1 Pedal steadily.")),
                raw("Hold001", "plank", "abs", List.of("wrists"), List.of("Step:1 Hold for 30 seconds.")),
                raw("Strtch1", "chest stretch", "pectorals", List.of(), List.of("Step:1 Open the arms.")),
                raw("Clash01", "plank", "abs", List.of(), List.of("Step:1 Repeat for 10 repetitions.")),
                raw("Planche", "stretched full planche push-up", "pectorals", List.of(), List.of("Step:1 Repeat for 10 repetitions."))));
        assertThat(batch.rows()).extracting(row -> row.findings().recordMethodHint()).containsExactly(
                StandardExercise.RecordMethod.CARDIO, StandardExercise.RecordMethod.HOLD_DURATION,
                StandardExercise.RecordMethod.UNSPECIFIED, StandardExercise.RecordMethod.UNSPECIFIED,
                StandardExercise.RecordMethod.SETS_REPS);
        assertThat(batch.rows().get(2).findings().typeHint()).isEqualTo(StandardExercise.ExerciseType.STRETCH);
        assertThat(batch.rows().get(1).findings().flags()).contains(UNMAPPED_MUSCLE);
        assertThat(batch.rows().get(3).findings().flags()).contains(TYPE_INSTRUCTION_REVIEW);
        assertThat(batch.rows().get(4).findings().flags()).doesNotContain(STRETCH_NAME_CUE);
    }

    @Test void validatesCountDigestDuplicateIdFieldsAndStrictJson() {
        var row = raw("abc1234", "press", "pectorals", List.of(), List.of("Step:1 Press."));
        byte[] bytes = ExerciseDbV1.JSON.writeValueAsBytes(List.of(row));
        assertThatIllegalArgumentException().isThrownBy(() -> ExerciseDbV1.parse(bytes, 2, null));
        assertThatIllegalArgumentException().isThrownBy(() -> ExerciseDbV1.parse(bytes, 1, "0".repeat(64)));
        assertThatIllegalArgumentException().isThrownBy(() -> parse(List.of(row, row))).withMessageContaining("Duplicate exerciseId");
        row.remove("instructions");
        assertThatIllegalArgumentException().isThrownBy(() -> parse(List.of(row))).withMessageContaining("instructions");
        assertThatThrownBy(() -> ExerciseDbV1.parse("[{\"exerciseId\":1,\"exerciseId\":2}]".getBytes(StandardCharsets.UTF_8), 1, null))
                .isInstanceOf(tools.jackson.core.exc.StreamReadException.class).hasMessageContaining("exerciseId");
        assertThatThrownBy(() -> ExerciseDbV1.parse("[] []".getBytes(StandardCharsets.UTF_8), 1, null))
                .hasMessageContaining("Trailing token");
    }

    static ExerciseDbV1.Batch parse(List<Map<String, Object>> rows) {
        return ExerciseDbV1.parse(ExerciseDbV1.JSON.writeValueAsBytes(rows), rows.size(), null);
    }

    static Map<String, Object> raw(String id, String name, String primary, List<String> secondary, List<String> instructions) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("exerciseId", id);
        row.put("name", name);
        row.put("gifUrl", "https://static.exercisedb.dev/media/" + id + ".gif");
        row.put("equipments", List.of("barbell"));
        row.put("bodyParts", List.of("chest"));
        row.put("targetMuscles", List.of(primary));
        row.put("secondaryMuscles", secondary);
        row.put("instructions", instructions);
        return row;
    }
}
