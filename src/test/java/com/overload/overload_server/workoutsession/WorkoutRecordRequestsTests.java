package com.overload.overload_server.workoutsession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WorkoutRecordRequestsTests {
    static final String ID="11111111-1111-4111-8111-111111111111";
    static String edit() { return """
            {"dataGeneration":"0","expectedVersion":"7","setCorrections":[{"id":"%s","weightKg":"62.5","reps":8}],
             "cardioCorrections":[],"deletedPerformedSetIds":[],"deletedCardioResultIds":[]}
            """.formatted(ID); }
    @Test void closedEditHasNoWriterOrPlanAndKeepsExactDecimal() {
        var value=WorkoutRecordRequests.edit(edit());assertThat(value.scope().expectedVersion()).isEqualTo(7);
        assertThat(value.setCorrections().getFirst().weightKg()).isEqualTo("62.5");
        assertThatThrownBy(()->WorkoutRecordRequests.edit(edit().replace("\"expectedVersion\":\"7\"","\"expectedVersion\":7"))).isInstanceOf(WorkoutSessionFailure.class);
    }
    @ParameterizedTest @ValueSource(strings={"writerEpoch","editorDeviceId","localRevision","appliedPlan","completionAssessment","userId"})
    void editRejectsProtectedOrActiveFields(String key) {
        var node=WorkoutSnapshotData.JSON.readTree(edit());var values=new LinkedHashMap<String,Object>();
        node.propertyNames().forEach(n->values.put(n,node.get(n)));values.put(key,ID);
        assertThatThrownBy(()->WorkoutRecordRequests.edit(WorkoutSnapshotData.json(values))).isInstanceOf(WorkoutSessionFailure.class);
    }
    @Test void editCannotCorrectAndDeleteSameRecordOrSilentlyClearAll() {
        assertThatThrownBy(()->WorkoutRecordRequests.edit(edit().replace("\"deletedPerformedSetIds\":[]","\"deletedPerformedSetIds\":[\""+ID+"\"]"))).isInstanceOf(WorkoutSessionFailure.class);
        assertThatThrownBy(()->WorkoutRecordRequests.edit(edit().replace("[{\"id\":\""+ID+"\",\"weightKg\":\"62.5\",\"reps\":8}]","[]"))).isInstanceOf(WorkoutSessionFailure.class);
        assertThatThrownBy(()->WorkoutRecordRequests.edit(edit().replace("\"reps\":8","\"reps\":0"))).isInstanceOf(WorkoutSessionFailure.class);
    }
    @Test void recoveryRequiresConfirmationAndRetainsOriginalOrigin() {
        String body="""
                {"dataGeneration":"0","expectedVersion":"7","confirm":true,
                 "origin":{"editorDeviceId":"%s","writerEpoch":"0","localRevision":"12"},
                 "performedSets":[{"id":"%s","sessionExerciseId":"%s","ordinal":4,"weightKg":"60","reps":8,
                  "completedAt":"2026-10-31T16:03:00Z","weightContext":{"kind":"UNVERIFIED","requestId":null},"manufacturerId":null,"loadType":null}],"cardioResults":[]}
                """.formatted(ID,ID,ID);
        assertThat(WorkoutRecordRequests.recover(body).origin().localRevision()).isEqualTo("12");
        assertThatThrownBy(()->WorkoutRecordRequests.recover(body.replace("\"confirm\":true","\"confirm\":false"))).isInstanceOf(WorkoutSessionFailure.class);
        assertThatThrownBy(()->WorkoutRecordRequests.recover(body.replace("\"writerEpoch\":\"0\"","\"writerEpoch\":0"))).isInstanceOf(WorkoutSessionFailure.class);
    }
}
