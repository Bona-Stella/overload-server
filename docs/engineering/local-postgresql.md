# 로컬 PostgreSQL · Flyway

> **개인 개발 DB V15 재구성 완료 (2026-11-01):** 사용자 명시 승인으로 `127.0.0.1:5433/overload_dev`의 기존 테스트 데이터를 폐기하고 V1~V15를 신규 적용했습니다. Flyway/Hibernate 검증·기본 조회 통과, 계정과 업무 데이터는 모두 비어 있습니다. 기존 컨테이너/볼륨을 유지했고 기동 검증 후 원래 정지 상태로 복원했습니다. [실제 재구성 보고서](../tasks/task-009-dev-v15-rebuild-report.md)를 확인하세요. 다른 DB·후속 파괴적 변경은 별도 승인이 필요합니다.

Task 007의 개발 환경이다. Windows/WSL에 PostgreSQL을 직접 설치하지 않는다.
007-1B의 내부 계정·개인 종목 seed는 [별도 문서](account-provisioning.md)를 따른다.
운영 서버 연결·배포 설정이 아니다. 이메일 OTP와 선택적 Google OIDC 서버 인증은 구현했으며,
공개 임의 계정 생성 API·운동 기록 기능은 구현하지 않았다.

## 준비 및 충돌 확인

- JDK 25, 저장소의 Gradle Wrapper, 실행 중인 Docker Desktop Linux 엔진과 Docker Compose가 필요하다.
- Windows PowerShell에서 저장소 루트(`build.gradle` 위치)를 작업 디렉터리로 사용한다.
- WSL 배포판에 PostgreSQL을 설치하거나 WSL/Docker 시스템 설정을 바꿀 필요가 없다.

```powershell
docker version
docker compose version
wsl --status
wsl --list --verbose
docker ps -a --format '{{.Names}} {{.Image}} {{.Ports}} {{.Status}}'
docker volume ls
Get-NetTCPConnection -State Listen | Where-Object LocalPort -in 5432,5433
```

기존 컨테이너·볼륨·DB에는 접속하거나 마이그레이션하지 않는다. 이 구성의 기본 식별자는
프로젝트 `overload-local`, 서비스 `postgres`, 컨테이너 `overload-local-postgres-1`,
볼륨 `overload-local_postgres_data`다. 같은 식별자의 기존 리소스가 있다면 먼저 소유·용도를 확인한다.
기존 PostgreSQL이 흔히 사용하는 5432 대신 **127.0.0.1:5433**에만 공개한다.
5433도 사용 중이면 기존 서비스를 중지하지 말고 `.env`의 `POSTGRES_PORT`를 빈 포트로 변경한다.

## 비밀값 준비

`.env.example`은 비밀번호가 없는 설명 예시이며 `.env`는 Git에서 제외된다.
최초 한 번 아래 명령으로 로컬 전용 비밀번호를 생성한다. 기존 `.env`는 덮어쓰지 않는다.

```powershell
if (Test-Path .env) { throw '.env already exists; keep the existing file.' }
$localPassword = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$localConfig = (Get-Content .env.example -Raw).Replace('POSTGRES_PASSWORD=', "POSTGRES_PASSWORD=$localPassword")
[System.IO.File]::WriteAllText((Join-Path $PWD '.env'), $localConfig)
Remove-Variable localPassword,localConfig
git check-ignore .env
docker compose config --quiet
```

위 생성 명령은 PowerShell 7 / 최신 .NET 기준이다. 다른 셸에서는 `.env.example`을 `.env`로
복사하고 `POSTGRES_PASSWORD`에 로컬 전용 임의 값을 직접 입력해도 된다.
Compose dotenv와 Spring properties 양쪽에서 동일하게 해석되도록 값은 **따옴표 없이
영문·숫자·밑줄·하이픈만** 사용한다. `.env` 내용이나 비밀값이 펼쳐지는 `docker compose config`
출력을 공유하거나 Git에 추가하지 않는다. 설정 검사는 `--quiet`를 사용한다.

| 변수 | 용도 / 예시 기본값 |
|---|---|
| `POSTGRES_DB` | 새 로컬 개발 DB: `overload_dev` |
| `POSTGRES_USER` | 새 로컬 개발 사용자: `overload_dev` |
| `POSTGRES_PASSWORD` | 필수, 예시 파일에는 비어 있음 |
| `POSTGRES_PORT` | 호스트 루프백 포트: `5433` |

컨테이너 환경변수는 **빈 볼륨 최초 초기화** 때 DB/사용자/비밀번호를 만든다.
기존 볼륨이 있는 상태에서 `.env`만 바꿔도 DB 자격정보가 바뀌지는 않는다.
비밀번호를 분실하거나 접속이 실패해도 볼륨을 지우거나 기존 DB를 초기화하지 말고 원인을 확인한다.
공식 이미지의 초기 사용자는 로컬 개발용 관리자이므로 이 구성을 운영 배포에 그대로 사용하지 않는다.

## 실행 및 접속

```powershell
docker compose up -d --wait --wait-timeout 120 postgres
docker compose ps
.\gradlew.bat --no-daemon bootRun --args='--spring.profiles.active=local'
```

Java가 PATH에 없다면 실제 설치된 JDK 25 경로를 현재 터미널에만 지정한다.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
.\gradlew.bat --no-daemon --version
```

`local` 프로필은 루트 `.env`를 명시적으로 읽어 JDBC URL·사용자·비밀번호를 구성한다.
Spring Boot가 Compose를 자동 기동하지 않으므로 DB를 먼저 실행한다. 기본 앱 포트는 8080이다.
아래 접속은 새 Overload DB에만 수행한다(변수를 바꿨으면 그 값으로 대체).

```powershell
docker compose exec postgres psql -U overload_dev -d overload_dev
```

SQL 예시:

```sql
SELECT version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT count(*) FROM users;
```

`local` 이외의 실행에서는 `.env`를 자동으로 읽지 않는다. 표준 Spring 외부 설정
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`를 제공한다.
이 표준 환경변수는 `local` 프로필에서도 우선하므로, 이전 터미널의 다른 DB 설정이 남아 있지 않은지
확인한다. 기존/운영 DB 주소를 사용하지 않는다. 연결정보가 없거나 DB에 연결할 수 없으면 시작이
실패하며 H2로 대체하지 않는다.

## 스키마 정책

- Spring Boot 시작 시 Flyway가 `classpath:db/migration`의 버전 SQL을 적용·검증한다.
- V1 users → V2 계정 기반 → V3 Spring Session → V4 이메일 OTP 순서로 적용한다.
  `users.id`는 서버가 공급할 UUID PK이며 DB 자동 UUID 기본값은 없다.
- `data_generation BIGINT NOT NULL DEFAULT 0`, `CHECK (data_generation >= 0)`다.
  후속 초기화 트랜잭션에서 서버가 관리하며 클라이언트 쓰기 권한/API는 아직 없다.
- `nickname TEXT NULL`이며 닉네임 길이·고유성 등 미확정 제품 제약을 추가하지 않았다.
- `created_at`, `updated_at`은 `TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`다.
  삽입 시 기본값만 제공하며, 후속 서버 갱신 로직이 `updated_at`을 명시적으로 유지해야 한다.
- V2는 신원·개인 웨이트 종목·휴식 설정 기반을 추가한다. 마이그레이션/앱 시작은 계정 seed를 생성하지 않는다.
  검증된 신원에 대한 내부 프로비저닝에서만 개인 seed를 생성한다.
  V3는 공식 Spring Session JDBC 테이블·인덱스를 생성하며 Session 자체 DDL 실행은 비활성화한다.
  V4는 OTP 발급·제한·로그인 claim을 추가한다. 루틴·운동 기록·동기화 테이블은 아직 없다.
- 스키마 변경 경로는 Flyway 하나다. 기본 SQL 초기화는 비활성화하고,
  Flyway clean은 차단하며 자동 baseline은 사용하지 않는다. 008-0C의 JPA/Hibernate는 기존 V2 종목·설정만 매핑하고
  `ddl-auto=validate`, `generate-ddl=false`, `open-in-view=false`를 사용한다. 인증·계정·세션은 JDBC를 유지한다.
- 적용한 버전 SQL을 수정하지 않는다. 이후 변경은 V2 이상 새 마이그레이션으로 추가한다.
  비어 있지 않은 미관리 스키마에서 시작이 거부되면 자동 baseline/초기화로 우회하지 않는다.

## 테스트와 빌드

```powershell
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon integrationTest
.\gradlew.bat --no-daemon build
git diff --check
```

- `test`: DB 없이 컨텍스트 배선·Health MVC·신원 입력·운영 초기값을 검증한다. DB 자동설정 제외와
  JdbcTemplate/트랜잭션 관리자 stub은 컨텍스트 배선 테스트에만 적용하며 실행용 설정에서 DB 오류를 숨기지 않는다.
- `integrationTest`: `postgres` 태그 테스트만 실행한다. Testcontainers PostgreSQL 18.6으로
  각 테스트의 새 컨테이너·빈 DB·임의 포트·임의 비밀번호를 사용한다. `.env` 개발 DB를 사용하지 않는다.
  테스트 클래스패스의 `application-local.yaml`은 개발 `.env` import를 제외하면서 기존 local 쿠키 설정만 유지한다.
  JPA/JDBC 혼용 및 실제 인증 회귀는 [008-0C 검증 보고서](../tasks/task-008-0c-jpa-jdbc-validation-report.md)를 따른다.
  최초 적용·V1 업그레이드·재시작 후 이력/사용자 보존, 스키마·제약·실제 HTTP Health,
  계정 중복 경쟁·전체 롤백·소유권/세대·운영 seed를 검증한다.
- `check`와 `build`는 통합 테스트도 포함하므로 Docker가 필요하다.
  Docker가 없으면 통합 테스트는 실패하며 조용히 건너뛰지 않는다.
- 초기 실행은 Gradle 의존성과 PostgreSQL/Ryuk 이미지 다운로드가 필요하다.
  `--offline`은 Gradle 캐시가 준비된 경우에만 사용하며 Docker 이미지 다운로드까지 막지는 않는다.
- 테스트가 만든 일회성 컨테이너·볼륨은 Testcontainers가 정리한다. 개발용 Compose 볼륨과 분리된다.

`local`에서만 HTTP 개발용 세션 쿠키 Secure 예외를 허용한다. 다른 프로필과 production/prod 혼합
프로필은 Secure를 강제한다. 실제 로그인 경로·세션/CSRF와 테스트 fixture의 경계는
[보안·세션 문서](security-session.md)를 따른다.
이메일 OTP의 Resend 실발송은 기본 비활성이다. [키 설정·실발송 승인 조건](email-otp.md)을 따른다.
JDBC 세션은 immediate flush로 저장하며 OTP 성공 응답 전 저장소 확인을 수행한다.
Google OIDC는 별도 [활성화·클라이언트 설정](google-oidc.md)이 필요하며 기존 V1~V4만 사용한다.

Health 계약은 `GET /api/v1/health` → 200 / `{"status":"UP"}`, POST → 405이며
응답은 DB/외부 서비스 상태를 의미하지 않는다.

## 중지 및 재시작

앱 실행 터미널에서 `Ctrl+C`로 서버를 종료한 뒤 DB를 중지한다.

```powershell
docker compose stop postgres
docker compose ps -a
```

재개할 때는 `docker compose up -d --wait --wait-timeout 120 postgres`와 앱 실행 명령을 사용한다.
볼륨은 보존되며 Flyway가 같은 마이그레이션을 중복 적용하지 않는다.
PostgreSQL 18 공식 이미지의 데이터는 `/var/lib/postgresql`에 마운트한 전용 named volume에 저장한다.
컨테이너를 재생성해도 같은 볼륨을 재사용한다. 볼륨 삭제 옵션, prune, 기존 DB 초기화는 수행하지 않는다.
새 메이저 버전으로 이미지 태그만 바꿔 기존 볼륨을 재사용하지 않는다.

## 근거

- [Spring Boot 4.1.2 의존성 관리](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)
- [Spring Boot Flyway 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [PostgreSQL 공식 이미지 · 18 데이터 경로](https://hub.docker.com/_/postgres)
- [Testcontainers PostgreSQL 모듈](https://java.testcontainers.org/modules/databases/postgres/)
