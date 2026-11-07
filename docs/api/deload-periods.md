# 디로딩 기간 시작·최초 임시 계획 API — G5-1

2026-11-08. [제품 v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md)과 [G5-1 구현 기록](../tasks/deload-g5-1-report.md)을 따른다. 기존 사용 환경에는 적용하지 않는다. 실제 디로딩 운동 실행/임시 계획 변경은 G5-2, 완료·취소·10일차 종료와 최종 재개는 G5-3 책임이다. 이 단계만 배포하면 ACTIVE 기간을 종료할 API가 없으므로 부분 배포하지 않는다.

## 인증·읽기·쓰기

모든 API는 principal의 사용자와 현재 dataGeneration만 처리한다. GET은 query/body 없이 read-only REPEATABLE_READ이며 설정·기간·계획을 생성하지 않는다. POST는 application/json·세션 쿠키·X-CSRF-TOKEN이 필요하고 query를 허용하지 않는다. 미지원/누락 필드·중복 JSON 키·비정규 Counter·조작된 userId/epoch/승인 중량/시작 시각은 거절한다. 성공200, 성공/오류 모두 no-store, 오류는 code-only다.

| Method/경로 | 결과 |
|---|---|
| GET /api/v1/deload-periods/eligibility | userId/dataGeneration, eligibility, activePeriod |
| GET /api/v1/deload-periods/options | 원본 계획일 수별 method/split/suggestedDates/available/issues |
| POST /api/v1/deload-periods/preview | 자격·불변 출처·선택 날짜·편성 결과·previewToken |
| POST /api/v1/deload-periods | 자격/출처 재검증 후 ACTIVE 기간과 최초 임시 계획 원자 생성 |
| GET /api/v1/deload-periods/current | userId/dataGeneration/period. ACTIVE 기간 부재는 period=null |

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
{"dataGeneration":"0","method":"THREE","split":"EQUAL","dates":[]}
```

method: KEEP/THREE/TWO/REST, split: ORIGINAL/PPL/FRONT_BACK/EQUAL/REST. dates=[]면 추천 날짜를 사용한다. KEEP은 원본 요일을 시작일부터 첫7일에 대응시키며 THREE는 +0/+2/+4, TWO는 +0/+3이다. 사용자 날짜는 해당 방식의 슬롯 개수와 일치해야 하고, 서로 달라야 하며 시작일~+6 안에 있어야 한다. 배열 순서가 각 분할의 날짜 대응이다. REST의 날짜는 빈 배열이다.

원본 4~6일: KEEP/THREE/TWO/REST, 3일: KEEP/TWO/REST, 1~2일: KEEP/REST, 0일: REST. 7일 편성은 현행 정책에 정의되어 있지 않아 REST만 제공한다. 자동으로 6일로 줄이지 않는다.

preview 응답에는 source(일정 version·배정 요일·루틴 Detail·승인 중량·표준 운동 PRIMARY/jointType), choice(정규화 날짜), generated(plans/issues), previewToken이 있다. plans의 weights/cardio는 original과 감량 target을 함께 담는다. 자격이 없어도 미리보기는 가능하며 시작 가능 여부를 성공한 것처럼 표시하지 않는다. issues가 있으면 해당 선택은 시작 불가다. options.available은 편성 가능 여부이며 네 자격/ACTIVE 기간 부재와는 별개다. 빈 운동일은 임시 계획으로 만들지 않으므로 선택 슬롯 수보다 실제 생성 계획이 적을 수 있고, 부족분을 새 운동으로 채우지 않는다.

```json
{"dataGeneration":"0","requestId":"00000000-0000-0000-0000-000000000001","previewToken":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","method":"THREE","split":"EQUAL","dates":["2026-11-08","2026-11-10","2026-11-12"]}
```

token은 서버 원본·자격 revision·오늘·정규화 선택·편성 규칙 version=1을 SHA-256으로 결합한다. 토큰은 권한을 부여하지 않으며 최종 시작 시 동일 소유자의 출처/자격을 다시 읽는다. 날짜·원본 version/승인 중량·일정·분류·자격 증거가 바뀌면 재미리보기가 필요하다. 원래 미리보기 요청과 같은 dates 표현 또는 정규화된 dates를 첫 시작에 사용할 수 있지만, 멱등 재시도는 첫 시작의 정확한 요청 표현을 유지한다.

시작은 사용자 FOR UPDATE로 일반 START·일정/원본 쓰기와 직렬화한다. 배정 원본/표준 분류는 공유 잠금으로 검증한다. requestId는 사용자/세대 내 유일하고 같은 ID+같은 요청은 최초 결과를 반환한다. 다른 요청 내용의 재사용은 충돌이다. ACTIVE 유일 인덱스가 최종 중복을 차단한다. 자격·출처를 재확인한 뒤 기간과 모든 자식 계획을 한 트랜잭션에서 저장하며 일부 생성 실패는 전체 rollback한다.

period에는 id/userId/dataGeneration/version/status/startedAt/startDate/timeZone/recommendedEndDate/maximumEndDate/method/split/sourceHash/source/plans가 있다. version은 십진 문자열이며 최초0이다. plans는 id/position/plan을 제공한다. 권장 종료일=start+6, 최대 종료일=start+9이지만 이 단계에서 자동 종료하지 않는다. 원본 routine/assignments/승인 중량을 수정하지 않으며, 원본 JSON·소속·요청·시작 경계는 DB UPDATE trigger로 보존한다.

## 자동 편성 경계

압축형은 세부 PRIMARY의 최초 대표 종목만 원본 요일/메인 순으로 선정한다. 다중 PRIMARY는 모두 커버하고 같은 표준 웨이트는 날짜 사이 중복하지 않는다. 균등형은 라운드로빈 후 COMPOUND→ISOLATION 순으로 정렬한다. PPL은 첫 메인 등장 순서를 사용하며 필요한 분할/순서가 불명확하면 EQUAL만 제공한다. 전후면은 FRONT/BACK이다. 카탈로그 분류를 새로 저장하거나 운동 이름으로 추측하지 않는다.

세부 분할은 기존 PRIMARY group/name을 사용한다. CHEST→PUSH/FRONT, BACK→PULL/BACK, LEGS→PPL LEGS, 전·측면 삼각근/삼두→PUSH/FRONT, 후면 삼각근/이두→PULL/BACK, 전후면의 대퇴사두근/복근→FRONT, 햄스트링/둔근/종아리→BACK이다. 코드에 명시한 정확한 분류명만 사용하고 그 밖의 분류는 보류한다. 충분한 PRIMARY와 jointType이 있으면 EQUAL은 사용할 수 있다. 운영 분류 품질 검증을 완료한 것으로 간주하지 않는다.

2026-11-08 사용자 확정 경계:

- 압축 하루 종목 상한: 3일형 min(floor(원본 평균 종목수+1),6), 2일형 min(floor(평균+2),7). 세트 상한 floor(원본 평균 세트×0.7), 1 미만일 때만 최소1 예외. 우선순위 낮은 종목의 세트를 최소1까지 줄인 후에도 넘으면 해당 종목을 제외하며 반복 수를 다시 늘리지 않는다.
- 웨이트 세트 ceil(원본/2), 최소1. 홀수 세트의 반복 범위 각 경계는 roundHalfUp(원본 세트×반복/(2×새 세트)), 최소1. 3×6~10→2×5~8, 3×10→2×8, 5×10→3×8.
- 승인 중량 우선×0.85 후 PLATE/STACK 5kg, DUMBBELL 2kg, 미지정1kg 내림. 0이 되면 원래 중량, BODYWEIGHT 기본 중량 유지.
- 유산소 시간×0.5를 분 단위 HALF_UP, SPEED×0.85 소수 첫째 자리 HALF_UP. 압축형은 원본 최초 유산소를 하루 최대1개 사용한다. 원본 유산소1일이면 첫 허용 슬롯, 2일 이상이면 두 슬롯이며 PPL의 LEGS 제외, Legs 없는 균등형은 첫 두 슬롯이다. KEEP은 원본 배정 유지.
- PRIMARY/jointType 부재: CLASSIFICATION_REQUIRED, 분할 부위 부재/혼재: SPLIT_CLASSIFICATION_REQUIRED/SPLIT_UNAVAILABLE, PPL 메인 순서 불명: SPLIT_ORDER_UNRESOLVED. PACE·거리·경사·저항: CARDIO_TARGET_POLICY_REQUIRED. 감량 시간0분: CARDIO_TIME_ROUNDS_TO_ZERO. 해당 편성만 보류하며 REST는 계속 지원한다. 무추정·원본 보존 원칙을 유지한다.

## 기간 중 기존 기능 보호

ACTIVE 기간 중 새 일반 START, 원본 루틴 생성/수정/삭제, 주간 일정 저장, 일반 기록 정정/복구/논리삭제/승인 해결 및 새 snapshot/FINISH 쓰기는409 DELOAD_ACTIVE_PERIOD다. 이미 확정된 START/snapshot/FINISH/승인 해결의 동일 요청 ACK는 기존 무변경 재시도 규칙을 유지한다. 읽기와 분석 ON/OFF는 허용하고 OFF/새 epoch가 기간을 종료하지 않는다.

G3는 기간 날짜에 새 MISS를 만들거나 기존 최초 MISS를 수정하지 않고 cursor만 전진할 수 있다. 정지된 과거 dirty는 그대로 보존하며 반복 검사 대상으로 재선택하지 않는다. G4는 ON 평가 구간과 기간이 겹치는 주를 DELOAD_PERIOD 사유로 PENDING 처리한다. N0 RESET/ADD를 생성하지 않고 후속 누적 공개를 막는다. GET은 가능한 마지막 확정 prefix와 PAUSED/blockedAt/추천false를 반환한다. 더 이른 보류가 있으면 그 기존 상태가 우선이다. OFF/시간대 부재도 기존 우선순위다. 최종 기간 재개·완료 새 epoch·취소 처리까지 구현한 것은 아니다.

주요 오류:400 INVALID_REQUEST,409 DATA_GENERATION_MISMATCH/DELOAD_ACTIVE_PERIOD/DELOAD_REQUEST_CONFLICT/DELOAD_NOT_ELIGIBLE/DELOAD_PLAN_REQUIRES_POLICY/DELOAD_PREVIEW_STALE/DELOAD_SOURCE_CHANGED/DELOAD_TIME_ZONE_CONFLICT/AWAITING_TIME_ZONE, DB 경합409 WRITE_CONFLICT, 장애503 DELOAD_DATA_UNAVAILABLE 또는500 DELOAD_DATA_FAILED. 기존 설정 누락 오류는 분석 API와 같다.

## 저장과 후속 책임

V27은 deload_periods/deload_temporary_plans와 workout_sessions의 nullable 쌍 deload_period_id/deload_plan_id를 추가한다. owner/generation/period/plan 복합 FK와 소속 불변 trigger를 사용한다. 기존 V1~26·세션 JSON/hash·현재 원본 데이터는 재작성하지 않는다. 일반 통계는 실제 디로딩 수행의 볼륨을 유지할 수 있지만 일반 계획 이행 후보와 G3/G4 일반 수행에는 명시적 디로딩 소속을 제외한다.

임시 계획은 일반 routine을 복제하지 않는 별도 JSON이다. 따라서 실제 실행 시 기존 source_routine_id/InitialPlan/승인 차단/고정 snapshot 계약에 이 소속을 연결하는 작업은 G5-2에 남는다. 최초 계획 날짜는 첫7일로 제한하며 이후 미수행 계획을 8~10일로 옮기는 저장 구조·이동 불변성은 G5-2에서 확장한다. G5-1에는 실행·사후 편집·완료·취소 endpoint가 없으며, 완료 이력은 테스트 fixture로만 검증한다.
