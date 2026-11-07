package com.overload.overload_server.deload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class DeloadAnalysisTests {
    private final Instant at=Instant.parse("2026-11-07T00:00:00Z");
    @Test void initialOnAndZoneBindingDoNotCreateReactivationEpoch() {
        var initial=new DeloadAnalysisState(0,true,0,at,at,null,"INITIAL");
        var bound=initial.change(true,"Asia/Seoul",at.plusSeconds(1));
        assertThat(bound.version()).isEqualTo(1);assertThat(bound.epoch()).isZero();
        assertThat(bound.epochStartedAt()).isEqualTo(at);assertThat(bound.reason()).isEqualTo("TIME_ZONE_SET");
        assertThat(bound.change(true,"Asia/Seoul",at.minusSeconds(1))).isSameAs(bound);
    }
    @Test void disablingKeepsEpochAndReactivationUsesNewVersionAndServerTime() {
        var initial=new DeloadAnalysisState(0,true,0,at,at,null,"INITIAL");
        var off=initial.change(false,"UTC",at.plusSeconds(1));
        assertThat(off.epoch()).isZero();assertThat(off.epochStartedAt()).isEqualTo(at);
        assertThat(off.reason()).isEqualTo("DISABLED");
        var on=off.change(true,"UTC",at.plusSeconds(2));
        assertThat(on.version()).isEqualTo(2);assertThat(on.epoch()).isEqualTo(2);
        assertThat(on.epochStartedAt()).isEqualTo(at.plusSeconds(2));assertThat(on.reason()).isEqualTo("ENABLED");
    }
    @Test void fixedZoneReverseClockAndOverflowAreRejected() {
        var s=new DeloadAnalysisState(1,true,0,at,at,"Asia/Seoul","TIME_ZONE_SET");
        assertThatThrownBy(()->s.change(false,"UTC",at)).isInstanceOf(DeloadAnalysisFailure.class).hasMessage("DELOAD_TIME_ZONE_CONFLICT");
        assertThatThrownBy(()->s.change(false,"Asia/Seoul",at.minusSeconds(1))).hasMessage("WRITE_CONFLICT");
        var full=new DeloadAnalysisState(Long.MAX_VALUE,true,0,at,at,"UTC","TIME_ZONE_SET");
        assertThatThrownBy(()->full.change(false,"UTC",at)).hasMessage("WRITE_CONFLICT");
    }
    @Test void countersRemainExactStringsAndBooleanIsStrict() {
        var save=DeloadAnalysisRequests.parse("{\"dataGeneration\":\"9007199254740993\",\"expectedVersion\":\"9223372036854775807\",\"enabled\":false,\"timeZone\":\"Asia/Seoul\"}");
        assertThat(save.generation()).isEqualTo(9007199254740993L);assertThat(save.expectedVersion()).isEqualTo(Long.MAX_VALUE);assertThat(save.enabled()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"null","{}","[]",
        "{\"dataGeneration\":0,\"expectedVersion\":\"0\",\"enabled\":true,\"timeZone\":\"UTC\"}",
        "{\"dataGeneration\":\"00\",\"expectedVersion\":\"0\",\"enabled\":true,\"timeZone\":\"UTC\"}",
        "{\"dataGeneration\":\"0\",\"expectedVersion\":\"9223372036854775808\",\"enabled\":true,\"timeZone\":\"UTC\"}",
        "{\"dataGeneration\":\"0\",\"expectedVersion\":\"0\",\"enabled\":\"true\",\"timeZone\":\"UTC\"}",
        "{\"dataGeneration\":\"0\",\"expectedVersion\":\"0\",\"enabled\":null,\"timeZone\":\"UTC\"}",
        "{\"dataGeneration\":\"0\",\"expectedVersion\":\"0\",\"enabled\":true,\"timeZone\":\"+09:00\"}",
        "{\"dataGeneration\":\"0\",\"expectedVersion\":\"0\",\"enabled\":true,\"timeZone\":\"UTC\",\"epoch\":\"1\"}",
        "{\"dataGeneration\":\"0\",\"expectedVersion\":\"0\",\"enabled\":true,\"timeZone\":\"UTC\",\"enabled\":false}"})
    void rejectsMalformedRequests(String raw) { assertThatThrownBy(()->DeloadAnalysisRequests.parse(raw)).isInstanceOf(DeloadAnalysisFailure.class).hasMessage("INVALID_REQUEST"); }
}
