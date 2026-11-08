# 주간 반복 일정·일별 판정 API — M1 / G2~G3

2026-11-06. 정책은 [통계·추적 v1.4](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.4.md), 구현 경계는 [구현 계획](../tasks/weekly-schedule-immediate-implementation-plan.md)을 따른다. **코드/격리 검증과 실제 개발·검토 DB 적용은 별개다. 이번에는 실제 DB에 V24를 적용하지 않는다.**

## GET /api/v1/weekly-schedule?timeZone=Asia%2FSeoul

기존 쿠키 인증·소유권·현재 dataGeneration·no-store를 유지한다. timeZone 하나만 받고 유효 IANA 값이어야 한다. 등록 후에는 저장된 달력 시간대를 응답한다. GET/history는 읽기 전용이며 전환·만료·dirty 표시를 실행하지 않는다.

```text
{ userId, dataGeneration, version, timeZone, appliedAt: Instant|null,
  assignments: [{dayOfWeek: 1..7, routineId: UUID}] }
```

미등록은 version="0", appliedAt=null, assignments=[]다. 등록한 전체 휴식은 version>0, appliedAt이 있고 assignments=[]다. current/pending/nextEffectiveFrom과 예약 적용 기능은 제거했다. 같은 루틴을 여러 요일에 배정할 수 있다. 빠진 요일은 휴식이다.

## PUT /api/v1/weekly-schedule

```json
{
  "contractVersion": "immediate-v1",
  "dataGeneration": "0",
  "expectedVersion": "0",
  "timeZone": "Asia/Seoul",
  "assignments": []
}
```

정확히 위 필드만 받는다. Counter는 long 범위의 정규 십진 문자열, 요일은1~7 정수·중복 불가, 최대7개다. UUID·JSON 중복 키·추가 필드·GET body·잘못된 시간대 검증을 유지한다. cookie/CSRF와 사용자 소유권은 기존 인증 경계를 따른다.

- 구 관리 요청의 contractVersion 누락/다른 값은 데이터 조회·쓰기 전에409 SCHEDULE_CONTRACT_MISMATCH다. 마커는 인증 수단이 아니다.
- owner FOR UPDATE와 READ_COMMITTED에서 START/관리 쓰기를 직렬화한다. 세대·expectedVersion·고정 시간대를 확인한다.
- 등록 후 동일 최신 배정은 no-op이며 version/이력을 늘리지 않는다. 미등록의 빈 배열 최초 등록은 새 일정이다.
- 실제 변경/최초 등록은 ACTIVE가 없을 때만 허용하며, 있으면409 ACTIVE_SESSION_EXISTS다.
- version+1 이력을 추가하고 즉시 응답한다. recorded_at은 owner 잠금 이후 서버 시각의 마이크로초 값, effective_from은 그 시각의 저장 시간대 날짜다.
- 동일 시각은 version으로 순서를 정한다. 최신 즉시 시각보다 뒤로 가거나 검사 완료 날짜로 되돌아간 서버 시각의 쓰기는409 WRITE_CONFLICT로 거절한다. 시각을 임의 보정하지 않는다.
- PWA는 저장 응답과 현재 GET의 version/assignments/appliedAt을 확인한다. 실패 입력·루틴 부분 성공을 유지하며, 다른 version을 조용히 병합하지 않는다.

세대/버전/시간대 충돌은 기존409 DATA_GENERATION_MISMATCH/SCHEDULE_VERSION_CONFLICT/SCHEDULE_TIME_ZONE_CONFLICT, 소유하지 않은 루틴은404, 형식 오류는400이다. 기존 code-only 오류·X-Request-ID·503 경계를 유지한다.

## 이력 조회와 구 예약 전환

GET /api/v1/weekly-schedule/history?beforeVersion=21은 현재 사용자/세대의 작은 버전부터가 아니라 **version<beforeVersion 최신20개**를 반환한다.

```text
{userId,dataGeneration,history:[{version,effectiveFrom,timeZone,recordedAt,appliedAt,assignments}],nextBeforeVersion:null|Counter}
```

appliedAt은 신규 즉시 행에서는 recordedAt, 구 계약 행에서는 null이다. 구 effectiveFrom은 역사적 원래 날짜이며 현재 예약이나 실제 적용 완료를 뜻하지 않는다. 미수행/통계 판정은 이 페이지20개에 의존하지 않는다.

V24의 initialize_workout_calendar는 해당 owner를 잠그고 가장 최근 저장한 배정 의도를 새 version으로 한 번 승격한다. 기준 version과 그 행의 recorded_at을 구/신 해석 경계로 사용한다. 재실행하면 기존 control이 있어 추가 승격하지 않는다. 구 행/JSON/날짜/이전 migration은 변경하지 않는다. 경계 이전은 effective_from, 이후는 신규 recorded_at/version으로 해석하므로 예전11/09 예약이 나중에 새 현재값을 덮지 않는다.

## M1 저장 구조

- V19~V23은 그대로 둔다. V24 하나에서 월요일 CHECK를 제거하고 사용자/세대/recorded_at/version 범위 인덱스를 추가한다. 별도 applied_at은 없다.
- workout_calendar_control은 사용자/세대1행의 시간대·기준 version·최초 검사 날짜·cursor다. 기존 미등록 계정에 가짜 휴식 일정을 만들지 않는다.
- workout_sessions.schedule_version_at_start는 nullable 범위 FK이며 신규 START에서만 기록한다. 기존 세션은 null 그대로다. DB/Entity가 후속 변경을 막는다. InitialPlan/요청 JSON/hash는 수정하지 않는다.
- daily_training_assessments는 사용자/세대/날짜PK, 첫 예정 START 참조·최초 MISS 시각/일정/루틴 계획 버전 참조·state/revision/dirty/재시도 시각만 저장한다. 계획/수행 JSON을 복제하지 않는다.
- 첫 START/최초 MISS 근거는 설정 후 변경할 수 없다. 일반 루틴·세션 삭제도 원래 계획·이력을 물리 삭제하지 않는다.
- 루틴 삭제의 실제 주간 배정 제외는 같은 트랜잭션에서 즉시 version을 추가한다. 직접 PUT과 같은 ACTIVE guard를 사용하며 실패하면 삭제·계획 이력·일정이 모두 롤백된다.

## 04시 판정과 재검사

날짜는 START의 저장된 workoutDate를 유지한다.00시는 날짜 경계,04시는 전날 검사 경계다. 첫 예정 START의 출처/InitialPlan을 우선 고정하며 미시작일은 그 날짜 종료 직전 유효한 이력을 쓴다. 다음 날02시 현재값 변경을 전날에 소급하지 않는다.

`DailyTrainingReviewJob`은 기본 60초 fixed delay로 실행하며 G5 자동 완료 → G3 일별 검사 → G4 주간 확정 순으로 호출한다. G3는 저장 시간대의 다음 로컬 날짜 04:00부터, G4는 다음 월요일 로컬 04:00 이후 필요한 일별 근거가 해소되면 확정한다. 04:00은 실행 예약 시각이 아니다. 별도 자정/월요일 작업은 없다. 20 owner, owner당 최대31일 창으로 사실/근거를 묶어 읽고, owner FOR UPDATE SKIP LOCKED·날짜PK·짧은 READ_COMMITTED 트랜잭션으로 다중 서버/재시작/실패를 처리한다. 기존 자동 종료도 owner FOR SHARE를 먼저 취득하므로 검사와 원본 변경이 직렬화된다. 검사 직후 자동 종료가 확정되면 같은 트랜잭션의 dirty 표시로 해당 날짜를 다시 깨운다. START/관리 쓰기의 G1 잠금 계약을 유지한다.

| state | 의미 |
|---|---|
| PERFORMED | 미삭제 종료 세션에 실제 세트 또는 양의 완료 유산소 활동이 있음. 완료 종목0인 부분 수행·다른 루틴·휴식일 수행도 원본으로 보존 |
| MISSED | 검사 가능한 예정일에 실제 수행이 없음. 최초 근거는 유지하고 늦은 실제 기록이 오면 현재 상태를 재평가 |
| PENDING_ACTIVE | 해당 workoutDate의 ACTIVE가 남아 판정 보류 |
| UNKNOWN | 계획 근거 부족/이전 미확인 범위. 임의의 미수행0을 생성하지 않음 |
| EMPTY | 기존 사실 변경을 재검사했으나 현재 실제 수행/예정이 없음. MISS나 휴식일별 영구 REST 행이 아님 |

실제 사실이 없는 휴식일은 새 일별 행 없이 cursor만 전진한다. 보류 날짜는 dirty/재시도 시각을 남겨 cursor가 넘어가도 잃지 않는다. 미확정 retry는10분 후, 원본이 변경되면 즉시 다시 대상이 된다.

신규 일반 START는 같은 트랜잭션에서 해당 날짜를 재검사 대상으로 깨우며 실제 수행으로 집계하지 않는다. 이미 검사한 날짜의 늦은 START도 ACTIVE 판정으로 보류한다. 종료/AUTO_CLOSED/논리 삭제 및 수행 INSERT·실질 UPDATE의 전용 DB trigger가 같은 트랜잭션에서 날짜를 dirty로 만든다. GET·동일 START/snapshot/FINISH ACK·변화 없는 쓰기는 dirty를 늘리지 않는다. 실패하면 원본과 dirty/cursor도 함께 롤백한다. 실제 수행이 있어도 일반 계획 A를 다른 루틴 B로 달성한 것으로 계산하지 않는다. G4 분석과 G5 기간은 별도 계약을 사용하고, 디로딩 소속 사실과 기간의 보호 날짜를 일반 미수행 판정에 사용하지 않는다. [분석 API](deload-analysis.md) · [기간 API](deload-periods.md)

## V24 적용 전 제한 (당시 작업 범위)

기존 데이터 보존을 전제로 한 당시 V24 전환 기록이다. 다중 프리셋의 전환 기준은 아래 확장 검토를 따른다.

전환 전 관리 쓰기 정지·새 영구 백업/격리 복원·기존 세션/루틴/승인/이력 지문 대조와 Backend/PWA 동시 전환이 필요하다. 실제 DB/기존 컨테이너/볼륨을 자동 변경하지 않는다. 구 검토 재개 도구의 V23 전용 guard도 이번에 V24 허용으로 바꾸지 않는다.

### 2026-11-07 현행 정책과 적용 범위

디로딩은 [v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md) 기준이며 G4 분석과 G5 기간은 별도 API다. 일정 조회는 유지하며 일정 변경은 ACTIVE 기간과 완료 당일에 409 DELOAD_ACTIVE_PERIOD로 차단한다. 완료 다음 고정 로컬 날짜 또는 무수행 취소 직후에는 변경할 수 있다. 일반 계획 ST-17~23과 디로딩 후보는 분리하며 G3는 분석OFF여도 유지한다. 이 문서의 이전 “이번 실제 DB 미적용”은 해당 개발 단계의 제한이다. 현재 V24가 확인된 대상은 기존 격리 검토DB뿐이며 개인/운영DB는 이번에 확인하거나 변경하지 않았다.

## G5-1 ACTIVE 기간 보호 (당시 구현 범위)

[디로딩 기간 계약](deload-periods.md)의 ACTIVE 기간에는 주간 일정 저장을409 DELOAD_ACTIVE_PERIOD로 차단한다. 읽기와 기존 확정 요청의 무변경 ACK는 유지한다. 기간 시작과 일반 쓰기는 기존 사용자 잠금으로 직렬화하며 [G5-2 임시 일정](deload-periods.md#임시-계획-관리--g5-2)은 별도 계획의 날짜만 변경한다. 기간 종료·최종 일반 검사 재개는 G5-3 전이다. 기존 검토 DB와 서버에는 새 기능을 배포하지 않는다.

## 다중 주간 일정 프리셋 확장 검토 · 미구현

[확정 제품·UI 정책](../../../overload-pwa/docs/product/Overload_UI_Decision_Log_v0.9.md#1016--다중-주간-루틴-일정과-자동-순환)을 따른다. 위의 단일 일정 GET/PUT·이력은 현재 구현 계약이다. 프리셋 CRUD·수동 적용·순환 구성 API 및 영속 모델은 후속 설계 대상이며 경로·DTO·DDL은 확정하지 않았다.

**V1 출시 전 전환 범위.** 현재 계정·운동 기록·일정은 개발·테스트 데이터이며 보존 대상인 운영 사용자나 운영 데이터는 없다. 기존 단일 일정을 1번 프리셋으로 옮기거나 기존 테스트 데이터를 유지하는 것은 선택 사항이다. 복잡성이 낮으면 재사용하고, 그렇지 않으면 테스트 데이터를 초기화해 새 구조로 시작할 수 있다. 실제 초기화·데이터 덮어쓰기·적용된 Flyway 이력 변경 및 breaking API 변경은 [AGENTS.md 15절](../../AGENTS.md#15-git-and-change-management)의 사전 승인 절차를 따른다.

기존 `GET/PUT /api/v1/weekly-schedule`과 이력 API, DB 테이블·컬럼은 [AGENTS.md 4.2·4.3절](../../AGENTS.md#42-v1-출시-전-설계와-호환성)에 따라 재사용과 수정·통합·교체·삭제를 비교한다. PWA와 Backend는 최신 계약으로 함께 전환하며, 구버전 전용 호환 계층·fallback·중복 구현은 두지 않는다.

- 새 계약에서도 소유권·동시성·데이터 무결성, 프리셋 제한, G5 보호와 실제 배정 변경의 ACTIVE 제한을 보장한다. 기존 `weekly_schedule_versions`와 즉시 적용 로직은 재사용 후보로 비교한다. 최초 START·MISS·InitialPlan과 당시 적용 근거를 보호한다. 적용 시각 이전으로 소급하지 않으므로 수요일 적용으로 월·화의 기록과 판정 근거가 바뀌지 않는다.
- `RoutineService.delete`와 `RoutinePlanHistory.removeFromSchedule`은 현재 최신 적용 배정만 변경한다. 다중 프리셋에서는 확인 후 루틴 삭제와 **모든 프리셋의 해당 루틴 배정 해제**를 한 트랜잭션에서 처리한다. 다른 루틴·요일과 과거 기록·통계 근거를 보호하고, 활성 배정이 바뀌면 실제 적용 근거를 갱신한다. 순환 구성이 그대로이므로 순환 위치도 유지한다. ACTIVE 및 디로딩 보호가 우선이다. 개별 루틴 폼의 요일 저장 경로는 주간 일정 편집으로 분리·정리한다.
- 사용자 잠금과 버전 검증을 프리셋 내용 및 순환 구성 변경에 적용한다. 먼저 커밋된 상태를 보호하고 오래된 저장을 거절하며 최신 상태로 다시 편집하도록 안내한다. 자동 병합·기기별 비교·별도의 초안 복구는 제공하지 않는다. 검증 단위와 데이터 형식은 최신 계약에 맞춰 정한다.
- 프리셋 삭제는 확인 뒤 요청하며 서버에서 활성 ID, 최소 1개, ON 중 최소 포함 2개와 보호 상태를 검증한다. 자동 ON에서 삭제로 포함 구성이나 포함 순서가 실제로 바뀌면 새 첫 포함 프리셋을 같은 트랜잭션에서 적용한다. ON 중 활성 프리셋을 **제외**하는 구성 편집에도 최소 2개와 새 첫 프리셋 적용 규칙을 적용한다. OFF에서는 구성 편집으로 활성 ID를 바꾸지 않는다.

### 자동 순환 적용과 세션 보호

- **대상과 효력.** 매주 일정의 로컬 시간대 월요일 00:00부터 다음 프리셋이 전환 대상이 된다. 전환은 성공적으로 적용된 시각부터 유효하다. 예정 시각으로 소급하거나 같은 전환을 중복 적용하지 않는다. 기존 주기적 Backend 작업 구조의 재사용을 우선 검토한다. 현재 기본 60,000ms fixed delay와 G5 자동 완료 → G3 → G4 호출 순서 및 G3/G4의 로컬 04:00 판정 경계는 위 구현 계약을 따른다. 순환 단계의 호출 위치는 G5 보호와 G3/G4의 독립 처리에 맞춰 구현 설계에서 정한다.
- **START와 ACTIVE.** 일반 신규 START의 주 이용 범주는 06:00~23:30으로 본다. 23:50~04:10은 중점 시나리오가 아니며 START를 강제로 막지 않는다. 월요일 경계 직후 Job 실행 전의 짧은 구간만을 위한 별도 순환 확인 체계를 두지 않는다. 실제 START의 시각·InitialPlan·일정 출처를 보존하고 서버에 ACTIVE가 남아 있으면 전환을 보류한다. 강제 종료 없이 정상 FINISH 또는 기존 `WorkoutExpiryJob`의 AUTO_CLOSED 뒤에 재시도한다. 24시간은 최초 START 시각이 아니라 마지막 실제 운동 동기화 시각 기준이다. 미전송 활동을 추정하지 않고 서버의 ACTIVE 상태를 기준으로 보호한다. [세션과 만료](workout-sessions.md#24시간-auto_closed와-만료-경합)
- **서버 중단 복구.** 재시작 후 미처리 전환을 확인하고, 여러 주를 놓쳤더라도 현재 달력 주차에 해당하는 프리셋만 계산해 한 번 적용한다. 실제 복구 적용부터 효력이 발생한다. 놓친 주차를 순서대로 적용하거나 과거 적용 이력을 가공하지 않는다. 서버 중단은 디로딩의 순환 정지와 구분한다. 복구 시에도 ACTIVE 및 G5 보호를 먼저 적용한다.
- **디로딩과 기록.** 디로딩 중 지난 월요일은 순환 위치를 소비하지 않는다. [G5 정지·복귀와 원본 선택](deload-periods.md#주간-일정-프리셋-연동-검토--미구현)을 적용한다. 기존 오프라인 동기화의 늦은 기록은 최초 START 기준으로 처리하고, AUTO_CLOSED 뒤에는 기존 복구 계약을 사용한다. 신규 오프라인 START나 자동 병합은 추가하지 않는다.
