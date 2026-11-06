package com.overload.overload_server.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface StandardExerciseRepository extends JpaRepository<StandardExercise, UUID> {
    List<StandardExercise> findAllByActiveTrueOrderByIdAsc();
    interface MuscleRow { UUID getExerciseId(); UUID getMuscleId(); String getGroupCode(); String getMuscleName(); String getRole(); }
    @org.springframework.data.jpa.repository.Query(value="SELECT em.standard_exercise_id AS exerciseId,m.id AS muscleId,m.group_code AS groupCode,m.name AS muscleName,em.role AS role FROM exercise_muscle em JOIN muscle m ON m.id=em.muscle_id ORDER BY em.standard_exercise_id,em.role,m.id",nativeQuery=true)
    List<MuscleRow> muscleRows();
}
