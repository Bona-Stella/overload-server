package com.overload.overload_server.catalog;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.workoutsession.InitialPlan;
import com.overload.overload_server.workoutsession.WorkoutSnapshotData;
import com.overload.testfixture.*;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static com.overload.overload_server.catalog.CatalogReviewService.Reason.*;

@Tag("postgres")
@Testcontainers
class CatalogIntegrationTests {
    static final String SEARCH = "/api/v1/standard-exercises/search-data", MANUFACTURERS = "/api/v1/manufacturers";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_catalog_api_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext app;
    static JdbcTemplate jdbc;
    Browser browser;

    @BeforeAll static void start() {
        assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432, 5433);
        assertThat(POSTGRES.getDatabaseName()).isEqualTo("overload_catalog_api_test");
        var key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        app = new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class, WorkoutReadFixtures.class)
                .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(), "--server.address=127.0.0.1", "--server.port=0",
                        "--OTP_HMAC_KEY=" + Base64.getEncoder().encodeToString(key), "--OTP_EMAIL_ENABLED=false", "--GOOGLE_OIDC_ENABLED=false",
                        "--GOOGLE_REAUTH_ENABLED=false", "--spring.session.jdbc.cleanup-cron=-", "--logging.level.root=WARN", "--spring.main.banner-mode=off");
        jdbc = app.getBean(JdbcTemplate.class);
        MixedPersistenceAssertions.verify(app);
        assertThat(app.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        app.getBean(Flyway.class).validate();
        assertThat(app.getBean(Flyway.class).info().current().getVersion().toString()).isEqualTo("35");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standard_exercise", Integer.class)).isZero();
    }

    @BeforeEach void prepare() throws Exception {
        WorkoutReadFixtures.observer = null;
        EmailOtpFixtures.CLOCK.reset(); app.getBean(EmailOtpFixtures.FakeSender.class).reset();
        jdbc.update("DELETE FROM exercise_db_v1_candidate");
        jdbc.update("DELETE FROM exercise_alias"); jdbc.update("DELETE FROM exercise_muscle");
        jdbc.update("DELETE FROM exercise_source");
        jdbc.update("DELETE FROM muscle WHERE id < 'd3200000-0000-4000-8000-000000000001'"); jdbc.update("DELETE FROM standard_exercise"); jdbc.update("DELETE FROM manufacturer");
        jdbc.update("DELETE FROM email_otp_rate_limits");
        browser = new Browser(); browser.login();
    }

    @AfterEach void cleanup() { WorkoutReadFixtures.observer = null; if (browser != null) browser.close(); }
    @AfterAll static void stop() { if (app != null) app.close(); }

    @Test void emptyCatalogRespondsWithoutSeedingOrMixingPersonalExercises() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions", Integer.class)).isZero();
        var owner=jdbc.queryForObject("SELECT id FROM users LIMIT 1",UUID.class);
        CatalogFixtures.personalExercises(jdbc,owner);
        assertThat(success(browser.get(SEARCH))).isEqualTo(JSON.readTree("{\"schemaVersion\":1,\"exercises\":[]}"));
        assertThat(success(browser.get(MANUFACTURERS))).isEqualTo(JSON.readTree("{\"manufacturers\":[]}"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standard_exercise", Integer.class)).isZero();
    }

    @Test void activeExercisesAndAliasesAreUniqueOrderedSharedAndLightweight() throws Exception {
        exercise(1, "바벨 벤치프레스", true); exercise(2, "덤벨 벤치프레스", true);
        exercise(3, "불가리안 스플릿 스쿼트", true); exercise(4, "하이로우", false);
        alias(1, "벤치"); alias(1, "barbell bench press"); alias(2, "벤치"); alias(4, "숨김 별칭");
        var response = browser.get(SEARCH); var data = success(response);
        assertThat(data.size()).isEqualTo(2); assertThat(data.get("schemaVersion").asInt()).isEqualTo(1);
        var values = data.get("exercises"); assertThat(values.size()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(values.get(i).get("id").asString()).isEqualTo(id(i + 1).toString());
            assertThat(values.get(i).size()).isEqualTo(4);
            assertThat(values.get(i).get("muscles").isArray()).isTrue();
        }
        assertThat(values.get(0).get("aliases")).isEqualTo(JSON.readTree("[\"barbell bench press\",\"벤치\"]"));
        assertThat(values.get(1).get("aliases")).isEqualTo(JSON.readTree("[\"벤치\"]"));
        assertThat(values.get(2).get("aliases").isEmpty()).isTrue();
        assertThat(browser.get(SEARCH).body()).isEqualTo(response.body());
        try (var other = new Browser()) { other.login(); assertThat(other.get(SEARCH).body()).isEqualTo(response.body()); }
    }

    @Test void manufacturersAreActiveOnlyOrderedAndIndependentFromExercises() throws Exception {
        manufacturer(1, "파나타", true); manufacturer(2, "해머스트렝스", true); manufacturer(3, "비활성", false);
        var response = browser.get(MANUFACTURERS); var items = success(response).get("manufacturers");
        assertThat(items.size()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            assertThat(items.get(i).size()).isEqualTo(2);
            assertThat(items.get(i).get("id").asString()).isEqualTo(id(i + 1).toString());
        }
        assertThat(items.get(0).get("name").asString()).isEqualTo("파나타");
        assertThat(browser.get(MANUFACTURERS).body()).isEqualTo(response.body());
        assertThat(success(browser.get(SEARCH)).get("exercises").isEmpty()).isTrue();
    }

    @Test void reviewedJointTypesRoundTripWithoutChangingSearchContractOrGuessingUnknownValues() throws Exception {
        var repository = app.getBean(StandardExerciseRepository.class);
        for (var type : StandardExercise.JointType.values()) {
            UUID key = id(type.ordinal() + 1);
            repository.saveAndFlush(StandardExercise.create(key, type.name(), type, true, NOW));
            assertThat(repository.findById(key)).get().extracting(StandardExercise::getJointType).isEqualTo(type);
        }
        repository.saveAndFlush(StandardExercise.create(id(4), "unreviewed", true, NOW));
        assertThat(repository.findById(id(4))).get().extracting(StandardExercise::getJointType).isNull();
        var values = success(browser.get(SEARCH)).get("exercises");
        assertThat(values.isEmpty()).isTrue();
    }

    @Test void allFiveJpaMappingsRoundTripCompositeKeysAndMultiplePrimaryMuscles() {
        exercise(1, "동일 이름", true); exercise(2, "동일 이름", true); manufacturer(1, "독립 제조사", true);
        var stored = app.getBean(ExerciseAliasRepository.class).saveAndFlush(ExerciseAlias.create(id(1), "　ＢＥＮＣＨ\u00a0 PRESS　"));
        assertThat(app.getBean(ExerciseAliasRepository.class).findById(new ExerciseAlias.Key(id(1), "bench press")))
                .get().extracting(ExerciseAlias::getAlias).isEqualTo(stored.getAlias());
        var muscles = app.getBean(MuscleRepository.class);
        muscles.saveAndFlush(Muscle.create(id(11), Muscle.Group.CHEST, "근육1", true));
        muscles.saveAndFlush(Muscle.create(id(12), Muscle.Group.CHEST, "근육2", true));
        var links = app.getBean(ExerciseMuscleRepository.class);
        links.saveAndFlush(ExerciseMuscle.create(id(1), id(11), ExerciseMuscle.Role.PRIMARY, 1));
        links.saveAndFlush(ExerciseMuscle.create(id(1), id(12), ExerciseMuscle.Role.PRIMARY, 2));
        assertThat(links.findAll()).hasSize(2).allMatch(value -> value.getRole() == ExerciseMuscle.Role.PRIMARY);
        assertThat(links.findById(new ExerciseMuscle.Key(id(1), id(11)))).isPresent();
        assertThat(muscles.findById(id(11))).get().extracting(Muscle::getGroup).isEqualTo(Muscle.Group.CHEST);
        assertThat(app.getBean(StandardExerciseRepository.class).findAll()).hasSize(2);
        assertThat(app.getBean(ManufacturerRepository.class).findById(id(1))).get().extracting(Manufacturer::getName).isEqualTo("독립 제조사");
    }

    @Test void aliasUniquenessIsPerExerciseAndConstraintsRejectInvalidRowsAndCascadingDeletes() {
        exercise(1, "벤치", true); exercise(2, "벤치", true); alias(1, "Bench"); alias(2, "BENCH");
        dbError("23505", () -> jdbc.update("INSERT INTO exercise_alias VALUES (?, 'Ｂｅｎｃｈ', 'bench')", id(1)));
        jdbc.update("INSERT INTO muscle(id,group_code,name) VALUES (?,'CHEST','근육1'),(?,'CHEST','근육2')", id(11), id(12));
        jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'PRIMARY',1),(?,?,'PRIMARY',2)", id(1), id(11), id(1), id(12));
        dbError("23505", () -> jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'SECONDARY',NULL)", id(1), id(11)));
        dbError("23514", () -> jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'OTHER',NULL)", id(2), id(11)));
        dbError("23514", () -> jdbc.update("INSERT INTO muscle(id,group_code,name) VALUES (?,'UNKNOWN','근육')", id(13)));
        dbError("23514", () -> jdbc.update("INSERT INTO standard_exercise(id,name) VALUES (?, E' \\t\\n')", id(3)));
        dbError("23514", () -> jdbc.update("INSERT INTO manufacturer(id,name) VALUES (?, ' ')", id(3)));
        dbError("23514", () -> jdbc.update("INSERT INTO muscle(id,group_code,name) VALUES (?,'CORE',' ')", id(13)));
        dbError("23514", () -> jdbc.update("INSERT INTO exercise_alias VALUES (?, ' ', 'blank')", id(1)));
        dbError("23514", () -> jdbc.update("INSERT INTO exercise_alias VALUES (?, 'alias', ' ')", id(1)));
        dbError("23503", () -> jdbc.update("INSERT INTO exercise_alias VALUES (?, 'missing', 'missing')", id(99)));
        dbError("23503", () -> jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'PRIMARY',1)", id(99), id(11)));
        dbError("23503", () -> jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'PRIMARY',3)", id(1), id(99)));
        dbError("23503", () -> jdbc.update("DELETE FROM standard_exercise WHERE id=?", id(1)));
        dbError("23503", () -> jdbc.update("DELETE FROM muscle WHERE id=?", id(11)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_alias", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_muscle", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT is_active AND created_at=updated_at FROM standard_exercise WHERE id=?", Boolean.class, id(2))).isTrue();
    }

    @Test void reviewsAreIndependentAndSearchRequiresBothAndSupportedRecording() throws Exception {
        var repository = app.getBean(StandardExerciseRepository.class);
        var candidate = repository.saveAndFlush(StandardExercise.create(id(1), "검수 중", true, NOW));
        var cardio = StandardExercise.create(id(2), "실내 자전거", true, NOW);
        cardio.updateDetails("실내 자전거", null, StandardExercise.ExerciseType.CARDIO,
                StandardExercise.RecordMethod.CARDIO, null, null, null, NOW);
        cardio.reviewMuscleTargets(true, NOW);
        cardio.reviewSplits(true, NOW);
        repository.saveAndFlush(cardio);
        assertThat(repository.findById(id(2))).get().matches(value ->
                value.getRecordMethod() == StandardExercise.RecordMethod.CARDIO
                        && value.isMuscleTargetsReviewed() && value.isSplitsReviewed());
        assertThat(candidate.isMuscleTargetsReviewed()).isFalse();
        assertThat(candidate.isSplitsReviewed()).isFalse();
        alias(1, "draft");
        assertThat(success(browser.get(SEARCH)).get("exercises").isEmpty()).isTrue();

        candidate.reviewMuscleTargets(true, NOW);
        repository.saveAndFlush(candidate);
        assertThat(success(browser.get(SEARCH)).get("exercises").isEmpty()).isTrue();

        candidate.reviewMuscleTargets(false, NOW);
        candidate.reviewSplits(true, NOW);
        repository.saveAndFlush(candidate);
        assertThat(success(browser.get(SEARCH)).get("exercises").isEmpty()).isTrue();

        candidate.reviewMuscleTargets(true, NOW);
        candidate.updateDetails("플랭크", StandardExercise.JointType.OTHER, StandardExercise.ExerciseType.STATIC_HOLD,
                StandardExercise.RecordMethod.HOLD_DURATION, "자세를 유지한다.", "매트", "private-plank", NOW);
        candidate.updateSplits(StandardExercise.PplSplit.PUSH, StandardExercise.FrontBackSplit.FRONT, NOW);
        repository.saveAndFlush(candidate);
        var stored = repository.findById(id(1)).orElseThrow();
        assertThat(stored.getRecordMethod()).isEqualTo(StandardExercise.RecordMethod.HOLD_DURATION);
        assertThat(stored.getExerciseType()).isEqualTo(StandardExercise.ExerciseType.STATIC_HOLD);
        assertThat(stored.getInstructionsKo()).isEqualTo("자세를 유지한다.");
        assertThat(stored.getEquipmentDescription()).isEqualTo("매트");
        assertThat(stored.getRepresentativeGifKey()).isEqualTo("private-plank");
        assertThat(stored.getPplSplit()).isEqualTo(StandardExercise.PplSplit.PUSH);
        assertThat(stored.getFrontBackSplit()).isEqualTo(StandardExercise.FrontBackSplit.FRONT);
        assertThat(success(browser.get(SEARCH)).get("exercises").isEmpty()).isTrue();

        candidate.updateDetails("벤치", null, StandardExercise.ExerciseType.WEIGHT,
                StandardExercise.RecordMethod.SETS_REPS, null, null, "private-bench", NOW);
        candidate.updateSplits(null, null, NOW);
        repository.saveAndFlush(candidate);
        stored = repository.findById(id(1)).orElseThrow();
        assertThat(stored.getPplSplit()).isNull();
        assertThat(stored.getFrontBackSplit()).isNull();
        assertThat(stored.isMuscleTargetsReviewed()).isTrue();
        assertThat(stored.isSplitsReviewed()).isTrue();
        var item = success(browser.get(SEARCH)).get("exercises").get(0);
        assertThat(item.size()).isEqualTo(4);
        assertThat(item.get("aliases").get(0).asString()).isEqualTo("draft");
        assertThat(item.get("muscles").isEmpty()).isTrue();
        assertThat(item.toString()).doesNotContain("private-bench");
    }

    @Test void orderedMuscleReplacementIsAtomicAndDoesNotApproveMapping() throws Exception {
        exercise(1, "벤치", true);
        var repository = app.getBean(StandardExerciseRepository.class);
        var draft = repository.findById(id(1)).orElseThrow();
        draft.reviewMuscleTargets(false, NOW);
        repository.saveAndFlush(draft);
        jdbc.update("INSERT INTO muscle(id,group_code,name) VALUES (?,'CHEST','A'),(?,'CHEST','B'),(?,'ARMS','C'),(?,'ARMS','D')",
                id(11), id(12), id(13), id(14));
        var service = app.getBean(ExerciseMuscleService.class);
        service.replace(id(1), List.of(id(11), id(12)), List.of(id(13), id(14)));
        var links = app.getBean(ExerciseMuscleRepository.class);
        var transaction = new TransactionTemplate(app.getBean("workoutTransactionManager", PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> {
            assertThat(links.findAllByStandardExerciseId(id(1))).hasSize(4);
            service.replace(id(1), List.of(id(12), id(11)), List.of(id(13), id(14)));
        });
        assertThat(links.findById(new ExerciseMuscle.Key(id(1), id(12)))).get()
                .extracting(ExerciseMuscle::getPrimaryOrder).isEqualTo(1);
        assertThat(links.findById(new ExerciseMuscle.Key(id(1), id(14)))).get()
                .extracting(ExerciseMuscle::getPrimaryOrder).isNull();
        assertThatThrownBy(() -> service.replace(id(1), List.of(id(11)), List.of(id(11))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replace(id(1), List.of(id(99)), List.of()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(links.findAll()).hasSize(4);
        dbError("23505", () -> jdbc.update("UPDATE exercise_muscle SET primary_order=1 WHERE muscle_id=?", id(11)));
        dbError("23514", () -> jdbc.update("UPDATE exercise_muscle SET primary_order=NULL WHERE muscle_id=?", id(11)));
        dbError("23514", () -> jdbc.update("UPDATE exercise_muscle SET primary_order=0 WHERE muscle_id=?", id(11)));
        dbError("23514", () -> jdbc.update("UPDATE exercise_muscle SET primary_order=3 WHERE muscle_id=?", id(13)));
        assertThat(success(browser.get(SEARCH)).get("exercises").isEmpty()).isTrue();

        draft.reviewMuscleTargets(true, NOW);
        repository.saveAndFlush(draft);
        var visible = success(browser.get(SEARCH)).get("exercises").get(0).get("muscles");
        var names = new ArrayList<String>();
        visible.forEach(item -> names.add(item.get("name").asString()));
        assertThat(names).containsExactly("B", "A", "C", "D");
    }

    @Test void multipleSourceIdsPreserveOneStandardUuidAndRemainPrivate() throws Exception {
        exercise(1, "벤치", true);
        var sources = app.getBean(ExerciseSourceRepository.class);
        exercise(2, "다른 운동", false);
        var system = ExerciseSource.System.EXERCISEDB_V1;
        var first = sources.saveAndFlush(ExerciseSource.create(system, "ex-db-A", id(1), "Bench Press", "https://source.invalid/a"));
        sources.saveAndFlush(ExerciseSource.create(system, "ex-db-B", id(1), "Barbell Bench Press", null));
        first.updateMetadata("Flat Bench Press", "https://source.invalid/updated");
        sources.saveAndFlush(first);
        assertThatThrownBy(() -> sources.saveAndFlush(ExerciseSource.create(system, "ex-db-A", id(2), "duplicate", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sources.findAllByStandardExerciseIdOrderBySourceExerciseId(id(2))).isEmpty();
        var matches = sources.findAllByStandardExerciseIdOrderBySourceExerciseId(id(1));
        assertThat(matches).extracting(ExerciseSource::getSourceExerciseId).containsExactly("ex-db-A", "ex-db-B");
        assertThat(matches.getFirst().getOriginalName()).isEqualTo("Flat Bench Press");
        assertThat(matches.getFirst().getOriginalGifReference()).isEqualTo("https://source.invalid/updated");
        assertThat(matches).extracting(ExerciseSource::getStandardExerciseId).containsOnly(id(1));
        dbError("23505", () -> jdbc.update("INSERT INTO exercise_source VALUES ('EXERCISEDB_V1','ex-db-A',?,'duplicate',NULL)", id(1)));
        dbError("23503", () -> jdbc.update("INSERT INTO exercise_source VALUES ('EXERCISEDB_V1','ex-db-C',?,'missing exercise',NULL)", id(99)));
        var response = browser.get(SEARCH);
        assertThat(success(response).get("exercises").get(0).get("id").asString()).isEqualTo(id(1).toString());
        assertThat(response.body()).doesNotContain("source.invalid", "ex-db-", "Flat Bench Press");
    }

    @Test void publicCatalogIncludesReadOnlyExercisesAndChoicesRequireSupportedRecording() throws Exception {
        exercise(1, "바벨 벤치프레스", true);
        alias(1, "barbell bench press");
        var running = CatalogFixtures.reviewedCardio(jdbc, "트레드밀 러닝", CatalogFixtures.TREADMILL);
        var outdoors = CatalogFixtures.reviewedCardio(jdbc, "야외 러닝", CatalogFixtures.OUTDOOR);
        var shared = CatalogFixtures.reviewedCardio(jdbc, "인터벌 러닝", CatalogFixtures.TREADMILL);
        var draft = CatalogFixtures.reviewedCardio(jdbc, "검수 중", CatalogFixtures.TREADMILL);
        jdbc.update("UPDATE standard_exercise SET splits_reviewed=false WHERE id=?", draft);
        var inactive = CatalogFixtures.reviewedCardio(jdbc, "비활성", CatalogFixtures.TREADMILL);
        jdbc.update("UPDATE standard_exercise SET is_active=false WHERE id=?", inactive);
        app.getBean(ExerciseAliasRepository.class).saveAndFlush(ExerciseAlias.create(running, "treadmill run"));
        jdbc.update("""
                INSERT INTO standard_exercise(id,name,exercise_type,record_method,muscle_targets_reviewed,splits_reviewed)
                VALUES (?,'플랭크','STATIC_HOLD','HOLD_DURATION',true,true),
                       (?,'스트레칭','STRETCH','UNSPECIFIED',true,true),
                       (?,'미연결 유산소','CARDIO','CARDIO',true,true)
                """, id(2), id(3), id(4));
        var general = success(browser.get("/api/v1/standard-exercises/catalog")).get("exercises");
        assertThat(general).extracting(e -> e.get("name").asString())
                .containsExactlyInAnyOrder("바벨 벤치프레스", "트레드밀 러닝", "야외 러닝", "인터벌 러닝", "플랭크", "스트레칭", "미연결 유산소");
        assertThat(general.get(0).get("aliases").get(0).asString()).isEqualTo("barbell bench press");
        assertThat(success(browser.get(SEARCH)).get("exercises")).extracting(e -> e.get("name").asString())
                .containsExactly("바벨 벤치프레스");
        var choices = success(browser.get("/api/v1/standard-exercises/cardio-choices")).get("activities");
        assertThat(choices).extracting(e -> UUID.fromString(e.get("id").asString())).containsExactlyInAnyOrder(running, outdoors, shared);
        assertThat(choices).allMatch(e -> e.get("category").asString().equals("RUNNING"));
        assertThat(choices).extracting(e -> e.get("kind").asString()).containsExactlyInAnyOrder("TREADMILL", "TREADMILL", "OUTDOOR");
    }

    @Test void publicDetailContainsOrderedTargetsAndInstructionsButNeverSourceMetadata() throws Exception {
        exercise(1, "바벨 벤치프레스", true);
        var service = reviews();
        addSources();
        var stored = service.replace(id(1), change(service.get(id(1)), weight("바벨 벤치프레스",
                List.of("벤치 프레스", "Bench press"), List.of(muscle(3), muscle(1)), List.of(muscle(23)), true, true), source("bench")));
        var response = browser.get(detailPath(id(1)));
        var detail = success(response);
        assertThat(detail.get("id").asString()).isEqualTo(id(1).toString());
        assertThat(detail.get("name").asString()).isEqualTo("바벨 벤치프레스");
        assertThat(detail.get("aliases")).extracting(JsonNode::asString).containsExactly("Bench press", "벤치 프레스");
        assertThat(detail.get("recordMethod").asString()).isEqualTo("SETS_REPS");
        assertThat(detail.get("exerciseType").asString()).isEqualTo("WEIGHT");
        assertThat(detail.get("equipmentDescription").asString()).isEqualTo("바벨");
        assertThat(detail.get("jointType").asString()).isEqualTo("COMPOUND");
        assertThat(detail.get("pplSplit").asString()).isEqualTo("PUSH");
        assertThat(detail.get("frontBackSplit").isNull()).isTrue();
        assertThat(detail.get("instructionSteps")).extracting(JsonNode::asString).containsExactly("바를 잡는다.", "바를 민다.");
        assertThat(detail.get("primaryMuscles")).extracting(value -> value.get("id").asString())
                .containsExactly(muscle(3).toString(), muscle(1).toString());
        assertThat(detail.get("primaryMuscles")).extracting(value -> value.get("primaryOrder").asInt()).containsExactly(1, 2);
        assertThat(detail.get("secondaryMuscles")).hasSize(1).allSatisfy(value -> {
            assertThat(value.get("id").asString()).isEqualTo(muscle(23).toString());
            assertThat(value.has("primaryOrder")).isFalse();
        });
        assertThat(detail.get("selectableForRoutine").asBoolean()).isTrue();
        assertThat(response.body()).doesNotContain("original", "sourceExerciseId", "representativeGif", "private.invalid");
        assertThat(stored.sources()).extracting(CatalogReviewService.Source::id)
                .containsExactly(source("bench"), source("bench-alternate"), source("missing-gif"));
        assertThat(stored.representativeGifKey()).isEqualTo("https://private.invalid/bench");
        assertThat(service.get(id(1))).isEqualTo(stored);
    }

    @Test void detailHidesEachUnpublishedStateAndRequiresAnAuthenticatedSession() throws Exception {
        exercise(1, "근육 검수 대기", true);
        exercise(2, "분할 검수 대기", true);
        exercise(3, "비활성", false);
        jdbc.update("UPDATE standard_exercise SET muscle_targets_reviewed=false WHERE id=?", id(1));
        jdbc.update("UPDATE standard_exercise SET splits_reviewed=false WHERE id=?", id(2));
        for (int value : List.of(1, 2, 3, 99)) error(browser.get(detailPath(id(value))), 404, "EXERCISE_NOT_FOUND");
        try (var anonymous = new Browser()) {
            error(anonymous.get(detailPath(id(1))), 401, "AUTHENTICATION_REQUIRED");
        }
        error(browser.get("/api/v1/standard-exercises/not-a-uuid"), 400, "INVALID_REQUEST");
        error(browser.get(detailPath(id(1)) + "?draft=true"), 400, "INVALID_REQUEST");
    }

    @Test void detailShowsUnsupportedAndUnlinkedCardioWithoutMakingThemSelectable() throws Exception {
        jdbc.update("""
                INSERT INTO standard_exercise(id,name,exercise_type,record_method,muscle_targets_reviewed,splits_reviewed)
                VALUES (?,'플랭크','STATIC_HOLD','HOLD_DURATION',true,true),
                       (?,'스트레칭','STRETCH','UNSPECIFIED',true,true),
                       (?,'미연결 유산소','CARDIO','CARDIO',true,true)
                """, id(1), id(2), id(3));
        var linked = CatalogFixtures.reviewedCardio(jdbc, "트레드밀 러닝", CatalogFixtures.TREADMILL);
        for (var exerciseId : List.of(id(1), id(2), id(3))) {
            var detail = success(browser.get(detailPath(exerciseId)));
            assertThat(detail.get("selectableForRoutine").asBoolean()).isFalse();
            assertThat(detail.get("instructionSteps")).isEmpty();
        }
        assertThat(success(browser.get(detailPath(linked))).get("selectableForRoutine").asBoolean()).isTrue();
    }

    @Test void reviewFlagsAreIndependentAndOnlyReviewedWeightsRequireAnActivePrimary() throws Exception {
        var service = reviews();
        var draft = StandardExercise.create(id(1), "검수 대기", true, NOW);
        app.getBean(StandardExerciseRepository.class).saveAndFlush(draft);
        var review = service.get(id(1));
        var splitApproved = service.replace(id(1), change(review, weight("분할만 검수",
                List.of(), List.of(), List.of(), false, true), null));
        assertThat(splitApproved.content().splitsReviewed()).isTrue();
        assertThat(splitApproved.content().muscleTargetsReviewed()).isFalse();
        reject(PRIMARY_REQUIRED, () -> service.replace(id(1), change(splitApproved, weight("근육 없는 웨이트",
                List.of(), List.of(), List.of(), true, true), null)));
        reject(INVALID_TARGETS, () -> service.replace(id(1), change(splitApproved, weight("없는 근육",
                List.of(), List.of(id(99)), List.of(), true, true), null)));
        reject(INVALID_TARGETS, () -> service.replace(id(1), change(splitApproved, weight("중복 근육",
                List.of(), List.of(muscle(1)), List.of(muscle(1)), true, true), null)));
        assertThat(service.get(id(1))).isEqualTo(splitApproved);
        var targetsApproved = service.replace(id(1), change(splitApproved, weight("근육만 검수",
                List.of(), List.of(muscle(1)), List.of(), true, false), null));
        assertThat(targetsApproved.content().muscleTargetsReviewed()).isTrue();
        assertThat(targetsApproved.content().splitsReviewed()).isFalse();
        error(browser.get(detailPath(id(1))), 404, "EXERCISE_NOT_FOUND");

        var cardioId = CatalogFixtures.reviewedCardio(jdbc, "러닝머신", CatalogFixtures.TREADMILL);
        var cardio = new CatalogReviewService.Content("러닝머신", List.of(), StandardExercise.ExerciseType.CARDIO,
                StandardExercise.RecordMethod.CARDIO, null, null, null, List.of(), List.of(), null, null, true, true);
        var approved = service.replace(cardioId, change(service.get(cardioId), cardio, null));
        assertThat(approved.cardioActivityId()).isEqualTo(CatalogFixtures.TREADMILL);
        assertThat(approved.content()).isEqualTo(cardio);
        var detail = success(browser.get(detailPath(cardioId)));
        assertThat(detail.get("pplSplit").isNull()).isTrue();
        assertThat(detail.get("frontBackSplit").isNull()).isTrue();
        assertThat(detail.get("primaryMuscles")).isEmpty();
        reject(INVALID_DETAILS, () -> service.replace(cardioId, change(approved, weight("웨이트로 변환",
                List.of(), List.of(muscle(1)), List.of(), true, true), null)));
        assertThat(service.get(cardioId)).isEqualTo(approved);
    }

    @Test void reviewValidatesSourceAndRollsBackWrittenDetailsAliasesAndTargetsOnFailure() throws Exception {
        exercise(1, "원래 운동", true);
        alias(1, "original");
        addSources();
        app.getBean(ExerciseMuscleService.class).replace(id(1), List.of(muscle(1)), List.of());
        var service = reviews();
        var before = service.get(id(1));
        var edited = weight("수정된 운동", List.of("updated"), List.of(muscle(2)), List.of(muscle(23)), true, false);
        for (String invalid : List.of("other-exercise", "unknown", "missing-gif")) {
            reject(INVALID_GIF_SOURCE, () -> service.replace(id(1), change(before, edited, source(invalid))));
        }
        reject(INVALID_DETAILS, () -> service.replace(id(1), change(before, weight("별칭 중복",
                List.of("Bench", "  BENCH  "), List.of(muscle(1)), List.of(), true, true), null)));
        assertThat(service.get(id(1))).isEqualTo(before);
        var updated = new AtomicBoolean();
        var aliasesDeleted = new AtomicBoolean();
        var targetsDeleted = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> {
            var sql = event.sql().toLowerCase(Locale.ROOT);
            if (!event.before() && sql.startsWith("update standard_exercise")) updated.set(true);
            if (!event.before() && sql.startsWith("delete from exercise_alias")) aliasesDeleted.set(true);
            if (!event.before() && sql.startsWith("delete from exercise_muscle")) targetsDeleted.set(true);
            if (event.before() && sql.startsWith("insert into exercise_muscle")) {
                throw new DataIntegrityViolationException("isolated catalog write failure");
            }
        };
        try {
            assertThatThrownBy(() -> service.replace(id(1), change(before, edited, source("bench"))))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            WorkoutReadFixtures.observer = null;
        }
        assertThat(updated).isTrue();
        assertThat(aliasesDeleted).isTrue();
        assertThat(targetsDeleted).isTrue();
        assertThat(service.get(id(1))).isEqualTo(before);
        assertThat(success(browser.get(detailPath(id(1)))).get("name").asString()).isEqualTo("원래 운동");
    }

    @Test void simultaneousReviewEditsFromOneRevisionOnlyAllowOneWinner() throws Exception {
        exercise(1, "원래 운동", true);
        var service = reviews();
        var before = service.get(id(1));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of("편집 A", "편집 B").stream().map(name -> executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Review start timed out");
                try {
                    service.replace(id(1), change(before, weight(name, List.of(name), List.of(muscle(1)), List.of(), true, true), null));
                    return name;
                } catch (CatalogReviewService.Rejected rejected) {
                    assertThat(rejected.reason()).isEqualTo(WRITE_CONFLICT);
                    return "";
                }
            })).toList();
            start.countDown();
            var winners = new ArrayList<String>();
            for (var future : futures) {
                var name = future.get(15, TimeUnit.SECONDS);
                if (!name.isEmpty()) winners.add(name);
            }
            assertThat(winners).hasSize(1);
            var current = service.get(id(1));
            assertThat(current.content().name()).isEqualTo(winners.getFirst());
            assertThat(current.content().aliases()).containsExactly(winners.getFirst());
            assertThat(current.updatedAt()).isAfter(before.updatedAt());
        }
    }

    @Test void regularUserHasNoHttpPathToManageTheCatalog() throws Exception {
        exercise(1, "원래 운동", true);
        var before = reviews().get(id(1));
        var csrf = browser.csrf();
        var path = detailPath(id(1));
        assertThat(browser.send("PATCH", path, "{\"name\":\"변조\"}", csrf).statusCode()).isEqualTo(405);
        assertThat(browser.send("PUT", path, "{\"name\":\"변조\"}", csrf).statusCode()).isEqualTo(405);
        assertThat(browser.get("/api/v1/admin/standard-exercises/" + id(1)).statusCode()).isEqualTo(404);
        assertThat(browser.send("PUT", "/api/v1/admin/standard-exercises/" + id(1), "{}", csrf).statusCode()).isEqualTo(404);
        assertThat(reviews().get(id(1))).isEqualTo(before);
    }

    private static CatalogReviewService reviews() { return app.getBean(CatalogReviewService.class); }

    @Test void intakeKeepsOriginalAndIndependentDraftAndNeverPublishesCandidates() throws Exception {
        var intake = app.getBean(ExerciseDbIntakeService.class);
        var input = intakeBatch();
        var inserted = intake.stage(input);
        assertThat(inserted.insertedRows()).isEqualTo(2);
        assertThat(inserted.storedRows()).isEqualTo(2);
        assertThat(inserted.missingIds()).isEmpty();
        var first = intake.get("Case123");
        assertThat(first.original()).isEqualTo(input.rows().getFirst().original());
        assertThat(first.findings()).isEqualTo(input.rows().getFirst().findings());
        assertThat(first.sourceSha256()).isEqualTo(input.sha256());
        assertThat(first.sourcePosition()).isEqualTo(1);
        assertThat(first.draft()).isNull();
        assertThat(first.standardExerciseId()).isNull();
        var draft = intake.saveDraft("Case123", 0, intakeDraft());
        assertThat(draft.draftVersion()).isEqualTo(1);
        assertThat(intake.stage(input).insertedRows()).isZero();
        assertThat(intake.get("Case123")).isEqualTo(draft);
        assertThat(intake.listAfter("Case123", 1)).extracting(ExerciseDbIntakeService.Entry::sourceId).containsExactly("case123");
        assertThatThrownBy(() -> intake.saveDraft("Case123", 0, intakeDraft()))
                .isInstanceOfSatisfying(ExerciseDbIntakeService.Rejected.class,
                        failure -> assertThat(failure.reason()).isEqualTo(ExerciseDbIntakeService.Reason.DRAFT_CONFLICT));
        assertThat(success(browser.get("/api/v1/standard-exercises/catalog")).get("exercises")).isEmpty();
        assertThat(success(browser.get(SEARCH)).get("exercises")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_source", Integer.class)).isZero();
        var csrf = browser.csrf();
        for (String path : List.of("/api/v1/admin/exercisedb/intake", "/api/v1/admin/exercisedb/Case123/link")) {
            assertThat(browser.get(path).statusCode()).isEqualTo(404);
            assertThat(browser.send("POST", path, "{}", csrf).statusCode()).isEqualTo(404);
        }
    }

    @Test void intakeRecoversMissingRowsAndRejectsChangedSourceAtomically() {
        var intake = app.getBean(ExerciseDbIntakeService.class);
        var input = intakeBatch();
        intake.stage(input);
        intake.saveDraft("Case123", 0, intakeDraft());
        var draft = intake.get("Case123");
        // Simulate an incomplete staging run, never a change to an imported standard exercise.
        jdbc.update("DELETE FROM exercise_db_v1_candidate WHERE source_exercise_id='case123'");
        assertThat(intake.stage(input).insertedRows()).isEqualTo(1);
        var changed = ExerciseDbV1Tests.parse(List.of(
                ExerciseDbV1Tests.raw("000new1", "new", "quads", List.of(), List.of("Step:1 Stand.")),
                ExerciseDbV1Tests.raw("Case123", "changed", "quads", List.of(), List.of("Step:1 Stand."))));
        assertThatThrownBy(() -> intake.stage(changed)).isInstanceOfSatisfying(ExerciseDbIntakeService.Rejected.class,
                failure -> assertThat(failure.reason()).isEqualTo(ExerciseDbIntakeService.Reason.SOURCE_CONFLICT));
        assertThat(intake.get("Case123")).isEqualTo(draft);
        assertThat(intake.listAfter(null, 10)).extracting(ExerciseDbIntakeService.Entry::sourceId).containsExactly("Case123", "case123");
    }

    @Test void explicitIntakeLinkAllowsMultipleSourcesAndPreservesExistingCatalog() throws Exception {
        var intake = app.getBean(ExerciseDbIntakeService.class);
        var input = intakeBatch();
        intake.stage(input);
        exercise(1, "기존 운동", true);
        exercise(2, "다른 운동", true);
        alias(1, "existing alias");
        app.getBean(ExerciseMuscleService.class).replace(id(1), List.of(muscle(1)), List.of());
        var before = reviews().get(id(1));
        assertThat(intake.linkExisting("Case123", id(1))).isEqualTo(id(1));
        assertThat(intake.linkExisting("Case123", id(1))).isEqualTo(id(1));
        assertThat(intake.linkExisting("case123", id(1))).isEqualTo(id(1));
        assertThatThrownBy(() -> intake.linkExisting("Case123", id(2)))
                .isInstanceOfSatisfying(ExerciseDbIntakeService.Rejected.class,
                        failure -> assertThat(failure.reason()).isEqualTo(ExerciseDbIntakeService.Reason.ALREADY_LINKED));
        var linked = reviews().get(id(1));
        assertThat(linked.id()).isEqualTo(before.id());
        assertThat(linked.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(linked.content()).isEqualTo(before.content());
        assertThat(linked.sources()).hasSize(2);
        assertThat(linked.sources()).extracting(source -> source.id().exerciseId()).containsExactlyInAnyOrder("Case123", "case123");
        assertThat(linked.sources()).extracting(CatalogReviewService.Source::originalGifReference)
                .containsExactlyInAnyOrder(input.rows().get(0).gifUrl(), input.rows().get(1).gifUrl());
        assertThat(intake.stage(input).insertedRows()).isZero();
        assertThat(intake.get("Case123").standardExerciseId()).isEqualTo(id(1));
        var withRepresentative = reviews().replace(id(1),
                change(linked, linked.content(), source("Case123")));
        assertThat(withRepresentative.representativeGifKey()).isEqualTo(input.rows().getFirst().gifUrl());
        assertThat(browser.get(detailPath(id(1))).body()).doesNotContain("Case123", "case123", "static.exercisedb.dev");
    }

    @Test void explicitNewDraftCreationUsesReviewedFieldsWithoutApprovingOrOverwriting() throws Exception {
        var intake = app.getBean(ExerciseDbIntakeService.class);
        intake.stage(intakeBatch());
        exercise(1, "기존 운동", true);
        var saved = intake.saveDraft("Case123", 0, intakeDraft());
        assertThatThrownBy(() -> intake.createDraftAndLink("Case123", 1, id(1)))
                .isInstanceOfSatisfying(ExerciseDbIntakeService.Rejected.class,
                        failure -> assertThat(failure.reason()).isEqualTo(ExerciseDbIntakeService.Reason.EXERCISE_ID_CONFLICT));
        assertThat(intake.get("Case123")).isEqualTo(saved);
        assertThat(intake.createDraftAndLink("Case123", 1, id(2))).isEqualTo(id(2));
        assertThat(intake.createDraftAndLink("Case123", 1, id(2))).isEqualTo(id(2));
        var created = reviews().get(id(2));
        assertThat(created.content().name()).isEqualTo("한국어 운동 후보");
        assertThat(created.content().instructionsKo()).isEqualTo("운동 방법 후보");
        assertThat(created.content().primary()).containsExactly(muscle(3), muscle(1));
        assertThat(created.content().secondary()).containsExactly(muscle(23));
        assertThat(created.content().muscleTargetsReviewed()).isFalse();
        assertThat(created.content().splitsReviewed()).isFalse();
        assertThat(created.sources()).hasSize(1);
        assertThat(intake.get("case123").standardExerciseId()).isNull();
        error(browser.get(detailPath(id(2))), 404, "EXERCISE_NOT_FOUND");
    }

    @Test void selectedCatalogPlanIsRepeatableAndKeepsUserDataAndPublicationSeparate() throws Exception {
        UUID owner = jdbc.queryForObject("SELECT id FROM users LIMIT 1", UUID.class);
        UUID routine = UUID.randomUUID(), placement = UUID.randomUUID(), session = UUID.randomUUID(), item = UUID.randomUUID();
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'keep',now(),now())", routine, owner);
        jdbc.update("INSERT INTO exercise_gif_access(user_id) VALUES (?)", owner);
        manufacturer(1, "파나타", true);
        try {
            exercise(2, "기존 운동", true);
            jdbc.update("""
                    INSERT INTO routine_weight_placements(id,user_id,data_generation,routine_id,standard_exercise_id,
                        position,set_count,min_reps,max_reps,initial_weight,approved_weight,created_at,updated_at,manufacturer_id,load_type)
                    VALUES (?,?,0,?,?,0,3,6,10,60,60,now(),now(),?,'STACK')
                    """, placement, owner, routine, id(2), id(1));
            var initial = new InitialPlan(2, routine, "keep", "0",
                    List.of(new InitialPlan.Weight(item, placement, id(2), "기존 운동", id(1), "STACK", 3, 6, 10, "60", "60")),
                    List.of());
            var applied = new WorkoutSnapshotData.Plan(2,
                    List.of(new WorkoutSnapshotData.WeightPlan(item, 3, 6, 10, id(1), "STACK")), List.of());
            jdbc.update("""
                    INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,
                        started_time_zone,started_device_id,editor_device_id,initial_plan_snapshot,applied_plan_snapshot,
                        last_workout_synced_at,created_at,updated_at)
                    VALUES (?,?,0,?,now(),CURRENT_DATE,'Asia/Seoul',?,?,?::jsonb,?::jsonb,now(),now(),now())
                    """, session, owner, routine, id(10), id(10), JSON.writeValueAsString(initial), JSON.writeValueAsString(applied));
            jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,standard_exercise_id) VALUES (?,?,?,0,'WEIGHT',?)",
                    item, session, owner, id(2));
            jdbc.update("""
                    INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,
                        approval_state_at_recording,manufacturer_id,load_type)
                    VALUES (?,?,?,1,60,10,now(),'TODAY_ONLY','TODAY_ONLY',?,'STACK')
                    """, id(3), session, item, id(1));
            var before = protectedRows();
            assertThat(runSelectedCatalog("{}", false)).hasSize(31);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM standard_exercise", Integer.class)).isEqualTo(1);

            var first = runSelectedCatalog("{}", true);
            assertThat(first).hasSize(31).allMatch(row -> Boolean.TRUE.equals(row.get("inserted")));
            assertThat(first).filteredOn(row -> "COMPOUND".equals(row.get("joint_type"))).hasSize(15);
            assertThat(first).filteredOn(row -> "ISOLATION".equals(row.get("joint_type"))).hasSize(14);
            var byKey = first.stream().collect(java.util.stream.Collectors.toMap(row -> (String) row.get("key"), row -> row));
            assertThat(byKey.get("legs_machine_crunch"))
                    .containsEntry("name", "머신 업도미널 크런치").containsEntry("joint_type", "ISOLATION");
            for (String key : List.of("legs_machine_crunch", "legs_captains_chair_leg_raise")) {
                assertThat(byKey.get(key)).containsEntry("joint_type", "ISOLATION")
                        .containsEntry("ppl_split", "LEGS").containsEntry("front_back_split", "FRONT");
            }
            assertThat(reviews().get((UUID) byKey.get("legs_machine_crunch").get("standard_exercise_id")).content().aliases())
                    .contains("머신 크런치");
            for (String key : List.of("push_standing_machine_lateral_raise", "push_machine_dips_press", "push_dumbbell_lateral_raise")) {
                assertThat(byKey.get(key)).containsEntry("front_back_split", "FRONT");
            }
            assertThat(byKey.get("legs_rogers_hip_press")).containsEntry("joint_type", "COMPOUND")
                    .containsEntry("front_back_split", "BACK");
            assertThat(byKey.get("push_hammer_shoulder_press")).containsEntry("placement_load_type", "STACK");
            assertThat(byKey.get("push_arsenal_shoulder_press")).containsEntry("placement_load_type", "PLATE");
            assertThat(byKey.get("legs_pendulum_squat"))
                    .containsEntry("manufacturer", "Arsenal Strength")
                    .containsEntry("manufacturer_id", byKey.get("push_arsenal_shoulder_press").get("manufacturer_id"))
                    .containsEntry("placement_load_type", "PLATE");
            for (String key : List.of("cardio_treadmill_running", "cardio_outdoor_running")) {
                assertThat(byKey.get(key)).containsEntry("joint_type", null)
                        .containsEntry("ppl_split", null).containsEntry("front_back_split", null);
            }
            var second = runSelectedCatalog("{}", true);
            assertThat(second).hasSize(31).allMatch(row -> Boolean.FALSE.equals(row.get("inserted")));
            assertThat(second).extracting(row -> row.get("standard_exercise_id"))
                    .containsExactlyElementsOf(first.stream().map(row -> row.get("standard_exercise_id")).toList());
            assertThat(protectedRows()).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM manufacturer", Integer.class)).isEqualTo(7);
            assertThat(first.stream().filter(row -> row.get("manufacturer") != null && row.get("manufacturer").equals("Panatta")))
                    .allMatch(row -> row.get("manufacturer_id").equals(id(1)));
            var shoulderPresses = first.stream().filter(row -> row.get("name").equals("머신 숄더 프레스")).toList();
            assertThat(shoulderPresses).hasSize(2).extracting(row -> row.get("standard_exercise_id")).doesNotHaveDuplicates();
            assertThat(shoulderPresses).extracting(row -> row.get("manufacturer_id")).doesNotHaveDuplicates();
            assertThat(first.stream().filter(row -> row.get("cardio_activity_id") != null)
                    .map(row -> row.get("cardio_activity_id"))).containsExactlyInAnyOrder(CatalogFixtures.TREADMILL,
                    UUID.fromString("ca000000-0000-4000-8000-000000000002"));
            assertThat(jdbc.queryForList("SELECT source_exercise_id FROM exercise_source", String.class)).containsExactly("nZZZy9m");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_db_v1_candidate", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM standard_exercise
                    WHERE id <> ? AND (muscle_targets_reviewed OR splits_reviewed OR representative_gif_key IS NOT NULL)
                    """, Integer.class, id(2))).isZero();

            UUID highRowId = first.stream().filter(row -> "nZZZy9m".equals(row.get("source_id")))
                    .map(row -> (UUID) row.get("standard_exercise_id")).findFirst().orElseThrow();
            assertThat(reviews().get(highRowId).content().primary()).containsExactly(muscle(6), muscle(8));
            error(browser.get(detailPath(highRowId)), 404, "EXERCISE_NOT_FOUND");
            // Exercise the public detail contract using a disposable fixture state.
            jdbc.update("UPDATE standard_exercise SET muscle_targets_reviewed=true,splits_reviewed=true WHERE id=?", highRowId);
            var response = browser.get(detailPath(highRowId));
            var detail = success(response);
            var payload = JSON.readTree(Files.readString(SELECTED_CATALOG.resolve("catalog.json")));
            var highRow = payload.get("exercises").valueStream().filter(row -> row.get("key").asString().equals("pull_machine_high_row"))
                    .findFirst().orElseThrow();
            assertThat(detail.get("instructionSteps")).isEqualTo(highRow.get("steps"));
            assertThat(detail.get("jointType").asString()).isEqualTo("COMPOUND");
            assertThat(response.body()).doesNotContain("nZZZy9m", "static.exercisedb.dev");
        } finally {
            jdbc.update("DELETE FROM performed_sets WHERE session_id=?", session);
            jdbc.update("DELETE FROM session_exercises WHERE session_id=?", session);
            jdbc.update("DELETE FROM workout_sessions WHERE id=?", session);
            jdbc.update("DELETE FROM routine_weight_placements WHERE id=?", placement);
            jdbc.update("DELETE FROM exercise_gif_access WHERE user_id=?", owner);
            jdbc.update("DELETE FROM routines WHERE id=?", routine);
        }
    }

    @Test void selectedCatalogRequiresExplicitIdentityAndRejectsReassigningItsSingleSource() throws Exception {
        exercise(1, "하이로우", true);
        jdbc.update("UPDATE standard_exercise SET id=? WHERE id=?", UUID.fromString("d1700000-0000-4000-8000-000000000004"), id(1));
        UUID existing = UUID.fromString("d1700000-0000-4000-8000-000000000004");
        assertThatThrownBy(() -> runSelectedCatalog("{}", true))
                .isInstanceOf(SQLException.class).hasMessageContaining("Verify existing exercise identity");
        var old = reviews().get(existing);
        var reuse = "{\"pull_machine_high_row\":\"" + existing + "\"}";
        var resolved = runSelectedCatalog(reuse, true);
        assertThat(resolved.stream().filter(row -> "nZZZy9m".equals(row.get("source_id"))))
                .singleElement().satisfies(row -> assertThat(row.get("standard_exercise_id")).isEqualTo(existing));
        assertThat(reviews().get(existing).content()).isEqualTo(old.content());
        assertThatThrownBy(() -> runSelectedCatalog(reuse, true, true))
                .isInstanceOf(SQLException.class).hasMessageContaining("Review approval requires matching catalog content");
        assertThatThrownBy(() -> runSelectedCatalog("{\"pull_machine_high_row\":null}", true))
                .isInstanceOf(SQLException.class).hasMessageContaining("already has a different link");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standard_exercise", Integer.class)).isEqualTo(31);
    }

    @Test void selectedCatalogApprovalEnablesRoutineCreationWithoutSelectingRepresentativeGif() throws Exception {
        var first = runSelectedCatalog("{}", true, true);
        assertThat(first).hasSize(31).allMatch(row ->
                Boolean.TRUE.equals(row.get("muscle_targets_reviewed")) && Boolean.TRUE.equals(row.get("splits_reviewed")));
        assertThat(success(browser.get(SEARCH)).get("exercises")).hasSize(29);
        assertThat(success(browser.get("/api/v1/standard-exercises/cardio-choices")).get("activities")).hasSize(2);
        var repeated = runSelectedCatalog("{}", true, true);
        assertThat(repeated).hasSize(31).allMatch(row -> Boolean.FALSE.equals(row.get("inserted")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standard_exercise WHERE representative_gif_key IS NOT NULL",
                Integer.class)).isZero();

        var treadmill = first.stream().filter(row -> "cardio_treadmill_running".equals(row.get("key")))
                .findFirst().orElseThrow();
        var outdoor = first.stream().filter(row -> "cardio_outdoor_running".equals(row.get("key")))
                .findFirst().orElseThrow();
        UUID routine = UUID.randomUUID();
        var payload = """
                {"id":"%s","dataGeneration":"0","name":"등록 검증","weightPlacements":[],"cardioPlacements":[
                  {"id":"%s","plan":{"kind":"TREADMILL","name":"트레드밀 러닝","targetSeconds":2100,
                    "targetDistanceKm":"5","intensity":{"mode":"SPEED","value":"8.5"},"inclinePercent":null,
                    "resistance":null,"activityId":"%s","standardExerciseId":"%s"}},
                  {"id":"%s","plan":{"kind":"TREADMILL","name":"트레드밀 러닝","targetSeconds":1200,
                    "targetDistanceKm":"3","intensity":null,"inclinePercent":null,
                    "resistance":null,"activityId":"%s","standardExerciseId":"%s"}},
                  {"id":"%s","plan":{"kind":"OUTDOOR","name":"야외 러닝","targetSeconds":2100,
                    "targetDistanceKm":null,"intensity":{"mode":"PACE","value":"7.5"},"inclinePercent":null,
                    "resistance":null,"activityId":"%s","standardExerciseId":"%s"}},
                  {"id":"%s","plan":{"kind":"TREADMILL","name":"트레드밀 러닝","targetSeconds":1200,
                    "targetDistanceKm":"3","intensity":null,"inclinePercent":null,
                    "resistance":null,"activityId":"%s","standardExerciseId":"%s"}}
                ]}
                """.formatted(routine,
                UUID.randomUUID(), treadmill.get("cardio_activity_id"), treadmill.get("standard_exercise_id"),
                UUID.randomUUID(), treadmill.get("cardio_activity_id"), treadmill.get("standard_exercise_id"),
                UUID.randomUUID(), outdoor.get("cardio_activity_id"), outdoor.get("standard_exercise_id"),
                UUID.randomUUID(), treadmill.get("cardio_activity_id"), treadmill.get("standard_exercise_id"));
        try {
            var response = browser.send("POST", "/api/v1/routines", payload, browser.csrf());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(jdbc.queryForList("SELECT target_seconds FROM routine_cardio_placements WHERE routine_id=? ORDER BY position",
                    Integer.class, routine)).containsExactly(2100, 1200, 2100, 1200);
        } finally {
            jdbc.update("DELETE FROM routine_cardio_placements WHERE routine_id=?", routine);
            jdbc.update("DELETE FROM routine_plan_history WHERE routine_id=?", routine);
            jdbc.update("DELETE FROM routines WHERE id=?", routine);
        }
    }

    private static final Path SELECTED_CATALOG = Path.of("docs/dev-data/overload-31-catalog");

    private static List<Map<String, Object>> runSelectedCatalog(String reuse, boolean commit) throws Exception {
        return runSelectedCatalog(reuse, commit, false);
    }

    private static List<Map<String, Object>> runSelectedCatalog(String reuse, boolean commit, boolean approve) throws Exception {
        var payload = Files.readString(SELECTED_CATALOG.resolve("catalog.json"));
        var script = Files.readString(SELECTED_CATALOG.resolve("catalog-plan.sql"))
                .replace("/* CATALOG_JSON */", "'" + payload.replace("'", "''") + "'::jsonb")
                .replace("/* REUSE_JSON */", "'" + reuse.replace("'", "''") + "'::jsonb")
                .replace("/* APPROVE */", Boolean.toString(approve));
        var result = new ArrayList<Map<String, Object>>();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            boolean hasResult = statement.execute(script);
            while (!hasResult && statement.getUpdateCount() != -1) hasResult = statement.getMoreResults();
            assertThat(hasResult).isTrue();
            try (var rows = statement.getResultSet()) {
                var fields = rows.getMetaData();
                while (rows.next()) {
                    var row = new HashMap<String, Object>();
                    for (int i = 1; i <= fields.getColumnCount(); i++) row.put(fields.getColumnLabel(i), rows.getObject(i));
                    result.add(row);
                }
            }
            if (commit) connection.commit();
            else connection.rollback();
        }
        return result;
    }

    private static List<String> protectedRows() {
        return List.of("routines", "routine_weight_placements", "routine_cardio_placements",
                "workout_sessions", "session_exercises", "performed_sets", "exercise_gif_access")
                .stream().map(table -> jdbc.queryForObject(
                        "SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t))::text, '[]') FROM " + table + " t", String.class))
                .toList();
    }

    private static ExerciseDbV1.Batch intakeBatch() {
        var steps = List.of("Step:1 Press.", "Step:2 Return to the start.");
        return ExerciseDbV1Tests.parse(List.of(
                ExerciseDbV1Tests.raw("Case123", "barbell press", "pectorals", List.of("triceps"), steps),
                ExerciseDbV1Tests.raw("case123", "barbell press", "pectorals", List.of("triceps"), steps)));
    }

    private static ExerciseDbIntakeService.Draft intakeDraft() {
        return new ExerciseDbIntakeService.Draft("한국어 운동 후보", List.of("bench press", "벤치"), "운동 방법 후보", "바벨",
                StandardExercise.ExerciseType.WEIGHT, StandardExercise.RecordMethod.SETS_REPS,
                StandardExercise.JointType.COMPOUND, List.of(muscle(3), muscle(1)), List.of(muscle(23)));
    }
    private static String detailPath(UUID exerciseId) { return "/api/v1/standard-exercises/" + exerciseId; }
    private static UUID muscle(int position) { return UUID.fromString("d3200000-0000-4000-8000-%012d".formatted(position)); }

    private static CatalogReviewService.Content weight(String name, List<String> aliases, List<UUID> primary, List<UUID> secondary,
                                                        boolean targetsReviewed, boolean splitsReviewed) {
        return new CatalogReviewService.Content(name, aliases, StandardExercise.ExerciseType.WEIGHT, StandardExercise.RecordMethod.SETS_REPS,
                "바벨", StandardExercise.JointType.COMPOUND, "바를 잡는다.\r\n\r\n 바를 민다.", primary, secondary,
                StandardExercise.PplSplit.PUSH, null, targetsReviewed, splitsReviewed);
    }

    private static CatalogReviewService.Change change(CatalogReviewService.Review before, CatalogReviewService.Content content,
                                                       CatalogReviewService.SourceId source) {
        return new CatalogReviewService.Change(before.updatedAt(), content, source);
    }

    private static CatalogReviewService.SourceId source(String id) {
        return new CatalogReviewService.SourceId(ExerciseSource.System.EXERCISEDB_V1, id);
    }

    private static void addSources() {
        exercise(2, "다른 운동", false);
        var repository = app.getBean(ExerciseSourceRepository.class);
        var system = ExerciseSource.System.EXERCISEDB_V1;
        repository.saveAllAndFlush(List.of(ExerciseSource.create(system, "bench", id(1), "Bench Press", "https://private.invalid/bench"),
                ExerciseSource.create(system, "bench-alternate", id(1), "Bench Press Alternate", "private-bench-asset"),
                ExerciseSource.create(system, "missing-gif", id(1), "No GIF", null),
                ExerciseSource.create(system, "other-exercise", id(2), "Other exercise", "https://private.invalid/other")));
    }

    private static void reject(CatalogReviewService.Reason reason, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CatalogReviewService.Rejected.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    @Test void searchUsesTwoReadOnlyJpaQueriesAndOneConsistentSnapshot() throws Exception {
        exercise(1, "벤치", true); alias(1, "bench");
        var independent = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var selects = new CopyOnWriteArrayList<String>(); var changed = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> {
            if (!catalogSql(event.sql())) return;
            if (event.before()) {
                selects.add(event.sql());
                assertThat(TransactionSynchronizationManager.hasResource(app.getBean(EntityManagerFactory.class))).isTrue();
                try {
                    assertThat(event.connection().isReadOnly()).isTrue();
                    assertThat(event.connection().getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                } catch (SQLException failure) { throw new AssertionError(failure); }
            } else if (!event.sql().contains("exercise_alias") && changed.compareAndSet(false, true)) {
                independent.update("UPDATE standard_exercise SET is_active=false WHERE id=?", id(1));
                independent.update("DELETE FROM exercise_alias WHERE standard_exercise_id=?", id(1));
            }
        };
        var data = success(browser.get(SEARCH));
        WorkoutReadFixtures.observer = null;
        assertThat(changed).isTrue(); assertThat(selects).hasSize(2);
        assertThat(data.get("exercises").size()).isEqualTo(1);
        assertThat(data.get("exercises").get(0).get("aliases")).isEqualTo(JSON.readTree("[\"bench\"]"));
        assertThat(success(browser.get(SEARCH)).get("exercises").isEmpty()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {SEARCH, MANUFACTURERS})
    void anonymousAndExpiredSessionsAreRejected(String path) throws Exception {
        try (var anonymous = new Browser()) { error(anonymous.get(path), 401, "AUTHENTICATION_REQUIRED"); }
        EmailOtpFixtures.CLOCK.advance(Duration.ofDays(91));
        error(browser.get(path), 401, "AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest @ValueSource(strings = {SEARCH, MANUFACTURERS})
    void queryAndGetBodiesAreRejectedBeforeCatalogSqlAndNoManagementApiExists(String path) throws Exception {
        var queries = new CopyOnWriteArrayList<String>();
        WorkoutReadFixtures.observer = event -> { if (event.before() && catalogSql(event.sql())) queries.add(event.sql()); };
        error(browser.get(path + "?q=bench"), 400, "INVALID_REQUEST");
        error(browser.send("GET", path, "{}", null), 400, "INVALID_REQUEST");
        assertThat(queries).isEmpty(); WorkoutReadFixtures.observer = null;
        assertThat(browser.send("POST", path, "{}", browser.csrf()).statusCode()).isEqualTo(405);
    }

    @ParameterizedTest @ValueSource(strings = {SEARCH, MANUFACTURERS})
    void databaseAndSecurityFailuresKeepExistingReadErrorContract(String path) throws Exception {
        WorkoutReadFixtures.observer = event -> { if (event.before() && catalogSql(event.sql())) throw new DataAccessResourceFailureException("private connection detail"); };
        error(browser.get(path), 503, "WORKOUT_DATA_UNAVAILABLE");
        WorkoutReadFixtures.observer = event -> { if (event.before() && catalogSql(event.sql())) throw new DataIntegrityViolationException("private SQL detail"); };
        error(browser.get(path), 500, "WORKOUT_DATA_READ_FAILED");
        WorkoutReadFixtures.observer = event -> { if (event.before() && catalogSql(event.sql())) throw new AccessDeniedException("private security detail"); };
        error(browser.get(path), 403, "ACCESS_DENIED");
    }

    private static boolean catalogSql(String sql) {
        String value = sql.toLowerCase(Locale.ROOT);
        return value.startsWith("select ") && (value.contains("from standard_exercise") || value.contains("from exercise_alias") || value.contains("from manufacturer"));
    }
    private static void exercise(int id, String name, boolean active) {
        var exercise = StandardExercise.create(id(id), name, active, NOW);
        exercise.reviewMuscleTargets(true, NOW);
        exercise.reviewSplits(true, NOW);
        app.getBean(StandardExerciseRepository.class).saveAndFlush(exercise);
    }
    private static void alias(int exercise, String name) {
        app.getBean(ExerciseAliasRepository.class).saveAndFlush(ExerciseAlias.create(id(exercise), name));
    }
    private static void manufacturer(int id, String name, boolean active) {
        app.getBean(ManufacturerRepository.class).saveAndFlush(Manufacturer.create(id(id), name, active, NOW));
    }
    private static UUID id(int value) { return new UUID(0, value); }
    private static void dbError(String state, Runnable action) {
        var failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(((SQLException) ((DataIntegrityViolationException) failure).getMostSpecificCause()).getSQLState()).isEqualTo(state);
    }
    private static JsonNode success(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200); headers(response); return JSON.readTree(response.body());
    }
    private static void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status); headers(response);
        assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree(JSON.writeValueAsString(Map.of("code", code))));
    }
    private static void headers(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow()).version()).isEqualTo(4);
    }

    static final class Browser implements AutoCloseable {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        void login() throws Exception {
            String email = UUID.randomUUID() + "@example.test", token = csrf();
            var issued = send("POST", "/api/v1/auth/email-otp/request", JSON.writeValueAsString(Map.of("email", email)), token);
            assertThat(issued.statusCode()).isEqualTo(202);
            var message = app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString()));
            assertThat(send("POST", "/api/v1/auth/email-otp/verify", JSON.writeValueAsString(Map.of("email", email, "challengeId", message.issuanceId(), "code", message.code())), token).statusCode()).isEqualTo(204);
        }
        String csrf() throws Exception { return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString(); }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        HttpResponse<String> send(String method, String path, String body, String csrf) throws Exception {
            String origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
            var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(15));
            if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { client.close(); }
    }
}
