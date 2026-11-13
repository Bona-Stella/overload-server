package com.overload.testfixture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres")
class ApprovedCatalogNormalizationIntegrationTests {
    @Test void normalizedSeedUsesOneShoulderExerciseAndIndependentEquipmentInBothRoutinePlacements() throws Exception {
        try(var environment=V22BrowserEnvironment.start(UUID.randomUUID(),false,true)) {
            var jdbc=environment.context().getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM standard_exercise",Integer.class)).isEqualTo(30);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM standard_exercise WHERE name='머신 숄더 프레스'",Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM (SELECT name FROM standard_exercise GROUP BY name HAVING count(*)>1) duplicates",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_weight_placements",Integer.class)).isEqualTo(33);
            assertThat(jdbc.queryForList("SELECT DISTINCT m.name FROM routine_weight_placements p JOIN standard_exercise e ON e.id=p.standard_exercise_id JOIN manufacturer m ON m.id=p.manufacturer_id WHERE e.name='머신 숄더 프레스' ORDER BY m.name",String.class))
                    .containsExactly("Arsenal Strength","Hammer Strength");
            assertThat(jdbc.queryForList("SELECT DISTINCT p.load_type FROM routine_weight_placements p JOIN standard_exercise e ON e.id=p.standard_exercise_id WHERE e.name='머신 숄더 프레스' ORDER BY p.load_type",String.class))
                    .containsExactly("PLATE","STACK");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_muscle m JOIN standard_exercise e ON e.id=m.standard_exercise_id WHERE e.name='머신 숄더 프레스' AND m.role='PRIMARY'",Integer.class)).isOne();
        }
    }
}
