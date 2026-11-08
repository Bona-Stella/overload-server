package com.overload.overload_server.deload;

import com.overload.testfixture.EmailOtpFixtures;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

final class DeloadScenarioClock {
    static final Instant START = Instant.parse("2026-11-08T03:00:00Z");
    private static final Instant ACCOUNT_CREATED_AT = START.minusSeconds(1);

    private DeloadScenarioClock() {}

    static void alignAccountDefaults(JdbcTemplate jdbc, PostgreSQLContainer postgres) {
        assertThat(jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL()))
            .isEqualTo(postgres.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();
        // Give new fixture rows the scenario timeline before the real account and INITIAL epoch insert.
        jdbc.execute("""
            ALTER TABLE users
                ALTER COLUMN created_at SET DEFAULT TIMESTAMPTZ '%s',
                ALTER COLUMN updated_at SET DEFAULT TIMESTAMPTZ '%s'
            """.formatted(ACCOUNT_CREATED_AT, ACCOUNT_CREATED_AT));
    }

    static void set(Instant instant) {
        EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(), instant));
    }

    static void bindNewAccount(DeloadAnalysisRepository repository, UUID owner) {
        var initial = repository.current(owner, 0);
        assertThat(initial.recordedAt()).isEqualTo(ACCOUNT_CREATED_AT);
        assertThat(initial.epochStartedAt()).isEqualTo(initial.recordedAt());
        repository.append(owner, 0, initial.change(true, "Asia/Seoul", EmailOtpFixtures.CLOCK.instant()));
    }
}
