package com.overload.overload_server.workoutsession;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.*;
import com.overload.overload_server.routine.*;
import com.overload.testfixture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import static org.assertj.core.api.Assertions.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;

@Tag("postgres") @Testcontainers
class WorkoutAssessmentHistoryIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_assessment_history_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext app;
    final Instant at=Instant.parse("2026-10-31T16:00:00Z");
    final UUID device=UUID.randomUUID();
    UUID owner,routine;
    JdbcTemplate jdbc;
    WorkoutSessionService sessions;
    WorkoutSnapshotService snapshots;
    WorkoutRecordService records;

    @BeforeAll static void startApp() {
        assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432,5433);
        var key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        app=new SpringApplicationBuilder(OverloadServerApplication.class,EmailOtpFixtures.class,WorkoutReadFixtures.class)
                .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
                        "--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+POSTGRES.getUsername(),
                        "--spring.datasource.password="+POSTGRES.getPassword(),"--server.address=127.0.0.1","--server.port=0",
                        "--OTP_HMAC_KEY="+Base64.getEncoder().encodeToString(key),"--OTP_EMAIL_ENABLED=false",
                        "--GOOGLE_OIDC_ENABLED=false","--GOOGLE_REAUTH_ENABLED=false","--spring.session.jdbc.cleanup-cron=-",
                        "--logging.level.root=WARN","--spring.main.banner-mode=off");
    }
    @BeforeEach void prepare() {
        WorkoutReadFixtures.observer=null;EmailOtpFixtures.CLOCK.reset();
        jdbc=app.getBean(JdbcTemplate.class);sessions=app.getBean(WorkoutSessionService.class);
        snapshots=app.getBean(WorkoutSnapshotService.class);records=app.getBean(WorkoutRecordService.class);
        owner=app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email(UUID.randomUUID()+"@example.test"),null).id();
        routine=UUID.randomUUID();
        app.getBean(RoutineService.class).create(owner,new RoutineRequests.Create(routine,0,"history test",List.of(
                new RoutineRequests.Weight(RoutineRequests.Mode.NEW,UUID.randomUUID(),CatalogFixtures.exercise(jdbc),null,null,3,6,10,new BigDecimal("60"))),List.of()));
    }
    @AfterEach void clearObserver() { WorkoutReadFixtures.observer=null; }
    @AfterAll static void stopApp() { if(app!=null)app.close(); }

    @ParameterizedTest @CsvSource({"0,false","2,false","0,true","2,true"})
    void assessmentSelectsDependOnCurrentEligibility(int historyCount,boolean eligible) {
        for(int n=0;n<historyCount;n++) {var past=start(at.plusSeconds(n*3600));finish(past,state(past,true));}
        var current=start(at.plusSeconds((historyCount+1)*3600));finish(current,state(current,eligible));
        var read=new TransactionTemplate(app.getBean("workoutTransactionManager",PlatformTransactionManager.class));
        read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var selects=new ArrayList<String>();var writes=new ArrayList<String>();
        var result=read.execute(tx->{
            var entity=app.getBean(WorkoutSessionRepository.class).find(owner,0,current.id(),false).orElseThrow();
            WorkoutReadFixtures.observer=e->{if(e.before()) {
                if(e.sql().startsWith("select "))selects.add(e.sql());
                else if(e.sql().startsWith("update ")||e.sql().startsWith("insert ")||e.sql().startsWith("delete "))writes.add(e.sql());
            }};
            try{return app.getBean(WorkoutAssessment.class).assess(entity,at);}finally{WorkoutReadFixtures.observer=null;}
        });
        assertThat(result.exercises()).allMatch(e->e.eligible()==eligible&&(e.benchmarkEvidence()!=null)==(eligible&&historyCount>0));
        assertThat(selects).hasSize(eligible?2+(historyCount+WorkoutAssessment.HISTORY_BATCH_SIZE-1)/WorkoutAssessment.HISTORY_BATCH_SIZE:1);
        assertThat(selects.stream().filter(sql->sql.contains(" from performed_sets "))).hasSize(eligible&&historyCount>0?2:1);
        assertThat(writes).isEmpty();
    }

    @Test void duplicateSnapshotFinishKeepsIntermediateFlushAndFirstAssessment() {
        var current=start(at);var state=state(current,false);var save=new Save(0,device,0,1,state);
        snapshots.save(owner,current.id(),save);
        var updates=new ArrayList<String>();var versions=new ArrayList<Long>();
        WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().startsWith("update workout_sessions ")) {
            updates.add(e.sql());
            try(var statement=e.connection().prepareStatement("select version from workout_sessions where id=?")) {
                statement.setObject(1,current.id());try(var rows=statement.executeQuery()){rows.next();versions.add(rows.getLong(1));}
            }catch(java.sql.SQLException failure){throw new IllegalStateException(failure);}
        }};
        WorkoutSnapshotData.FinishAck ack;
        try{ack=snapshots.finish(owner,current.id(),new Finish(save,at.plusSeconds(600)));}finally{WorkoutReadFixtures.observer=null;}
        assertThat(updates).hasSize(2);assertThat(versions).containsExactly(1L,2L);
        assertThat(ack.completionAssessment().sessionVersion()).isEqualTo("2");
        var assessment=records.assessment(owner,current.id());
        assertThat(JSON.treeToValue(assessment.completionAssessment(),WorkoutAssessment.Result.class)).isEqualTo(ack.completionAssessment());
        assertThat(assessment.currentAssessment().evaluation().exercises()).isEqualTo(ack.completionAssessment().exercises());
    }

    @Test void skippedHistoryStillRollsBackAFailingFinishFlush() {
        var current=start(at);var save=new Save(0,device,0,1,state(current,false));snapshots.save(owner,current.id(),save);
        var before=jdbc.queryForMap("select * from workout_sessions where id=?",current.id());
        var failed=new java.util.concurrent.atomic.AtomicBoolean();
        WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().startsWith("update workout_sessions ")) {
            failed.set(true);throw new org.springframework.dao.DataAccessResourceFailureException("isolated FINISH flush failure");
        }};
        try{assertThatThrownBy(()->snapshots.finish(owner,current.id(),new Finish(save,at.plusSeconds(600))))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);}finally{WorkoutReadFixtures.observer=null;}
        assertThat(failed).isTrue();assertThat(jdbc.queryForMap("select * from workout_sessions where id=?",current.id())).isEqualTo(before);
    }

    @Test void batchesPreserveRepositoryOrderEmptyInputsAndManagedTransactionChanges() {
        var past=start(at);finish(past,state(past,true));var current=start(at.plusSeconds(3600));finish(current,state(current,true));
        var transaction=new TransactionTemplate(app.getBean("workoutTransactionManager",PlatformTransactionManager.class));
        var before=jdbc.queryForList("select id,reps from performed_sets where session_id=? order by id",past.id());
        transaction.executeWithoutResult(tx->{
            var repository=app.getBean(WorkoutSnapshotRepository.class);var entity=app.getBean(WorkoutSessionRepository.class).find(owner,0,current.id(),false).orElseThrow();
            var queries=new ArrayList<String>();WorkoutReadFixtures.observer=e->{if(e.before())queries.add(e.sql());};
            try{assertThat(repository.setsForSessions(List.of())).isEmpty();assertThat(queries).isEmpty();}finally{WorkoutReadFixtures.observer=null;}
            var old=repository.sets(past.id());var currentSets=repository.sets(current.id());
            var batch=repository.setsForSessions(List.of(current.id(),past.id()));
            assertThat(batch).containsExactlyElementsOf(java.util.stream.Stream.concat(old.stream(),currentSets.stream())
                    .sorted(Comparator.comparing((PerformedSet s)->s.sessionId.toString()).thenComparing(s->s.id.toString())).toList());
            assertThat(repository.setsForSessions(List.of(past.id()))).containsExactlyElementsOf(old);
            old.getFirst().reps=12;
            var assessment=app.getBean(WorkoutAssessment.class).assess(entity,at);
            assertThat(assessment.exercises().getFirst().benchmarkEvidence().repsSum()).isEqualTo("28");
            var em=EntityManagerFactoryUtils.getTransactionalEntityManager(app.getBean(EntityManagerFactory.class));
            assertThat(em.contains(old.getFirst())).isTrue();old.getFirst().reps=13;repository.flush();
            assertThat(jdbc.queryForObject("select reps from performed_sets where id=?",Integer.class,old.getFirst().id)).isEqualTo(13);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForList("select id,reps from performed_sets where session_id=? order by id",past.id())).isEqualTo(before);
    }

    @Test void batchFailureRollsBackPendingHistoryChanges() {
        var past=start(at);finish(past,state(past,true));var current=start(at.plusSeconds(3600));finish(current,state(current,true));
        var other=start(at.plusSeconds(1800));finish(other,state(other,true));
        var before=jdbc.queryForList("select id,reps from performed_sets where session_id=? order by id",past.id());
        var transaction=new TransactionTemplate(app.getBean("workoutTransactionManager",PlatformTransactionManager.class));
        assertThatThrownBy(()->transaction.executeWithoutResult(tx->{
            var repository=app.getBean(WorkoutSnapshotRepository.class);repository.sets(past.id()).getFirst().reps=20;
            var entity=app.getBean(WorkoutSessionRepository.class).find(owner,0,current.id(),false).orElseThrow();
            WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().contains(" from performed_sets ")&&e.sql().contains(" in ("))throw new org.springframework.dao.DataAccessResourceFailureException("isolated batch failure");};
            try{app.getBean(WorkoutAssessment.class).assess(entity,at);}finally{WorkoutReadFixtures.observer=null;}
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForList("select id,reps from performed_sets where session_id=? order by id",past.id())).isEqualTo(before);
    }

    @Test void fullPastPlansEmptySessionsAndScopeStillControlComparison() {
        var otherRoutine=start(at.minusSeconds(3600));finish(otherRoutine,state(otherRoutine,true));
        routine=UUID.randomUUID();app.getBean(RoutineService.class).create(owner,new RoutineRequests.Create(routine,0,"two exercises",List.of(
                new RoutineRequests.Weight(RoutineRequests.Mode.NEW,UUID.randomUUID(),CatalogFixtures.exercise(jdbc),null,null,3,6,10,new BigDecimal("60")),
                new RoutineRequests.Weight(RoutineRequests.Mode.NEW,UUID.randomUUID(),CatalogFixtures.exercise(jdbc),null,null,3,6,10,new BigDecimal("60"))),List.of()));
        var empty=start(at);var base=empty.snapshot().state();finish(empty,new State(base.sessionExercises(),base.appliedPlan(),List.of(),List.of(),List.of(),List.of(),List.of()));
        var incomplete=start(at.plusSeconds(3600));var full=allExercises(incomplete,false);
        var high=full.performedSets().subList(0,3).stream().map(s->new SetValue(s.id(),s.sessionExerciseId(),s.ordinal(),s.weightKg(),20,s.completedAt(),s.weightContext(),s.manufacturerId(),s.loadType())).toList();
        finish(incomplete,new State(full.sessionExercises(),full.appliedPlan(),high,List.of(),List.of(),List.of(),List.of()));
        var complete=start(at.plusSeconds(7200));finish(complete,allExercises(complete,false));
        var current=start(at.plusSeconds(10800));finish(current,allExercises(current,true));
        var read=new TransactionTemplate(app.getBean("workoutTransactionManager",PlatformTransactionManager.class));read.setReadOnly(true);
        read.executeWithoutResult(tx->{
            var sessionRepository=app.getBean(WorkoutSessionRepository.class);var repository=app.getBean(WorkoutSnapshotRepository.class);
            var entity=sessionRepository.find(owner,0,current.id(),false).orElseThrow();
            assertThat(sessionRepository.finishedBefore(entity).stream().map(s->s.id)).containsExactlyInAnyOrder(otherRoutine.id(),empty.id(),incomplete.id(),complete.id());
            var previous=previousAssessment(entity,at,repository,sessionRepository);var actual=app.getBean(WorkoutAssessment.class).assess(entity,at);
            assertThat(actual).isEqualTo(previous);assertThat(actual.exercises().getFirst().advice()).isEqualTo("DECREASE");
            assertThat(actual.exercises().getFirst().benchmarkEvidence().sessionId()).isEqualTo(complete.id());assertThat(actual.exercises().get(1).eligible()).isFalse();
            var scope=new WorkoutSession();scope.id=current.id();scope.userId=owner;scope.dataGeneration=1;scope.startedAt=entity.startedAt;
            assertThat(sessionRepository.finishedBefore(scope)).isEmpty();scope.dataGeneration=0;scope.userId=UUID.randomUUID();assertThat(sessionRepository.finishedBefore(scope)).isEmpty();
        });
    }
    State allExercises(WorkoutSessionResponses.Detail session,boolean partial) {
        var base=session.snapshot().state();var sets=new ArrayList<SetValue>();
        for(int i=0;i<base.appliedPlan().weightExercises().size();i++)for(int n=1;n<=3;n++)sets.add(new SetValue(UUID.randomUUID(),base.appliedPlan().weightExercises().get(i).sessionExerciseId(),n,"60",partial&&i==0?5:8,
                session.startedAt().plusSeconds(n*60),new Context(partial&&i==1?"TODAY_ONLY":"INITIAL_APPROVED",null),null,null));
        return new State(base.sessionExercises(),base.appliedPlan(),sets,List.of(),List.of(),List.of(),List.of());
    }

    @Test void unreadableEmptyHistoricalPlanStillFailsFinishAtomically() {
        var template=start(at);finish(template,state(template,true));
        cloneRow("workout_sessions",template.id(),Map.of("id",UUID.randomUUID(),"initial_plan_snapshot",Map.of("schemaVersion",2,"weightExercises","broken")));
        var current=start(at.plusSeconds(3600));var save=new Save(0,device,0,1,state(current,true));snapshots.save(owner,current.id(),save);
        var before=jdbc.queryForMap("select * from workout_sessions where id=?",current.id());
        assertThatThrownBy(()->snapshots.finish(owner,current.id(),new Finish(save,current.startedAt().plusSeconds(600)))).isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThat(jdbc.queryForMap("select * from workout_sessions where id=?",current.id())).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(ints={0,1,10,100,101,1000})
    void batchQueriesMatchPreviousResultsAndMeasureHistoryCosts(int historyCount) throws Exception {
        if(historyCount>0) {
            var template=start(at);var facts=state(template,true);finish(template,facts);
            var row=jdbc.queryForMap("select * from workout_sessions where id=?",template.id());
            var transaction=new TransactionTemplate(app.getBean("workoutTransactionManager",PlatformTransactionManager.class));
            transaction.executeWithoutResult(tx->{for(int n=1;n<historyCount;n++) {
                UUID id=UUID.randomUUID(),exercise=UUID.randomUUID();var replacements=new LinkedHashMap<String,Object>();
                replacements.put("id",id);
                String oldExercise=template.snapshot().state().sessionExercises().getFirst().id().toString();
                for(String column:List.of("initial_plan_snapshot","applied_plan_snapshot","completion_assessment_snapshot")) {
                    String text=row.get(column).toString().replace(oldExercise,exercise.toString());
                    replacements.put(column,JSON.readTree(text));
                }
                var ids=new LinkedHashMap<UUID,UUID>();for(var set:facts.performedSets())ids.put(set.id(),UUID.randomUUID());
                String assessment=json(replacements.get("completion_assessment_snapshot"));
                for(var pair:ids.entrySet())assessment=assessment.replace(pair.getKey().toString(),pair.getValue().toString());
                replacements.put("completion_assessment_snapshot",JSON.readTree(assessment));
                cloneRow("workout_sessions",template.id(),replacements);
                cloneRow("session_exercises",UUID.fromString(oldExercise),Map.of("id",exercise,"session_id",id));
                for(var pair:ids.entrySet())cloneRow("performed_sets",pair.getKey(),Map.of("id",pair.getValue(),"session_id",id,"session_exercise_id",exercise));
            }});
        }
        var current=start(at.plusSeconds(3600));finish(current,state(current,true));
        // Warm both paths, then alternate order. Each measurement uses a fresh persistence context.
        measure(current.id(),true);measure(current.id(),false);
        var previous=new ArrayList<Measurement>();var batched=new ArrayList<Measurement>();
        for(int n=0;n<3;n++) {
            if(n%2==0){previous.add(measure(current.id(),true));batched.add(measure(current.id(),false));}
            else{batched.add(measure(current.id(),false));previous.add(measure(current.id(),true));}
        }
        for(var sample:previous)assertThat(sample.selects()).isEqualTo(historyCount+2);
        for(var sample:batched) {
            assertThat(sample.result()).isEqualTo(previous.getFirst().result());
            assertThat(sample.selects()).isEqualTo(2+(historyCount+WorkoutAssessment.HISTORY_BATCH_SIZE-1)/WorkoutAssessment.HISTORY_BATCH_SIZE);
            assertThat(sample.rows()).isEqualTo(4L*historyCount+3);
            assertThat(sample.managedEntities()).isEqualTo(previous.getFirst().managedEntities());
            assertThat(sample.batchParameters()).isLessThanOrEqualTo(WorkoutAssessment.HISTORY_BATCH_SIZE);
        }
        for(var sample:previous)assertThat(sample.rows()).isEqualTo(4L*historyCount+3);
        var report=Map.of("history",historyCount,"setsPerSession",3,"batchSize",WorkoutAssessment.HISTORY_BATCH_SIZE,
                "previous",summary(previous),"batched",summary(batched));
        var output=Path.of("build/reports/task4-assessment-H"+historyCount+".json");Files.createDirectories(output.getParent());Files.writeString(output,json(report));
        if(historyCount>=100) {
            assertThat(median(batched.stream().mapToLong(Measurement::nanos).toArray())).isLessThan(median(previous.stream().mapToLong(Measurement::nanos).toArray()));
            assertThat(median(batched.stream().mapToLong(Measurement::allocatedBytes).toArray())).isLessThanOrEqualTo(median(previous.stream().mapToLong(Measurement::allocatedBytes).toArray()));
        }
    }

    void cloneRow(String table,UUID source,Map<String,?> replacements) {
        // Only this class's disposable, fully constrained fixture rows are cloned; triggers stay enabled.
        jdbc.update("insert into "+table+" select (jsonb_populate_record(null::"+table+",to_jsonb(s)||cast(? as jsonb))).* from "+table+" s where id=?",json(replacements),source);
    }
    record Measurement(WorkoutAssessment.Result result,long nanos,long allocatedBytes,long sampledHeapGrowth,long retainedHeapGrowth,
                       int selects,long rows,int managedEntities,int batchParameters) { }
    Measurement measure(UUID id,boolean previous) {
        var read=new TransactionTemplate(app.getBean("workoutTransactionManager",PlatformTransactionManager.class));
        read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return read.execute(tx->{
            var repository=app.getBean(WorkoutSnapshotRepository.class);var sessions=app.getBean(WorkoutSessionRepository.class);
            var current=sessions.find(owner,0,id,false).orElseThrow();
            var factory=app.getBean(EntityManagerFactory.class);var statistics=factory.unwrap(SessionFactory.class).getStatistics();
            statistics.setStatisticsEnabled(true);statistics.clear();
            var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
            assertThat(allocation.isThreadAllocatedMemorySupported()).isTrue();allocation.setThreadAllocatedMemoryEnabled(true);
            System.gc();long initialHeap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            var peak=new long[]{initialHeap};var selects=new int[1];var parameters=new int[1];
            WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().startsWith("select ")) {
                selects[0]++;peak[0]=Math.max(peak[0],ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                if(e.sql().contains(" from performed_sets ")&&e.sql().contains(" in ("))parameters[0]=Math.max(parameters[0],(int)e.sql().chars().filter(c->c=='?').count());
            }};
            long before=allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()),started=System.nanoTime();
            WorkoutAssessment.Result result;
            try{result=previous?previousAssessment(current,at,repository,sessions):app.getBean(WorkoutAssessment.class).assess(current,at);}
            finally{WorkoutReadFixtures.observer=null;}
            long nanos=System.nanoTime()-started,allocated=allocation.getThreadAllocatedBytes(Thread.currentThread().threadId())-before;
            peak[0]=Math.max(peak[0],ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            long rows=Arrays.stream(statistics.getQueries()).mapToLong(q->statistics.getQueryStatistics(q).getExecutionRowCount()).sum();
            int managed=EntityManagerFactoryUtils.getTransactionalEntityManager(factory).unwrap(org.hibernate.Session.class).getStatistics().getEntityCount();
            System.gc();long retained=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()-initialHeap;
            return new Measurement(result,nanos,allocated,peak[0]-initialHeap,retained,selects[0],rows,managed,parameters[0]);
        });
    }
    Map<String,Object> summary(List<Measurement> samples) {
        var first=samples.getFirst();return Map.of("selects",first.selects(),"rows",first.rows(),"managedEntities",first.managedEntities(),"maxBatchParameters",first.batchParameters(),
                "medianMs",median(samples.stream().mapToLong(Measurement::nanos).toArray())/1_000_000.0,
                "medianAllocatedBytes",median(samples.stream().mapToLong(Measurement::allocatedBytes).toArray()),
                "medianSampledHeapGrowth",median(samples.stream().mapToLong(Measurement::sampledHeapGrowth).toArray()),
                "medianRetainedHeapGrowth",median(samples.stream().mapToLong(Measurement::retainedHeapGrowth).toArray()));
    }
    long median(long[] values){Arrays.sort(values);return values[values.length/2];}

    // Work 3 implementation, retained here only as an identical-input/JPA-query measurement reference.
    WorkoutAssessment.Result previousAssessment(WorkoutSession session,Instant assessedAt,WorkoutSnapshotRepository records,WorkoutSessionRepository sessions) {
        var current=WorkoutAssessment.evaluate(InitialPlan.read(session.initialPlan),JSON.readValue(session.appliedPlan,Plan.class),records.sets(session.id));
        var past=new ArrayList<Map.Entry<WorkoutSession,WorkoutAssessment.Evaluation>>();
        if(current.exercises().stream().anyMatch(WorkoutAssessment.ExerciseResult::eligible)) {
            for(var candidate:sessions.finishedBefore(session))past.add(Map.entry(candidate,WorkoutAssessment.evaluate(InitialPlan.read(candidate.initialPlan),JSON.readValue(candidate.appliedPlan,Plan.class),records.sets(candidate.id))));
        }else sessions.flush();
        var exercises=new ArrayList<WorkoutAssessment.ExerciseResult>();
        for(var item:current.exercises()) {
            WorkoutAssessment.Benchmark benchmark=null;
            if(item.eligible())for(var candidate:past)for(var prior:candidate.getValue().exercises()) {
                if(!prior.eligible()||!prior.comparisonContext().equals(item.comparisonContext()))continue;
                var s=candidate.getKey();var evidence=new WorkoutAssessment.Benchmark(s.id,Long.toString(s.version),s.endedAt,prior.evaluatedPerformedSetIds(),prior.evaluatedReps(),prior.repsSum(),WorkoutAssessment.RULE_VERSION);
                if(benchmark==null||WorkoutAssessment.benchmarkOrder(evidence,benchmark)<0)benchmark=evidence;
            }
            exercises.add(new WorkoutAssessment.ExerciseResult(item.sessionExerciseId(),item.eligible(),item.reasonCodes(),item.comparisonContext(),item.evaluatedPerformedSetIds(),item.evaluatedReps(),item.repsSum(),benchmark,item.advice(),item.adviceReason()));
        }
        return new WorkoutAssessment.Result(2,WorkoutAssessment.RULE_VERSION,assessedAt,Long.toString(session.version),current.complete(),List.copyOf(exercises));
    }

    WorkoutSessionResponses.Detail start(Instant started) {
        return sessions.start(owner,new WorkoutSessionRequests.Start(UUID.randomUUID(),0,routine,device,started,
                LocalDate.ofInstant(started,ZoneId.of("Asia/Seoul")),"Asia/Seoul")).session();
    }
    State state(WorkoutSessionResponses.Detail session,boolean eligible) {
        var base=session.snapshot().state();var exercise=base.sessionExercises().getFirst().id();var sets=new ArrayList<SetValue>();
        for(int n=1;n<=3;n++)sets.add(new SetValue(UUID.randomUUID(),exercise,n,"60",8,session.startedAt().plusSeconds(n*60),
                new Context(eligible?"INITIAL_APPROVED":"TODAY_ONLY",null),null,null));
        return new State(base.sessionExercises(),base.appliedPlan(),sets,List.of(),List.of(),List.of(),List.of());
    }
    WorkoutSnapshotData.FinishAck finish(WorkoutSessionResponses.Detail session,State state) {
        return snapshots.finish(owner,session.id(),new Finish(new Save(0,device,0,1,state),session.startedAt().plusSeconds(600)));
    }
}
