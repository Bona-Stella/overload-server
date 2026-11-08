 <div align="center">
  <h1>🏋️ overload-server</h1>

  <h3>꾸준한 운동을 데이터로, 성장의 과정을 기록으로.</h3>

  <p>
    웨이트 트레이닝 기록 및 관리를 위한<br>
    <strong>Overload</strong> 앱의 백엔드 REST API 서버
  </p>

  <p>
    무게와 반복 횟수부터 운동 루틴, 운동 이력, 성장 지표까지<br>
    체계적으로 관리하는 서비스를 목표로 합니다.
  </p>

  <img src="https://img.shields.io/badge/Java-25%20LTS-ED8B00?style=flat-square&amp;logo=openjdk&amp;logoColor=white" alt="Java">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1.2-6DB33F?style=flat-square&amp;logo=springboot&amp;logoColor=white" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Gradle-9.8.2-02303A?style=flat-square&amp;logo=gradle&amp;logoColor=white" alt="Gradle">
  <img src="https://img.shields.io/badge/PostgreSQL-18.6-4169E1?style=flat-square&amp;logo=postgresql&amp;logoColor=white" alt="PostgreSQL 18.6">
  <img src="https://img.shields.io/badge/Status-Initial_Setup-6B7280?style=flat-square" alt="Status">
</div>

> **개인 개발 DB의 마지막 확인 기록:** 2026-11-02 승인된 `127.0.0.1:5433/overload_dev`의 V15에 V16·V17을 적용하고 개발용 카탈로그·소량의 계정/운동 자료를 등록했습니다. 당시 OTP·루틴·장비·오프라인·종료/복구 확인은 [4단계 보고서](docs/tasks/standard-exercise-catalog-dev-phase-4-report.md)를 따릅니다. [이전 V15 재구성](docs/tasks/task-009-dev-v15-rebuild-report.md)은 그보다 앞선 기록입니다. V18~V22 미적용 표기 역시 마지막 보고 기준이며6A에서는 개인 DB의 현재 상태를 조회하지 않았습니다. 최신 조합 검증과 다른 DB·후속 변경 승인은 별도로 구분합니다.

---

## 📌 프로젝트 소개

**Overload**는 웨이트 트레이닝을 하는 사용자가 자신의 운동을 기록하고, 루틴을 관리하며, 기록의 변화를 통해 **점진적 과부하(Progressive Overload)**를 확인할 수 있도록 돕는 앱입니다.

`overload-server`는 사용자 계정, 운동 종목, 운동 세션, 세트별 기록, 루틴 및 통계 데이터를 관리하고 클라이언트 앱에 REST API를 제공하는 백엔드 프로젝트입니다.

> 현재 이메일 OTP·선택적 Google 인증, 세션·CSRF, 표준 카탈로그·휴식 설정, 루틴·운동 세션·기록 정정/삭제/복구, 통계 overview·성장·주간 일정 API를 구현했습니다. 실발송 기본값은 비활성이며 최신 코드의 실제 브라우저·PWA·실기기 종합 검증은6B에 남아 있습니다. 아래 기능 체크리스트는 초기 개발 목표이고 현행 API는 아래 계약 링크를 따릅니다.

---

## ✨ 주요 기능

### 👤 사용자 관리
- [ ] 회원가입 및 로그인
- [ ] 사용자 정보 조회 및 수정
- [ ] 사용자별 운동 데이터 관리
- [ ] 인증 및 인가

### 🏋️ 운동 기록 관리
- [ ] 운동 종목 등록 및 조회
- [ ] 날짜별 운동 세션 생성
- [ ] 운동 종목별 세트 추가 및 수정
- [ ] 세트별 중량(kg), 반복 횟수(Reps) 기록
- [ ] 운동 시간 및 메모 기록
- [ ] 운동 기록 수정 및 삭제

### 📋 운동 루틴 관리
- [ ] 사용자별 운동 루틴 생성
- [ ] 루틴에 운동 종목 추가
- [ ] 운동 순서 및 목표 세트 설정
- [ ] 저장된 루틴을 이용한 운동 시작
- [ ] 루틴 수정 및 삭제

### 📊 운동 통계 및 성장 분석
- [ ] 일별, 주별, 월별 운동 기록 조회
- [ ] 운동 종목별 중량 변화 추적
- [ ] 총 운동 볼륨(Volume) 분석
- [ ] 운동 빈도 및 수행 횟수 집계
- [ ] 개인 최고 기록(PR) 관리
- [ ] 점진적 과부하 추적

---

## 📈 핵심 운동 지표

| 지표 | 설명 |
|:---|:---|
| Weight | 세트별 운동 중량 (kg) |
| Reps | 세트별 반복 횟수 |
| Sets | 종목별 수행 세트 수 |
| Volume | 중량 × 반복 횟수의 합계 |
| Frequency | 일정 기간 동안의 운동 빈도 |
| Personal Record | 종목별 개인 최고 기록 |
| Progressive Overload | 이전 운동 대비 수행 능력 변화 |

**운동 볼륨 계산 예시**

```text
Exercise: Bench Press

Set 1: 60kg × 10 reps = 600kg
Set 2: 70kg × 8 reps  = 560kg
Set 3: 80kg × 5 reps  = 400kg

Total Volume = 1,560kg
```

> 운동 볼륨과 PR의 세부 계산 기준은 도메인 설계 과정에서 확정할 예정입니다.

---

## 🛠 기술 스택

Java 및 Spring Boot 기반의 REST API 서버를 개발합니다.

### Backend

| Category | Technology | Version |
|:---|:---|:---|
| Language | Java | 25 LTS |
| Framework | Spring Boot | 4.1.2 |
| Framework Core | Spring Framework | 7.x |
| Build Tool | Gradle | 9.8.2 |
| ORM | Spring Data JPA / Hibernate | 설정·루틴·세션·수행 저장, 인증·계정 JDBC 및 통계 조회 유지 |
| Security | Spring Security | 7.1.2, CSRF·OTP·선택적 OIDC 로그인 |
| Authentication | Google OIDC + 이메일 OTP(Resend) / Spring Session JDBC 쿠키 세션 | 기존 LIVE 기록과 최신 조합6B 검증을 구분 |
| Validation | Jakarta Validation | Starter 구성 완료 |

### Database & Infrastructure

| Category | Technology | Version / Status |
|:---|:---|:---|
| RDBMS | PostgreSQL | 18.6 (로컬 개발·테스트) |
| DB Migration | Flyway | 12.4.0 (Boot BOM 관리) |
| Container | Docker | 로컬 PostgreSQL / 테스트 컨테이너 |
| Local Environment | Docker Compose | `compose.yaml` |

### Testing & Documentation

| Category | Technology |
|:---|:---|
| Unit Test | JUnit |
| Integration Test | Spring Boot Test |
| Container Test | Testcontainers 2.0.5 (Boot BOM 관리) |
| API Documentation | OpenAPI / Swagger UI (도입 예정) |

> 라이브러리 버전은 가능한 한 Spring Boot의 의존성 관리 설정(BOM)을 따릅니다. 실제 적용 버전은 호환성 검증 후 확정합니다.

---

## 🧩 도메인 설계

주요 도메인은 사용자, 운동 종목, 운동 세션, 운동 세트, 루틴 및 통계로 구성합니다.

```text
User
 │
 ├── WorkoutSession
 │      │
 │      └── WorkoutExercise
 │                │
 │                ├── Exercise
 │                │
 │                └── WorkoutSet
 │
 └── Routine
        │
        └── RoutineExercise
                  │
                  └── Exercise
```

### 주요 엔티티

| Entity | Description |
|:---|:---|
| User | 사용자 계정 및 프로필 |
| Exercise | 스쿼트, 벤치프레스 등 운동 종목 |
| WorkoutSession | 특정 날짜에 진행한 운동 세션 |
| WorkoutExercise | 운동 세션에 포함된 운동 종목 |
| WorkoutSet | 개별 세트의 중량, 횟수, 순서 |
| Routine | 사용자가 저장한 운동 루틴 |
| RoutineExercise | 루틴에 포함된 운동 종목과 수행 순서 |

> 위 구조는 초기 도메인 설계안으로, 실제 DB 테이블 및 엔티티 관계는 개발 과정에서 변경될 수 있습니다.

---

## 📁 프로젝트 구조

Git 저장소 루트가 Gradle 프로젝트 루트입니다. 현재 구조는 다음과 같습니다.

```text
overload-server/
├── AGENTS.md
├── docs/
│   ├── engineering/backend.md
│   └── reference/bootstrap/backend.gitignore.txt
├── gradle/wrapper/
├── src/
│   ├── main/
│   │   ├── java/com/overload/overload_server/OverloadServerApplication.java
│   │   └── resources/ (application.yaml, application-local.yaml, db/migration/)
│   └── test/java/com/overload/overload_server/OverloadServerApplicationTests.java
├── .gitattributes
├── .gitignore
├── .env.example
├── compose.yaml
├── build.gradle
├── settings.gradle
├── gradlew
├── gradlew.bat
└── README.md
```

기존 하위 `overload-server/`에는 로컬 캐시·빌드 결과·IDE 설정·`HELP.md`를 보존했습니다. 실행 명령은 하위 폴더가 아닌 저장소 루트에서 사용합니다. 해당 로컬 파일은 별도 정리 대상입니다.

### 향후 도메인 패키지 역할

- `global` : 공통 설정, 예외 처리, 응답 규약
- `auth` : 사용자 인증 및 인가
- `user` : 사용자 계정 및 프로필 관리
- `exercise` : 운동 종목 데이터 관리
- `workout` : 운동 세션 및 세트 기록 관리
- `routine` : 운동 루틴 생성 및 관리
- `statistics` : 운동 기록 집계 및 성장 지표 분석

---

## 🔗 API 설계

RESTful API를 기반으로 클라이언트와 통신합니다.

Base URL:

```text
/api/v1
```

### 현재 주요 API

| Method | Endpoint | Description |
|:---|:---|:---|
| GET | `/api/v1/standard-exercises/search-data`, `/api/v1/manufacturers` | 표준 카탈로그·제조사 |
| GET / PATCH | `/api/v1/workout-settings` | 사용자별 휴식 설정 |
| GET / POST | `/api/v1/routines` | 루틴 목록·생성 |
| GET / PUT / DELETE | `/api/v1/routines/{id}` | 루틴 상세·수정·논리 삭제 |
| POST | `/api/v1/workout-sessions` | 운동 시작 |
| GET | `/api/v1/workout-sessions/active`, `/api/v1/workout-sessions/closed`, `/api/v1/workout-sessions/{id}` | 진행·종료 목록·상세 |
| GET | `/api/v1/statistics/overview` | 선택 기간 통계·계획 근거 |
| GET | `/api/v1/statistics/growth/exercises` | 성장 종목 검색 |
| GET | `/api/v1/statistics/growth/exercises/{standardId}` | 루틴 배치 선택 |
| GET | `/api/v1/statistics/growth/exercises/{standardId}/placements/{placementId}` | 승인 중량 이력 |
| GET / PUT | `/api/v1/weekly-schedule` | 주간 일정 조회·저장 |
| GET | `/api/v1/weekly-schedule/history` | 일정 이력 |

요청·응답과 query·인증·CSRF·오류 조건은 [표준 카탈로그](docs/api/standard-exercise-catalog.md), [휴식 설정](docs/api/workout-online-write.md), [루틴](docs/api/routine-management.md), [세션·기록·평가](docs/api/workout-sessions.md), [통계](docs/api/statistics.md), [주간 일정](docs/api/weekly-schedule.md)을 따릅니다. 개인 운동 GET과 구 `/api/v1/statistics`는 제거됐고 개인 운동 POST/PATCH는 보안 차단만 유지합니다. 초기 `/workouts`·통계 volume/frequency/progress/records 예시는 현재 endpoint가 아닙니다.

---

## 🚀 로컬 실행

### Requirements

- JDK 25
- Gradle Wrapper
- 실행 중인 Docker Desktop Linux 엔진 / Docker Compose

### Clone Repository

```bash
git clone https://github.com/Bona-Stella/overload-server.git
cd overload-server
```

이후 명령은 `build.gradle`과 Gradle Wrapper가 있는 저장소 루트에서 실행합니다.

### Run Application

먼저 [로컬 PostgreSQL 실행 문서](docs/engineering/local-postgresql.md)에 따라
`.env`에 비밀값을 준비하고 `docker compose up -d --wait --wait-timeout 120 postgres`를 실행합니다.
기존 5432 DB와 분리된 `127.0.0.1:5433` 및 전용 영속 볼륨을 사용합니다.

**macOS / Linux**

```bash
./gradlew --no-daemon bootRun --args='--spring.profiles.active=local'
```

**Windows**

```powershell
.\gradlew.bat --no-daemon bootRun --args='--spring.profiles.active=local'
```

Windows에서 Java가 PATH에 없다면, 설치된 JDK 25 경로를 현재 PowerShell 프로세스에만 지정한 뒤 실행합니다. 아래 경로는 실제 설치 위치로 바꿉니다. 영구 환경변수를 변경할 필요는 없습니다.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
.\gradlew.bat --version
```

`local` 프로필은 저장소 루트 `.env`로 PostgreSQL에 연결하고 Flyway를 적용합니다.
다른 프로필은 표준 `SPRING_DATASOURCE_URL`·`SPRING_DATASOURCE_USERNAME`·`SPRING_DATASOURCE_PASSWORD`로 설정합니다.
DB 없이 실행하는 앱 구성은 아닙니다. JPA는 기존 운동 종목·설정에 적용하며 Hibernate는 Flyway 스키마를 검증만 합니다.
인증·계정·Spring Session JDBC는 유지합니다. 기본 HTTP 포트는 `8080`입니다.
인증·계정 API, 운동 종목·휴식 설정 GET, 일반 온라인 종목 생성·이름 변경·휴식 설정 변경을 제공합니다. 프론트엔드 조회 연동은 별도 작업입니다.

### Health Check API (구현 완료)

`GET /api/v1/health`는 `200 OK`와 `Content-Type: application/json`으로 다음 응답을 반환합니다.

```json
{"status":"UP"}
```

애플리케이션의 HTTP 요청 처리 가능 여부만 확인하며, 데이터베이스 및 외부 서비스의 정상 동작을 보장하지 않습니다. `POST /api/v1/health`는 `405 Method Not Allowed`를 반환합니다.

로컬 서버를 실행한 상태에서 다른 터미널로 확인합니다.

```powershell
curl.exe -i http://localhost:8080/api/v1/health
curl.exe -i -X POST http://localhost:8080/api/v1/health
```

검증 후 서버 실행 터미널에서 `Ctrl+C`로 종료하고 `docker compose stop postgres`로 DB를 중지합니다.
볼륨은 보존됩니다. 재시작·접속·포트 충돌·비밀값 관리 방법은 [로컬 PostgreSQL 문서](docs/engineering/local-postgresql.md)를 참고합니다.

### 테스트 및 빌드

```powershell
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon integrationTest
.\gradlew.bat --no-daemon build
```

`integrationTest`와 이를 포함하는 `check`·`build`에는 Docker가 필요합니다.
초기 실행은 Gradle 배포판·의존성·컨테이너 이미지 다운로드가 필요합니다.
`--offline`은 Gradle 캐시가 준비된 경우에만 사용합니다.

`test`는 DB 없이 컨텍스트 배선·Health MVC 계약, 신원 입력 계약과 운영 초기값·휴식 경계를 검증합니다.
`integrationTest`는 일회성 PostgreSQL에서 V1→V10 마이그레이션·재시작·Health,
계정 원자적 생성·동시 요청·전체 롤백·소유권/세대 제약을 검증합니다.
V3 세션 테이블·실제 HTTP/HTTPS 쿠키·CSRF·90일 절대 만료·10분 재인증 경계도 검증합니다.
보안 응답과 프론트의 토큰 조회·갱신 계약은 [보안·세션 문서](docs/engineering/security-session.md)를 참고합니다.
이메일 OTP 상태·제한·실패 복구·Resend 설정과 실발송 승인 조건은 [이메일 OTP 문서](docs/engineering/email-otp.md)를 따릅니다.
Google 클라이언트 준비·정확한 localhost 콜백·활성화·세션 상태 조회는 [Google OIDC 문서](docs/engineering/google-oidc.md)를 따릅니다.
휴식 기본값과 신원/트랜잭션 계약, 개인 운동 seed 중단 상태는
[계정 프로비저닝 문서](docs/engineering/account-provisioning.md)를 참고합니다.

현재 운동 쓰기·진행 세션·복구 기준은 [Task 009 v1.0](docs/tasks/task-009-redesign-v1.0.md)입니다.
1차 구조 정리에서 구 명령 Controller·Service·Store·Codec·stamp·영수증/feed 런타임과 전용 테스트를 제거했습니다.
휴식 설정 변경은 일반 Controller·Service·DTO와 기존 JPA 트랜잭션을 사용합니다. 개인 운동 생성·이름 변경 구현과 GET은 제거됐으며 [온라인 계약](docs/api/workout-online-write.md)의 차단 정책만 유지합니다. 웨이트·유산소 루틴 CRUD·배치 논리 제거/명시 복원·명시적 중량 승인은 [루틴 관리 계약](docs/api/routine-management.md)을 따릅니다. V11 루틴 스키마의 개인 DB 적용은 아래 V15·V17 적용 당시 기록과 구분합니다.
세션 [START·조회·takeover·snapshot·승인 해결·FINISH·자동 종료 및 종료 후 정정·복구](docs/api/workout-sessions.md)는 V12~V15를 사용합니다. InitialPlan은 불변 JSONB 하나에 보존하고 ACTIVE는 세대와 무관하게 사용자당 하나입니다. 수행은 관계형 저장하며 승인 충돌이 유효 수행 저장을 막지 않습니다. 명시 종료는 최초 평가·AppliedPlan을 봉인하고 AUTO_CLOSED는 마지막 서버 기록만 보존합니다. 종료 후 정정·명시 복구·논리 삭제와 현재 평가는 봉인 사실과 분리하며, 확인 불가 복구 승인 주장은 UNVERIFIED로 보존합니다.
**V12~V15 격리 검증 후, 2026-11-01 개인 개발 DB의 승인된 V15 신규 구성도 완료했습니다.** [재구성 보고서](docs/tasks/task-009-dev-v15-rebuild-report.md)를 따릅니다. 앱 시작 시 대기 migration이 자동 적용되므로 다른 DB·후속 파괴적 작업에는 별도 승인이 필요합니다.
현재는 V17 표준 UUID·장비 계약과 Frontend/IndexedDB3·설치형 PWA를 연결했고 [실제 개발 환경 검증](docs/tasks/standard-exercise-catalog-dev-phase-4-report.md)도 완료했습니다. 조회·takeover·중복·무변경 snapshot·승인 해결은 운동 동기화 시각을 연장하지 않습니다. 기존 인증·휴식 기본값은 유지하며 개인 운동 가입 seed는 중단했습니다.

통계·추적/디로딩의 [0·1단계 보고서](docs/tasks/statistics-deload-phase-0-1-report.md)는 당시 기록입니다. 통계 대시보드와2B~2E는 이후 구현됐으며 디로딩 도메인 연결과 정확한 운영 카탈로그 분류는 미완료입니다. V18~V22는 코드·격리 검증과 개인 DB 적용을 구분합니다. 개인 DB의 마지막 기록은V17 적용/V18~V22 미적용이며6A에서는 접속·재확인하지 않았습니다. 새 앱을 해당 DB에 연결하면 대기 migration이 실행될 수 있습니다.

[통계·추적 2E](docs/tasks/statistics-tracking-phase-2e-report.md)에서 맨몸 운동의 명시적 추가 중량 기록·정정·오프라인 보존과 실제 반복 기반 총중량 계산을 연결했습니다. 기존 숫자 중량이나 체중을 추가 부하로 해석하지 않고, 근거가 없으면 세트·반복만 표시합니다. 승인 평가와 근육 다중 매핑/대분류 중복 제거를 유지합니다. [실제 DB/API 계약](docs/api/bodyweight-additional-load.md)을 따르며 개인 DB V18~V22는 미적용입니다. 운영 분류·디로딩3단계·실기기 검증은 남아 있습니다.

[통계·추적 2D](docs/tasks/statistics-tracking-phase-2d-report.md)에서 실제 유산소 속도·활동 시간, 직접 입력 우선/계산 거리와 출처, 정정·오프라인 기록 및 종류별 누적 거리를 연결했습니다. 목표 속도로 실제값을 만들지 않으며 기존 기록·고정 요청을 보존합니다. V21과 개인 DB V18~V20은 미적용입니다. 실제 계약은 [유산소 측정](docs/api/cardio-actual-measurements.md)을 따르며 디로딩 구분·혼재 연결은3단계입니다.

[통계·추적 2C](docs/tasks/statistics-tracking-phase-2c-report.md)에서 기존 승인 사건의 기간별(1/3/6개월·전체) 검색·루틴 배치 선택·승인 중량 변화 그래프를 연결했습니다. 다른 배치를 하나의 성장선으로 합치지 않으며 기록 없는 기간의 값을 만들지 않습니다. 실제 API·보존/디로딩 한계는 [조회 계약](docs/api/approved-weight-growth.md)을 따릅니다. 개인 DB V18~V20 적용·운영 카탈로그 검증·디로딩3단계 연결은 이번 완료 범위가 아닙니다.

[통계·추적 2A](docs/tasks/statistics-tracking-phase-2a-report.md)에서 기존 기록을 사용하는 [읽기 집계 API](docs/api/statistics.md)와 실제 계정의 주/월 운동량·근육별 상세·기존 이력 연결을 추가했습니다. 2A 당시 미완료였던 주간 전체 계획/달성률은 아래 2B에서 구현했습니다. 승인 중량 성장선은2C에서 구현했으며 유산소 계산 거리 저장은2D에서 구현했고 디로딩 연결은 후속이며 2단계 전체 완료가 아닙니다. 개인 DB V18 미적용·운영 카탈로그 분류 검증 미완료 상태는 유지합니다.

[2B](docs/tasks/statistics-tracking-phase-2b-report.md)는 당시 최초 등록 즉시·기존 변경 다음 월요일 방식의 역사 기록입니다. 현재 일반 일정은 [G2~G3 계약](docs/api/weekly-schedule.md)에 따라 변경도 즉시 적용하며 과거 근거를 보존합니다. 2B에서는 주간 일정·적용 이력과 미수행 예정일을 포함한 주간 달성률을 추가했습니다. 시작 계획과 실제 완료를 비교하며 추가 운동·다른 세션의 부분 기록을 합쳐 계획을 채우지 않습니다. 과거 근거가 없거나 유산소 목표를 확인할 수 없으면 수치를 추정하지 않습니다. V19·V20은 격리 검증용으로만 사용했고 개인 DB V18·V19·V20은 미적용입니다. 첫 주는 등록일~일요일의 예정일만 분모에 포함합니다. 실제 적용은 별도 승인 범위입니다.
V1~V9 Flyway 이력은 변경하지 않았습니다. 정식 V10은 폐기 stamp·receipt/feed 구조만 제거하며 기존 데이터·version/deleted_at을 보존합니다.
V10 전체 회귀·실제 V8 백업의 격리 복원/업그레이드·승인된 개인 로컬 DB V8→V9→V10 적용 결과는 [V10 최종 보고서](docs/tasks/task-009-v10-local-application-report.md)를 참고하세요.
삭제 목록·DB 의존성과 검증 결과는 [1차 구조 정리 보고서](docs/tasks/task-009-phase-1-cleanup-report.md)를 참고하세요.

---

## 🗺 개발 로드맵

### Phase 1. 프로젝트 기반 구축
- [x] Spring Boot 프로젝트 생성
- [x] Gradle 및 MVC·Validation 의존성 구성
- [x] 로컬 PostgreSQL·Flyway 연동 (최소 users 스키마)
- [ ] 공통 예외 처리 및 응답 규약 설계
- [x] Spring Boot 컨텍스트 로딩 테스트 구성

### Phase 2. 운동 기록 MVP
- [ ] 운동 종목 도메인 구현
- [ ] 운동 세션 CRUD
- [ ] 운동 종목 및 세트 기록 CRUD
- [ ] 운동 이력 조회

### Phase 3. 사용자 및 루틴 관리
- [ ] 사용자 인증 및 인가
- [ ] 사용자별 운동 기록 분리
- [ ] 운동 루틴 CRUD
- [ ] 저장된 루틴 기반 운동 기록 생성

### Phase 4. 운동 통계 및 서비스 개선
- [ ] 운동 볼륨 통계
- [ ] 운동 빈도 분석
- [ ] 개인 최고 기록 및 성장 추적
- [ ] API 문서화
- [ ] 성능 최적화 및 보안 개선

---

## 📚 참고 문서

- [Spring Boot 공식 문서](https://docs.spring.io/spring-boot/index.html)
- [Spring Framework 공식 문서](https://docs.spring.io/spring-framework/reference/)
- [Java 공식 문서](https://docs.oracle.com/en/java/)
- [Gradle 공식 문서](https://docs.gradle.org/)
- [PostgreSQL 공식 문서](https://www.postgresql.org/docs/)

---

<div align="center">
  <strong>Overload — Track your lifts. Own your progress. 💪</strong>
</div>

### 2026-11-07 디로딩 현행 문서

정책은 [v1.7](../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md), 구현 근거와 잔여 작업은 [Backend 현황 분석](../overload-pwa/docs/tasks/deload-v17-backend-readiness-analysis.md)을 따른다. G0~G3 기반은 구현 확인, [G4-1 설정/epoch API](docs/api/deload-analysis.md)와 V25 스키마는 구현했으며 [G4-2 공통 InitialPlan 판정/정확분수](docs/tasks/deload-g4-2-report.md)와 [G4-3 독립 후보/E/N](docs/tasks/deload-g4-3-report.md) 내부 계산을 구현했고 [G4-4 월04시 확정/RESET·ADD·내부 누적](docs/tasks/deload-g4-4-report.md)도 구현했으며 [G4-5 과거 정정 재계산](docs/tasks/deload-g4-5-report.md)과 승인된 V26 EXCLUDED 상태를 구현했다. 가장 이른 변경 주부터 같은 epoch의 후속 주를 재평가하며 실제 DB에는 V25/V26을 적용하지 않았다. [G4-6 읽기 전용 조회](docs/tasks/deload-g4-6-report.md)로 확정 prefix·보류/재계산·정확8주 추천을 연결했다. [G5-1 기간 시작/최초 임시 계획](docs/api/deload-periods.md)·V27을 구현했다. [G5-2 운동 실행/임시 계획 관리](docs/tasks/deload-g5-2-report.md)·V28을 구현했다. [G5-3 기간 완료·취소·자동 종료·분석 재개](docs/tasks/deload-g5-3-report.md)와 V29를 구현했다. 실제 DB 적용과 G6 PWA 연동은 후속이다. 기존 환경에는 V25 이후를 적용하지 않았다. V25/명시적 T_adopt 이행은 기존 검토·개인 DB에 실행하지 않았다. 신규 계정은 기본ON·누적0의 초기 epoch로 확정됐고 OFF→ON은 새 epoch다. 기존 미설정 계정은 비소급 이행 경계를 사용하며 HTML 초기ON은 실제 서버 상태의 근거가 아니다. 자격은4조건, 기간 중복 생성 차단은 별도 상태 제약이다. 일반 G3는 분석OFF와 독립이다. 기존 격리 검토 DB의 V24 확인을 개인/운영DB 적용으로 확대하지 않는다.
