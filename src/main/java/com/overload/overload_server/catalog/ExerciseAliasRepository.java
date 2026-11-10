package com.overload.overload_server.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface ExerciseAliasRepository extends JpaRepository<ExerciseAlias, ExerciseAlias.Key> {
    List<ExerciseAlias> findAllByStandardExerciseIdOrderByNormalizedAlias(UUID standardExerciseId);

    @Query("select a from ExerciseAlias a, StandardExercise e where a.standardExerciseId=e.id and e.active=true order by a.standardExerciseId,a.normalizedAlias")
    List<ExerciseAlias> findAllForActiveExercises();
}
