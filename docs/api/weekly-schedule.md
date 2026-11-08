# 다중 주간 일정 프리셋과 적용 이력

Backend는 V30 저장 기반과 신규 가입 초기화(1단계), 프리셋 조회·생성·요일 배정 편집·수동 활성화·삭제 및 루틴 삭제 연동(2단계)을 구현한다. 3단계 자동 순환과 G5 원본 선택·정지·복귀, PWA 신규 API 연결, 실제 DB 적용은 후속 작업이다.

## 2단계 API 계약

모든 경로의 공통 접두어는 `/api/v1/weekly-schedule`이다. 기존 세션 쿠키 인증과 사용자·현재 데이터 세대의 범위 제한을 적용한다. 모든 쓰기는 CSRF 토큰과 JSON 본문이 필요하다. 성공 응답은 모두 전체 상태이며 `Cache-Control: no-store`를 사용한다. 쿼리 매개변수와 GET 본문은 허용하지 않는다.

| 메서드와 접미어 | 성공 | JSON 본문 | 동작 |
| --- | --- | --- | --- |
| `GET` | 200 | 없음 | 읽기 전용 스냅샷을 표시 순번으로 반환 |
| `POST /presets` | 201 | `{dataGeneration, expectedStateVersion}` | 마지막에 순환 제외인 빈 프리셋 추가 |
| `PUT /presets/{id}/assignments` | 200 | `{dataGeneration, expectedStateVersion, expectedPresetVersion, assignments, timeZone?}` | 전체 요일 배정을 한 번에 교체 |
| `PUT /active` | 200 | `{dataGeneration, expectedStateVersion, presetId, timeZone?}` | 자동 순환 OFF에서 지정 프리셋을 수동 활성화 |
| `DELETE /presets/{id}` | 200 | `{dataGeneration, expectedStateVersion, expectedPresetVersion}` | 비활성 프리셋 삭제 후 표시 순번을 연속으로 정리 |

세대와 버전은 0부터 부호 있는 BIGINT 최댓값까지의 정규 십진 **문자열**이다. UUID는 표준 문자열을 사용한다. 배정은 `[{ "dayOfWeek": 1, "routineId": "<UUID>" }]` 형식으로 월요일 1~일요일 7의 중복 없는 정수와, 같은 사용자·세대의 삭제되지 않은 루틴을 받는다. 빠진 요일에는 배정이 없다. 루틴 하나를 여러 프리셋과 요일에 쓸 수 있다. 응답의 배정은 요일 오름차순이다. JSON 중복 키, 추가 필드, 값의 형식 오류를 거절한다.

신규 가입 직후의 응답 예시다.

```json
{
  "dataGeneration": "0",
  "stateVersion": "0",
  "activePresetId": "11111111-1111-4111-8111-111111111111",
  "autoEnabled": false,
  "timeZone": null,
  "nextRotationAt": null,
  "appliedScheduleVersion": null,
  "appliedAt": null,
  "presets": [
    {
      "id": "11111111-1111-4111-8111-111111111111",
      "displayOrder": 1,
      "presetVersion": "0",
      "rotationIncluded": false,
      "assignments": []
    }
  ]
}
```

- 생성은 최대 4개다. 활성 프리셋과 마지막 프리셋의 삭제를 거절한다. 삭제 후 표시 순번만 당기고 남은 ID와 내용 버전은 유지한다.
- 최초 실제 적용은 활성 프리셋에 대한 명시적 저장 또는 활성화에서 이루어진다. 빈 배정도 적용할 수 있다. 적용 전 두 `applied*` 필드는 null이다. GET·생성·비활성 편집은 적용 이력, 달력 기준, G3 날짜를 만들지 않는다.
- `timeZone`은 IANA 시간대다. 달력 또는 G4에 고정된 시간대가 있으면 응답에 제공하고, 적용 시 생략할 수 있다. 미확정 상태에서 최초 실제 적용하려면 입력해야 한다. 비활성 편집은 시간대를 확정하지 않는다.
- `stateVersion`은 생성·삭제·활성 ID 변경·최초 실제 적용 때 증가한다. 같은 명령에서 활성 ID 변경과 최초 적용이 겹치면 한 번 증가한다. `presetVersion`은 그 프리셋의 배정이 바뀔 때 증가한다. 활성 프리셋의 실제 배정 변경은 적용 이력도 추가한다. 비활성 편집은 개별 프리셋에만 영향을 준다.
- 최초 적용 이후 같은 배정의 다른 프리셋을 활성화하면 ID와 상태 버전이 바뀌고, 기존 적용 버전과 시각을 유지한다. 같은 프리셋을 같은 내용으로 다시 저장·활성화하면 무변경이다. 버전은 무변경 여부를 판정하기 전에 확인하며, 충돌 시 재조회해서 다시 편집한다.
- 일반 ACTIVE 운동 중에는 최초 적용, 실제 배정 변경 및 수동 활성 ID 변경을 거절한다. G5 ACTIVE 기간과 완료 당일에는 프리셋 쓰기 전체를 제한하며 조회는 허용한다. 완료 다음 고정 로컬 날짜 또는 무수행 취소 직후에는 변경할 수 있다.

| 상태 | 오류 코드 |
| --- | --- |
| 400 | `INVALID_REQUEST`, `SCHEDULE_TIME_ZONE_REQUIRED` |
| 401 / 403 | 기존 세션 인증 / CSRF 오류 |
| 404 | `PRESET_NOT_FOUND`, `ROUTINE_NOT_FOUND` |
| 409 | `DATA_GENERATION_MISMATCH`, `SCHEDULE_STATE_VERSION_CONFLICT`, `PRESET_VERSION_CONFLICT`, `SCHEDULE_TIME_ZONE_CONFLICT` |
| 409 | `PRESET_STATE_MISSING`, `PRESET_LIMIT_REACHED`, `ACTIVE_PRESET`, `LAST_PRESET` |
| 409 | `ACTIVE_SESSION_EXISTS`, `DELOAD_ACTIVE_PERIOD`, `AUTO_ROTATION_ENABLED`, `ROTATION_REQUIRES_TWO_PRESETS`, `WRITE_CONFLICT` |
| 500 | `WORKOUT_DATA_WRITE_FAILED` |
| 503 | `WORKOUT_DATA_UNAVAILABLE` |

도메인 오류는 `{"code":"…"}` 형식이다. 2단계 API로는 자동 순환을 켤 수 없다. ON 상태에서는 수동 활성화와 구성 변경을 거절하며, 삭제 후 포함 개수가 2개 미만이면 해당 제약을 먼저 보고한다. 이미 ON인 계정의 유효한 구성 변경과 재시작은 3단계에서 함께 제공한다.

## 적용 이력과 트랜잭션

저장 프리셋의 현재 내용은 관리용이다. START·MISS·G3/G4·통계와 기존 G5 원본 조회는 `weekly_schedule_versions` 및 당시 불변 근거를 계속 읽는다. 활성 편집·수동 활성화·루틴 삭제에 의한 실제 적용은 `AppliedWeeklySchedule.apply`를 통과한다. 이 경로는 ACTIVE·달력의 마감 날짜·시간 역행을 검증하고, 루틴 계획 이력과 적용 스냅샷을 기록하며, 최초 적용 시에만 달력 기준을 등록한다. 저장 프리셋·상태와 함께 커밋하거나 함께 롤백한다.

쓰기의 첫 데이터 잠금은 START와 같은 `users FOR UPDATE`다. 이후 현재 세대와 상태·프리셋 버전을 확인하고 같은 `workoutTransactionManager` 트랜잭션에서 처리한다. 조회는 읽기 전용 REPEATABLE READ를 사용한다. `recorded_at`은 사용자 잠금 이후 서버 시각을 마이크로초로 정규화한 값이고, `effective_from`은 저장된 시간대의 해당 날짜다. 같은 시각의 적용은 적용 버전으로 구분한다. 수요일 변경은 월·화의 당시 배정 및 최초 START·MISS·InitialPlan을 소급 변경하지 않는다.

루틴 논리 삭제 시 해당 루틴을 참조하는 모든 저장 프리셋에서 배정을 해제하고 영향받은 프리셋 버전을 올린다. 활성 프리셋이 바뀌면 새 실제 적용 이력을 추가한다. 순환 구성·위치는 유지한다. 루틴, 루틴 계획 이력, 전체 프리셋, 새 일정 이력은 단일 트랜잭션으로 처리한다.

## 단일 일정 API와 구 예약 전환 (이전 구현 기록)

이전 단일 일정 API는 시간대 쿼리를 받던 GET, `contractVersion: "immediate-v1"`을 사용하던 루트 PUT, 페이지 방식의 `GET /history`로 구성됐다. 2단계는 루트 GET을 전체 상태 조회로 교체했다. 루트 PUT과 사용자 화면에서 사용하지 않던 `/history`는 제거했다. 내부 이력은 계속 사용한다.

V24의 initialize_workout_calendar는 해당 owner를 잠그고 가장 최근 저장한 배정 의도를 새 version으로 한 번 승격한다. 기준 version과 그 행의 recorded_at을 구/신 해석 경계로 사용한다. 재실행하면 기존 control이 있어 추가 승격하지 않는다. 구 행/JSON/날짜/이전 migration은 변경하지 않는다. 경계 이전은 effective_from, 이후는 신규 recorded_at/version으로 해석하므로 예전11/09 예약이 나중에 새 현재값을 덮지 않는다.

## M1 저장 구조

- V19~V23은 그대로 둔다. V24 하나에서 월요일 CHECK를 제거하고 사용자/세대/recorded_at/version 범위 인덱스를 추가한다. 별도 applied_at은 없다.
- workout_calendar_control은 사용자/세대1행의 시간대·기준 version·최초 검사 날짜·cursor다. 기존 미등록 계정에 가짜 휴식 일정을 만들지 않는다.
- workout_sessions.schedule_version_at_start는 nullable 범위 FK이며 신규 START에서만 기록한다. 기존 세션은 null 그대로다. DB/Entity가 후속 변경을 막는다. InitialPlan/요청 JSON/hash는 수정하지 않는다.
- daily_training_assessments는 사용자/세대/날짜PK, 첫 예정 START 참조·최초 MISS 시각/일정/루틴 계획 버전 참조·state/revision/dirty/재시도 시각만 저장한다. 계획/수행 JSON을 복제하지 않는다.
- 첫 START/최초 MISS 근거는 설정 후 변경할 수 없다. 일반 루틴·세션 삭제도 원래 계획·이력을 물리 삭제하지 않는다.
- 루틴 삭제의 실제 주간 배정 제외는 같은 트랜잭션에서 즉시 version을 추가한다. 수동 적용과 같은 ACTIVE 검사를 사용하며 실패하면 삭제·계획 이력·일정이 모두 롤백된다.

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

기존 데이터 보존을 전제로 한 당시 V24 전환 기록이다. 다중 프리셋의 전환 기준은 아래 V1 출시 전 전환 절을 따른다.

전환 전 관리 쓰기 정지·새 영구 백업/격리 복원·기존 세션/루틴/승인/이력 지문 대조와 Backend/PWA 동시 전환이 필요하다. 실제 DB/기존 컨테이너/볼륨을 자동 변경하지 않는다. 구 검토 재개 도구의 V23 전용 guard도 이번에 V24 허용으로 바꾸지 않는다.

### 2026-11-07 현행 정책과 적용 범위

디로딩은 [v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md) 기준이며 G4 분석과 G5 기간은 별도 API다. 일정 조회는 유지하며 일정 변경은 ACTIVE 기간과 완료 당일에 409 DELOAD_ACTIVE_PERIOD로 차단한다. 완료 다음 고정 로컬 날짜 또는 무수행 취소 직후에는 변경할 수 있다. 일반 계획 ST-17~23과 디로딩 후보는 분리하며 G3는 분석OFF여도 유지한다. 이 문서의 이전 “이번 실제 DB 미적용”은 해당 개발 단계의 제한이다. 현재 V24가 확인된 대상은 기존 격리 검토DB뿐이며 개인/운영DB는 이번에 확인하거나 변경하지 않았다.

## G5-1 ACTIVE 기간 보호 (당시 구현 범위)

[디로딩 기간 계약](deload-periods.md)의 ACTIVE 기간에는 주간 일정 저장을409 DELOAD_ACTIVE_PERIOD로 차단한다. 읽기와 기존 확정 요청의 무변경 ACK는 유지한다. 기간 시작과 일반 쓰기는 기존 사용자 잠금으로 직렬화하며 [G5-2 임시 일정](deload-periods.md#임시-계획-관리--g5-2)은 별도 계획의 날짜만 변경한다. 기간 종료·최종 일반 검사 재개는 G5-3 전이다. 기존 검토 DB와 서버에는 새 기능을 배포하지 않는다.

## V30 프리셋 저장 기반과 신규 계정 초기화

`weekly_schedule_presets`는 사용자·세대에 속하는 UUID, 표시 순번, 포함 여부, 프리셋 버전과 배정 JSON 배열을 저장한다. 순번은 1~4이며 같은 사용자·세대 안에서 중복될 수 없다. 배정 배열은 최대 7개다. 요일별 단일 배정과 루틴 소유권은 프리셋 편집 서비스가 사용자 잠금 아래 검증한다.

`weekly_schedule_state`는 사용자·세대당 한 행으로 활성 프리셋, 순환 설정, 상태 버전과 다음 순환 시각을 저장한다. 복합 FK는 다른 사용자·세대의 프리셋을 참조하거나 현재 활성 프리셋을 삭제하는 것을 막는다. OFF에서는 다음 순환 시각이 비어 있어야 한다. 이후 Job이 시각이 있는 계정을 검색할 수 있도록 인덱스를 둔다.

인증된 신규 계정의 가입 트랜잭션에서 빈 1번 프리셋 하나(순환 제외, 버전 0)와 이를 활성으로 참조하는 상태(순환 OFF, 버전 0, 다음 시각 없음)를 삽입한다. 기존 G4 분석 기본 ON은 별개다. 가입 실패는 두 행을 포함한 계정 생성 전체를 롤백한다. 로그인 재진입과 가입 경쟁의 패자는 프리셋을 추가하지 않는다.

V30은 기존 계정을 채우지 않는다. 신규 가입도 `weekly_schedule_versions`, `workout_calendar_control`, `daily_training_assessments`에 행을 만들지 않는다. 빈 프리셋은 최초 실제 적용이나 과거 휴식·미수행 근거를 의미하지 않는다.

<a id="다중-주간-일정-프리셋-확장-검토--미구현"></a>

## V1 출시 전 전환과 3단계 범위

이전 단일 일정 데이터를 1번 프리셋으로 이행하거나 기존 테스트 계정을 보존할 의무는 없다. V30 상태가 없는 계정에 GET이나 로그인으로 프리셋을 생성하지 않는다. 전환은 테스트 데이터 초기화 또는 단순 재사용을 검토할 수 있으며, 실제 DB 초기화·덮어쓰기·Flyway 이력 변경은 AGENTS.md 15절의 사전 승인 범위다. 2단계에서는 V30을 그대로 사용한다.

3단계에서는 ON 전환·포함/제외와 전체 순서 편집·월요일 Job·중단 복구와 [G5 원본 선택·정지·복귀](deload-periods.md#주간-일정-프리셋-연동-검토--미구현)를 함께 검증한다. ON에서 전체 저장 순서 또는 포함 구성이 실제로 달라지면 새 첫 포함 프리셋부터 재시작한다. 이 규칙은 제외 그룹 내 순서 변경에도 적용한다. 요일 배정 편집은 순환을 재시작하지 않는다. 이 순서 규칙은 PWA v0.9 결정 로그 10.16절의 이전 예외보다 최신이다.

### 자동 순환 적용과 세션 보호

- **대상과 효력.** 매주 일정의 로컬 시간대 월요일 00:00부터 다음 프리셋이 전환 대상이 된다. 전환은 성공적으로 적용된 시각부터 유효하다. 예정 시각으로 소급하거나 같은 전환을 중복 적용하지 않는다. 기존 주기적 Backend 작업 구조의 재사용을 우선 검토한다. 현재 기본 60,000ms fixed delay와 G5 자동 완료 → G3 → G4 호출 순서 및 G3/G4의 로컬 04:00 판정 경계는 위 구현 계약을 따른다. 순환 단계의 호출 위치는 G5 보호와 G3/G4의 독립 처리에 맞춰 구현 설계에서 정한다.
- **START와 ACTIVE.** 일반 신규 START의 주 이용 범주는 06:00~23:30으로 본다. 23:50~04:10은 중점 시나리오가 아니며 START를 강제로 막지 않는다. 월요일 경계 직후 Job 실행 전의 짧은 구간만을 위한 별도 순환 확인 체계를 두지 않는다. 실제 START의 시각·InitialPlan·일정 출처를 보존하고 서버에 ACTIVE가 남아 있으면 전환을 보류한다. 강제 종료 없이 정상 FINISH 또는 기존 `WorkoutExpiryJob`의 AUTO_CLOSED 뒤에 재시도한다. 24시간은 최초 START 시각이 아니라 마지막 실제 운동 동기화 시각 기준이다. 미전송 활동을 추정하지 않고 서버의 ACTIVE 상태를 기준으로 보호한다. [세션과 만료](workout-sessions.md#24시간-auto_closed와-만료-경합)
- **서버 중단 복구.** 재시작 후 미처리 전환을 확인하고, 여러 주를 놓쳤더라도 현재 달력 주차에 해당하는 프리셋만 계산해 한 번 적용한다. 실제 복구 적용부터 효력이 발생한다. 놓친 주차를 순서대로 적용하거나 과거 적용 이력을 가공하지 않는다. 서버 중단은 디로딩의 순환 정지와 구분한다. 복구 시에도 ACTIVE 및 G5 보호를 먼저 적용한다.
- **디로딩과 기록.** 디로딩 중 지난 월요일은 순환 위치를 소비하지 않는다. [G5 정지·복귀와 원본 선택](deload-periods.md#주간-일정-프리셋-연동-검토--미구현)을 적용한다. 기존 오프라인 동기화의 늦은 기록은 최초 START 기준으로 처리하고, AUTO_CLOSED 뒤에는 기존 복구 계약을 사용한다. 신규 오프라인 START나 자동 병합은 추가하지 않는다.
