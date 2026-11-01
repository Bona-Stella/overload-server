# 표준 운동 카탈로그 — Backend 1단계 구현·검증 보고서

작성일: 2026-11-02 (Asia/Seoul).

## 1. 결과와 범위

표준 카탈로그5표를 추가하는 V16, JPA Entity/Repository, 표준 운동 검색 데이터·제조사 조회를 구현했다. 기존 인증·read 오류·no-store 및 workoutTransactionManager를 재사용했다. 루틴·세션의 운동 식별 FK, 수행·계획/평가, 개인 종목 CRUD/20개 가입 seed, Frontend·IndexedDB는 변경하지 않았다.

기준 HEAD는 Backend `be73b2201824058020ddcfa11798466d88663d94`, Frontend `984899c2d5545483026b6014a0361e91a18ade96`다. 시작 시 Backend에는 이전 작업의 `standard-exercise-catalog-design-analysis.md`만 untracked였고 Frontend는 clean이었다. 기존 보고서를 덮어쓰지 않았다.

최종 설계의 ‘신규5표+기존 FK 전환을 한 migration으로’ 구상은 **이번 사용자 지시에 따라 단계 분리**했다. V16은 신규 카탈로그만 추가하고 FK/계약 전환은 후속2단계다. 기존 V1~V15 원문15개는 HEAD와 hash가 모두 같음을 확인했다.

## 2. 변경 파일

아래 경로는 Backend 저장소 기준이다. 신규 제품 코드는 `src/main/java/com/overload/overload_server/catalog/`에 모았다.

| 구분 | 실제 파일 | 내용 |
| --- | --- | --- |
| Migration | `src/main/resources/db/migration/V16__create_standard_exercise_catalog.sql` | 빈5표·PK/FK/CHECK, 데이터 INSERT·기존 표 ALTER 없음 |
| Entity5개 | `catalog/StandardExercise.java`, `ExerciseAlias.java`, `Muscle.java`, `ExerciseMuscle.java`, `Manufacturer.java` | 이름·활성·시각 또는 최소 연결 필드. 복합키는 두 Entity의 static Key + IdClass |
| Repository5개 | `catalog/StandardExerciseRepository.java`, `ExerciseAliasRepository.java`, `MuscleRepository.java`, `ExerciseMuscleRepository.java`, `ManufacturerRepository.java` | 모두 JpaRepository. 활성 목록 파생 조회·별칭 JPQL |
| 조회 경계 | `catalog/CatalogReadController.java`, `CatalogReadService.java`, `CatalogResponses.java` | 두 GET·가벼운 DTO·일관된 읽기 트랜잭션 |
| JPA 등록 | `persistence/WorkoutPersistenceConfiguration.java` | 기존 명시적 Repository 탐색에 catalog 패키지만 추가 |
| 읽기 오류 | `workout/WorkoutReadErrors.java` | 기존 오류 처리 적용 대상을 CatalogReadController까지 확대. 기존 응답 의미 유지 |
| 신규 단위 테스트 | `src/test/java/com/overload/overload_server/catalog/ExerciseAliasTests.java` | NFKC·Unicode 공백·소문자·비공백/NUL 검증6개 |
| 신규 HTTP/JPA/DB 테스트 | `src/test/java/com/overload/overload_server/catalog/CatalogIntegrationTests.java` | 실제 OTP/쿠키·표준/별칭/제조사·복합키/역할·오류·일관성12개 |
| 신규 migration 테스트 | `src/test/java/com/overload/overload_server/database/V16MigrationIntegrationTests.java` | 빈V15→V16·기존 표 구조/제약/인덱스/trigger/history 불변1개 |
| 기존 최신 스키마 기대값 | `database/PostgresMigrationTests.java`, `database/V9MigrationCompatibilityTests.java`, `database/V10MigrationIntegrationTests.java`, `persistence/JpaJdbcCompatibilityIntegrationTests.java`, `auth/email/EmailOtpIntegrationTests.java`（각 파일은 `src/test/java/com/overload/overload_server/` 아래） | 최신 버전/기동 target16·전체 표 목록/이력16으로 확장. 기존 체크섬·데이터 검증 유지 |
| 과거 단계 테스트 고정 | `src/test/java/com/overload/overload_server/database/V15MigrationIntegrationTests.java` | V14→V15 전용 테스트의 target15를 명시. 최신 V16 검증은 신규 테스트 담당 |
| 테스트 인프라 기대값 | `src/test/java/com/overload/testfixture/MixedPersistenceAssertions.java`, `RequestIdHttpTests.java`, `src/test/java/com/overload/overload_server/OverloadServerApplicationTests.java` | 정확한 Entity 목록에5개 추가, DB 없는 context에 카탈로그 service mock 추가 |
| 문서 | `docs/api/standard-exercise-catalog.md`, 이 보고서 | 현행 API 계약·구현/검증 결과 |

기존 스키마 검사·전체 Entity 목록을 느슨한 contains 검사로 바꾸거나 테스트를 삭제하지 않았다. latest version을 가정한 값을16으로 늘리고 V16 이력까지 정확히 검사한다.

## 3. 신규 DB 관계

```text
standard_exercise ──< exercise_alias
standard_exercise ──< exercise_muscle >── muscle
manufacturer (운동과 독립)

기존 exercise_definitions → routine_weight_placements / session_exercises 유지
```

- standard_exercise / manufacturer: UUID PK, 비공백 이름, active 기본true, created_at/updated_at TIMESTAMPTZ 기본 CURRENT_TIMESTAMP. 이름 UNIQUE 없음.
- exercise_alias: `(standard_exercise_id,normalized_alias)` 복합PK, 표준 FK, 원문/정규화 별칭 비공백. 같은 별칭을 다른 표준 UUID에 등록 가능.
- muscle: UUID PK, 비공백 세부명, active 기본true, CHEST/BACK/SHOULDERS/ARMS/LEGS/CORE CHECK.
- exercise_muscle: `(standard_exercise_id,muscle_id)` 복합PK·두 FK, PRIMARY/SECONDARY CHECK. 복수 PRIMARY 허용, 가중치 컬럼 없음.
- 카탈로그 PK 인덱스5개·FK3개만 추가한다. CASCADE DELETE·검색 인덱스·캐시·감사 표 없음.

JPA는 실제 사용하지 않는 양방향 연관관계 없이 UUID 필드로 매핑한다. 별칭 factory는 NFKC→Unicode 공백 정리→Locale.ROOT 소문자화를 적용한다. 직접 관리 SQL의 normalized_alias는 등록자가 같은 규칙으로 공급해야 하며, 비공백 CHECK가 정규화를 대신한다고 보장하지 않는다.

실제 운영 카탈로그·제조사·근육 데이터를 확정하거나 INSERT하지 않았다. 카탈로그가 빈 상태로 Flyway/Hibernate 검증과 앱 기동이 가능하다. 테스트 fixture의 벤치프레스/불가리안 스플릿 스쿼트/하이로우 등은 운영 seed가 아니다.

## 4. API·응답

[상세 계약](../api/standard-exercise-catalog.md).

`GET /api/v1/standard-exercises/search-data`

```json
{"schemaVersion":1,"exercises":[{"id":"11111111-1111-4111-8111-111111111111","name":"불가리안 스플릿 스쿼트","aliases":["bulgarian split squat","불스스"]}]}
```

`GET /api/v1/manufacturers`

```json
{"manufacturers":[{"id":"22222222-2222-4222-8222-222222222222","name":"파나타"}]}
```

UUID는 예시다. 활성 운동/제조사만 UUID 오름차순, 별칭은 정규화 키 오름차순으로 반환한다. 운동마다 한 항목이며 이름이 같은 다른 UUID는 별개다. 별칭이 없으면 aliases=[], 데이터가 없으면 각 목록=[]다. 개인 정의·사용자 소유권 필드·미디어·근육 매핑을 응답에 섞지 않는다.

검색은 read-only REPEATABLE_READ의 JPA 조회2개로 조합하고 제조사는 단일 조회다. 별칭 조회 사이에 독립 연결로 활성/별칭을 변경해도 같은 스냅샷으로 읽는 테스트를 통과했다. EntityManager/JdbcTemplate를 신규 제품 조회에 직접 사용하지 않는다.

기존 세션 인증·no-store·X-Request-ID·Security 헤더 유지. query/GET body는400 INVALID_REQUEST, 익명/만료401, 권한403, 일시 DB장애503 WORKOUT_DATA_UNAVAILABLE, 예상 밖 SQL/매핑500 WORKOUT_DATA_READ_FAILED다. Security 예외는 기존 핸들러로 전달하고 SQL/예외 세부를 노출하지 않는다. 관리자 POST/PATCH/DELETE·서버 검색어 API·서버 캐시는 추가하지 않았다.

## 5. 검증

검증은 개인 .env를 읽지 않는 기존 테스트 classpath·Testcontainers 패턴을 재사용했다. 신규 fixture는 DB명과 mapped port가5432/5433이 아님을 검사하고 PostgreSQL18.6-alpine의 일회성 DB만 사용했다. 개인 overload_dev에 접속/적용하지 않았다.

| 실행 | 결과 |
| --- | --- |
| `gradlew --offline --no-daemon test` | 213개 통과, 실패/오류/skip0 |
| 카탈로그·V16 선택 `integrationTest` | 13개 통과, 실패/오류/skip0 |
| 기존 루틴·세션 및 영향 테스트 선택 `integrationTest` | 237개 통과, 실패/오류/skip0 |
| `bootJar` | BUILD SUCCESSFUL, 실행 JAR 생성 |
| 원본 V1~V15 비교 | 15개 모두 HEAD와 동일 |

실행 명령:

```powershell
$env:JAVA_HOME='C:\Users\Home\.jdks\temurin-25.0.5'
.\gradlew.bat --offline --no-daemon test
.\gradlew.bat --offline --no-daemon integrationTest --tests '*CatalogIntegrationTests' --tests '*V16MigrationIntegrationTests'
.\gradlew.bat --offline --no-daemon integrationTest --tests '*PostgresMigrationTests' --tests '*V9MigrationCompatibilityTests' --tests '*V10MigrationIntegrationTests' --tests '*V15MigrationIntegrationTests' --tests '*JpaJdbcCompatibilityIntegrationTests' --tests '*WorkoutReadIntegrationTests' --tests '*WorkoutWriteIntegrationTests' --tests '*RoutineIntegrationTests' --tests '*WorkoutSessionIntegrationTests' --tests '*EmailOtpIntegrationTests.schemaHasDurableConstraintsAndIndexesAndMigrationIsNotRepeated' bootJar
```

전체 PostgreSQL 통합 테스트·Frontend 테스트·실제 브라우저 재검증을 실행했다고 주장하지 않는다. 변경 범위에 맞춰 새 기능과 기존 루틴/세션·영향받은 기동/스키마 검사를 선택했다.

기존 회귀237개의 상세 수는 루틴23, 세션105, 운동 조회22, 개인 종목/휴식 쓰기52, JPA/JDBC17, 전체 스키마/과거 업그레이드10, V10 5, V9 1, V15 1, 이메일OTP 스키마1이다. 신규 카탈로그13개와 합쳐 PostgreSQL 통합250개, 단위213개로 **실행한 테스트 총463개가 모두 통과**했다. 테스트 XML 기준 실패/오류/skip은0이다.

수정한 검증 오류:

- 처음 컴파일에서 복합키 내부 클래스 이름 Id가 JPA @Id 이름을 가렸다. 내부 클래스를 Key로 바꿔 해결하고 단위/통합 컴파일을 통과했다.
- 기본 sandbox에서 Gradle 사용자 캐시 잠금과 Docker pipe 접근이 거부되어 기존 캐시·격리 Docker 테스트에 필요한 실행 권한으로 재실행했다. 의존성 설치나 개인 DB 사용으로 우회하지 않았다.
- 일괄 편집용 python 명령이 설치되지 않아 실행되지 않았다. 실제 수정은 apply_patch/PowerShell로 수행했다.

## 6. 다음 단계에서 바꿀 위치

후속2단계의 설계 대상이며 이번에 수정하지 않았다.

| 대상 | 다음 변경 |
| --- | --- |
| routine_weight_placements / session_exercises | 개인 정의 FK·컬럼을 standard_exercise_id로 단일 교체. V16은 그대로 두고 다음 미사용 migration에서 수행 |
| performed_sets | 당시 제조사/부하 컬럼·불변 수행 문맥 추가 |
| RoutineRequests/Responses, RoutineWeightPlacement, RoutineService/Repository | 표준 운동 조회·장비 명시 편집, 같은 배치/승인 중량 유지 |
| InitialPlan, WorkoutSnapshotData/Requests/Service/Repository, 관련 Entity | 표준 UUID·계획/수행 장비·단일 새 문서 계약·기존 잠금/원자성 유지 |
| WorkoutAssessment, WorkoutRecordData/Requests/Service | 운동 식별 필드 변경·정정/복구 장비 보존. 공식 비교에 장비 추가 금지 |
| AccountRepository/AccountSeed, WorkoutWriteController | 개인20개 seed 중단·일반 개인 생성/수정 차단 |
| Frontend | 검색/제조사 선택·단일 새 IndexedDB 문서/일회 테스트 데이터 전환·PWA 경계 |

표준을 루틴에 연결하거나 새 세션 계약을 열기 전에2단계를 함께 검증한다. 현재는 기존 Task009 경로가 계속 개인 정의 기반으로 동작하는 것이 정상이다.

## 7. 미해결·Git·정리

미해결 제품 오류는 없다. 이번 단계의 제한으로 표준 운동은 아직 루틴·세션에서 선택할 수 없고, 개인 종목 생성·가입seed도 계속 동작한다. 실제 카탈로그 등록·개인DB V16 적용·Frontend 연결·후속FK/수행/JSON 전환은 남은 작업이다. 서버 API의 정상 동작은 격리 PostgreSQL·실제 HTTP/세션으로 확인했으며 실제 브라우저 UI 검증은 이번 범위가 아니다.

Backend HEAD·Frontend HEAD는 시작값을 유지한다. Frontend는 clean, staging은 없다. 이번 변경은 기존 추적 파일11개 수정·신규19개이며, 이전 작업의 untracked 설계 보고서1개는 그대로 별도 존재한다. 새 파일을 포함한 공백 검사와 문서 링크/Markdown 구조를 점검했다. 기존 migration15개는 원문 동일하다.

Gradle 실행 세션은 모두 정상 종료했고 각 명령은 --no-daemon을 사용했다. 최종 프로세스 조회에서도 Gradle daemon/test worker/Backend 프로세스가 남아 있지 않음을 확인했다. Testcontainers/Ryuk·임시 PostgreSQL 실행 컨테이너는0개이며, 시작 시 존재한 사용자 컨테이너5개가 같은 ID로 유지됨을 확인했다. 기존 프로세스/컨테이너를 종료하지 않았다.

사용자가 승인한 신규 migration 작성·격리 DB 적용만 수행했다. 개인 DB migration/초기화·Frontend 변경·새 의존성·관리 UI/ROLE_ADMIN·commit/push는 실행하지 않았다. 기존 untracked 설계 보고서는 이번 변경과 구분한다.
