package com.overload.overload_server.catalog;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "exercise_source")
@IdClass(ExerciseSource.Key.class)
public class ExerciseSource implements Persistable<ExerciseSource.Key> {
    public enum System { EXERCISEDB_V1 }

    @Id @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, updatable = false, columnDefinition = "text")
    private System sourceSystem;

    @Id @Column(name = "source_exercise_id", nullable = false, updatable = false, columnDefinition = "text")
    private String sourceExerciseId;

    @Column(name = "standard_exercise_id", nullable = false, updatable = false)
    private UUID standardExerciseId;

    @Column(name = "original_name", nullable = false, columnDefinition = "text")
    private String originalName;

    @Column(name = "original_gif_reference", columnDefinition = "text")
    private String originalGifReference;

    @Transient
    private boolean newSource = true;

    protected ExerciseSource() { }

    @Override public Key getId() { return new Key(sourceSystem, sourceExerciseId); }
    @Override public boolean isNew() { return newSource; }

    @PostLoad @PostPersist
    private void markPersisted() { newSource = false; }

    public static ExerciseSource create(System sourceSystem, String sourceExerciseId, UUID standardExerciseId,
                                        String originalName, String originalGifReference) {
        var source = new ExerciseSource();
        source.sourceSystem = Objects.requireNonNull(sourceSystem);
        source.sourceExerciseId = requireText(sourceExerciseId);
        source.standardExerciseId = Objects.requireNonNull(standardExerciseId);
        source.updateMetadata(originalName, originalGifReference);
        return source;
    }

    public void updateMetadata(String originalName, String originalGifReference) {
        requireText(originalName);
        if (originalGifReference != null) requireText(originalGifReference);
        this.originalName = originalName;
        this.originalGifReference = originalGifReference;
    }

    private static String requireText(String text) {
        if (Objects.requireNonNull(text).isBlank()) throw new IllegalArgumentException("Blank exercise source value");
        return text;
    }

    public System getSourceSystem() { return sourceSystem; }
    public String getSourceExerciseId() { return sourceExerciseId; }
    public UUID getStandardExerciseId() { return standardExerciseId; }
    public String getOriginalName() { return originalName; }
    public String getOriginalGifReference() { return originalGifReference; }

    public static class Key implements Serializable {
        public System sourceSystem;
        public String sourceExerciseId;

        public Key() { }

        public Key(System sourceSystem, String sourceExerciseId) {
            this.sourceSystem = sourceSystem;
            this.sourceExerciseId = sourceExerciseId;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Key key && sourceSystem == key.sourceSystem
                    && Objects.equals(sourceExerciseId, key.sourceExerciseId);
        }

        @Override public int hashCode() { return Objects.hash(sourceSystem, sourceExerciseId); }
    }
}
