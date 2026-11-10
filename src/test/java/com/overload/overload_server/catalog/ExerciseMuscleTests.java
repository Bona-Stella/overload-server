package com.overload.overload_server.catalog;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ExerciseMuscleTests {
    @Test
    void primaryRequiresPositiveOrderAndSecondaryRejectsAnyOrder() {
        var exercise = UUID.randomUUID();
        var muscle = UUID.randomUUID();
        assertThat(ExerciseMuscle.create(exercise, muscle, ExerciseMuscle.Role.PRIMARY, 1).getPrimaryOrder()).isEqualTo(1);
        assertThat(ExerciseMuscle.create(exercise, muscle, ExerciseMuscle.Role.SECONDARY, null).getPrimaryOrder()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() ->
                ExerciseMuscle.create(exercise, muscle, ExerciseMuscle.Role.PRIMARY, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ExerciseMuscle.create(exercise, muscle, ExerciseMuscle.Role.PRIMARY, 0));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ExerciseMuscle.create(exercise, muscle, ExerciseMuscle.Role.SECONDARY, 1));
    }
}
