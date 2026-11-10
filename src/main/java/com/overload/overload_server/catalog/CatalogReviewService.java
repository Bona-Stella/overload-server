package com.overload.overload_server.catalog;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Internal catalog administration. An authorized HTTP adapter can call this service once one is defined. */
@Service
public class CatalogReviewService {
    private final StandardExerciseRepository exercises;
    private final ExerciseAliasRepository aliases;
    private final MuscleRepository muscles;
    private final ExerciseMuscleService targets;
    private final ExerciseSourceRepository sources;
    private final Clock clock;

    CatalogReviewService(StandardExerciseRepository exercises, ExerciseAliasRepository aliases, MuscleRepository muscles,
                         ExerciseMuscleService targets, ExerciseSourceRepository sources, ObjectProvider<Clock> clocks) {
        this.exercises = exercises;
        this.aliases = aliases;
        this.muscles = muscles;
        this.targets = targets;
        this.sources = sources;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    public record Content(String name, List<String> aliases, StandardExercise.ExerciseType exerciseType,
                          StandardExercise.RecordMethod recordMethod, String equipmentDescription,
                          StandardExercise.JointType jointType, String instructionsKo, List<UUID> primary,
                          List<UUID> secondary, StandardExercise.PplSplit pplSplit,
                          StandardExercise.FrontBackSplit frontBackSplit, boolean muscleTargetsReviewed,
                          boolean splitsReviewed) { }

    public record SourceId(ExerciseSource.System system, String exerciseId) { }
    public record Source(SourceId id, String originalName, String originalGifReference) { }
    public record Change(Instant expectedUpdatedAt, Content content, SourceId representativeSource) { }
    public record Review(UUID id, Instant updatedAt, boolean active, UUID cardioActivityId, Content content,
                         List<Source> sources, String representativeGifKey) { }

    public enum Reason { EXERCISE_NOT_FOUND, WRITE_CONFLICT, INVALID_DETAILS, INVALID_TARGETS, PRIMARY_REQUIRED, INVALID_GIF_SOURCE }

    public static final class Rejected extends RuntimeException {
        private final Reason reason;

        private Rejected(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() { return reason; }
    }

    @Transactional(transactionManager = "workoutTransactionManager", readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Review get(UUID exerciseId) {
        return review(exercises.findById(exerciseId).orElseThrow(() -> new Rejected(Reason.EXERCISE_NOT_FOUND)));
    }

    @Transactional("workoutTransactionManager")
    public Review replace(UUID exerciseId, Change change) {
        if (change == null || change.expectedUpdatedAt() == null) throw new Rejected(Reason.INVALID_DETAILS);
        var exercise = exercises.findForUpdate(exerciseId).orElseThrow(() -> new Rejected(Reason.EXERCISE_NOT_FOUND));
        if (!exercise.getUpdatedAt().equals(change.expectedUpdatedAt())) throw new Rejected(Reason.WRITE_CONFLICT);

        var content = change.content();
        if (content == null || !validText(content.name()) || content.exerciseType() == null || content.recordMethod() == null
                || content.equipmentDescription() != null && !validText(content.equipmentDescription())
                || content.instructionsKo() != null && !validText(content.instructionsKo())
                || exercise.getCardioActivityId() != null && content.recordMethod() != StandardExercise.RecordMethod.CARDIO) {
            throw new Rejected(Reason.INVALID_DETAILS);
        }
        var replacementAliases = validateAliases(exerciseId, content.aliases());
        validateTargets(content);
        var gifReference = representativeGif(exerciseId, change.representativeSource());
        var oldAliases = aliases.findAllByStandardExerciseIdOrderByNormalizedAlias(exerciseId);

        // PostgreSQL stores timestamptz at microsecond precision. Even a same-clock write advances the token.
        Instant updatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!updatedAt.isAfter(exercise.getUpdatedAt())) updatedAt = exercise.getUpdatedAt().plus(1, ChronoUnit.MICROS);
        exercise.updateDetails(content.name(), content.jointType(), content.exerciseType(), content.recordMethod(),
                content.instructionsKo(), content.equipmentDescription(), gifReference, updatedAt);
        exercise.updateSplits(content.pplSplit(), content.frontBackSplit(), updatedAt);
        exercise.reviewMuscleTargets(content.muscleTargetsReviewed(), updatedAt);
        exercise.reviewSplits(content.splitsReviewed(), updatedAt);
        aliases.deleteAll(oldAliases);
        aliases.flush();
        aliases.saveAll(replacementAliases);
        targets.replace(exerciseId, content.primary(), content.secondary());
        return review(exercise);
    }

    private static List<ExerciseAlias> validateAliases(UUID exerciseId, List<String> values) {
        if (values == null) throw new Rejected(Reason.INVALID_DETAILS);
        var normalized = new HashSet<String>();
        var replacement = new ArrayList<ExerciseAlias>();
        for (String value : values) {
            if (!validText(value) || ExerciseAlias.normalize(value).isEmpty()) throw new Rejected(Reason.INVALID_DETAILS);
            var alias = ExerciseAlias.create(exerciseId, value);
            if (!normalized.add(alias.getNormalizedAlias())) throw new Rejected(Reason.INVALID_DETAILS);
            replacement.add(alias);
        }
        return replacement;
    }

    private void validateTargets(Content content) {
        if (content.primary() == null || content.secondary() == null) throw new Rejected(Reason.INVALID_TARGETS);
        var ids = new HashSet<>(content.primary());
        ids.addAll(content.secondary());
        if (ids.contains(null) || ids.size() != content.primary().size() + content.secondary().size()) {
            throw new Rejected(Reason.INVALID_TARGETS);
        }
        var found = muscles.findAllById(ids);
        if (found.size() != ids.size()) throw new Rejected(Reason.INVALID_TARGETS);
        if (content.muscleTargetsReviewed() && content.recordMethod() == StandardExercise.RecordMethod.SETS_REPS
                && found.stream().noneMatch(muscle -> muscle.isActive() && content.primary().contains(muscle.getId()))) {
            throw new Rejected(Reason.PRIMARY_REQUIRED);
        }
    }

    private String representativeGif(UUID exerciseId, SourceId source) {
        if (source == null) return null;
        if (source.system() == null || !validText(source.exerciseId())) throw new Rejected(Reason.INVALID_GIF_SOURCE);
        return sources.findByStandardExerciseIdAndSourceSystemAndSourceExerciseId(exerciseId, source.system(), source.exerciseId())
                .map(ExerciseSource::getOriginalGifReference).orElseThrow(() -> new Rejected(Reason.INVALID_GIF_SOURCE));
    }

    private Review review(StandardExercise exercise) {
        UUID id = exercise.getId();
        var exerciseAliases = aliases.findAllByStandardExerciseIdOrderByNormalizedAlias(id).stream().map(ExerciseAlias::getAlias).toList();
        var primary = new ArrayList<UUID>();
        var secondary = new ArrayList<UUID>();
        for (var row : exercises.muscleRows(id)) {
            ("PRIMARY".equals(row.getRole()) ? primary : secondary).add(row.getMuscleId());
        }
        var exerciseSources = sources.findAllByStandardExerciseIdOrderBySourceExerciseId(id).stream()
                .map(source -> new Source(new SourceId(source.getSourceSystem(), source.getSourceExerciseId()),
                        source.getOriginalName(), source.getOriginalGifReference())).toList();
        var content = new Content(exercise.getName(), exerciseAliases, exercise.getExerciseType(), exercise.getRecordMethod(),
                exercise.getEquipmentDescription(), exercise.getJointType(), exercise.getInstructionsKo(), List.copyOf(primary),
                List.copyOf(secondary), exercise.getPplSplit(), exercise.getFrontBackSplit(), exercise.isMuscleTargetsReviewed(),
                exercise.isSplitsReviewed());
        return new Review(id, exercise.getUpdatedAt(), exercise.isActive(), exercise.getCardioActivityId(),
                content, exerciseSources, exercise.getRepresentativeGifKey());
    }

    private static boolean validText(String value) {
        return value != null && !value.isBlank() && value.indexOf('\0') < 0;
    }
}
