# Statistics API — Phase 2A

2E 현행 맨몸 총중량 계산은 [Bodyweight Additional Load](bodyweight-additional-load.md)를 따른다. BODYWEIGHT는 별도 명시된 추가 중량×실제 반복만 사용하며, 과거 weight/체중을 재해석하지 않는다. 무근거는null·세트/반복만 표시한다. 아래 ‘추가 부하 후속’은2A 당시 기록이며 최신 종목/근육/group/주·월 집계에는2E 계약이 우선한다.

2D의 실제 유산소 속도·거리 출처·overview cardioByKind는 [Actual Cardio Measurements](cardio-actual-measurements.md)를 따른다. 기존2A 응답 형태는 유지하며, 아래 ‘실제 속도/계산 거리 후속’ 설명은2A/2B 시점 기록이다.

2C 승인 성장 추적의 실제 조회 계약은 [Approved Weight Growth API](approved-weight-growth.md)를 따른다. 이하2A/2B endpoint는 보존한다.

현행 보완: [2B 주간 일정·계획 이력](weekly-schedule.md)과 아래 overview endpoint를 추가했다. 기존 이 문서의 `/api/v1/statistics`는 2A 계약대로 유지한다. 최신 정책은 [Statistics and Tracking v1.3](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.3.md)이며 v1.1·v1.2 원본은 보존했다.

2026-11-02 (Asia/Seoul). **통계·추적 2단계의 첫 구현 단위**다. 기준은 [Statistics and Tracking v1.1](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.1.md)이며 제품 본문은 한국어로 유지했다. 디로딩 정책은 [Deloading v1.2](../product/Overload_Deloading_Design_Specification_v1.2.md)를 따른다.

## 구현 범위와 데이터 안전

신규 HTTP 읽기 API만 추가했다. 별도 집계 테이블·캐시·migration·쓰기 API는 없다. 개인 DB에는 접속하거나 V18을 적용하지 않았다. 운영 카탈로그의 정확한 PRIMARY/SECONDARY·joint_type 검증은 남아 있으며 현재 연결을 집계할 수 있다는 사실과 분류 정확성을 구분한다.

기존 principal·현재 dataGeneration, workoutTransactionManager의 read-only REPEATABLE_READ 트랜잭션을 사용한다. 요청의 userId/generation 입력은 받지 않는다. 조회가 세션 만료·takeover·승인·운동 동기화 시각을 갱신하지 않는다. Spring Session의 기존 인증/쿠키/CSRF 정책은 유지한다.

## GET /api/v1/statistics

필수 query는 `fromDate=YYYY-MM-DD&toDateExclusive=YYYY-MM-DD` 두 개뿐이다. 종료일 배타 범위, 1~366일을 허용한다. 이는 조회량을 제한하는 기술 계약이며 주/월 보정 정책이 아니다. 기간 전체 성장은 별도 후속 계약이며 이 API의 366일 제한을 전체 성장 이력의 제품 제한으로 사용하지 않는다.

실제 달력 날짜를 검사하고 중복 query·미지정 query·다른 query·GET body는 400 INVALID_REQUEST다. 익명/만료는 기존 인증 경계의 401이다. 기존 읽기 오류 규약 `{code}`, Cache-Control no-store·X-Request-ID·보안 헤더를 재사용한다. DB 연결/timeout은 503 WORKOUT_DATA_UNAVAILABLE, 예상 밖 조회 오류는 500 WORKOUT_DATA_READ_FAILED이며 내부 SQL·예외를 응답에 노출하지 않는다.

### 원본과 필터

- `workout_sessions.workout_date`는 시작 시각+당시 IANA 시간대에 대응하는 불변 날짜다. 완료 시각·업로드 날짜로 분할/재귀속하지 않는다.
- 본인·현재 세대·FINISHED/AUTO_CLOSED·미삭제 세션의 현재 미삭제 performed_sets, COMPLETED cardio_results만 조회한다. ACTIVE·SKIPPED·삭제 사실은 집계하지 않는다.
- 승인 상태·TODAY_ONLY·UNVERIFIED·장비·표준 운동 활성 여부로 실제 기록을 제외하지 않는다. 현재 활성 muscle 연결을 참고 분류로 사용한다.
- 정정·삭제·명시 누락 복구 이후 매 조회에서 관계형 수행을 읽는다. record_change_events를 수행으로 더하지 않고 삭제 UUID를 복구하지 않는다. InitialPlan과 봉인 AppliedPlan·평가·승인 사건은 변경하지 않는다.
- 고정 SQL 6개(소유자/세대, 기간 세션, 세트, 유산소, 근육 연결, 최근3건)로 조회하며 운동/근육별 N+1은 없다. 집계는 정확한 BigDecimal/정수로 처리한다.

### 응답

Counter와 총중량·거리는 정확한 JSON 문자열이다. `weight × reps` 합계는 개별 중량의 허용 자릿수를 초과할 수 있으므로 프론트에서는 합계를 Number로 변환하지 않는다. 날짜는 ISO date, 시각은 UTC Instant다.

```text
userId, dataGeneration, fromDate, toDateExclusive
planCoverage = "STARTED_SESSIONS_ONLY"
plannedWorkoutDays = null
routineAchievementPercent = null
summary: Weight
cardioActiveMs, unmappedCompletedSets: Counter
days: [{ date, weights: Weight, cardioActiveMs }]
exercises: [{ standardExerciseId, name, weights: Weight }]
muscles: [{ id, name, groupCode, role, weights: Weight }]
groups: [{ groupCode, role, weights: Weight }]
cardio: [{ kind, name, activeMs, distanceKm, recordsWithoutDistance }]
recentSessions: [{ id, routineName, workoutDate, startedAt, status }]

Weight = { plannedSets, completedSets, reps, volumeLoadKg, setsWithoutVolume }
```

- `plannedSets`는 **조회 대상 종료 세션의 불변 InitialPlan에 보존된 웨이트 계획 세트**만 더한다. 주간 전체 예정일의 계획이 아니다. AppliedPlan 축소·현재 루틴 변경으로 바꾸지 않는다. 이 제한은 모든 하위 Weight에 동일하다.
- 실제 계정용 주간 일정/적용 시점 이력이 없으므로 전체 계획 운동일·주간 루틴 달성률은 null이다. 현재 루틴으로 과거 미시작 날짜의 분모를 생성하거나 0/100%를 반환하지 않는다.
- `completedSets`, `reps`는 실제 유효 완료 세트·실제 반복 합계다. 추가 종목·추가 세트를 포함하며 계획을 초과해도 잘라내지 않는다. 계획0/실제양수는 계획 없음으로 표시한다.
- `volumeLoadKg = Σ(각 세트의 실제 weight × 실제 reps)`. BODYWEIGHT는 기존 수치가 추가 중량인지 체중인지 구분되지 않아 이번 단위에서 제외한다. 제외한 세트 수가 `setsWithoutVolume`이고 집계 가능한 세트가 전혀 없으면 volumeLoadKg=null이다. 일부만 계산 가능하면 확인 가능한 세트의 소계임을 함께 표시한다. 명시적 추가 부하 구분과 합산은 후속 구현이다.
- 세부 muscle에는 역할별 각 세트를 전부 반영한다. group에서는 같은 실제 세트/대분류/role 조합을 한 번만 센다. SECONDARY는 별도 참여량이며 0.5 환산이나 PRIMARY와 합산하지 않는다. 전체 summary는 muscle JOIN과 무관하게 실제 세트를 한 번씩만 센다. 총중량에도 동일 중복 제거 원칙을 적용한다.
- `unmappedCompletedSets`는 활성 PRIMARY 연결이 없는 실제 세트 수이며 전체 실제 세트에는 남긴다. 카탈로그 검토 완료 수치가 아니다.
- days는 조회 범위의 모든 날짜를 오름차순으로 포함한다. 운동이 없는 날의 실제 세트/시간은0이며 근거 없는 계획은 만들지 않는다.
- 유산소는 kind+당시 계획의 name별로 분리한다. AppliedPlan을 우선하고 보존 InitialPlan을 대안으로 삼는다. 확인 불가 메타는 UNCLASSIFIED로 분리한다. 웨이트 세트와 합치지 않는다. 시간은 전체 actual active_ms이며 거리 미입력은 recordsWithoutDistance로 표시하고 거리 합계에서 제외한다. 모든 거리가 미입력이면 distanceKm=null이다. 서로 다른 종목 거리를 하나로 합산하지 않는다.
- 기존 직접 입력 distance를 그대로 합산한다. 계획 속도로 실제 거리를 만들거나 사용자가 수정한 거리를 덮어쓰지 않는다. 실제 속도/계산 거리 출처를 저장하는 기능은 후속 구현이다.
- recentSessions는 기간과 별도로 본인·현재 세대의 미삭제 종료 기록 중 시작 시각 DESC/UUID ASC 최신3건이다. 상세는 기존 workout-sessions API를 사용한다.

## 실제 계정 화면

오프라인 운동 입력과 별개로 온라인 실제 계정 화면에 통계 요약을 연결했다. 진행 세션 입력 중에는 표시하지 않는다. 기본 이번 주(월~일) 요약 → 운동 현황/근육별 상세, 주/월 이전·다음, 세트/총중량 전환을 제공한다. 대시보드의 최근3건/전체보기는 기존 상세·이력 화면으로 연결하며 기존 exit guard를 유지한다.

계정/세대/기간 검증·AbortSignal을 사용하고 계정 전환이나 unmount 후 이전 응답을 표시하지 않는다. API·통계 데이터는 IndexedDB/localStorage/SW에 캐싱하지 않는다. 기존 DB3/schema2·고정 snapshot/finish 원문·타이머·오프라인 permit을 변경하지 않는다. 서버 미반영 로컬 실적은 포함되지 않음을 화면에 안내한다.

## 아직 구현하지 않은 2단계 요구사항

주간 일정·적용 시점별 계획 이력과 미시작 날짜의 분모(ST-10·15·17~23), 전체 계획 대비 유산소 목표/달성 비교, 승인 중량 성장선·검색(ST-38~42), 실제 유산소 속도·자동 거리/출처·수정 보존(ST-50~51), BODYWEIGHT 명시적 추가 부하(ST-36)가 남아 있다. 디로딩 기간 표시·분모/승인 평가 분리는 3단계 도메인 연결 전이며 완료로 표시하지 않는다. 이번 구현은 통계 기능 전체 완료가 아니다.

위 문단은 2A 당시의 잔여 항목이다. 주간 일정·계획 이력·미시작 예정일 분모와 최초 계획 달성 비교는 후속2B에서 구현했으며 범위/한계는 다음과 같다.

## GET /api/v1/statistics/overview — 2B

날짜 query·인증·no-store·오류 형식은 원래 statistics와 같다. 기존 endpoint의 고정 null·coverage 계약을 깨지 않도록 새 endpoint로 제공한다.

```text
{ statistics: StatisticsResponse,
  effectiveWorkoutDays: Decimal|null,
  unknownPlanDays: integer, unverifiableDays: integer,
  plannedDays: [{ date, status, scheduleVersion, plan: RoutineDetail|null,
                  plannedExercises, completedExercises, effectiveDay, selectedSessionId }] }
```

- 날짜별로 해당 날짜에 유효한 최신 일정 version을 찾는다. 최초 등록은 당일 즉시 적용하며 첫 주 등록 전 날짜만 BEFORE_REGISTRATION으로 제외한다. 그 날짜의 plan/count/effectiveDay는 null이고 unknownPlanDays를 증가시키지 않는다. 휴식 또는 소급 계획을 만들지 않는다. 최초 등록 주보다 앞선 주는 UNKNOWN이며 과거 분모를 추정하지 않는다. 명시적 휴식 일정은 REST다.
- 예정일에 해당 루틴을 시작한 증거가 있으면 가장 이른 시작의 불변 InitialPlan을 그날 계획 표시/계획 세트 원본으로 사용한다. 삭제/ACTIVE 세션도 시작 계획의 증거는 유지하며 실제 수행 분자에는 기존처럼 미삭제 종료 세션만 사용한다. 시작한 세션이 없으면 당시 계획 이력의 현지 날짜 종료 이전 최신값을 사용한다. 미래/오늘 계획에는 현재 확인된 최신 자료만 반영하며 지난 날짜를 이후 편집으로 바꾸지 않는다.
- routine_plan_history는 조회 구간의 날짜별 마지막 계획과 구간 직전 기준만, 일정은 적용일별 마지막 version·구간 직전 기준·최초version1을 읽는다. 최초 이력은 같은 SQL의 UNION으로 확보하여 최근20개 편집에 의존하지 않는다. 세션/근육별 N+1 없이 추가 최대3개 고정 query를 사용한다. 적용 계획의 세트에는 **운동하지 않은 예정일**도 포함한다. 근육별 분류·중복 제거는 2A와 같다.
- 완료 후보는 예정일 날짜·예정 루틴이 모두 일치하는 미삭제 FINISHED/AUTO_CLOSED 세션이다. 각 후보의 **자기 InitialPlan**과 현재 유효 수행을 비교한다. WEIGHT는 해당 sessionExerciseId의 실제 완료 세트 수가 최초 setCount 이상이어야 완료 종목1개다. AppliedPlan 축소나 추가 종목은 분자/분모를 바꾸지 않는다. 반복·승인 중량 성과 평가를 이 달성률 산식에 섞지 않는다.
- 후보별 완료 계획 종목/최초 계획 종목의 정확한 유리수를 비교하여 **단일 최고값**만 사용한다. 서로 다른 세션의 부분 세트/종목을 합치지 않는다. 예정일에 수행이 없으면 유효 운동일0, 휴식일/다른 루틴/추가 운동은 계획 분자를 채우지 않으며 실제 운동량에는 남는다. 일일 최대1이다. 유리수 합계는 BigInteger로 유지하고 표시할 때만 반올림한다.
- CARDIO는 최초 시간·거리 목표와 실제 activeMs/distance를 비교한다. 이미 확인된 목표 미달은 미완료다. 실제 속도/경사/저항이나 필요한 거리 값이 없어서 판정할 수 없으면 UNVERIFIABLE로 남긴다. 계획 속도를 실제로 대체하지 않는다. 불확실한 후보의 가능한 최고값이 확실한 최상 후보를 넘지 못하면 그날은 확실한 결과를 사용할 수 있다.
- plannedDays.status는 UNKNOWN/BEFORE_REGISTRATION/REST/PLANNED/UNVERIFIABLE다. complete count/effectiveDay는 확인 불가일 때 null이며 잘못된0을 만들지 않는다. 표시 계획과 최고 후보의 InitialPlan 버전이 다른 같은 날짜의 경우, plan은 최초 시작 계획, plannedExercises/completedExercises/selectedSessionId는 최고 후보의 최초 계획 기준임을 구분한다.
- statistics.planCoverage는 FULL_SCHEDULE_HISTORY 또는 PARTIAL_SCHEDULE_HISTORY다. UNKNOWN 날짜가 있으면 plannedWorkoutDays·routineAchievementPercent·effectiveWorkoutDays는 null이다. 최초 등록 주는 등록 전 날짜의 제외 근거가 있으므로 FULL_SCHEDULE_HISTORY이며 등록일~일요일 예정일만 분모에 포함한다. 목요일 월·목·금·토 등록/목·금 완료의 경우 계획일3·유효일2·달성률66.666667%다. 다음 주는 전체4일이다. 모든 날짜의 근거가 있고 계획일이0이면 계획일0/달성률null이다. 숫자가 없는 과거 분모를0이나 현재 루틴으로 채우지 않는다.
- 모든 날짜의 계획이 알려져도 UNVERIFIABLE 날짜가 있으면 전체 유효일/달성률은 null이다. 확인 가능한 날짜의 상세는 유지한다. 실제 속도 저장은 후속 기능이다.
- 월요일부터 정확히7일인 조회만 주간 루틴 달성률 `min(100, 유효일 합계 / 계획일 ×100)`를 반환한다. 다른 날짜 범위는 계획일/일별 상태만 제공하고 새로운 월간 달성률 정책을 만들지 않는다. 주간 달성률은 소수6자리, 유효일은 소수12자리까지 표시한다.
- actual 세트·볼륨·유산소·날짜·최근3건은 2A와 같은 현재 유효 원본을 사용한다. 정정/삭제/명시 누락 복구 이후 매 조회에서 재계산하며 최초 평가·승인 중량·운동 동기화 시각은 변경하지 않는다. 통계는 오프라인 미반영 사실을 추정하지 않는다.

실제 계정 UI는 overview를 사용하고 다음 월요일 일정 예약·현재/예정/페이지별 변경 이력, 주간 계획일·달성률, 날짜별 예정/휴식/확인 불가와 당시 계획/수행 상세 이동을 제공한다. 기존 DB3/schema2·타이머·고정 요청·PWA permit은 유지한다. 성장선·실제 속도/계산 거리 저장·명시적 맨몸 추가 부하·디로딩 연결은 여전히 후속이다.
