package com.overload.testfixture;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/** Explicit isolated fixtures; account provisioning never seeds either catalog or personal definitions. */
public final class CatalogFixtures {
    private CatalogFixtures() { }
    public static UUID exercise(JdbcTemplate jdbc) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO standard_exercise(id,name) VALUES (?,'test standard')",id);
        return id;
    }
    public static UUID manufacturer(JdbcTemplate jdbc) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO manufacturer(id,name) VALUES (?,'test manufacturer')",id);
        return id;
    }
    public static void personalExercises(JdbcTemplate jdbc, UUID owner) {
        for (String name : com.overload.overload_server.account.AccountSeed.WEIGHT_EXERCISE_NAMES)
            jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name) VALUES (?,?,0,?)",UUID.randomUUID(),owner,name);
    }
}
