package com.overload.overload_server.workoutsession;

import org.junit.jupiter.api.Test;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;
import static org.assertj.core.api.Assertions.*;

class CardioMeasurementsTests {
    CardioValue value(String distance,String speed,String source){return new CardioValue(1800000L,1800000L,false,distance,null,new CardioMeasurement(speed,source));}
    @Test void actualSpeedAndActivityProduceExactDistanceWithoutAPlan(){
        var actual=CardioMeasurements.normalize(value(null,"8",null));
        assertThat(actual.distanceKm()).isEqualTo("4");assertThat(actual.measurement().distanceSource()).isEqualTo("CALCULATED");
        assertThat(CardioMeasurements.calculated("0",1800000L)).isEqualTo("0");
        assertThat(CardioMeasurements.calculated("1",1200000L)).isEqualTo("0.333333");
        assertThat(CardioMeasurements.calculated("1.8",1L)).isEqualTo("0.000001");
    }
    @Test void manualDistanceAlwaysWinsAndMissingSpeedNeverCreatesDistance(){
        var manual=CardioMeasurements.normalize(value("3.8","8","MANUAL"));assertThat(manual.distanceKm()).isEqualTo("3.8");
        assertThat(CardioMeasurements.normalize(value(null,null,null)).distanceKm()).isNull();
        assertThat(CardioMeasurements.normalize(new CardioValue(1800000L,1800000L,false,null,null)).measurement()).isNull();
        assertThat(CardioMeasurements.normalize(value("3.8","8",null)).measurement().distanceSource()).isNull();
    }
    @Test void falseCalculationClaimsInvalidSourcesAndCapacityOverflowAreRejected(){
        for(var input:new CardioValue[]{value("5","8","CALCULATED"),value("4",null,"CALCULATED"),value(null,null,"MANUAL"),value("4","8","TARGET")})
            assertThatThrownBy(()->CardioMeasurements.normalize(input)).isInstanceOf(WorkoutSessionFailure.class);
        assertThatThrownBy(()->CardioMeasurements.calculated("99999999999999.999999",86400000L)).isInstanceOf(WorkoutSessionFailure.class);
        assertThatThrownBy(()->CardioMeasurements.calculated("8",null)).isInstanceOf(WorkoutSessionFailure.class);
    }
    @Test void omittedMetadataNeverReplacesSubmittedDistancesAndDoesNotClaimAStaleCalculation(){
        var changedTime=new CardioValue(1800000L,3600000L,true,"3.8",120);
        var kept=CardioMeasurements.preserveOmitted(value("3.8","8","MANUAL"),changedTime);
        assertThat(kept.distanceKm()).isEqualTo("3.8");assertThat(kept.measurement()).isEqualTo(new CardioMeasurement("8","MANUAL"));
        var recalculated=CardioMeasurements.preserveOmitted(value("4","8","CALCULATED"),new CardioValue(1800000L,3600000L,true,"4",null));
        assertThat(recalculated.distanceKm()).isEqualTo("4");assertThat(recalculated.measurement().distanceSource()).isNull();assertThat(recalculated.measurement().actualSpeedKmh()).isEqualTo("8");
        var edited=CardioMeasurements.preserveOmitted(value("4","8","CALCULATED"),new CardioValue(1800000L,1800000L,false,"3.5",null));
        assertThat(edited.distanceKm()).isEqualTo("3.5");assertThat(edited.measurement().distanceSource()).isEqualTo("MANUAL");
        var cleared=CardioMeasurements.preserveOmitted(value("4","8","CALCULATED"),value(null,null,null));assertThat(cleared.distanceKm()).isNull();assertThat(cleared.measurement().actualSpeedKmh()).isNull();
    }
    @Test void legacyJsonRetainsExactlyFiveFieldsAndDoesNotChangeFrozenPayloadSerialization(){
        var old=new CardioValue(1000L,1000L,false,"1.25",null);
        assertThat(JSON.writeValueAsString(old)).isEqualTo("{\"timerMs\":1000,\"activeMs\":1000,\"corrected\":false,\"distanceKm\":\"1.25\",\"averageBpm\":null}");
        assertThat(JSON.readValue(JSON.writeValueAsString(old),CardioValue.class)).isEqualTo(old);
        var extended=value(null,null,null);assertThat(JSON.writeValueAsString(extended)).contains("\"measurement\":{\"actualSpeedKmh\":null,\"distanceSource\":null}");
    }
}
