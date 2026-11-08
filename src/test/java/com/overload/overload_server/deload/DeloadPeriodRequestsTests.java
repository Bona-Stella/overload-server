package com.overload.overload_server.deload;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DeloadPeriodRequestsTests {
    final String valid="{\"dataGeneration\":\"0\",\"method\":\"REST\",\"split\":\"REST\",\"dates\":[]}";
    @Test void strictPreviewRejectsOwnershipNumbersDuplicateKeysTrailingJsonAndInvalidDates() {
        assertThat(DeloadPeriodRequests.preview(valid).generation()).isZero();
        for(var bad:List.of(valid.replace("\"0\"","0"),valid.replace("\"0\"","\"00\""),valid.replace("\"0\"","\"9223372036854775808\""),valid.replace("{","{\"userId\":\"bad\","),valid.replace("{","{\"method\":\"KEEP\","),valid+"{}",valid.replace("[]","[\"2026-02-30\"]"),valid.replace("[]","null"),"null"))
            assertThatThrownBy(()->DeloadPeriodRequests.preview(bad)).as(bad).isInstanceOf(DeloadPeriodFailure.class);
    }
    @Test void optionalPresetIdIsCanonicalAndNotNullable() {
        UUID selected=UUID.randomUUID();
        assertThat(DeloadPeriodRequests.preview(valid).presetId()).isNull();
        assertThat(DeloadPeriodRequests.preview(valid.replace("{","{\"presetId\":\""+selected+"\",")).presetId()).isEqualTo(selected);
        assertThatThrownBy(()->DeloadPeriodRequests.preview(valid.replace("{","{\"presetId\":null,")))
                .isInstanceOf(DeloadPeriodFailure.class);
    }
    @Test void startRequiresCanonicalUuidAndFullServerPreviewHash() {
        var input=valid.replace("{","{\"requestId\":\""+UUID.randomUUID()+"\",\"previewToken\":\""+"a".repeat(64)+"\",");assertThat(DeloadPeriodRequests.start(input).previewToken()).isEqualTo("a".repeat(64));
        for(var bad:List.of(input.replace("a".repeat(64),"short"),input.replace("a".repeat(64),"A".repeat(64)),input.replaceFirst("[0-9a-f]{8}-","1-")))assertThatThrownBy(()->DeloadPeriodRequests.start(bad)).isInstanceOf(DeloadPeriodFailure.class);
    }
}
