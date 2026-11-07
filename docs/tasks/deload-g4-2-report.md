# G4-2 공통 InitialPlan 운동 완료 판정 추출

2026-11-07, Asia/Seoul. 기준은 [디로딩 v1.7 DL-08](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md#22-하루-유효-운동일과-후보), [준비 보고서5절](../../../overload-pwa/docs/tasks/deload-v17-backend-readiness-analysis.md#5-g4-개발-범위의존성검증-계획), [G4-1 구현 기록](deload-g4-1-report.md), [통계 v1.4 ST-17~23](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.4.md)이다.

시작은 양쪽 `feature/weekly-schedule-daily-review-t02b`의 clean 상태였다. Backend HEAD `75778d0ccb3f64008513ec2b1cd1b7bba2552e3f`, PWA HEAD `91a64f55f206e759bb1d6f3022aa79217985616c`를 확인했다. **G4-2 단일 세션 판정과 정확분수만 공유하며 G4-3 이후 기능은 구현하지 않는다.**

## 1. 최소 추출 구조

| 파일 | 실제 책임 |
|---|---|
| [InitialPlanCompletion](../../src/main/java/com/overload/overload_server/completion/InitialPlanCompletion.java) | PlanningCalculator의 private judge/Candidate에서 단일 세션 판정·완료/미확인/계획 종목 수·하한/상한을 추출한 순수 함수 |
| [ExactFraction](../../src/main/java/com/overload/overload_server/completion/ExactFraction.java) | 기존 Fraction의 BigInteger 덧셈/비교·표시를 공유. 기약 분수와 양의 분모를 보장 |
| [PlanningCalculator](../../src/main/java/com/overload/overload_server/statistics/PlanningCalculator.java) | 공유 판정을 호출하는 기존 일반 통계 소비자. 예정 루틴 필터·날짜별 최고 단일값·계획일 분모·UNKNOWN 상한 비교는 그대로 |

private judge/Fraction를 복사해서 병행 운영하지 않는다. 기존 구현은 제거하고 공통 구현 하나를 호출한다. 조회 Repository/SQL·계획/세션 저장 모델·통계 응답 DTO에는 변화가 없다. 원본을 더 조회하거나 재저장하지 않으며 같은 계획 종목 수에 선형인 단일 세션 판정이다. 유산소 사실은 기존 인덱스에서 필요한 측정값만 공통 입력으로 전달한다.

## 2. 단일 세션 입력·출력 계약

`InitialPlanCompletion.judge(sessionId, initialPlan, performedSetCounts, completedCardioEvidence)`를 사용한다. DB·Spring·일정·날짜·분석 설정을 참조하지 않는다.

| 입력 | 의미·소비자 책임 |
|---|---|
| sessionId / InitialPlan | 해당 세션의 불변 최초 계획. AppliedPlan·현재 루틴·승인 평가 결과를 대신 넣지 않음 |
| Map<sessionExerciseId, 세트 수> | 같은 세션의 유효 미삭제 WEIGHT 수행 세트 수. 현재 StatisticsRepository/PlanningCalculator가 제공하는 스코프/카운트를 재사용 |
| Map<sessionActivityId, CardioEvidence> | 같은 세션의 미삭제 COMPLETED 결과. 활동 시간(ms)·거리(km)·실제 속도(km/h)만 사용. 소유권/세대/삭제/상태 필터는 조회 소비자가 담당 |

출력 Result는 session·completed·unknown·planned와 lower/upper·hasPlan/isExact다. 이 결과는 “실제 수행 존재”나 G3 상태·승인 적격성 결과가 아니다. 부분 수행이 있어도 completed는0일 수 있다. G4-3의 일반 세션/ON 구간·실제 활동 후보 조건은 후속 소비자가 적용해야 한다.

- 웨이트는 각 InitialPlan 종목의 세트를 모두 수행해야 완료1이다.4세트 중3세트는0이고 추가 종목은 분자/분모에 들어가지 않는다. 수행 원본은 변경하지 않는다.
- 유산소는 기존 시간→거리→SPEED 또는 PACE 비교를 유지한다. 실제 속도×PACE가60 이상인지 확인하는 기존 규칙도 같다. 경사/저항 목표의 실제 증거가 없으면 UNKNOWN이다. 새 거리·시간·속도 기준은 만들지 않는다.
- 완료 결과가 아예 없으면 해당 유산소는 미완료0이다. 결과가 있으나 필요한 측정값이 없으면 UNKNOWN이다. 공통 입력의 활동 시간이null인 경우도 UNKNOWN으로 보존할 수 있으며 기존 정상 DB 경로는 필수 activeMs를 제공한다. 알려진 목표 미달이 있으면 다른 측정이 미확인이라도 완료 불가0인 기존 논리를 유지한다.
- lower=completed/planned, upper=(completed+unknown)/planned다. 미확인 종목을 임의로 완료/실패로 바꾸지 않는다. 다른 세션의 종목을 합치거나 여기서 최고 세션을 고르지 않는다.
- 계획0은 hasPlan/isExact가false이며0/0=1을 만들지 않는다. bounds의0/1을 정상적인 완료 판정으로 쓰지 않는다. 기존 일반 소비자는 planned>0 필터를 그대로 유지한다.
- null 계획/목록, 중복·누락된 종목 식별자, 비양수 계획 세트/시간, 잘못된 수치/강도 모드·음수 실제 근거는 IllegalArgumentException으로 거절한다. 손상 입력을 성공이나 확정 실패 실적으로 저장하지 않는다. 정상 DB/API 계약의 허용값 범위는 변경하지 않는다.

## 3. 정확한 분수와 일반 통계 보존

ExactFraction은 BigInteger 분자/분모로 비교·덧셈하며 생성 시 기약형으로 정규화한다.0/음수 분모를 거부한다. decimal의12자리 HALF_UP·percent의6자리/상한100은 기존 표시 형식 그대로다. 표시 문자열을 다시 계산에 넣지 않는다.1.0000000000001이 표시에서는1이어도 정확 비교는1보다 크다.

일반 ST-17~23의 예정 루틴 제한·현재 보존 일정 근거·계획일 분모·최고 단일 후보와 모든 후보의 상한 비교를 PlanningCalculator에 유지했다. 공통 Result가 완전1이면 다른 UNKNOWN 후보의 상한1 때문에 날짜를 보류하지 않는 기존 동작을 검증한다. UNKNOWN이 최고값에 영향을 주면 UNVERIFIABLE을 유지한다.

[WorkoutAssessment](../../src/main/java/com/overload/overload_server/workoutsession/WorkoutAssessment.java)는 승인 중량 비교/적격성/조언을 위한 다른 계약이다. 이번에 바꾸지 않는다. [G3 판정](../../src/main/java/com/overload/overload_server/statistics/DailyTrainingReviewService.java), START/FINISH·snapshot/ACK/writerEpoch·정정/복구·G4-1 API/epoch도 수정하지 않는다.

## 4. 검증 범위

- 공통 단위 테스트: 전체/부분 웨이트·추가 종목 제외·최초 계획 불변·유산소 시간/거리/속도/PACE·측정 부족/경사/저항 UNKNOWN·혼합 계획·계획0/손상 입력·별도 반쪽 세션·정확 분수/표시 반올림 경계.
- 기존 PlanningCalculatorTests를 유지하고 최고 단일값과 UNKNOWN 상한의 관계에 대한 회귀를 추가했다.
- 기존 StatisticsIntegrationTests의 임시 DB와 실제 START/FINISH API fixture를 재사용했다. 같은 예정일의1/2·3/4에서 기존 최고 단일0.75, 일반 계획일2/이행률37.5%를 확인한다. 예정 외 루틴/휴식일의 완전 수행은 일반 계획값을 채우지 않는다. 조회/공통 판정 전후 세션 JSON/hash·writer/version·실제 세트 전체가 동일한지 확인한다.
- 기존 G3·G4-1·승인/동기화/보안/원본 보호를 포함한 전체 Backend 단위·격리 통합과 assemble을 검증했다. 새 migration은 없다. 실제 검토/개인/운영 DB에 테스트를 실행하지 않았다.

실행 결과(2026-11-07):

| 검증 | 결과 |
|---|---|
| `gradlew.bat test integrationTest assemble --offline --console=plain` | 성공. 최종 실행은 현재 코드의 전체 단위 결과를 UP-TO-DATE로 재사용하고 전체 격리 통합과 assemble을 실행 |
| 전체 단위 | 32개 suite, 358건, 실패/오류/skip 0 |
| 전체 격리 통합 | 36개 suite, 685건, 실패/오류/skip 0. 임시 Testcontainers PostgreSQL만 사용 |
| 공통 판정/분수·일반 계획 단위 | InitialPlanCompletion 16건, ExactFraction 3건, PlanningCalculator 10건 포함 |
| 기존 G3·G4-1 회귀 | DailyTrainingReview 단위2건 및 Statistics/WorkoutSession 통합에 포함된 G3 검증 성공. DeloadAnalysis 단위15건/통합13건 성공 |
| 일반 통계·원본 보존 통합 | StatisticsIntegration 23건 성공. 신규 최고 단일값/예정 외 제외/원본 무변경 검증 포함 |
| 소스 대조 | 공통 타입·호출 치환을 제외한 calculate 흐름과 일정 helper/detail 코드는 기준 HEAD와 동일. 기존 migration 25개 blob 동일 |
| 문서 검사 | 변경 문서4개 코드 fence 및 로컬 링크108개 정상. 공백 검사 통과 |

표시 반올림은 기존 12자리/6자리 형식을 유지하며 새 빌드 오류나 테스트 실패는 없었다. 검증 로그와 환경 대조 JSON은 기존 ignore 대상인 build/logs 아래에만 두고 커밋에서 제외한다.

## 5. 보존 및 종료 경계

PWA 소스/UI/문서는 그대로 뒀다. V1~V25 migration을 수정·추가하지 않았고 기존 검토 DB는V24를 유지했다. `overload-home-review-b4972e78`의 동일 컨테이너ID/볼륨·자동삭제 비활성 상태와 도메인10개 테이블의 건수/내용 지문·기존dump4개 SHA-256이 전후 동일하다. 루틴9개·세션15개·웨이트 수행28개·유산소 결과1개를 보존했다. Backend PID60208/PWA PID63500과 시작 시각도 동일하며 재시작하지 않았다. 테스트 worker는 종료됐다. 개인/운영 DB에는 접속하지 않았고 V25/T_adopt 이행을 실행하지 않았다. 원격 참조도 동일하다.

후속 G4-3는 이 공통 입력/출력을 사용해 일반 세션/ON 구간·예정 외/휴식일의 실제 후보·날짜 집합·G3 확정MISS·E/N을 구현해야 한다. 이번에는 후보 탐색·하루 선택·주간 RESET/ADD·04시 확정·dirty/revision 재계산·G5/G6를 구현하지 않는다. 검증을 통과한 관련 Backend 파일만 로컬 커밋하고 push하지 않는다.
