package com.overload.overload_server.catalog;

import jakarta.persistence.*;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Entity
@Table(name = "exercise_alias")
@IdClass(ExerciseAlias.Key.class)
public class ExerciseAlias {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    @Id @Column(name = "standard_exercise_id", nullable = false, updatable = false) private UUID standardExerciseId;
    @Id @Column(name = "normalized_alias", nullable = false, updatable = false, columnDefinition = "text") private String normalizedAlias;
    @Column(name = "alias", nullable = false, columnDefinition = "text") private String alias;

    protected ExerciseAlias() { }

    public static ExerciseAlias create(UUID standardExerciseId, String alias) {
        String normalized = normalize(alias);
        if (normalized.isEmpty() || alias.indexOf('\0') >= 0) throw new IllegalArgumentException("Alias must not be blank or contain NUL");
        var value = new ExerciseAlias();
        value.standardExerciseId = Objects.requireNonNull(standardExerciseId);
        value.alias = alias; value.normalizedAlias = normalized;
        return value;
    }

    static String normalize(String alias) {
        return WHITESPACE.matcher(Normalizer.normalize(Objects.requireNonNull(alias), Normalizer.Form.NFKC))
                .replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    public UUID getStandardExerciseId() { return standardExerciseId; }
    public String getAlias() { return alias; }
    public String getNormalizedAlias() { return normalizedAlias; }

    public static class Key implements Serializable {
        public UUID standardExerciseId;
        public String normalizedAlias;
        public Key() { }
        public Key(UUID standardExerciseId, String normalizedAlias) {
            this.standardExerciseId = standardExerciseId; this.normalizedAlias = normalizedAlias;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Key id && Objects.equals(standardExerciseId, id.standardExerciseId)
                    && Objects.equals(normalizedAlias, id.normalizedAlias);
        }
        @Override public int hashCode() { return Objects.hash(standardExerciseId, normalizedAlias); }
    }
}
