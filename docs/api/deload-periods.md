# 디로딩 기간·임시 계획 API — G5

2026-11-08. [제품 v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md), [G5-1 기록](../tasks/deload-g5-1-report.md), [G5-2 기록](../tasks/deload-g5-2-report.md), [G5-3 기록](../tasks/deload-g5-3-report.md)을 따른다. 디로딩 운동은 [공통 세션 계약](workout-sessions.md#g5-2-디로딩-세션)을 사용한다.

## 인증·읽기·쓰기

모든 API는 principal의 사용자와 현재 dataGeneration만 처리한다. GET은 read-only REPEATABLE_READ이며 본문을 허용하지 않으며 쿼리는 이력의 `offset`과 옵션의 `presetId`만 지원한다. POST·PUT·DELETE는 application/json·세션 쿠키·X-CSRF-TOKEN이 필요하고 query를 허용하지 않는다. 미지원/누락 필드·중복 JSON 키·비정규 Counter·조작된 userId/epoch/승인 중량/시작 시각은 거절한다. 성공200, 성공/오류 모두 no-store, 오류는 code-only다.

| Method/경로 | 결과 |
|---|---|
| GET /api/v1/deload-periods/eligibility | userId/dataGeneration, eligibility, activePeriod |
| GET /api/v1/deload-periods/options?presetId={UUID} | 선택 원본의 ID·버전 및 method/split/suggestedDates/available/issues. 생략 시 현재 활성 프리셋 사용 |
| POST /api/v1/deload-periods/preview | 자격·불변 출처·선택 날짜·편성 결과·previewToken |
| POST /api/v1/deload-periods | 자격/출처 재검증 후 ACTIVE 기간과 최초 임시 계획 원자 생성 |
| GET /api/v1/deload-periods/current | userId/dataGeneration/period. ACTIVE 또는 고정 시간대 기준 완료 당일의 COMPLETED를 반환한다. 그 밖에는 period=null이다. |
| GET /api/v1/deload-periods/{period} | 현재 세대의 기간 상세. 다른 사용자·세대의 기간이나 없는 ID는 404. |
| GET /api/v1/deload-periods/history?offset=0 | 종료 이력 요약 최대 50개를 closedAt과 ID 내림차순으로 반환한다. 응답에 userId, dataGeneration, periods, nextOffset이 있다. |
| POST /api/v1/deload-periods/{period}/complete | 직접 완료 또는 마감 경과 시 자동 완료와 같은 경계로 종료. |
| POST /api/v1/deload-periods/{period}/cancel | 실제 수행 없는 기간을 취소. |

분석 설정이나 시간대 부재를 성공한 기본값으로 채우지 않는다. 시간대 미확정이면 자격 조회에 AWAITING_TIME_ZONE을 반환하며 옵션/미리보기/시작은 거절한다. 분석 OFF와 누적0은 자격 차단 이유가 아니다.

## 자격

eligibility는 eligible/timeZone/today/recentActual/afterPreviousCompletion/todayClear/activeSessionClear/reasons/revision을 제공한다. activePeriod는 네 조건과 별도 상태 제약이다.

1. today-7~today-1의 저장된 workout_date에 실제 일반 수행 존재.
2. 최근 COMPLETED 디로딩이 있으면 그 closed_at 이후 새 실제 일반 수행 존재. 완료와 같은 날짜라도 실제 수행 시각이 완료 후라면 인정하며, 시작하려는 오늘의 실제 수행은 3번에서 차단한다.
3. 오늘의 실제 일반 수행 없음.
4. ACTIVE 일반 세션 없음. 수행 없는 ACTIVE도 차단한다.

실제 수행은 미삭제 세션의 미삭제 WEIGHT performed_set 또는 미삭제 COMPLETED cardio_result의 active_ms>0이다. START만 있음·무수행 종료·SKIPPED·논리 삭제·명시적 디로딩 소속은 인정하지 않는다. 유산소 목표 완료율과 실제 활동 존재는 별개다. 현재 분석 시간대의 오늘과 저장된 운동 날짜를 사용하며 168시간으로 환산하지 않는다.

reasons: RECENT_GENERAL_ACTIVITY_REQUIRED / POST_COMPLETION_ACTIVITY_REQUIRED / TODAY_GENERAL_ACTIVITY / ACTIVE_GENERAL_SESSION / AWAITING_TIME_ZONE. revision은 자격 증거의 서버 해시이며 클라이언트가 직접 지정하지 않는다.

## 미리보기와 시작

```json
{"dataGeneration":"0","presetId":"11111111-1111-4111-8111-111111111111","method":"THREE","split":"EQUAL","dates":[]}
```

method: KEEP/THREE/TWO/REST, split: ORIGINAL/PPL/FRONT_BACK/EQUAL/REST. dates=[]면 추천 날짜를 사용한다. KEEP은 원본 요일을 시작일부터 첫7일에 대응시키며 THREE는 +0/+2/+4, TWO는 +0/+3이다. 사용자 날짜는 해당 방식의 슬롯 개수와 일치해야 하고, 서로 달라야 하며 시작일~+6 안에 있어야 한다. 배열 순서가 각 분할의 날짜 대응이다. REST의 날짜는 빈 배열이다.

원본 4~6일: KEEP/THREE/TWO/REST, 3일: KEEP/TWO/REST, 1~2일: KEEP/REST, 0일: REST. 7일 편성은 현행 정책에 정의되어 있지 않아 REST만 제공한다. 자동으로 6일로 줄이지 않는다.

preview 응답에는 source(프리셋 ID·내용 버전·배정 요일·루틴 Detail·승인 중량·표준 운동 PRIMARY/jointType), choice(정규화 날짜), generated(plans/issues), previewToken이 있다. plans의 weights/cardio는 original과 감량 target을 함께 담는다. 자격이 없어도 미리보기는 가능하며 시작 가능 여부를 성공한 것처럼 표시하지 않는다. issues가 있으면 해당 선택은 시작 불가다. options.available은 편성 가능 여부이며 네 자격/ACTIVE 기간 부재와는 별개다. 빈 운동일은 임시 계획으로 만들지 않으므로 선택 슬롯 수보다 실제 생성 계획이 적을 수 있고, 부족분을 새 운동으로 채우지 않는다.

```json
{"dataGeneration":"0","requestId":"00000000-0000-0000-0000-000000000001","presetId":"11111111-1111-4111-8111-111111111111","previewToken":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","method":"THREE","split":"EQUAL","dates":["2026-11-08","2026-11-10","2026-11-12"]}
```

옵션·미리보기·시작의 `presetId`는 선택 필드이며 생략하면 현재 활성 프리셋을 읽는다. 토큰은 서버 원본·자격 revision·오늘·정규화 선택을 토큰 형식 버전 2와 함께 SHA-256으로 결합한다. 토큰은 권한을 부여하지 않으며 최종 시작 시 동일 소유자의 출처/자격을 다시 읽는다. 날짜·프리셋 배정·루틴 개정과 승인 중량·분류·자격 증거가 바뀌면 재미리보기가 필요하다. 명시 선택한 원본의 표시 순번이나 포함 여부만 바뀌면 토큰은 유지된다. 원래 미리보기 요청과 같은 dates 표현 또는 정규화된 dates를 첫 시작에 사용할 수 있지만, 멱등 재시도는 첫 시작의 정확한 요청 표현을 유지한다.

시작은 사용자 FOR UPDATE로 일반 START·일정/원본 쓰기와 직렬화한다. 배정 원본/표준 분류는 공유 잠금으로 검증한다. requestId는 사용자/세대 내 유일하고 같은 ID+같은 요청은 기존 기간을 반환하며 새 기간을 만들지 않는다. G5-2 계획 변경이 적용된 뒤라면 같은 기간의 현재 표현을 반환한다. 다른 요청 내용의 재사용은 충돌이다. ACTIVE 유일 인덱스가 최종 중복을 차단한다. 자격·출처를 재확인한 뒤 기간과 모든 자식 계획을 한 트랜잭션에서 저장하며 일부 생성 실패는 전체 rollback한다.

period에는 id/userId/dataGeneration/version/status/startedAt/startDate/timeZone/recommendedEndDate/maximumEndDate/method/split/sourceHash/source/plans가 있다. version은 십진 문자열이며 최초0이다. 권장 종료일=start+6, 최대 종료일=start+9다. 자동 종료는 아래 G5-3 전이를 따른다. 원본 routine/assignments/승인 중량을 수정하지 않으며, 원본 JSON·소속·요청·시작 경계는 DB UPDATE trigger로 보존한다.

## 자동 편성 경계

압축형은 현재 선정된 전체 PRIMARY UUID 집합에 대해 중복 없음, 부분 중복, 완전 중복 순으로 후보를 비교한다. 같은 등급에서는 원본 순서를 사용한다. 완전 중복은 비어 있는 명시적 분할 또는 균등형 운동일을 채울 필요가 있을 때 후순위로 사용한다. 한 운동은 한 날에만 배치한다. 균등형은 라운드로빈을 유지하고 최종 일별 순서는 COMPOUND→ISOLATION, 같은 관절 유형은 원본 순서다.

PPL과 전후면은 운동별 `ppl_split`과 `front_back_split`을 사용한다. PPL 순서는 원본 요일의 첫 메인에 지정된 분할의 최초 등장 순서다. NULL 운동은 정규 분할을 성립시키는 근거로 쓰지 않는다. 정규 분할이 성립하면 원본 요일 메인의 분할을 우선하고, 여유가 없거나 메인 분할이 없으면 명시적 운동을 위한 자리를 보존하며 종목 수가 적은 날에 한 번 배치한다. 동률은 편성 날짜 순서다. 필요한 분할이나 순서가 성립하지 않으면 해당 옵션은 `ready=false`와 사유를 반환한다. 균등 편성이 가능한 경우 별도의 `EQUAL` 옵션으로 선택한다.

새 미리보기의 출처에 운동별 분할과 전체 PRIMARY를 포함한다. START 전 근거가 바뀌면 기존 토큰 검증을 적용한다. 시작한 기간의 봉인 출처·최초/현재 계획에는 이후 변경을 소급 적용하지 않는다.

2026-11-08 사용자 확정 경계:

- 압축 하루 종목 상한: 3일형 min(floor(원본 평균 종목수+1),6), 2일형 min(floor(평균+2),7). 세트 상한 floor(원본 평균 세트×0.7), 1 미만일 때만 최소1 예외. 우선순위 낮은 종목의 세트를 최소1까지 줄인 후에도 넘으면 해당 종목을 제외하며 반복 수를 다시 늘리지 않는다.
- 웨이트 세트 ceil(원본/2), 최소1. 홀수 세트의 반복 범위 각 경계는 roundHalfUp(원본 세트×반복/(2×새 세트)), 최소1. 3×6~10→2×5~8, 3×10→2×8, 5×10→3×8.
- 승인 중량 우선×0.85 후 PLATE/STACK 5kg, DUMBBELL 2kg, 미지정1kg 내림. 0이 되면 원래 중량, BODYWEIGHT 기본 중량 유지.
- 유산소 시간×0.5를 분 단위 HALF_UP, SPEED×0.85 소수 첫째 자리 HALF_UP. 압축형은 원본 최초 유산소를 하루 최대1개 사용한다. 원본 유산소1일이면 첫 허용 슬롯, 2일 이상이면 두 슬롯이며 PPL의 LEGS 제외, Legs 없는 균등형은 첫 두 슬롯이다. KEEP은 원본 배정 유지.
- PRIMARY/jointType 부재: CLASSIFICATION_REQUIRED, 필수 분할 부재: SPLIT_UNAVAILABLE, PPL 메인 순서 불명: SPLIT_ORDER_UNRESOLVED. PACE·거리·경사·저항: CARDIO_TARGET_POLICY_REQUIRED. 감량 시간0분: CARDIO_TIME_ROUNDS_TO_ZERO. 해당 편성만 보류하며 REST는 계속 지원한다. 무추정·원본 보존 원칙을 유지한다.

## 기간 중 기존 기능 보호

ACTIVE 기간 중 새 일반 START, 원본 루틴 생성/수정/삭제, 주간 일정 저장, 일반 기록 정정/복구/논리삭제/승인 해결 및 새 일반 snapshot/FINISH 쓰기는409 DELOAD_ACTIVE_PERIOD다. 이미 확정된 동일 요청 ACK와 일반 조회는 유지한다. 소속이 디로딩인 세션은 공통 운동 쓰기를 사용하며 일반 승인·성장 평가에서는 제외한다. 읽기와 분석 ON/OFF는 허용하고 OFF/새 epoch가 기간을 종료하지 않는다.

G3는 기간 날짜에 새 MISS를 만들거나 기존 최초 MISS를 수정하지 않고 cursor만 전진할 수 있다. 정지된 과거 dirty는 그대로 보존하며 반복 검사 대상으로 재선택하지 않는다. G4는 ON 평가 구간과 ACTIVE 기간이 겹치는 주를 DELOAD_PERIOD 사유로 PENDING 처리한다. N0 RESET/ADD를 생성하지 않고 후속 누적 공개를 막는다. GET은 가능한 마지막 확정 prefix와 PAUSED/blockedAt/추천false를 반환한다. 더 이른 보류가 있으면 그 기존 상태가 우선이다. OFF/시간대 부재도 기존 우선순위다.

형식 오류는 `400 INVALID_REQUEST`다. 옵션·미리보기에서 현재 사용자·세대에 원본이 없으면 `404 DELOAD_SOURCE_PRESET_NOT_FOUND`다. 시작 때 원본이 바뀌거나 삭제되면 `409 DELOAD_PREVIEW_STALE`이다. 기타 `409` 오류는 `DATA_GENERATION_MISMATCH`, `DELOAD_ACTIVE_PERIOD`, `DELOAD_REQUEST_CONFLICT`, `DELOAD_NOT_ELIGIBLE`, `DELOAD_PLAN_REQUIRES_POLICY`, `DELOAD_SOURCE_CHANGED`, `AWAITING_TIME_ZONE`이다. DB 경합은 `409 WRITE_CONFLICT`, 데이터 접근 장애는 `503 DELOAD_DATA_UNAVAILABLE` 또는 `500 DELOAD_DATA_FAILED`다. 설정 누락 오류는 분석 API와 같다.

## 임시 계획 관리 — G5-2

`period`의 `currentMethod`와 `currentSplit`은 현재 방식이며 `method`/`split`과 `source`는 최초 출처다. ACTIVE의 `plans`는 살아 있는 계획만 담는다. 각 원소는 `id`, `position`, `plan`(생성 당시 불변 편성), `version`, `plannedDate`, `current`(현재 label/weights/cardio), `deletedAt`을 제공한다. 종료된 기간의 응답에는 논리 삭제된 계획도 포함한다. `hasActivity`는 소속 세션에 첫 수행 표식이 하나라도 있음을 뜻하며 논리 삭제 후에도 유지된다.

| Method/상대 경로 (`/api/v1/deload-periods`) | 추가 입력 및 결과 |
|---|---|
| PUT /{period}/plans/{plan} | `name`, `weightPlacements`, `cardioPlacements`. 전체 현재 계획 교체. 기존 루틴 생성의 항목 형식과 단위 검증을 공유하며 여기서 `initialWeightKg`는 임시 목표 중량이다 |
| PUT /{period}/plans/{plan}/date | `date`(ISO 로컬 날짜), `swapPlanId`(빈 날짜면 null). 날짜 이동 또는 두 계획의 원자 맞교환 |
| DELETE /{period}/plans/{plan} | 계획을 논리 삭제. 삭제 날짜는 휴식이며 기간이나 다른 계획은 자동 변경하지 않는다 |
| PUT /{period}/method | `method`, `split`, `dates`. 실제 수행 및 진행 세션이 없고 남은 날짜가 맞을 때 최초 source로 새 편성을 생성 |

모두 `dataGeneration`, `expectedVersion`, `requestId`가 공통 필수다. `expectedVersion`은 기간 `version`, START의 `expectedPlanVersion`은 선택한 계획의 `version`이다. 쿠키·CSRF·JSON·정확한 필드 집합을 확인한다. 서버가 현재 사용자/세대의 ACTIVE 기간과 소속 계획을 읽고 owner FOR UPDATE에서 START/쓰기와 직렬화한다. 변경된 계획 및 기간 버전을 증가시킨다. 마지막 변경의 동일 requestId·내용은 추가 쓰기 없이 현재 응답을 반환하고, 다른 내용은 DELOAD_REQUEST_CONFLICT다. 이후 변경이 이미 적용되었다면 오래된 expectedVersion을 거절해 중복 반영을 막는다. 별도 요청 영수증 목록은 없다.

```json
{"dataGeneration":"0","expectedVersion":"2","requestId":"00000000-0000-0000-0000-000000000002","date":"2026-11-17","swapPlanId":null}
```

맞교환은 대상 날짜의 실제 소속 계획 ID와 `swapPlanId`가 일치해야 한다. 두 계획 모두 편집 가능해야 하며 두 날짜가 함께 저장되거나 모두 롤백된다. 오늘 다른 계획 선택은 현재 오늘 계획을 명시해 맞교환한다. 빈 날짜로의 이동과는 구분한다. 대상 날짜는 기간 시간대의 오늘 이후이며 시작일~+9 안이다. 이미 시작해 유효한 세션이 연결된 계획은 날짜·내용·삭제가 동결된다. 명시적으로 무수행 정리한 세션의 불변 InitialPlan은 남지만 해당 계획은 다시 수정/실행할 수 있다.

자동 생성 날짜는 최초 7일이다. 첫 7일 중 방식 변경은 아직 실제 수행이 없고 유효 세션이 없어야 하며 선택한 슬롯 전체가 남은 최초 7일 범위에 들어가야 한다. 8~10일에는 기존 미수행 계획 이동·수정·삭제는 가능하며 새 자동 계획은 생성하지 않는다. 수행 전 REST 전환은 빈 계획만 남기며 새 계획을 만들지 않는다. 모든 계획이 삭제되어도 기간은 ACTIVE이고, 11일차에 이 API가 기간을 완료하지 않는다. 직접 편집에는 감량을 다시 적용하지 않는다. 신규 웨이트 배치는 [표준 카탈로그의 신규 선택 조건](standard-exercise-catalog.md)을 따른다. 같은 배치 ID와 표준 운동 UUID로 저장된 웨이트는 운동의 활성·검수 상태가 바뀌어도 편집·시작할 수 있다. V33 이후 새 유산소 배치는 공개 표준 운동과 활성 기록 설정을 함께 선택한다. 임시 계획에 이미 저장된 유산소는 당시 UUID·이름·측정 종류를 보존하며 설정이 바뀌어도 편집·시작할 수 있다.

추가 충돌 코드는 다음과 같다.

- 기간: `DELOAD_PERIOD_NOT_FOUND`, `DELOAD_PERIOD_NOT_ACTIVE`, `DELOAD_PERIOD_WINDOW_CLOSED`, `DELOAD_PERIOD_VERSION_CONFLICT`
- 계획: `DELOAD_PLAN_NOT_FOUND`, `DELOAD_PLAN_DELETED`, `DELOAD_PLAN_FROZEN`, `DELOAD_PLAN_VERSION_CONFLICT`, `DELOAD_EXERCISE_UNAVAILABLE`
- 날짜·방식: `DELOAD_DATE_CONFLICT`, `DELOAD_DATE_OCCUPIED`, `DELOAD_METHOD_FROZEN`, `DELOAD_GENERATION_WINDOW_CLOSED`, `DELOAD_INSUFFICIENT_DATES`

## 저장과 후속 책임

V27 기간·임시 계획·소속 FK 위에 V28이 가변 `current_plan`, 논리 삭제, 맞교환 가능한 살아 있는 날짜 유일성, 현재 방식과 마지막 변경 키를 추가한다. 최초 `source_snapshot`/`initial_plan`은 변경하지 않는다. 세션 출처는 일반의 기존 scoped routine FK 또는 디로딩의 scoped period/plan FK로 구분한다. 최초 실제 수행 표식은 세션에만 저장하며 기간별 존재 여부를 계산한다. G3/G4는 일반 사실만 사용하고 전체 운동량/근육 통계에는 유효한 디로딩 수행도 포함한다.

## 완료·취소·자동 종료 — G5-3

두 명령은 동일한 요청 구조를 쓴다.

```json
{"dataGeneration":"0","expectedVersion":"2","requestId":"00000000-0000-0000-0000-000000000001"}
```

- **직접 완료.** 시작 직후나 7일 전이라도 수행 유무와 무관하게 완료를 요청할 수 있다. ACTIVE 운동이 남아 있으면 `409 ACTIVE_SESSION_EXISTS`다. 마감 전 직접 완료의 상태는 COMPLETED, `endReason=MANUAL_COMPLETION`이다. 완료 당일은 고정 시간대 기준 디로딩 날짜로 유지한다. 다음 로컬 날짜부터 일반 루틴·일정·기록 쓰기와 START가 열린다. 새 START는 서버 현재 시각과 요청의 실제 시작 시각을 기간의 고정 시간대로 검사한다. 여행 중의 저장 수행일은 세션 계약을 따른다.
- **무수행 취소.** 어떤 연결 세션에도 영속 활동 표식이 없어야 한다. 논리 삭제는 표식을 없애지 않는다. START만 한 세션은 실제 활동이 없지만, 세션이 남아 있다면 [명시적 abandon](workout-sessions.md#g5-2-디로딩-세션)으로 원본과 ACK를 확인해야 한다. 취소는 CANCELLED, `endReason=USER_CANCELLED`이며 성공 즉시 일반 기능을 연다. 취소 전에 시작한 요청으로 보호 날짜를 새 일반 기록으로 등록할 수 없다.
- **자동 완료.** 7일은 권장 기간이다. 10일차 종료는 저장된 IANA 시간대의 11일차 첫 순간으로 계산한다. 기존 일별 검사 tick이 기간 종료 → 자동 순환 → G3 → G4 순으로 처리한다. `closedDate`는 10일차이고 `closedAt`은 그 날짜의 마지막 마이크로초다. `processedAt`은 실제 처리 시각이다. 마감 이후에도 ACTIVE 세션이 있으면 일반 START를 잠근 채 재시도한다. 기존 FINISH 또는 공통 24시간 AUTO_CLOSED 이후에 종료할 수 있다. 지연으로 일반 접근이 잠겼던 11일차 이후의 온전한 날짜에도 소급 MISS를 만들지 않는다. 마감 이후 취소는 `DELOAD_AUTO_COMPLETION_DUE`다. 마감 이후 완료 요청으로 종료가 성립하는 경우도 서버는 자동 완료로 기록한다.

성공은 current와 같은 형식으로 해당 기간을 반환한다. 상세의 `closedAt`, `closedDate`, `endReason`, `processedAt`은 ACTIVE에서 null이다. 이전 스키마에서 이미 종료된 기간은 근거 없는 종료 사유를 채우지 않는다. 종료된 상세는 삭제한 임시 계획의 `deletedAt`과 원본도 제공한다. 종료 상태에서는 임시 계획의 편집 및 새 디로딩 START가 차단된다. 기존 세션의 고정 FINISH ACK와 명시적 기록 복구는 기존 세션 계약으로 처리된다.

사용자 잠금 아래에서 세대, 기간 버전, 세션 상태, 영속 수행을 검증한다. 성공한 `requestId`와 동일한 명령 및 버전은 재실행 없이 같은 기간의 현재 표현을 반환한다. 동일 ID의 다른 종료 명령·버전·기간은 `DELOAD_REQUEST_CONFLICT`다. 자동 작업이 먼저 완료했으면 직전 버전의 직접 완료 요청은 해당 자동 완료를 확인할 수 있다. 직접 완료와 작업의 경쟁에서도 종료 및 분석 경계는 각각 최대 한 번 기록된다.

추가 오류는 `ACTIVE_SESSION_EXISTS`, `DELOAD_ACTIVITY_RECORDED`, `DELOAD_SESSION_ABANDON_REQUIRED`, `DELOAD_PERIOD_VERSION_CONFLICT`, `DELOAD_PERIOD_NOT_ACTIVE`다. 종료, 영향 주간 재검사 표시, 필요한 새 epoch 생성과 순환 기준 복원은 원자적이다. [분석 재개 계약](deload-analysis.md#g5-3-완료와-취소-후-분석-재개)이 누적과 날짜 범위를 정의한다.

<a id="주간-일정-프리셋-연동-검토--미구현"></a>

## 주간 일정 프리셋과 순환 복귀

일반 활성 프리셋 A, 선택 편성 원본 B, 기간 내 임시 계획을 구분한다. `/options`는 `sourcePresetId`와 `sourcePresetVersion`을 반환한다. 미리보기·시작 응답의 `source`는 `presetId`, `presetVersion`, 루틴 상세와 표준 분류를 고정한다. 이전 기간에 저장된 `scheduleVersion`은 역사적 출처로 유지한다. B는 순환 제외 프리셋도 가능하다. 선택과 기간 시작은 B를 활성화하거나 일반 적용 이력을 추가하지 않는다. 임시 계획 재편성도 불변 원본을 재사용한다.

시작은 사용자 잠금 아래 원본과 자격을 재검증하고 변경·삭제된 원본에는 `409 DELOAD_PREVIEW_STALE`을 반환한다. 성공한 요청 재시도는 같은 기간을 반환한다. 시작·임시 계획 저장과 함께 ON 순환의 `nextRotationAt`을 비우며, G5 중에는 읽기가 가능하고 프리셋 쓰기는 차단된다. A와 순환 ON/OFF는 유지되고 중간 월요일은 소비하지 않는다.

직접 완료는 다음 로컬 날짜, 무수행 취소는 취소 당일, 자동 완료는 실제 처리가 이루어져 일반 접근이 열린 로컬 날짜에 A를 사용한다. 복귀일이 월요일이어도 A를 유지한다. ON이면 종료 트랜잭션에서 복귀 날짜보다 뒤의 첫 월요일을 다음 기준으로 저장하고 A 다음 순서를 진행한다. 기존 보호 날짜와 실제 처리 시각을 구별하며 과거에 새 적용 이력을 만들지 않는다. [자동 순환 설정](weekly-schedule.md#자동-순환-설정)
