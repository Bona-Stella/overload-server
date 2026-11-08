package com.overload.testfixture;

import com.overload.overload_server.auth.email.OtpEmailSender;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** User-review fixture: real production HTTP APIs, a fresh labelled PostgreSQL, no developer configuration. */
public final class HomeReviewEnvironment {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    @TestConfiguration(proxyBeanMethods = false)
    public static class ReviewTime {
        @Bean @Primary ReviewClock homeReviewClock() { return new ReviewClock(); }
    }
    public static final class ReviewClock extends Clock {
        private volatile Instant fixed;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return fixed == null ? Instant.now() : fixed; }
        void at(LocalDate day, int hour) { fixed = day.atTime(hour, 0).atZone(ZONE).toInstant(); }
        void live() { fixed = null; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply one new review UUID");
        UUID run = UUID.fromString(args[0]);
        try (var environment = V22BrowserEnvironment.start(run, false, ReviewTime.class)) {
            var clock = environment.context().getBean(ReviewClock.class);
            var jdbc = environment.context().getBean(JdbcTemplate.class);
            var sender = environment.context().getBean(EmailOtpFixtures.FakeSender.class);
            var today = LocalDate.now(ZONE);
            String email = "home-" + run.toString().substring(0, 8) + "@example.test";
            String origin = environment.evidence().get("backendOrigin").toString();
            Map<String,Object> samples;
            try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
                samples = seed(client, origin, sender, jdbc, clock, today, email);
            } finally { clock.live(); }
            // Mail delivery is local and opt-in; OTP verification itself remains the production API.
            var inbox = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            inbox.createContext("/inbox", exchange -> {
                try (exchange) {
                    if (!exchange.getRequestMethod().equals("POST") || exchange.getRequestHeaders().containsKey("Origin")
                            || !run.toString().equals(exchange.getRequestHeaders().getFirst("X-Review-Run"))) {
                        exchange.sendResponseHeaders(403, -1); return;
                    }
                    var activeIds = jdbc.queryForList("SELECT id FROM email_otp_challenges WHERE recipient=? AND purpose='LOGIN' AND state='ACTIVE' AND expires_at>? ORDER BY issued_at DESC LIMIT 1",UUID.class,email,java.sql.Timestamp.from(clock.instant()));
                    var messages = activeIds.stream().map(sender.messages::get).filter(Objects::nonNull).map(m -> Map.of("challengeId",m.issuanceId().toString(),"code",m.code())).toList();
                    var bytes = JSON.writeValueAsString(Map.of("email",email,"messages",messages)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.getResponseHeaders().set("Cache-Control","no-store");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                }
            });
            inbox.start();
            var ready = new LinkedHashMap<String,Object>(environment.evidence());
            ready.put("reviewEmail", email); ready.put("reviewDate", today.toString());
            ready.put("inboxOrigin", "http://127.0.0.1:" + inbox.getAddress().getPort());
            ready.put("clockMode", "SYSTEM_UTC_AFTER_API_SEED");
            ready.put("preparedUsers", jdbc.queryForObject("SELECT count(*) FROM users",Integer.class));
            ready.put("sampleApiEvidence", samples);
            ready.put("actualMigrations", jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class));
            ready.put("activeSessions", jdbc.queryForObject("SELECT count(*) FROM workout_sessions WHERE status='ACTIVE'",Integer.class));
            Files.writeString(environment.directory().resolve("home-review.json"),JSON.writeValueAsString(ready),StandardOpenOption.CREATE_NEW);
            var hook = new Thread(() -> { try { inbox.stop(0); environment.close(); } catch (Exception ignored) { } });
            Runtime.getRuntime().addShutdownHook(hook);
            System.out.println("HOME_REVIEW_READY " + run + " " + origin);
            try {
                var stop = environment.directory().resolve("stop");
                while (!(Files.exists(stop) && Files.readString(stop).trim().equals(run.toString()))) Thread.sleep(250);
            } finally { inbox.stop(0); Runtime.getRuntime().removeShutdownHook(hook); }
        }
    }

    private static Map<String,Object> seed(HttpClient client, String origin, EmailOtpFixtures.FakeSender sender, JdbcTemplate jdbc,
                             ReviewClock clock, LocalDate today, String email) throws Exception {
        var monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        clock.at(monday.minusWeeks(1), 7);
        request(client,origin,"/api/v1/auth/email-otp/request","POST",Map.of("email",email),202);
        var message = sender.messages.values().stream().filter(m -> m.recipient().equals(email)).findFirst().orElseThrow();
        request(client,origin,"/api/v1/auth/email-otp/verify","POST",Map.of("email",email,"challengeId",message.issuanceId().toString(),"code",message.code()),204);
        var settings = request(client,origin,"/api/v1/workout-settings","GET",null,200);
        String generation = settings.get("dataGeneration").asString();
        UUID bench=UUID.randomUUID(), row=UUID.randomUUID(), squat=UUID.randomUUID(), maker=UUID.randomUUID();
        // Catalog definitions are administration data; workout/routine/schedule facts below use HTTP only.
        jdbc.update("INSERT INTO standard_exercise(id,name,joint_type) VALUES (?,'벤치 프레스','COMPOUND'),(?,'랫 풀다운','COMPOUND'),(?,'바벨 스쿼트','COMPOUND')",bench,row,squat);
        jdbc.update("INSERT INTO manufacturer(id,name) VALUES (?,'검토용 플레이트 장비')",maker);
        var muscle=UUID.randomUUID(); jdbc.update("INSERT INTO muscle(id,group_code,name) VALUES (?,'CHEST','대흉근')",muscle);
        jdbc.update("INSERT INTO exercise_muscle(standard_exercise_id,muscle_id,role) VALUES (?,?,'PRIMARY')",bench,muscle);
        UUID push=UUID.randomUUID(), pull=UUID.randomUUID(), mixed=UUID.randomUUID();
        createRoutine(client,origin,generation,push,"상체 PUSH",List.of(weight(bench,maker,"60",3,6,10),weight(row,null,"40",2,8,12)),List.of());
        createRoutine(client,origin,generation,pull,"하체 · 등 근력",List.of(weight(squat,maker,"80",4,6,8),weight(row,null,"45",3,8,12)),List.of());
        var cardio = new LinkedHashMap<String,Object>(); cardio.put("kind","TREADMILL");cardio.put("name","러닝머신");cardio.put("targetSeconds",1200);cardio.put("targetDistanceKm",null);cardio.put("intensity",Map.of("mode","SPEED","value","8"));cardio.put("inclinePercent",null);cardio.put("resistance",null);
        createRoutine(client,origin,generation,mixed,"전신 근력과 유산소 · 긴 루틴명 확인용",List.of(weight(bench,maker,"55",2,8,12),weight(squat,maker,"60",3,8,10)),List.of(Map.of("id",UUID.randomUUID().toString(),"plan",cardio)));
        var assignments = new ArrayList<Map<String,Object>>();
        // Today is always planned, with at least one rest day; earlier Monday is a full API workout.
        for(int day : new int[]{1,2,4,6}) assignments.add(Map.of("dayOfWeek",day,"routineId",(day==1?push:day==2?pull:mixed).toString()));
        int todayDay=today.getDayOfWeek().getValue();
        if(assignments.stream().noneMatch(a->a.get("dayOfWeek").equals(todayDay))) assignments.add(Map.of("dayOfWeek",todayDay,"routineId",mixed.toString()));
        var initial=request(client,origin,"/api/v1/weekly-schedule","GET",null,200);
        String schedulePath="/api/v1/weekly-schedule/presets/"+initial.get("activePresetId").asString()+"/assignments";
        var applied=request(client,origin,schedulePath,"PUT",Map.of("dataGeneration",generation,"expectedStateVersion",initial.get("stateVersion").asString(),"expectedPresetVersion","0","timeZone",ZONE.getId(),"assignments",assignments),200);
        var dates=today.equals(monday)?List.of(monday.minusWeeks(2),monday.minusWeeks(1),monday.minusDays(4)):List.of(monday.minusWeeks(1),monday.minusDays(4),monday);
        for(var date : dates) {
            clock.at(date,18); finishRoutine(client,origin,generation,date.equals(monday.minusDays(4))?pull:push,date,clock);
        }
        // Apply a later revision while preserving evidence from the earlier dates.
        clock.at(today,7);
        var pending=new ArrayList<>(assignments);pending.removeIf(a->a.get("dayOfWeek").equals(7));pending.add(Map.of("dayOfWeek",7,"routineId",pull.toString()));
        request(client,origin,schedulePath,"PUT",Map.of("dataGeneration",generation,"expectedStateVersion",applied.get("stateVersion").asString(),"expectedPresetVersion","1","assignments",pending),200);
        var active=request(client,origin,"/api/v1/workout-sessions/active","GET",null,200);
        if(!active.get("session").isNull())throw new IllegalStateException("Review must start without an active workout");
        var routines=request(client,origin,"/api/v1/routines","GET",null,200);
        var overview=request(client,origin,"/api/v1/statistics/overview?fromDate="+monday+"&toDateExclusive="+monday.plusWeeks(1),"GET",null,200);
        if(routines.get("routines").size()!=3 || overview.get("statistics").get("recentSessions").size()!=3)
            throw new IllegalStateException("Review API sample counts do not match");
        return Map.of("routineCount",3,"recentSessionCount",3,"workoutDates",dates.stream().map(LocalDate::toString).toList(),"weekPlannedDays",overview.get("plannedDays"),"weekWeightSummary",overview.get("statistics").get("summary"));
    }
    private static Map<String,Object> weight(UUID exercise,UUID maker,String kg,int sets,int min,int max) {
        var w=new LinkedHashMap<String,Object>();w.put("id",UUID.randomUUID().toString());w.put("standardExerciseId",exercise.toString());w.put("manufacturerId",maker==null?null:maker.toString());w.put("loadType",maker==null?"STACK":"PLATE");w.put("setCount",sets);w.put("minReps",min);w.put("maxReps",max);w.put("initialWeightKg",kg);return w;
    }
    private static void createRoutine(HttpClient c,String o,String g,UUID id,String name,List<Map<String,Object>> weights,List<Map<String,Object>> cardio)throws Exception {
        request(c,o,"/api/v1/routines","POST",Map.of("id",id.toString(),"dataGeneration",g,"name",name,"weightPlacements",weights,"cardioPlacements",cardio),201);
    }
    private static void finishRoutine(HttpClient c,String o,String g,UUID routine,LocalDate date,ReviewClock clock)throws Exception {
        UUID device=UUID.randomUUID(), id=UUID.randomUUID(); Instant started=clock.instant();
        var session=request(c,o,"/api/v1/workout-sessions","POST",Map.of("id",id.toString(),"dataGeneration",g,"routineId",routine.toString(),"editorDeviceId",device.toString(),"startedAt",clock.instant().toString(),"workoutDate",date.toString(),"startedTimeZone",ZONE.getId()),201);
        var snapshot=JSON.readTree(JSON.writeValueAsString(session.get("snapshot").get("state")));
        var records=new ArrayList<Map<String,Object>>();
        for(var exercise:session.get("initialPlan").get("weightExercises")) {
            for(int ordinal=1;ordinal<=exercise.get("setCount").asInt();ordinal++) {
                var context=new LinkedHashMap<String,Object>();context.put("kind","INITIAL_APPROVED");context.put("requestId",null);
                var record=new LinkedHashMap<String,Object>();record.put("id",UUID.randomUUID().toString());record.put("sessionExerciseId",exercise.get("sessionExerciseId").asString());record.put("ordinal",ordinal);record.put("weightKg",exercise.get("approvedWeightKg").asString());record.put("reps",exercise.get("maxReps").asInt());record.put("completedAt",started.plusSeconds(ordinal*60L).toString());record.put("weightContext",context);record.put("manufacturerId",exercise.get("manufacturerId").isNull()?null:exercise.get("manufacturerId").asString());record.put("loadType",exercise.get("loadType").asString());records.add(record);
            }
        }
        if(!snapshot.isObject())throw new IllegalStateException("Expected a snapshot object");
        var value=(ObjectNode)snapshot;value.set("performedSets",JSON.valueToTree(records));
        clock.at(date,19);
        request(c,o,"/api/v1/workout-sessions/"+id+"/finish","POST",Map.of("dataGeneration",g,"editorDeviceId",device.toString(),"writerEpoch","0","localRevision","1","snapshot",value,"endedAt",started.plusSeconds(1800).toString()),200);
    }
    private static JsonNode request(HttpClient client,String origin,String path,String method,Object body,int expected)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(15)).header("Accept","application/json");
        if(!method.equals("GET")) {
            var csrf=request(client,origin,"/api/v1/auth/csrf","GET",null,200);builder.header(csrf.get("headerName").asString(),csrf.get("token").asString());
        }
        if(body!=null)builder.header("Content-Type","application/json");
        var response=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=expected)throw new IllegalStateException("Review API "+method+" "+path+" status="+response.statusCode()+" code="+response.body());
        return response.body().isBlank()?JSON.readTree("{}"):JSON.readTree(response.body());
    }
}
