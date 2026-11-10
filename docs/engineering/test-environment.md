# UI 검토와 API 통합 테스트 환경

UI 구현·Mock 시연·사용자 승인 순서는 [Frontend 기본 UI 개발 절차](../../../overload-pwa/docs/engineering/frontend.md#181-기본-ui-개발-절차)를 따른다. 실제 API 연결에 들어갈 때는 현행 [API 계약](../api/)과 이미 준비된 테스트 서버를 먼저 조사한다. 기존 환경의 소유자·격리·스키마·접근 경로가 모두 맞으면 재사용한다. 조건이 맞지 않으면 승인 범위를 확인한 뒤 기존 일회용 fixture를 사용한다.

## 환경 선택

| 환경 | 용도와 경계 |
| --- | --- |
| UI Mock 미리보기 | PWA의 개발 전용 화면에서 합성 데이터로 배치·조작을 검토한다. Backend·DB 준비 전에 사용할 수 있다. |
| 보존된 홈·루틴 검토 환경 | 실제 Spring Boot·Vite·PostgreSQL과 검토 계정 데이터의 역사적 통합 환경이다. named volume과 PC 덤프를 보존한다. 마지막 DB 직접 확인은 V24였으며 최신 API 연동에는 버전 호환성 확인이 필요하다. |
| 일회용 PostgreSQL 자동 테스트 | JDK 25, Gradle Wrapper와 Docker Desktop Linux 엔진이 필요하다. `integrationTest`는 `postgres` 태그와 Testcontainers PostgreSQL 18.6을 사용한다. 격리 DB에서 빈 DB V1~V31 적용, V30→V31 전환 및 서버 API를 검증한다. |
| 일회용 V30·V31 브라우저 검토 fixture | 기존 Backend test-classpath 진입점이 새 검토 실행에 전용 DB, 실제 Spring API, 합성 계정·표본을 준비한다. 프런트는 별도 loopback Vite 프록시를 연결하고 계약을 검증한다. |
| 로컬 개발 DB / 개인 데이터 | Compose의 PostgreSQL 18.6은 기본 loopback 5433과 전용 named volume을 사용한다. 개인 기록의 실제 위치와 적용 이력을 별도로 확인한다. 통합 작업에는 테스트 전용으로 확인된 대상만 사용한다. |
| 운영 | 별도의 HTTPS 배포 및 운영 계정·DB다. 테스트 seed, 미리보기, 테스트 인증, 실험적 DB 적용은 격리 환경에서만 수행한다. |

로컬 개발의 `compose.yaml`, `.env.example`, `application-local.yaml`과 `docker compose ps`, `.\gradlew.bat --no-daemon bootRun --args='--spring.profiles.active=local'`은 로컬 개발용이다. 설정 키는 `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT`다. `bootRun`은 연결 대상에 대기 migration을 적용할 수 있으므로, 격리 환경을 확인하기 위해 실행부터 하지 않는다. 로컬 실행 및 종료 명령은 기존 안내를 참조한다. [로컬 PostgreSQL·Flyway](local-postgresql.md)

## Mock 미리보기

PWA 루트에서 Node.js 24, 설치된 의존성, 빈 포트를 확인한 뒤 `npm run dev -- --host 127.0.0.1`로 개발 서버를 실행한다. 기본 포트는 3000이다. 예를 들어 `/routine-preview`의 합성 루틴과 빈 목록, `/ui-foundation`의 T01 공통 표현을 확인할 수 있다. 대상 화면의 기존 미리보기나 별도 fixture를 사용하고 개인 앱과 분리된 브라우저 프로필에서 연다. `/demo`는 기기에 데이터를 저장하는 별도 개발 데모이므로 화면 검토 전용 Mock에는 `/routine-preview`와 같이 실제 저장 경로를 호출하지 않는 진입점을 사용한다. [Vite 설정](../../../overload-pwa/vite.config.ts) · [미리보기 분기](../../../overload-pwa/src/main.tsx) · [루틴 Mock 예시](../../../overload-pwa/src/review/RoutinePreview.tsx)

## 보존된 검토 서버 확인과 재개

- 구성은 Windows의 PWA와 Backend 형제 저장소, PowerShell 7, Node.js 24, JDK 25, Docker Desktop Linux 엔진, PostgreSQL named volume 및 Git에서 제외한 로컬 archive다. DB는 전용 loopback 포트를 사용한다. Backend test-classpath의 `HomeReviewResumeEnvironment`는 Spring을 동적 loopback 포트로 실행하고 PWA의 `home-review-server.mjs`는 Vite를 3000에서 제공한다. 시험용 메일 수신함과 제어 포트도 loopback이다.
- 2026-11-09에 상태 명령으로 보존 컨테이너의 **중지 상태**, named volume 연결과 원본 덤프 해시 일치를 확인했다. 당시 명령은 DB에 접속하지 않았다. 실제 Flyway 버전은 과거 읽기 전용 DB 조회의 **V24**가 마지막 확인 기록이다. T02-A 마감의 V22 복원과 T02-B V23 추가 적용 기록은 그보다 이전 상태다. [V24 직접 확인 기록](../../../overload-pwa/docs/tasks/deload-v17-backend-readiness-analysis.md#9-실제-db서버백업-보존-확인) · [백업·복원 근거](../../../overload-pwa/docs/tasks/ui-v09-t02a-closeout.md)
- 보존 DB 재개 전용 검토 서버는 `actualMigrations`가 정확히 V1~V24인 실행만 수용한다. 현재 Backend는 V31을 목표로 하므로 최신 두 저장소와 보존 DB를 조합해 바로 재개할 수 있다고 가정하지 않는다. 보존 환경을 다시 쓸 때는 호환 리비전과 API·스키마를 별도로 확인한다. 초기화, 자동 적용, 검사 우회로 버전 차이를 해소하지 않는다. [검토 Vite 버전 검사](../../../overload-pwa/scripts/home-review-server.mjs) · [Backend 수동 재개 진입점](../../src/test/java/com/overload/testfixture/HomeReviewResumeEnvironment.java)
- 실제 연동에 앞서 PWA 루트에서 `pwsh -NoProfile -File scripts/home-review-resume.ps1 -Action Status`를 사용한다. 이 명령은 아카이브·덤프 해시·컨테이너 라벨·볼륨·실행 상태를 읽는다. 상태 확인과 실제 재개는 별개다. 호환성 확인, T02-B 1차 UI 완료, 사용자의 명시적 재개 승인 후에만 같은 스크립트의 `-Action Start -AfterT02BFirstUi`를 수동으로 실행한다. `-JavaHome`에는 설치된 JDK 25, `-LanAddress`에는 필요한 경우 확인한 현재 사설 IPv4를 사용한다. `-MigrateT02BV23`은 당시 V23 전용 승인 옵션이다.
- 재개한 검토 앱은 PC에서 `http://127.0.0.1:3000/`, 승인된 동일 사설망 실기기에서는 확인한 LAN 주소의 3000으로 접근한다. `/__home_review`에서 실행 ID와 Backend origin을 확인한 뒤 검토 계정으로 로그인한다. LAN HTTP에서 Web Locks를 쓸 수 없으면 새 운동 START는 PC loopback으로 검증한다. 설치형 PWA 및 HTTPS 쿠키 검증은 별도 기록한다. [수동 재개 스크립트](../../../overload-pwa/scripts/home-review-resume.ps1)

<a id="v30-자동-테스트와-일회용-브라우저-fixture"></a>

## V31 자동 테스트와 일회용 브라우저 fixture

자동 회귀는 Backend 루트에서 `.\gradlew.bat --no-daemon integrationTest`를 실행한다. 단일 스키마 감사가 필요하면 `.\gradlew.bat --no-daemon integrationTest --tests '*PostgresMigrationTests'`로 범위를 좁힌다. V1~V30 신규 적용과 V24→V30 전환의 이전 검증 기록은 [Backend 12.5절](backend.md#125-다중-주간-일정-프리셋)에 있다. 자동 테스트와 브라우저 실제 연동 결과는 따로 기록한다. [Gradle 테스트 구성](../../build.gradle) · [마이그레이션 통합 테스트](../../src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java)

HOME·ROUTINE 검토에는 이름을 유지한 `V22BrowserEnvironment` 진입점을 사용했다. 이 진입점은 새 tmpfs DB와 전용 loopback 포트, V1~V31 Flyway 검증을 사용한다. 합성 계정은 테스트 전용 메일 발송기로 실제 OTP 인증을 거친다. 검토 표본은 실제 API로 준비했다. `HomeReviewEnvironment`는 별도의 자동 표본 생성 진입점이고, 기존 DB·계정 보존이 필요한 경우에는 앞 절의 재개 조건을 따른다. [격리 실행 검증](../../src/test/java/com/overload/testfixture/V22BrowserEnvironment.java) · [홈 표본 생성](../../src/test/java/com/overload/testfixture/HomeReviewEnvironment.java)

승인된 일회용 실행의 Backend 루트 명령이다. Docker와 실제 설치된 `JAVA_HOME`을 확인하고, 개발·운영 설정이 유입되지 않는 별도 셸을 사용한다. fixture는 `SPRING_*`, `OTP_*`, `RESEND_*`, `GOOGLE_*`, `AUTH_*`, `OVERLOAD_*`가 상속된 실행을 거부한다. 새 UUID마다 독립 실행 디렉터리를 사용한다.

```powershell
.\gradlew.bat --no-daemon -I scripts/home-review.gradle homeReviewClasspath
if ($LASTEXITCODE -ne 0) { throw 'homeReviewClasspath failed' }
$reviewClasspath = (Get-Content -Raw -LiteralPath 'build/home-review/classpath.txt').Trim()
$reviewRun = [guid]::NewGuid().ToString()
& (Join-Path $env:JAVA_HOME 'bin/java.exe') -cp $reviewClasspath com.overload.testfixture.V22BrowserEnvironment $reviewRun
```

`V22_READY` 이후 `build/verification-v22/<run-id>/ready.json`에서 실행 ID, PID, `backendOrigin`, DB와 migration 근거를 필요한 항목만 확인한다. 로그·ready 파일에 있는 인증 정보는 로컬 테스트 세션 안에서만 사용한다. HOME·ROUTINE의 실제 OTP 검토에는 `V22BrowserEnvironment`의 `ready.json`을 검증하는 [전용 Vite 실행기](../../../overload-pwa/scripts/v30-review-server.mjs)를 사용했다. 이 실행기는 격리 V30을 4174, 격리 V31을 4175에서 제공한다. V24 재개 전용 `home-review-server.mjs`와 V22 이력을 요구하는 구형 검증기는 별도 경로다. [test-classpath 작업](../../scripts/home-review.gradle) · [구형 브라우저 증거 검사](../../../overload-pwa/tests/fixtures/v22-environment.mjs)

## DB와 테스트 데이터 준비

1. 환경·컨테이너 라벨·마운트·loopback 포트·DB명·테스트 계정·데이터 세대를 먼저 확인한다. 이미 실행 중인 승인된 DB의 버전이 필요하면 확인된 대상에 **읽기 전용**으로 접속해 `flyway_schema_history`를 설치 순서대로 조회한다. 중지된 보존 DB의 버전 확인만을 위해 시작하지 않는다. 목표 스키마와 실제 적용 이력은 별개로 기록한다. [목표 스키마](../database/schema.md) · [ERD](../database/erd.md)
2. V31 이름 저장을 포함한 배포 전 검증은 성공 이력 V1~V31과 실패 유무를 확인한다. V24 이후 migration이 필요한 보존·개발 DB의 변경은 대상·백업·영향을 확인하고 별도로 승인받는다. V30 적용이나 기존 계정 로그인만으로 프리셋 상태가 생기지 않으며, 기존 미설정 계정의 G4 이행도 별도다. 새 테스트 계정을 정상 인증으로 생성하거나 사용 가능한 기존 전용 계정을 확인한다. [프리셋 전환](../api/weekly-schedule.md#v1-출시-전-전환) · [디로딩 분석 API](../api/deload-analysis.md)
3. 화면별 표본은 테스트 전용 계정과 현재 세대에서 API로 준비한다. 기존 fixture가 있으면 필요한 시나리오에 재사용한다. 추가 표본은 실행 ID·고정 요청 식별자 등으로 구분하고 재실행 시 같은 준비가 중복 운동을 만들지 않도록 현재 상태·버전·멱등성 계약을 확인한다. 데이터 보존이 필요한 환경에서는 기존 기록·백업을 그대로 두고 다른 작업의 계정·자료를 삭제하거나 재시드하지 않는다. 개인 브라우저의 IndexedDB·고정 요청·세션도 보호한다. [루틴 API](../api/routine-management.md) · [세션 API](../api/workout-sessions.md)

```sql
BEGIN READ ONLY;
SELECT current_database(), inet_server_port();
SELECT installed_rank, version, success FROM flyway_schema_history ORDER BY installed_rank;
COMMIT;
```

## 실제 브라우저 연동 검증

1. 승인된 Backend의 loopback origin에서 `GET /api/v1/health`의 HTTP 200과 `status=UP`를 확인한다. Health는 HTTP 응답 확인이고, DB 이력과 인증된 데이터 조회는 별도 검증한다.
2. 일회용 V31 fixture나 확인된 loopback Backend에 일반 Vite를 연결할 때는 PWA 루트의 별도 셸에서 `OVERLOAD_DEV_API_TARGET`을 확인된 Backend origin으로 지정한 뒤 `npm run dev -- --host 127.0.0.1`를 실행한다. 프록시는 같은 origin의 `/api`와 Google 인증 경로를 전달한다. 다른 서비스가 이미 포트를 사용하면 그대로 두고 `--port`로 검증 전용 포트를 지정한다. `VITE_HOME_REVIEW`를 켜지 않은 상태에서 `/`의 실제 계정 경로를 분리된 테스트 브라우저 프로필로 연다.
3. 전용 테스트 계정으로 로그인하고 세션 쿠키와 CSRF 획득·갱신, 로그아웃과 재로그인을 기존 계약으로 확인한다. HTTP 쿠키 예외는 `local` 테스트에 한정한다. 일회용 홈 fixture의 경우 실제 UI의 OTP 요청을 사용하고, 같은 실행의 테스트 전달 경로만 사용한다. V31 전용 프록시는 loopback 제어 서비스에 연결한다. 보존 검토 fixture는 `inboxOrigin`과 일치하는 `X-Review-Run` 헤더를 사용한다. 운영 메일 발송이나 개인 Google 계정을 사용하지 않는다. 쿠키·토큰·OTP·비밀키·개인 계정 값은 커밋, 로그 공유, 캡처, 보고서에 남기지 않는다. [서버 세션·CSRF](security-session.md) · [PWA 인증](../../../overload-pwa/docs/engineering/authentication.md)
4. 브라우저 Network에서 실제 응답 상태와 필요한 필드만 확인한다. 표시·쓰기·새로고침·재조회가 같은 계정·세대를 유지하는지, 유효한 입력과 오류·권한·구버전 충돌을 구분하는지 검증한다. 홈 예시에서는 활성 프리셋·요일 배정·오늘 루틴·최근 운동을 응답과 비교한다. 미리보기의 합성 응답이나 fallback을 실제 앱 경로가 소비하지 않는지 확인한다. Job이나 모바일·오프라인 동작은 해당 경로를 실제로 실행한 결과를 따로 기록한다. fixture의 `integration` 프로필은 자동 review/expiry를 끈다. [통합 프로필](../../src/test/resources/application-integration.yaml)

<a id="home-routine-검토-종료-기록"></a>

## HOME·ROUTINE 검토 종료 기록

2026-11-10 종료 확인 당시 Mock Vite 4173과 실제 V31 Vite 4175, V31 Spring 5505·제어 5957·전용 PostgreSQL 32784를 정리했다. V30의 Vite 4174와 Spring 2755는 앞서 실행 기한이 끝났다. 통합 테스트가 끝난 뒤 검토 포트 해제와 일회용 테스트 컨테이너 정리를 확인했다. 개인 개발 DB·보존 DB·다른 프로젝트 서비스에는 조치하지 않았다. 위 포트는 종료 이력이며 활성 접속 주소로 사용하지 않는다.

## 종료와 보존

- **Mock 및 일회용 실행.** 소유한 Vite를 실행 터미널에서 `Ctrl+C`로 종료한다. 일회용 Backend도 해당 터미널에서 정상 종료한다. 홈 fixture는 해당 `build/verification-v22/<run-id>/stop` 파일의 내용이 자신의 run ID와 일치할 때 정상 종료할 수 있다. JVM의 종료 루틴과 Testcontainers가 자신이 만든 임시 PostgreSQL을 정리한다. 종료 후 해당 run의 PID·포트·컨테이너만 확인한다. 브라우저 저장소 정리는 전용 프로필에 한정한다.
- **보존된 검토 실행.** 수동 재개한 동일 run을 확인하고 PWA 루트에서 `pwsh -NoProfile -File scripts/home-review-resume.ps1 -Action Stop`을 사용한다. 이 경로는 프런트와 Spring을 닫은 뒤 보존 DB를 정지한다. 다시 `-Action Status`로 컨테이너·볼륨·덤프가 남아 있는지 확인한다. 구형 `home-review.ps1 -Action Stop`, 기존 볼륨 삭제·prune·덮어쓰기 복원은 피한다. 추가 검토 데이터의 백업이 승인되면 새 파일로 보존한다. [보존 및 장애 복원 기록](../../../overload-pwa/docs/tasks/ui-v09-t02a-closeout.md)
- **로컬 개발·개인·운영 환경.** 사용한 테스트 실행과 경계가 다른 컨테이너·볼륨·계정에는 정리 작업을 적용하지 않는다. 실제 데이터 삭제·초기화·migration은 [Backend AGENTS.md 15절](../../AGENTS.md#15-git-and-change-management)의 승인 절차를 따른다.
