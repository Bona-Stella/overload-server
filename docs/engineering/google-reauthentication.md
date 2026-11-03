# Google OIDC 최근 재인증 — Task 007-1C-3B-2

현재 인증된 Overload 사용자에 이미 연결된 Google identity만 재증명한다. 일반 Google LOGIN의
신규 가입/프로비저닝과 별도 필터·등록 ID·state 속성·성공/실패 핸들러를 사용한다.
신원 생성·연결·해제·이전·자동 병합이나 프론트 변경은 없다. 이메일 일치는 인증 근거가 아니다.

## HTTP 및 활성화 계약

`GOOGLE_REAUTH_ENABLED=false`가 독립 기본값이다. `GOOGLE_OIDC_ENABLED`와 `GOOGLE_REAUTH_ENABLED`가
모두 true여야 이 실험 기능을 등록한다. 일반 Google LOGIN과 [대표 이메일 등록 확인](reauthentication-email.md)은
Google LOGIN 플래그만 따르며 이 실험 플래그가 false여도 동작한다. 실제 `.env`는 변경하지 않는다.
동일한 검증된 Google 웹 클라이언트를 사용하되 등록 ID `google-reauth`는 서버 내부 구분이다.

Google [공식 Security Bundle 문서](https://developers.google.com/identity/siwg/security-bundle)는 Google 계정의
강제 재인증 요청을 지원하지 않는다고 설명한다. `max_age=0`/essential 요청으로 새 Google 인증을 보장하지 못한다.
추가 auth_time 클레임은 게시·검증된 앱 및 관련 설정이 필요하며 개발 Testing 클라이언트 지원은 미확인이다.
따라서 이 기능은 일반 사용자의 최근 재인증 수단으로 의존하지 않는다. 사용자 확정 정책에 따라 모든 계정의
최근 증명은 대표 이메일 OTP를 사용한다. 누락·오래된 auth_time 또는 iat/계정 선택을 성공으로 대체하지 않는다.

| 요청 | 계약 |
|---|---|
| POST /oauth2/reauth/google | 인증+CSRF 필수. form-urlencoded `identityId` UUID. Google authorization으로 302 |
| GET /login/oauth2/code/google-reauth | 동일 원본 인증 세션 필수. 표준 code/state 처리, issuer/sub/auth_time 검증 |
| 성공 | 새 SESSION 쿠키와 고정 `/api/v1/auth/session`으로 302, 다음 GET 200 `{userId}` |
| 일반 프로토콜/취소/증명 실패 | 고정 `/auth/google/reauth/failure`로 302. 정상 원본 인증 세션 보존 |
| GET /auth/google/reauth/failure | 401 `{code:"GOOGLE_REAUTH_FAILED"}` |
| 익명 시작/콜백 | 401 AUTHENTICATION_REQUIRED |
| 인증된 시작의 CSRF 누락/오류 | 기존 403 CSRF_INVALID |
| 없는/타인/EMAIL identity | 일반화된 404 IDENTITY_NOT_FOUND |
| 인증된 사용자의 비활성 시작/콜백 | 503 GOOGLE_REAUTH_UNAVAILABLE |
| 인증된 GET 시작 | 405; 실제 시작은 POST만 지원 |

콜백 성공 이후 CSRF는 다시 GET `/api/v1/auth/csrf`로 얻는다. 예전 CSRF·세션 ID는 재사용하지 않는다.
시작은 GET 대신 CSRF 보호 POST를 채택했다. 같은 origin에서 CSRF를 얻고 폼의 `_csrf` 또는
X-CSRF-TOKEN 헤더로 보낸다. 브라우저 최상위 폼 제출 → 302 Google → 최상위 GET 콜백으로 호환된다.
fetch는 Google 브라우저 인증 화면의 대체가 아니다. 프론트 연동은 이번에 구현하지 않았다.

자체 OAuth 응답/오류는 no-store/no-referrer다. next/returnUrl 및 provider 오류 설명을 응답으로 반영하지 않는다.
응답 커밋 직전 세션/증명 만료·저장소 장애는 302 성공을 취소하고 401/503, 새 세션 폐기·쿠키 제거로 처리한다.
이 최종 실패는 본문이 없을 수 있으며 성공 Location을 제거한다. HTTP 상태와 다음 session 응답으로 확인해야 한다.
일반 LOGIN 시작/콜백은 인증된 세션에서 계속 409 LOGOUT_REQUIRED이며 재인증으로 자동 전환되지 않는다.

## 콜백 등록 — 실제 클라이언트 미확인

추가로 Google Console에 등록할 정확한 PC 개발 URI는 다음이다:

```text
http://localhost:8080/login/oauth2/code/google-reauth
```

기존 `http://localhost:8080/login/oauth2/code/google`은 유지한다. 재인증 URI는 검증된 일반 등록 URI에
`-reauth`를 붙여 도출하며 운영에서는 기존 명시적 HTTPS URI의 같은 호스트를 유지한다.
사용자 승인 없이 Console 또는 `.env`를 수정하지 않는다. 콜백 추가 등록 여부도 LIVE 전에 확인해야 한다.

## auth_time의 의미와 엄격한 판정

Google 공식 문서는 auth_time을 Google 사용자 인증 시각, iat를 ID 토큰 발급 시각으로 구분한다.
2026-06-16 발표는 새 메타데이터를 verified apps 대상으로 설명한다. **현재 Overload 개발/Testing
클라이언트가 auth_time을 반환하는지는 미확인**이다. 자동화 성공은 Google의 실제 지원 증거가 아니다.

재인증 요청에 `claims={"id_token":{"auth_time":{"essential":true}}}`와 표준 OIDC 의도인 `max_age=0`을 보낸다.
Google이 max_age를 지원/강제한다고 가정하지 않으며 prompt=login을 추가하지 않는다.
계정 선택/동의/새 code/새 토큰/최근 iat/email_verified만으로 재인증하지 않는다.
Google이 요청을 무시하거나 오래된/누락 auth_time을 반환하면 거부한다. 비밀번호 재입력이나 2FA를 강제했다는 뜻이 아니다.

검증은 공식 OidcIdTokenDecoderFactory의 RS256/JWKS·issuer/aud/azp·exp/iat와 표준 OIDC nonce/PKCE/state를 유지한다.
REAUTH 전용 claim converter에서 **형 변환 전** auth_time이 비음수 정수형 JSON 숫자인지 검사한다.
문자열·소수·누락을 허용하거나 JWT를 수동 디코딩해 검증을 대체하지 않는다. LOGIN decoder 정책은 유지한다.

- 요청 시작은 서버 Clock의 Instant를 임시 JDBC 상태에 기록한다. 요청 수명은 **5분 미만**이다.
- auth_time은 초 단위이므로 시작을 초 단위로 내림한 시각 이상이어야 한다. 이 정밀도 차이(1초 미만) 외에
  과거 60초 허용은 없다. 시작보다 1초 전 인증은 최근 인증이었더라도 거부한다.
- 미래 시계 오차는 현재 서버 시각보다 최대 **60초**까지 허용한다. 60초 초과·서버 시각 역행·5분 정확 경계는 거부한다.
- 세션 reauthenticatedAt는 `max(기존 reauthenticatedAt, min(auth_time, 서버 현재 시각))`이다.
  콜백 도착 시각으로 새 10분을 발급하지 않으며 미래 시각을 세션 metadata에 저장하지 않는다.
- 쓰기 전/후·커밋 직전·저장소 재조회·쿠키·응답 커밋 직전까지 시간 증거를 검사한다.
  90일 세션 자체가 유효하더라도 Google 요청/증명이 만료됐으면 성공할 수 없다.

## 상태·트랜잭션과 세션 보존

1. 서버 SessionPrincipal UUID와 원본 SessionMetadata/session ID를 캡처한다. identity UUID는 선택값일 뿐이며,
   DB에서 현재 사용자의 `(google, https://accounts.google.com, sub)` 신원을 조회한다.
2. 인증된 원본 Spring Session에 전용 Pending 속성을 저장한다. purpose, 사용자/identity/session,
   원본 metadata, 시작 시각, 공식 authorization request(state/nonce/PKCE)를 직렬화한다.
   사용자 UUID·identity·원본 metadata는 Google 요청 파라미터에 추가하지 않는다. 표준 state/nonce는 OAuth 프로토콜에만 사용한다.
3. 콜백은 identity 공유 락 → 원본 session FOR UPDATE 순서로 최신 DB 상태를 검사한다.
   동일 원본 snapshot·동일 state만 **임시 속성 DELETE**로 원자 소비·커밋한다. 원본 session 행은 삭제하지 않는다.
   병렬 콜백 중 한 건만 토큰 교환에 도달하며, 새 탭 시작은 같은 세션의 이전 미완료 state를 대체한다.
4. 트랜잭션을 닫은 뒤 표준 OIDC provider가 code/token/서명/클레임/nonce를 검증한다. 네트워크 요청 중 DB 락을 잡지 않는다.
   취소/잘못된 토큰/오래된 인증은 이 단계에서 끝나며 원본 UUID principal·metadata·기존 CSRF 바이트를 보존한다.
5. 검증된 issuer/sub가 **선택한 기존 identity와 정확히 일치**하는지 DB 공유 락으로 다시 검사한다.
   다른 Google 계정을 선택해도 사용자 교체·가입·병합은 하지 않는다.
6. 새 트랜잭션에서 3B-1의 공용 SessionReauthentication/ReauthenticationSessionRepository를 사용한다.
   원본 세션과 metadata가 여전히 같을 때만 새 primary key/session ID로 교체하고 이전 행/CSRF를 폐기한다.
   userId/loginStartedAt/absoluteExpiresAt 및 계정/운동/휴식/세대는 보존한다.
7. 커밋 후 공식 SessionRepository 실제 재조회 및 공용 cookie/응답 검증을 통과해야 성공한다.
   공용 경로에 검증된 인증 시각과 최종 증명 검사용 콜백을 추가했으며 이메일 REAUTH의 기존 기본 동작은 유지한다.

공식 OAuth2LoginAuthenticationFilter의 임시 Context 저장은 Null repository, 자동 세션 전략은 no-op,
authorized client 저장은 폐기형이다. success/failure 핸들러가 원래 자체 Context를 복원한다.
OidcUser/ID·access·refresh token/authorized client는 장기 세션에 쓰지 않는다. 기존 일반 LOGIN의
익명 세션 삭제 저장소나 계정 생성 성공 핸들러를 재사용하지 않는다.

Pending 저장·소비는 같은 JDBC 연결/기존 V3 직렬화 형식을 사용한다. REQUIRES_NEW인 공식 SessionRepository를
행 락 안에서 중첩 호출하지 않는다. 검증은 풀 크기 1, 재시작 및 멀티탭에서도 수행한다.
공통 GoogleBoundRequests.Pending은 Java Serializable 객체이며 Spring Session JDBC 속성으로 저장·복원한다. JSON Mapper 변환 경로가 아니다. 과거5필드 Java 직렬화 호환성 전용 테스트 제거는 현재 Pending 저장·소비·신원/세션 바인딩 보호를 제거한 것이 아니다.
Google auth_time 경로 자체는 별도 테이블/새 의존성을 요구하지 않는다. 후속 공통 대표 이메일에는 추가형 V6를
사용하며 V1~V5와 Task 006은 불변이다. 공통 bound OIDC 경계는 추출했지만 auth_time 판정은 그대로 보존했다.

## 실패 복구·경합 경계

- 잘못된 state/타 브라우저는 원래 브라우저의 Pending을 소비하지 못한다. 일치한 취소/만료/실패 state는 되살리지 않는다.
- 신원 불일치/프로토콜 오류/커밋 전 SQL 실패는 가능한 한 원본 인증 세션을 보존한다. 새 시작 POST+CSRF로 재시도한다.
- 최종 세션 교체 전 SQL 실패는 교체 전체를 롤백한다. 다만 앞서 커밋된 state/code 소비는 복구하지 않는다.
- 교체 커밋 후 재조회/쿠키/응답 직전 실패는 새 세션을 폐기한다. 원래 ID도 폐기됐으므로 정상 LOGIN으로 복구한다.
- 로그아웃/이메일 REAUTH/다른 Google 재인증이 원본 행을 먼저 교체하면 이 흐름은 거절한다.
  Google 교체가 먼저 커밋되면 예전 ID의 요청은 새 primary key를 수정할 수 없다. 완료된 작업을 과거 쿠키로 소급 취소하지 않는다.
- 응답이 불확실하면 인증 성공으로 표시하지 않고 현재 session API를 확인한다. 소비한 code/state를 재전송하지 않는다.

## 검증과 후속 LIVE

테스트는 실제 PostgreSQL 18.6 Testcontainers 및 루프백 OIDC/JWKS/RSA 서버를 사용한다.
실메일·Google OAuth/JWKS 실서버·개발 Compose DB에 접속하지 않는다. `.env`·키·활성값과 기존 사용자 데이터를 유지한다.
자세한 테스트 개수/실패 수정/변경 목록은 [3B-2 검증 보고서](../tasks/task-007-1c-3b-2-validation-report.md)를 따른다.

실험 기능의 후속 별도 승인 LIVE에서는 사용자가 callback을 Console에 추가 등록하고 검증 앱/Testing 제한을 확인한 뒤
직접 Google 인증을 수행한다. 실제 검증된 auth_time이 요청보다 새롭고 최근인지, 없거나 오래되면 거부되는지 확인해야 한다.
현재는 이 준비/클레임 제공/실제 재인증 성공을 완료로 주장하지 않는다. 시간 검사를 완화하는 대체 경로는 없다.

공식 근거:

- [Google OIDC claim reference](https://developers.google.com/identity/openid-connect/reference)
- [Google의 새 세션 메타데이터 발표](https://developers.googleblog.com/enhance-security-and-trust-new-session-metadata-in-sign-in-with-google/)
- [Spring Security 7.1.2 OIDC decoder](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/oidc/authentication/OidcIdTokenDecoderFactory.java)
- [공식 OIDC code provider](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/oidc/authentication/OidcAuthorizationCodeAuthenticationProvider.java)
