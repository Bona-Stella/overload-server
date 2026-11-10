package com.overload.overload_server.catalog;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.*;

@Entity
@Table(name = "exercise_muscle")
@IdClass(ExerciseMuscle.Key.class)
public class ExerciseMuscle {
    public enum Role { PRIMARY, SECONDARY }

    @Id @Column(name = "standard_exercise_id", nullable = false, updatable = false) private UUID standardExerciseId;
    @Id @Column(name = "muscle_id", nullable = false, updatable = false) private UUID muscleId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, columnDefinition = "text") private Role role;

    @Column(name = "primary_order") private Integer primaryOrder;

    protected ExerciseMuscle() { }

    public static ExerciseMuscle create(UUID standardExerciseId, UUID muscleId, Role role, Integer primaryOrder) {
        Objects.requireNonNull(role);
        if (role == Role.PRIMARY ? primaryOrder == null || primaryOrder < 1 : primaryOrder != null) {
            throw new IllegalArgumentException("Only PRIMARY requires a positive order");
        }
        var value = new ExerciseMuscle();
        value.primaryOrder = primaryOrder;
        value.standardExerciseId = standardExerciseId; value.muscleId = muscleId; value.role = role;
        return value;
    }

    public UUID getStandardExerciseId() { return standardExerciseId; }
    public UUID getMuscleId() { return muscleId; }
    public Role getRole() { return role; }
    public Integer getPrimaryOrder() { return primaryOrder; }

    public static class Key implements Serializable {
        public UUID standardExerciseId;
        public UUID muscleId;
        public Key() { }
        public Key(UUID standardExerciseId, UUID muscleId) {
            this.standardExerciseId = standardExerciseId; this.muscleId = muscleId;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Key id && Objects.equals(standardExerciseId, id.standardExerciseId)
                    && Objects.equals(muscleId, id.muscleId);
        }
        @Override public int hashCode() { return Objects.hash(standardExerciseId, muscleId); }
    }
}
