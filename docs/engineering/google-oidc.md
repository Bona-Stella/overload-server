# Google OIDC 로그인 (Task 007-1C-3A)

서버 측 Authorization Code + OIDC 로그인이다. 공식 Spring Security OAuth2 Client 7.1.2/Jose와
Boot 4.1.2 BOM을 사용하며, JWT를 앱 인증 수단으로 발급하지 않는다. 기존 이메일 OTP·JDBC 세션을 유지한다.
실제 Google 계정 브라우저 E2E는 별도 [3A-LIVE 보고서](../tasks/task-007-1c-3a-live-validation-report.md)에서 검증했다.
이번 재인증 작업의 자동화는 루프백 OIDC 서버의 서명된 토큰과 실제 PostgreSQL을 사용하며 Google 실로그인을 반복하지 않는다.
일반 SSO가 초기화한 reauthenticatedAt는 [EMAIL 수단 변경의 소유 증명](email-identities.md)이 아니다.
EMAIL/Google identity 연결·해제에는 최근 대표 이메일 OTP 증명이 따로 필요하다.
Google 변경은 [별도 LINK/UNLINK 계약](google-identities.md)이며 일반 LOGIN 성공/프로비저닝과 분리한다.

## 로컬 설정·실행

Google Cloud Console에서 OAuth **웹 애플리케이션**을 준비한다. 개발 클라이언트 이름은 Overload Web Dev로
관리할 수 있지만, 이 서버가 Console 생성 여부를 추측하거나 클라이언트를 자동 생성하지 않는다.
승인된 redirect URI는 정확히 다음 하나다:

```text
http://localhost:8080/login/oauth2/code/google
```

동의 화면이 Testing이면 로그인에 사용할 Google 계정을 테스트 사용자로 등록한다.
JS origin localhost:3000은 별도 프론트의 설정이고 이 서버 흐름의 필수 설정이 아니다.
iPhone의 localhost는 PC 서버가 아니다. 휴대폰 LAN IP·임의 포트 콜백을 추가하지 않는다.

기존 local `.env[.properties]` import를 유지한다. 실제 값은 Git·채팅·로그에 넣지 않는다.

| 변수 | 의미 |
|---|---|
| GOOGLE_OIDC_ENABLED | 기본 false, Google 필터/등록의 명시적 활성화 |
| GOOGLE_CLIENT_ID | 발급된 웹 클라이언트 ID, apps.googleusercontent.com 형식 |
| GOOGLE_CLIENT_SECRET | 해당 클라이언트 비밀값 |
| GOOGLE_REDIRECT_URI | local에서 빈 값이면 위 URI; 지정해도 정확히 같은 URI만 허용 |

비-local 또는 local+prod/production 혼합은 명시적 HTTPS URI와 동일 콜백 경로가 필요하다.
true로 활성화했는데 클라이언트/콜백이 누락·잘못된 경우 부팅 실패로 알린다. 가짜 값으로 대체하지 않는다.
Google이 비활성이면 자격정보 없이 Health·OTP·세션·DB 없는 컨텍스트 테스트가 기동하며 Google 진입/콜백 GET은 503이다.
기존 Resend/HMAC/PostgreSQL 환경변수는 변경할 필요가 없다. 특히 Google 테스트 때문에 실메일을 활성화하지 않는다.

기존 Docker Compose PostgreSQL 실행 안내를 따른 뒤 저장소 루트에서 실행한다:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE/.jdks/temurin-25.0.4.1"
.\gradlew.bat --no-daemon bootRun --args="--spring.profiles.active=local --server.address=127.0.0.1 --server.port=8080 --GOOGLE_OIDC_ENABLED=true"
```

사용자가 승인한 PC 브라우저에서 **최상위 탐색**으로 `http://localhost:8080/oauth2/authorization/google`을 연다.
fetch·Vite /api 프록시를 로그인 진입으로 사용하지 않는다. 프론트 코드는 이번에 변경하지 않았다.
Google 계정 선택·비밀번호·2FA·동의는 사용자가 직접 수행하며 에이전트에게 제공하지 않는다.
테스트 종료 시 자신의 서버 프로세스를 종료하고 일시 활성화한 Google 설정을 되돌린다. DB·볼륨은 보존한다.

## HTTP 계약

| 요청 | 동작 |
|---|---|
| GET /oauth2/authorization/google | 활성 상태: Google authorization 302; 비활성: 503 GOOGLE_LOGIN_UNAVAILABLE |
| GET /login/oauth2/code/google | 활성 상태: 표준 code/state 검증; 비활성: 같은 503 |
| 검증·가입·실제 세션 저장 성공 | 고정 `/api/v1/auth/session`으로 302 |
| 프로토콜 취소·오류·저장 실패 | 고정 `/auth/google/failure`로 302 |
| GET /auth/google/failure | 401 `{code:"GOOGLE_LOGIN_FAILED"}` |
| GET /api/v1/auth/session | 인증 시 200 `{userId}` UUID 하나만; 익명 401 |
| 기존 인증 세션의 Google 시작/콜백 | 409 `{code:"LOGOUT_REQUIRED"}`, 현재 로그인 유지 |

상태/실패 API와 OAuth 응답은 no-store다. OAuth에는 no-referrer도 설정한다.
성공 URL에는 코드·토큰·이메일·UUID를 넣지 않는다. next/returnUrl은 사용하지 않는다.
보호 API의 익명 응답은 기존 JSON 401이며 로그인 HTML·Basic·폼 로그인으로 대체하지 않는다.
세션 상태 API는 읽기 전용이며 요청 userId를 사용하지 않는다. 앱 계정 식별자는 서버 principal에서만 얻는다.

## 검증 경계

등록은 공식 Google 엔드포인트와 `https://accounts.google.com` issuer를 사용한다. 외부 issuer/JWKS를
설정하는 생산용 환경변수를 추가하지 않았다. 공식 등록과 같은 RS256 키 검증·OidcIdTokenValidator·
JwtTimestampValidator·OIDC provider의 nonce 검증을 사용한다.

- 표준 code grant의 redirect_uri·state·PKCE S256을 확인하고 클라이언트 인증으로 토큰을 교환한다.
- state/nonce/code verifier는 임시 익명 Spring Session에만 바인딩한다. 기본 resolver의 nonce 해시 바인딩을 그대로 유지한다.
- 서명/JWKS·정확한 issuer·client audience·필수 sub/exp/iat·시각·다중 aud의 azp 등을 공식 검증기가 검사한다.
  기본 시계 오차 허용은 라이브러리 기준(60초)이며 테스트 만료값은 이를 충분히 넘긴다.
- ID 토큰은 등록된 HTTPS issuer를 정확히 따라야 한다. 기존 VerifiedIdentity의 Google issuer 정규화 정책은 바꾸지 않는다.
  실제 프로비저닝 키는 `(google, https://accounts.google.com, sub)`다.
- 이메일/이메일 검증 플래그를 신원 키로 쓰지 않는다. 이메일 변경·주소 일치로 새 신원 연결/계정 병합을 하지 않는다.
- 검증된 ID 토큰으로 충분하므로 UserInfo 호출을 끈다. Google API·offline access·refresh token을 요청하지 않는다.
- 브라우저가 임의 ID token/OidcUser/userId를 넘기는 공개 로그인/가입 경로는 없다.

토큰 교환은 connect 3초/read 5초이며 자동 재시도하지 않는다. Google 원문 오류를 안전한 일반 오류로 바꾼다.
callback의 code/state 전체 URL, Authorization 헤더, 토큰 본문을 기록하지 않는다.
기본 access log는 비활성이고 민감 HTTP/OAuth DEBUG·TRACE·wire logging을 켜지 않아야 한다.
CSRF는 기존 상태 변경 요청 전체에 유지하며 OAuth GET의 state 보호를 이유로 제외 범위를 넓히지 않았다.

## 일회성 상태·계정·세션 순서

1. authorization request를 기존 JDBC Spring Session에 저장하고 저장소 재조회로 state 저장을 확인한 뒤 Google로 이동한다.
2. 콜백에서 익명 세션 행을 잠그고 최신 요청/state를 같은 DB 연결로 다시 읽는다. Spring Session JDBC 기본값과 같은
   Spring Core DeserializingConverter를 사용한다. REQUIRES_NEW인 SessionRepository를 잠금 트랜잭션 안에서 호출하지 않아
   작은 연결 풀 고갈을 피한다. 기본 세션 serializer를 바꾸면 이 임시 상태 reader도 함께 검토해야 한다.
   일치한 요청만 익명 세션 행을 삭제·커밋하고 FK로 임시 속성도 제거한다. 병렬 콜백은 한 건만 획득하며 새 DDL은 없다.
3. 이 트랜잭션이 끝난 뒤 네트워크 토큰 교환·서명/클레임/nonce 검증을 수행한다. provider 실패나 상태 소비 실패는 계정을 만들지 않는다.
4. 임시 OAuth context는 저장하지 않는다. 필터의 Context repository를 Null로, 필터 자체 세션 전략을 no-op으로 두어
   기본 성공 저장/회전이 최종 자체 세션보다 먼저 일어나지 않게 한다. AuthorizedClient 저장도 폐기형 repository로 대체한다.
5. 검증된 issuer/sub만 외부 트랜잭션 없이 기존 AccountProvisioningService(REQUIRES_NEW)에 전달한다.
   기존 유일키/롤백·동시 가입·운동 20개/휴식 기본값·재로그인 보존 경로를 재사용한다.
6. 공용 VerifiedSessionLogin이 새 JDBC primary key 세션을 만들고 기존 AuthenticationSessionService로
   SessionPrincipal(UUID)/SessionMetadata만 저장한다. immediate flush 후 Context·UUID·metadata 전체 일치를 재조회한다.
   이 확인을 통과한 뒤만 성공 302를 보낸다. 같은 공용 경로를 이메일 OTP도 사용한다.

Google access/ID/refresh token·OidcUser·OAuth2AuthenticationToken은 장기 세션/DB에 저장하지 않는다.
콜백 재생·취소·오류는 임시 상태를 제거하고 일반 실패 위치로 보낸다. 이미 로그인한 브라우저의 시작/콜백은
토큰 교환 전에 409로 닫으며 기존 계정·세션을 교체하거나 만료를 연장하지 않는다.
다른 브라우저의 잘못된 콜백이 원래 브라우저의 미완료 state를 소유·삭제할 수는 없다.

프로비저닝 실패는 계정/seed 원자 롤백 후 새 Google 진입부터 재시도한다. 세션 저장 실패는 이미 완성된 계정이
남을 수 있으나 성공으로 응답하지 않고 Context/세션을 폐기한다. DB 회복 후 새 흐름은 같은 issuer/sub 계정을 재사용한다.
소비된 state/code를 재활성화하거나 계정/세션/외부 Google 교환을 하나의 트랜잭션이라고 취급하지 않는다.

기존 최초 로그인 +90일 절대 만료·10분 재인증·쿠키 HttpOnly/SameSite/Secure·로그아웃/CSRF 갱신을 유지한다.
이번 일반 Google 로그인은 인증 수단 연결·재인증 API가 아니다. 이메일 재인증은
[007-1C-3B-1](email-reauthentication.md), 별도 Google 최근 재인증은
[007-1C-3B-2](google-reauthentication.md)는 독립 `GOOGLE_REAUTH_ENABLED=false`로 기본 비활성이다.
일반 LOGIN에는 이 플래그를 요구하지 않는다. Google 재인증의 개발 클라이언트 auth_time 제공 여부는 LIVE 전까지 미확인이다.
Google-only의 [대표 이메일 최초 등록](reauthentication-email.md)은 별도 `/oauth2/contact/google` POST와
`/login/oauth2/code/google-contact` 콜백으로 기존 issuer/sub 및 서명된 verified email 후보만 확인한다.
추가 OTP까지 검증해야 등록/최근 이메일 재인증을 인정하며, Google 비밀번호 재인증이라고 주장하지 않는다.
일반 LOGIN 성공은 대표 연락처를 자동 등록하거나 기존 연락처를 Google 이메일로 덮어쓰지 않는다.
EMAIL·Google 수단 연결·해제는 후속 구현된 [EMAIL 계약](email-identities.md)과 [Google 계약](google-identities.md)을 따른다. LOGIN 성공만으로 기존 계정의 신원을 자동 연결하지 않는다.

## 검증·실로그인 구분

```powershell
.\gradlew.bat --no-daemon test integrationTest build
```

자동화의 Google 자격정보·RSA 키·OIDC 서버는 src/test에만 있으며 생산 JAR에는 포함되지 않는다.
Mock 등록은 테스트의 루프백 authorization/token/JWKS와 임시 포트만 대체하고 issuer·서명·state·nonce 검증은 유지한다.
PostgreSQL은 독립 Testcontainers 18.6이며 기존 Compose DB·실메일·개인 Google 계정은 사용하지 않는다.
최종 개수·실패 수정·Git/정리 증거는 [검증 보고서](../tasks/task-007-1c-3a-validation-report.md)를 따른다.

기존 PC 브라우저 로그인은 3A-LIVE 결과와 구분하며 이번 자동화 결과로 실로그인을 새로 검증했다고 주장하지 않는다.
Frontend 인증 연동과 EMAIL/Google 연결·해제는 후속 구현됐다. Google auth_time을 사용하는 민감 업무 쓰기는 별도 미구현 범위다. 최신 코드의 실제 브라우저·iPhone/LAN HTTPS 검증과 운영 배포를 이번 자동화 결과로 완료 처리하지 않는다.
Google 재인증은 테스트 OIDC/PostgreSQL 검증과 실제 Google 재인증 검증을 구분한다.

## Task 007-1D-1: 선택적 PWA 복귀

기본 LOGIN 성공 `/api/v1/auth/session` 및 실패 `/auth/google/failure`는 유지한다.
서버 소유 `AUTH_FRONTEND_RETURN_URI`가 비어 있으면 기존 LIVE 계약 그대로다.
명시적으로 설정한 경우에만 성공은 이 고정 URI, 실패는 같은 origin의 `/auth/failed`로 이동한다.

- local 전용 값: `http://localhost:3000/auth/complete` 정확히 하나.
- 비로컬/혼합 production 프로필: 명시한 신뢰 가능한 HTTPS origin의 `/auth/complete`, 기본 HTTPS 포트만 허용한다.
- userinfo·query·fragment·CRLF·다른 경로·상대/프로토콜 상대 URI·인코딩 경로 변형은 거절한다.
- 요청 next/returnUrl·Host/forwarded header로 복귀 목적지를 만들지 않는다. 잘못된 설정 값 자체를 오류에 출력하지 않는다.
- code/state/token은 복귀 URL에 포함하지 않는다. 기존 no-store/no-referrer 및 state/nonce/PKCE·JDBC 세션 검증은 유지한다.
- CONTACT/LINK/조건부 REAUTH의 성공/실패 경로는 바꾸지 않는다. Google 강제 재인증은 계속 기본 비활성이다.

PWA는 localhost:3000의 Vite 프록시로 시작하지만 기존 공식 LOGIN 콜백은 localhost:8080 그대로다.
같은 localhost 호스트의 쿠키를 유지하며 localhost와 127.0.0.1/LAN IP를 섞지 않는다.
운영은 같은 호스트 HTTPS 역방향 프록시 및 SPA 복귀 라우팅을 별도 구성/검증해야 한다.
쿠키 Domain·CORS·HttpOnly/SameSite/Secure·90일 정책은 변경하지 않았다.
실제 `.env`·개발 DB·V1~V8은 바꾸지 않았고 실서비스 인증·메일·운영 배포는 수행하지 않았다.

기존 364개와 신규 24개를 합한 388개 테스트 및 `test integrationTest build`가 통과했다.
고정 URI·주입/미승인 경로 거부는 단위 테스트, 성공/실패 콜백과 JDBC 복원은 실제 PostgreSQL Testcontainers와 서명된 테스트 OIDC로 검증했다.
프론트 연동/브라우저 범위는 [007-1D-1 보고서](../../../overload-pwa/docs/tasks/task-007-1d-1-validation-report.md)를 따른다.

### Task 007-1D-2A: CONTACT 전용 선택적 PWA 복귀

`AUTH_CONTACT_FRONTEND_RETURN_URI`는 LOGIN의 `AUTH_FRONTEND_RETURN_URI`와 독립적이다.
빈 기본값은 기존 성공 `/api/v1/auth/reauth/email/contact`, 실패 `/auth/google/contact/failure`를 보존한다.
local에서는 `http://localhost:3000/account/contact/complete`만 허용하며 실패는 같은 origin의 `/account/contact/failed`다.
비로컬 또는 production 혼합 프로필에서는 운영자가 명시한 HTTPS origin의 같은 고정 경로/기본 HTTPS 포트만 허용한다.
기존 LOGIN과 같은 검증을 공유하여 userinfo/query/fragment/제어문자/다른 경로/포트/인코딩 경로 변형을 거절한다.
요청 Host/next/returnUrl을 사용하지 않고 토큰/code/state/email/sub를 복귀 URI에 넣지 않는다.

변경은 CONTACT 성공·실패의 목적지뿐이다. POST 시작·CSRF·원본 세션/identity 바인딩·서명/nonce/state/PKCE 검증과
후보 생성 경계는 그대로다. LOGIN/LINK/기본 비활성 REAUTH의 복귀 계약은 변경하지 않았다.
React는 복귀 후 서버 contact 상태를 재조회하며, 후보 확인만으로 VERIFIED나 최근 이메일 소유 증명을 표시하지 않는다.
기존 Google Console CONTACT 콜백 localhost:8080은 그대로이며 React 복귀 경로로 대신 등록하지 않는다.
실제 `.env`는 수정하지 않았고 후속 LIVE 시 해당 프로세스에만 설정해 검증한다.

기본 계약 회귀 테스트에는 빈 CONTACT 복귀 인자를 명시해 향후 로컬 설정과 격리했다.
검증은 PostgreSQL 18.6 Testcontainers·서명된 테스트 OIDC·fake sender로 수행했으며 실제 계정/메일을 사용하지 않았다.
프론트 범위와 결과는 [1D-2A 보고서](../../../overload-pwa/docs/tasks/task-007-1d-2a-validation-report.md)를 따른다.

기존 OIDC 공식 근거:

- [Spring OAuth2 Login 구성](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html)
- [Security 7.1.2 OAuth2 Login 필터](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/web/OAuth2LoginAuthenticationFilter.java)
- [기본 성공 시 Context 저장 순서](https://github.com/spring-projects/spring-security/blob/7.1.2/web/src/main/java/org/springframework/security/web/authentication/AbstractAuthenticationProcessingFilter.java)
- [기본 ID token 검증기](https://github.com/spring-projects/spring-security/blob/7.1.2/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/oidc/authentication/OidcIdTokenDecoderFactory.java)
- [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect)
