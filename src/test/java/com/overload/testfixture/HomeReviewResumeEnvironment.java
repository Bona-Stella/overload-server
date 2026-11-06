package com.overload.testfixture;

import com.overload.overload_server.OverloadServerApplication;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.flywaydb.core.Flyway;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Manual resume of an already-restored, explicitly verified local DB. Never starts Testcontainers or seeds. */
public final class HomeReviewResumeEnvironment {
    public static void main(String[] args) throws Exception {
        if ((args.length != 2 && args.length != 3) || !args[1].equals("--after-t02b-first-ui")) throw new IllegalArgumentException("Explicit T02-B first UI completion required");
        if (System.getenv().keySet().stream().anyMatch(k -> k.matches("^(SPRING_|OTP_|RESEND_|GOOGLE_|AUTH_|OVERLOAD_).*")))
            throw new IllegalStateException("Clean child environment required");
        var json = JsonMapper.builder().build();
        var file = Path.of(args[0]).toAbsolutePath().normalize();
        var allowed = Path.of("../overload-pwa/.local-backups/home-review").toAbsolutePath().normalize();
        if (!file.startsWith(allowed) || !file.getFileName().toString().equals("archive.json")) throw new IllegalArgumentException("Private local archive required");
        var c = json.readTree(Files.readString(file));
        if (!c.get("database").asString().equals("overload_v22_isolated") || !c.get("startupPolicy").asString().equals("MANUAL_AFTER_T02B_FIRST_UI_ONLY"))
            throw new IllegalArgumentException("Wrong review archive");
        int port = c.get("databasePort").asInt();
        if (port <= 0 || port == 5432 || port == 5433) throw new IllegalArgumentException("Dedicated loopback DB required");
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/overload_v22_isolated", password = c.get("databasePassword").asString();
        var dataSource = new DriverManagerDataSource(url, "overload_test", password);
        var direct = new JdbcTemplate(dataSource);
        if (!direct.queryForObject("SELECT current_database()", String.class).equals("overload_v22_isolated")) throw new IllegalStateException("DB identity mismatch");
        if (direct.queryForObject("SELECT count(*) FROM routines", Integer.class) == 0) throw new IllegalStateException("Existing review data required; never seed an empty DB");
        var flyway=Flyway.configure().dataSource(dataSource).load();
        if(args.length==3){
            if(!args[2].equals("--migrate-t02b-v23"))throw new IllegalArgumentException("Explicit additive V23 flag required");
            var before=fingerprints(direct);
            var current=flyway.info().current();
            if(current==null||!List.of("22","23").contains(current.getVersion().toString()))throw new IllegalStateException("Only preserved V22 or V23 review DB accepted");
            if(Arrays.stream(flyway.info().pending()).anyMatch(m->!m.getVersion().toString().equals("23")))throw new IllegalStateException("Only reviewed V23 may be applied");
            var result=flyway.migrate();
            if(!before.equals(fingerprints(direct)))throw new IllegalStateException("Existing domain rows changed during additive migration");
            var proof=Path.of("build/home-review/t02b-v23-preservation.json");Files.createDirectories(proof.getParent());Files.writeString(proof,json.writeValueAsString(Map.of("migrationsExecuted",result.migrationsExecuted,"existingDomainFingerprints",before,"unchanged",true,"database","overload_v22_isolated")));
        }
        flyway.validate();
        String run = UUID.fromString(c.get("resumeRun").asString()).toString(), instance = UUID.randomUUID().toString();
        var directory = Path.of("build/verification-v22", run).toAbsolutePath().normalize();
        Files.createDirectories(directory);Files.deleteIfExists(directory.resolve("preserve-stop"));
        long pid = ProcessHandle.current().pid();
        var app = new SpringApplicationBuilder(OverloadServerApplication.class, WorkoutBrowserEnvironment.TestEmail.class, HomeReviewEnvironment.ReviewTime.class)
                .initializers(context -> {
                    var filter = new FilterRegistrationBean<Filter>((request, response, chain) -> {
                        var incoming = (HttpServletRequest) request;var http = (HttpServletResponse) response;
                        if (!incoming.getMethod().equals("POST") || incoming.getHeader("Origin") != null) { http.setStatus(403);return; }
                        headers(run,instance,pid,"http://127.0.0.1:" + request.getLocalPort()).forEach(http::setHeader);http.setStatus(204);
                    });
                    filter.addUrlPatterns("/__v22_identity");filter.setOrder(Integer.MIN_VALUE);
                    context.getBeanFactory().registerSingleton("preservedReviewIdentity",filter);
                }).web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
                        "--spring.datasource.url="+url,"--spring.datasource.username=overload_test","--spring.datasource.password="+password,
                        "--spring.flyway.enabled=false","--server.address=127.0.0.1","--server.port=0",
                        "--OTP_HMAC_KEY="+c.get("otpHmacKey").asString(),"--OTP_EMAIL_ENABLED=false","--GOOGLE_OIDC_ENABLED=false","--GOOGLE_REAUTH_ENABLED=false",
                        "--spring.session.jdbc.cleanup-cron=-","--logging.level.root=OFF","--spring.main.banner-mode=off","--spring.main.log-startup-info=false");
        String origin="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
        var jdbc=app.getBean(JdbcTemplate.class);var sender=app.getBean(EmailOtpFixtures.FakeSender.class);
        var control=new V22BrowserControl(sender,jdbc,headers(run,instance,pid,origin));
        String email=c.get("reviewEmail").asString();
        var inbox=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        inbox.createContext("/inbox",exchange->{try(exchange){
            if(!exchange.getRequestMethod().equals("POST")||exchange.getRequestHeaders().containsKey("Origin")||!run.equals(exchange.getRequestHeaders().getFirst("X-Review-Run"))){exchange.sendResponseHeaders(403,-1);return;}
            var ids=jdbc.queryForList("SELECT id FROM email_otp_challenges WHERE recipient=? AND purpose='LOGIN' AND state='ACTIVE' AND expires_at>? ORDER BY issued_at DESC LIMIT 1",UUID.class,email,java.sql.Timestamp.from(Instant.now()));
            var messages=ids.stream().map(sender.messages::get).filter(Objects::nonNull).map(m->Map.of("challengeId",m.issuanceId().toString(),"code",m.code())).toList();
            byte[] bytes=json.writeValueAsBytes(Map.of("email",email,"messages",messages));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }});inbox.start();
        var ready=new LinkedHashMap<String,Object>();
        ready.put("runId",run);ready.put("instanceId",instance);ready.put("backendPid",pid);ready.put("backendOrigin",origin);ready.put("browserControlOrigin",control.origin());
        ready.put("database","overload_v22_isolated");ready.put("databasePort",port);ready.put("containerId",c.get("containerId").asString());ready.put("containerRunId",run);ready.put("jdbcUrl",url);
        ready.put("freshDatabase",false);ready.put("storageMode","persistent-restored");ready.put("sourceRun",c.get("sourceRun").asString());ready.put("reviewEmail",email);ready.put("reviewDate",java.time.LocalDate.now().toString());
        ready.put("actualMigrations",jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",String.class));
        ready.put("activeSessions",jdbc.queryForObject("SELECT count(*) FROM workout_sessions WHERE status='ACTIVE'",Integer.class));ready.put("inboxOrigin","http://127.0.0.1:"+inbox.getAddress().getPort());ready.put("clockMode","SYSTEM_UTC_NO_SEED");
        Files.writeString(directory.resolve("home-review.json"),json.writeValueAsString(ready));
        var hook=new Thread(()->{inbox.stop(0);control.close();app.close();});Runtime.getRuntime().addShutdownHook(hook);
        try { while(!Files.exists(directory.resolve("preserve-stop")))Thread.sleep(250); }
        finally { Runtime.getRuntime().removeShutdownHook(hook);inbox.stop(0);control.close();app.close(); }
    }
    private static Map<String,String> fingerprints(JdbcTemplate jdbc){
        var result=new TreeMap<String,String>();
        for(String table:List.of("routines","routine_weight_placements","routine_cardio_placements","routine_plan_history","weekly_schedule_versions","workout_sessions","session_exercises","performed_sets","cardio_results","progress_change_events","session_weight_approval_requests","record_change_events")){
            String fields=table.equals("routines")?"to_jsonb(r)-'tag'":table.equals("routine_cardio_placements")?"to_jsonb(r)-'activity_id'":"to_jsonb(r)";
            result.put(table,jdbc.queryForObject("SELECT count(*)::text||':'||md5(COALESCE(string_agg(md5(("+fields+")::text),'' ORDER BY md5(("+fields+")::text)),'')) FROM "+table+" r",String.class));
        }return result;
    }
    private static Map<String,String> headers(String run,String instance,long pid,String origin){return Map.of("X-V22-Run",run,"X-V22-Instance",instance,"X-V22-Pid",Long.toString(pid),"X-V22-Backend",origin);}
}
