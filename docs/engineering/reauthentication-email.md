# 공통 대표 재인증 이메일 — Task 007-1C-3B 보완

2026-10-28 사용자 확정 정책을 반영한다. Google SSO LOGIN은 유지하고, 로그인 제공자와 별개인
대표 이메일 하나로 OTP 재인증한다. Task 006 원문과 기존 V1~V5는 수정하지 않는다.
이 문서는 기존 3B-1/3B-2 보고서의 당시 결과를 덮어쓰지 않는 현재 구현 계약이다.

## 계정과 연락처의 구분

`user_reauthentication_emails`는 `user_id` PK/FK로 사용자당 최대 한 행만 가진다.
`id` UUID, 정규화된 `email`, VERIFIED 전용 `status`, `verified_at`, `verification_source`를 저장한다.
주소에는 계정 간 UNIQUE 제약이 없다. 동일 주소라도 UUID별 연락처·OTP·세션은 분리된다.
대표 이메일과 실제 OTP를 검증한 세션에서는 별도 [EMAIL 로그인 수단 연결·해제](email-identities.md)를
명시 요청할 수 있다. 연락처 등록 자체는 로그인 identity를 생성하지 않는다.
반면 로그인 identity의 `(provider,issuer,subject)` 소유권 UNIQUE/FK 계약은 그대로다.
연락처 등록은 EMAIL identity 생성·계정 연결·병합을 하지 않는다.

등록 API는 최초 등록만 허용한다. 기존 VERIFIED 연락처는 이후 EMAIL 로그인 또는 Google 이메일 변경으로
덮어쓰지 않는다. 주소 변경/분실 복구는 미구현이며, 인증 수단 연결·해제는 현재 [EMAIL](email-identities.md)·[Google](google-identities.md)의 별도 명시 API를 사용한다. 주소 변경은 후속 단계에서
기존 수단의 최근 재인증과 새 주소의 OTP 증명을 모두 요구하는 별도 계약이 필요하다.

미검증 주소는 `reauth_email_candidates`에만 저장한다. 후보 UUID·사용자·기존 Google identity·검증된 sub·주소·
원본 세션 snapshot 해시·만료·소비 시각을 저장하며 VERIFIED 연락처 조회/재인증에는 사용할 수 없다.
후보 FK는 `(user_id,google_identity_id)`로 같은 소유자를 강제한다. 응답은 주소 첫 글자와 `***@***`만 노출한다.
토큰, 쿠키, 원문 issuer/sub 또는 원문 이메일을 연락처 조회 응답에 싣지 않는다.

## EMAIL 가입자와 과거 데이터

실제 LOGIN OTP를 원자 소비한 결과 시각과 프로비저닝된 기존 EMAIL identity 소유권을 재확인하여
처음 한 번 연락처를 `EMAIL_LOGIN_OTP`로 등록한다. 추가 메일을 발송하지 않는다.
범용 `VerifiedIdentity` 생성이나 계정 프로비저닝 호출 자체는 이메일 검증 증거로 사용하지 않는다.
기존 연락처가 다르면 그대로 유지하며 로그인 identity를 통한 재인증 우회도 허용하지 않는다.

V6는 기존 EMAIL identity와 주소가 같고 `accepted_at`이 있으며 CONSUMED인 LOGIN 또는 해당 사용자/identity의
REAUTH OTP가 보존된 경우만 `HISTORICAL_OTP`로 보충한다. 이는 기존 LOGIN의 전역 신원 유일성과
신원 이전·병합 기능이 없다는 계약에 근거한다. bare identity, 미검증 발급, Google 토큰 이메일은 근거가 아니다.
사용자에게 입증된 EMAIL 주소가 여러 개이면 자동 선택하지 않는다. 증거가 없거나 모호한 계정은 정상 EMAIL
OTP 로그인으로 소유권을 다시 입증해 최초 등록한다. 기존 주소/사용자/운동/설정/세션은 삭제·갱신하지 않는다.

로그인 OTP 소비·프로비저닝의 기존 경계는 유지한다. 연락처 저장 실패 시 성공 로그인을 반환하지 않으며,
이미 소비한 OTP를 복구하지 않는다. 기존 계정이 생성된 경우에도 새 LOGIN OTP로 다시 입증하여 복구한다.

## Google-only 최초 등록의 증명

1. 인증된 자체 `SessionPrincipal.userId`와 원본 JDBC 세션/metadata를 캡처한다.
2. CSRF 보호 POST에서 선택한 identity가 현재 사용자 소유의 기존 Google issuer/sub인지 검사한다.
3. 전용 등록 ID `google-contact`와 전용 pending 속성으로 새 공식 OIDC code/state/nonce/PKCE 확인을 시작한다.
   pending은 세션에 바인딩하고 수명은 5분 미만이다. 콜백의 state는 DB 락과 원자 소비 후 토큰 교환한다.
4. 공식 OIDC 검증기의 서명/JWKS, issuer, audience/azp, 만료 및 nonce 검증을 유지한다.
   ID 토큰의 issuer/sub가 선택한 기존 identity와 정확히 같아야 한다. 원시 `email` 문자열과 Boolean
   `email_verified=true`가 필수다. 누락·false·문자열 true·다른 Google 계정은 거절한다.
5. 검증된 이메일을 세션 바인딩된 후보로만 저장한다. 이 단계는 로그인/재인증 시각을 갱신하거나 계정을 생성하지 않는다.
6. 사용자가 후보 UUID로 명시적으로 OTP 발급을 요청한다. 서버에 저장된 후보 주소 한 곳에만 발송한다.
7. 실제 CONTACT_ENROLL OTP 검증과 후보 소비·VERIFIED 등록·JDBC 세션 회전을 한 트랜잭션으로 처리한다.
   이 이메일 소유권 증명은 최초 등록과 최근 이메일 재인증을 함께 충족하므로 즉시 두 번째 메일을 요구하지 않는다.

과거 Google 로그인에 이메일을 보존하지 않았어도 이 새로운 확인 흐름으로 진입한다.
활성 세션만으로 임의 주소를 입력하는 API는 없으며, 오래된 세션에 저장된 이메일 클레임도 신뢰하지 않는다.
Google 연락처 OIDC는 Google 계정 비밀번호 재인증 강제를 의미하지 않는다. 실제 최근 증거는 별도 이메일 OTP다.
`auth_time` 기반 실험 기능과 목적·등록 ID·콜백·pending namespace·검증기를 구분한다.
OIDC 취소/실패/다른 신원은 연락처를 등록하지 않으며 OTP 취소/만료/다른 세션도 등록할 수 없다.

일반 Google LOGIN의 기존 openid/email scope와 성공 계약은 유지한다. 별도 승인 LIVE 전에 Google Console에
다음 URI의 등록이 필요하다. 이번에는 Console이나 실제 `.env`를 수정하지 않았다.

```text
http://localhost:8080/login/oauth2/code/google-contact
```

## API

모든 경로는 인증된 자체 세션을 사용한다. POST는 같은 세션의 CSRF가 필수이며 주소·userId를 받지 않는다.
JSON의 알 수 없는 필드와 발급/검증 URL의 주소 입력 파라미터는 400이다. 응답은 no-store다.

| 요청 | 입력 | 결과 |
|---|---|---|
| GET /api/v1/auth/identities | 없음 | 기존 `{identities:[{id,provider}]}` |
| GET /api/v1/auth/reauth/email/contact | 없음 | `{status:"VERIFIED",maskedEmail}` 또는 `{status:"REQUIRED",candidate?:{id,maskedEmail}}` |
| POST /oauth2/contact/google | form `identityId`, CSRF | 공식 Google authorization으로 302 |
| GET /login/oauth2/code/google-contact | 표준 code/state | 성공 시 고정 contact 조회 경로로 302, 이메일 발송 없음 |
| GET /auth/google/contact/failure | 없음 | 401 `GOOGLE_CONTACT_FAILED` |
| POST /api/v1/auth/reauth/email/registration/request | `{candidateId}` | 202 `{status:"ACCEPTED",challengeId}` |
| POST /api/v1/auth/reauth/email/registration/verify | `{candidateId,challengeId,code}` | 204 + 회전된 세션 쿠키 |
| POST /api/v1/auth/reauth/email/registration/cancel | 없음 | 204, 미소비 후보 만료 처리, VERIFIED 연락처 유지 |
| POST /api/v1/auth/reauth/email/request | `{}` | 202 `{status:"ACCEPTED",challengeId}` |
| POST /api/v1/auth/reauth/email/verify | `{challengeId,code}` | 204 + 회전된 세션 쿠키 |

2026-11-03 승인으로 이메일 재인증 JSON의 선택적 `identityId` 입력 지원을 제거했다.
발급은 `{}`, 검증은 `{challengeId,code}`만 사용한다. 인증·CSRF 검사 이후 `identityId`는 null을 포함한 어떤 값이라도 400 `INVALID_REQUEST`이며 발송·OTP 소비를 수행하지 않는다. 대상은 서버의 현재 VERIFIED 대표 연락처로 결정하고, 진행 중 연락처와 원본 세션을 다시 검증한다. Google CONTACT form의 identityId 계약은 그대로다.
대표 이메일 없으면 409 `REAUTH_EMAIL_REQUIRED`, 이미 등록된 최초 등록 시도는 409
`REAUTH_EMAIL_ALREADY_VERIFIED`다. 형식 400, 잘못된/만료/다른 세션 증명 401 `OTP_INVALID`, 세션 만료/교체
401 `AUTHENTICATION_REQUIRED`, 제한 429 `OTP_RATE_LIMITED`+Retry-After, 발송/DB 설정 장애 503 `OTP_UNAVAILABLE`을 사용한다.
OIDC 실패는 세부 provider 정보를 반사하지 않는 고정 failure 경로를 사용한다. 익명은 401, CSRF 오류는 403이다.

## OTP·중복 발송·동시성

LOGIN/REAUTH/CONTACT_ENROLL을 목적별 HMAC·행·상태·제한으로 분리한다. LOGIN의 기존 HMAC 바이트는 불변이다.
나머지는 사용자·연락처 또는 후보 UUID·원본 session ID·전체 원본 metadata의 해시도 HMAC에 포함한다.
다른 사용자·세션·목적·후보의 코드는 혼용할 수 없다. 일반 REAUTH는 VERIFIED 대표 주소만 사용한다.

6자리 SecureRandom, 유효 5분, 재발송 최소 60초, 개별/누적 오류 5회, 누적 실패 창 30분을 유지한다.
수신자 요청 5회/시간·검증 20회/15분, IP 요청 20회/시간·검증 100회/15분도 유지한다.
REAUTH/CONTACT_ENROLL의 수신자 키는 `(purpose,userId,email)`이다. 세션 또는 후보를 바꿔도 동일 사용자/주소의
제한은 초기화되지 않는다. 같은 주소의 다른 계정은 독립 예산을 가지며 IP 제한은 여전히 공유된다.

PostgreSQL rate/guard 행 락으로 예약 한 건만 커밋한 후 트랜잭션 밖에서 발송한다. 동시/중복 요청은 429이며
두 신원이 있어도 대표 이메일 하나로만 전송한다. Google 후보를 다시 얻어도 60초/실패 예산은 유지한다.
후보는 생성 후 5분 동안 발급 가능하며, 실제 예약 시 발급+5분까지 연장해 늦게 요청한 OTP도 온전한 5분을 갖는다.
요청 실패/429는 후보 만료를 연장하지 않는다. 새 후보는 이전 미소비 후보를 무효화한다.
등록용 Resend 멱등 키는 `email-otp/contact-enroll/<issuanceId>`다. 기존 LOGIN/REAUTH 키는 유지한다.
재시도 없는 기존 sender를 사용하며 202는 provider 수락·DB 활성화이지 수신 확인이 아니다.

V6는 V5의 주소별 REAUTH 실패 횟수·발급 시각·실패 창과 request/verify-email rate 창·hits를
해당 EMAIL identity 소유자 범위의 새 키로 복사한다. 기존 행·LOGIN·IP 예산은 그대로 둔다.
V5의 미완료 REAUTH 코드는 새 연락처 바인딩과 호환되지 않는다. 기존 행은 보존하되 새 API에서 사용하지 않으며,
전환 후 남은 제한 시간을 지키고 다시 요청한다. 제한을 초기화하거나 과거 코드를 무조건 수용하지 않는다.

## 원자성·세션과 실패 복구

OTP 제한/guard → 사용자 행 → 필요한 identity 공유 락 → 원본 세션 행 순으로 검사한다.
Google pending 저장/소비는 기존 identity → session 락이며, 네트워크 교환 중 쓰기 트랜잭션은 없다.
최초 등록의 연락처 쓰기·후보 소비·OTP 소비·새 세션 primary key/ID 생성·옛 세션 삭제는 같은 JDBC 트랜잭션이다.
일반 재인증도 같은 SessionReauthentication 경계를 재사용한다. userId/loginStartedAt/absoluteExpiresAt는 보존하고
reauthenticatedAt만 실제 OTP 소비 시각으로 갱신한다. 최초 로그인+90일 및 최근 10분 정책은 그대로다.
기존 CSRF/임시 OAuth 속성은 복사하지 않으며 성공 후 새 쿠키로 GET `/api/v1/auth/csrf`를 호출한다.

커밋 전 SQL/90일 만료 실패는 연락처·후보·OTP·회전을 롤백한다. 원본 세션과 미만료 코드는 재시도 가능하다.
커밋 후 재조회/쿠키/응답 실패는 새 세션을 폐기하고 401/503이며 성공으로 표시하지 않는다.
이미 확정된 VERIFIED 연락처와 CONSUMED OTP는 보존한다. 정상 LOGIN으로 복구하며 연락처를 자동 덮어쓰지 않는다.
연락처 변경·분실 복구가 없으므로 mailbox 접근 상실은 후속 제품 정책 과제로 남는다.

## 설정·검증 범위

`GOOGLE_OIDC_ENABLED=true`, `GOOGLE_REAUTH_ENABLED=false` 조합에서 Google LOGIN과 연락처 확인은 가능하고
Google auth_time 재인증 시작/콜백은 503이다. `GOOGLE_REAUTH_ENABLED` 생략도 비활성이다.
실험 재인증은 두 플래그가 모두 true일 때만 등록하며 엄격한 auth_time 검사를 보존한다.
[Google 공식 문서](https://developers.google.com/identity/siwg/security-bundle)는 강제 Google 재인증 요청을 지원하지
않는다고 설명한다. 공통 이메일 흐름은 그 기능이나 Security Bundle 추가 클레임에 의존하지 않는다.

자동화는 격리 PostgreSQL Testcontainers·루프백 서명 OIDC 서버·fake sender를 사용한다.
이번 V6는 개발 DB에 적용하지 않았다. 실제 callback 등록/Google 동의·클레임/메일 전달은 후속 승인 LIVE가 필요하다.
구체적 결과는 [공통 이메일 검증 보고서](../tasks/task-007-1c-3b-common-email-validation-report.md)를 따른다.

Task 007-1D-2A의 React `/account`에서 이 조회·등록·재인증 계약을 사용한다.
CONTACT만 선택적 `AUTH_CONTACT_FRONTEND_RETURN_URI`로 고정 React 복귀를 지원한다.
빈 기본값·POST/CSRF·신원/후보/세션·실제 OTP 소유 증명과 90일 만료 계약은 유지한다.
세부 설정은 [Google OIDC 문서](google-oidc.md), UI·테스트 범위는
[프론트 인증 문서](../../../overload-pwa/docs/engineering/authentication.md)를 따른다.
