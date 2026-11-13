# 루틴 관리 온라인 API — Task 009

상태: V17 표준 운동 UUID 단일 계약. 웨이트·유산소 루틴 관리와 명시적 중량 승인을 제공한다. 개인 DB의 마지막 V17 적용 기록과 현재 미확인 상태는 [DB 안내](../engineering/local-postgresql.md)를 따른다.
기준은 [Task 009 v1.0](../tasks/task-009-redesign-v1.0.md), 유지되는 [Task 006 도메인 정책](../tasks/task-006-integrated-design-v0.3.1.md), 기존 [온라인 쓰기](workout-online-write.md)다.
Backend 세션과 Frontend/IndexedDB3·local schema2는 같은 표준 UUID 계약으로 전환했다. 당시 구현·연동 기록은 [Frontend3단계](../../../overload-pwa/docs/tasks/standard-exercise-catalog-frontend-phase-3-report.md)와 [실제 개발 환경4단계](../tasks/standard-exercise-catalog-dev-phase-4-report.md)를 따른다. 최신 코드의 종합 검증은6B에서 별도로 확인한다.

## HTTP 및 인증

기존 쿠키 세션과 쓰기의 `X-CSRF-TOKEN`을 사용한다. userId는 요청받지 않는다. 성공·명시적 오류는 no-store이며 기존 X-Request-ID를 유지한다.
아래 경로는 query를 받지 않는다. GET 본문도 거부한다. 쓰기 Content-Type은 application/json이고 DELETE도 JSON 본문이 필수다.

| 메서드 | 경로 | 성공 |
|---|---|---|
| POST | /api/v1/routines | 201, 상세 및 Location |
| GET | /api/v1/routines | 200, 현재 세대 비삭제 루틴 목록 |
| GET | /api/v1/routines/{id} | 200, 활성 배치 상세 |
| PUT | /api/v1/routines/{id} | 200, 저장한 활성 배치 상세 |
| DELETE | /api/v1/routines/{id} | 204 |
| GET | /api/v1/routines/{id}/removed-placements | 200, 같은 루틴의 삭제 배치 상세 |

UUID는 하이픈을 포함한 완전한 UUID 문자열이다. UUID 대소문자는 허용하되 응답은 정규형이다. dataGeneration·version·expectedVersion은 `0` 또는 선행 0 없는 양의 long 십진 문자열이며 상한은 9223372036854775807이다.
이름은 비공백·NUL 없음·유효 Unicode를 검사하고 원래 공백과 Unicode를 보존한다. 루틴 이름 중복을 허용하며 임의 길이 제한·trim·이름 UNIQUE는 없다. 유산소 이름은 기존 Java/JS UTF-16 길이 100 이하도 검사한다.
JSON 중복 키·미지원 필드·필수 필드 누락·부적절한 타입·null을 거부한다. 명시적으로 nullable인 manufacturerId/loadType·유산소 선택값·응답 deletedAt은 예외다.

## 요청 DTO

POST의 필수 필드:

```text
{id, dataGeneration, name, weightPlacements: WeightNew[], cardioPlacements: CardioNew[]}
WeightNew = {id, standardExerciseId, manufacturerId:UUID|null, loadType:LoadType|null, setCount, minReps, maxReps, initialWeightKg}
CardioNew = {id, plan: CardioPlan}
```

PUT의 필수 필드:

```text
{dataGeneration, expectedVersion, name, weightPlacements: WeightInput[],
 cardioPlacements: CardioInput[], weightApprovals: Approval[]}

WeightInput NEW = {mode:"NEW", ...WeightNew}
WeightInput EXISTING/RESTORE = {mode:"EXISTING"|"RESTORE", id, manufacturerId:UUID|null, loadType:LoadType|null, setCount, minReps, maxReps}
CardioInput = {mode:"NEW"|"EXISTING"|"RESTORE", id, plan: CardioPlan}
Approval = {placementId, nextApprovedWeightKg, occurredAt}
```

DELETE: `{dataGeneration, expectedVersion}`.

두 구성 배열과 승인 배열의 누락·null은 400이다. 빈 배열은 명시 입력이다. 최종 웨이트+유산소가 최소 하나 있어야 한다.
배열 순서가 저장 순서이며 position을 입력받지 않는다. 제출된 전체 구성에서 빠진 기존 활성 배치는 논리 삭제한다.
배치 ID는 요청의 두 배열 전체에서 중복될 수 없다. 같은 표준 운동을 여러 번 배치할 수 있으며 각 배치 ID·승인 계보는 독립이다.
EXISTING/RESTORE에는 표준 운동 ID·최초 중량·현재 승인 중량을 입력할 수 없다. 종목 교체는 제거+NEW다.
RESTORE는 같은 사용자·세대·루틴의 지정된 삭제 ID만 허용한다. 활성 ID를 RESTORE로 보내거나 삭제 ID를 EXISTING으로 보내면409다.
복원 시 계획 세트/목표 또는 유산소 계획은 명시 입력대로 적용하되 최초·승인 중량은 보존한다.

Approval 대상은 최종 구성에 포함된 EXISTING/RESTORE 웨이트다. NEW에는 최초 중량을 입력한다.
동일 대상의 중복 승인 입력은400이며, 삭제되는 배치를 승인할 수 없다.
occurredAt은 사용자가 루틴 저장으로 승인한 UTC 시각(`YYYY-MM-DDTHH:mm:ss[.SSS]Z`, 소수초 최대3자리)이다. 서버 recordedAt과 별도로 보존하며 충돌 우선순위에 사용하지 않는다.

CardioPlan의 모든 필드:

```text
{kind:"TREADMILL"|"BIKE"|"OUTDOOR"|"STAIRS"|"OTHER", name, targetSeconds,
 targetDistanceKm:Decimal|null, intensity:{mode:"SPEED"|"PACE",value:Decimal}|null,
 inclinePercent:Decimal|null, resistance:Decimal|null}
```

## 장비 선택과 보존

LoadType은 PLATE/STACK/DUMBBELL/BODYWEIGHT 또는 JSON null만 허용한다. manufacturerId/loadType은 NEW·EXISTING·RESTORE 모두 필수 nullable 키다. 표준 UUID는 공용 카탈로그를 참조하며 개인 정의 UUID를 받지 않는다. NEW는 활성 상태이며 근육 타겟·분할 검수를 모두 완료한 SETS_REPS 표준 운동을 선택할 수 있다. 이미 저장된 배치는 표준 운동의 활성·검수 상태가 이후 바뀌어도 편집·RESTORE·START할 수 있다.

제조사 신규 선택은 활성 카탈로그 ID만 허용한다. 현재 배치에 보존된 제조사 ID는 비활성화 후에도 동일 값 저장·복원을 허용한다. 없는/새로 선택한 비활성 제조사는400 INVALID_REQUEST다. 장비 변경은 배치 UUID·최초/승인 중량을 유지하며 승인 사건을 생성하지 않는다. 실제 변경만 routine.version을1회 올리고 동일 값 재저장은 기존 no-op이다. 표준 운동 변경은 제거+NEW를 사용한다. RESTORE는 저장한 장비를 기본값으로 명시 전송한다.

장비 편집으로 달라진 routine.version에도 기존 세션 승인 경합 규칙을 적용한다. 장비가 달라졌다는 이유로 승인값 초기화·TODAY_ONLY 강제·공식 평가 비교 키 확장은 하지 않는다.

## 수치 계약 — 이번 사용자 승인으로 확정

decimal은 JSON 문자열·Java BigDecimal·PostgreSQL NUMERIC(20,6)이다. 정규형은 `0`, `61.3`, `0.000001`처럼 지수·부호+·선행0·불필요한 후행0 없는 평문이다.
JSON 숫자·음수/음수0·NaN/Infinity·공백·소수점6자리 초과는400이다. UI 초안 정규화는 제출 전 수행하며 서버는 반올림하지 않는다.
API에서 먼저 범위·scale을 검증하여 DB NUMERIC의 암묵적 반올림에 의존하지 않는다. 실제 수행·환산값을 계획에서 생성하지 않는다.

| 값 | 범위 |
|---|---|
| initialWeightKg, nextApprovedWeightKg | 0..99999999999999.999999 kg |
| setCount, minReps, maxReps | JSON 정수 1..2147483647, minReps≤maxReps |
| targetSeconds | JSON 정수 1..86400초 |
| targetDistanceKm | 0.001..1000km 또는null |
| SPEED | 0.1..100km/h |
| PACE | 0.1..120분/km |
| inclinePercent | 0..50 또는null, TREADMILL만 |
| resistance | 1..100 또는null, BIKE만, 소수 허용 |

거리·강도는 TREADMILL/BIKE/OUTDOOR에서만 허용하며 다른 종류에는null이어야 한다. 부적합 값을 자동 제거하지 않는다.
분→정수 초 변환은 기존 UI 정책의 책임이다. 서버는 정수 초만 받는다. planned setCount/min/max 크기에 비례한 예정 세트 생성·반복은 없다.

## 응답

목록: `{userId,dataGeneration,routines:[{id,name,version}]}`, UUID 오름차순.
상세/삭제 후보: `{userId,dataGeneration,id,version,name,weightPlacements,cardioPlacements}`.
웨이트 항목은 `{id,standardExerciseId,exerciseName,manufacturerId,loadType,setCount,minReps,maxReps,initialWeightKg,approvedWeightKg,deletedAt}`다.
유산소 항목은 `{id,plan:CardioPlan,deletedAt}`다. 정상 상세의 deletedAt은null이다.
두 배열은 position·UUID 오름차순이다. 삭제 후보에는 보존된 계획·중량·삭제 시각을 반환하며 이름으로 후보를 자동 선택하지 않는다.
승인값은 서버 응답으로 확인한다. 응답은 Entity가 아닌 DTO이며 decimal·counter의 문자열 정밀도를 유지한다.

## 중복·버전·트랜잭션

- POST는 고정 UUID로 생성하고 version=0이다. UUID 점유·삭제 ID 재사용은409다. 동시 PK 경쟁도409이며 덮어쓰지 않는다. 응답 유실 후 고정 UUID GET으로 확인한다.
- PUT은 현재 기대 버전이 먼저 일치해야 한다. 이전 버전의 동일 본문도409다. 영구 영수증을 보관하지 않는다.
- 현재 버전의 실제 무변경 PUT은200이며 version/updated_at/승인 사건을 바꾸지 않는다.
- 실제 변경은 여러 자식/승인 변경이 있어도 부모 version을 정확히1 올린다. 복원·논리 제거도 변경이다.
- DELETE는 활성 루틴에서 버전을 확인하고 논리 삭제+version1 증가. 본인 현재 세대의 이미 삭제된 루틴은204, 추가 변경 없음. GET/PUT은404다.
- 버전 overflow는409 WRITE_CONFLICT이며 전체 롤백한다. 자동 재시도·자동 병합은 없다.

단일 workoutTransactionManager/JPA 트랜잭션 안에서 소유자→루틴→종목 UUID 순 잠금으로 처리한다. G1부터 PUT/DELETE는 START와 직렬화하기 위해 users FOR UPDATE를 먼저 사용하고, 쓰기 격리 수준은 READ_COMMITTED다. 신규 POST는 기존 users FOR SHARE를 유지한다. routine FOR UPDATE 및 자식 변경·사건·부모 버전의 원자성은 유지한다.
루틴 version은 행 잠금 아래 서비스가 한 번 증가시키는 단일 카운터다. 이 Entity에는 @Version을 추가하지 않아 수동 증가와 이중 적용되지 않는다. 기존 종목/설정 @Version은 유지한다.
부분 UNIQUE의 순서 교환은 겹치지 않는 양수 임시 위치로 flush 후 최종 위치로 저장한다. 숫자 overflow를 검사하며 임시 상태가 별도로 커밋되지 않는다.
조회는 기존과 같은 readOnly REPEATABLE_READ 트랜잭션에서 소유자·세대·DTO 전체를 일관되게 완성한다.

신규 배치는 최초·승인 중량을 같은 값으로 초기화하되 두 컬럼을 구분하며 최초값은 이후 변경하지 않는다.
명시 승인에서 값이 달라질 때만 이전/다음 승인 중량·당시 이름·사용자 발생 시각·서버 기록 시각·ROUTINE_EDITOR 출처를 불변 사건으로 기록한다.
동일 값·복원만으로는 사건이 없으며 루틴 화면 승인은 이미 시작한 세션의 상태를 변경하지 않는다.

## 보존 및 후속 세션 경계

루틴 삭제는 부모 deleted_at만 설정하며 자식·승인 사건은 보존한다. 활성 여부에는 부모 삭제도 포함한다.
FK에는 기록 삭제 cascade가 없다. 승인 사건은 Hibernate Immutable 및 모든 필드 updatable=false이며 update/delete API가 없다.
출처 UUID·최초/승인 중량은 유지한다. 미래 세션은 새 세션 종목 ID와 출처 배치 ID를 구분하고 시작 당시 전체 계획을 복사해야 한다.
START는 같은 users→routine 잠금 순서와 users FOR UPDATE/routine FOR SHARE, READ_COMMITTED 쓰기 트랜잭션에서 읽기·InitialPlan·세션 생성을 원자 처리한다. 경합 실패는 전체 실패로 처리한다. 구현 범위는 [세션 계약](workout-sessions.md)을 따른다. 루틴과 세션 모두 V17 단일 표준 계약을 사용한다.
루틴 version을 공식 비교 계보에 추가하거나 이름/순서 변경으로 과거 수행을 수정하지 않는다.

2B의 [주간 일정·계획 이력 계약](weekly-schedule.md)에 따라 생성·실제 편집·명시 승인·세션 승인 반영·삭제의 최종 계획을 같은 트랜잭션의 `routine_plan_history`에 기록한다. 무변경 쓰기는 이력을 늘리지 않는다. 논리 삭제는 모든 저장 프리셋에서 해당 루틴의 배정을 해제하고, 영향을 받은 프리셋 버전을 갱신한다. 활성 프리셋 배정이 달라지면 실제 적용 이력도 함께 추가한다. 첫 예정 START/이미 지난 날짜의 계획과 실제 기록을 지우지 않는다. 이력 또는 일정 기록 실패 시 루틴 삭제도 롤백한다. 원래 세션 InitialPlan·승인 사건·현재 승인 중량 계약은 유지한다.

## 오류

본문은 `{code}`뿐이다. 401 AUTHENTICATION_REQUIRED와403 CSRF_INVALID/ACCESS_DENIED는 기존 Security 처리를 유지한다.
400 INVALID_REQUEST, 404 ROUTINE_NOT_FOUND/PLACEMENT_NOT_FOUND/EXERCISE_NOT_FOUND, 409 DATA_GENERATION_MISMATCH/ROUTINE_VERSION_CONFLICT/ROUTINE_ID_CONFLICT/PLACEMENT_ID_CONFLICT/PLACEMENT_STATE_CONFLICT/ACTIVE_SESSION_EXISTS/WRITE_CONFLICT를 사용한다.
소유권 없는 대상은 미존재와 같은404다. 사전 조회 뒤의 동시 PK/UNIQUE 경쟁·교착·직렬화 충돌은409 WRITE_CONFLICT다.
확인된 연결·timeout 오류는503 WORKOUT_DATA_UNAVAILABLE, 예상 밖 실패는500 WORKOUT_DATA_WRITE_FAILED다. SQL/예외 원문은 본문에 노출하지 않는다.
405/415 등 프로토콜 오류와 필터·컨테이너 경계 전체를 code-only로 보장하지 않는다.

## DB 적용 상태

V11은 위 네 테이블만 추가하며 V1~V10을 수정하지 않는다. 기존 계정·종목·설정 재시드나 변환은 없다.
V11 구현 당시 검증 대상은 격리 Testcontainers PostgreSQL뿐이었다. 이후 개인 로컬 V11 적용·기존 데이터 보존은 사용자 확인 사항이다. 신규 V12는 개인 로컬에 적용하지 않으며 실제 DB 대상 앱을 실행하지 않는다.

위 적용 설명은 V11 단계 당시 기록이다. 이후 V17 적용/V18~V22 미적용이라는 마지막 보고가 있으며6A에서는 개인 DB 상태를 재확인하지 않았다. [DB 안내](../engineering/local-postgresql.md)에 따라 격리 migration 검증과 실제 DB 적용 승인을 구분한다.
## T02-B V23 추가 계약 (2026-11-06)

- 기존 루틴 CRUD endpoint, 사용자 소유권, dataGeneration, expectedVersion, CSRF와 트랜잭션을 유지한다.
- 생성/수정 요청에 nullable `tag`를 추가한다. 허용값은 PUSH/PULL/LEGS/FRONT/BACK/FULL/UPPER/LOWER/CARDIO/MIXED 하나다. 생성 생략은 null, 수정 생략은 기존값 보존, 명시 null은 해제다. 잘못된 값은400이며 추정/기본 태그를 부여하지 않는다.
- 목록/상세에 `tag`, 기존 저장 시각 `createdAt`을 응답한다. 목록은 created_at DESC, id DESC이며 수정일로 정렬하지 않는다. 과거 저장된 계획 이력에 필드를 백필하지 않는다.
- V33 이후 신규 유산소 배치는 선택 API에서 받은 `standardExerciseId` 및 `activityId`를 Plan에 함께 전송한다. 서버가 현재 공개 상태, CARDIO 기록 방식, 활성 설정, 측정 종류와 표준 운동명을 검증한다. 기존 배치 편집·복원은 저장된 식별과 당시 이름을 유지한다. 새 종목을 선택하려면 기존 배치를 제거하고 새 ID로 추가한다.
- 기존 Plan의 표준 UUID와 활동 ID는 없을 수 있다. 없던 키는 응답에서 생략하며 저장된 기존 계획에 ID를 추정해 채우지 않는다.
- 카탈로그는 RUNNING/WALKING/CYCLING과8개 세부 종목 관계를 저장한다. 기존 배치ID/세션ID는 독립적이다. 기존 종목을 이름으로 매핑하거나 과거 기록·snapshot/hash를 다시 쓰지 않는다.
- V23은 routines.tag와 routine_cardio_placements.activity_id의 nullable 추가 및 독립 catalog 테이블/관계만 추가한다. V1~V22 이력은 변경하지 않는다. 현재 적용은 격리 보존 검토 DB만이며 개인/운영 DB에는 적용하지 않는다.
- 루틴과 Weekly Schedule은 별도 API다. UI는 루틴 성공/일정 실패를 알리고 고정 요청·expectedVersion·현재 assignments로 일정만 확인/재시도한다. 다른 버전의 일정을 자동 병합하지 않는다. G2의 저장 즉시 적용과 ROUTINE-03 교체 확인을 사용한다.

## G1 원본 관리 보호 (2026-11-06)

현재 세대 ACTIVE 세션의 sourceRoutineId와 같은 원본에 대해 실제 종목 추가/제거/복원/순서·세트/반복 목표·장비·유산소 계획 변경과 논리 삭제를409 ACTIVE_SESSION_EXISTS로 거절한다. 다른 원본의 구성 관리나 신규 루틴 생성은 이 guard로 막지 않는다. 다만 다른 루틴 삭제가 최신 주간 배정의 제외를 발생시키면 같은 ACTIVE 주간 일정 guard로409이며, 삭제·계획 이력도 함께 롤백한다. 배정에 영향 없는 다른 루틴 삭제는 허용한다. 이름/태그만 변경하거나 구성 no-op/승인 중량만 처리하는 요청은 기존 계약대로 허용한다. 실제 구성 변경과 승인을 함께 보내면 전체 요청을 롤백한다. 먼저 소유권·세대·expectedVersion을 검증하며, stale 버전은 no-op에도 충돌한다.

정상 수행·FINISH/ACK·세션 임시 계획·승인 해결·종료 후 정정/명시 복구의 계약은 유지한다.24시간 만료는 기존 expiry/세션 접근 흐름이 확정하며 관리 API가 별도 만료나 타 기기 오프라인 감지를 구현하지 않는다. 관련 근거와 G2 이후 계약 차이는 [G0~G1 보고서](../tasks/weekly-schedule-g0-g1-report.md)에 정리한다. 이 G1 당시 기록과 달리 이후 G2~G3 코드는 V24와 즉시 일정 계약으로 전환했다. 실제 DB 적용은 별도 승인 전까지 하지 않으며 [현재 일정 계약](weekly-schedule.md)을 따른다.

## G5-1 ACTIVE 기간 보호

[디로딩 기간 계약](deload-periods.md)의 ACTIVE 기간과 COMPLETED 당일에는 루틴 생성·수정·삭제를 409 DELOAD_ACTIVE_PERIOD로 차단한다. 읽기와 기존 확정 요청의 무변경 ACK는 유지한다. 기간 시작과 일반 쓰기는 기존 사용자 잠금으로 직렬화하며 [G5-2 임시 계획](deload-periods.md#임시-계획-관리--g5-2)은 별도 저장하고 일반 루틴 목록을 변경하지 않는다. 완료 다음 로컬 날짜 또는 무수행 취소 직후에 일반 루틴 쓰기를 재개한다. 기존 검토 DB와 서버에는 새 기능을 배포하지 않는다.

## 일반 운동 화면의 소비 확인 — 2026-11-13

PWA15a0f6b는 GET 목록/상세의 안정 배치ID·표준 운동ID·승인 중량·장비·목표를 START와 이후 세션 InitialPlan의 출처로 사용한다. 운동 중 순서·세트·추가/교체는 해당 세션 AppliedPlan/snapshot에 저장하며 루틴 PUT을 몰래 호출하지 않는다. 명시 진행 중량 승인은 기존 세션 승인 요청/결과와 충돌 해결 계약을 재사용한다. 제조사별 표준 운동 복제가 아니라 배치의 manufacturerId/loadType을 사용한다. 기존 DTO·nullable·버전·오류는 이 문서가 소유하며 이번 연동에서 새 루틴 엔드포인트/스키마를 만들지 않았다. [세션 소비](workout-sessions.md#일반-운동-pwa-소비-확인--2026-11-13)
