package com.overload.overload_server.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExerciseSourceRepository extends JpaRepository<ExerciseSource, ExerciseSource.Key> {
    interface GifAccess {
        boolean getMemberAllowed();
        boolean getCurrentRepresentative();
    }

    List<ExerciseSource> findAllByStandardExerciseIdOrderBySourceExerciseId(UUID standardExerciseId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<ExerciseSource> findByStandardExerciseIdAndSourceSystemAndSourceExerciseId(
            UUID standardExerciseId, ExerciseSource.System sourceSystem, String sourceExerciseId);

    @Modifying
    @Query(value = """
            INSERT INTO exercise_source(source_system, source_exercise_id, standard_exercise_id, original_name, original_gif_reference)
            VALUES ('EXERCISEDB_V1', :sourceId, :standardId, :name, :gif)
            ON CONFLICT (source_system, source_exercise_id) DO NOTHING
            """, nativeQuery = true)
    int insertV1Link(String sourceId, UUID standardId, String name, String gif);

    // A shared reference cannot identify one source unambiguously.
    @Transactional(transactionManager = "workoutTransactionManager", readOnly = true)
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM exercise_gif_access WHERE user_id = :userId) AS "memberAllowed",
                   EXISTS (
                       SELECT 1
                       FROM exercise_source s JOIN standard_exercise e ON e.id = s.standard_exercise_id
                       WHERE s.source_system = 'EXERCISEDB_V1' AND s.source_exercise_id = :sourceId
                         AND s.source_exercise_id COLLATE "C" = :sourceId
                         AND e.is_active AND e.muscle_targets_reviewed AND e.splits_reviewed
                         AND e.representative_gif_key = s.original_gif_reference
                         AND NOT EXISTS (
                             SELECT 1 FROM exercise_source other
                             WHERE other.standard_exercise_id = s.standard_exercise_id
                               AND other.source_system = s.source_system
                               AND other.original_gif_reference = s.original_gif_reference
                               AND other.source_exercise_id <> s.source_exercise_id
                         )
                   ) AS "currentRepresentative"
            """, nativeQuery = true)
    GifAccess gifAccess(UUID userId, String sourceId);
}
