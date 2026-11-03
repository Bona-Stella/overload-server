# 계정 신원 조회·이메일 OTP 재인증 (Task 007-1C-3B-1)

현재는 [공통 대표 재인증 이메일 계약](reauthentication-email.md)을 적용한다. EMAIL/GOOGLE 로그인과 별개인
사용자당 VERIFIED 대표 이메일 하나를 사용한다. 계정 생성, 신원 연결·이동·삭제·병합은 호출하지 않는다.
Google-only 계정도 기존 Google 신원의 새 OIDC 확인과 실제 이메일 OTP를 거쳐 최초 등록할 수 있다.
Google auth_time 재인증은 별도 기본 비활성 실험 기능이며, 민감 업무 쓰기 API와 프론트 연동은 미구현이다.

## HTTP 계약

모든 응답은 `Cache-Control: no-store`다. userId는 서버 `SessionPrincipal`에서만 얻는다.

| 요청 | JSON | 성공 |
|---|---|---|
| GET /api/v1/auth/identities | 없음 | 200 `{identities:[{id,provider}]}` |
| POST /api/v1/auth/reauth/email/request | `{}` | 202 `{status:"ACCEPTED",challengeId}` |
| POST /api/v1/auth/reauth/email/verify | `{challengeId,code}` | 204, 회전된 SESSION 쿠키 |

challengeId는 UUID, code는 선행 0을 보존하는 6자리 문자열이다.
조회는 자기 계정의 identity UUID와 소문자 provider만 반환한다. 원문/가린 이메일, issuer, sub, OAuth 토큰은 반환하지 않는다.
재인증 대상은 인증된 사용자에 대해 서버에서 확인한 현재 VERIFIED 대표 연락처 하나로 고정한다.
2026-11-03 승인으로 선택적 `identityId` 입력을 제거했다. 인증·CSRF 검사를 통과한 발급·검증 JSON에 해당 필드가 있으면 값이 null이어도 400 `INVALID_REQUEST`이며 발송·OTP 소비를 수행하지 않는다. 알 수 없는 필드는 기존 요청별 JSON 검증으로 거절한다.
대표 이메일이 없으면 409 `REAUTH_EMAIL_REQUIRED`다. 최초 등록/조회/취소 API는 공통 계약 문서를 따른다.

모든 경로는 인증된 세션이 필요하다(익명 401). POST에는 같은 쿠키 세션으로
GET `/api/v1/auth/csrf`에서 받은 `X-CSRF-TOKEN`이 필요하다(인증 후 누락/오류 403).
성공 후 예전 세션 쿠키·CSRF는 폐기하고 새 쿠키로 CSRF를 다시 조회한다.
기존 `/api/v1/auth/session`의 `{userId}`, logout, 익명 LOGIN OTP 계약은 그대로다.

오류는 기존 `{code}`를 사용한다: 형식 400 `INVALID_REQUEST`, 증명 실패 401 `OTP_INVALID`,
세션 변경/만료 401 `AUTHENTICATION_REQUIRED`, 제한 429 `OTP_RATE_LIMITED`와 `Retry-After`,
설정/발송/DB 실패 503 `OTP_UNAVAILABLE`. 응답 커밋 직전 실패는 401/503 및 쿠키 제거로 처리하며 본문이 없을 수 있다.
202는 provider 수락과 DB 활성화 확인이며 최종 수신 성공은 아니다. 자동 재발송하지 않는다.

## 목적·제한·스키마

V1~V4는 수정하지 않는다. V5는 기존 OTP 세 테이블의 purpose CHECK에 `REAUTH`를 추가하고
challenge에 `reauth_user_id`, `reauth_identity_id`를 추가한다. LOGIN은 두 값이 NULL,
V5 REAUTH의 두 값은 당시 필수였으며 `(user_id,id)` FK로 동일 소유자에 묶었다.
후속 V6는 대표 이메일/후보 테이블과 `reauth_contact_id`를 추가한다. 신규 공통 REAUTH는 사용자+연락처 FK,
CONTACT_ENROLL은 사용자+기존 Google identity FK를 사용한다. 과거 V5 행은 보존하되 이전 REAUTH 바인딩은
새 API에서 수용하지 않는다. LOGIN 상태 및 기존 REAUTH 예산을 보존하는 전환은 공통 계약 문서를 따른다.

공유 `OtpChallenges`는 목적별 HMAC·발급 상태·제한/락·소비만 담당한다. 프로비저닝은 LOGIN 호출자에만 남는다.
LOGIN HMAC의 기존 길이 접두어 5개 필드 바이트 형식은 그대로 유지하므로 V4에서 발급한 LOGIN OTP가 깨지지 않는다.
REAUTH는 목적과 이메일·발급 UUID·코드뿐 아니라 해시된 세션 바인딩도 HMAC에 포함한다.
바인딩은 사용자 UUID, 대표 연락처 UUID(등록은 후보 UUID), 현재 세션 ID, 원래 세션 metadata 전체를 포함한다.
원문 세션 ID는 OTP 테이블에 저장하지 않는다. 상수시간 비교와 소비 후 verifier 영점 덮어쓰기를 유지한다.

6자리 SecureRandom, 유효 5분, 재발송 최소 60초, 개별 발급 및 이메일 누적 실패 5회/30분,
이메일 요청 5회/시간·검증 20회/15분, IP 요청 20회/시간·검증 100회/15분은 기존 값이다.
모든 제한과 활성 challenge 포인터는 `(식별자,purpose)`별 PostgreSQL 상태다.
공통 재인증/등록의 수신자 식별자는 userId+주소이며, 동일 주소의 다른 계정은 분리하고 IP 제한은 유지한다.
재전송·앱 재시작으로 실패 예산이 초기화되지 않는다. X-Forwarded-For 대신 기존 socket peer를 사용한다.
`email_otp_login_claims`는 LOGIN 전용이며 REAUTH는 쓰지 않는다.
메일은 REAUTH 제목/본문과 `email-otp/reauth/<issuanceId>` 멱등 키를 사용한다. LOGIN 메시지·키 형식은 유지한다.

## 트랜잭션·경합·실패 복구

1. 요청의 인증 principal·유효 metadata를 캡처한다. 기존 이메일/IP 제한 → 이메일 guard → 사용자 행 → 필요한 identity 공유 락 → 세션 행 락 순서다.
2. 발급은 짧은 예약 트랜잭션을 커밋한 뒤 네트워크 발송한다. 열린 쓰기 트랜잭션에서 메일을 보내지 않는다.
   활성화 때도 VERIFIED 대표 연락처/소유 신원과 현재 저장된 세션을 검사한다. 발송 실패는 FAILED, 중단/세션 변경으로 남은 PENDING은 검증 불가다.
3. 검증은 REAUTH 목적·활성 포인터·만료·바인딩·예산·HMAC을 확인한 같은 트랜잭션 안에서 OTP를 소비한다.
   Spring Session 원본 행을 `FOR UPDATE`로 잠그고 저장된 UUID principal·metadata·DB TTL·원래 절대 만료를 다시 확인한다.
4. 새 **primary key와 session ID**에 기존 자체 SecurityContext, 최초 생성 시각을 복사한다.
   metadata의 userId/loginStartedAt/absoluteExpiresAt는 유지하고 reauthenticatedAt만 현재 시각으로 바꾼다.
   기존 CSRF·임시 속성을 복사하지 않으며 원본 세션은 삭제한다. OTP 소비와 이 교체는 **같은 DB 트랜잭션**이다.
5. 쓰기 전·후 및 커밋 직전에 저장된 principal·metadata·절대 만료를 검사한다.
   커밋 후에는 공식 `SessionRepository.findById`로 재조회하고 실제 저장된 전체 metadata와 자체 principal을 비교한다.
   쿠키 작성 및 HTTP 응답 커밋 직전에도 재검사하여 204 성공 오판을 막는다.

Session JDBC의 기본 저장은 REQUIRES_NEW이므로 OTP 트랜잭션에서 공식 저장소를 중첩 호출해 원자성을 주장하지 않는다.
잠금/교체는 기존 V3 공식 DDL·Java 직렬화 형식으로 같은 JDBC 연결을 사용한다.
공식 저장소 재조회는 커밋 후 외부 트랜잭션 없이 수행한다.
새 primary key는 늦게 도착한 원본 요청의 save가 인증 세션을 복구하거나 새 metadata를 덮어쓰지 못하게 한다.
쿠키 serializer의 요청 한정 marker는 외부 필터가 이전 ID의 쿠키를 다시 쓸 때에도 검증된 새 ID만 사용하게 한다.
응답 필터는 재인증 응답에만 최종 저장소 확인을 적용한다.

로그아웃/회전이 원본 행 락을 먼저 확정하면 재인증은 거부된다. 재인증 교체가 먼저 커밋되면 이후의 예전 ID 요청은
새 primary key에 접근할 수 없다. 이미 완료한 트랜잭션을 나중에 도착한 예전 쿠키로 소급 취소한다고 주장하지 않는다.
서로 다른 기기·사용자의 증명 혼용, 중복 verify, 재발급/verify 경쟁은 성공 한 건만 허용한다.

- 커밋 전 SQL/속성 쓰기/시각 검증 실패: OTP 소비와 세션 교체 모두 롤백. 원래 세션이 유효하고 코드가 만료되지 않았다면 재시도 가능하다.
- 커밋 후 재조회·쿠키·최종 응답 검증 실패: 소비된 OTP는 되살리지 않는다. 새 인증 세션을 폐기하고 실패로 응답한다.
  이전 ID도 이미 폐기됐으므로 새 CSRF와 **일반 LOGIN OTP**로 로그인해야 한다. DB 장애로 정리를 못 해도 성공 응답을 보내지 않는다.
- 응답 유실/불확실: 같은 코드로 성공을 추측하지 않는다. 새 쿠키의 `/api/v1/auth/session`을 확인하고 불확실하면 명시 로그아웃·일반 로그인으로 복구한다.

`SessionPolicy`의 최초 로그인 +90일 절대 만료와 최근 10분(정확 경계 포함) 정책은 변경하지 않는다.
테스트용 최근 인증 probe·장애 injector는 src/test 명시적 import에만 존재한다. 민감한 업무 쓰기 API를 추가하지 않았다.

## 검증·실사용 구분

`.env`, HMAC/Resend/Google 키와 기존 Docker 개발 DB는 변경하지 않는다. 테스트에서는 fake sender와 루프백 OIDC만 사용한다.
실제 메일/Google 로그인 재검증은 하지 않고 격리 PostgreSQL 18.6 Testcontainers + HTTP + fake sender로 검사한다.
전체 명령은 `.\gradlew.bat --no-daemon test integrationTest build`다. 실제 개수·실패 수정·보존 증거는
[3B-1 당시 보고서](../tasks/task-007-1c-3b-1-validation-report.md)와
[공통 이메일 보완 보고서](../tasks/task-007-1c-3b-common-email-validation-report.md)를 구분한다.

채택 버전 동작 근거: [Spring Session 4.1.1 JDBC](https://github.com/spring-projects/spring-session/blob/4.1.1/spring-session-jdbc/src/main/java/org/springframework/session/jdbc/JdbcIndexedSessionRepository.java),
[SessionRepositoryFilter](https://github.com/spring-projects/spring-session/blob/4.1.1/spring-session-core/src/main/java/org/springframework/session/web/http/SessionRepositoryFilter.java).
