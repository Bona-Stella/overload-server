# G4-4 주간 확정·RESET/ADD·내부 누적

2026-11-07, Asia/Seoul. [디로딩 v1.7 DL-05~14](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md), [G4 준비5절](../../../overload-pwa/docs/tasks/deload-v17-backend-readiness-analysis.md#5-g4-개발-범위의존성검증-계획), [G4-1](deload-g4-1-report.md)·[API](../api/deload-analysis.md), [G4-2](deload-g4-2-report.md), [G4-3](deload-g4-3-report.md)를 기준으로 한다. 준비/이전 단계 보고서의 당시 미구현 문구와 이번 완료 범위를 구분한다.

시작은 양쪽 `feature/weekly-schedule-daily-review-t02b`·clean이었다. Backend `6fdc5195e22dadd1158f825d8b06b39e0fa395e4`, PWA `91a64f55f206e759bb1d6f3022aa79217985616c`를 확인했다. **G4-4 내부 확정/저장/fold만 구현하며 G4-5 과거 정정 재계산과 G4-6 최종 조회·G5/G6·main 병합은 하지 않는다.**

## 1. 구현 구조

| 파일 | 책임 |
|---|---|
| [DeloadWeeklyPolicy](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyPolicy.java) | 고정 IANA 시간대의 다음 월04시·정확 E 임계값/기여·연속된 주간 결과 fold·canonical 입력 식별 |
| [DeloadWeeklyRepository](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyRepository.java) | V25 주간 결과/진도, 독립 분석 계정 탐색, owner 잠금, PK/CAS, 원본 revision 감사와 G3 전 영속 invalidation |
| [DeloadWeeklyFinalization](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyFinalization.java) | 20-owner·owner당 최대4주 창, 최초 미처리 gap부터 순차 확정, 실패/PENDING 재시도, 내부 누적 조회 |
| [DeloadWeeklyCandidates](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyCandidates.java) | 기존 read-only REPEATABLE_READ 계산 유지. 새 calculateLocked는 owner 잠금이 있는 호출자의 READ_COMMITTED 트랜잭션에 MANDATORY로 참여 |
| [DeloadCandidateCalculator](../../src/main/java/com/overload/overload_server/deload/DeloadCandidateCalculator.java) | 기존 후보/E/N 결과 유지. 날짜7개 전체의 source revision 벡터만 추가 |
| [DailyTrainingReviewService](../../src/main/java/com/overload/overload_server/statistics/DailyTrainingReviewService.java)·[Job](../../src/main/java/com/overload/overload_server/statistics/DailyTrainingReviewJob.java) | 기존 G3 dirty 해제 전에 주간 표식을 같은 트랜잭션으로 보존. 기존 tick에서 G3 뒤 G4 호출 |

새 API·스케줄러·migration·라이브러리·current-total 캐시·일별 점수 표·범용 이벤트 시스템을 만들지 않는다. PlanningCalculator/일반 통계·G3 판정식·운동 START/FINISH/승인/고정 snapshot/ACK·writerEpoch·인증·PWA는 그대로다.

## 2. 월요일04시와 대상 탐색

`week_start+1주`의 고정 분석 시간대04:00을 publication 경계로 사용한다. 03:59:59에는 예약/확정하지 않으며 04:00부터 검사한다. Java ZoneId 경계와 SQL의 `(로컬시각-4시간)` 주 경계를 대응시켜 DST/IANA 시간대를 유지한다.

현재 owner/세대·최신 ON·확정 시간대 설정에서만 새 결과를 확정한다. OFF면 G4를 중단하고 G3는 계속 실행한다. 재ON은 새 epoch이며 이전 epoch의 저장 결과/실적을 새 epoch에 합치지 않는다. epoch 시작 전/전부 OFF인 주를 정상 N0 주로 만들지 않는다. 시작 주는 epoch의 월요일이며, ON startedAt을 갖는 실제 적격 세션의 저장 workoutDate가 이전 주이면 그 이전 주부터 확인한다. START만으로 과거 주를 추가하지 않는다. 한 번 저장한 최초 주도 시작점 근거로 보존해, 여행 날짜의 원본이 삭제돼도 그 dirty 주를 누적 prefix에서 건너뛰지 않는다.

독립 배치 SQL은 최신 분석 설정·현재 users 세대를 기준으로, 최초 미처리 gap/PENDING·due backlog·저장 source vector와 달라진 계정을 찾는다. calendar_control만으로 사용자를 찾지 않으며 가짜 일정/control은 만들지 않는다. PK로 범위가 제한된 주간/일별 근거를 조인해 한 번에 최대20 owner를 선택한다. 같은 owner의 재시도 updated_at 순으로 처리 기회를 돌린다. 날짜별 N+1 조회나 누락 주 전체 generate_series는 없다.

각 owner는 최대4주(28일)를 한 트랜잭션에서 처리한다. 가장 이른 미처리 주에서 시작하고 중간 보류에서 멈춘다. 나중 확정 행이 있더라도 앞의 gap을 건너뛰지 않는다. 남은 due 주는 다음 tick에서 다시 탐색하며 오래된 주를 잘라 버리지 않는다. 이미 확정된 dirty 주는 재계산하지 않고 영속 표식을 유지해 G4-5로 넘기며 그 owner의 새 후속 확정도 보류한다.

## 3. 확정 조건과 저장

owner FOR UPDATE·READ_COMMITTED 아래에서 현재 ON/epoch/시간대와 due를 확인하고 V25 PENDING 행을 준비한다. G4-3을 호출해 실제 E/N·G3 검사 완료·ACTIVE/UNKNOWN·시간대/원본 손상을 검사한다. calculable만으로 허가하지 않으며 필요한 검사 완료·시각 조건도 별도로 확인한다.

계산 후 같은 owner 잠금에서 입력을 다시 읽고 canonical 식별값 및 최신 설정 전체를 비교한다. 다르면 STALE/PENDING으로 남기고 숫자를 확정하지 않는다. 일치하면 `status=PENDING AND revision=expected` CAS로 CONFIRMED를 저장한다. 기존 운동 쓰기는 users FOR SHARE/FOR UPDATE를 먼저 취하므로 확정의 FOR UPDATE와 충돌한다. G3/설정 변경/START/expiry도 이 경계에 참여한다. 직접 SQL로 이 계약을 우회하는 운영 변경은 승인된 쓰기 정지/별도 관리가 필요하다.

V25의 owner/generation/epoch/week PK·epoch FK·정확 NUMERIC/CHECK를 그대로 사용한다. 저장 필드는 E 분자/분모·N, RESET/ADD·기여 분자/분모, CONFIRMED/PENDING, ruleVersion=1·주별 revision, input_revision·needs_recheck·confirmed_at/updated_at이다. PENDING은 E/N/operation/기여/confirmed_at을 NULL로 유지한다. 같은 주 재실행은 확정 행을 중복 생성하거나 재가산하지 않는다.

| 입력 | 결과 | 기여 |
|---|---|---:|
| [1], [1,0], [1/2,1/2] | RESET | 0/1 |
| [1,1] | ADD | 1/1 |
| [1,1/2] | ADD | 3/4 |
| [1,1,1,0] | ADD | 3/4 |
| [1,1,1,1/2,0] | ADD | 7/10 |
| 정상 검사 완료 N0/E0 | RESET | 0/1 |
| UNKNOWN/ACTIVE/검사 미완료 | PENDING | NULL |

E≤1을 먼저 검사한다. E>1인 경우에만 min(1,E/N)을 계산한다. ExactFraction의 BigInteger를 사용하고 표시 반올림을 재입력하지 않는다. 표시가1인 1.0000000000001도 정확 비교에서는 ADD다.

## 4. source revision과 G4-5 경계

기존 V25의 input_revision TEXT에 `{hash,revisions}` JSON을 저장한다. hash는 ruleVersion·owner/세대·ON 경계/시간대/설정version·관련 검사 경계·정렬된 후보/판정/날짜별 근거의 canonical SHA-256이다. revisions는 해당 월~일7개 날짜의 revision 전체이며 근거 부재는0이다. 최대 revision 하나만 저장하지 않는다. observedAt·단순 reviewedAt 갱신은 source 변화로 사용하지 않고, 이후 cursor는 해당 주 말일로 cap한다.

G3는 기존 owner 잠금 안에서 dirty 해제 **이전**에 영향 주의 needs_recheck를 세운다. 이 UPDATE와 G3 판정/dirty 해제/cursor 저장은 같은 트랜잭션이므로 둘 중 하나만 commit하지 않는다. 실패하면 원래 source dirty가 남고 다음 검사에서 재시도한다. 이미 저장한 E/N/operation/기여는 수정하지 않는다.

별도로 저장한 revision 벡터와 현재 일별 revision을 비교해, calendar가 없어 G3가 처리하지 않는 계정과 G3 이후의 변경도 탐지한다. 변경되거나 입력 메타데이터가 불명확한 확정 행은 dirty로 표시한다. 기존 원본 revision은 dirty=false 뒤에도 남으므로 변경 사실을 잃지 않는다. 마커 시각은 기존 updated_at보다 역행시키지 않는다.

이번에는 확정된 과거 dirty 주의 E/N을 재평가하거나 후속 주를 순차 재계산하지 않는다. G4-5가 가장 이른 영향 주부터 같은 epoch에서 새 주별 결과를 만들고 dirty를 해결해야 한다. OFF/재ON hard epoch를 넘지 않으며 G5 기간 정지/완료/취소·소속 필터도 후속이다.

## 5. 내부 누적과 실패 복구

현재 epoch의 권위 있는 주간 행을 시간순으로 fold한다. RESET은0, ADD는 정확한 기여를 더한다. currentTotal += DB 쓰기나 별도 캐시는 없다. 첫 gap/PENDING/dirty/다른 ruleVersion에서 멈추고 그 이전 prefix와 confirmedThrough/blockedAt을 반환한다. 이후 ADD를 확정 누적으로 건너뛰어 공개하지 않는다. 내부 accumulated도 source vector를 감사한 뒤 fold한다. 확정 행이 없는 초기0은 confirmedThrough=null로 구분한다.

owner 잠금 중 실패하면 최대4주 창 전체가 rollback된다. 기존 PENDING은 보존되고 신규 gap은 설정/기존 주간 행에서 재발견된다. DB가 허용하면 별도 짧은 owner 트랜잭션으로 그 첫 gap에 숫자 없는 PENDING 재시도 표식만 남긴다. 표식 저장도 실패하면 gap 자체가 사라지지 않아 복구 후 다시 발견된다. 테스트는 세 번째 주 실패로 앞 두 신규 확정까지 rollback한 뒤 원래 gap에서 재개하는 경우와 격리 Spring 서버 재시작을 검증한다.

G4-1 GET/PUT·expectedVersion·ON/OFF·epoch·시간대 정책은 변경하지 않았다. **confirmedWeeks는 계속null**이고 최종 범위/보류/재계산 상태의 HTTP 연결은 G4-6이다. 기존 NOT_IMPLEMENTED 문자열도 최종 조회 미연동 의미로 보존하며 내부 엔진 부재와 혼동하지 않는다.

## 6. 검증

전용 단위는04시 전후·서울/시카고/베를린 DST 경계·E 임계값/정확 분수·연속 fold/중간 gap·보류/dirty·입력 식별을 검증한다. Job 단위는 같은 tick에서 G3→G4 순서를 확인한다.

전용 Testcontainers는03:59:59/04:00·SQL/Java IANA 대응, 일정 없음·계획0, G3 검사/분수 저장, 원본 변경 중 stale 방지, 실제 owner SHARE 쓰기/OFF 경합, 중복 호출/다중 worker, 20-owner/4주 catch-up, OFF/재ON epoch, G3 dirty-clear 원자성과 rollback, CAS 실패, 중간 실패/격리 서버 재시작, 나중 확정 행보다 앞선 gap 보호를 검증한다. JDBC observer는 실제 DB 결과를 대체하지 않고 fault/latch만 주입한다. 동시 원본 쓰기는 기존 쓰기의 owner SHARE 경계를 사용한다. 실제 Workout API 회귀는 기존 전체 통합과 구분한다.

초기 전용 통합13건 중1건은 미래 시각 fixture의 endedAt<startedAt 제약 위반이었다. fixture의 실제 수행/종료 시각을 시작 이후로 고쳤다. 전체 단위 중8건은 DB 비사용 HTTP/context fixture가 새 서비스의 named transaction manager를 제공하지 못한 것으로, 기존 G3와 같은 방식으로 G4 서비스 mock을 추가했다. 제품 제약·보호 assertion을 완화하지 않았다. 이후 전체 단위400건과 전용 통합22건을 통과했다. 자체 검토에서 이전 주로 저장된 여행 날짜의 원본 삭제 시 시작 주가 이동하지 않도록 최소 저장 주를 보존하고 회귀를 추가했다.

보완 후 전용 통합23건과 전체 단위를 통과한 Java12개 파일의 SHA-256을 고정하고, `gradlew.bat test integrationTest assemble --offline --console=plain`을 다시 완료했다. 최종 실행의 test UP-TO-DATE는 바로 앞 검증의 동일 코드 전체 단위 결과를 재사용하며, 전체 격리 통합과 assemble은 최종 코드로 다시 실행했다.

| 최종 검증 | 결과 |
|---|---|
| 전체 단위 | 35 suite·400건, 실패/오류/skip 0 |
| 전체 격리 통합 | 38 suite·719건, 실패/오류/skip 0 |
| G4-4 전용 | Policy 단위15건·Job 순서 단위1건·격리 통합23건 포함 |
| G4-1 회귀 | DeloadAnalysis 단위15건·통합13건 포함 |
| G4-2/3 회귀 | InitialPlanCompletion16건·ExactFraction3건·G4-3 후보 단위26건/통합10건 포함 |
| G3/일반 통계/원본 | DailyTrainingReview 단위2건·PlanningCalculator10건·StatisticsIntegration24건·WorkoutSession 통합150건 포함 |
| assemble | 성공, 최종 전체 실행8분6초 |
| 원본 대조 | G3 판정 본문은 원자적 주간 표식 호출 외 동일. 운동/승인/동기화/API 원본 불변, 기존 migration25개 blob 동일 |
| 문서·공백 | 변경 문서5개 로컬 링크120개·fence 정상, diff/신규 추가 행 공백 검사 통과 |

기존 JVM class-sharing/unchecked/Gradle 안내 외 빌드 실패는 없다. PWA는 미변경이므로 npm 테스트/build/lint는 재실행하지 않았다. 검증한 격리 테스트 서버·worker는 종료했다.

## 7. 보존 및 종료

기존 검토 DB 컨테이너는 시작부터 중지 상태였다. 시작/초기화/복원/seed/V25 적용을 하지 않았다. 기존 V1~V25 blob과 PWA 추적 파일이 동일하며 새 migration은 없다. 개인/운영 DB에는 접근하지 않았다. 중지 DB의 스키마/행수/내용 지문은 이번에 직접 재확인하지 않았으며 마지막 G4-2의 V24 확인과 구분한다. 컨테이너ID/중지 상태·시각/영구volume/AutoRemove=false와 백업4개 SHA-256이 전후 동일하다. 기존 Backend60208/PWA63500 및 각각의 시작 시각(02:45:15/02:45:23)도 동일하며 기존 서버를 재시작하지 않았다. Git 원격 참조도 동일하다.

로그/환경 snapshot/검토 manifest/테스트 산출물은 기존 ignore 경로에 두고 관련 Backend 소스·테스트·문서만 로컬 커밋한다. push·main 병합·G4-5 이후 개발은 하지 않는다.
