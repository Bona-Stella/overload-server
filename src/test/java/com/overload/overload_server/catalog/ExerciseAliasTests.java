package com.overload.overload_server.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.UUID;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class ExerciseAliasTests {
    @ParameterizedTest
    @MethodSource("normalizedAliases")
    void normalizesCompatibilityCharactersUnicodeWhitespaceAndCase(String input, String expected) {
        var alias = ExerciseAlias.create(UUID.randomUUID(), input);
        assertThat(alias.getNormalizedAlias()).isEqualTo(expected);
        assertThat(alias.getAlias()).isEqualTo(input);
    }

    static Stream<Arguments> normalizedAliases() {
        return Stream.of(Arguments.of("　ＢＥＮＣＨ　ＰＲＥＳＳ　", "bench press"),
                Arguments.of(" Bench\t\nPress ", "bench press"),
                Arguments.of("\u00a0Bench\u2009\u2009Press\u00a0", "bench press"),
                Arguments.of("　불스스　", "불스스"), Arguments.of("I", "i"));
    }

    @Test void rejectsEmptyNormalizedAliasAndNul() {
        for (String input : new String[]{"", " \t\n", "\u00a0\u2009　", "bench\0press"}) {
            assertThatThrownBy(() -> ExerciseAlias.create(UUID.randomUUID(), input)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
