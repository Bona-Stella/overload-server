package com.overload.overload_server.deload;

import com.overload.overload_server.completion.ExactFraction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DeloadAnalysisQueryTests {
    final LocalDate first=LocalDate.of(2026,10,5);
    DeloadWeeklyRepository.Row row(int offset,String status,boolean dirty,String op,ExactFraction add) {
        return new DeloadWeeklyRepository.Row(first.plusWeeks(offset),status,dirty,1,1,null,null,op,add,"input");
    }
    DeloadAnalysisQuery.Result view(int last,List<DeloadWeeklyRepository.Row> rows,boolean review) {
        var end=first.plusWeeks(last);return DeloadAnalysisQuery.project(first,end,rows,DeloadWeeklyPolicy.fold(first,end,rows),review);
    }
    @Test void initialIsNotConfirmedResetZero() {
        var initial=view(-1,List.of(),false);assertThat(initial.status()).isEqualTo("INITIAL");assertThat(initial.weeks()).isNull();assertThat(initial.through()).isNull();
        var zero=view(0,List.of(row(0,"CONFIRMED",false,"RESET",ExactFraction.ZERO)),false);
        assertThat(zero.status()).isEqualTo("CONFIRMED");assertThat(zero.weeks()).isEqualTo(ExactFraction.ZERO);assertThat(zero.from()).isEqualTo(first);assertThat(zero.through()).isEqualTo(first);
    }
    @Test void gapPendingAndReviewHaveNoInventedConfirmedZero() {
        assertThat(view(0,List.of(),false).status()).isEqualTo("PENDING");assertThat(view(0,List.of(),true).status()).isEqualTo("AWAITING_REVIEW");
        var pending=view(0,List.of(row(0,"PENDING",false,null,null)),false);assertThat(pending.weeks()).isNull();assertThat(pending.blockedAt()).isEqualTo(first);
    }
    @Test void dirtyOrMissingEarlierHistoryCannotPublishTheLaterAdd() {
        var rows=List.of(row(0,"CONFIRMED",false,"ADD",ExactFraction.of(3,4)),row(1,"CONFIRMED",true,"ADD",ExactFraction.of(1,1)),row(2,"CONFIRMED",false,"ADD",ExactFraction.of(1,1)));
        var result=view(2,rows,false);assertThat(result.status()).isEqualTo("RECALCULATING");assertThat(result.weeks()).isEqualTo(ExactFraction.of(3,4));assertThat(result.through()).isEqualTo(first);assertThat(result.blockedAt()).isEqualTo(first.plusWeeks(1));
        assertThat(view(2,List.of(rows.getLast()),false).weeks()).isNull();assertThat(view(2,List.of(rows.getLast()),false).status()).isEqualTo("RECALCULATING");
    }
    @Test void excludedIsResolvedWithoutResetAndItsDirtyStateStillBlocks() {
        var rows=List.of(row(0,"CONFIRMED",false,"ADD",ExactFraction.of(1,1)),row(1,"EXCLUDED",false,null,null),row(2,"CONFIRMED",false,"ADD",ExactFraction.of(3,4)));
        var result=view(2,rows,false);assertThat(result.weeks()).isEqualTo(ExactFraction.of(7,4));assertThat(result.status()).isEqualTo("CONFIRMED");assertThat(result.through()).isEqualTo(first.plusWeeks(2));
        assertThat(view(0,List.of(row(0,"EXCLUDED",true,null,null)),false).status()).isEqualTo("RECALCULATING");
    }
    @ParameterizedTest @CsvSource({"79999999999999,10000000000000,false","8,1,true","33,4,true"})
    void exactRecommendationDoesNotUseRoundedDisplayOrClamp(long n,long d,boolean ready) {
        var value=new ExactFraction(java.math.BigInteger.valueOf(n),java.math.BigInteger.valueOf(d));var rows=new ArrayList<DeloadWeeklyRepository.Row>();
        for(int i=0;i<n/d;i++)rows.add(row(i,"CONFIRMED",false,"ADD",ExactFraction.of(1,1)));
        if(n%d!=0)rows.add(row(rows.size(),"CONFIRMED",false,"ADD",new ExactFraction(java.math.BigInteger.valueOf(n%d),java.math.BigInteger.valueOf(d))));
        var result=view(rows.size()-1,rows,false);
        assertThat(result.recommendationReady()).isEqualTo(ready);assertThat(result.weeks()).isEqualTo(value);
        if(!ready)assertThat(value.decimal()).isEqualTo("8");
    }
    @Test void evenEightInATrustedPrefixDoesNotCreateRecommendationWhileWaiting() {
        var rows=new ArrayList<DeloadWeeklyRepository.Row>();for(int i=0;i<8;i++)rows.add(row(i,"CONFIRMED",false,"ADD",ExactFraction.of(1,1)));
        var result=view(8,rows,false);assertThat(result.weeks()).isEqualTo(ExactFraction.of(8,1));assertThat(result.recommendationReady()).isFalse();assertThat(result.status()).isEqualTo("PENDING");
    }
    @Test void futurePublicationIsNotVisibleBeforeItsMondayFourAmBoundary() {
        assertThat(view(-1,List.of(row(0,"CONFIRMED",false,"ADD",ExactFraction.of(1,1))),false).status()).isEqualTo("INITIAL");
    }
}
