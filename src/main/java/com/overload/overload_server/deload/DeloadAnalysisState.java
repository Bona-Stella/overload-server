package com.overload.overload_server.deload;

import java.time.Instant;
import static com.overload.overload_server.deload.DeloadAnalysisFailure.Reason.*;

record DeloadAnalysisState(long version, boolean enabled, long epoch, Instant recordedAt,
                           Instant epochStartedAt, String timeZone, String reason) {
    DeloadAnalysisState change(boolean nextEnabled, String zone, Instant now) {
        if(timeZone!=null&&!timeZone.equals(zone))throw new DeloadAnalysisFailure(DELOAD_TIME_ZONE_CONFLICT);
        if(enabled==nextEnabled&&zone.equals(timeZone))return this;
        if(version==Long.MAX_VALUE||now.isBefore(recordedAt))throw new DeloadAnalysisFailure(WRITE_CONFLICT);
        long next=version+1;
        return new DeloadAnalysisState(next,nextEnabled,!enabled&&nextEnabled?next:epoch,now,
                !enabled&&nextEnabled?now:epochStartedAt,zone,
                enabled==nextEnabled?"TIME_ZONE_SET":nextEnabled?"ENABLED":"DISABLED");
    }
}
