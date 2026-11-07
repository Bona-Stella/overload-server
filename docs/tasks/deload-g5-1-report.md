# G5-1 디로딩 시작·최초 임시 계획 구현

2026-11-08. [API 계약](../api/deload-periods.md), [분석 조회 계약](../api/deload-analysis.md), [제품 v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md)이 기준이다.

## 1. 범위·시작 상태

양쪽 feature/weekly-schedule-daily-review-t02b·clean에서 시작했다. Backend HEAD=67b5ddb35f7f1281ee542d342d96472bb79789d4, PWA HEAD=91a64f55f206e759bb1d6f3022aa79217985616c. G4-1~6은 유지하고 G5-1만 구현한다. PWA/UI·G5-2 운동 실행/편집·G5-3 완료/취소·G6·실제 DB 적용·main 병합·push는 수행하지 않는다.

기간 시작 API가 생겼지만 종료 기능이 아직 없으므로 기존 사용 환경에 부분 배포하거나 연결하지 않는다. 기존 검토 DB는 중지 상태로 유지한다.

## 2. 구현 구조

| 책임 | 실제 파일/재사용 |
|---|---|
| HTTP 경계 | DeloadPeriodController/Requests, 기존 DeloadAnalysisErrors의 code-only/no-store·인증/CSRF 관례 |
| 트랜잭션 | DeloadPeriodService, 기존 workoutTransactionManager·owner FOR UPDATE |
| 출처/자격/저장 | DeloadPeriodRepository, 기존 RoutineService/RoutineRepository의 Detail·공유 잠금, 분석 설정 시간대·세대 |
| 순수 자동 편성 | DeloadPlan/DeloadPlanGenerator. SQL/외부 의존성 없이 최초 source→target 생성 |
| 기간 경계 공유 | DeloadPeriods. ACTIVE 확인·기간 겹침·G3 정지 날짜만 처리 |
| G3/G4 | 기존 DailyTrainingReviewService/후보/주간 저장·조회에 기간 경계 연결. 별도 scheduler/누적 캐시/중복 일별 저장소 없음 |
| 일반 쓰기 보호 | 루틴/일정 관리·세션 START·snapshot/FINISH·기록 정정/복구/삭제·승인 해결의 기존 잠금 내 guard |
| 통계 보호 | 실제 전체 수행 볼륨 유지, 일반 계획 후보/G3/G4에서 deload membership 제외 |

V27은 deload_periods와 deload_temporary_plans를 추가한다. owner/generation 복합 FK, ACTIVE partial unique, requestId unique, 방식/분할/시각/날짜/version 제약, 원본 출처·최초 임시 계획 UPDATE 불변 trigger가 있다. 권장 종료일=start+6, 최대=start+9이다. 최초 계획은 첫7일만 생성한다.

workout_sessions에는 nullable deload_period_id/deload_plan_id 쌍을 추가한다. 두 필드 동시 존재/부재·소속 복합 FK·UPDATE 불변성을 보장하며 기존 세션의 값은 모두 NULL이다. 실제 실행 API가 이 필드를 쓰는 기능은 아직 없다. 일반 routine 복제·승인 중량 복제 저장·기존 JSON/hash 재작성은 하지 않는다. V1~26은 바꾸지 않았다.

### 네 조건과 멱등성

최근7개 로컬 날짜(오늘 제외)의 실제 일반 수행, 이전 COMPLETED 종료 이후 새 일반 수행, 오늘 무수행, ACTIVE 일반 세션 부재를 각각 검사한다. 웨이트 일부 유효 세트도 실제 수행이고 유산소는 미삭제 COMPLETED+active_ms>0이면 된다. START-only/SKIPPED/삭제/명시적 디로딩 소속은 제외한다. 이전 완료와 같은 날짜라도 수행 시각이 완료 후면 2번은 만족할 수 있으며 오늘 수행 금지는 별도로 적용한다. 분석 OFF·누적0·계획0/1일을 추가 차단 조건으로 사용하지 않는다.

owner 잠금으로 정상 START·일정·원본/기록 쓰기와 기간 시작을 직렬화한다. 현재 세대·네 자격·ACTIVE 부재·미리보기 토큰·원본/분류를 재검증한다. 같은 requestId/같은 요청은 최초 결과를 반환하고 다른 내용은 충돌이다. 자식 계획 삽입 실패도 기간까지 전체 rollback한다. GET/preview는 설정/기간/계획을 쓰지 않는다.

### 원본과 자동 편성

불변 source에는 현재 일정 version, 요일별 루틴 version/순서/계획/승인 중량, 표준 운동의 PRIMARY와 jointType이 들어간다. 현재 캘린더 전환 baseline 이후의 실제 일정 계약을 사용하며 과거 예약 이력을 현재 편성으로 되살리지 않는다. sourceHash와 서버 token에는 오늘·자격 revision·정규화 날짜·규칙 version이 포함된다. 일반 루틴·assignments·승인·수행 기록을 수정하지 않는다.

KEEP은 원본 요일/종목, THREE/TWO는 PRIMARY 대표성/메인 순서·분할 또는 라운드로빈, 선정 후 COMPOUND→ISOLATION이다. 다중 PRIMARY를 모두 커버하며 웨이트 중복은 없고 빈 분할을 새 종목으로 채우지 않는다. PPL 순서는 원본 요일별 첫 메인 등장 순서다. 압축형 유산소는 최초 원본을 허용 슬롯에 최대1개씩 사용하며 Legs 없는 균등형도 지원한다. REST는 계획 없이 기간만 생성한다.

85% 중량·장비별 내림/소중량 보존·BODYWEIGHT 유지, 세트50%·홀수 반복 범위 보정, 유산소 시간50%/속도85%를 최초 생성에만 적용한다. 사후 편집 때 재감량하는 경로는 없다.

사용자에게 확인하고 확정한 세 가지 경계:

1. 홀수 세트의 min/max 각각 기존 총 반복×0.5÷새 세트 수를 HALF_UP, 최소1. 3×6~10→2×5~8, 5×10→3×8.
2. 압축 하루 종목 상한·평균 세트70%는 내림. 70%가1 미만인 경우만 최소1세트 예외.
3. PRIMARY/jointType 불충분 또는 PACE/거리/경사/저항 감량 미확정이면 해당 편성만 명시적 issues로 보류. 정상 데이터/REST는 계속 지원하고 원본을 추정·변경하지 않음.

카탈로그에는 분할 전용 부위 코드가 없으므로 기존 group_code와 명시적인 세부 PRIMARY 이름만 대응시킨다. 운동명/첫 운동의 이름으로 부위를 추정하지 않는다. 운영 카탈로그에 미분류가 남아 있으면 미리보기가 보류될 수 있으며 이는 성공으로 위장하지 않는다. 7일 원본의 비휴식 변환은 현행 표에 없으므로 REST만 제공한다.

### G3/G4 정지와 원본 보호

기간 날짜의 새 일반 MISS 생성을 건너뛰고 기존 최초 MISS/수행일/InitialPlan은 보존한다. cursor는 전진하며 정지 중 dirty는 보존한다. G4 ON 평가 구간과 기간 겹침은 DELOAD_PERIOD 사유로 PENDING이며 가짜 정상 N0 RESET/ADD를 만들지 않는다. EXCLUDED는 기존 누적 무변경 의미를 유지한다. GET은 마지막 신뢰 prefix와 PAUSED/blockedAt/추천false를 제공하고 더 이른 미해결이면 그 상태를 우선한다. OFF→ON도 기간을 종료하지 않는다.

ACTIVE 중 일반 관리/기록 쓰기는 DELOAD_ACTIVE_PERIOD로 차단한다. 기존 확정 START/snapshot/FINISH/승인 해결의 같은 요청 ACK는 보존한다. 인증·소유권·세대·expectedVersion·원본 hash·writerEpoch를 우회하지 않는다. 통계의 실제 운동량과 일반 계획 이행 후보를 분리하고 G3 PERFORMED를 완료율1로 바꾸지 않는다.

## 3. 검증

최종 gradlew.bat test integrationTest assemble --offline --console=plain 결과는 아래와 같다. 모든 DB 테스트는 무작위 비밀번호/포트의 임시 Testcontainers PostgreSQL만 사용한다.

| 검증 | 결과 |
|---|---|
| 전체 단위 | 38 suite·426건, 실패/오류/skip 0 |
| 전체 격리 통합 | 41 suite·777건, 실패/오류/skip 0 |
| G5-1 전용 | 편성 단위11·요청 경계2·일반 통계 분리 회귀1, 기간 API 격리21·V26→27 업그레이드1 포함 |
| assemble | 성공. 최종 전체 명령8분53초 |
| 문서/diff/최종 source hash | 변경 문서9개 내부 파일 링크·fence·JSON 예시12개·공백 검사 통과. 최종 Java/SQL291파일 해시 동일 |

첫 전체 실행은776건 중 가입 테스트의 테이블 목록 기대값1건이 실패했고 V27의 두 새 테이블을 반영했다. 새 snapshot 쓰기 guard와 격리 회귀도 보완한 뒤 코드/테스트를 다시 고정하여 전체 검증을 재실행했고, 위 최종 결과는 수정 후 실행이다. JVM CDS 및 Gradle configuration-cache 안내는 실패가 아니다.

테스트는 장비별 감량/소중량, 홀수 min/max, 상한 최소1, 큰 정수, KEEP/빈 상태, PPL 최초 메인 순서, FRONT/BACK·EQUAL, 다중 PRIMARY/중복 제거/관절 정렬, 유산소 감량/배치/불명확 보류·사용자 날짜를 검증한다. 실제 인증 API는 네 조건·유산소 활동·완료 이력 fixture·계획0/1·OFF·멱등성·원본 보존·출처/날짜 변경·rollback·기간 유일/FK/불변성·정상 START 및 일정 저장과 경합·계정/세대·CSRF·G3 정지·G4 prefix/정지·설정 변경 후 기간 유지·기록 쓰기 차단을 검증한다. 기존 G4-1~6/G3/일반 통계/운동·승인·인증·V25→26 제약도 전체 회귀로 유지한다.

중간 검증에서 원본 잠금 조회가 tag/createdAt을 생략해 미리보기와 다른 source hash가 되는 오류를 발견했다. 기존 공유 잠금은 재사용하고 실제 snapshot은 같은 Routine Detail 계약으로 읽도록 고쳤다. 테스트 fixture의 존재하지 않는 승인 테이블 참조, 선행 주 확정 전 후속 주 요청 기대값도 수정했다. V24/V26 역사 migration 테스트는 해당 버전으로 명시적으로 고정했고 별도 V26→27 전체 행/기존 checksum 보존 검증을 추가했다. 어떤 판정/보안 assertion도 실패를 숨기기 위해 제거하지 않았다.

## 4. 환경 보존

시작 시 기존 overload-home-review-b4972e78는 exited/AutoRemove=false였고 컨테이너/볼륨을 시작·삭제·재생성하지 않았다. 실제 DB에는 SQL·migration·seed·복원·정정을 실행하지 않는다. V24·루틴9/세션15/세트28/유산소1은 이전 확인 기록이며 이번 재조회 결과가 아니다.

전후 증거는 Git 제외 PWA logs/g5-1-environment-before.json/after.json에 저장한다. 보존 컨테이너 ID/상태/볼륨·백업4개 SHA-256·기존 서버 PID/시작 시각·PWA 추적 파일/HEAD·양쪽 원격 참조·기존26개 migration의 동일성은 최종 비교 후 확정한다. 테스트용 Spring/worker/Testcontainers만 생성·정리하며 기존 Backend/PWA 서버는 재시작하지 않는다. 로그·덤프·환경 증거·빌드 산출물은 커밋하지 않는다.

## 5. G5-2/3에 남는 책임

- G5-2: 별도 임시 계획을 실제 세션 source_routine_id/InitialPlan/소속·승인 차단·snapshot/ACK와 연결. 임시 계획 편집/미수행 날짜 이동·맞교환·삭제와 완료 운동 불변성. 최초7일을 넘긴 신규 자동 생성 금지, 8~10일 이동의 저장 제약 확장.
- G5-3: 완료·조기 완료·취소·10일차 자동 종료, 실제 수행 존재 분기, 정지 날짜 최종 정책/재개·완료 새 epoch/취소 누적, 막힌 G3 dirty/G4 주간 결과의 최종 처리.
- 운영 적용: 카탈로그 분류 품질, 가입/관리 쓰기 경계·T_adopt 및 V25~27 이행·백업 복원 검증·실제 적용 승인은 별도다. 현재 카탈로그 쓰기 API가 없으므로 운영 외부 분류 변경은 기동/관리 작업을 통제하는 배포 절차가 필요하다.
- G6: PWA 연동/UI·iPhone 검토. 이번에는 PWA 파일을 수정하지 않는다.

G5-1 범위 검증 후 관련 Backend 파일만 로컬 커밋한다. 커밋 해시와 양쪽 Git 최종 상태는 완료 응답으로 제공한다. G5-2 이후 작업은 시작하지 않는다.
