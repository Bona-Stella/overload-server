package com.overload.overload_server.workoutsession;

import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotRequests.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;
import static com.overload.overload_server.workoutsession.WorkoutRecordData.*;

final class WorkoutRecordRequests {
    static Scope scope(JsonNode b) { return new Scope(counter(b,"dataGeneration"),counter(b,"expectedVersion")); }
    static Scope delete(String raw) {
        var b=parse(raw);fields(b,"dataGeneration","expectedVersion");return scope(b);
    }
    static Edit edit(String raw) {
        var b=parse(raw);fields(b,"dataGeneration","expectedVersion","setCorrections","cardioCorrections","deletedPerformedSetIds","deletedCardioResultIds");
        var sets=new ArrayList<SetCorrection>();var cardio=new ArrayList<CardioCorrection>();
        for(var p:array(b,"setCorrections")) {
            fields(p,"id","weightKg","reps");sets.add(new SetCorrection(uuid(p,"id"),decimal(p,"weightKg"),count(p,"reps")));
        }
        for(var p:array(b,"cardioCorrections")) {
            optionalFields(p,"measurement","id","activeMs","corrected","distanceKm","averageBpm");
            // Equality to the immutable stored timer is checked under the session lock.
            var checked=cardioNumbers(p,null);
            cardio.add(new CardioCorrection(uuid(p,"id"),checked.activeMs(),checked.corrected(),checked.distanceKm(),checked.averageBpm(),checked.measurement()));
        }
        var deletedSets=ids(b,"deletedPerformedSetIds");var deletedCardio=ids(b,"deletedCardioResultIds");
        var setIds=new ArrayList<>(sets.stream().map(SetCorrection::id).toList());setIds.addAll(deletedSets);unique(setIds);
        var cardioIds=new ArrayList<>(cardio.stream().map(CardioCorrection::id).toList());cardioIds.addAll(deletedCardio);unique(cardioIds);
        if(setIds.isEmpty()&&cardioIds.isEmpty())throw invalid();
        return new Edit(scope(b),List.copyOf(sets),List.copyOf(cardio),deletedSets,deletedCardio);
    }
    static Recovery recover(String raw) {
        var b=parse(raw);fields(b,"dataGeneration","expectedVersion","confirm","origin","performedSets","cardioResults");
        if(!b.get("confirm").isBoolean()||!b.get("confirm").asBoolean())throw invalid();
        var o=b.get("origin");fields(o,"editorDeviceId","writerEpoch","localRevision");
        var origin=new Origin(uuid(o,"editorDeviceId"),Long.toString(counter(o,"writerEpoch")),Long.toString(counter(o,"localRevision")));
        var sets=new ArrayList<SetValue>();var cardio=new ArrayList<Cardio>();
        for(var p:array(b,"performedSets"))sets.add(set(p,true));
        for(var p:array(b,"cardioResults")) {
            var value=cardio(p);if(!value.status().equals("COMPLETED"))throw invalid();cardio.add(value);
        }
        unique(sets.stream().map(SetValue::id).toList());unique(cardio.stream().map(Cardio::id).toList());
        if(sets.isEmpty()&&cardio.isEmpty())throw invalid();
        return new Recovery(scope(b),origin,List.copyOf(sets),List.copyOf(cardio));
    }
    static ClosedResolve resolve(String raw) {
        var b=parse(raw);fields(b,"dataGeneration","expectedVersion","sessionExerciseId","pendingRequestIds","decision","occurredAt","newApprovalRequest");
        var exercise=uuid(b,"sessionExerciseId");var pending=ids(b,"pendingRequestIds");if(pending.isEmpty())throw invalid();
        var decision=text(b,"decision");var occurred=time(b,"occurredAt");
        return new ClosedResolve(scope(b),exercise,pending,decision,occurred,reapproval(b,exercise,occurred,decision));
    }
}
