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
    private static final java.util.List<String> LEGACY_PERSONAL_NAMES = java.util.List.of(
			"벤치프레스", "인클라인 덤벨 프레스", "체스트 프레스", "케이블 플라이",
			"랫풀다운", "시티드 케이블 로우", "바벨 로우", "풀업",
			"스쿼트", "레그 프레스", "레그 익스텐션", "레그 컬", "루마니안 데드리프트",
			"숄더 프레스", "사이드 레터럴 레이즈", "리어 델트 플라이",
			"바벨 컬", "덤벨 컬", "케이블 푸시다운", "오버헤드 트라이셉스 익스텐션");

    public static void personalExercises(JdbcTemplate jdbc, UUID owner) {
        for (String name : LEGACY_PERSONAL_NAMES)
            jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name) VALUES (?,?,0,?)",UUID.randomUUID(),owner,name);
    }
}
