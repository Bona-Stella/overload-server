package com.overload.overload_server.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface StandardExerciseRepository extends JpaRepository<StandardExercise, UUID> {
    List<StandardExercise> findAllByActiveTrueOrderByIdAsc();
}
