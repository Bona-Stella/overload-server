# Task 008-0C — JPA/JDBC 런타임 호환성 검증

- 작성일: 2026-10-30 (Asia/Seoul)
- 시작 Git: server `main/7eb3ef7`, pwa `main/1c84d23`, 양쪽 clean.
- 상태: **TX-01~TX-08 및 기존 인증 회귀 통과 / 전체 build 성공 / 리뷰 전 미커밋**.
- 기준: [JPA 설계](task-008-0-jpa-persistence-design.md), [도메인·API 계약](task-008-0-domain-and-api-contract-v0.1.md), [공통 HTTP 계약](task-008-0d-common-api-contract-v0.1.md), [Task 006](task-006-integrated-design-v0.3.1.md).

## 1. 적용 범위

Boot 4.1.2 BOM의 `spring-boot-starter-data-jpa`만 추가했다. 개별 ORM 버전 override, 별도 DataSource/pool, 테스트 프레임워크는 추가하지 않았다. 최종 bootJar의 실제 포함 라이브러리로 Spring Data JPA/Commons 4.1.2, Hibernate 7.4.13.Final, Spring ORM 7.0.10, Jakarta Persistence 3.2.0, Session JDBC 4.1.1을 확인했다.

별도 Gradle dependencies 조회는 자동 승인 검토의 사용량 한도 오류로 실행되지 않았다(안전성 거부가 아님). 추가 실행 없이 이미 성공한 build의 `build/libs/overload-server-0.0.1-SNAPSHOT.jar`를 `jar tf`로 읽어 실제 패키징 버전을 확인했다. AutoConfiguration imports는 실행 JAR의 `META-INF/spring/`에 있으며 구성 class는 BOOT-INF/classes에 포함된다. 테스트 fixture와 테스트용 local overlay는 제품 패키지에 포함되지 않는다.

기존 V2의 ExerciseDefinition/UserWorkoutSettings만 매핑한다. UUID/사용자 PK/data_generation/TEXT/INTEGER/TIMESTAMPTZ를 유지하며, User/identity를 JPA로 전환하지 않는다. 연관관계·cascade·version/deleted_at·ID 생성기·자동 시각 갱신 callback은 추가하지 않았다. 읽기 Repository는 owner+generation 메서드만 제공한다. 운동 Controller·REST API·루틴·세션·세트 저장 서비스는 없다.

`spring.jpa.hibernate.ddl-auto=validate`, `generate-ddl=false`, `open-in-view=false`다. Flyway와 Session의 기존 DDL 설정은 유지했다. V1~V8은 변경하지 않았다.

## 2. 트랜잭션 구성

`WorkoutPersistenceConfiguration`은 DataSource 자동 구성 이후, 기본 JDBC/JPA 관리자와 Repository 자동 구성 이전에 적용하는 Boot AutoConfiguration이다. 이 순서를 imports 메타데이터로 명시했다. 일반 컴포넌트 스캔 순서에 따라 manager 선택이 달라지지 않으며, DataSource가 없는 기존 DB 비사용 테스트 컨텍스트에는 적용하지 않는다.

- `transactionManager`: primary JDBC 관리자, SpringSessionTransactionManager로 지정. 기존 unqualified 인증 생성자·TransactionTemplate은 이를 선택한다.
- Boot의 `spring.dao.exceptiontranslation.enabled` 기본 true이면 JdbcTransactionManager, false이면 DataSourceTransactionManager를 사용하는 기존 동작과 customizers를 유지한다.
- `workoutTransactionManager`: 같은 DataSource와 EMF의 JpaTransactionManager. 운동 Repository의 transactionManagerRef와 readOnly annotation에 명시한다.
- Spring Session JDBC의 기본 REQUIRES_NEW는 변경하지 않았다. SessionRepository를 운동 JPA tx에 중첩하는 구조는 도입하지 않는다.
- 운동 JPA tx의 직접 JDBC는 동일 연결에 참여한다. 두 관리자 간 자유 중첩·분산 원자성을 보장한다고 주장하지 않는다. 인증 SQL·잠금 순서·쿠키·CSRF·수명·Request ID 코드는 변경하지 않았다.

## 3. 테스트 격리

실행은 Testcontainers PostgreSQL 18.6의 테스트 전용 빈 DB·임의 포트·임의 비밀번호와 합성 계정만 사용한다. 기존 Compose/개발 DB는 접속하지 않는다. 실행 전 상속된 Spring/메일/OAuth/DB 환경 설정은 실행 프로세스에서 제거했다. JDK는 기존 Temurin 25.0.4.1을 사용했다.

테스트 전용 `application-local.yaml`이 main의 동명 local 파일을 가린다. 테스트에서는 개발 `.env` import가 없으며 기존 HTTP local cookie 설정만 유지한다. 혼합 컨텍스트 검사에서도 property source에 `.env`가 없음을 확인한다. 운영 local 설정을 수정한 것은 아니다.

모든 기존 PostgreSQL 회귀의 앱 시작 경로에 `MixedPersistenceAssertions`를 추가했다. DataSource 한 개·명시된 두 manager·JPA EMF·정확히 두 Entity·validate/OSIV 설정을 확인하므로, JPA를 끈 인증 테스트 통과를 혼용 성공으로 세지 않는다. 기존 DB 없는 단위/Request ID HTTP 테스트는 그대로 유지한다.

신규 호환성 테스트의 시험용 DDL은 새 컨테이너의 기존 테이블 컬럼 일시 rename과 실패 trigger/function뿐이다. 기존 인증/스키마 회귀의 전용 시험 행·감사/참조 표도 기존대로 그 일회성 DB에서만 사용했다. 제품 migration이나 신규 운동 도메인 테이블을 만들지 않는다. 실패 시 finally/AfterEach로 원복하고 컨테이너를 정리한다. schema validation 실패가 스키마를 자동 복구하지 않는지도 검사한다.

## 4. TX-01~TX-08 결과

새 상세 검증 `JpaJdbcCompatibilityIntegrationTests`는 **17개 통과**했다. 기존 OAuth/OTP 경합은 실제 혼합 설정의 회귀를 재사용했다. 테스트 전용 관찰기는 실제 Session TransactionOperations와 Hibernate statement 실행을 관측하며 인증/DB 동작을 대체하지 않는다.

| 게이트 | 실제 검증·근거 | 결과 |
|---|---|---|
| TX-01 | DataSource 1개, EMF와 양 manager가 같은 DataSource, unqualified primary JDBC·계정 TransactionTemplate 선택, 실제 Repository SQL 실행 중 JPA tx binding, 예외 변환 false 설정 보존. 추가로 JPA readOnly REPEATABLE_READ의 실제 PostgreSQL isolation과 JDBC 연결 공유 | PASS |
| TX-02 | JDBC 가입 20종목·1설정의 JPA 조회, 원 UUID/세대/편집값·시각 보존, 계정·세대 조회 조건, 2^53 초과 세대와 microsecond 시각, 재시작 전후 행·V1~V8 checksum 불변. 누락 컬럼을 Hibernate가 거부하고 자동 복구하지 않음 | PASS |
| TX-03 | Hibernate doReturningWork/JdbcTemplate의 backend PID·txid 일치. 두 쓰기 순서의 커밋과 애플리케이션 예외 롤백, JPA commit-time 제약 실패 시 선행 JDBC 쓰기도 롤백 | PASS |
| TX-04 | flush 전 JDBC는 이전값, flush 후 변경값. JDBC/native 갱신 후 managed Entity가 stale임을 확인하고 refresh/clear·재조회. JDBC unique 실패 후 전체 롤백 및 새 tx 복구 | PASS |
| TX-05 | 실제 AccountProvisioning REQUIRES_NEW의 독립 커밋, 바깥 JDBC 롤백·연결 재개, seed 실패의 내부 전체 롤백과 바깥 tx 정상 커밋. 기존 계정 경쟁/seed 롤백 회귀 11개도 통과 | PASS |
| TX-06 | 실제 Session 저장소의 TransactionOperations 안에서 JDBC manager/REQUIRES_NEW·별도 backend PID 관측. 바깥 readOnly와 무관한 쓰기·외부 롤백 뒤 session 보존, 독립 삭제 뒤 부활 없음 | PASS |
| TX-07 | 혼합 GoogleOidc 회귀 40개: 같은 state callback 경쟁의 단일 소비·단일 exchange, provider의 독립 연결에서 pending 소비 커밋 확인, 1-connection pool 상태 소비·재시작·실패 회귀. Google REAUTH/CONTACT/LINK도 전체 회귀 | PASS |
| TX-08 | 실제 EMAIL OTP 로그인→재인증. OTP 소비 단계의 SQL 실패 시 신규 session/소비 함께 롤백·구 session 유지, 재시도 성공 시 OTP 소비와 session 회전/절대 만료 보존. 기존 EMAIL 재인증 40개에서 attributes/consume 양 실패·동시 검증·응답 직전 검증도 통과 | PASS |

Request ID의 추가 실제 JDBC 검증: HTTP CSRF·OTP·재인증·session·logout 응답에서 서버 UUID 헤더를 확인했다. 외부 X-Request-ID를 그대로 채택하지 않으며, Session 저장소의 실제 JDBC tx 안에서 해당 응답 ID와 같은 MDC를 관측했다. 그 경로의 JPA tx binding은 없었다. 204 빈 본문·no-store·같은 계정·구 session 삭제·로그아웃 뒤 401도 유지됐다. Request ID 제품 코드를 수정하지 않았다.

## 5. 실행·수정 이력

1. JPA 최소 설정 컴파일과 기존 DB 비사용 컨텍스트/Request ID HTTP 테스트 실행 성공.
2. 새 테스트 컴파일에서 AssertJ 제네릭 추론 두 오류 발생. SessionMetadata 명시 타입과 Entity Java 타입 이름 비교로 수정했다.
3. 첫 PostgreSQL 대상 실행: 96개 중 95개 통과. 누락 컬럼의 Hibernate 거부는 정상 발생했으나 검사 문자열 `Schema-validation`이 실제 `Schema validation`과 달라 한 assertion 실패. root SchemaManagementException 타입과 누락 컬럼 유지 검사로 수정했다.
4. 최종 `gradlew.bat --offline --no-daemon build`는 **BUILD SUCCESSFUL, 3분 51초**. test/integrationTest가 실제 실행됐고 bootJar/jar/check/build가 성공했다. 검사를 비활성화하거나 pool 확대만으로 우회하지 않았다.

### 5.1 최종 JUnit XML 집계

| 범위 / 클래스 | tests | 실패·오류·skipped |
|---|---:|---:|
| DB 비사용 단위/컨텍스트/HTTP 전체 | 112 | 0 / 0 / 0 |
| AccountProvisioningIntegrationTests | 11 | 0 / 0 / 0 |
| EmailOtpIntegrationTests | 27 | 0 / 0 / 0 |
| EmailReauthenticationIntegrationTests | 40 | 0 / 0 / 0 |
| CommonReauthenticationEmailIntegrationTests | 35 | 0 / 0 / 0 |
| EmailIdentityIntegrationTests | 34 | 0 / 0 / 0 |
| GoogleContactFrontendIntegrationTests | 8 | 0 / 0 / 0 |
| GoogleIdentityIntegrationTests | 56 | 0 / 0 / 0 |
| GoogleLinkFrontendIntegrationTests | 11 | 0 / 0 / 0 |
| GoogleOidcIntegrationTests | 40 | 0 / 0 / 0 |
| GoogleReauthenticationIntegrationTests | 64 | 0 / 0 / 0 |
| PostgresMigrationTests | 10 | 0 / 0 / 0 |
| SecuritySessionIntegrationTests | 10 | 0 / 0 / 0 |
| JpaJdbcCompatibilityIntegrationTests (신규) | 17 | 0 / 0 / 0 |
| **PostgreSQL 합계** | **363** | **0 / 0 / 0** |
| **전체 합계** | **475** | **0 / 0 / 0** |

기존 PostgreSQL 회귀 346개를 전부 다시 실행했고, 새 17개가 더해졌다. XML은 `build/test-results/test/`와 `build/test-results/integrationTest/`, HTML은 `build/reports/tests/`에 있다. 이전 보관 결과를 이번 PASS로 재사용하지 않았다. 기존 unchecked 컴파일 경고와 JVM CDS 안내는 있으나 최종 컴파일·테스트·빌드 오류는 없다.

## 6. 변경 파일·검증 한계

변경은 build.gradle, application.yaml, 두 Entity/읽기 Repository, manager AutoConfiguration/imports, 테스트 local 설정·관찰/검사 fixture·호환성 테스트, 기존 PostgreSQL 테스트의 혼합 컨텍스트 확인, 관련 도입 상태 문서와 이 보고서다. 기존 인증/보안/Request ID 제품 코드, SQL migration, 프론트는 불변이다.

주요 소스: [manager 설정](../../src/main/java/com/overload/overload_server/persistence/WorkoutPersistenceConfiguration.java), ExerciseDefinition (당시 Backend `src/main/java/com/overload/overload_server/exercise/ExerciseDefinition.java`, `b6f0478^`의 Git 트리), [UserWorkoutSettings](../../src/main/java/com/overload/overload_server/workoutsettings/UserWorkoutSettings.java), [새 호환성 테스트](../../src/test/java/com/overload/overload_server/persistence/JpaJdbcCompatibilityIntegrationTests.java), [모든 회귀의 혼합 구성 검사](../../src/test/java/com/overload/testfixture/MixedPersistenceAssertions.java), [실제 트랜잭션 관찰기](../../src/test/java/com/overload/testfixture/JpaCompatibilityFixtures.java).

검증 후 Testcontainers/Ryuk가 실행 중 목록에 없고, 기존 사용자 컨테이너 5개가 같은 ID로 유지됨을 확인했다. 테스트 JVM·단발 Gradle daemon 종료도 확인했다. 시작 전의 Node 프로세스 5개는 같은 PID로 유지됐다. 사용자 컨테이너/프로세스는 종료하지 않았다. 일반 Git diff 공백 검사와 새 파일의 공백·비밀값 검사를 수행했다. 기존 CRLF 설정에 따른 변환 안내가 있으며, 설정을 변경하거나 파일 전체를 재포맷하지 않았다.

008-1 GET API·새 운동 테이블·실사용 계정·실제 메일/OAuth·설치형 PWA 검증은 이번 범위 밖이다. 새 영수증/epoch/version/공식 과부하 평가를 구현·검증했다고 해석하지 않는다. 코드·테스트 변경은 리뷰 전 Commit하지 않으며 Push하지 않는다.

핵심 혼용을 막는 미해결 실패는 없다. 다만 두 manager 간 자유로운 중첩이나 JPA tx 안 SessionRepository 호출은 보장하지 않는 설계 경계다. 실제 개발 DB 연결·기존 계정 로그인 LIVE·독립 실행 JAR의 DB 부팅은 실행하지 않았다. 패키징은 성공한 bootJar의 포함 항목만 읽어 확인했다. 008-1 API는 진행하지 않았으며 후속 승인/리뷰 대상이다.

최종 Git: server main/7eb3ef7에 기존 추적 파일 수정17개·신규11개(총28개)가 미스테이징 상태다. pwa main/1c84d23은 clean이며 변경하지 않았다. Commit/Push는 수행하지 않았다.
