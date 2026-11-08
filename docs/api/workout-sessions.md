# 운동 세션 API — Task 009 / 표준 운동 V17

2E의 선택 bodyweightLoad와 V22는 [맨몸 추가 부하 계약](bodyweight-additional-load.md)을 따른다. 기존 weight/승인 평가·구9필드 JSON/hash·고정 요청을 보존하며, 생략과 명시적 제거를 구분한다. 개인 DB V18~V22는 미적용이다.

현행 유산소 실제 속도·거리 보완은 [2D 측정 계약](cardio-actual-measurements.md)을 따른다. 선택 measurement와 V21은 기존5필드 JSON·고정 요청/hash를 보존하며, 아래 과거 본문보다 이 확장 설명이 우선한다. 개인 DB는 승인된 V17 상태이고 V18~V21은 미적용이다.

상태: START·조회·takeover·snapshot·승인 요청/해결, FINISH·최초 평가 봉인·24시간 AUTO_CLOSED, 종료 후 수행 정정·삭제·명시 복구·현재 평가·승인 해결 구현. V12~V15 격리 검증 후 2026-11-01 사용자 명시 승인으로 개인 개발 DB도 V1~V15 신규 구성을 완료했다. [실제 재구성 보고서](../tasks/task-009-dev-v15-rebuild-report.md)를 따르며 다른 DB·후속 파괴적 작업에는 별도 승인이 필요하다.
기준: [현행 설계와 후속 승인 보완](../tasks/task-009-redesign-v1.0.md), [루틴 계약](routine-management.md), 기존 인증·CSRF·Task 008 영속성 경계.

V17 Backend는 [표준 운동 설계](../tasks/standard-exercise-catalog-design-analysis.md)의 단일 UUID·장비 계약을 구현한다. 아래 문서의 운동 계획/평가 schemaVersion은2다. Frontend/IndexedDB3·local schema2 전환과 당시 V17 개발 DB 연동은 [4단계 보고서](../tasks/standard-exercise-catalog-dev-phase-4-report.md)에 기록돼 있다. 6A에서는 개인 DB를 조회하지 않았으며 최신 조합의 종합 검증은6B에 남아 있다.

## 현재 제공 범위

V12는 `workout_sessions`를 추가한다. InitialPlan은 `initial_plan_snapshot` JSONB 하나가 유일한 원본이다.
V13은 현재 AppliedPlan과 수행·승인·정정 사실을 추가한다. V14는 명시 종료 시각·서버 종료 시각·최초 평가만 추가한다. 예정 세트 수만큼 수행 행이나 객체를 생성하지 않는다.
V15는 기존 테이블의 논리 삭제·복구 이력·승인 문맥만 확장한다. Frontend/IndexedDB의 종료·사후 정정·선택 복구 연결은 [Frontend 5D 보고서](../../../overload-pwa/docs/tasks/task-009-frontend-5d-report.md)를 따른다. 종료된 세션의 ACTIVE 쓰기나 자동 재개는 제공하지 않는다.

## HTTP·인증

기존 SessionPrincipal의 userId, 세션 쿠키, 쓰기의 X-CSRF-TOKEN을 사용한다. userId를 입력받지 않는다.
별도 editorProof·기기 비밀 토큰·기기 인증 테이블은 없다. editorDeviceId는 인증 수단이 아닌 브라우저 인스턴스 UUID다.
종료 목록의 명시된 페이지 query 외 query는 지원하지 않으며 GET body도 거절한다. 쓰기의 Content-Type은 application/json이다.
성공·명시적 오류는 no-store, 기존 X-Request-ID를 유지한다. Entity를 반환하지 않는다.
UUID는 완전한 하이픈 표기(대소문자 허용), Counter는 0..9223372036854775807의 정규 십진 문자열이다.
JSON 중복 키·추가 필드·필수 필드 누락·명시적으로 허용하지 않은 null·잘못된 타입·후행 JSON을 거부한다.

| 메서드 | 경로 | 성공 |
|---|---|---|
| POST | /api/v1/workout-sessions | 신규201+Location, 동일 시작 재확인200 |
| GET | /api/v1/workout-sessions/active | 200, session은 상세 또는null |
| GET | /api/v1/workout-sessions/closed | 200, 본인 현재 세대 종료 메타의 제한 페이지 |
| GET | /api/v1/workout-sessions/{id} | 200, 본인 현재 세대 상세 |
| POST | /api/v1/workout-sessions/{id}/takeover | 200, 새 epoch 또는 동일 인수 확인 |
| PUT | /api/v1/workout-sessions/{id}/snapshot | 200, 전체 확정 상태 저장 또는 중복 ACK |
| POST | /api/v1/workout-sessions/{id}/approval-resolutions | 200, 미해결 승인 명시 해결 또는 결과 재확인 |
| POST | /api/v1/workout-sessions/{id}/finish | 200, 최종 snapshot·최초 평가·종료 또는 동일 종료 재확인 |
| PATCH | /api/v1/workout-sessions/{id}/records | 200, 종료 후 선택 수행 정정·명시 삭제 |
| POST | /api/v1/workout-sessions/{id}/recoveries | 200, 종료 후 선택한 누락 완료 수행 복구 |
| DELETE | /api/v1/workout-sessions/{id} | 200, 종료 세션 전체 논리 삭제 |
| POST | /api/v1/workout-sessions/{id}/closed-approval-resolutions | 200, 종료 후 PENDING 해결 |
| GET | /api/v1/workout-sessions/{id}/assessment | 200, 최초 평가·현재 평가 구분 |
| GET | /api/v1/workout-sessions/{id}/record-change-events | 200, 본인 세션 변경 이력 |

START의 모든 필드:

```text
{id:UUID, dataGeneration:Counter, routineId:UUID, editorDeviceId:UUID,
 startedAt:Instant, workoutDate:LocalDate, startedTimeZone:IANA}
```

startedAt은 실제 사용자 시작 시각이다. UTC `YYYY-MM-DDTHH:mm:ss[.SSS]Z`, 소수초 최대3자리, 연도0001..9999다.
workoutDate는 실제 유효한 YYYY-MM-DD이며 시작 시각을 해당 IANA 시간대로 변환한 날짜와 일치해야 한다.
잘못된 JSON/날짜 형식은400, 시간대·현지 날짜 문맥 불일치는422 CLOCK_CONTEXT_INVALID다.
서버 업로드 시각으로 덮어쓰거나 서버와의 임의 허용 편차를 도입하지 않는다.

takeover의 모든 필드:

```text
{dataGeneration:Counter, editorDeviceId:UUID, expectedWriterEpoch:Counter}
```

현재 epoch가 기대값이면 이전 기기 승인 없이 writerEpoch/version을 각각1 올리고 새 기기를 지정하며 localRevision=0으로 한다.
현재 epoch가 기대값+1이고 대상 기기가 같다면 직전 인수 응답 유실의 재확인으로200, 추가 변경 없음이다.
다른 인수가 끼어든 오래된 요청은409 EDITOR_OWNERSHIP_LOST다. Counter overflow도409이며 전체 롤백한다.
takeover는 lastWorkoutSyncedAt을 갱신하지 않는다. 별도 heartbeat·주기 송신은 없다.

## 응답·불변 계획

상세:

```text
{userId,dataGeneration,id,status:"ACTIVE"|"FINISHED"|"AUTO_CLOSED",version,startedAt,workoutDate,startedTimeZone,
 editorDeviceId,writerEpoch,localRevision,lastWorkoutSyncedAt,initialPlan,lastSnapshotSavedAt,snapshot,
 endedAt:Instant|null,closedAt:Instant|null,completionAssessment:CompletionAssessment|null,deletedAt:Instant|null}
initialPlan = {schemaVersion:2,routineId,routineName,routineVersion,
 weightExercises:[{sessionExerciseId,sourcePlacementId,standardExerciseId,exerciseName,
   manufacturerId:UUID|null,loadType:LoadType|null,setCount,minReps,maxReps,initialWeightKg,approvedWeightKg}],
 cardioActivities:[{sessionActivityId,sourcePlacementId,plan:CardioPlan}]}
```

Counter는 문자열, 계획 수는 JSON 정수, decimal은 기존 루틴 정규 문자열이다. 두 배열은 원래 배치 순서이며 루틴 version은 출처 정보일 뿐 비교 계보 key가 아니다.
세션 종목/활동에는 새 UUID를 부여하고 배치 UUID와 구별한다. 수행 슬롯이나 세트 UUID는 생성하지 않는다.
ACTIVE 응답은 `{userId,dataGeneration,session:상세|null}`이다. 호출 기기는 응답 editorDeviceId와 자신의 ID를 비교해 읽기 전용 여부를 표시한다.
snapshot 구조는 아래를 따른다. START·상세·takeover 및 ACTIVE 내부 상세는 같은 확장 DTO를 사용한다.

START는 고정 UUID와 원래 요청을 사용한다. 이미 본인 현재 세대의 동일 ID가 있으면 루틴·최초 기기·시각·날짜·시간대 일치 확인 후 현재 세션을 반환한다.
이 재확인은 루틴 삭제 이후에도 가능하며 InitialPlan 재복사·편집권 재부여·시각 갱신을 하지 않는다. 다른 내용·타 소유자 ID 점유는409다.
동시 생성 PK/ACTIVE UNIQUE 경쟁은409 WRITE_CONFLICT이며 새 트랜잭션의 동일 START로 확인할 수 있다.

초기 version/writerEpoch/localRevision은0이다. 마지막 동기화 시각은 START의 서버 저장 시각이며 실제 시작 시각과 구별한다.
InitialPlan은 JPA updatable=false 및 PostgreSQL immutable-start trigger로 수정할 수 없다. 관계형 계획 사본은 없다.
루틴·종목 이름/목표/승인값 변경·배치 제거·루틴 논리 삭제가 이 JSONB를 바꾸지 않는다. 삭제 cascade는 없다.

## 제약·트랜잭션

ACTIVE 부분 UNIQUE는 **user_id 단독**이다. 세대가 바뀌어도 한 계정의 ACTIVE를 두 개 허용하지 않는다.
세션 owner/generation 및 source routine은 복합 FK다. 구 세대 연결·자동 세대 이관은 허용하지 않는다.
향후 reset은 users 배타 잠금 아래 기존 운동 데이터 정리·세대 증가·재구성을 원자 처리해야 한다. 현재 reset API는 없다.

G1부터 START는 workoutTransactionManager의 READ_COMMITTED 쓰기 트랜잭션 하나에서 users FOR UPDATE → routine FOR SHARE → 종목 UUID순 공유 잠금 → InitialPlan/세션 INSERT를 처리한다. owner 잠금 대기 이후 최신 커밋 상태를 읽고 루틴 PUT/DELETE·주간 일정 PUT과 직렬화한다.
관리 변경이 먼저 확정되면 START는 최신 계획을 복사하고, START가 먼저 확정되면 해당 원본 구성/삭제·주간 배정의 실제 변경은409 ACTIVE_SESSION_EXISTS다. 기존 세션 승인·snapshot/FINISH·ACK·임시 계획·정정/복구에는 새 관리 guard를 적용하지 않는다. 동시 START는 사용자 행에서 직렬화하고 ACTIVE UNIQUE도 유지한다. 경합 실패는409, 자동 재시도/병합 없음이다.
같은 트랜잭션에서 JDBC owner 조회와 JPA 잠금·삽입을 처리한다. takeover는 users 공유 잠금→본인 세션 FOR UPDATE로 직렬화한다.
세션 상세/ACTIVE는 짧은 별도 users 공유 잠금→session 배타 잠금 트랜잭션에서 자동 만료를 먼저 확정한 뒤, 기존 readOnly REPEATABLE_READ로 owner/generation 및 DTO를 일관되게 완성한다. 만료 전환 외 수행 변경·만료 연장·seed를 하지 않는다. Task 008 종목/설정 GET의 읽기 전용 경계는 변경하지 않는다.
기존 primary JDBC 관리자·Spring Session REQUIRES_NEW·CSRF·인증 수명 정책은 유지한다.

## 오류

본문은 `{code}`다. 기존401 AUTHENTICATION_REQUIRED와403 CSRF_INVALID/ACCESS_DENIED를 유지한다.
400 INVALID_REQUEST,404 SESSION_NOT_FOUND/ROUTINE_NOT_FOUND,422 CLOCK_CONTEXT_INVALID,
409 DATA_GENERATION_MISMATCH/SESSION_ID_CONFLICT/ACTIVE_SESSION_EXISTS/EDITOR_OWNERSHIP_LOST/SESSION_NOT_ACTIVE/WRITE_CONFLICT를 정의한다.
타 소유자 조회/인수는 미존재와 같은404이며 UUID 점유 응답에 소유자 정보를 넣지 않는다.
확인된 연결·timeout 오류는503 WORKOUT_DATA_UNAVAILABLE, 예상 밖 실패는500 WORKOUT_DATA_WRITE_FAILED다.
405/415 등 프로토콜 오류는 기존 MVC 경계를 유지한다. SQL·예외 원문·민감정보는 본문에 노출하지 않는다.

## V13 저장 경계

| 저장 위치 | 원본과 관계 |
|---|---|
| workout_sessions.applied_plan_snapshot | 현재 적용 목표·순서의 유일 원본 JSONB. InitialPlan과 별개의 변경 가능한 계획 |
| workout_sessions.last_snapshot_hash/saved_at | 현재 epoch의 마지막 수락 revision의 정규 요청 hash와 ACK 시각. 요청별 영수증 아님 |
| session_exercises | 세션 종목/활동 UUID와 종류·표준 운동 ID. 목표·승인값·원래 순서를 복제하지 않음 |
| performed_sets | 실제 완료 세트와 최초 저장 당시 승인 문맥, 명시 삭제 tombstone |
| cardio_results | completed/skipped 확정 결과와 원본 타이머·허용 정정값·tombstone |
| session_weight_approval_requests | 불변 승인 의도·종목별 sequence·선행 의존성과 적용/해결 사실 |
| record_change_events | 수행 정정·삭제 전후 값과 서버 기록 시각. 클릭/전송 로그 아님 |
| progress_change_events의 추가 출처 FK | 실제 승인 중량 변화만 WORKOUT 사건으로 추가. V11 사건·기존 컬럼값 보존 |

V12→V13은 기존 모든 컬럼값을 유지하고 InitialPlan에서 현재 계획과 종목 ID registry만 초기화한다. 초기 수행/승인 행을 만들지 않는다.
InitialPlan은 계속 유일한 최초 계획 원본이며 관계형 registry는 그 계획의 두 번째 사본이 아니다.
현재 AppliedPlan에는 목표·순서와 장비만, 실제 중량·반복·시간은 수행 테이블에만 저장한다.
새 WEIGHT 종목은 활성 공용 표준 운동을 참조해야 한다. 기존 registry의 표준 운동이 비활성화되어도 START 재확인·snapshot·복구를 거절하지 않는다.
새 세션 종목에는 출처 배치 입력 필드가 없다. InitialPlan에 출처가 있는 최초 종목만 원래 루틴 배치로 승인할 수 있다.

## 표준 운동·장비 단일 계약

InitialPlan/AppliedPlan/CompletionAssessment는 schemaVersion=2만 사용한다. 평가 ruleVersion은 기존 task009-completion-v1을 유지한다. /api/v1 경로와 외부 메타·writerEpoch/localRevision 계약은 유지하며 구 JSON codec은 없다.

InitialPlan.weightExercises의 각 항목은 기존 목표·출처·중량에 manufacturerId:UUID|null, loadType:LoadType|null을 포함한다. START가 루틴 값을 자동 복사하며 재선택 입력은 없다. AppliedPlan.weightExercises는 {sessionExerciseId,setCount,minReps,maxReps,manufacturerId,loadType}다. 모든 장비 키는 필수 nullable이며 LoadType은 PLATE/STACK/DUMBBELL/BODYWEIGHT만 허용한다.

진행 중 장비 변경은 AppliedPlan만 바꾼다. 루틴·InitialPlan·기존 완료 세트는 유지한다. performedSets/복구 SetValue에도 manufacturerId/loadType을 필수로 전송한다. 새 세트에는 완료 당시 값을 넣는다. 전체 snapshot에는 변경 전후 장비 세트가 함께 올 수 있으므로 현재 AppliedPlan과 일치를 강제하지 않는다. 서버는 존재하는 제조사 UUID를 검증하고, 저장된 세트의 장비 변경은422 IMMUTABLE_RECORD_CONTEXT로 거절한다. 없는 제조사는422 INVALID_SNAPSHOT이며 전체 요청을 롤백한다. 보존/미송신 완료 사실은 제조사 비활성만으로 거절하지 않는다. PostgreSQL도 수행 장비 불변 trigger를 적용한다.

장비 필드는 canonical snapshot hash·계획 동등성에 포함한다. 같은 revision의 장비 차이는409 REVISION_PAYLOAD_MISMATCH다. 장비 계획의 실제 변경은 lastWorkoutSyncedAt을 갱신하고 높은 revision의 동일 내용은 연장하지 않는다.

공식 비교 Context에서는 운동 필드만 standardExerciseId로 교체한다. 제조사/부하는 비교 키·승인 조건에 추가하지 않는다. 장비만 바뀐 루틴 편집은 기존 version 경합 규칙상 PENDING/ROUTINE_CHANGED가 될 수 있지만 승인 중량을 초기화하지 않는다.

사후 일반 정정은 장비를 입력받지 않고 원본을 유지한다. 복구에는 원본 장비를 포함하며 동일 UUID 내용 비교·감사 before/after에도 포함한다. UNVERIFIED 복구 역시 장비를 보존한다. 최초 계획·봉인 계획·최초 평가·FINISH 재확인은 변경하지 않는다. record-change-events.changeContext의 schemaVersion은 감사 문맥 자체의 기존1로서 계획 schema2와 별개다.

## snapshot 정확한 요청 계약

아래 모든 키는 필수다. nullable 값도 키를 생략하지 않는다. 배열은 비어 있을 수 있지만 AppliedPlan에는 최소 한 종목/활동이 있어야 한다.

```json
{
  "dataGeneration": "0",
  "editorDeviceId": "11111111-1111-4111-8111-111111111111",
  "writerEpoch": "0",
  "localRevision": "1",
  "snapshot": {
    "sessionExercises": [
      {"id": "22222222-2222-4222-8222-222222222222", "kind": "WEIGHT", "standardExerciseId": "33333333-3333-4333-8333-333333333333"}
    ],
    "appliedPlan": {
      "schemaVersion": 2,
      "weightExercises": [{"sessionExerciseId": "22222222-2222-4222-8222-222222222222", "setCount": 3, "minReps": 6, "maxReps": 10, "manufacturerId": null, "loadType": null}],
      "cardioActivities": []
    },
    "performedSets": [
      {"id": "44444444-4444-4444-8444-444444444444", "sessionExerciseId": "22222222-2222-4222-8222-222222222222", "ordinal": 1, "weightKg": "62.5", "reps": 8, "completedAt": "2026-10-31T16:02:00Z", "weightContext": {"kind": "APPROVAL_REQUEST", "requestId": "55555555-5555-4555-8555-555555555555"}, "manufacturerId": null, "loadType": null}
    ],
    "cardioResults": [],
    "deletedPerformedSetIds": [],
    "deletedCardioResultIds": [],
    "approvalRequests": [
      {"id": "55555555-5555-4555-8555-555555555555", "sessionExerciseId": "22222222-2222-4222-8222-222222222222", "sequence": "1", "previousRequestId": null, "expectedRoutineVersion": "0", "previousApprovedWeightKg": "60", "nextApprovedWeightKg": "62.5", "occurredAt": "2026-10-31T16:01:00Z"}
    ]
  }
}
```

`sessionExercises.kind`: WEIGHT는 standardExerciseId UUID 필수, CARDIO는 null이다. ID·종류·표준 운동은 불변이며 누락된 기존 registry를 삭제하지 않는다.
AppliedPlan의 배열 순서가 현재 계획 순서다. CARDIO 항목은 `{sessionActivityId,plan}`이며 plan은 기존 [루틴 CardioPlan](routine-management.md)의 키·제약을 그대로 사용한다.
setCount/반복 목표/ordinal/reps는 1..2147483647 JSON 정수이고 minReps≤maxReps다. ordinal은 계획 세트 수를 초과해도 허용한다.
중량은 0 이상, 정수부 최대14자리·소수 최대6자리의 기존 정규 문자열(불필요한 소수 끝0 없음)이다. 시각은 START와 같은 UTC 밀리초 이하 형식이다.

`weightContext`는 다음 중 하나다. 어떤 경우에도 실제 weightKg와 승인 중량을 동일하다고 추정하지 않는다.

- `{kind:"INITIAL_APPROVED",requestId:null}`: 최초 종목만 허용. InitialPlan 승인값을 최초 저장 문맥으로 고정.
- `{kind:"TODAY_ONLY",requestId:null}`: 오늘만 수행. 승인값 없음. 추가 종목에도 허용.
- `{kind:"APPROVAL_REQUEST",requestId:UUID}`: 본 세션·종목의 보존 요청 참조. 서버 최초 수행 저장 시의 요청 상태와 APPLIED일 때의 승인값을 고정.

CARDIO 확정 항목의 모든 키:

```text
{id,sessionActivityId,status:"COMPLETED"|"SKIPPED",startedAt:Instant|null,occurredAt:Instant,result:CardioResult|null}
CardioResult = {timerMs:Integer,activeMs:Integer,corrected:Boolean,distanceKm:Decimal|null,averageBpm:Integer|null}
```

COMPLETED는 startedAt/result 필수, SKIPPED는 result=null이며 미완료 값을 생성하지 않는다.
timerMs는 0..9007199254740991, activeMs는 1..86400000이다. corrected=false이면 둘이 같아야 한다.
distanceKm는 0.001..1000, TREADMILL/BIKE/OUTDOOR만 허용한다. BPM은20..300이다.
초안·현재 running/paused 상태·타이머 앵커는 이 API에 저장하지 않는다.
모든 수행/승인 시각은 시작 이후, 유산소 종료는 시작 이후, timerMs는 해당 경과시간 이하여야 한다. 승인 문맥을 참조한 세트는 승인 요청 시각 이후여야 한다.
sequence가 뒤인 승인 시각이 선행 보존 요청보다 앞서면 CLOCK_CONTEXT_INVALID다. 서버 시각으로 보정하지 않는다.

### 교체·삭제·정정

- AppliedPlan은 전체 교체한다. 기존 완료 수행이 있는 종목을 빼려면 같은 snapshot에서 해당 수행 전부를 삭제 ID로 명시해야 한다. 완료 수행을 그대로 둔 제외는422 PERFORMED_EXERCISE_IN_USE다.
- 기존 유효 수행을 active 배열과 삭제 배열 양쪽에서 누락하면422 SNAPSHOT_INCOMPLETE다. 누락을 삭제 의도로 해석하지 않는다.
- 명시 삭제는 deleted_at과 before audit를 남긴다. 이미 삭제한 ID 재확인은 무변경이다. 일반 snapshot은 삭제한 UUID를 부활시키지 않는다.
- 같은 ordinal/활동의 교체는 이전 UUID를 명시 삭제하고 새 UUID로 완료 사실을 추가한다. 기존 삭제 UUID의 재사용은 금지한다.
- 기존 세트 UUID·종목·ordinal·완료 시각·승인 참조/최초 승인 문맥·제조사·부하는 불변이다. 중량·반복만 정정하며 before/after audit를 남긴다.
- 유산소 UUID·활동·status·시작/완료 시각·timerMs는 불변이다. activeMs/정정 플래그/거리/BPM만 허용 범위 안에서 정정한다.
- 승인 요청·처리 결과와 registry는 서버 보존 사실이다. 후속 입력에서 빠져도 삭제/재적용하지 않는다.
- 원래 미완료 종목을 빼도 InitialPlan은 유지된다. 현재 계획 축소를 원계획 완료로 해석하지 않으며, 최초 종료 평가에서도 원계획 완료 여부를 별도로 확인한다.

### revision·ACK

현재 epoch에서 localRevision>0의 높은 값만 수락한다. 연속 번호일 필요는 없다.
동일 revision·동일 정규 요청은200 duplicate=true이며 운동 DML/시간/version 변경이 없다. 다른 본문은409 REVISION_PAYLOAD_MISMATCH, 낮은 revision은409 REVISION_STALE다.
객체 키 순서·UUID 대소문자·identity/수행/삭제 배열의 순서는 의미를 바꾸지 않는다. 계획 순서는 보존하고 승인 배열은 종목별 sequence로 정규화한다.
ACK 유실 시 같은 입력을 재전송한다. 응답/상세의 acceptedLocalRevision 또는 localRevision과 승인 결과를 확인한다.
높은 revision의 실제 무변경 저장은 수락 revision/hash·snapshotSavedAt·version만 갱신한다. lastWorkoutSyncedAt은 AppliedPlan/유효 수행/명시 삭제/허용 정정이 실제 달라진 경우만 갱신한다.
takeover는 localRevision=0/hash=null/lastSnapshotSavedAt=null로 새 epoch를 시작하며 계획·수행·승인 결과·lastWorkoutSyncedAt을 유지한다.

정확한 ACK 키(모든 Counter는 문자열):

```text
{userId,dataGeneration,sessionId,writerEpoch,acceptedLocalRevision,version,snapshotSavedAt,lastWorkoutSyncedAt,duplicate,approvalResults:[ApprovalResult]}
ApprovalResult = {id,sessionExerciseId,sequence,status:"APPLIED"|"PENDING"|"NOT_APPLIED",reason:String|null,
 approvalEventId:UUID|null,routineVersionBefore:Counter|null,routineVersionAfter:Counter|null,resolvedAt:Instant|null,resolvedByRequestId:UUID|null}
```

START/상세/takeover의 추가 `snapshot`은 `{state:위 snapshot 전체 구조,approvalResults:[ApprovalResult],recordedContexts:[{setId,approvalStateAtRecording,approvedWeightKgAtRecording:Decimal|null}]}`다.
state는 유효 수행·전체 삭제 ID·전체 보존 요청을 반환한다. recordedContexts는 삭제된 세트까지 최초 문맥을 보존한다. 승인 상태는 전송 필드가 아니라 서버 응답이다.
lastSnapshotSavedAt은 아직 해당 epoch에서 snapshot을 수락하지 않았다면 null이다.

## 명시 승인 적용·충돌·온라인 해결

요청 UUID별 원문 의도를 보존한다. 같은 UUID의 다른 의도는409 APPROVAL_REQUEST_ID_CONFLICT다.
종목별 sequence는1부터 빈틈없이 증가한다. 점유 순번은409 APPROVAL_SEQUENCE_CONFLICT, 건너뜀은422 APPROVAL_SEQUENCE_GAP다.
previousRequestId는 같은 종목의 앞선 요청을 참조하며 그 next 중량과 새 previous 중량이 같아야 한다. 오프라인 연속 승인은 이 연결을 유지한다. 재조회로 확인한 새 독립 승인은 null로 표현한다.
같은 업로드는 잠금 당시 루틴 version을 공통 외부 기준으로 처리한다. 내부 각 승인을 순서대로 적용하고 실제 변화가 있으면 루틴 version은 트랜잭션당1 증가한다. 각 실제 변화 사건은 따로 보존한다.
ACK 유실 후 이어지는 요청은 이 세션의 APPLIED 요청에 저장한 versionBefore→versionAfter 연결이 기대값과 현재값 사이를 빠짐없이 설명하는 경우에만 자체 증가로 인정한다. 외부 변경으로 생긴 빈틈은 승인 충돌이다.

정상은 APPLIED다. 같은 중량의 명시 승인도 의도를 보존하지만 실제 변화 사건/루틴 version 증가는 없다.
루틴 변경(ROUTINE_CHANGED), 출처 삭제(SOURCE_REMOVED), 출처 없음(NO_SOURCE_PLACEMENT), 선행 미해결/미적용(PREDECESSOR_PENDING/PREDECESSOR_NOT_APPLIED)은 PENDING이다.
PENDING이 있어도 유효 수행과 현재 계획은 같은 트랜잭션에서 저장하고200 ACK한다. 승인된 것으로 표시하지 않는다. 저장소 실패/제약 오류를 PENDING으로 바꾸지 않는다.

온라인 해결 요청의 모든 키:

```json
{
  "dataGeneration": "0", "editorDeviceId": "11111111-1111-4111-8111-111111111111", "writerEpoch": "0",
  "expectedSessionVersion": "2", "expectedLocalRevision": "1",
  "sessionExerciseId": "22222222-2222-4222-8222-222222222222",
  "pendingRequestIds": ["55555555-5555-4555-8555-555555555555"],
  "decision": "KEEP_CURRENT", "occurredAt": "2026-10-31T16:05:00Z", "newApprovalRequest": null
}
```

pendingRequestIds는 그 종목의 현재 미해결 요청 전부와 일치해야 한다. session version/localRevision도 같아야 하며 불일치는409 RESOLUTION_CONFLICT다.
KEEP_CURRENT는 선택한 요청을 NOT_APPLIED/KEPT_CURRENT로 확정하고 현재 루틴 승인값을 유지한다.
REAPPROVE는 `decision:"REAPPROVE"`, `newApprovalRequest:{id:새UUID,sequence:다음Counter,expectedRoutineVersion:현재Counter,previousApprovedWeightKg:현재Decimal,nextApprovedWeightKg:Decimal}`를 보낸다.
새 요청의 종목/occurredAt은 해결 요청에서 가져오고 previousRequestId=null이다. 선택한 과거 요청은 NOT_APPLIED/REAPPROVED/resolvedByRequestId=새UUID로 확정한다.
루틴 현재 version/중량을 다시 검증하고 새 요청·새 사건을 지금 발생한 승인으로 원자 저장한다. 삭제/출처 없는 배치는409 SOURCE_NOT_APPROVABLE이며 KEEP_CURRENT만 가능하다.
과거 PENDING을 APPLIED로 소급 전환하거나 기존 수행 문맥·과거 사건을 고치지 않는다.

해결 응답은 `{userId,dataGeneration,sessionId,writerEpoch,localRevision,version,lastWorkoutSyncedAt,approvalResults:[ApprovalResult]}`다.
해결은 session version만 올리고 localRevision/hash/lastWorkoutSyncedAt을 바꾸지 않는다.
응답 유실 시 GET 상세의 status/reason/resolvedAt/resolvedByRequestId로 확인한다. 동일 해결 사실 재확인은200 무변경이다. 별도 영구 요청 영수증/요청 ID는 없다.

## snapshot 트랜잭션과 추가 오류

하나의 workoutTransactionManager READ_COMMITTED 트랜잭션에서 users FOR SHARE → 승인 포함 시 source routine FOR UPDATE → 표준 운동 UUID순 공유 잠금 → 본인 세션 FOR UPDATE → 하위 저장 순서다.
세션 출처 사전 조회는 스칼라 JDBC만 사용하고, 잠금 전에 JPA 세션을 캐시하지 않는다. routine PUT/DELETE와 같은 잠금 순서이며 takeover는 세션 잠금에서 직렬화한다.
수행·승인 요청·실제 변화 사건·정정 이력·계획·버전·ACK 메타는 하나의 커밋이다. 중간 SQL 오류/overflow/불변 문맥 위반은 전체 롤백한다.
CSRF·소유권·세대·편집 기기·epoch 검증은 중복 ACK와 해결 재확인에도 우회하지 않는다.

추가400 INVALID_REQUEST,404 RECORD_NOT_FOUND/EXERCISE_NOT_FOUND,409 RECORD_ID_CONFLICT(다른 문맥이 점유한 수행/종목 UUID),422 INVALID_SNAPSHOT/IMMUTABLE_RECORD_CONTEXT/RECORD_DELETED와 위 도메인 오류를 사용한다. 기존503/500 저장소 오류 경계는 유지한다.
위 snapshot/approval-resolutions는 ACTIVE 전용이다. 종료 후 수정은 아래 별도 계약을 사용한다.

## 종료 운동 목록 — Frontend 5D 최소 읽기 계약

`GET /api/v1/workout-sessions/closed?limit=20`. 로그인 principal의 본인·현재 dataGeneration 중 FINISHED/AUTO_CLOSED만 조회한다. 논리 삭제된 세션도 삭제 메타를 반환하며 ACTIVE는 제외한다. 목록 조회는 만료 전이·운동 저장·평가 계산을 수행하지 않는다. 기존 세션 필터의 인증 접근 처리는 그대로다. no-store와 GET body 거절을 유지한다.

query는 `limit`, `beforeClosedAt`, `beforeId`만 허용하고 중복/추가 키는400이다. limit은 기본20, 정규 양수1~50이다. cursor 두 필드는 둘 다 없거나 둘 다 있어야 한다. beforeClosedAt은 UTC Z 시각(소수 최대6자리), beforeId는 UUID다. `closedAt DESC → id DESC`의 엄격한 다음 페이지를 가져오며 cursor는 불변 종료 메타를 사용한다. 임의 sort/filter/userId/dataGeneration query를 받지 않는다.

```text
{userId,dataGeneration,sessions:[
 {id,routineName,status:"FINISHED"|"AUTO_CLOSED",version,workoutDate,startedAt,
  endedAt:Instant|null,closedAt,deletedAt:Instant|null}
],next:{beforeClosedAt,beforeId}|null}
```

routineName은 불변 InitialPlan의 시작 당시 이름이다. Counter는 정규 문자열이다. 수행 요약·통계·달력·메모·평가·전체 상세를 추가하지 않는다. 종료/삭제 메타의 의미는 상세 계약과 같다. UI는 AUTO_CLOSED의 closedAt을 실제 endedAt으로 대신 표시하지 않는다. DB 스키마/기존 V1~V15 migration 변경은 없다.

## FINISH — 최종 snapshot과 최초 종료 평가

`POST /api/v1/workout-sessions/{id}/finish`, application/json. 요청의 정확한 필드는 기존 snapshot PUT의 `dataGeneration,editorDeviceId,writerEpoch,localRevision,snapshot`에 `endedAt:Instant` 하나를 추가한 것이다. snapshot 구조와 모든 필드·정규화·명시 삭제·허용 정정·승인 요청 규칙은 V13과 동일하다. userId·평가값·승인 처리 상태를 입력받지 않는다.

```json
{
  "dataGeneration": "0",
  "editorDeviceId": "11111111-1111-4111-8111-111111111111",
  "writerEpoch": "0",
  "localRevision": "1",
  "endedAt": "2026-10-31T16:10:00Z",
  "snapshot": {
    "sessionExercises": [{"id":"22222222-2222-4222-8222-222222222222","kind":"WEIGHT","standardExerciseId":"33333333-3333-4333-8333-333333333333"}],
    "appliedPlan": {"schemaVersion":2,"weightExercises":[{"sessionExerciseId":"22222222-2222-4222-8222-222222222222","setCount":3,"minReps":6,"maxReps":10,"manufacturerId":null,"loadType":null}],"cardioActivities":[]},
    "performedSets": [],
    "cardioResults": [],
    "deletedPerformedSetIds": [],
    "deletedCardioResultIds": [],
    "approvalRequests": []
  }
}
```

위 예시는 수행이 없는 부분 명시 종료다. 실제 종료 시각은 시작 이후이면서 최종 유효 세트/유산소의 발생 시각 이후여야 한다. 같은 finish에서 새로 제출한 승인 시각도 endedAt 이후일 수 없다. 기존 승인 해결 같은 후속 관리 시각이나 서버 업로드 시각으로 실제 endedAt을 덮어쓰지 않는다. 모순은422 CLOCK_CONTEXT_INVALID다.

하나의 workoutTransactionManager 트랜잭션에서 기존 snapshot 저장 로직을 재사용하여 최종 수행·승인 요청을 저장하고, 최초 평가·FINISHED·endedAt·closedAt을 원자 확정한다. 같은 트랜잭션의 세션 version은1회만 증가한다. 유효 수행 부족·PENDING·비교 대상 없음은 종료를 막지 않는다. 미완료 세트나 유산소를 자동 완료/SKIPPED 처리하지 않는다.

응답의 정확한 필드:

```text
{userId,dataGeneration,sessionId,status:"FINISHED",writerEpoch,acceptedLocalRevision,version,currentVersion,deletedAt:Instant|null,
 snapshotSavedAt,lastWorkoutSyncedAt,endedAt,closedAt,duplicate,approvalResults:[ApprovalResult],
 completionAssessment:CompletionAssessment}
CompletionAssessment = {schemaVersion:2,ruleVersion:"task009-completion-v1",assessedAt,sessionVersion,
 overallWeightPlanComplete:Boolean|null,exercises:[ExerciseAssessment]}
ExerciseAssessment = {sessionExerciseId,eligible,reasonCodes:[String],comparisonContext:ComparisonContext|null,
 evaluatedPerformedSetIds:[UUID],evaluatedReps:[Count],repsSum:Counter,benchmarkEvidence:Benchmark|null,
 advice:"INCREASE"|"DECREASE"|"MAINTAIN"|"UNAVAILABLE",adviceReason:String}
ComparisonContext = {routineId,sourcePlacementId,standardExerciseId,plannedSetCount,minReps,maxReps,approvedWeightKg,unit:"KG"}
Benchmark = {sessionId,sessionVersion,endedAt,performedSetIds:[UUID],reps:[Count],repsSum:Counter,ruleVersion}
```

Counter는 기존과 같은 정규 문자열이다. 유산소만 있는 세션은 overallWeightPlanComplete=null, exercises=[]이며 가짜 웨이트 평가를 만들지 않는다.
APPLIED_PLAN은 별도 사본 없이 기존 applied_plan_snapshot을 종료와 동시에 동결한다. JSONB 최초 평가의 선택 UUID·반복 배열·합계는 불변 판단 근거이며 편집 가능한 수행 원본이 아니다. 관계형 실제 수행을 평가 JSON으로 수정하지 않는다.
최초 평가의 sessionVersion 및 finish 응답 version은 최초 종료 version이다. 향후 허용 정정으로 현재 상세 version이 달라지더라도 최초 종료 결과를 바꾸지 않는다.
currentVersion은 현재 세션 version이다. approvalResults는 현재 승인 해결 결과를 반환한다. 최초 평가·endedAt·closedAt·종료 식별 hash/epoch/revision과 현재 승인 결과를 구분한다. 전체 논리 삭제 뒤에도 원래 FINISH 재확인은200이며 deletedAt을 함께 반환한다. 이는 저장·복원·평가 재실행이 아니고 삭제 기록을 다시 노출하는 근거가 아니다. 다른 종료 내용과 구 기기/epoch는 계속 거절한다.
START·snapshot·종료의 서버 저장 메타 시각은 PostgreSQL과 동일한 microsecond 정밀도로 기록하여 최초 응답과 재조회/중복 ACK를 일치시킨다. 클라이언트 실제 발생 시각은 기존 millisecond 입력 그대로 보존하며 서버 시각으로 보정하지 않는다. 만료 여부는 정밀도를 줄이기 전 서버 Clock으로 판정한다.

### 종료 중복·revision

- 높은 revision은 V13과 같이 검증·저장한다. 현재 revision과 동일하고 canonical snapshot hash가 같아도 최초 finish로 종료할 수 있다. 낮은 revision이나 같은 revision의 다른 snapshot은 기존409를 유지한다.
- START/takeover 직후 revision 0은 아직 확인된 snapshot hash가 없다. 최초 FINISH는 종료 선택이라는 새 로컬 확정 변경의 높은 revision을 사용한다. 재시도 때 revision을 새로 발급하지 않는다.
- FINISHED의 동일 `owner/generation/device/epoch/revision/canonical snapshot hash/endedAt`은200 duplicate=true다. 최초 평가·승인·수행·version·시각을 다시 저장하지 않는다.
- 종료 식별에는 기존 snapshot hash·epoch·revision과 endedAt을 사용한다. 별도 finish 요청 ID·영구 영수증·추가 fingerprint 컬럼은 없다.
- 다른 내용·종료 시각·revision의 finish는409 FINISH_REQUEST_MISMATCH, 구 기기/epoch는409 EDITOR_OWNERSHIP_LOST다. 동일 종료 재확인은 24시간 이후에도 최초 결과를 반환한다.
- FINISHED 이후 일반 snapshot·takeover·ACTIVE 승인 해결은409 SESSION_NOT_ACTIVE다. 종료 후 승인 해결/수행 복구에는 별도 온라인 API를 사용한다. 논리 삭제된 세션의 일반 쓰기는409 SESSION_DELETED다.
- AUTO_CLOSED에는 성공한 명시 finish가 없으므로409 SESSION_AUTO_CLOSED이며 허위 finish ACK를 만들지 않는다.

## 최초 공식 평가 — 확정 규칙

사용자가 2026-10-31 구현 중 A·B를 확정했다. 목표 반복 범위 밖의 양수 수행도 정상 문맥이면 후보로 허용한다(A). 승인 전 수행을 명시 삭제했더라도 이력과 당시 승인 문맥을 보존하면서 현재 유효 첫 N개로 판단하고, 그 삭제 이력만으로 영구 제외하지 않는다(B).

InitialPlan 대상 종목의 삭제되지 않은 완료 수행을 ordinal ASC→UUID ASC로 정렬해 처음 N개를 선택한다. 승인 검사는 선택 후에 한다. 임시/PENDING 수행을 건너뛰어 뒤의 정상 수행으로 바꾸지 않는다. N개 이후 수행은 모두 저장하지만 그 자체로 자격을 박탈하지 않는다.
전체 원계획 웨이트 완료 여부, 최종 목표 세트 수·반복 범위 일치, 수행 당시의 정상 승인 문맥과 실제 중량 일치를 검사한다. 중간 구조 변경 flag·루틴 이름·표시 정렬로 판단하지 않는다. 현재 루틴 승인값이나 나중의 재승인으로 최초 수행 문맥을 바꾸지 않는다.

제외 사유는 INCOMPLETE_INITIAL_WEIGHT_PLAN, INITIAL_EXERCISE_REMOVED, SET_COUNT_MISMATCH, COMPARISON_CONTEXT_MISMATCH, INSUFFICIENT_PERFORMED_SETS, TEMPORARY_WEIGHT, NO_APPROVED_CONTEXT, MIXED_WEIGHT다. 유산소 skip/미입력 자체를 웨이트 제외 사유로 사용하지 않는다.

과거 후보는 본인 현재 세대의 FINISHED 중 `candidate.endedAt <= current.startedAt`이고 비교 문맥이 같은 단일 세션이다. AUTO_CLOSED·현재 세션은 제외한다. 첫 N개 합계 DESC→실제 endedAt DESC→UUID ASC로 선택하며 여러 세션의 반복을 합성하지 않는다. 별도 benchmark 캐시 테이블은 없다.

이번 수행 권고는 후보 순위와 독립이다. 자격 미충족은 UNAVAILABLE/INELIGIBLE, 첫 N개 중 하나라도 하한 미달은 DECREASE/BELOW_MIN_REPS, 전부 상한 이상은 INCREASE/ALL_AT_OR_ABOVE_MAX_REPS, 나머지는 MAINTAIN/TARGET_NOT_EXCEEDED_BY_ALL_SETS다. 비교 대상이 없더라도 정상 수행 자체의 권고는 계산한다. 중량 변경 폭·1RM·최근 약한 수행과 최고 기준을 합친 새 공식은 만들지 않는다. 승인 중량/사건을 자동 변경하지 않는다.
이 산식은 2026-10-31 사용자가 확정한 초기 안내 정책이다. 결과는 사용자의 판단을 돕는 권고이며 승인 중량 변경은 별도의 명시적 승인을 통해서만 처리한다.

## 24시간 AUTO_CLOSED와 만료 경합

`now >= lastWorkoutSyncedAt + Duration.ofHours(24)`를 세션 잠금 후 새로 읽은 서버 Clock으로 판정한다. 먼저 만료를 검사한 뒤 입력을 적용하므로 늦은 snapshot이 기한을 갱신하며 살아나지 않는다. GET·takeover·중복·높은 무변경 revision·승인 해결은 기한을 연장하지 않는다.

V14 추가 컬럼은 ended_at(사용자 실제 종료), closed_at(서버 종료 기록), completion_assessment_snapshot 세 개다. 상태별 제약:

| 상태 | ended_at | closed_at | completionAssessment |
|---|---|---|---|
| ACTIVE | null | null | null |
| FINISHED | 필수 | 필수 | 필수 |
| AUTO_CLOSED | null | 필수 | null |

AUTO_CLOSED는 현재 저장된 수행·유산소·PENDING·계획을 그대로 보존한다. 미전송 초안을 만들거나 운동 시간을 24시간으로 채우지 않는다. 종료 후 AppliedPlan·최초 평가·종료 시각·editor/epoch/revision/hash/동기화 메타는 DB trigger로 동결한다. InitialPlan trigger와 사용자 단독 ACTIVE UNIQUE도 유지한다.

snapshot/finish/takeover/승인 해결에서 만료 전환은 트랜잭션 결과로 반환해 **먼저 커밋한 뒤**409 SESSION_AUTO_CLOSED로 변환한다. 정상 만료에서 트랜잭션 내부 예외를 던져 종료를 롤백하지 않는다. 실제 DB 오류는 전체 롤백한다.
새 START는 기존 만료 ACTIVE를 닫고 flush하여 슬롯을 해제한 뒤 새 세션/registry를 같은 트랜잭션에 생성한다. 실패하면 함께 롤백한다. 동일 START 재조회는 기존 종료 상태를 반환하며 새 ACTIVE를 만들지 않는다.

예약 작업은 기존 Spring @Scheduled로 시작 시 실행하고 기본 fixed delay60초다. `overload.workout.expiry.enabled=false`로 비활성화할 수 있고 `overload.workout.expiry.delay-ms`로 간격을 지정한다. 만료 기준24시간은 설정으로 바꾸지 않는다.
후보는 잠그지 않고 최대1000개 조회하며 후보별 독립 트랜잭션에서 users FOR SHARE→session FOR UPDATE SKIP LOCKED 순으로 잠그고 재검사한다. 처리 실패는 경고를 기록하고 롤백하며 다음 실행에서 재시도한다. 기동 시 DB에서 만료 후보를 다시 읽으므로 별도 작업 영수증/메모리 만료 상태/분산 잠금은 없다.
요청과 작업이 경합하면 세션 잠금 아래 최신 상태·기한을 재확인한다. 기한 전에 잠금을 얻어 유효성을 확인한 저장/종료와 만료 후 전이를 구분한다. AUTO_CLOSED 이후의 오래된 업로드는 결과를 덮어쓰거나 재개하지 못한다.

## 종료 후 정정·삭제·복구 — V15

FINISHED와 AUTO_CLOSED에만 허용한다. 로그인·CSRF·principal 소유권·현재 dataGeneration과 expectedVersion을 검증한다. ACTIVE writerEpoch/localRevision 계약을 사용하거나 해당 필드를 받지 않는다. 모든 Counter·Decimal·UUID·실제 발생 시각의 형식과 수치 범위는 V13과 동일하다. 추가/중복/누락 필드와 중복 대상 UUID는400 INVALID_REQUEST다. 모든 경로는 query를 받지 않는다.

### 선택 수행 정정·삭제

PATCH records의 정확한 필드:

```json
{
  "dataGeneration":"0","expectedVersion":"7",
  "setCorrections":[{"id":"11111111-1111-4111-8111-111111111111","weightKg":"62.5","reps":8}],
  "cardioCorrections":[],"deletedPerformedSetIds":[],"deletedCardioResultIds":[]
}
```

cardioCorrections의 항목은 `{id,activeMs,corrected,distanceKm,averageBpm}`이며 nullable 거리/심박을 명시한다. 타이머 원본은 입력받지 않는다. COMPLETED 수치만 정정하며 corrected=false이면 저장된 timerMs와 activeMs가 같아야 한다. 유산소 kind에 따른 거리 허용과 기존 범위도 검사한다.
배열 네 개는 필수이며 적어도 한 대상을 지정한다. 같은 종류의 UUID를 정정/삭제 양쪽에 넣지 않는다. 누락된 수행은 변경하지 않는다. UUID·종목·ordinal·실제 완료 시각·원래 weightContext·최초 승인 상태/중량과 유산소 status/시작·완료 시각/timerMs는 변경하지 않는다.
각 변경은 before/after 이력을 저장하고 논리 삭제는 deleted_at을 기록한다. 같은 값 정정이나 이미 삭제된 ID의 삭제 확인은 expectedVersion이 현재일 때 changed=false이며 이력·version을 추가하지 않는다. tombstone의 값 정정은422 RECORD_DELETED다.

### 누락 완료 수행 복구

POST recoveries의 정확한 필드:

```json
{
  "dataGeneration":"0","expectedVersion":"8","confirm":true,
  "origin":{"editorDeviceId":"22222222-2222-4222-8222-222222222222","writerEpoch":"0","localRevision":"12"},
  "performedSets":[],"cardioResults":[]
}
```

위 배열은 위에서 정의한 V17 SetValue(장비2필드 포함)/기존 Cardio 구조이며, 적어도 하나의 실제 완료 수행을 선택한다. 유산소는 COMPLETED만 허용하고 미완료/SKIPPED를 새로 만들지 않는다. 서버에 이미 등록된 같은 세션 종목만 대상으로 한다. 표준 운동이 비활성화되거나 원본 루틴이 논리 삭제돼도 기존 registry와 보존 계획을 사용한다. 유산소 계획이 InitialPlan·봉인 AppliedPlan 어디에도 없어 검증할 수 없다면422 RECOVERY_CONTEXT_UNAVAILABLE다. 새 계획이나 종목을 받아 보완하지 않는다.
confirm=true는 명시적 사용자 선택이다. origin은 원래 로컬 문맥을 보존하는 이력이며 인증·편집권 증명이 아니다. source epoch가 마지막 epoch보다 크거나, 알려진 최초/마지막 epoch의 기기와 모순되면422 INVALID_RECOVERY_ORIGIN이다. 중간 epoch의 기기 이력을 가지고 있다고 주장하지 않는다. 이전 epoch를 현재 epoch로 재태깅하거나 localRevision을 서버에 적용하지 않는다.

FINISHED는 `startedAt <= completedAt/occurredAt <= endedAt`을 요구한다. AUTO_CLOSED는 시작 이후와 타이머·시각 선후 관계만 검사하며 closedAt 뒤의 실제 완료라는 이유만으로 거절하지 않는다. 클라이언트 원래 시각을 유지하고 서버 시각으로 보정하지 않는다. AUTO_CLOSED 상태·endedAt=null·completionAssessment=null은 그대로다.

같은 UUID의 동일 수행은 alreadyPresentIds로 반환한다. 같은 UUID의 다른 값은409 RECORD_ID_CONFLICT, 기존 tombstone은422 RECORD_DELETED다. 다른 UUID가 유효 ordinal 또는 유산소 활동을 점유하면409 RECORD_SLOT_CONFLICT다. 자동 정정·병합·재번호·tombstone 해제는 하지 않는다. 한 항목이라도 실패하면 업로드 전체를 롤백한다. 원본은 향후 IndexedDB에 남겨 사용자가 다시 확인한다.

복구 승인 문맥은 서버가 결정한다. 클라이언트가 승인 처리 상태나 승인 중량을 지정하지 않는다.

- INITIAL_APPROVED: 보존 InitialPlan에 해당 종목이 있으면 그 승인값을 유지한다. 실제 중량이 다르면 현재 공식 평가에서 제외될 수 있다.
- TODAY_ONLY: 기존 비승인 문맥을 유지한다.
- APPROVAL_REQUEST: 같은 세션·종목의 보존 요청이 ACTIVE 중 생성된 APPLIED/PENDING이면 해당 사실을 유지하고 승인 시각 이후 수행인지 검사한다. 현재 루틴 승인값을 읽어 대신 쓰지 않는다.
- 확인할 수 없는 주장, 이미 해결되어 당시 상태를 확인할 수 없는 요청, 종료 후 새 재승인을 근거로 한 주장은 UNVERIFIED로 저장한다. 원래 Context와 관련 UUID는 복구 사건 changeContext.claimedWeightContext에 보존한다. 실제 수행 행의 context_kind/approval_state_at_recording은 UNVERIFIED, 승인 FK·승인값은 null이다.

UNVERIFIED/null 참조를 명시적으로 제출할 수도 있다. 상세 snapshot의 weightContext.kind와 recordedContexts.approvalStateAtRecording에도 UNVERIFIED가 추가된다. 이는 복구/조회 계약 확장이며 ACTIVE snapshot의 허용 입력은 기존 세 가지다. UNVERIFIED를 첫 N개에서 건너뛰거나 이후 승인으로 정상화하지 않는다.

### 세션 전체 논리 삭제

DELETE body는 정확히 `{dataGeneration,expectedVersion}`이다. 세션 deletedAt만 기록하고 상태·종료 메타·계획·최초 평가·하위 수행·승인 사건은 보존한다. SESSION/DELETED 사건을 하나 기록한다. 상세 GET은 본인 tombstone과 보존 사실을 반환한다. deletedAt이 있으면 수정·복구·승인 해결을 거절하고 현재 공식 평가/다른 세션의 비교 후보에서 제외한다. 원래 FINISH 확인 외 일반 쓰기는 SESSION_DELETED다. 전체 삭제 해제 API는 제공하지 않는다.

정정·복구·전체 삭제의 정확한 성공 응답:

```text
{userId,dataGeneration,sessionId,status,version,changed,changeEventIds:[UUID],
 recoveredIds:[UUID],alreadyPresentIds:[UUID],deletedAt:Instant|null}
```

### 종료 후 PENDING 해결

POST closed-approval-resolutions body는 `{dataGeneration,expectedVersion,sessionExerciseId,pendingRequestIds,decision,occurredAt,newApprovalRequest}`다. newApprovalRequest의 키와 KEEP_CURRENT/REAPPROVE 의미는 ACTIVE 해결과 동일하다. 해당 종목의 현재 PENDING 전체를 명시해야 하며 재승인은 현재 루틴 version/승인값·활성 출처를 검증한 새 UUID/시각/순번이다.
성공 응답은 `{userId,dataGeneration,sessionId,status,version,changed,approvalResults}`다. 기존 요청의 NOT_APPLIED/해결 시각/새 참조는 보존한다. 종료 후 생성된 요청은 created_after_closure=true로 구분하여 이후 복구의 과거 정상 승인 근거로 사용하지 않는다. 기존 수행 문맥과 최초 평가는 변경하지 않는다. 값이 같은 명시 재승인도 요청 사실은 보존하며 실제 중량 변화가 있을 때만 기존 V11 방식의 승인 사건과 루틴 version 변경을 만든다.

### 현재 평가·변경 이력 조회

```text
GET assessment -> {userId,dataGeneration,sessionId,status,version,completionAssessment,
 currentAssessment:{state:"EVALUATED"|"EXCLUDED",reasonCodes:[String],advice:null|"UNAVAILABLE",evaluation:CompletionAssessment|null}}
GET record-change-events -> {userId,dataGeneration,sessionId,version,events:[
 {id,recordKind:"SET"|"CARDIO"|"SESSION",recordId,changeKind:"CORRECTED"|"DELETED"|"RECOVERED",
  before:Object|null,after:Object|null,changeContext:Object|null,recordedAt}]}
```

현재 평가는 readOnly REPEATABLE_READ 한 트랜잭션에서 수행과 과거 후보의 현재 유효 사실을 재평가한다. 기존 첫 N개/순위/시간 조건/권고 규칙과 버전을 유지한다. 과거 후보를 정정·삭제하면 다음 조회에 반영되지만 최초 평가를 연쇄 변경하지 않는다. 평가 JSONB/캐시/백그라운드 재계산 테이블은 없다. 비교 후보 변경은 대상 세션 자체 version을 바꾸지 않으므로 그 version만을 전체 평가 캐시 key로 사용하지 않는다.

현재 WorkoutAssessment는 현재 수행을 먼저 평가하고 eligible 종목이 하나도 없을 때만 과거 비교 조회를 생략한다. eligible이 하나라도 있으면 DECREASE를 포함해 기존 과거 종료 세션 후보·비교·순위·동률 정책을 유지한다. 과거 수행은 전체 후보를100개 세션씩 조회해 세션별로 분류하며 수행이 없는 후보도 평가한다. FINISH 결과 구성 전 변경 가시성은 비교 조회의 자동 flush 또는 조회를 생략하는 분기의 명시적 flush로 유지한다.

평가 함수 내부 SELECT는 코드 경로 기준으로 비교가 불필요하면 현재 수행 조회1회, 필요하면 후보 H개에 대해 `2 + ceil(H/100)`회다(H=0은2회). 호출자의 다른 조회·자동 flush DML은 제외하며 처리 시간 측정값이 아니다. 전체 후보와 JPA 관리 엔티티의 선형 증가는 남아 있으며 운영 성능을 보장하지 않는다. JSON Mapper 구조와 복구·이력·잠금·rollback 계약은 변경하지 않는다.

AUTO_CLOSED/삭제 세션은 EXCLUDED, 이유 AUTO_CLOSED/SESSION_DELETED, advice=UNAVAILABLE, evaluation=null이다. 최초 평가가 없던 AUTO_CLOSED에는 계속 null이다. ACTIVE 평가 조회는409 SESSION_NOT_CLOSED다. 이력은 본인 세션의 운동 중 이력까지 포함하고 recordedAt ASC→UUID ASC로 전체 반환한다. 전역 feed/cursor가 아니다.
새 사건 changeContext는 `{schemaVersion:1,sessionVersion:Counter,origin?:Origin,claimedWeightContext?:Context}`다. 기존 V13 사건은 context=null 그대로다. RECOVERED는 before=null/after=추가 수행, 삭제 수행은 tombstone과 before를 보존한다. 계정은 불변 session 소유권으로 확인하며 기기 값은 원본 주장으로만 표시한다.

### 동시성·롤백·응답 유실

실제 변경 트랜잭션당 세션 version/updatedAt만 한 번 갱신한다. InitialPlan·봉인 AppliedPlan·최초 평가·종료 시각·writerEpoch·localRevision·hash·lastSnapshotSavedAt·lastWorkoutSyncedAt은 변경하지 않는다. stale expectedVersion은 무변경/동일 재요청에도409 SESSION_VERSION_CONFLICT다. ACK 유실 시 상세·이력·승인 해결 사실을 재조회하고 사용자 확인 없이 새 version을 붙여 재적용하지 않는다. 새 영구 요청 영수증은 없다.
일반 정정/복구/삭제는 users FOR SHARE→session FOR UPDATE, 재승인은 users→routine FOR UPDATE→session 순서다. 표준 운동 잠금이 필요한 ACTIVE/루틴 경로의 UUID순 규칙은 유지하며 세션을 잠근 뒤 루틴을 잠그지 않는다. JPA/JDBC workoutTransactionManager 하나에서 수행·이력·승인·version을 원자 처리한다. DB 실패는 전체 롤백하며 PENDING으로 위장하지 않는다.
추가 오류는409 SESSION_NOT_CLOSED/SESSION_DELETED/SESSION_VERSION_CONFLICT/RECORD_SLOT_CONFLICT와422 RECOVERY_CONTEXT_UNAVAILABLE/INVALID_RECOVERY_ORIGIN이다. 기존404 RECORD_NOT_FOUND·409 SOURCE_NOT_APPROVABLE/RESOLUTION_CONFLICT·422 RECORD_DELETED/CLOCK_CONTEXT_INVALID 및 인증·CSRF·소유권·세대·503 경계를 유지한다.

T02-B V23: 유산소 Plan의 optional activityId는 세션 최초/적용 계획에 그대로 전달된다. 새로 제공한 ID는 active catalog의 kind/name과 일치해야 하며 미제공 과거7필드 JSON을 보존한다. 기존 schemaVersion=2, snapshot 요청 고정·hash·ACK·writerEpoch 및 수행 불변 정책은 변경하지 않는다.

## G2~G3: START 일정 출처·일별 파생 판정

V24의 nullable schedule_version_at_start는 신규 START에서만 설정한다. 당시 시각의 구/신 버전 경계와 저장된 workoutDate의 요일로 예정 여부를 확인하며, 첫 예정 START의 세션 참조를 날짜별로 고정한다. 기존 null·InitialPlan/요청 JSON/날짜/시간대/hash는 backfill하지 않는다. 미수행이 먼저 확정된 날짜에 늦은 START가 와도 최초 미수행 근거를 교체하지 않는다.

원본 세션 상태/삭제 및 실제 수행의 변경에 대한 전용 DB trigger가 동일 workoutTransactionManager 트랜잭션에서 날짜를 dirty로 만든다. 동일 FINISH/snapshot ACK·GET·단순 높은 revision no-op은 일별 재검사나 원본 시각을 늘리지 않는다. 기존24시간 만료와 종료 후 정정/삭제/명시 누락 복구도 같은 방식으로 재검사 대상이 되며 새로운 운동 쓰기/오프라인 시스템을 도입하지 않는다. 자동 검사는 [일정·일별 계약](weekly-schedule.md)을 따르며 디로딩 소속 사실과 보호 날짜는 일반 G3 판정에서 제외한다.

### 현행 정책과 적용 범위

디로딩은 [v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md) 기준이며 G4 분석, G5-1 기간 시작, G5-2 운동 세션과 G5-3 종료·재개가 구현되었다. 일반 계획 ST-17~23과 디로딩 후보는 분리하며 G3는 분석OFF여도 유지한다. 기존 격리 검토DB는 이전 확인 기준 V24이며, 이번에는 중지 상태를 유지하고 개인/운영DB를 조회하거나 변경하지 않았다.

### 다중 프리셋 자동 순환 연동

자동 전환은 START와 같은 사용자 행 잠금을 사용하고 ACTIVE 세션이 있으면 보류한다. 정상 FINISH 또는 마지막 실제 운동 동기화 기준 24시간 AUTO_CLOSED 이후의 tick에서 현재 로컬 주차를 계산한다. 최초 START·InitialPlan·일정 출처는 유지한다. 전환 전 실제 START와 뒤늦게 도착한 동기화는 원래 세션의 근거로 처리한다. AUTO_CLOSED 뒤 기록은 위 만료·정정·복구 계약을 사용한다. 새 오프라인 START를 추가하지 않는다. [자동 순환 적용과 세션 보호](weekly-schedule.md#자동-순환-적용과-세션-보호)

## G5-1 ACTIVE 기간 보호

[디로딩 기간 계약](deload-periods.md)의 ACTIVE 기간과 COMPLETED 당일에는 새 일반 START와 원본 기록 정정·복구·논리 삭제·승인 해결/새 snapshot·FINISH를409 DELOAD_ACTIVE_PERIOD로 차단한다. 읽기와 기존 확정 요청의 무변경 ACK는 유지한다. 기간 시작과 일반 쓰기는 기존 사용자 잠금으로 직렬화한다. 디로딩 소속 세션은 아래의 공통 운동 경로를 사용한다. 다음 고정 로컬 날짜부터 일반 쓰기가 재개되며 무수행 취소는 즉시 재개된다. 기존 검토 DB와 서버에는 새 기능을 배포하지 않는다.

## G5-2 디로딩 세션

`POST /api/v1/workout-sessions/deload`는 기존 START의 인증·CSRF·세대·단일 ACTIVE·owner 잠금·ID 재시도를 공유한다. 요청에는 일반 `routineId` 대신 `planId`와 `expectedPlanVersion`이 필요하다. 기간과 세션 유형은 서버가 소속 계획으로 결정한다. `periodId`, `userId`, `initialPlan`, 승인 중량을 입력받지 않는다.

```json
{"id":"00000000-0000-0000-0000-000000000001","dataGeneration":"0","planId":"00000000-0000-0000-0000-000000000002","expectedPlanVersion":"0","editorDeviceId":"00000000-0000-0000-0000-000000000003","startedAt":"2026-11-08T03:00:00Z","workoutDate":"2026-11-08","startedTimeZone":"Asia/Seoul"}
```

성공201, 동일 START 재전송200이다. 서버가 현재 사용자·세대의 ACTIVE 기간, 살아 있는 미수행 계획, 계획 버전, 저장된 기간 시간대, 서버의 오늘/예정일/최대 10일을 대조한다. 다른 기간·계정·세대·오래된 계획을 사용할 수 없다. 시작 이후 해당 계획은 편집·이동·삭제를 차단한다. 이미 생성된 START의 재시도는 처음 봉인한 출처를 확인하며 새 계획 또는 변경된 버전으로 시작하지 않는다.

V28에서 일반 세션의 `source_routine_id`는 기존 owner/generation 루틴 FK와 필수 조건을 유지한다. 디로딩만 이 필드와 일반 일정 version이 NULL이며 V27의 `deload_period_id`/`deload_plan_id` 소속 FK를 사용한다. 상세·ACTIVE·종료 목록에서 디로딩이면 두 소속 ID를 반환한다. 일반 응답에 이 필드를 추가하지 않는다.

디로딩 `InitialPlan`은 schemaVersion2, `routineId=null`, `routineName`=임시 이름, `routineVersion`=계획 version이다. `deloadOrigin`에 기간/계획/버전·날짜·시간대·현재 목표와 최초 편성 출처를 봉인한다. 압축형의 각 최초 항목은 해당 원본 루틴/배치/버전을 유지한다. 사용자 편집으로 추가한 항목은 현재 계획에만 있고 원본에서 가져온 것으로 꾸미지 않는다. 일반 루틴을 복제하지 않으며 기존 일반 InitialPlan JSON 및 고정 요청 hash 형식은 보존한다. G6 이전 PWA는 이 null을 거절하므로 디로딩 세션의 일반 상세·ACTIVE 소비는 G6 통합 검증 범위다.

### 공통 진행, 평가와 이력

기존 snapshot·FINISH·takeover·AUTO_CLOSED·record 정정·논리 삭제·명시 복구를 그대로 사용한다. 디로딩 세트 문맥은 `TODAY_ONLY`만 사용하며 `approvalRequests`는 빈 배열이다. 일반 승인 문맥 및 승인 해결 시도는409 DELOAD_APPROVAL_FORBIDDEN이다. 편집 기기/epoch/revision·고정 요청 재전송·원본 장비/완료시각·종료 뒤 version 경계를 유지한다.

부분 수행도 FINISH 가능하다. 최초 평가 ruleVersion=`deload-record-only-v1`, `overallWeightPlanComplete=null`, 종목 advice=`UNAVAILABLE`, `DELOAD_SESSION` 사유다. 사후 평가 역시 일반 평가에서 제외한다. 실제 세트/유산소·근육 통계·전체 이력은 유지하며 일반 승인/성장 사건·일반 비교 후보·G3/G4 일반 계획에는 넣지 않는다.

`GET /api/v1/workout-sessions/deload/{period}`는 해당 사용자·현재 세대의 기간 소속 세션 상세만 반환한다. 무수행 명시 정리 세션은 전체 종료 목록과 이 조회에서 제외한다. 논리 삭제된 실제 기록은 기존대로 deletedAt을 표시하며 유효 실제 운동량에는 합산하지 않는다. 타 사용자/누락 기간은404 SESSION_NOT_FOUND다. GET은 query/body 없이 읽기 전용이다.

### 무수행 세션 정리

`POST /api/v1/workout-sessions/{id}/abandon`은 기간을 취소하지 않는다. 현재 편집 기기의 `dataGeneration`, `expectedVersion`, `editorDeviceId`, `writerEpoch`, `localRevision`, `confirm:true`가 일치하는 디로딩 세션에만 적용한다. 서버에 한 번이라도 실제 수행이 저장됐다면 논리삭제 이후에도409 DELOAD_ACTIVITY_RECORDED다. 성공 시 무수행 세션을 이력에서 제외하고 계획 잠금을 풀지만 봉인 출처를 지우지 않는다. 아직 ACTIVE면 AUTO_CLOSED로 정리하고 이미 종료됐다면 기존 FINISH/AUTO_CLOSED 사실은 유지한다. 동일 요청 재시도는 버전이나 시각을 다시 바꾸지 않는다.

클라이언트는 이 API 전 자신이 아는 미동기화 수행·고정 요청을 확인하고 정리 ACK가 오기 전 로컬 원본을 제거하지 않아야 한다. 서버는 아직 전송되지 않은 타 기기 사실을 추정하지 않는다. 동시 snapshot과 정리는 기존 owner 잠금/버전으로 직렬화한다. 24시간 AUTO_CLOSED는 공통 세션 정책이며 기간 종료는 별도 상태 전이다. 기간 자동 완료는 ACTIVE 세션이 있으면 기다린다. 기존 FINISH ACK와 명시적 복구는 기간 종료 후에도 유지된다.

실제 수행 표식 `first_deload_activity_at`은 유효 웨이트 세트 또는 양의 COMPLETED 유산소와 같은 트랜잭션에서 한 번 저장한다. START·SKIPPED·무수행 종료만으로는 설정하지 않는다. 정정·논리삭제·복구·종료 이후에도 처음 저장한 표식을 바꾸지 않는다. 기록 저장이 롤백되면 표식도 롤백된다. 기간 `hasActivity`는 이 표식의 존재이며 현재 화면의 세트 개수와 별개다.
