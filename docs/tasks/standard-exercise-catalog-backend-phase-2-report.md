# 표준 운동 카탈로그 — Backend 2단계 구현·검증 보고서

작성일: 2026-11-02 (Asia/Seoul).

## 1. 결과와 경계

기존 `/api/v1/routines`, `/api/v1/workout-sessions`의 WEIGHT 식별을 `standardExerciseId` 하나로 전환했다. InitialPlan·AppliedPlan·CompletionAssessment는 schemaVersion=2만 생성/사용한다. 개인 정의와 표준의 이중 식별, API v2, 과거 JSON codec이나 자동 데이터 이관은 없다.

루틴·세션 장비 설정과 완료 당시 장비 사실을 분리했다. 제조사·부하는 공식 비교 Context에 포함하지 않으며, 승인값 자동 초기화·TODAY_ONLY 강제·새 승인 사건을 만들지 않는다. 기존 Task009 잠금·version·승인·FINISH·AUTO_CLOSED·정정/복구 흐름을 보존했다.

실제 개인 `overload_dev`에는 접속·migration·삭제·초기화를 하지 않았다. Frontend 제품 코드·IndexedDB·V1~V16·의존성·commit/push도 변경하지 않았다. 기존 Frontend와 새 Backend는 아직 서로 다른 계약이므로 통합 서비스 완료 상태가 아니다.

## 2. 변경 파일

경로는 `overload-server` 저장소 기준이다.

| 구분 | 파일 | 변경 |
| --- | --- | --- |
| 신규 migration | `src/main/resources/db/migration/V17__connect_standard_exercises_to_workouts.sql` | 빈 운동 도메인 전제·표준 FK 교체·장비 컬럼/FK/CHECK·JSON schema2·수행 장비 불변 trigger |
| 루틴 | `src/main/java/com/overload/overload_server/routine/RoutineRequests.java`, `RoutineResponses.java`, `RoutineWeightPlacement.java`, `RoutineRepository.java`, `RoutineService.java` | 표준 운동 조회·필수 nullable 장비·같은 배치/승인 중량·실제 변경/no-op |
| 세션 계약 | `src/main/java/com/overload/overload_server/workoutsession/InitialPlan.java`, `WorkoutSnapshotData.java`, `WorkoutSnapshotRequests.java` | 단일 schema2·표준 UUID·계획/수행 장비·엄격한 키/허용값 |
| 세션 저장 | 같은 패키지의 `WorkoutSnapshotEntities.java`, `WorkoutSnapshotRepository.java`, `WorkoutSnapshotService.java` | 표준 registry·장비 원본 저장·장비 포함 hash/동등성·제조사 존재 검증·기존 세트 장비 변경 차단 |
| 평가/복구 | 같은 패키지의 `WorkoutAssessment.java`, `WorkoutRecordService.java` | 공식 키의 운동 필드만 교체·최초/현재 평가 schema2·정정/복구/감사 장비 보존 |
| 가입/접근 | `src/main/java/com/overload/overload_server/account/AccountRepository.java`, `AccountSeed.java`, `security/SecurityConfiguration.java` | 가입 개인20개 INSERT 중단·역사적 이름 상수 설명·일반 개인 POST/PATCH denyAll |
| 신규 검증 | `src/test/java/com/overload/overload_server/database/V17MigrationIntegrationTests.java`, `src/test/java/com/overload/testfixture/CatalogFixtures.java` | V16→V17·데이터 존재 시 무손실 실패·제약/불변성·명시적 격리 카탈로그/개인 fixture |
| 주요 회귀 | 테스트의 `workout/RoutineIntegrationTests.java`, `workoutsession/WorkoutSessionIntegrationTests.java`, `WorkoutAssessmentTests.java`, `WorkoutSnapshotRequestsTests.java`, `WorkoutRecordRequestsTests.java`, `routine/RoutineRequestsTests.java` | 기존 검증 유지·표준 fixture·장비/식별/재전송/복구 검증 추가 |
| 개인 CRUD/조회/영속성 | 테스트의 `workout/WorkoutWriteIntegrationTests.java`, `WorkoutReadIntegrationTests.java`, `persistence/JpaJdbcCompatibilityIntegrationTests.java` | 기존 내부 개인 도메인 테스트 명시 fixture·제품403 검증·혼합 JPA/JDBC 검증 유지 |
| 가입/최신 스키마 기대값 | 테스트의 `account/AccountProvisioningIntegrationTests.java`, `auth/email/EmailOtpIntegrationTests.java`, `auth/google/GoogleOidcIntegrationTests.java`, `database/PostgresMigrationTests.java`, `V9MigrationCompatibilityTests.java`, `V10MigrationIntegrationTests.java`, `V16MigrationIntegrationTests.java`, `catalog/CatalogIntegrationTests.java`, `security/SecuritySessionIntegrationTests.java` | 가입0개·최신V17·과거V16 migration 테스트 target 고정·기존 개인 데이터 보존 검증 |
| 계약 문서 | `docs/api/routine-management.md`, `workout-sessions.md`, `workout-online-write.md`, `standard-exercise-catalog.md`, `AGENTS.md` | 현재 표준 계약·장비·403·적용 경계·후속 Frontend 상태 |

개인 CRUD 구현은 제거하지 않았다. 기존 CRUD HTTP 형식/트랜잭션 회귀는 `WorkoutWriteIntegrationTests`에만 명시 등록되는 test-only Controller subclass의 별도 경로로 유지했다. 제품 경로의403은 별도 테스트한다. 테스트 클래스는 제품 JAR에 포함되지 않으며 제품에 우회 경로를 추가하지 않았다. 개인 GET/Repository 검증에서 필요한 과거 정의는 테스트가 직접 INSERT한다. 가입 seed를 테스트용으로 재개방하지 않는다.

## 3. V17 데이터 무결성

1. 관련 표에 ACCESS EXCLUSIVE 잠금을 잡은 뒤 routines/workout_sessions가 비어 있는지 검증한다. 데이터가 있으면 migration 전체를 중단한다. TRUNCATE·DELETE·이름 매핑·UUID 재해석이 없다. 사용자·신원·기존 개인 정의는 변경하지 않는다.
2. pg_constraint에서 정확히 두 개인 정의 FK만 찾아 교체한다. 소유자·세대·루틴·세션 복합 FK, 배치/registry UNIQUE와 ACTIVE/ordinal 부분 UNIQUE는 유지한다.
3. routine_weight_placements/session_exercises 컬럼을 standard_exercise_id로 rename하고 standard_exercise(id) FK를 설정한다. WEIGHT 필수/CARDIO null인 기존 CHECK는 rename을 따라 유지한다.
4. 루틴/수행의 manufacturer_id nullable FK, load_type nullable CHECK를 추가한다. 코드값은 PLATE/STACK/DUMBBELL/BODYWEIGHT뿐이다.
5. 기존 InitialPlan/AppliedPlan/closure 세 CHECK의 나머지 조건을 보존하면서 schema1을2로 교체한다. InitialPlan·종료 불변 trigger는 유지한다.
6. 수행 제조사·부하는 Entity updatable=false와 별도의 DB trigger로 생성 후 변경을 막는다. 수치 정정·논리 삭제는 계속 가능하다.

V1~V16 원문과 체크섬의 보존은 Git 원본 비교 및 Flyway 검증으로 확인한다. 실제 개인 DB 적용을 승인하거나 실행한 보고서가 아니다.

## 4. API 필드와 동작

| 대상 | 현재 계약 |
| --- | --- |
| Routine NEW | `{id,standardExerciseId,manufacturerId,loadType,setCount,minReps,maxReps,initialWeightKg}` |
| Routine EXISTING/RESTORE | 기존 mode/id/목표에 manufacturerId/loadType 필수 nullable. standardExerciseId/최초 중량 변경은 금지 |
| Routine 응답 | standardExerciseId/exerciseName/manufacturerId/loadType과 기존 계획·최초/승인 중량·deletedAt |
| InitialPlan(schema2) | 각 최초 웨이트에 standardExerciseId와 루틴의 manufacturerId/loadType 복사. START의 추가 입력 없음 |
| AppliedPlan(schema2) | 각 웨이트에 `{sessionExerciseId,setCount,minReps,maxReps,manufacturerId,loadType}` |
| registry 입력 | WEIGHT standardExerciseId 필수, CARDIO는null |
| SetValue / 복구 | 기존 완료 사실에 manufacturerId/loadType 필수 nullable |
| CompletionAssessment(schema2) | comparisonContext의 운동 필드만 standardExerciseId. ruleVersion=`task009-completion-v1` 유지 |
| 개인 POST/PATCH | 인증된 일반 사용자403 ACCESS_DENIED. 익명/만료401·CSRF403 유지 |

모든 장비 키는 누락과 미지정을 구별한다. NEW 루틴은 활성 표준/제조사를 선택한다. 기존 참조의 비활성화가 START·동일 저장·복구를 막지 않는다. snapshot/복구의 완료 사실 제조사는 존재 여부로 검사하여 오프라인에서 완료된 원본을 비활성만으로 거부하지 않는다. 없는 제조사는 루틴400 INVALID_REQUEST, 세션422 INVALID_SNAPSHOT이다.

루틴 장비 편집은 같은 배치·최초/승인 중량을 유지하고 실제 변경에만 부모 version을1회 올린다. 기존 expectedRoutineVersion/ownVersionPath 경합 규칙은 유지한다. 세션 장비 편집은 루틴을 수정하지 않는다.

완료 세트 장비를 최종 AppliedPlan과 일치시키지 않는다. 같은 전체 snapshot에 변경 전/후 사실이 함께 올 수 있다. 기존 세트 장비 변경은422 IMMUTABLE_RECORD_CONTEXT이며 같은 revision의 다른 장비 본문은409 REVISION_PAYLOAD_MISMATCH다. 정정은 장비를 입력받지 않고 유지하며 복구 중 동일 UUID 판정에도 원본 장비를 포함한다. UNVERIFIED 승인 주장도 장비 사실을 잃지 않는다.

감사 changeContext의 schemaVersion=1과 카탈로그 search-data의 schemaVersion=1은 각각 별도 형식이므로 유지한다. 구 계획 schema1 decoder를 유지한다는 의미가 아니다.

## 5. 검증

실행 명령(설치된 Java25·기존 Gradle 캐시 사용):

```powershell
$env:JAVA_HOME='C:\Users\Home\.jdks\temurin-25.0.5'
.\gradlew.bat --offline --no-daemon compileTestJava
.\gradlew.bat --offline --no-daemon test
.\gradlew.bat --offline --no-daemon build
```

단계별로 루틴/세션/카탈로그/개인 쓰기 및 V17·영향받은 가입/영속성/마이그레이션 테스트를 선택 실행한 뒤, 최종 `build`에서 전체 테스트를 실행했다. 중간 실행 횟수를 더해 테스트 수를 부풀리지 않는다.

| 최종 검증 | 결과 |
| --- | --- |
| Gradle build (test·integrationTest·check·bootJar 포함) | BUILD SUCCESSFUL, 5분53초 |
| test | 20 suite / 220개, 실패·오류·skip0 |
| integrationTest | 27 suite / 602개, 실패·오류·skip0 |
| 합계 | **822개 모두 통과** |
| 주요 통합 suite | 루틴26·세션111·개인 CRUD/403/휴식53·카탈로그12·V17 신규2 |
| V1~V16 원문 | 16개 모두 HEAD와 바이트 단위 동일 |
| 변경 공백/문서 링크 | git diff --check 통과, 수정 문서6개의 로컬 링크24개 유효 |
| Frontend 작업 트리 | clean |

검증 근거는 `build/test-results/test/TEST-*.xml`, `build/test-results/integrationTest/TEST-*.xml`이며 이 빌드 결과물은 커밋하지 않았다. Boot JAR도 생성했다. 설정된 별도 정적 분석/formatter 작업은 없으며 새 도구를 설치하지 않았다.

주요 보존/추가 검증:

- 빈V16→V17 성공·재실행 무변경·Flyway validate·현재 JPA/Hibernate 기동, 기존 migration 이력/소유권 FK/부분 UNIQUE/불변 trigger 보존. 데이터가 있는 V16은 삭제/DDL 없이 원자 실패한다.
- 표준 UUID 루틴 생성/중복 배치·다른 사용자와 같은 공용 표준 사용 시 승인/소유권 격리·개인 정의 UUID 거부·큰 세대/계획 수치·명시 복원·rollback·동시성.
- 제조사/부하 편집 시 동일 배치 UUID·최초/승인 중량 보존·승인 사건 없음·실제 변경에만 routine.version 증가·동일 값/no-op·비활성 기존 참조 보존.
- START의 장비 자동 복사·InitialPlan schema2 불변·진행 계획만 장비 변경·한 snapshot의 서로 다른 당시 장비 세트 저장·장비만 다른 동일 revision 충돌·동일 재송신·높은 무변경 revision의 기한 미연장.
- 기존 첫N개·원계획 완료·증량/유지/감량·승인 체인/PENDING·routine.version 경합·다른 장비의 과거 세션 비교 유지. 공식 Context에 장비를 넣지 않는다.
- 기존 writerEpoch/localRevision/takeover·단일ACTIVE·사용자/세대 잠금·snapshot 원자성·FINISH 재확인·최초 평가 봉인·AUTO_CLOSED·예약 작업/재기동·경합/DB 실패 회귀 유지.
- FINISHED/AUTO_CLOSED 정정·논리 삭제·복구에서 장비 보존·동일 UUID 장비 차이 거부·UNVERIFIED 복구 장비 보존·감사 전후 값·최초/현재 평가 분리.
- 개인 제품 POST/PATCH는 정상/불량 본문/존재·미존재 ID 모두403이며 미변경. 내부 개인 CRUD 검증과 기존 카탈로그 조회·휴식 설정·인증 전체 회귀도 유지한다.

중간에 발견하고 해결한 오류:

- RoutineResponses.Weight의 장비2필드 추가 후 START 구성자의 인자가 누락된 컴파일 오류를 수정했다.
- 이전 가입20개 seed에 기대던 fixture/검증을 명시 테스트 데이터 또는 가입0개 기대값으로 전환했다. 가입 SQL 실패/롤백 검증은 계속 생성되는 user_workout_settings에 실패를 주입한다. exercise_definitions의 실패 주입 사례는 그 표를 전혀 쓰지 않고 가입이 성공함을 검증한다.
- 기존 큰 세트 수 테스트 요청의 필수 nullable 장비 키 누락을 수정했다. 최신 매핑 기동 테스트의 target16·이력15/16 기대값과 비어 있는 목록에 대한 containsOnly 기대값을 현재 계약에 맞췄다. V16 자체 구조 검증은 target16으로 고정했다.
- 기본 sandbox의 Gradle 캐시 잠금/Docker 접근 제한은 승인된 검증 실행 권한으로 해결했다. 의존성을 새로 받거나 개인 DB로 우회하지 않았다. 결과 표의 PowerShell 집계 출력 오류는 JSON으로 다시 집계해 위 숫자를 확인했다.

최종 실행의 제품/테스트 미해결 오류는 없다. 실제 개인 DB 적용·Frontend/IndexedDB·실제 브라우저 UI/연결 검증은 이번 범위에서 실행하지 않았다.

모든 Gradle 실행 세션은 종료됐고 `--no-daemon`을 사용했다. 종료 후 GradleDaemon/GradleWorkerMain/Backend Java 프로세스가 없음을 확인했다. Testcontainers/Ryuk·임시 PostgreSQL 실행 컨테이너도 남아 있지 않다. 기존 사용자 컨테이너5개(`81a6b4d6aca2`, `908b9728eec1`, `97473d622cad`, `1079a897c476`, `927921a2c15b`)가 같은 ID로 유지됨을 확인했다. 기존 사용자 프로세스/컨테이너는 종료하지 않았다.

승인 상태: 요청된 Backend 구현·신규 migration·격리 검증 범위에서 완료했고 추가 사용자 승인 대기는 없다. 실제 개인 DB 적용·초기화·Frontend·원격 push 등 금지/범위 밖 작업은 실행하지 않았다.

## 6. 최종 설계 대비 및 후속3단계

기능 정책 차이는 없다. 최초 설계의 예시 ‘신규 카탈로그+연결 한 migration’은 사용자 단계 분리에 맞춰 카탈로그 V16과 연결 V17로 나눴다. 장비 DB 불변 trigger는 최종 설계의 완료 장비 불변성을 SQL에서도 보강한 것이다. 주간 통계·색인·관리 UI·카탈로그 초기 데이터는 이번 요청의 범위가 아니며 추가하지 않았다.

Frontend3단계에서는 다음을 함께 연결해야 한다.

- 개인 정의 bootstrap/생성/이름 변경 UI 대신 표준 search-data 및 제조사 조회 사용. UUID·필수 nullable 장비·네 부하 코드 적용.
- 루틴 NEW와 EXISTING/RESTORE DTO·응답 전환. 장비 변경 시 배치/승인값 보존, 운동 교체는 제거+NEW.
- InitialPlan/AppliedPlan/평가 schema2와 standardExerciseId 적용. 세트 완료 시 당시 장비를 SetValue에 복사하고 이후 계획 변경으로 수정하지 않기.
- 정정·복구·이력 UI에서 장비 원본·동일 UUID 비교·고정 요청 재송신 유지. 장비가 다르다는 이유로 공식 평가를 제외하지 않기.
- 최종 설계에 따른 IndexedDB3 단일 문서 전환·구 테스트 문서 폐기와 worker 호환 경계를 별도 승인된 Frontend 작업에서 구현. 이번 Backend 작업은 브라우저 저장소를 건드리지 않았다.
- 실제 개인 DB 적용 전 대상과 비어 있는 운동 도메인을 확인하고 별도 적용 절차를 진행. 데이터가 있으면 V17이 중단하며 자동 정리하지 않는다.

기존 Backend는 개발 중간 상태에서 이전 Frontend와 직접 연결해 사용할 수 없다. 새 계약의 Frontend 통합·실제 DB 적용·카탈로그 등록이 남아 있다.
