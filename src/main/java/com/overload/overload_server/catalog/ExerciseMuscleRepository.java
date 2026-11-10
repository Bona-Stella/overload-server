package com.overload.overload_server.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ExerciseMuscleRepository extends JpaRepository<ExerciseMuscle, ExerciseMuscle.Key> {
    List<ExerciseMuscle> findAllByStandardExerciseId(UUID exerciseId);
}
