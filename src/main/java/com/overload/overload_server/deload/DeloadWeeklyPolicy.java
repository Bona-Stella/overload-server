package com.overload.overload_server.deload;

import com.overload.overload_server.completion.ExactFraction;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Exact weekly operations and a contiguous fold, never a mutable current-total cache. */
final class DeloadWeeklyPolicy {
    static final int RULE_VERSION=1;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    record Decision(String operation,ExactFraction contribution) { }
    record Fold(ExactFraction weeks,LocalDate confirmedThrough,LocalDate blockedAt) { }
    static LocalDate monday(LocalDate date) {return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));}
    static LocalDate firstWeek(DeloadAnalysisState state,Collection<DeloadWeeklyRepository.Row> rows,LocalDate earlier) {
        var first=monday(state.epochStartedAt().atZone(ZoneId.of(state.timeZone())).toLocalDate());
        if(earlier!=null&&earlier.isBefore(first))first=earlier;
        for(var row:rows)if(row.week().isBefore(first))first=row.week();
        return first;
    }
    static Instant dueAt(LocalDate week,String zone) {return week.plusWeeks(1).atTime(4,0).atZone(ZoneId.of(zone)).toInstant();}
    static LocalDate lastDue(Instant now,String zone) {
        var current=monday(now.atZone(ZoneId.of(zone)).toLocalDate());
        return current.minusWeeks(now.isBefore(current.atTime(4,0).atZone(ZoneId.of(zone)).toInstant())?2:1);
    }
    static Decision decide(ExactFraction e,int n) {
        if(n<0||n>7||e.compareTo(ExactFraction.ZERO)<0||e.compareTo(ExactFraction.of(n,1))>0)throw new IllegalArgumentException("Invalid weekly E/N");
        if(e.compareTo(ExactFraction.of(1,1))<=0)return new Decision("RESET",ExactFraction.ZERO);
        var ratio=new ExactFraction(e.numerator(),e.denominator().multiply(BigInteger.valueOf(n)));
        return new Decision("ADD",ratio.compareTo(ExactFraction.of(1,1))>0?ExactFraction.of(1,1):ratio);
    }
    static boolean excluded(DeloadCandidateCalculator.Week week) {
        return week.days().isEmpty()&&week.pending().equals(Set.of(DeloadCandidateCalculator.Pending.OUTSIDE_ON_INTERVAL));
    }
    static boolean resolvable(DeloadCandidateCalculator.Week week) {
        return week.calculable()&&week.requiredReviewsComplete()||excluded(week);
    }
    static Fold fold(LocalDate first,LocalDate last,List<DeloadWeeklyRepository.Row> rows) {
        var index=new HashMap<LocalDate,DeloadWeeklyRepository.Row>();rows.forEach(r->index.put(r.week(),r));
        var total=ExactFraction.ZERO;LocalDate through=null;
        for(var week=first;!week.isAfter(last);week=week.plusWeeks(1)) {
            var row=index.get(week);
            if(row==null||!row.resolved()||row.dirty()||row.rule()!=RULE_VERSION)return new Fold(total,through,week);
            if(!row.status().equals("EXCLUDED"))total=row.operation().equals("RESET")?ExactFraction.ZERO:total.add(row.contribution());
            through=week;
        }
        return new Fold(total,through,null);
    }
    /** Stable full source vector plus canonical calculation hash. observedAt/review timestamps are not source changes. */
    static String input(DeloadCandidateCalculator.Week week) {
        var revisions=new TreeMap<String,String>();week.sourceRevisions().forEach((d,r)->revisions.put(d.toString(),Long.toString(r)));
        var days=new ArrayList<Object>();
        for(var day:week.days()) {
            var candidates=day.candidates().stream().sorted(Comparator.comparing(c->c.session().toString()))
                .map(c->Arrays.asList(c.session(),c.version(),c.localRevision(),c.snapshotHash(),c.completion())).toList();
            var g=day.g3();Object evidence=g==null?null:Arrays.asList(g.state(),g.revision(),g.dirty(),g.reviewedAt()!=null,g.firstMissedAt(),g.missedScheduleVersion(),g.missedRoutine(),g.missedPlanVersion());
            days.add(Arrays.asList(day.date(),candidates,day.selectedSession(),day.counted(),day.lower(),day.upper(),day.active(),evidence,day.reviewRequired(),day.reviewComplete(),day.pending().stream().map(Enum::name).sorted().toList()));
        }
        var c=week.reviewBoundary();boolean needsCalendar=week.days().stream().anyMatch(DeloadCandidateCalculator.Day::reviewRequired);
        Object calendar=!needsCalendar||c==null?null:Arrays.asList(c.timeZone(),c.baselineVersion(),c.boundary(),c.reviewFrom(),c.reviewedThrough().isAfter(week.weekEndExclusive().minusDays(1))?week.weekEndExclusive().minusDays(1):c.reviewedThrough());
        var first=week.firstScheduleDate();
        String raw=JSON.writeValueAsString(Arrays.asList(RULE_VERSION,week.owner(),week.generation(),week.epoch(),week.weekStart(),
            first!=null&&first.isBefore(week.weekEndExclusive())?first:null,calendar,revisions,days,week.lowerE(),week.upperE(),week.n(),week.requiredReviewsComplete()));
        try {
            var root=new LinkedHashMap<String,Object>();root.put("hash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))));root.put("revisions",revisions);
            return JSON.writeValueAsString(root);
        } catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
