# 일반 온라인 종목·휴식 설정 쓰기 (Task 009 1차)

기준은 [Task009 v1.0](../tasks/task-009-redesign-v1.0.md)과 [Task006](../tasks/task-006-integrated-design-v0.3.1.md)의 종목 UUID 동일성·동명이종목 허용 정책이다. 기존 [Task008 GET](../tasks/task-008-1a-workout-read-api-report.md) 두 개의 URL·본문·오류·정렬·읽기 트랜잭션은 변경하지 않는다. 온라인 호출만 제공하며 클라이언트의 자동 재전송 큐는 제공하지 않는다.

## V17 현재 접근 경계

일반 사용자 POST /api/v1/exercises 및 PATCH /api/v1/exercises/{id}는 제품 Security 경계에서403 ACCESS_DENIED로 차단한다. 익명/만료401과 CSRF403은 기존 정책을 따른다. Controller·Service·Entity·Repository의 개인 CRUD 내부 구현은 보존하며 아래 개인 CRUD의 성공·검증 계약은 보관된 내부 동작을 설명한다. 일반 사용자에게 성공 경로를 제공하지 않는다. GET 개인 목록의 기존 의미와 휴식 설정 API는 유지한다.

가입 시 개인 운동 정의는0개이며 사용자·신원·휴식 기본값만 생성한다. 새 루틴·세션은 표준 카탈로그 UUID만 사용한다.

## 요청과 응답

인증 세션 쿠키와 `X-CSRF-TOKEN`이 필요하다. Content-Type은 application/json이며 query·미지원 필드·null·잘못된 타입을 거부한다. `dataGeneration`은 GET에서 받은 현재 세대의 정규화된 음이 아닌 long 십진 **문자열**이다. userId는 입력받지 않고 세션에서 결정한다.

| 메서드·URL | JSON 요청 | 성공 |
|---|---|---|
| POST /api/v1/exercises | `{"id":"UUID","dataGeneration":"0","name":"벤치프레스"}` | 제품403 ACCESS_DENIED (내부 구현: 신규201/중복200) |
| PATCH /api/v1/exercises/{id} | `{"dataGeneration":"0","name":"바벨 벤치프레스"}` | 제품403 ACCESS_DENIED (내부 구현: 200) |
| PATCH /api/v1/workout-settings | `{"dataGeneration":"0","restLowSeconds":180,"restMediumSeconds":120,"restHighSeconds":90}` | 200 |

종목 응답은 `{"userId":"UUID","dataGeneration":"0","id":"UUID","name":"벤치프레스"}`이다. 설정 응답은 기존 GET과 동일한 userId·문자열 dataGeneration·세 정수 초다. 모든 성공/명시적 오류는 no-store이며 기존 X-Request-ID를 유지한다. 생성 응답 Location은 종목 PATCH URI다. 개별 종목 GET은 추가하지 않는다.

이름은 공백만 있는 값·NUL·잘못된 Unicode를 거부하고 원래 공백과 Unicode를 보존한다. 임의 길이 제한·trim·이름 UNIQUE는 추가하지 않는다. 휴식은 기존 INTEGER/설정 모델에 맞춰 1~2147483647의 정수 초다. 소수·문자열 숫자·boolean은 허용하지 않는다. 세 값을 모두 지정해 원자적으로 변경한다.

생성 UUID는 요청자가 고정해 제공한다. 같은 사용자/세대의 동일 UUID와 **현재** 이름이 일치하면 기존 종목을 반환한다. 동일 UUID의 다른 내용·삭제된 UUID·타 계정 UUID 점유·동시 생성 PK 충돌은 409이고 덮어쓰지 않는다. 별도 명령 영수증이 없으므로 과거 생성 응답을 재현하지 않는다. 이후 이름이 바뀐 종목에 이전 생성 본문을 다시 보내면409다. 동명이지만 다른 UUID면 별도 종목으로 생성한다.

## 오류·트랜잭션

오류 본문은 기존 형식인 `{"code":"..."}`뿐이다.

| HTTP | code | 의미 |
|---|---|---|
| 400 | INVALID_REQUEST | 잘못된 JSON/타입/값·미지원 필드/query/UUID |
| 401 | AUTHENTICATION_REQUIRED | 비로그인·만료·users 부재 |
| 403 | CSRF_INVALID / ACCESS_DENIED | 기존 보안 처리 |
| 404 | EXERCISE_NOT_FOUND | 본인 현재 세대의 비삭제 종목이 아님 |
| 409 | DATA_GENERATION_MISMATCH | 요청 세대가 현재 계정 세대와 다름 |
| 409 | EXERCISE_ID_CONFLICT | 생성 UUID 점유/내용 충돌. 점유 계정 정보는 반환하지 않음 |
| 409 | WRITE_CONFLICT | JPA 낙관적 잠금·직렬화/교착 충돌. 자동 재시도 없음 |
| 500 | WORKOUT_SETTINGS_MISSING | 현재 세대 설정 부재. 자동 복구/seed하지 않음 |
| 500 | WORKOUT_DATA_WRITE_FAILED | 예상 밖 DB/내부 오류 |
| 503 | WORKOUT_DATA_UNAVAILABLE | 확인된 연결·timeout·일시 종료 오류 |

각 서비스 작업은 기존 workoutTransactionManager의 JPA 트랜잭션 하나에서 실행한다. 같은 연결의 users FOR SHARE로 작업 도중 계정 삭제/세대 변경을 막고, 종목·설정은 반드시 owner+generation으로 조회한다. 공유 잠금은 같은 사용자 쓰기끼리 직렬화하지 않는다. 필요한 변경만 dirty checking하고 flush/commit 시 기존 @Version으로 중첩된 수정의 유실을 방지한다. 같은 값의 순차 재요청은 timestamp/version을 바꾸지 않는다.

기존 GET에는 version이 없으므로 새 expectedVersion 계약을 강제하지 않는다. @Version은 **동시에 겹친 DB 트랜잭션**을 보호하며 이미 완료된 다른 수정 이후 도착한 명시적 PATCH까지 오래된 화면으로 판정하는 것은 아니다. 클라이언트는409에서 다시 조회하고 사용자가 재시도할지 결정해야 한다. 오프라인 큐·자동 병합·receipt/feed·추가 schema/의존성은 없다.
