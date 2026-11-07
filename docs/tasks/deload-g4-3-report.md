# G4-3 독립 운동 후보·주간 E/N 계산

2026-11-07, Asia/Seoul. 기준: [디로딩 v1.7 DL-05~14](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md), [준비 보고서5절](../../../overload-pwa/docs/tasks/deload-v17-backend-readiness-analysis.md#5-g4-개발-범위의존성검증-계획), [G4-1](deload-g4-1-report.md)·[API](../api/deload-analysis.md), [G4-2](deload-g4-2-report.md), [통계 v1.4](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.4.md).

시작 HEAD는 Backend `86669456a7211121e17365e08a8582b86d31ab5e`, PWA `91a64f55f206e759bb1d6f3022aa79217985616c`이며 양쪽 `feature/weekly-schedule-daily-review-t02b`·clean을 확인했다. **이번 범위는 내부 읽기·후보·E/N 계산이다. 외부 누적 응답·04시 확정·RESET/ADD·dirty 소비/후속 주 재계산은 구현하지 않는다.**

## 1. 구조와 기존 조회 재사용

| 모듈 | 변경/재사용 |
|---|---|
| [StatisticsRepository](../../src/main/java/com/overload/overload_server/statistics/StatisticsRepository.java) | 기존 세트/COMPLETED 유산소 SQL을 private 메서드로 이동해 일반 통계와 공유. `completionFacts`는 동일 owner/세대/미삭제/수행일 범위에 ON startedAt 경계를 추가하고 세션 메타데이터를 읽음 |
| [DeloadAnalysisRepository](../../src/main/java/com/overload/overload_server/deload/DeloadAnalysisRepository.java) | G4-1 설정 이력의 특정 epoch 행 조회만 추가. 기존 GET/PUT·시간대 확정·전이 쓰기는 불변 |
| [DeloadCandidateRepository](../../src/main/java/com/overload/overload_server/deload/DeloadCandidateRepository.java) | 현재 G3 state/dirty/revision/reviewedAt·최초 MISS 출처와 최초 일정 적용일/부재를 읽음. 새 일별 저장소나 일정 추론 없음 |
| [DeloadWeeklyCandidates](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyCandidates.java) | 내부 `calculate(owner,generation,epoch,monday)` 진입점. 현재 세대 확인 후 read-only REPEATABLE_READ snapshot에서 설정/사실/G3/control 조회 |
| [DeloadCandidateCalculator](../../src/main/java/com/overload/overload_server/deload/DeloadCandidateCalculator.java) | 순수 날짜별 후보·최고 단일 하한/상한·정확 주간 E/N. G4-2 `InitialPlanCompletion`/`ExactFraction` 호출 |

일반 통계의 `read`는 기존 종료 세션·세트/유산소·근육 매핑·최근 기록 계약을 유지한다. G4는 근육 매핑·최근3건·루틴 계획 이력·일반 PlannedDay를 조회하지 않는다. 사실3회와 설정/owner/G3/control/일정 부재의 고정 횟수 조회이며 날짜별 추가 쿼리는 없다. 한 호출은 월~일7일 범위다. 내부 진입점의 owner는 후속 서버 작업이 전달하며 외부 userId 입력 API를 만들지 않았다.

## 2. 적격성·epoch·시간대

- 세션 SQL은 owner/current generation·미삭제·저장된 workoutDate·원래 startedAt의 `[epochStartedAt, DISABLED.recordedAt)`을 사용한다. 종료 경계는 배타적이다. 최초 ON·ADOPTED_INITIAL·재ON의 epoch를 혼합하지 않는다. 시간대 최초 확정 version은 같은 epoch를 유지한다.
- FINISHED/AUTO_CLOSED 중 유효 WEIGHT 실제 세트1개 이상 또는 유효 COMPLETED 유산소 activeMs>0인 세션만 실제 후보다. 실제1세트/목표 미달도 남고 완료율0이 가능하다. START만 종료·삭제 사실만 있는 기록·SKIPPED는 N을 만들지 않는다.
- ACTIVE는 완료 후보와 분리해 보류 근거로 반환한다. 운동 만료/종료를 유발하지 않는다.
- 현재 디로딩 소속 열은 없다. 현행 일반 세션만 다루며 G5에서 명시적 일반/디로딩 소속과 정지 구간 필터를 연결해야 한다.
- 주 경계는 고정 분석 시간대의 월~일이다. 주중 활성화로 시작 요일을 바꾸지 않는다. 실제 세션의 workoutDate를 분석 시간대나 업로드일로 다시 계산하지 않는다. 다른 수행 시간대 때문에 ON 기준일 직전 날짜로 저장된 적격 세션도 해당 저장 날짜에서 평가한다.
- MISS/일별 검사 대상 날짜는 고정 시간대에서 ON 구간과 겹치는 날짜다. 활성화/이행 날짜 이전 MISS와 epoch 시작보다 먼저 확정된 최초 MISS를 가산하지 않는다. 시작/끝 시각과 날짜별 범위를 함께 반환해 후속 확정 단계가 경계를 확인할 수 있다.

## 3. 최고 단일 판정과 E/N

불변 InitialPlan의 종목 판정은 G4-2를 재사용한다. 현재 루틴/AppliedPlan/승인 평가로 분모를 바꾸지 않는다. 같은 날 여러 세션의 완료 종목을 합치지 않고, lower 최대의 단일 세션과 모든 후보 upper 최대를 비교한다. 동일 하한은 UUID로 일관되게 선택한다.

UNKNOWN이 최고값을 바꿀 수 있으면 보류한다. 알려진 완전1이 있으면 다른 정상 UNKNOWN의 upper≤1이 값을 바꾸지 못하므로 계산할 수 있다. 손상 JSON/계획0은 별도 INVALID_PLAN이며 성공이나 확정0으로 바꾸지 않는다.

현재 **MISSED + !dirty + reviewedAt/firstMissedAt 존재**만 확정 MISS 후보로 사용한다. 실제와 MISS가 겹쳐도 날짜당 N1이고 실제 최고 단일값을 쓴다. MISS만 있으면0이다. PERFORMED를1로 변환하지 않으며 최초 START/MISS·현재 G3 결과를 수정하지 않는다.

| 날짜별 값 | E(정확 분수) | N | E/N(계산 비율) |
|---|---:|---:|---:|
| [1] | 1/1 | 1 | 1/1 |
| [1,1] | 2/1 | 2 | 1/1 |
| [1,1/2] | 3/2 | 2 | 3/4 |
| [1,1,1,0] | 3/1 | 4 | 3/4 |
| [1/2,1/2] | 1/1 | 2 | 1/2 |
| [1,1,1,1/2,0] | 7/2 | 5 | 7/10 |
| [1,1,1,1,0] | 4/1 | 5 | 4/5 |

`ExactFraction`의 BigInteger 연산을 사용하고 표시 반올림을 입력하지 않는다. `rate()`는 E/N이지 ADD가 아니다. E≤1의 RESET 판정은 G4-4 책임이며 1/1을 이 단계에서1주 가산하지 않는다. N0은 나누지 않고 rate=null로 둔다.

## 4. 계산 가능·보류의 출력 계약

Week는 owner/세대, epoch/설정version/ON 시각 범위/시간대, 주 범위·평가 날짜 범위·observedAt, 날짜별 후보/선택 세션, G3 근거/개별revision·최초 일정 적용일·calendar 검사 경계, 정확 lowerE/upperE, 관측한 집합 크기n, 필요한 검사 완료 여부와 보류 사유를 반환한다. 후보에는 session version/localRevision/snapshotHash를 포함한다.

`e`/`rate()`는 계산 입력이 갖춰진 경우만 사용 가능하며 보류 시 null이다. lowerE/upperE/n은 해당 snapshot에서 관측한 범위이지 보류 주의 확정값이 아니다. `calculable`도 주간 publication 허가나 확정 완료를 뜻하지 않는다.

- 시간대 미확정/잘못된 시간대, ON 구간 밖, 아직 끝나지 않은 날짜, ACTIVE, 손상 계획, 최고값에 영향을 주는 UNKNOWN을 정상0과 구분한다.
- calendar_control이 있으면 reviewFrom/reviewedThrough와 해당 날짜의 dirty/UNKNOWN/PENDING_ACTIVE/reviewedAt을 함께 확인한다. cursor만으로 완료 처리하지 않는다. 일정 이력이 있는데 control이 없거나 고정 시간대가 충돌해도 보류한다.
- **일정 이력 부재가 확인된 계정 또는 최초 일정 적용일 이전 날짜**는 기존 실제/ACTIVE/source revision을 평가한다. G3의 예정 MISS 검사를 요구하거나 가짜 일정/control을 만들지 않는다. START/사실 변경으로 생긴 UNKNOWN/dirty 행은 revision 근거로 보존하며 일정 미등록만으로 영구 보류하지 않는다. 최초 일정 적용일 이후면서 G3 전환 경계 이전인 불명확한 날짜는 보류하며 소급 MISS를 생성하지 않는다.
- 검사 미완료 N0은 e=null로 보류하며 검사 완료된 정상 N0/E0과 구분한다. 미확정/보류 주를 RESET으로 저장하지 않는다.

## 5. 검증

전용 단위는 실제 없음/부분0, COMPLETED 유산소 미달/UNKNOWN, 예정 외/휴식일/계획0, 최고 단일/반쪽 합산 금지, MISS 합집합/dirty/PERFORMED, OFF/이행 전/epoch/주중ON/저장 날짜, 시간대/검사/ACTIVE/손상 계획, 분수 예7개와 세 개의1/3 합계를 검증한다.

전용 Testcontainers는 실제 V25 제약 아래 SQL fixture를 만들고 scope·논리 삭제·세대·epoch 구간·G3 현재 근거·반복 조회 원본 불변·정정 후 revision·일정 없는2일 E2/N2를 확인한다. 기존 StatisticsIntegration의 실제 START/FINISH API fixture도 재사용한다. 예정 루틴 대신 다른 루틴을 수행한 날짜와 휴식일 수행은 **일반 계획값0을 유지하면서 G4 lowerE2/N2**가 되는지 검증한다. 이 비교 fixture는 G3 미검사이므로 e=null이다. GET confirmedWeeks도 null을 유지한다.

초기에는 테스트에서 package-private InitialPlan.json()을 호출한 컴파일 실패가 있어 기존 JsonMapper로 fixture를 직렬화했다. 전용 통합 첫 실행은8건 중1건이 fixture 생성 순서에 따른 단일 ACTIVE 제약 위반이었다. 종료 fixture를 먼저 생성해 수정했으며 제품 제약/판정을 약화하지 않았다. 이후 전용 검증과 일반 통계 비교는 성공했다. 자체 검토에서 같은 시각 ON/OFF의 빈 구간과 주중 최초 일정 등록 전의 확인된 무일정 날짜를 보완하고 회귀 테스트를 추가했다.

최종 검증은 보완 이후 Java8개 파일의 SHA-256을 고정한 상태에서 `gradlew.bat test integrationTest assemble --offline --console=plain`로 완료했다. 최종 실행의 test UP-TO-DATE는 바로 앞 경계 검증에서 실행한 현재 코드의 전체 단위 결과를 재사용한다. 전체 격리 통합은 최종 코드로 다시 실행했다.

| 검증 | 결과 |
|---|---|
| 전체 단위 | 33 suite·384건, 실패/오류/skip 0 |
| 전체 격리 통합 | 37 suite·696건, 실패/오류/skip 0 |
| G4-3 전용 | 단위26건·격리 통합10건 포함. 계획0일에 실제2일 E2/N2, 부분 수행의 G3 PERFORMED와 완료값0을 실제 G3 reviewBatch로 검증 |
| 기존 회귀 | PlanningCalculator 10건, StatisticsIntegration 24건, DailyTrainingReview 단위2건, WorkoutSession 통합150건 포함 |
| G4-1 | DeloadAnalysis 단위15건·통합13건 포함. 기존 GET/PUT·epoch·confirmedWeeks=null 유지 |
| assemble | 성공, 최종 전체 실행7분55초 |
| 조회/원본 대조 | 공유 세트/유산소 SQL 내용 동일, PlanningCalculator/G3/운동 쓰기·승인·동기화 원본 불변. 기존 migration25개 blob 동일 |
| 문서·공백 | 변경 문서5개 local link115개·fence 정상, 새 파일/추가 행과 diff 공백 검사 통과 |

기존 unchecked/class-sharing/Gradle 안내 외 새 빌드 실패는 없다. 로그/환경 JSON/검토 manifest는 기존 ignore 대상 build/logs에 두고 커밋하지 않는다. PWA 코드가 그대로이므로 npm 검증은 재실행하지 않았다.

## 6. 보존 환경과 잔여 책임

이번 시작 시 기존 검토 컨테이너 `overload-home-review-b4972e78`는 이미 exited였다. 동일 ID/영구 volume/AutoRemove=false·중지 상태/시각과 기존 dump4개 SHA-256이 전후 동일하다. 중지 상태여서 이번에 SQL로 V24/건수/내용을 재조회하지 않았다. 마지막 G4-2 검증에서 V24·루틴9/세션15/세트28/유산소1을 확인했고 이번 migration/seed/복원/기존 DB 쓰기는 실행하지 않았다. 기존 Backend60208/PWA63500의 PID·시작 시각이 동일하며 재시작·중지하지 않았다. 기존 Backend classpath에 devtools가 없고 테스트 worker는 종료됐다. 개인/운영 DB에는 접속하지 않았다. 원격 참조도 전후 동일하다.

G4-4에는 분석 대상 계정의 독립 탐색, 기존 G3 실행 순서와 연결, 다음 월요일04시 확정 가능 여부·owner 잠금/트랜잭션/CAS, 주간 평가 writer, E 임계값 RESET/ADD, 입력 revision 식별값·ruleVersion을 남긴다. G4-5에는 dirty 해제와의 원자적 연결 및 가장 이른 영향 주부터 같은 epoch 후속 주 재계산을, G4-6에는 마지막 확정 누적/보류 상태의 실제 API 응답을 남긴다. G5 소속/정지/완료/취소와 G6 UI는 미구현이다.

G4-1 API/일반 통계 응답, PlanningCalculator/G3/승인/START/FINISH/snapshot/ACK/writerEpoch/정정·복구와 PWA는 변경하지 않는다. V1~V25는 추가·수정하지 않고 새 migration/라이브러리/범용 event/중복 일별 저장을 도입하지 않는다. 관련 Backend 파일만 로컬 커밋하고 push하지 않는다.
