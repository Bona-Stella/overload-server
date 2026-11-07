# 디로딩 분석 설정·epoch API — G4-1

2026-11-07. [제품 v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md) DL-05와 [G4 준비 계약](../../../overload-pwa/docs/tasks/deload-v17-backend-readiness-analysis.md#5-g4-개발-범위의존성검증-계획)을 구현한다. **설정/epoch와 [G4-3 내부 후보/E/N 계산](../tasks/deload-g4-3-report.md)은 구현했으며 04시 확정·누적 저장·재계산·디로딩 기간은 미구현**이다. PWA 연결은 변경하지 않는다.

## GET /api/v1/deload-analysis

인증 세션의 사용자/현재 dataGeneration만 조회한다. query와 본문은 받지 않는다. read-only REPEATABLE_READ이며 누락 설정을 자동 생성하지 않는다. 성공 응답은200·no-store다.

```json
{
  "userId": "00000000-0000-0000-0000-000000000001",
  "dataGeneration": "0",
  "enabled": true,
  "version": "0",
  "epoch": "0",
  "epochStartedAt": "2026-11-07T00:00:00Z",
  "timeZone": null,
  "calculationStatus": "AWAITING_TIME_ZONE",
  "epochInitialWeeks": {"numerator": "0", "denominator": "1"},
  "confirmedWeeks": null
}
```

`epochInitialWeeks`는 그 epoch의 초기값0이라는 뜻이며 실제 확정 누적이 아니다. G4-3 내부 E/N은 외부 확정 누적이 아니므로 `confirmedWeeks`는 항상null이다. 마지막 확정 범위·입력 revision·재계산 상태는 G4-4~6에서 구현한다. 현재 `calculationStatus`는 OFF면 DISABLED, ON/시간대 미확정이면 AWAITING_TIME_ZONE, ON/시간대 확정이면 NOT_IMPLEMENTED다. ON/OFF와 실제 계산 구현 여부를 혼동하지 않는다.

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

변경 시각은 owner 잠금 후 PostgreSQL `clock_timestamp()`의 마이크로초 값이다. 가입은 DB users.created_at을 사용한다. 서버 시각 역행/버전 overflow는 충돌로 거절하며 현재 시각을 임의 보정하지 않는다. 재로그인은 설정을 재seed하지 않는다. OFF는 일반 기록·START/FINISH·G3·통계를 끄지 않는다. 수동 시작/실제 기간은 G5 책임이며 이 API에서 기간을 종료하지 않는다.

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

## V25와 명시적 기존 계정 이행

V25는 `deload_analysis_versions`와 `deload_weekly_assessments` 및 scope/FK/CHECK/전이 제약만 추가한다. 기존 V1~V24·운동/계획/수행/승인 JSON·hash는 변경하지 않는다. versions는 현재값과 설정 이력의 단일 저장소이고 epoch는 그 시작 version을 참조한다. 별도 current 설정·누적 캐시·일별 점수·이벤트 표는 없다. 주간 표는 정확 분수/상태/revision 저장 제약만 있으며 계산·저장 job은 없다.

**스키마 migration은 기존 계정을 채우지 않는다.** 내부 `DeloadAnalysisService.adoptExisting(owner,generation,T_adopt)`만 명시적 경계로 이행할 수 있도록 준비했다. HTTP·scheduler·startup hook·자동 T_adopt는 없다. owner 잠금·현재세대 확인 후 설정이 없을 때만 ADOPTED_INITIAL·ON·version0/epoch0을 삽입한다. T_adopt는 필수 마이크로초 시각이고 users.created_at 이전/서버 미래를 거부한다. 기존 일정 시간대는 재사용한다. 이미 설정이 있으면 선택을 보존하며 재실행/동시 호출은 한 번만 생성한다. 과거 users.created_at으로 소급하지 않는다. 실제 기록의 ON/epoch 구간 필터는 G4-3 내부 계산에서 사용하며 누적 확정/재계산은 후속 G4 범위다.

실제 적용은 대상·전환 시각 확정, 가입/관리 쓰기 정지, 영구 백업·격리 복원, 스키마 적용·명시적 이행 검증 및 별도 승인이 선행되어야 한다. **이번에는 Testcontainers에만 V25를 적용하며 기존 검토 DB(V24), 개인/운영 DB에는 migration/이행을 실행하지 않는다.** 기존 검토 서버를 새 빌드로 재시작하지 않는다.
