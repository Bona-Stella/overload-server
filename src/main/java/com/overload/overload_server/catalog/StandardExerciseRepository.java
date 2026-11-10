package com.overload.overload_server.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.*;

public interface StandardExerciseRepository extends JpaRepository<StandardExercise, UUID> {
    List<StandardExercise> findAllByActiveTrueOrderByIdAsc();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select exercise from StandardExercise exercise where exercise.id = :id")
    Optional<StandardExercise> findForUpdate(UUID id);

    interface MuscleRow {
        UUID getExerciseId();
        UUID getMuscleId();
        String getGroupCode();
        String getMuscleName();
        String getRole();
        Integer getPrimaryOrder();
    }
    String MUSCLE_ROWS = """
            SELECT em.standard_exercise_id AS exerciseId, m.id AS muscleId, m.group_code AS groupCode,
                   m.name AS muscleName, em.role AS role, em.primary_order AS primaryOrder
            FROM exercise_muscle em JOIN muscle m ON m.id = em.muscle_id
            """;
    String MUSCLE_ORDER = " ORDER BY em.standard_exercise_id, em.role, em.primary_order, m.id";

    @Query(value = MUSCLE_ROWS + MUSCLE_ORDER, nativeQuery = true)
    List<MuscleRow> muscleRows();

    @Query(value = MUSCLE_ROWS + " WHERE em.standard_exercise_id = :exerciseId" + MUSCLE_ORDER, nativeQuery = true)
    List<MuscleRow> muscleRows(UUID exerciseId);
}
