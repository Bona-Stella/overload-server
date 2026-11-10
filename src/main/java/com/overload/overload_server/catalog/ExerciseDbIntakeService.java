package com.overload.overload_server.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.overload.overload_server.catalog.ExerciseDbV1.JSON;

/** Private intake, candidate drafts and explicit linking. No user-facing controller calls this service. */
@Service
public class ExerciseDbIntakeService {
    private static final String BATCH_ROWS = """
            FROM jsonb_to_recordset(CAST(? AS jsonb)) AS incoming(id text, position integer, original jsonb, findings jsonb)
            """;
    private static final String QUEUE = """
            SELECT c.*, s.standard_exercise_id
            FROM exercise_db_v1_candidate c
            LEFT JOIN exercise_source s
                ON s.source_system = 'EXERCISEDB_V1' AND s.source_exercise_id = c.source_exercise_id
            """;
    private static final RowMapper<Entry> ENTRY = (row, index) -> {
        String draft = row.getString("review_draft");
        return new Entry(row.getString("source_exercise_id"), row.getString("source_file_sha256"),
                row.getInt("source_position"), JSON.readTree(row.getString("original")),
                JSON.readValue(row.getString("findings"), ExerciseDbV1.Findings.class),
                draft == null ? null : JSON.readValue(draft, Draft.class),
                row.getLong("draft_version"), row.getObject("standard_exercise_id", UUID.class));
    };
    private final JdbcTemplate jdbc;
    private final StandardExerciseRepository exercises;
    private final ExerciseSourceRepository sources;
    private final CatalogReviewService catalog;
    private final MuscleRepository muscles;

    ExerciseDbIntakeService(JdbcTemplate jdbc, StandardExerciseRepository exercises, ExerciseSourceRepository sources,
                            CatalogReviewService catalog, MuscleRepository muscles) {
        this.jdbc = jdbc;
        this.exercises = exercises;
        this.sources = sources;
        this.catalog = catalog;
        this.muscles = muscles;
    }

    public record StageResult(String sourceSha256, int sourceRows, int insertedRows, int storedRows, List<String> missingIds) { }
    public record Draft(String koreanName, List<String> aliases, String instructionsKo, String equipmentDescription,
                        StandardExercise.ExerciseType exerciseType, StandardExercise.RecordMethod recordMethod,
                        StandardExercise.JointType jointType, List<UUID> primary, List<UUID> secondary) { }
    public record Entry(String sourceId, String sourceSha256, int sourcePosition, JsonNode original,
                        ExerciseDbV1.Findings findings, Draft draft, long draftVersion, UUID standardExerciseId) { }
    private record ImportRow(String id, int position, JsonNode original, ExerciseDbV1.Findings findings) { }

    public enum Reason { SOURCE_CONFLICT, SOURCE_NOT_FOUND, EXERCISE_NOT_FOUND, ALREADY_LINKED, EXERCISE_ID_CONFLICT, DRAFT_CONFLICT, INVALID_DRAFT }
    public static final class Rejected extends RuntimeException {
        private final Reason reason;
        Rejected(Reason reason, String detail) { super(reason.name() + ": " + detail); this.reason = reason; }
        public Reason reason() { return reason; }
    }

    @Transactional("workoutTransactionManager")
    public StageResult stage(ExerciseDbV1.Batch batch) {
        String payload = JSON.writeValueAsString(batch.rows().stream()
                .map(row -> new ImportRow(row.sourceId(), row.position(), row.original(), row.findings())).toList());
        int inserted = jdbc.update("""
                INSERT INTO exercise_db_v1_candidate(source_exercise_id, source_file_sha256, source_position, original, findings)
                SELECT incoming.id, ?, incoming.position, incoming.original, incoming.findings
                """ + BATCH_ROWS + " ORDER BY incoming.id COLLATE \"C\" ON CONFLICT (source_exercise_id) DO NOTHING",
                batch.sha256(), payload);
        var missing = jdbc.queryForList("SELECT incoming.id " + BATCH_ROWS + """
                LEFT JOIN exercise_db_v1_candidate c ON c.source_exercise_id = incoming.id
                WHERE c.source_exercise_id IS NULL ORDER BY incoming.position
                """, String.class, payload);
        var conflicts = jdbc.queryForList("SELECT incoming.id " + BATCH_ROWS + """
                JOIN exercise_db_v1_candidate c ON c.source_exercise_id = incoming.id
                WHERE c.source_file_sha256 <> ? OR c.source_position <> incoming.position OR c.original <> incoming.original
                ORDER BY incoming.position
                """, String.class, payload, batch.sha256());
        int stored = jdbc.queryForObject("SELECT count(*) FROM exercise_db_v1_candidate WHERE source_file_sha256=?",
                Integer.class, batch.sha256());
        if (!conflicts.isEmpty() || !missing.isEmpty() || stored != batch.rows().size()) {
            throw new Rejected(Reason.SOURCE_CONFLICT, "conflicting IDs=" + conflicts + ", missing IDs=" + missing
                    + ", stored rows=" + stored);
        }
        return new StageResult(batch.sha256(), batch.rows().size(), inserted, stored, missing);
    }

    @Transactional(transactionManager = "workoutTransactionManager", readOnly = true)
    public Entry get(String sourceId) {
        return read(sourceId, false);
    }

    @Transactional(transactionManager = "workoutTransactionManager", readOnly = true)
    public List<Entry> listAfter(String afterId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Candidate page limit must be 1–100");
        return List.copyOf(jdbc.query(QUEUE + " WHERE c.source_exercise_id > ? ORDER BY c.source_exercise_id LIMIT ?",
                ENTRY, afterId == null ? "" : afterId, limit));
    }

    @Transactional("workoutTransactionManager")
    public Entry saveDraft(String sourceId, long expectedVersion, Draft draft) {
        var entry = read(sourceId, true);
        requireVersion(entry, expectedVersion);
        validateDraft(draft);
        jdbc.update("""
                UPDATE exercise_db_v1_candidate SET review_draft=CAST(? AS jsonb), draft_version=draft_version+1
                WHERE source_exercise_id=?
                """, JSON.writeValueAsString(draft), sourceId);
        return read(sourceId, false);
    }

    @Transactional("workoutTransactionManager")
    public UUID linkExisting(String sourceId, UUID standardId) {
        var entry = read(sourceId, true);
        if (alreadyLinked(entry, standardId)) return standardId;
        exercises.findForUpdate(standardId).orElseThrow(() -> new Rejected(Reason.EXERCISE_NOT_FOUND, standardId.toString()));
        insertLink(entry, standardId);
        return standardId;
    }

    @Transactional("workoutTransactionManager")
    public UUID createDraftAndLink(String sourceId, long expectedVersion, UUID newStandardId) {
        var entry = read(sourceId, true);
        if (alreadyLinked(entry, newStandardId)) return newStandardId;
        requireVersion(entry, expectedVersion);
        var draft = entry.draft();
        if (draft == null || !ExerciseDbV1.validText(draft.koreanName())) {
            throw new Rejected(Reason.INVALID_DRAFT, "A candidate name is required");
        }
        int inserted = jdbc.update("INSERT INTO standard_exercise(id,name) VALUES (?,?) ON CONFLICT (id) DO NOTHING",
                newStandardId, draft.koreanName());
        if (inserted != 1) throw new Rejected(Reason.EXERCISE_ID_CONFLICT, newStandardId.toString());
        var exercise = catalog.get(newStandardId);
        var content = new CatalogReviewService.Content(draft.koreanName(), draft.aliases(),
                draft.exerciseType() == null ? StandardExercise.ExerciseType.OTHER : draft.exerciseType(),
                draft.recordMethod() == null ? StandardExercise.RecordMethod.UNSPECIFIED : draft.recordMethod(),
                draft.equipmentDescription(), draft.jointType(), draft.instructionsKo(), draft.primary(), draft.secondary(),
                null, null, false, false);
        catalog.replace(newStandardId, new CatalogReviewService.Change(exercise.updatedAt(), content, null));
        insertLink(entry, newStandardId);
        return newStandardId;
    }

    private void insertLink(Entry entry, UUID standardId) {
        int inserted = sources.insertV1Link(entry.sourceId(), standardId, entry.original().get("name").asString(),
                entry.original().get("gifUrl").asString());
        if (inserted == 0 && !alreadyLinked(read(entry.sourceId(), false), standardId)) {
            throw new Rejected(Reason.SOURCE_CONFLICT, entry.sourceId());
        }
    }

    private Entry read(String sourceId, boolean lock) {
        if (lock) {
            // Read the source link in a new statement after waiting for any prior linker to commit.
            if (jdbc.queryForList("SELECT source_exercise_id FROM exercise_db_v1_candidate WHERE source_exercise_id=? FOR UPDATE",
                    String.class, sourceId).isEmpty()) throw new Rejected(Reason.SOURCE_NOT_FOUND, sourceId);
        }
        return jdbc.query(QUEUE + " WHERE c.source_exercise_id=?", ENTRY, sourceId)
                .stream().findFirst().orElseThrow(() -> new Rejected(Reason.SOURCE_NOT_FOUND, sourceId));
    }

    private static boolean alreadyLinked(Entry entry, UUID standardId) {
        if (standardId == null) throw new IllegalArgumentException("Standard exercise ID is required");
        if (entry.standardExerciseId() == null) return false;
        if (!entry.standardExerciseId().equals(standardId)) throw new Rejected(Reason.ALREADY_LINKED, entry.sourceId());
        return true;
    }

    private static void requireVersion(Entry entry, long expectedVersion) {
        if (entry.draftVersion() != expectedVersion) throw new Rejected(Reason.DRAFT_CONFLICT, entry.sourceId());
    }

    private void validateDraft(Draft draft) {
        if (draft == null || draft.aliases() == null || draft.primary() == null || draft.secondary() == null
                || invalidOptional(draft.koreanName()) || invalidOptional(draft.instructionsKo())
                || invalidOptional(draft.equipmentDescription())) {
            throw new Rejected(Reason.INVALID_DRAFT, "Invalid draft fields");
        }
        var aliases = new HashSet<String>();
        for (String alias : draft.aliases()) {
            if (!ExerciseDbV1.validText(alias)) throw new Rejected(Reason.INVALID_DRAFT, "Invalid alias");
            String normalized = ExerciseAlias.normalize(alias);
            if (normalized.isEmpty() || !aliases.add(normalized)) throw new Rejected(Reason.INVALID_DRAFT, "Invalid or duplicate alias");
        }
        var ids = new HashSet<>(draft.primary());
        ids.addAll(draft.secondary());
        if (ids.contains(null) || ids.size() != draft.primary().size() + draft.secondary().size()
                || muscles.findAllById(ids).size() != ids.size()) throw new Rejected(Reason.INVALID_DRAFT, "Unknown or repeated muscle");
    }

    private static boolean invalidOptional(String value) { return value != null && !ExerciseDbV1.validText(value); }
}
