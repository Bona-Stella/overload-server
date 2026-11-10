# Overload Backend Engineering Rules

맨몸 추가 부하2E는 [DB/API 계약](../api/bodyweight-additional-load.md)을 따른다. V22의 nullable 객체만 추가하고 기존 weight를 추가 중량으로 해석/백필하지 않는다. 통계만 명시적 부하×실제 반복을 사용하며 승인 평가를 변경하지 않는다. 생략 필드·구JSON/hash를 보존하고 개인 DB V18~V22는 적용하지 않는다.

유산소 실제 측정의 현행 확장은 [2D DB/API 계약](../api/cardio-actual-measurements.md)을 따른다. V21은 nullable measurement 한 컬럼이며 과거 출처/속도 백필과 기존 JSON/hash 변경은 없다. 개인 DB V18~V21은 적용하지 않았으며 새 코드로 실제 DB 대상 앱을 실행하지 않는다.

> Backend Development Guidelines for AI Coding Agents
>
> Repository: `overload-server`
>
> Stack: Java 25 / Spring Boot 4 / PostgreSQL
>
> Status: Initial Engineering Guidelines

---

## 1. 목적 및 적용 범위

이 문서는 Overload 백엔드의 기술별 개발 규칙을 정의합니다.

AI 코딩 에이전트와 개발자는 백엔드 코드의 생성, 수정,
리팩터링, 테스트 및 데이터베이스 설계 시 본 규칙을 준수합니다.

공통 작업 원칙은 저장소 루트 [AGENTS.md](../../AGENTS.md)를 따릅니다.
V1 출시 전 설계 우선·호환성·단순화·리팩터링·테스트 기준은 해당 문서 4절과 13절을 참조합니다.
아래 과거 단계의 구현·보존 기록은 영구적인 구조 유지 의무가 아니며, 제품 정책과 데이터 보호 조건을 구분합니다.

Task 007의 확정 정책과 구현 단계는 [Task 006 최종 설계 v0.3.1](../tasks/task-006-integrated-design-v0.3.1.md)을 따릅니다.
운동 쓰기·진행 세션·복구는 [Task 009 v1.0](../tasks/task-009-redesign-v1.0.md)이 우선합니다.
Task 006의 나머지 운동·인증 정책과 Task 008의 휴식 설정 조회 계약은 유지합니다. 개인 운동 GET과 전용 Entity·Repository는 3단계 승인 정리에서 제거했으며 DB·Flyway 이력은 보존합니다.
현재 온라인 휴식 설정 변경과 개인 운동 POST/PATCH 차단·내부 구현 제거 상태는 [온라인 쓰기 API 계약](../api/workout-online-write.md)을 따릅니다.
웨이트·유산소 루틴 관리 및 명시적 승인 중량 변경은 [루틴 온라인 계약](../api/routine-management.md)을 따릅니다.
세션 START·조회·takeover·snapshot·승인 해결·FINISH·최초 평가·AUTO_CLOSED와 종료 후 정정/명시 복구/논리 삭제/현재 평가/승인 해결은 [세션 계약](../api/workout-sessions.md)을 따릅니다. 개인 개발 DB는 2026-11-01 사용자 명시 승인으로 테스트 데이터를 폐기하고 V1~V15를 신규 적용했습니다. [재구성 보고서](../tasks/task-009-dev-v15-rebuild-report.md)를 확인하며 다른 DB·후속 파괴적 작업은 별도 승인합니다.
개인 개발 DB의 2026-11-02 승인 적용과 당시 Frontend/IndexedDB3·PWA 검증은 [V16·V17 적용 기록](../tasks/standard-exercise-catalog-dev-phase-4-report.md)에 있습니다. 최신 확인 범위는 [1.1절](#11-현재-개발-현황)을 참고합니다. 만료 요청은 자동 종료를 커밋한 뒤 오류로 변환하며, 트랜잭션 안의 예외로 정상 만료를 롤백하지 않습니다. 종료 후 편집은 expectedVersion을 사용하고 확인 불가 복구 문맥은 UNVERIFIED로 보존합니다.

본 문서는 다음 항목에 집중합니다.

- Java 및 Spring Boot 코드 작성 규칙
- 애플리케이션 아키텍처
- 도메인 모델링
- REST API 설계
- JPA 및 트랜잭션 관리
- PostgreSQL 데이터베이스 설계
- 인증 및 인가
- 예외 처리 및 검증
- 테스트 및 품질 검증
- AI 에이전트의 백엔드 작업 기준

### 규칙의 범위

- 공통 개발 규칙을 중복 정의하지 않습니다.
- 기술별 구체적인 구현 원칙을 우선적으로 설명합니다.
- 기존 코드와 다르면 최신 확정 설계를 우선하며, 제품 정책·보안·데이터 무결성 충돌은 `AGENTS.md` 4.1절에 따라 보고합니다.
- 구현되지 않은 기능이나 설정을 존재한다고 가정하지 않습니다.
- 확정되지 않은 설계 결정은 임의로 확정하지 않습니다.

### 1.1 현재 개발 현황

**마지막 확인** 2026-11-10 (KST). HEAD `5b5b136`을 이전 검증 작업 트리와 대조했다. `ee68258`은 V31 이름 저장 구현이고 `5b5b136`은 운동 세션 테스트 시계를 안정화했다.

| 범위 | 상태와 근거 |
| --- | --- |
| 다중 주간 프리셋과 자동 순환 | **완료.** V30 저장·신규 계정 기본값·관리·수동 활성화·순환·누락 주차 복구와 V31 이름 저장을 구현했다. [API](../api/weekly-schedule.md) · [격리 회귀](#125-다중-주간-일정-프리셋) |
| G3 일별 검사·일반 통계·G4 분석 | **완료.** 적용 일정과 최초 근거, 통계·성장 조회, 분석 설정·epoch·주별 확정·재계산을 구현했다. [통계 API](../api/statistics.md) · [성장 API](../api/approved-weight-growth.md) · [G3·G4 구현](#124-ui-v09와-디로딩-v17-연동) |
| G5 기간·세션·프리셋 연동 | **완료.** 기간 시작·임시 계획·세션·종료, 분석 재개와 일반 프리셋 순환 정지·복귀를 구현했다. [디로딩 기간 API](../api/deload-periods.md) · [G5 연동](#124-ui-v09와-디로딩-v17-연동) |
| V31 목표 스키마 | **격리 검증 완료.** PostgreSQL 18.6의 빈 DB V1~V31 적용과 V30→V31 전환에서 기존 행 보존을 확인했다. [DB 설계서](../database/schema.md) · [ERD](../database/erd.md) |
| 실제 개발·보존 검토 DB 전환 | **보류.** 개인 개발 DB의 적용 버전은 미확인이다. HOME·ROUTINE 검증은 일회용 DB에서 수행했고 개인 개발·보존 DB에는 V31을 적용하지 않았다. 보존 DB의 마지막 직접 확인은 V24다. 기존 계정의 `T_adopt`와 프리셋 상태, 승인된 DB·백업·복원·전환 계획은 별도로 확인한다. [DB 적용 경계](#124-ui-v09와-디로딩-v17-연동) |
| HOME·ROUTINE PWA | **연동·PC 검토 완료.** Frontend `e34e433`에서 V30·V31 계약을 사용한다. 최신 iPhone 실기기·설치형 PWA·HTTPS START, G6 전체 통합, 실제 DB 적용·배포는 별도다. [Frontend 현황](../../../overload-pwa/docs/engineering/frontend.md#87-v09-적용-범위와-구현-상태) |

**마지막 회귀 검증.** 단위 439건, 격리 PostgreSQL 통합 844건, 패키징과 `git diff --check` 통과. 실패·건너뜀은 0건이다. [시계 안정화 기록](#운동-세션-통합-테스트-시계-안정화)

HOME·ROUTINE 연동과 검토 환경 종료를 마쳤다. G6에서는 분석·기간·세션 응답과 혼재 세션 보호를 함께 검증한다. [PWA 연동 지침](../../../overload-pwa/docs/engineering/backend-ui-v0.9.md) · [검토 종료 기록](test-environment.md#home-routine-검토-종료-기록)

#### 현황 갱신

**공통 상태 표기.** 완료는 구현과 해당 단계 검증을 마친 상태, 진행 중은 구현을 시작한 상태, 예정은 구현 전, 검증 대기는 구현 후 필수 확인이 남은 상태, 보류는 선행 작업이나 결정이 필요한 상태다.

**공통 갱신 기준.** 기능·단계를 마감하거나 API·스키마·주요 결정·테스트 환경·선행조건이 바뀌면, 작업 담당자가 자기 저장소의 개발 현황에서 해당 항목을 갱신한다. 확인 날짜, 검증 기준 커밋, 구현한 범위와 남은 범위, 실제 실행한 검사와 결과 및 미검증 범위, 다음 작업·위험·선행조건, 변경된 현행 계약 링크를 기록한다. 커밋 전 검증은 당시 HEAD와 검증한 작업 트리 범위를 함께 적고, 검증한 코드 커밋과 문서 커밋을 구분한다. 이번 작업 커밋은 생성 후 최종 보고에 적는다. 실제 DB 적용·사용자 승인·실기기 결과와 격리 검증은 각각 기록한다.

---

## 2. 기술 스택 및 버전 관리

### 2.1 기본 기술 스택

| Category | Technology |
|:---|:---|
| Language | Java 25 LTS |
| Framework | Spring Boot 4.1.x |
| Framework Core | Spring Framework 7.x |
| Build Tool | Gradle 9.x |
| Database | PostgreSQL 18.x |
| Persistence | 운동 도메인: Spring Data JPA / Hibernate (008-0C V2 매핑), 인증·계정: JDBC 유지 |
| Validation | Jakarta Validation |
| Security | Spring Security (007-1C-1 기반 도입, 실제 로그인 미구현) |
| DB Migration | Flyway (007-1A 도입, Boot BOM 관리) |
| Testing | JUnit / Spring Boot Test |
| Integration Testing | Testcontainers PostgreSQL (007-1A 도입, Boot BOM 관리) |
| API Documentation | OpenAPI (도입 예정) |

### 2.2 버전 관리 원칙

- Java 25 LTS를 프로젝트의 기본 Java 버전으로 사용합니다.
- 실제 프로젝트의 Java Toolchain 설정을 기준으로 판단합니다.
- Gradle Wrapper를 사용합니다.
- 가능한 경우 Spring Boot의 의존성 관리 기능을 활용합니다.
- 라이브러리 버전을 불필요하게 개별 지정하지 않습니다.
- 신규 의존성 도입 전 기존 의존성으로 해결 가능한지 검토합니다.
- 라이브러리 업데이트 시 호환성과 빌드 결과를 확인합니다.
- 최신 버전이라는 이유만으로 무조건 업데이트하지 않습니다.

### 2.3 Spring Boot 4 호환성

Spring Boot 4 및 Spring Framework 7을 기준으로 구현합니다.

특히 다음 사항을 확인합니다.

- Spring Boot 4의 모듈화된 Starter 구조
- Jakarta 기반 라이브러리 사용
- Spring Security 호환성
- Hibernate 및 Spring Data JPA 버전
- 테스트 관련 의존성 구성
- 외부 라이브러리의 Spring Boot 4 지원 여부

Spring MVC를 사용하는 경우
`spring-boot-starter-webmvc`를 우선 검토합니다.

과거 Spring Boot 2 또는 3 기준 코드를
검증 없이 복사하여 사용하지 않습니다.

---

## 3. 프로젝트 아키텍처

### 3.1 기본 아키텍처

Overload 백엔드는 도메인 중심 패키지 구조를 사용합니다.

각 도메인 내부에서는 책임에 따라 계층을 분리합니다.

기본 요청 처리 흐름:

```text
HTTP Request
     |
     v
Controller
     |
     v
Service
     |
     v
Repository
     |
     v
Database
```

계층별 책임:

| Layer | Responsibility |
|:---|:---|
| Controller | HTTP 요청 및 응답 처리 |
| Service | 비즈니스 로직 및 트랜잭션 |
| Repository | 데이터베이스 접근 |
| Entity | 영속성 모델 및 도메인 상태 |
| DTO | 계층 외부와의 데이터 전달 |

### 3.2 권장 디렉터리 구조

```text
src/main/java/com/overload/server/
|
├── OverloadServerApplication.java
|
├── global/
│   ├── config/
│   ├── exception/
│   └── response/
|
├── auth/
├── user/
├── exercise/
├── workout/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── entity/
│   └── dto/
├── routine/
└── statistics/
```

`com.overload.server`는 권장 패키지 예시입니다.

실제 프로젝트의 패키지명이 다를 경우
기존 패키지 구조를 우선합니다.

작은 도메인에서는 불필요한 하위 패키지를
미리 생성하지 않아도 됩니다.

### 3.3 의존성 방향

다음 규칙을 따릅니다.

- Controller는 Service를 호출합니다.
- Service는 Repository를 통해 데이터를 조회 및 변경합니다.
- Repository는 영속성 관련 책임만 수행합니다.
- Controller에서 Repository를 직접 호출하지 않습니다.
- Repository에 비즈니스 정책을 구현하지 않습니다.
- Entity가 Controller나 HTTP 계층에 의존하지 않도록 합니다.
- 도메인 간 직접 의존은 필요한 경우에만 허용합니다.
- 순환 의존성을 만들지 않습니다.

### 3.4 구조 단순화

공통 판단 기준은 `AGENTS.md` 4.3~4.4절을 따릅니다.
계층별 책임은 유지하되 형식적인 Service 인터페이스·추상 Repository·중간 위임 계층은 필요할 때만 둡니다.

---

## 4. Java 코드 작성 규칙

### 4.1 Java 기본 원칙

- Java 25의 안정적인 표준 기능을 활용합니다.
- Preview 기능은 명시적인 승인 없이 사용하지 않습니다.
- 명확한 타입과 이름을 사용합니다.
- 불필요한 형변환을 피합니다.
- null 가능성을 명확하게 처리합니다.
- 변경 가능한 상태를 최소화합니다.
- 부작용이 없는 로직은 순수 함수로 구현하는 것을 우선합니다.

### 4.2 네이밍 컨벤션

| Element | Convention | Example |
|:---|:---|:---|
| Class | PascalCase | `WorkoutService` |
| Interface | PascalCase | `WorkoutRepository` |
| Method | camelCase | `createWorkout()` |
| Variable | camelCase | `workoutDate` |
| Constant | UPPER_SNAKE_CASE | `MAX_PAGE_SIZE` |
| Package | lowercase | `workout.service` |
| DTO | 역할 기반 | `CreateWorkoutRequest` |

### 4.3 의존성 주입

- 생성자 주입을 기본으로 사용합니다.
- 필드 주입을 사용하지 않습니다.
- 순환 참조를 허용하지 않습니다.
- 의존성이 과도하게 많아지면 클래스의 책임을 재검토합니다.
- 테스트를 위해 불필요한 Setter를 생성하지 않습니다.

### 4.4 DTO 설계

요청 및 응답 DTO는 명확하게 구분합니다.

권장 네이밍:

```text
CreateWorkoutRequest
UpdateWorkoutRequest
WorkoutResponse
WorkoutDetailResponse
WorkoutSetResponse
```

- 요청 DTO와 응답 DTO를 분리합니다.
- JPA Entity를 API 요청 및 응답으로 직접 노출하지 않습니다.
- 단순한 데이터 전달 객체는 Java record 사용을 우선 검토합니다.
- 외부 API에서 필요한 필드만 노출합니다.
- DTO에 비즈니스 로직을 과도하게 포함하지 않습니다.
- API DTO와 영속성 모델의 변경 영향을 분리합니다.

### 4.5 Null 및 Optional

- 반환값의 부재를 표현할 때 `Optional`을 적절히 사용합니다.
- Entity 필드나 DTO 필드에 Optional을 무분별하게 사용하지 않습니다.
- null이 허용되는 필드와 필수 필드를 명확하게 구분합니다.
- `Optional.get()`의 무조건적인 사용을 피합니다.
- 불필요한 null 반환 및 중복 null 검사를 줄입니다.

### 4.6 Lombok

Lombok은 필수 의존성으로 간주하지 않습니다.

도입하는 경우 다음 규칙을 적용합니다.

- JPA Entity에 `@Data`를 무분별하게 사용하지 않습니다.
- Entity의 equals/hashCode 및 toString에 관계 필드가 포함되지 않도록 주의합니다.
- 생성자와 필드 변경 가능성이 명확하게 드러나도록 합니다.
- Lombok 사용 여부는 프로젝트 내에서 일관되게 유지합니다.

---

## 5. Spring Boot 계층별 규칙

### 5.1 Controller

Controller는 HTTP 계층을 담당합니다.

주요 책임:

- HTTP 요청 수신
- 요청 DTO 바인딩
- 입력 형식 검증
- 인증된 사용자 정보 전달
- Service 호출
- HTTP 상태 및 응답 DTO 반환

Controller에서 하지 말아야 할 작업:

- 복잡한 비즈니스 로직 처리
- Repository 직접 호출
- JPA Entity 직접 수정
- 트랜잭션 관리 로직 구현
- 통계 및 운동 볼륨 계산
- 데이터베이스 관련 세부 처리

### 5.2 Service

Service는 애플리케이션의 작업 단위와
비즈니스 규칙을 처리합니다.

주요 책임:

- 비즈니스 요구사항 구현
- 도메인 규칙 적용
- 트랜잭션 경계 정의
- 사용자별 데이터 접근 검증
- 데이터 생성 및 수정 흐름 제어
- 필요에 따른 도메인 간 작업 조정

비즈니스 규칙이 특정 Entity의 상태와
직접 연관되는 경우 Entity 내부 구현도 검토합니다.

Service가 단순한 Repository 호출만 반복한다면
불필요한 계층이나 메서드가 없는지 검토합니다.

### 5.3 Repository

Repository는 데이터 접근 책임을 담당합니다.

규칙:

- Spring Data JPA의 기본 Repository 기능을 우선 검토하되, 기존 JDBC가 더 명확하고 효율적이면 유지할 수 있습니다.
  JDBC를 JPA로 바꾸는 것 자체를 목표로 삼지 않습니다.
- 인증·계정 및 007-1B 내부 프로비저닝은 기존 JDBC를 유지합니다.
  신원·운영 seed·트랜잭션 계약은 [계정 프로비저닝 문서](account-provisioning.md)를 따릅니다.
- 008-0C는 기존 개인 종목·운동 설정 V2 매핑과 운동 전용 JPA 관리자를 추가합니다.
  기본 JDBC 관리자와 Spring Session은 유지하며 [혼용 검증 보고서](../tasks/task-008-0c-jpa-jdbc-validation-report.md)를 따릅니다.
- 단순 CRUD는 제공되는 Repository 기능을 우선 사용합니다.
- 복잡한 조회가 필요할 때 명시적인 Query를 검토합니다.
- N+1 문제가 발생하지 않도록 조회 패턴을 확인합니다.
- 조회 목적에 맞게 Entity, DTO Projection 등을 선택합니다.
- 사용자 소유 데이터는 소유자 조건을 고려해 조회합니다.
- 필요한 경우 페이지네이션을 적용합니다.
- DB 조회가 필요한 비즈니스 정책의 최종 판단은 Service에서 수행합니다.

### 5.4 설정 관리

- 설정은 `application.yml` 및 환경별 설정으로 관리합니다.
- 민감 정보는 환경변수 또는 별도 비밀 관리 수단을 사용합니다.
- 환경별 차이는 Spring Profile로 명확하게 분리합니다.
- 설정값을 코드에 하드코딩하지 않습니다.
- 복잡한 설정 그룹은 `@ConfigurationProperties` 사용을 검토합니다.
- 설정 파일에 사용하지 않는 옵션을 미리 추가하지 않습니다.

### 5.5 프레임워크 활용과 검증

- Spring Boot·Spring Data JPA의 기본 기능으로 수동 CRUD·반복 SQL·데이터 매핑을 줄일 수 있는지 검토합니다.
- Spring 표준 기능으로 충분하면 자체 구현을 추가하지 않습니다.
- 단순화 전후 주요 SQL 쿼리 수, N+1, 불필요한 데이터 로딩, 트랜잭션 일관성을 확인합니다.
- 실행 성능·메모리 사용과 코드 가독성을 함께 비교하고, 실제 개선 근거로 접근 방식을 선택합니다.
- 인증·계정의 JDBC 및 운동 JPA 혼용을 변경할 때는 기존 트랜잭션·보안 계약을 먼저 확인합니다.

---

## 6. 도메인 모델링 규칙

### 6.1 주요 도메인

Overload의 주요 도메인은 다음과 같습니다.

| Domain | Responsibility |
|:---|:---|
| User | 사용자 및 계정 관리 |
| Exercise | 운동 종목 관리 |
| WorkoutSession | 운동 세션 관리 |
| WorkoutExercise | 세션 내 운동 종목 관리 |
| WorkoutSet | 세트별 운동 기록 |
| Routine | 운동 루틴 관리 |
| RoutineExercise | 루틴에 포함된 운동 종목 관리 |
| Statistics | 운동 통계 및 분석 |

### 6.2 도메인 관계

```text
User
 |
 ├── WorkoutSession
 |       |
 |       └── WorkoutExercise
 |                 |
 |                 ├── Exercise
 |                 |
 |                 └── WorkoutSet
 |
 └── Routine
         |
         └── RoutineExercise
                   |
                   └── Exercise
```

위 관계는 초기 개념 모델입니다.

실제 JPA 연관관계 및 테이블 구조는
도메인 요구사항에 맞춰 확정합니다.

### 6.3 운동 세션

`WorkoutSession`은 한 번의 운동 활동을 나타냅니다.

설계 시 고려할 속성:

- 운동 세션 ID
- 사용자 ID
- 운동 날짜
- 시작 시각
- 종료 시각
- 운동 상태
- 생성 및 수정 시각

운동 날짜와 실제 시작 시각은
서로 다른 개념으로 취급합니다.

운동 세션이 완료되지 않은 상태도 고려합니다.

### 6.4 운동 종목과 세트

`WorkoutExercise`는 운동 세션에서 수행한
개별 운동 종목을 나타냅니다.

`WorkoutSet`은 실제 수행한 세트 기록을 나타냅니다.

세트 기록의 주요 데이터:

- 세트 ID
- 운동 종목 수행 ID
- 세트 순서
- 중량
- 반복 횟수
- 세트 유형
- 완료 여부
- 메모

세트 유형 및 상태는 최종 설계의 MVP 범위를 따릅니다.
후속 확장의 구체적인 값은 별도 명세 없이 추가하지 않습니다.

### 6.5 운동 중량

운동 중량은 정확한 소수 계산이 필요합니다.

- Java `BigDecimal` / PostgreSQL `NUMERIC` 계열은 최종 설계 2절의 후보 방향을 따릅니다.
- 기본 저장 단위는 kg입니다.
- 부동소수점 오차가 발생할 수 있는 계산을 지양합니다.
- 음수 중량 등 유효하지 않은 값에 대한 검증을 적용합니다.
- 실제 허용 범위와 소수 자릿수는 도메인 명세로 확정합니다.

체중 운동이나 보조 중량을 사용하는 운동은
일반적인 중량 운동과 기록 방식이 다를 수 있으므로
중량이 0이라는 이유만으로 잘못된 기록으로 처리하지 않습니다.

### 6.6 운동 볼륨

기본적인 운동 볼륨 계산식은 다음과 같습니다.

```text
Set Volume = Weight × Reps

Total Volume = Sum(Set Volume)
```

계산 시 최종 설계의 확정 정책과 대조할 사항:

- 워밍업 세트 포함 여부
- 미완료 세트 처리
- 체중 운동 처리
- 보조 중량 처리
- 실패 세트 처리
- 결과의 정밀도 및 반올림

확정된 운동 계산 정책은 최종 설계를 따르며, 상세 정책을 이 문서에 중복 정의하지 않습니다.
명세에 없는 계산 정책을 임의로 추가하지 않습니다.

### 6.7 개인 최고 기록(PR)

PR은 단순히 하나의 최대 중량만 의미하지 않을 수 있습니다.

예시:

- 최대 수행 중량
- 특정 중량에서의 최대 반복 횟수
- 추정 1RM
- 최대 운동 볼륨

각 PR은 계산 기준과 데이터 출처가 명확해야 합니다.

추정 1RM 등의 계산식은
별도 도메인 명세를 통해 정의합니다.

### 6.8 도메인 불변식

Entity와 Service는 데이터의 일관성을 유지해야 합니다.

예시:

- 세트는 유효한 운동 종목 수행 기록에 속해야 합니다.
- 운동 세션은 유효한 사용자에게 속해야 합니다.
- 다른 사용자의 운동 기록을 임의로 수정할 수 없어야 합니다.
- 운동 완료 상태 전환은 정의된 조건을 따라야 합니다.
- 세트 순서는 동일한 운동 종목 수행 내에서 일관되어야 합니다.

규칙은 애플리케이션 검증과
필요한 DB 제약조건을 함께 고려합니다.

---

## 7. JPA 및 트랜잭션 규칙

### 7.1 Entity 설계

- Entity는 데이터베이스 영속성 모델로 사용합니다.
- Entity를 API 응답에 직접 사용하지 않습니다.
- Entity 간 연관관계는 필요한 경우에만 설정합니다.
- 기본 생성자와 식별자 관리 방식은 JPA 규칙을 따릅니다.
- 무분별한 Setter 공개를 지양합니다.
- 상태 변경은 의미 있는 도메인 메서드를 우선 검토합니다.
- 컬렉션 관계를 불필요하게 전부 로딩하지 않습니다.

### 7.2 연관관계

- 단방향 관계로 충분하다면 단방향을 우선합니다.
- 양방향 연관관계는 실제 필요성이 있을 때 적용합니다.
- 모든 연관관계를 EAGER로 설정하지 않습니다.
- 필요한 조회에 따라 Fetch Join, Entity Graph, Projection 등을 검토합니다.
- Cascade 및 Orphan Removal은 생명주기 관계가 명확한 경우에만 적용합니다.

### 7.3 트랜잭션

트랜잭션 경계는 기본적으로 Service 계층에서 관리합니다.

- 변경 작업에는 `@Transactional`을 적용합니다.
- 조회 전용 작업에는 `@Transactional(readOnly = true)` 사용을 검토합니다.
- 트랜잭션 범위는 가능한 한 짧게 유지합니다.
- 하나의 작업에서 데이터 일관성이 필요한 변경을 함께 처리합니다.
- 외부 네트워크 호출을 긴 DB 트랜잭션 내부에서 수행하지 않습니다.
- 트랜잭션의 전파 및 롤백 정책을 이해하고 적용합니다.
- Spring Proxy 기반 트랜잭션의 Self-invocation 문제를 고려합니다.

### 7.4 영속성 컨텍스트

- 영속 상태와 준영속 상태의 차이를 이해합니다.
- Dirty Checking을 올바르게 활용합니다.
- 불필요한 `save()` 호출을 반복하지 않습니다.
- Lazy Loading을 무분별하게 발생시키지 않습니다.
- API 응답 직렬화 과정에서 Lazy Loading이 발생하지 않도록 합니다.
- OSIV 비활성화를 기본 방향으로 검토합니다.
- 조회 및 DTO 변환에 필요한 데이터는 명시적인 트랜잭션 안에서 확보합니다.

### 7.5 동시성 처리

동시에 같은 운동 기록을 수정하는 상황을 고려합니다.

- 동시 수정으로 데이터가 유실될 가능성을 검토합니다.
- 필요한 경우 Optimistic Locking을 검토합니다.
- `@Version` 적용 여부를 도메인별로 판단합니다.
- 버전 충돌 시 사용자에게 명확한 오류를 전달합니다.
- 필요한 경우 중복 요청 방지 또는 멱등성 처리를 적용합니다.
- 불필요한 비관적 락 사용을 지양합니다.

일반 온라인 수정의 낙관적 잠금과 진행 세션의 writerEpoch/revision 검사를 구분합니다.
진행 스냅샷의 원자 저장·중복 판정은 Task 009 v1.0 7절을 따릅니다.
하위 변경에 JPA `@Version`이 자동 반응한다고 가정하거나 모든 엔티티에 기계적으로 적용하지 않습니다.

---

## 8. PostgreSQL 및 DB Migration

### 8.1 데이터베이스 설계

현행 목표 스키마의 관계는 [ERD](../database/erd.md), 전체 컬럼·제약조건과 보존 규칙은 [DB 설계서](../database/schema.md)를 참고합니다. 실제 개발 DB에 적용된 버전은 별도로 확인합니다.

- PostgreSQL을 기본 RDBMS로 사용합니다.
- 테이블과 컬럼 이름은 `snake_case`를 사용합니다.
- Primary Key와 Foreign Key를 명확하게 정의합니다.
- 필요한 경우 UNIQUE, NOT NULL, CHECK 제약조건을 적용합니다.
- 자주 사용하는 조회 조건을 기준으로 인덱스를 검토합니다.
- 무분별한 인덱스 추가를 피합니다.
- 삭제 정책과 데이터 보존 요구사항을 명확하게 정의합니다.

### 8.2 데이터 타입

| Data | Java | PostgreSQL |
|:---|:---|:---|
| Domain Identifier | UUID | UUID |
| Weight | BigDecimal | NUMERIC |
| Repetitions | Integer | INTEGER |
| Workout Date | LocalDate | DATE |
| Timestamp | Instant | TIMESTAMPTZ |
| Status | Enum | VARCHAR 또는 별도 매핑 |

도메인 UUID와 소유권·세대 제약의 적용 범위는 최종 설계 6절을 따릅니다.
Spring Session 관리 테이블 등 프레임워크 내부 식별자는 해당 스키마와 구분합니다.
이미 구현한 컬럼의 자료형·정밀도는 [DB 설계서](../database/schema.md)를 확인합니다.

Enum 저장 방식은 확장성을 고려하여 결정하며,
변경 가능성이 있는 Enum에서 ORDINAL 저장을 피합니다.

### 8.3 날짜 및 시간

- 서버에서 관리하는 시점 데이터는 UTC를 기준으로 처리합니다.
- 실제 이벤트 시각은 `Instant` 사용을 우선합니다.
- PostgreSQL에서는 `TIMESTAMPTZ` 사용을 검토합니다.
- 운동 날짜는 사용자의 시간대를 기준으로 별도 관리합니다.
- 사용자 시간대는 필요한 경우 IANA Zone ID로 관리합니다.
- 타임존이 없는 날짜와 타임존이 있는 시점을 혼동하지 않습니다.
- 테스트에서는 서버의 기본 시간대에 의존하지 않습니다.
- 시간 의존 로직은 `Clock` 주입을 검토합니다.

### 8.4 DB Migration

스키마 변경의 실행 기준은 [Flyway migration](../../src/main/resources/db/migration/)입니다. 현재 로컬 PostgreSQL·Flyway 구성과 검증 명령은 [로컬 실행 문서](local-postgresql.md)를 따릅니다.

규칙:

- DB 스키마 변경은 Migration 파일로 관리합니다.
- 이미 적용된 Migration 파일을 임의로 수정하지 않습니다.
- 새로운 변경은 새로운 Migration으로 추가합니다.
- 운영 데이터에 영향을 주는 변경은 별도로 검토합니다.
- 테이블 또는 컬럼 삭제 시 기존 데이터 손실을 확인합니다.
- 마이그레이션과 Entity 정의의 일치 여부를 검증합니다.
- 운영 환경에서 `ddl-auto=update`에 의존하지 않습니다.
- Flyway 도입 이후에는 Hibernate의 `validate` 사용을 우선 검토합니다.

### 8.5 조회 성능

- N+1 Query 발생 여부를 확인합니다.
- 대량 데이터를 한 번에 메모리로 불러오지 않습니다.
- 이력 조회에는 페이지네이션을 고려합니다.
- 대규모 통계는 DB 집계와 애플리케이션 계산 중 적합한 방식을 선택합니다.
- 필요 없는 연관 데이터를 조회하지 않습니다.
- 실제 측정 없이 성능 최적화를 과도하게 진행하지 않습니다.

---

## 9. REST API 설계 규칙

### 9.1 API 기본 원칙

- REST API를 기본으로 사용합니다.
- JSON을 기본 요청 및 응답 데이터 형식으로 사용합니다.
- 리소스 중심의 URI를 설계합니다.
- HTTP Method의 의미를 준수합니다.
- 적절한 HTTP Status Code를 반환합니다.
- API 변경 시 Backend와 Frontend가 같은 최신 계약으로 전환되도록 영향과 적용 순서를 검토합니다.

기본 API Prefix:

```text
/api/v1
```

### 9.2 URI 네이밍

- URI는 소문자를 사용합니다.
- 리소스 이름은 복수형 명사를 우선합니다.
- URI에서 동사 표현을 지양합니다.
- 경로 깊이를 불필요하게 늘리지 않습니다.
- 식별자 이름은 API 전체에서 일관되게 사용합니다.

예시:

```http
GET    /api/v1/standard-exercises/search-data
GET    /api/v1/workout-settings
PATCH  /api/v1/workout-settings
POST   /api/v1/workout-sessions
GET    /api/v1/workout-sessions/{sessionId}
GET    /api/v1/routines
```

위 경로의 요청·응답 조건은 [온라인 설정](../api/workout-online-write.md), [세션](../api/workout-sessions.md), [루틴](../api/routine-management.md)의 현행 계약을 따릅니다. 개인 운동 GET은 제거됐고 POST/PATCH는 차단 경계만 유지합니다.

### 9.3 HTTP Method

| Method | Purpose |
|:---|:---|
| GET | 리소스 조회 |
| POST | 리소스 생성 또는 정의된 작업 실행 |
| PUT | 리소스 전체 교체 |
| PATCH | 리소스 일부 변경 |
| DELETE | 리소스 삭제 |

HTTP Method의 멱등성 특성을 고려합니다.

### 9.4 HTTP Status Code

| Status | Usage |
|:---|:---|
| 200 | 정상 조회 또는 처리 |
| 201 | 리소스 생성 |
| 204 | 반환 본문이 없는 정상 처리 |
| 400 | 잘못된 요청 |
| 401 | 인증되지 않은 요청 |
| 403 | 접근 권한 부족 |
| 404 | 존재하지 않는 리소스 |
| 409 | 데이터 상태 또는 버전 충돌 |
| 422 | 의미적으로 처리할 수 없는 요청(필요 시) |
| 500 | 서버 내부 오류 |

상태 코드 사용 기준을 API 전체에서 일관되게 유지합니다.

### 9.5 요청 및 응답

성공 응답은 API별 DTO를 직접 반환합니다. 공통 성공 래퍼는 두지 않습니다. HTTP 204는 본문 없이 반환하며, 헤더는 해당 API와 추적 계약을 따릅니다.

- 요청 DTO와 응답 DTO를 명확히 구분합니다.
- Entity를 직접 직렬화하지 않습니다.
- 사용자가 변경할 수 없는 필드는 요청에서 제외합니다.
- 서버가 관리하는 값은 서버에서 생성합니다.
- API 필드의 이름과 데이터 타입을 일관되게 유지합니다.
- 변경 가능한 필드와 불변 필드를 구분합니다.

### 9.6 페이지네이션

운동 이력과 같이 데이터가 누적되는 조회에는
페이지네이션을 적용합니다.

- 페이지 크기에 상한을 둡니다.
- 정렬 기준을 명확하게 정의합니다.
- 동일한 데이터 집합에 대해 안정적인 정렬을 사용합니다.
- 대규모 데이터 조회 시 필요한 경우 Cursor 기반 페이지네이션을 검토합니다.

### 9.7 API 계약 관리

- API 변경 시 요청 및 응답 구조를 확인합니다.
- Breaking Change는 `AGENTS.md` 15절의 사전 승인과 양쪽 저장소의 전환 조율을 따릅니다.
- 필드 변경은 최신 설계·입력 의미·현재 데이터 보호를 검토하며, 구버전 호환성 전용 계층은 기본으로 추가하지 않습니다.
- OpenAPI 도입 이후 문서와 구현을 일치시킵니다.
- 실제로 존재하지 않는 Endpoint를 구현 완료로 표시하지 않습니다.

---

## 10. 입력 검증 및 예외 처리

### 10.1 입력 검증

Jakarta Validation을 기본 검증 도구로 사용합니다.

예시 어노테이션:

- `@NotNull`
- `@NotBlank`
- `@Size`
- `@Min`
- `@Max`
- `@DecimalMin`
- `@Valid`

규칙:

- 입력 데이터의 형식과 필수 여부를 검증합니다.
- 검증 기준은 실제 도메인 규칙을 따릅니다.
- DTO 형식 검증과 비즈니스 규칙 검증을 구분합니다.
- 클라이언트 검증이 존재하더라도 서버에서 다시 검증합니다.
- 범위가 확정되지 않은 필드에 임의의 제한을 설정하지 않습니다.

### 10.2 예외 처리

공통 예외 처리는 `@RestControllerAdvice`를 우선 사용합니다.

- 도메인 오류와 시스템 오류를 구분합니다.
- 예외를 의미 없이 포괄적으로 처리하지 않습니다.
- 예외를 무시하고 정상 응답으로 변환하지 않습니다.
- 로그에 문제 분석에 필요한 정보를 남깁니다.
- 내부 Stack Trace를 API 응답에 노출하지 않습니다.
- 사용자에게 이해할 수 있는 오류 메시지를 제공합니다.

### 10.3 오류 응답 형식

- 명시적으로 처리하는 도메인·애플리케이션 오류는 의미에 맞는 HTTP 오류 상태와 `{"code":"…"}` 중심 JSON을 사용합니다. `code`는 안정적인 UPPER_SNAKE_CASE 문자열이며, 추가 필드는 기능별 계약에 명시된 경우에만 사용합니다. 내부 예외나 SQL 상세를 노출하지 않습니다.
- 기존 인증 오류의 상태·본문·헤더를 유지합니다. OAuth 리다이렉트, 본문 없는 응답, MVC 기본 오류, 컨테이너·프록시 오류는 각 경계를 따릅니다. 기능별 계약을 넘어 공통 오류 래퍼나 `ProblemDetail`을 일괄 적용하는 규약은 없습니다.
- [RequestIdFilter](../../src/main/java/com/overload/overload_server/http/RequestIdFilter.java)는 REQUEST·ERROR 디스패치에 서버 생성 UUID를 `X-Request-ID` 응답 헤더와 로그 MDC에 제공합니다. ERROR 재디스패치는 같은 요청 ID를 재사용하고 외부 요청 헤더는 신뢰하지 않습니다. 추적 ID를 본문 필수 필드로 추가하지 않습니다. 필터 진입 전에 거절된 요청에는 헤더가 없을 수 있습니다.

[공통 HTTP 합의와 당시 검증 기록](../tasks/task-008-0d-common-api-contract-v0.1.md#1-확정된-공통-방향)은 설계 근거로 보존합니다. 개별 요청·응답과 오류 코드는 각 기능의 현행 API 계약을 적용합니다.

---

## 11. 인증 및 인가

### 11.1 기본 원칙

사용자별 운동 기록은
반드시 인증된 사용자 기준으로 관리합니다.

- 인증과 인가를 구분합니다.
- 모든 보호 대상 API에 접근 제어를 적용합니다.
- 서버는 클라이언트가 전달한 사용자 ID를 신뢰하지 않습니다.
- 사용자 정보는 검증된 인증 컨텍스트를 기준으로 조회합니다.
- 다른 사용자의 데이터에 대한 접근을 차단합니다.
- 관리자 권한이 필요한 기능은 명시적으로 구분합니다.

### 11.2 Spring Security

Spring Security를 인증 및 인가의 기본 도구로 사용합니다.

- MVP 인증은 최종 설계 3절의 Google OIDC + 이메일 OTP(Resend), Spring Session JDBC/PostgreSQL 기반 쿠키 세션을 따릅니다.
- Spring Security·Session JDBC 기반은 007-1C-1, 이메일 OTP 서버는 007-1C-2에서 도입했고 Resend 실수신·로그인도 검증했습니다.
- 007-1C-3A에서 공식 OAuth2 Client 기반 Google OIDC를 추가했습니다. 로컬 서명/JWKS·PostgreSQL 테스트와
  실제 Google 브라우저 검증은 구분하며, 설정·활성화·API는 [Google OIDC 문서](google-oidc.md)를 따릅니다.
- 이메일 OTP의 상태·트랜잭션·제한·환경 설정은 [이메일 OTP 문서](email-otp.md)를 따릅니다.
- 현재 세션·쿠키·CSRF·보안 응답 및 후속 연동 계약은 [보안·세션 문서](security-session.md)를 따릅니다.
- 보호 대상 Endpoint와 공개 Endpoint를 명확히 구분합니다.
- 인증되지 않은 접근에 적절한 오류를 반환합니다.
- CORS는 허용할 Origin을 명시적으로 설정합니다.
- 운영 환경에서 무분별한 전체 Origin 허용을 피합니다.
- HTTPS 사용을 전제로 운영 환경을 설계합니다.

### 11.3 MVP 쿠키 세션 인증

세션 수명·재인증·계정 운영은 최종 설계 3절을 따릅니다.
애플리케이션 JWT 및 Access/Refresh Token 발급 흐름을 별도로 도입하지 않습니다.

- Google OIDC 응답의 서명·만료·Issuer·Audience 및 state/nonce를 검증합니다.
- 서버 비밀키를 코드나 저장소에 포함하지 않습니다.
- 세션 고정 방지 및 로그아웃·계정 작업에 따른 세션 폐기를 적용합니다.
- 인증 실패와 인가 실패를 구분합니다.
- CSRF 방어와 HttpOnly/Secure/SameSite Cookie 속성을 최종 설계에 맞게 적용합니다.
- 구체적인 Cookie·CORS·배포 환경의 기술 계약은 구현 전 검증합니다.

### 11.4 사용자별 데이터 접근

사용자 소유 데이터는 조회 시점부터
접근 권한을 검증할 수 있도록 설계합니다.

예시 개념:

```text
Authenticated User
        |
        v
Workout Service
        |
        v
Find Workout by ID + Owner
        |
        v
Authorized Data Access
```

운동 ID만 알고 있다는 이유로
다른 사용자의 기록을 조회하거나 변경할 수 없어야 합니다.

---

## 12. 진행 세션 API와 로컬 보호 연동

현행 기준은 [Task 009 v1.0](../tasks/task-009-redesign-v1.0.md)입니다. 다음은 서버 구현과 현재 Frontend/IndexedDB 연동의 원칙입니다. V17 실제 개발 환경 검증 범위는 [4단계 보고서](../tasks/standard-exercise-catalog-dev-phase-4-report.md)를 따릅니다.

### 12.1 전송과 중복 판정

- 일반 종목·루틴·설정 관리는 온라인 API로 처리합니다. 범용 명령·영수증/feed를 만들지 않습니다.
- 진행 운동은 완료·종료·재연결 시 전체 확정 스냅샷을 받습니다. 미완료 초안은 공식 수행으로 저장하지 않습니다.
- 사용자 소유·세대 확인, 세션 행 잠금, 상태·writerEpoch·revision·도메인 검증과 저장을 하나의 운동 트랜잭션으로 처리합니다.
- 동등 epoch/revision/내용은 중복 성공이며 최근 활동 시각을 연장하지 않습니다. 다른 내용, 낮은 revision, 이전 epoch는 덮어쓰지 않습니다.
- finish는 최종 스냅샷 저장과 명시 종료를 원자 처리하고 동일 종료 재시도에 기존 결과를 반환합니다.

### 12.2 편집 권한과 24시간 보존

- 세대와 무관하게 사용자별 ACTIVE 하나를 DB 제약으로 보장합니다. deviceId는 인증 수단이 아니며 별도 editorProof·기기 비밀 토큰은 도입하지 않습니다.
- 명시적 편집 권한 인수 시 epoch를 증가시키고 revision을 0으로 초기화하며 서버 확정 상태를 반환합니다. 이전 기기의 승인은 필요 없습니다.
- START와 실제 확정 운동 상태 저장 후 24시간이면 AUTO_CLOSED로 마지막 서버 완료 기록을 보존합니다. takeover·조회·중복·무변경 snapshot·승인 해결은 기한을 연장하지 않습니다. 만료 시각을 실제 운동 종료 시각으로 사용하지 않습니다. V14와 요청/예약 작업의 만료 처리로 구현합니다.
- START/GET/TAKEOVER/SNAPSHOT/FINISH에서도 만료를 확인합니다. 늦은 스냅샷으로 자동 종료 기록을 다시 열거나 덮어쓰지 않습니다.

### 12.3 데이터 무결성과 구현 경계

- PostgreSQL을 단일 서버 원본으로 사용하고 불변 계획·승인 사건·최초 종료 평가는 보존합니다.
- IndexedDB는 이미 시작한 운동의 로컬 복원용입니다. 새 운동의 오프라인 시작·자동 병합·Redis·주기적 heartbeat는 도입하지 않습니다.
- V1~V9 이력은 수정하지 않습니다. 정식 V10은 폐기 컬럼·테이블만 제거합니다. 2026-10-31 사용자 승인으로 개인 로컬 `127.0.0.1:5433/overload_dev`의 V8→V9→V10 적용·기존 데이터 보존 검증을 완료했습니다. [실제 적용 보고서](../tasks/task-009-v10-local-application-report.md)를 확인하고, 다른 DB·후속 파괴적 migration에는 별도 승인을 받습니다.

### 12.4 UI v0.9와 디로딩 v1.7 연동

정책은 [디로딩 v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md), 화면과 연동은 [PWA G6 지침](../../../overload-pwa/docs/engineering/backend-ui-v0.9.md)을 적용합니다. 상세 제품 규칙은 제품 명세에서, 요청·응답·오류·잠금은 각 API 계약에서 관리합니다.

- [G4 분석](../api/deload-analysis.md)은 신규 기본 ON, 설정·epoch·독립 후보·주별 확정·과거 정정 재계산과 조회를 구현했습니다. [G3 일별 검사와 일정 이력](../api/weekly-schedule.md)은 G4 ON/OFF와 독립입니다.
- [G5 기간과 임시 계획](../api/deload-periods.md) 및 [디로딩 세션](../api/workout-sessions.md#g5-2-디로딩-세션)은 시작·기간 내 실행·완료·취소·자동 완료와 일반 쓰기 보호를 구현했습니다. 원본 프리셋·순환과 복귀는 12.5절을 확인합니다. [일반 통계](../api/statistics.md)는 전체 실제 수행량과 일반 예정 루틴 달성을 구분합니다.
- [1차 격리 검증 기록](../../../overload-pwa/docs/tasks/deload-v17-backend-readiness-analysis.md#0-2026-11-08-1차-개발-최종-현황)은 V24→V29 전환에서 계획·수행·평가·고정 요청·승인 사건·정정·검사 근거의 보존을 확인했습니다. 프리셋까지 포함한 후속 회귀 검증은 12.5절에 있습니다.

**기존 DB 적용 경계.** 실제 개발·검토 DB 전환과 G6는 후속입니다. V25는 기존 계정을 자동 등록하지 않으며, 미설정 계정의 신규 일반 START와 일정 저장에는 설정 부재 오류를 적용합니다. 기존 검토·개인 DB에 V25나 `T_adopt` 이행을 실행하지 않았습니다. 적용 전 대상과 비소급 경계, 승인된 실행 방법, 쓰기 중단과 영구 백업의 격리 복원, 재개 전 검증을 확인합니다. V30의 [기존 계정 전환](../api/weekly-schedule.md#v1-출시-전-전환)도 별도입니다. G6 이전 PWA는 디로딩 세션의 null `InitialPlan.routineId`를 받아들이지 않으므로 혼재 기록을 노출하는 배포는 별도로 검증합니다. [분석 설정 이행 계약](../api/deload-analysis.md#v25v26와-명시적-기존-계정-이행)

**남은 제품·연동 검토.** 운영 카탈로그 분류 검증, 날짜별 종목·근육 시계열과 제조사 집계, 성장 검색의 카탈로그 별칭 및 디로딩 전용 종목 노출, 0회 초안과 양의 정수 완료 검증의 경계를 구분합니다. G6는 계정·세션 시간대 차이, 타 기기 미전송, 10일차 활성 세션 경합, 8일차 안내 범위와 혼재 세션의 동기화 보호를 검증합니다.

---

### 12.5 다중 주간 일정 프리셋

**1단계 구현.** V30과 가입 트랜잭션은 [프리셋·상태 테이블과 초기화](../api/weekly-schedule.md#v30-프리셋-저장-기반과-신규-계정-초기화)를 제공한다. 가입 시 실제 일정 적용은 시작하지 않는다.

**2단계 구현.** [프리셋 API](../api/weekly-schedule.md#2단계-api-계약)는 읽기 전용 전체 조회, 빈 프리셋 생성, 요일 편집, 수동 적용, 프리셋 삭제를 제공한다. START와 같은 사용자 행 잠금 아래 실제 적용 이력의 공통 쓰기 경로를 사용한다. 루틴 논리 삭제와 전체 프리셋의 배정 해제는 같은 트랜잭션에서 실행한다. G3/G4와 일반 운동 통계는 기존 불변 적용 근거를 사용한다. 당시 PWA에는 단일 일정 계약이 남아 있었다.

**3단계 구현.** [자동 순환 설정과 복구](../api/weekly-schedule.md#자동-순환-설정) 및 [G5 원본 선택·정지·복귀](../api/deload-periods.md#주간-일정-프리셋과-순환-복귀)를 제공한다. 기존 tick은 G5 자동 완료 → 순환 → G3 → G4 순이다. 순환은 계정별 트랜잭션에서 사용자 행 잠금과 다음 전환 시각을 재검증한다. V30의 활성 ID와 다음 전환 시각을 사용한다. 당시 실제 DB 적용과 PWA API 연결은 후속 범위였다.

**4단계 최종 감사.** 기존 V30의 사용자·세대 복합 참조, 표시 순번 제약, 배정 배열 크기를 확인했다. 요일 중복과 루틴 소유권·삭제 상태는 사용자 잠금 아래 API에서 검증한다. 프리셋은 현재 편집 상태를, `weekly_schedule_versions`는 당시 적용 근거를 맡는다. 운영 코드에서 일반 일정의 실제 적용은 `AppliedWeeklySchedule.apply` 한 곳에서 기록한다. 기존 V24 전환 함수와 G5의 역사적 원본 필드는 당시 근거를 보존한다.

V30 마감 당시 격리 회귀에서 단위 438개와 PostgreSQL 통합 840개가 통과했다. 실패와 건너뜀은 0개였다. 빈 DB의 V1~V30 적용과 V24→V30 전환을 검증했다. 재기동 첫 tick의 G5 복귀와 별도 계정의 누락 주차 복구, START 사용자 잠금 중 순환 건너뛰기, 반복 실패 계정과 정상 계정의 격리, 지연 전환 이후 최초 MISS·정정·복구를 검증했다. 당시 PWA 연동과 실제 DB 적용은 후속 범위였다.

**V31 이름 저장 및 PWA 연동.** 이름 변경은 사용자·세대·프리셋 소유권과 상태·프리셋 버전, 일반 쓰기 보호를 확인한다. 적용 이력과 순환 상태는 유지한다. HOME·ROUTINE의 실제 OTP·저장·재조회와 이름 변경 후 순환·배정 보존을 PC Chrome과 전용 V31 서버에서 검증했다. [이름 변경 계약](../api/weekly-schedule.md#v31-프리셋-이름-변경) · [Frontend 현황](../../../overload-pwa/docs/engineering/frontend.md#87-v09-적용-범위와-구현-상태)

#### 운동 세션 통합 테스트 시계 안정화

V31 검토 후 전체 회귀에서 843개 중 821개가 통과했고 운동 세션 테스트 22개가 실패했다. 로그인 뒤 테스트 시계를 과거로 이동하면서 세션 발급 시각이 미래가 되어 운동 로직 진입 전에 HTTP 401을 반환했다. 동일한 실패는 V31 이전 `a5b47d6`에서도 재현했다.

`5b5b136`은 OTP 로그인 전에 시나리오 기준 시각을 적용하고 테스트가 끝나면 공유 시계를 복구한다. 정상 세션 조회와 발급 시각 이전 요청 거부를 확인하는 회귀를 추가했다. 운영 코드와 인증 정책은 유지했다. 이전 실패 22개와 신규 회귀 1개를 먼저 통과한 뒤 2026-11-10 Gradle Wrapper의 `test integrationTest assemble`로 단위 439개·격리 PostgreSQL 통합 844개·패키징을 검증했다. 실패와 건너뜀은 0개였다. [테스트 소스](../../src/test/java/com/overload/overload_server/workoutsession/WorkoutSessionIntegrationTests.java)

## 13. 테스트 규칙

### 13.1 테스트 기본 원칙

- 공통 테스트 기준은 `AGENTS.md` 13절을 따르며, 현재 확정된 제품 동작을 검증합니다.
- 새로운 핵심 비즈니스 로직에는 테스트를 작성합니다.
- 버그 수정 시 가능한 경우 회귀 테스트를 추가합니다.
- 정상 입력과 예외 입력을 함께 검증합니다.
- 테스트는 독립적으로 실행할 수 있도록 작성합니다.
- 테스트 순서에 의존하지 않습니다.
- 현재 시간이나 실행 환경에 불필요하게 의존하지 않습니다.

### 13.2 Unit Test

Unit Test의 주요 대상:

- 운동 볼륨 계산
- PR 산정
- 운동 세션 상태 변경
- 세트 순서 관리
- 도메인 유효성 검증
- 조건별 비즈니스 로직

가능한 경우 Spring Context 없이 테스트합니다.

비즈니스 로직의 실제 결과를 검증하며
단순히 Mock 호출 여부만 확인하는 테스트를 지양합니다.

### 13.3 Controller Test

Controller Test에서는 다음 항목을 검증합니다.

- HTTP Status Code
- 요청 DTO 검증
- 응답 JSON 구조
- 잘못된 요청 처리
- 인증되지 않은 접근
- 권한 없는 요청

Spring MVC 테스트 도구와
현재 Spring Boot 버전에 맞는 Test Starter를 사용합니다.

### 13.4 Repository Test

Repository Test에서는 다음 항목을 검증합니다.

- 데이터 저장 및 조회
- 관계 매핑
- 사용자별 조회 조건
- DB 제약조건
- 복잡한 Query 결과
- 페이지네이션 및 정렬

PostgreSQL 전용 기능이나 실제 SQL 동작이 중요하다면
Testcontainers 기반 PostgreSQL 테스트를 우선 검토합니다.

### 13.5 Integration Test

Integration Test에서는 계층 간 연결을 검증합니다.

예시:

```text
Controller
    |
    v
Service
    |
    v
Repository
    |
    v
Test Database
```

검증 대상:

- 운동 세션 생성 및 조회
- 세트 기록 추가 및 수정
- 사용자별 데이터 접근 권한
- 트랜잭션 롤백
- 데이터 무결성
- API 오류 처리

### 13.6 테스트 우선순위

MVP에서는 다음 순서로 검증을 강화합니다.

1. 핵심 도메인 계산 로직
2. 운동 기록 CRUD
3. 사용자 소유권 검증
4. 트랜잭션 및 데이터 무결성
5. API 요청 및 응답 계약
6. 동시성 및 중복 요청 처리

모든 코드에 형식적인 테스트를 만드는 것보다
실제 오류를 방지하는 테스트를 우선합니다.

---

## 14. 빌드 및 로컬 검증

### 14.1 Gradle Wrapper

Gradle 명령은 프로젝트에 포함된
Gradle Wrapper를 우선 사용합니다.

macOS / Linux:

```bash
./gradlew test
./gradlew build
```

Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

### 14.2 검증 규칙

코드 변경 후 다음 항목을 확인합니다.

- 컴파일 오류
- 테스트 결과
- 애플리케이션 실행 오류
- 의존성 충돌
- 설정 파일 문제
- 데이터베이스 연결 문제

실행 가능한 검증 명령은
프로젝트의 실제 Gradle 설정을 확인한 뒤 사용합니다.

설정되지 않은 Linter나 Formatter를
설치된 것으로 가정하지 않습니다.

### 14.3 검증 실패 시

- 실패 로그를 확인합니다.
- 변경 코드와 실패의 관련성을 분석합니다.
- 테스트를 임의로 비활성화하지 않습니다.
- 테스트 실패를 숨기지 않습니다.
- 환경 문제로 실행하지 못한 경우 이를 명시합니다.
- 기존에 존재하던 실패와 새로 발생한 실패를 구분합니다.

---

## 15. 로깅 및 운영 안정성

### 15.1 로깅

- SLF4J 기반 로깅을 사용합니다.
- `System.out.println()`을 애플리케이션 로그 용도로 사용하지 않습니다.
- 로그 레벨을 목적에 맞게 구분합니다.
- 불필요한 반복 로그를 지양합니다.
- 인증 정보 및 개인정보를 출력하지 않습니다.
- 예외 원인을 추적할 수 있도록 필요한 정보를 기록합니다.

### 15.2 예외 상황

다음 상황을 고려합니다.

- 잘못된 운동 기록 입력
- 존재하지 않는 운동 종목
- 권한 없는 운동 세션 조회
- 중복 데이터 생성
- 데이터베이스 연결 실패
- 트랜잭션 충돌
- 외부 의존성 오류

### 15.3 성능 및 안정성

- 측정 없이 과도한 성능 최적화를 하지 않습니다.
- 대량 조회 시 페이지네이션을 우선 검토합니다.
- 대규모 데이터 집계는 실제 Query 비용을 확인합니다.
- 적절한 타임아웃을 설정합니다.
- 외부 서비스 호출 시 실패 가능성을 고려합니다.
- 중요한 변경 후 기존 기능의 회귀 여부를 확인합니다.

---

## 16. AI 에이전트 백엔드 작업 절차

AI 에이전트는 백엔드 작업을 수행할 때
다음 절차를 따릅니다.

### Step 1. Inspect

- 루트 `AGENTS.md` 확인
- 현재 `backend.md` 확인
- 기존 프로젝트 및 패키지 구조 확인
- Gradle 설정 및 Java 버전 확인
- 관련 코드와 테스트 확인
- API 및 DB 스키마 영향 범위 확인

### Step 2. Plan

다음 내용을 정리합니다.

- 구현 대상
- 변경 파일
- 변경이 필요한 계층
- DB Migration 필요 여부
- API 계약 변경 여부
- 테스트 계획
- 보안 및 데이터 무결성 영향

### Step 3. Implement

구현 순서는 상황에 맞게 조정하되
도메인 규칙과 데이터 일관성을 우선합니다.

대표적인 작업 흐름:

```text
Domain Rules
     |
     v
Entity / Repository
     |
     v
Service
     |
     v
DTO / Controller
     |
     v
Tests
```

이 순서는 절대적인 구현 순서를 의미하지 않습니다.

기존 코드 및 요구사항에 따라
가장 안전하고 단순한 순서를 선택합니다.

### Step 4. Verify

- 코드 컴파일
- 관련 단위 테스트
- 관련 통합 테스트
- 빌드 결과
- 데이터 무결성
- 보안 및 접근 권한

### Step 5. Review

작업 완료 전 확인합니다.

- Controller에 비즈니스 로직이 포함되지 않았는가?
- Entity가 API에 직접 노출되지 않는가?
- 트랜잭션 경계가 적절한가?
- N+1 문제가 발생할 가능성이 있는가?
- 다른 사용자 데이터에 접근할 수 있는가?
- DTO 검증이 누락되지 않았는가?
- 예외 및 오류 처리가 일관적인가?
- API가 최신 확정 설계 및 Frontend 계약과 일치하고 현재 데이터를 보호하는가?
- Migration 및 테스트가 필요한가?

### Step 6. Report

작업 완료 시 다음 내용을 보고합니다.

#### Summary
- 구현한 주요 기능

#### Files
- 생성 및 수정한 파일

#### API Changes
- 변경된 Endpoint 및 DTO

#### Database Changes
- Entity 및 Migration 변경 사항

#### Validation
- 실행한 검증 명령
- 테스트 및 빌드 결과
- 실행하지 못한 검증과 이유

#### Notes
- 설계 결정
- 남은 문제
- 추가 작업

---

## 17. Definition of Done — Backend

백엔드 작업 완료 전 다음 항목을 확인합니다.

### Architecture

- [ ] 최신 설계와 공통 단순화 기준을 충족하며 구조 변경의 근거가 명확한가?
- [ ] 계층별 책임을 올바르게 분리했는가?
- [ ] 불필요한 추상화를 추가하지 않았는가?

### Domain & Persistence

- [ ] 도메인 규칙을 올바르게 구현했는가?
- [ ] Entity 관계가 적절한가?
- [ ] 트랜잭션 범위가 적절한가?
- [ ] 데이터 정확성과 무결성을 유지하는가?
- [ ] 필요한 DB Migration을 추가했는가?

### API & Security

- [ ] 요청 및 응답 DTO를 분리했는가?
- [ ] 입력 검증을 적용했는가?
- [ ] 적절한 HTTP Status를 사용하는가?
- [ ] 예외 처리가 일관적인가?
- [ ] 인증 및 인가 요구사항을 충족하는가?
- [ ] 사용자별 데이터 접근을 검증하는가?

### Quality

- [ ] 핵심 로직에 적절한 테스트가 있는가?
- [ ] 실행 가능한 테스트를 수행했는가?
- [ ] 빌드 결과를 확인했는가?
- [ ] 불필요한 코드 변경이 없는가?
- [ ] 관련 문서를 갱신했는가?
- [ ] 미해결 문제와 검증되지 않은 항목을 보고했는가?

---

## 18. 추가 설계 결정이 필요한 항목

인증·식별자·운동·동기화·삭제의 확정 정책은 최종 설계를 따릅니다.
다음은 정책을 다시 결정하는 항목이 아닌, 남은 기술 상세와 후속 검토 대상입니다.

- 중량 데이터의 정밀도 및 기술적 허용 범위
- MVP 범위 밖 세트 유형과 PR의 후속 확장 기준
- Cookie·CSRF·OIDC/OTP·배포 환경의 세부 구현 계약
- 디로딩 기간·임시 일정·세션 소속의 연동 계약과 동기화 경계(12.4절)
- 소유권·세대 FK, 인덱스 및 집계 버전 증가의 구체적인 DDL·JPA 매핑
- 새 API에 필요한 선택 오류 필드와 해당 실패 경계
- 운영 환경별 백업·삭제 이력·멱등 정보의 구체적인 보존·파기 계약

AI 에이전트는 위 항목을
명시적인 근거 없이 임의로 확정하지 않습니다.

확정된 설계 결정은 관련 문서와 테스트에 반영합니다.

---

## 19. 참고 문서

- [Spring Boot Documentation](https://docs.spring.io/spring-boot/)
- [Spring Framework Reference](https://docs.spring.io/spring-framework/reference/)
- [Spring Data JPA](https://docs.spring.io/spring-data/jpa/reference/)
- [Spring Security](https://docs.spring.io/spring-security/reference/)
- [Hibernate ORM](https://hibernate.org/orm/documentation/)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
- [Flyway Documentation](https://documentation.red-gate.com/fd)
- [Testcontainers](https://java.testcontainers.org/)
- [RFC 9457 — Problem Details](https://www.rfc-editor.org/rfc/rfc9457.html)

---

## Final Principle

Overload 백엔드는 단순히 요청을 처리하는 서버가 아니라,
사용자의 운동 데이터를 안전하고 정확하게 관리하는 시스템입니다.

모든 구현은 다음을 우선합니다.

**Data Integrity → Security → Correctness → Maintainability → Performance**

AI 에이전트는 변경 범위를 이해하고,
도메인 규칙을 준수하며,
검증 가능한 결과를 제공해야 합니다.
