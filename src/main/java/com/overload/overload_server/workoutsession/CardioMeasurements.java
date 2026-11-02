package com.overload.overload_server.workoutsession;

import java.math.*;
import java.util.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;

/** Actual measurements only. Never accepts a routine target as a performed speed. */
final class CardioMeasurements {
    private static final BigDecimal CAPACITY=new BigDecimal("100000000000000");
    static CardioValue normalize(CardioValue value) {
        var m=value.measurement();String distance=value.distanceKm();
        if(m!=null){
            String speed=m.actualSpeedKmh(),source=m.distanceSource();
            if(speed!=null&&!speed.matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{0,5}[1-9])?"))throw WorkoutSnapshotRequests.invalid();
            if(source!=null&&!Set.of("MANUAL","CALCULATED").contains(source))throw WorkoutSnapshotRequests.invalid();
            if(distance==null&&speed!=null){
                if("MANUAL".equals(source))throw WorkoutSnapshotRequests.invalid();
                distance=calculated(speed,value.activeMs());source="CALCULATED";
            }
            else if("CALCULATED".equals(source)){
                if(speed==null||distance==null||new BigDecimal(distance).compareTo(new BigDecimal(calculated(speed,value.activeMs())))!=0)throw WorkoutSnapshotRequests.invalid();
            }
            else if("MANUAL".equals(source)&&distance==null)throw WorkoutSnapshotRequests.invalid();
            m=new CardioMeasurement(speed,source);
        }
        if(distance!=null){
            var number=new BigDecimal(distance);boolean calculated=m!=null&&"CALCULATED".equals(m.distanceSource());
            if(calculated?(number.signum()<0||number.compareTo(CAPACITY)>=0):(number.compareTo(new BigDecimal("0.001"))<0||number.compareTo(new BigDecimal("1000"))>0))throw WorkoutSnapshotRequests.invalid();
        }
        return new CardioValue(value.timerMs(),value.activeMs(),value.corrected(),distance,value.averageBpm(),m);
    }
    static String calculated(String speed,Long activeMs){
        if(activeMs==null||activeMs<1||activeMs>86400000)throw WorkoutSnapshotRequests.invalid();
        var distance=new BigDecimal(speed).multiply(BigDecimal.valueOf(activeMs)).divide(BigDecimal.valueOf(3600000),6,RoundingMode.HALF_UP);
        if(distance.signum()<0||distance.compareTo(CAPACITY)>=0)throw WorkoutSnapshotRequests.invalid();
        return distance.stripTrailingZeros().toPlainString();
    }
    /** An older writer omitting the extension must not erase previously stored measurement facts. */
    static CardioValue preserveOmitted(CardioValue old,CardioValue value){
        if(value.measurement()!=null||old==null||old.measurement()==null)return normalize(value);
        var previous=old.measurement();String distance=value.distanceKm(),source=previous.distanceSource();
        if(!Objects.equals(distance,old.distanceKm()))source=distance==null?null:"MANUAL";
        else if("CALCULATED".equals(source)&&!Objects.equals(distance,calculated(previous.actualSpeedKmh(),value.activeMs())))source=null;
        return normalize(new CardioValue(value.timerMs(),value.activeMs(),value.corrected(),distance,value.averageBpm(),new CardioMeasurement(previous.actualSpeedKmh(),source)));
    }
}
