package com.overload.overload_server.workoutsession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;

class BodyweightLoadRequestsTests {
    static final String ID="11111111-1111-4111-8111-111111111111";
    static final String LEGACY="{\"id\":\""+ID+"\",\"sessionExerciseId\":\""+ID+"\",\"ordinal\":1,\"weightKg\":\"80\",\"reps\":8,\"completedAt\":\"2026-11-01T00:00:00Z\",\"weightContext\":{\"kind\":\"TODAY_ONLY\",\"requestId\":null},\"manufacturerId\":null,\"loadType\":\"BODYWEIGHT\"}";
    SetValue parse(String extension){return WorkoutSnapshotRequests.set(JSON.readTree(LEGACY.substring(0,LEGACY.length()-1)+extension+"}"),false);}
    @Test void oldCanonicalJsonIsByteIdenticalAndUnknownWeightDoesNotBecomeExtra(){
        var value=parse("");assertThat(value.bodyweightLoad()).isNull();assertThat(JSON.writeValueAsString(value)).isEqualTo(LEGACY);
        assertThat(JSON.readValue(LEGACY,SetValue.class)).isEqualTo(value);
        assertThat(new SetValue(UUID.fromString(ID),UUID.fromString(ID),1,"80",8,value.completedAt(),value.weightContext(),null,"BODYWEIGHT")).isEqualTo(value);
    }
    @Test void explicitZeroAndClearAreDistinctFromOmissionAndSurviveJsonRoundTrips(){
        var extra=parse(",\"bodyweightLoad\":{\"additionalWeightKg\":\"0\"}");
        var clear=parse(",\"bodyweightLoad\":{\"additionalWeightKg\":null}");
        assertThat(extra.bodyweightLoad()).isEqualTo(new BodyweightLoad("0"));assertThat(clear.bodyweightLoad()).isEqualTo(new BodyweightLoad(null));
        assertThat(parse("").preserveOmittedLoad(extra)).isEqualTo(extra);assertThat(clear.preserveOmittedLoad(extra)).isEqualTo(clear);
        for(var value:new SetValue[]{extra,clear})assertThat(JSON.readValue(JSON.writeValueAsString(value),SetValue.class)).isEqualTo(value);
    }
    @ParameterizedTest @ValueSource(strings={"{}","[]","{\"additionalWeightKg\":5}","{\"additionalWeightKg\":\"-1\"}","{\"additionalWeightKg\":\"1.0\"}","{\"additionalWeightKg\":\"1e2\"}","{\"additionalWeightKg\":\"100000000000000\"}","{\"additionalWeightKg\":\"0.0000001\"}","{\"additionalWeightKg\":\"5\",\"bodyMass\":80}"})
    void invalidEvidenceIsRejected(String raw){assertThatThrownBy(()->parse(",\"bodyweightLoad\":"+raw)).isInstanceOf(WorkoutSessionFailure.class);}
    @Test void otherEquipmentAndUnknownLoadCannotAcquireBodyweightEvidence(){
        for(String load:new String[]{"null","\"PLATE\"","\"STACK\"","\"DUMBBELL\""}){
            String raw=JSON.writeValueAsString(parse(",\"bodyweightLoad\":{\"additionalWeightKg\":\"5\"}")).replace("\"loadType\":\"BODYWEIGHT\"","\"loadType\":"+load);
            assertThatThrownBy(()->WorkoutSnapshotRequests.set(JSON.readTree(raw),false)).isInstanceOf(WorkoutSessionFailure.class);
        }
    }
}
