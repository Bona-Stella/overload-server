# 일반 온라인 휴식 설정과 개인 운동 접근 경계

현재 휴식 설정 GET/PATCH는 [Task009 v1.0](../tasks/task-009-redesign-v1.0.md)을 따른다. [Task008 GET 보고서](../tasks/task-008-1a-workout-read-api-report.md)는 제거 전 개인 운동 조회도 포함한 과거 기록이다. 현재 운동 선택은 [표준 카탈로그](standard-exercise-catalog.md)를 사용한다. 온라인 호출만 제공하며 클라이언트의 자동 재전송 큐는 제공하지 않는다.

## V17 현재 접근 경계

일반 사용자 POST /api/v1/exercises 및 PATCH /api/v1/exercises/{id}는 제품 Security 경계에서403 ACCESS_DENIED로 차단한다. 익명/만료401과 CSRF403은 기존 정책을 따른다. 차단된 개인 생성·이름 변경의 Controller·Service·전용 DTO와 Entity/Repository 쓰기 함수는 제거했다. 일반 사용자에게 성공 경로를 제공하지 않는다. 2026-11-03 승인으로 GET /api/v1/exercises와 전용 Entity·Repository도 제거했다. exercise_definitions 테이블·기존 데이터·Flyway 이력은 보존하고 휴식 설정 GET/PATCH와 표준 카탈로그 API는 유지한다. 이전 경로의 거절 테스트는 현재 보안 경계를 검증한다.

가입 시 개인 운동 정의는0개이며 사용자·신원·휴식 기본값만 생성한다. 새 루틴·세션은 표준 카탈로그 UUID만 사용한다.

## 요청과 응답

모두 인증 세션 쿠키가 필요하며 PATCH에는 `X-CSRF-TOKEN`과 Content-Type application/json이 필요하다. query·미지원 필드·null·잘못된 타입을 거부하고 GET 본문도 거절한다. `dataGeneration`은 GET에서 받은 현재 세대의 정규화된 음이 아닌 long 십진 **문자열**이다. userId는 입력받지 않고 세션에서 결정한다.

| 메서드·URL | JSON 요청 | 성공 |
|---|---|---|
| GET /api/v1/workout-settings | 없음 | 200 |
| PATCH /api/v1/workout-settings | `{"dataGeneration":"0","restLowSeconds":180,"restMediumSeconds":120,"restHighSeconds":90}` | 200 |

설정 응답은 GET/PATCH 모두 `{userId,dataGeneration,restLowSeconds,restMediumSeconds,restHighSeconds}`이며 세 휴식 시간은 정수 초다. 모든 성공/명시적 오류는 no-store이며 기존 X-Request-ID를 유지한다. 개인 운동 목록·상세·생성 응답이나 Location 계약은 현재 제공하지 않는다.

휴식은 기존 INTEGER/설정 모델에 맞춰 1~2147483647의 정수 초다. 소수·문자열 숫자·boolean은 허용하지 않는다. 세 값을 모두 지정해 원자적으로 변경한다.

## 오류·트랜잭션

오류 본문은 기존 형식인 `{"code":"..."}`뿐이다.

| HTTP | code | 의미 |
|---|---|---|
| 400 | INVALID_REQUEST | 잘못된 JSON/타입/값·미지원 필드/query/UUID |
| 401 | AUTHENTICATION_REQUIRED | 비로그인·만료·users 부재 |
| 403 | CSRF_INVALID / ACCESS_DENIED | 기존 보안 처리 |
| 409 | DATA_GENERATION_MISMATCH | 요청 세대가 현재 계정 세대와 다름 |
| 409 | WRITE_CONFLICT | JPA 낙관적 잠금·직렬화/교착 충돌. 자동 재시도 없음 |
| 500 | WORKOUT_SETTINGS_MISSING | 현재 세대 설정 부재. 자동 복구/seed하지 않음 |
| 500 | WORKOUT_DATA_READ_FAILED / WORKOUT_DATA_WRITE_FAILED | 예상 밖 조회/쓰기 DB·내부 오류 |
| 503 | WORKOUT_DATA_UNAVAILABLE | 확인된 연결·timeout·일시 종료 오류 |

GET은 read-only REPEATABLE_READ, PATCH는 기존 workoutTransactionManager의 JPA 트랜잭션 하나에서 실행한다. PATCH는 같은 연결의 users FOR SHARE로 작업 도중 계정 삭제/세대 변경을 막고, 설정은 반드시 owner+generation으로 조회한다. 공유 잠금은 같은 사용자 쓰기끼리 직렬화하지 않는다. 필요한 변경만 dirty checking하고 flush/commit 시 기존 @Version으로 중첩된 수정의 유실을 방지한다. 같은 값의 순차 재요청은 timestamp/version을 바꾸지 않는다.

기존 GET에는 version이 없으므로 새 expectedVersion 계약을 강제하지 않는다. @Version은 **동시에 겹친 DB 트랜잭션**을 보호하며 이미 완료된 다른 수정 이후 도착한 명시적 PATCH까지 오래된 화면으로 판정하는 것은 아니다. 클라이언트는409에서 다시 조회하고 사용자가 재시도할지 결정해야 한다. 오프라인 큐·자동 병합·receipt/feed·추가 schema/의존성은 없다.

## 일반 운동 UI 연결 상태 — 2026-11-13

일반 운동은 위 GET의 세 휴식 시간과 기존 min/max 반복 구간 선택을 사용한다. 마지막 계획 세트는 휴식을 만들지 않고, 수동 종료/30초 연장은 현재 기기 타이머에 적용한다. 5~1초 기본 효과음·시각 강조와 수동 무음 종료를 구현했으나 독립 휴식 알림 토글/리드 시간의 서버 필드는 없다. 현재 UI 시연이나 로컬 효과음을 설정 PATCH의 새 필드로 문서화하지 않는다. 기구 메모 서버 저장도 이 API 범위에 없다.
