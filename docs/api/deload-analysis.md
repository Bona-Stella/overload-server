# 디로딩 분석 설정·epoch·확정 누적 API — G4

2026-11-08. [제품 v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md) DL-05~14와 [G4 준비 계약](../../../overload-pwa/docs/tasks/deload-v17-backend-readiness-analysis.md#5-g4-개발-범위의존성검증-계획)을 따른다. 설정/epoch·공통 판정·후보/E/N·주간 확정·재계산과 [G4-6 조회 연동](../tasks/deload-g4-6-report.md)을 구현했다. [G5-1 기간 시작/최초 임시 계획](deload-periods.md)을 추가했으며 실제 DB 적용·G5-2/3·G6 PWA 연결은 별도다.

## GET /api/v1/deload-analysis

인증 principal의 사용자/현재 dataGeneration만 조회한다. query와 본문은 받지 않는다. read-only REPEATABLE_READ의 한 snapshot이며 누락 설정을 자동 생성하지 않는다. 성공200·no-store, 오류는 기존 code-only/no-store 관례다. GET은 분석 설정·주간 결과·dirty·G3 cursor·운동 원본을 쓰지 않고 owner 잠금도 잡지 않는다. HTTP 세션 저장 등 기존 인증 인프라와 분석 도메인 쓰기를 구분한다.

다음은 격리 fixture의 RESET→ADD 정정 후4.75주 응답 예시이며 userId만 예시 값이다.

```json
{
  "userId": "00000000-0000-0000-0000-000000000001",
  "dataGeneration": "0",
  "enabled": true,
  "version": "1",
  "epoch": "0",
  "epochStartedAt": "2026-10-25T15:00:00Z",
  "timeZone": "Asia/Seoul",
  "calculationStatus": "CONFIRMED",
  "epochInitialWeeks": {"numerator": "0", "denominator": "1"},
  "confirmedWeeks": {"numerator": "19", "denominator": "4"},
  "confirmedFrom": "2026-10-26",
  "confirmedThrough": "2026-11-23",
  "blockedAt": null,
  "recommendationThreshold": {"numerator": "8", "denominator": "1"},
  "recommendationReady": false
}
```

기존 필드와 분수/Counter의 십진 문자열 타입을 유지하며 confirmedFrom/confirmedThrough/blockedAt/recommendationThreshold/recommendationReady만 추가한다. version은 설정의 expectedVersion이며 주간 결과 version이 아니다. 같은 설정 version에서도 원본·Job 변화로 조회 결과가 달라질 수 있다. NOT_IMPLEMENTED는 다음 실제 상태로 교체한다.

| calculationStatus | 의미 |
|---|---|
| DISABLED | OFF. 누적·범위·blockedAt=null, 추천false. 저장 이력/일반 운동/G3/통계는 유지 |
| AWAITING_TIME_ZONE | ON이나 분석 시간대 미확정. 누적·범위=null, 추천false |
| INITIAL | 시간대가 있지만 아직 확정 시각에 도달한 평가 주가 없음. 초기 기준0만 알려졌으며 confirmedWeeks/범위=null |
| PAUSED | G5-1 기간이 현재 ON 구간과 겹침. 더 이른 미해결이 없으면 마지막 신뢰 prefix/blockedAt을 제공하고 추천false. 최종 재개는 G5-3 |
| CONFIRMED | 필요한 마지막 due 주까지 연속된 저장 결과가 신뢰 가능. 정확 누적과 범위 제공 |
| AWAITING_REVIEW | 첫 미해결 주에 필요한 G3 cursor/날짜 검사/시간대 근거가 미완료. 확정 prefix만 제공 |
| PENDING | due gap 또는 아직 확정하지 않은 주. Job 대기·후보 UNKNOWN/ACTIVE 등을 포함하며 확정 prefix만 제공 |
| RECALCULATING | 첫 미해결 위치 이후 기존 확정 결과/재검사 표식이 있어 재평가 필요. source revision 변화도 저장 없이 발견하며 확정 prefix만 제공 |

상태 우선순위는 OFF → 시간대 부재 → 더 이른 미해결이 없는 기간 PAUSED → 아직 due 없음 → 미해결 구간의 재검사/기존 확정 suffix → G3 검사 대기 → 일반 보류 → 전체 확정이다. 재계산과 G3 대기가 겹치면 RECALCULATING으로 반환한다. 이 상태는 작업 worker 실행 중이라는 뜻이 아니라 재검사 필요·대기·진행을 포함한다.

epochInitialWeeks=0/1은 초기 기준이고 완료값이 아니다. 첫 해결 주가 없으면 confirmedWeeks/confirmedFrom/confirmedThrough=null이다. 확정 RESET0은 confirmedWeeks=0/1과 non-null 범위로 구분한다. confirmedThrough는 마지막 해결 주의 월요일(그 주 일요일까지)이다. 범위는 달력 주 라벨이며 각 주의 실제 평가 날짜는 epoch/ON 경계를 따른다. 범위에는 EXCLUDED도 포함되지만 이는 훈련 주수나 N0 RESET이 아니다. 여행 workoutDate 때문에 confirmedFrom이 epoch 시작의 분석 시간대 월요일보다 앞설 수 있으며, 실제 세션의 startedAt ON 경계는 그대로 유지한다.

현재 epoch의 저장 결과를 week_start 순으로 기존 fold에 전달한다. RESET0·정확 ADD·EXCLUDED 무변경을 적용하고 첫 gap/PENDING/dirty/규칙 불일치에서 중단한다. blockedAt은 그 월요일이며 RECALCULATING에서는 재계산 시작 위치다. 뒤의 ADD를 건너뛰어 공개하지 않는다. 표시할 확정 prefix가 없으면 null이며 가짜0을 만들지 않는다. 이전 epoch는 읽어 합산하지 않는다.

GET은 저장 입력의 날짜7개 revision 벡터와 rule/hash 형식을 최신 G3 revision에 대조하여 snapshot 안에서만 유효 dirty를 계산한다. 설정/주간 행을 생성하거나 dirty를 저장·해소하지 않는다. G4 Job과 같은 최초 주 경계 탐색을 사용하며, 일반 기록의 완료 판정·주간 E/N을 매 GET 다시 계산하지 않는다. 최초 주보다 앞선 여행 날짜의 실제 후보/ACTIVE 존재만 탐색한다. 수행 없는 ACTIVE는 N에 가산하지 않고 보류하며, 빈 종료가 확정되면 Job이 EXCLUDED 처리한다.

추천은 정확 분수가8 이상이고 전체 due 범위가 CONFIRMED인 경우에만 recommendationReady=true다. 보류/재계산 중에는 prefix가8 이상이어도false다. 반올림 표시값·게이지 clamp로 판정하지 않고8 초과 원본 누적도 그대로 반환한다. 추천은 강제/기간 자동 시작/수동 시작 자격이 아니며 G5의 네 조건과 별개다.

동시 Job·설정·정정은 snapshot 시작 전 또는 이후 한 시점의 일관된 값으로 보인다. 조회 중 새 쓰기가 commit됐더라도 그 GET은 이전 snapshot을 유지하며 다음 GET에서 새 상태를 본다. 설정과 다른 epoch/세대의 누적을 섞지 않는다. 별도 누적 캐시·새 scheduler·새 migration은 없다.

## PUT /api/v1/deload-analysis/settings

세션 쿠키·`X-CSRF-TOKEN`·application/json이 필요하다. query는 허용하지 않는다.

```json
{"dataGeneration":"0","expectedVersion":"0","enabled":false,"timeZone":"Asia/Seoul"}
```

정확히 네 필드를 받는다. Counter는 정규화된 음이 아닌 long 십진 문자열이며 boolean/숫자형 Counter·선행0·중복 JSON 키·null·미지원 필드를 거절한다. enabled는 boolean, timeZone은 명시적 IANA ZoneId다. userId/epoch/누적/기준 시각은 받지 않는다.

owner FOR UPDATE·READ_COMMITTED에서 현재 세대→설정 존재→expectedVersion→일정/분석 고정 시간대→실제 전이를 검사한다. version 검사가 no-op보다 먼저다. 성공200은 GET과 같은 응답·no-store다.

| 전이 | 결과 |
|---|---|
| 신규 가입 | enabled=true·version0/epoch0·INITIAL. users.created_at이 recorded_at/epoch_started_at이며 계정/identity/휴식 설정과 함께 원자 생성 |
| 시간대 최초 확정 | version+1·TIME_ZONE_SET, enabled/epoch/epoch_started_at 유지 |
| ON→OFF | version+1·DISABLED, 기존 epoch/기준 시각 유지. 최초 시간대 지정과 함께 처리 가능 |
| OFF→ON | version+1·ENABLED, 그 새 version이 epoch 식별자, 서버 전환 시각이 새 epoch_started_at·초기0 |
| 동일 enabled·동일 확정 시간대 | no-op, 이력/버전/시각/epoch 불변 |
| ACK 유실 후 옛 expectedVersion 재요청 |409 후 GET 재확인. 최신 상태로 자동 재전이/병합하지 않음 |

변경 시각은 owner 잠금 후 PostgreSQL `clock_timestamp()`의 마이크로초 값이다. 가입은 DB users.created_at을 사용한다. 서버 시각 역행/버전 overflow는 충돌로 거절하며 현재 시각을 임의 보정하지 않는다. 재로그인은 설정을 재seed하지 않는다. OFF는 일반 기록·START/FINISH·G3·통계를 끄지 않는다. 수동 시작/실제 기간은 [G5-1 별도 API](deload-periods.md) 책임이며 이 설정 API에서 기간을 종료하지 않는다.

## 시간대와 기존 쓰기 경계

가입 계약에는 시간대가 없으므로 초기 ON·epoch 시각·초기0만 확정하고 timeZone=null을 허용한다. JVM 기본 시간대는 쓰지 않는다. 현재 일정의 고정 시간대가 있으면 그 값과 일치해야 한다.

새 일반 START와 일정 저장은 기존 owner 잠금 트랜잭션에서 최초 분석 시간대를 함께 확정한다. 실패하면 설정 변경도 함께 rollback한다. 중복 START ACK는 초기화/시간대 변경을 반복하지 않는다. 이미 확정된 분석 시간대와 START의 여행/수행 문맥 시간대는 별개다. 후속 START의 startedTimeZone/workoutDate는 그대로 보존하고 분석 시간대를 덮지 않는다. 일정은 고정 분석 시간대와 충돌하면409다. 설정 쓰기·최초 START·일정 저장의 경쟁은 같은 owner 잠금으로 직렬화한다.

## 오류

본문은 기존 `{"code":"..."}` 형태이며 오류도 no-store다.

| HTTP | code |
|---|---|
|400|INVALID_REQUEST|
|401|AUTHENTICATION_REQUIRED|
|403|CSRF_INVALID / ACCESS_DENIED|
|409|DATA_GENERATION_MISMATCH / DELOAD_VERSION_CONFLICT / DELOAD_TIME_ZONE_CONFLICT / WRITE_CONFLICT|
|500|DELOAD_SETTINGS_MISSING / DELOAD_DATA_FAILED|
|503|DELOAD_DATA_UNAVAILABLE|

누락 설정은 무결성/이행 부재 오류이며 GET·PUT·START에서 조용히 신규 계정으로 취급하지 않는다. DB 세부 오류/자격 정보는 응답이나 로그에 노출하지 않는다.

## V25/V26와 명시적 기존 계정 이행

V25는 `deload_analysis_versions`와 `deload_weekly_assessments` 및 scope/FK/CHECK/전이 제약만 추가한다. 기존 V1~V24·운동/계획/수행/승인 JSON·hash는 변경하지 않는다. versions는 현재값과 설정 이력의 단일 저장소이고 epoch는 그 시작 version을 참조한다. 별도 current 설정·누적 캐시·일별 점수·이벤트 표는 없다. 주간 표는 G4-4에서 정확 E/N·RESET/ADD·기여·상태/ruleVersion/revision·시각을 저장한다. input_revision은 canonical 계산 SHA-256과 날짜별 source revision 전체 벡터를 담은 JSON TEXT다. 기존 G3 Job의 tick 뒤에 독립 분석 배치를 연결했고 별도 scheduler는 없다. G3 dirty 해제와 같은 트랜잭션에서 주간 needs_recheck를 보존하며, 확정 dirty 주는 G4-5에서 기존 숫자를 보존하고 가장 이른 영향 주부터 같은 epoch의 후속 주를 재평가한다. ON 구간 밖 여행 주가 마지막 실제 수행 삭제로 분석 제외되는 경우 승인된 V26 EXCLUDED 상태를 저장한다. E/N·operation·contribution은 NULL이고 판정 확정 시각·입력 식별·revision을 보존한다. 정확히 OUTSIDE_ON_INTERVAL만 있고 날짜/후보가 없는 결과를 두 번 확인한 뒤 CAS로 확정한다. 이는 N0 RESET과 다르며 fold에서 가산/RESET 없이 진행한다. EXCLUDED도 입력이 바뀌면 dirty로 차단하고 정상 후보가 다시 생기면 CONFIRMED로 재평가한다. [G4-5 근거](../tasks/deload-g4-5-report.md)를 따른다. current-total 캐시나 중복 일별 저장소는 없다.

**스키마 migration은 기존 계정을 채우지 않는다.** 내부 `DeloadAnalysisService.adoptExisting(owner,generation,T_adopt)`만 명시적 경계로 이행할 수 있도록 준비했다. HTTP·scheduler·startup hook·자동 T_adopt는 없다. owner 잠금·현재세대 확인 후 설정이 없을 때만 ADOPTED_INITIAL·ON·version0/epoch0을 삽입한다. T_adopt는 필수 마이크로초 시각이고 users.created_at 이전/서버 미래를 거부한다. 기존 일정 시간대는 재사용한다. 이미 설정이 있으면 선택을 보존하며 재실행/동시 호출은 한 번만 생성한다. 과거 users.created_at으로 소급하지 않는다. 실제 기록의 ON/epoch 구간 필터는 G4-3 내부 계산에서 사용하며 주간 확정/내부 fold는 G4-4, 과거 정정 재계산/최종 조회는 G4-5~6 범위다.

실제 적용은 대상·전환 시각 확정, 가입/관리 쓰기 정지, 영구 백업·격리 복원, 스키마 적용·명시적 이행 검증 및 별도 승인이 선행되어야 한다. **V25/V26은 Testcontainers에서만 검증했으며 기존 검토 DB(V24), 개인/운영 DB에는 migration/이행을 실행하지 않는다.** 기존 검토 서버를 새 빌드로 재시작하지 않는다.
