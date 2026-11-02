package com.overload.overload_server.statistics;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class GrowthQueryTests {
    @Test void calendarMonthsClampLeapDaysAndUseTheRequestedLocalDate(){
        var query=new GrowthQuery("1","Asia/Seoul","",0);
        var range=query.range(Instant.parse("2024-03-30T15:00:00Z"));
        assertThat(range.fromDate()).isEqualTo(LocalDate.of(2024,2,29));assertThat(range.toDateExclusive()).isEqualTo(LocalDate.of(2024,4,1));
        assertThat(new GrowthQuery("6","UTC","",0).range(Instant.parse("2026-11-02T00:00:00Z")).fromDate()).isEqualTo(LocalDate.of(2026,5,2));
        assertThat(new GrowthQuery("all","UTC","",0).range(Instant.EPOCH).fromDate()).isNull();
    }
    @Test void queryRejectsForgedScopeDuplicatePeriodUnknownZoneAndOverflow(){
        var values=new LinkedMultiValueMap<String,String>();values.add("period","3");values.add("timeZone","UTC");
        assertThat(GrowthQuery.parse(values,true,true).offset()).isZero();
        for(String[] pair:new String[][]{{"userId","fake"},{"period","1"},{"offset","01"},{"offset","2147483647"},{"q","x".repeat(101)}}){
            var copy=new LinkedMultiValueMap<>(values);copy.add(pair[0],pair[1]);assertThatThrownBy(()->GrowthQuery.parse(copy,true,true)).isInstanceOf(StatisticsFailure.class);
        }
        values.set("timeZone","wrong");assertThatThrownBy(()->GrowthQuery.parse(values,true,true)).isInstanceOf(StatisticsFailure.class);
        assertThatThrownBy(()->GrowthQuery.id("1-1-1-1-1")).isInstanceOf(StatisticsFailure.class);
    }
}
