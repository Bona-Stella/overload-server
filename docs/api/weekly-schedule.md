# Weekly Schedule and Plan History — Phase 2B

2026-11-02 (Asia/Seoul). 기준: [Statistics and Tracking v1.3](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.3.md) ST-10~23, [Task 009](../tasks/task-009-redesign-v1.0.md). **최초 등록은 당일 즉시 적용**, 기존 일정 변경은 **다음 월요일**, 같은 예정일은 **최고 단일 세션 달성률**이라는 사용자 결정을 반영했다.

## DB와 적용 경계

V19는 `routine_plan_history`, `weekly_schedule_versions` 두 표·인덱스·UPDATE 방지 트리거를 추가한다. V20은 `weekly_schedule_versions_effective_from_check`만 교체해 **version=1의 평일 적용**을 허용한다. version>1은 계속 ISO 월요일만 허용한다. V1~V19 SQL·기존 행·이력·불변 계획·승인 사실은 변경하지 않고 INSERT 백필도 없다. 개인 개발 DB에는 **V18·V19·V20을 모두 적용하지 않았다**. local Backend를 해당 DB에 연결해 실행하면 대기 migration이 자동 실행될 수 있으므로 실제 적용은 별도 승인 범위다.

| 표 | 키·소유권 | 보존 내용 |
| --- | --- | --- |
| routine_plan_history | PK (routine_id,routine_version), FK (user_id,data_generation,routine_id)→routines | recorded_at·deleted·당시 RoutineResponses.Detail JSONB |
| weekly_schedule_versions | PK (user_id,data_generation,version), 현재 사용자/세대 FK | recorded_at·effective_from(첫 version은 등록일, 이후 ISO 월요일)·time_zone·assignments JSONB 최대7개 |

양쪽은 새 행으로만 변경하며 UPDATE는 DB가 거절한다. DELETE API는 없고, 향후 계정 전체 영구 삭제/초기화 구현 시 다른 운동 데이터와 함께 정리할 소유 데이터다. 기존 계정/운동 초기화 기능을 새로 구현하거나 보안 정책을 변경한 작업은 아니다.

루틴 생성·실제 편집·명시 중량 승인·삭제에서 기존 workoutTransactionManager 트랜잭션 안에 최종 flush 후 계획을 기록한다. 동일 routine_version은 중복 INSERT하지 않는다. 일정에 지정하는 기존 루틴의 이력이 없으면 **현재 확인한 시점**의 기준만 등록하며 그 이전 계획을 추정하지 않는다. 세션 InitialPlan의 유일한 원본은 여전히 workout_sessions의 불변 JSONB다. 새 루틴 계획 이력은 **미시작 예정일**의 계획 재현을 위한 것이며 세션 InitialPlan의 대체 원본이 아니다.

## GET /api/v1/weekly-schedule?timeZone=Asia/Seoul

인증된 본인·현재 세대만 조회한다. query는 timeZone 하나, 유효 IANA Zone ID이며 GET body/중복·추가 query는400이다. 최초 일정이 없으면 요청 시간대를 사용한다. 최초 등록 뒤에는 기존 일정의 기준 시간대를 유지한다. 시간대 변경 UI는 제공하지 않으며 다른 시간대의 PUT은409 SCHEDULE_TIME_ZONE_CONFLICT다. 이를 여행 중 수행의 workout_date 변경이나 운동 시작 제한으로 사용하지 않는다.

```text
{ userId, dataGeneration, version, timeZone, nextEffectiveFrom,
  current: Version|null, pending: Version|null, history: Version[] }
Version = { version, effectiveFrom, timeZone, recordedAt,
            assignments: [{ dayOfWeek: 1..7 (월..일), routineId: UUID }] }
```

Counter는 정규 문자열이다. 초기 version="0", current/pending=null, history=[]다. `nextEffectiveFrom`은 **다음 저장에 적용할 날짜**이며, 미등록 상태에서는 서버 Clock/요청 시간대 기준 오늘, 등록 후에는 저장된 시간대 기준 **엄격한 다음 월요일**이다. 월요일의 기존 일정 편집도 그 다음 월요일부터 적용한다. 최초 PUT은 당일부터 current가 되고 pending=null이다. current와 pending은 별도로 조회하므로 미래 일정을20번 넘게 편집해도 오래된 현재 일정을 잃지 않는다. history는 최근20개, version 오름차순이다. DTO 필드명·요청 구조는 유지한다.

## PUT /api/v1/weekly-schedule

기존 쿠키 인증·CSRF·소유권/세대 경계를 사용한다. query는 받지 않는다.

```json
{
  "dataGeneration": "0",
  "expectedVersion": "0",
  "timeZone": "Asia/Seoul",
  "assignments": [{ "dayOfWeek": 1, "routineId": "11111111-1111-4111-8111-111111111111" }]
}
```

정확히 위 필드만 허용하며 중복 JSON 키/요일·비정수/범위 밖 요일·잘못된 UUID/Counter·추가 필드를 거절한다. 요일별 루틴은 최대 하나, 같은 루틴의 여러 요일 지정은 허용한다. 빠진 요일은 휴식이다. 빈 배열은 명시적인 주7일 휴식 일정이며 미등록/과거 확인 불가와 다르다. 입력은 전체 일정 대체로 정규화하고 요일순으로 저장한다.

사용자 행 FOR UPDATE→루틴 확인 순으로 직렬화한다. expectedVersion이 다르면409 SCHEDULE_VERSION_CONFLICT, 세대가 다르면409 DATA_GENERATION_MISMATCH, 본인 현재 세대의 활성 루틴이 아니면404다. 외부 사용자 ID나 루틴 사본을 입력받지 않는다. 저장 성공은200/현재 View이며 원래 루틴·승인 중량을 변경하지 않는다. 최초 저장은version1로 등록일 즉시 적용한다. 동일 최신 일정의 재저장은 version/이력을 늘리지 않는다. 기존 일정 변경은 version+1 행으로 다음 월요일에 적용한다. 같은 적용일의 예약을 바꾸면 이전 행을 보존하고 높은 version이 유효하다. V19 시점에 이미 저장된 version1의 날짜를 당겨 쓰거나 소급 수정하지 않는다.

첫 주는 최초 `effective_from`부터 일요일까지 남은 예정일만 분모에 포함한다. 그 주의 등록 전 날짜는 BEFORE_REGISTRATION으로 제외하며 휴식 계획을 생성하지 않는다. 다음 주부터 전체 요일을 적용한다. 최초 등록 주보다 앞선 주는 여전히 UNKNOWN이며 분모를 추정하지 않는다. 예: 목요일에 월·목·금·토 등록, 목·금 완료·토 미수행 → 첫 주2/3=66.666667%, 다음 주 분모4.

ACK 유실/충돌 시 입력을 유지하고 명시 재조회로 현재 서버 일정을 확인한다. 자동 재승인·범용 Outbox/영수증·임의 병합은 없다. 온라인 전용 관리 API이며 IndexedDB/PWA 문서나 고정 snapshot/finish 요청을 바꾸지 않는다.

루틴 삭제는 계정 행을 먼저 잠근 뒤 루틴 삭제·계획 이력·다음 월요일의 일정 제외를 같은 트랜잭션으로 확정한다. 현재 주 계획일을 소급 제거하지 않으며 삭제한 루틴을 다시 실행 가능하게 만들지도 않는다. 루틴 삭제로 과거 세션·승인 사건·일정 이력을 삭제하지 않는다.

## GET /api/v1/weekly-schedule/history?beforeVersion=21

본인·현재 세대의 version < beforeVersion을 최신순으로 최대20개 반환한다. beforeVersion은 양수 Counter이며 필수다. 다른 query/GET body는400이다.

```text
{ userId, dataGeneration, history: Version[], nextBeforeVersion: Counter|null }
```

다음 페이지가 있을 때만 마지막 version을 nextBeforeVersion으로 반환한다. current/pending과 독립된 과거 조회이며 쓰기·만료·운동 동기화 시각을 갱신하지 않는다.

오류/no-store/요청 ID는 기존 RoutineErrors·Security 경계를 재사용한다. 400 INVALID_REQUEST, 401 AUTHENTICATION_REQUIRED, 403 CSRF_INVALID, 위409 충돌, 503 WORKOUT_DATA_UNAVAILABLE, 예상 밖500 WORKOUT_DATA_WRITE_FAILED다. 내부 SQL/예외는 노출하지 않는다.
