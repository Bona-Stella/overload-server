# 주간 루틴 정책 전환 — G0 계약 정리·G1 기존 보호 보완

작성: 2026-11-06. 기준: [최종 구현 계획](weekly-schedule-immediate-implementation-plan.md). **이번 구현은 G0~G1까지만이다.** 주간 일정의 기존 다음 월요일 적용을 유지하며 신규 시간축·일별 판정·디로딩 엔진·Frontend 전환은 구현하지 않았다. 공식 제품 명세와 기존 역사 보고서는 변경하지 않는다.

## 1. G0: 현재 계약과 후속 변경의 경계

| 영역 | 이번에 유지/보완한 실제 계약 | G2 이후 필요한 변경·검증 |
|---|---|---|
| WeeklySchedule GET/PUT | 현재 userId/dataGeneration/version/timeZone/nextEffectiveFrom/current/pending/history DTO와 전체 assignments PUT 유지. 최초 등록 당일·이후 다음 월요일 적용, expectedVersion/no-op 유지. ACTIVE 중 실제 배정 변경만409 추가 | 즉시 적용 DTO로 동시 전환, pending/nextEffectiveFrom 사용자 기능 제거. §3.4의 최신 저장본 승격/구 예약 비실행 검증 |
| Routine POST/PUT/DELETE | UUID·소유권·세대·expectedVersion·T02-B tag/cardio 계약 유지. 원본 구성/삭제 guard와 관리 PUT의 owner 배타 잠금 보완 | 삭제의 일정 제외 시점을 즉시 변경. 이 단계에서는 다음 월요일 제외를 그대로 유지 |
| START | startedAt/startedTimeZone/workoutDate 검증, sourceRoutineId·불변 InitialPlan, 동일 UUID 재확인 유지. 사용자 배타 잠금·READ_COMMITTED로 관리 쓰기와 직렬화 | 첫 예정 START의 일정 version 출처 추가와 당시 배정 조회. 아직 신규 컬럼·JSON 변경 없음 |
| snapshot/FINISH/ACK | 현재 전체 확정 요청·hash·writerEpoch·revision·중복 ACK·현재 편집 기기 검증 유지. 관리 guard를 이 경로에 넣지 않음 | 향후 실제 사실 변경과 파생 판정 dirty 표시를 같은 트랜잭션에 연결. 이번엔 구현 안 함 |
| 일정/루틴 이력 | V19/V20 불변 UPDATE 방지·기존 JSON/버전/FK 유지. WeeklyScheduleService가 owner 잠금 후 Clock.instant를 recorded_at으로 기록 | 신규 즉시 행에서 recorded_at을 재사용하고 기준 version으로 구 effective_from 해석을 분리. 월요일 CHECK 변경/범위 인덱스·시각 단조성 검증 필요 |
| 일반 통계 | PlanningRepository의 일정·날짜 말 루틴 이력·첫 START 조회와 PlanningCalculator의 최고 단일 InitialPlan 판단, 정확한 Fraction 유지 | 첫 예정 START 고정·날짜 종료 직전 배정 조회를 보완. ST-17~23의 일반 계획 분모/실제 운동량을 디로딩 규칙으로 바꾸지 않음 |
|04시/디로딩 | 현재 미구현. 임시로 성공한 API/판정이나 개발용5.2주를 저장하지 않음 | 자동 MISS·cursor/dirty·확정 주별 E/N, E≤1 초기화, 월04시 확정. 예정 외·휴식일 실제 일반 수행 포함, 날짜별 최고 단일 값. OFF/기간/완료/취소 경계는 실제 기간 구현과 연결 |

향후 DTO/DB/정책의 상세는 기존 구현 계획 §3~7을 따른다. 이 보고서는 이를 복제한 새 아키텍처 문서가 아니라 현재 배포 동작과 미래 변경을 구분하는 G0 결과다. 시간축 DDL·미수행 저장·디로딩 설정/주별/기간 모델은 하나도 생성하지 않았다.

현재 WeeklyScheduleService는 owner 잠금 뒤 Clock.instant를 한 번 얻어 JDBC Timestamp로 recorded_at에 INSERT한다. RoutineService의 계획 이력 시각은 기존처럼 마이크로초로 절삭한다. owner 잠금은 version 증가 순서를 보장하지만 recorded_at의 엄격한 증가나 실제 commit 시각을 보장하지는 않는다. G2에서는 PostgreSQL 시간 정밀도·동일 시각의 version 정렬·시계 역행·자정 경합을 검증해야 한다. 기존 시각/행을 보정하거나 이를 위해 G1에서 저장 규칙을 바꾸지 않았다.

### 공식 문서의 차기 개정 대상

Weekly Schedule의 현재 계약은 G1을 반영하되 즉시 적용으로 바꾸지 않는다. 제품 개정은 구현 계획 §5의 ST-15/AC-20·26, ST-12의 디로딩 범위, DL-06/09/10/11/13/80 및 UI Decision Log 후속 조항에 남긴다. 최종 확정된 예정 외/휴식일 일반 운동 인정은 다시 질문하지 않는다. 현재 공식 제품 명세와 계획의 차이를 G2 구현 완료처럼 덮어쓰지 않는다.

## 2. 이미 있던 보호와 재사용

- 기존 세션 editorDeviceId/writerEpoch·takeover·소유권·세대 검증 및 PWA의 다른 기기 읽기 전용/미확인 원본 차단을 유지했다. 새 기기 권한·전역 오프라인 잠금은 없다.
- START의 routine/표준 운동 잠금과 InitialPlan 복사를 유지했다. 데이터베이스의 불변 계획/날짜/소유권과 계정당 ACTIVE UNIQUE도 그대로다.
- 세션 snapshot·FINISH의 고정 원문·중복 응답·ACK,24시간 AUTO_CLOSED 및 만료 커밋 후 오류, 정정·논리 삭제·명시 누락 복구를 재사용했다. 단순 조회/재확인으로 마지막 운동 동기화 시각을 늘리지 않는다.
- PlanningCalculator의 기존 judge/정확한 Fraction과 완료·부분·유산소 UNKNOWN·최고 단일 세션 테스트를 그대로 사용했다. **다른 소비자가 아직 없으므로 G1에서 추출만을 위한 새 공통 클래스는 만들지 않았다.** G4에서 필요할 때 작은 함수로 분리하며 일반 통계 후보 선택을 공유 엔진에 섞지 않는다.

## 3. G1 실제 수정과 원인

| 파일(기본 경로: src/main/java/com/overload/overload_server) | 변경/근거 |
|---|---|
| workoutsession/WorkoutSessionService.java | START만 READ_COMMITTED로 변경하고 첫 데이터 조회 전에 owner FOR UPDATE. 기존 REPEATABLE_READ snapshot/공유 owner 잠금만으로는 대기 후 관리 변경 또는 ACTIVE 생성의 최신 상태를 확실히 재조회할 수 없었음 |
| workoutsession/WorkoutSessionRepository.java | 기존 사용자 행을 잠그는 작은 START 전용 FOR UPDATE 조회 추가. 신규 잠금 테이블/서비스 없음 |
| routine/RoutineService.java | 관리 PUT도 기존 DELETE의 owner 배타 잠금 재사용. 쓰기 격리를 READ_COMMITTED로 명시. 기존 differs/제거 판단으로 실제 구성 변화일 때만 guard, 삭제도 같은 원본 guard. 승인·메타데이터/no-op 분리 |
| routine/RoutineRepository.java | 현재 사용자/세대·해당 sourceRoutineId의 ACTIVE 존재 조회만 추가. 세션 상태·원본·마지막 동기화 시각을 변경하지 않음 |
| routine/RoutinePlanHistory.java | 기존 일정 변경 책임 안에 작은 공통 ACTIVE guard 추가. 명시 PUT과 루틴 삭제의 간접 배정 제외가 함께 사용하며, 삭제+이력+일정 원자 롤백 유지 |
| routine/WeeklyScheduleService.java | PUT의 READ_COMMITTED 명시, 기존 owner FOR UPDATE/버전/시간대 검증 재사용. 동일 최신 배정 no-op 다음에 공유 ACTIVE guard. 적용일·이력 생성 방식은 그대로 |
| routine/RoutineFailure.java | 관리 API의409 ACTIVE_SESSION_EXISTS 추가. 기존 code-only 오류·권한·버전 충돌 유지 |

**차단:** 현재 일반 ACTIVE의 원본 종목 추가/제거/복원/순서·세트/반복·장비·유산소 계획 변경, 해당 원본 삭제, 전체 주간 배정의 실제 변경/최초 등록. 현재 서버에는 디로딩 소속이 없으므로 ACTIVE는 현재 일반 세션이며 기간 구분은 G5에서 실제 모델과 연결한다.

**유지:** 다른 루틴 구성 관리/생성, 배정에 영향 없는 다른 루틴 삭제, 원본 이름·태그만 변경, 동일 구성, 승인 중량만 변경, 세션 내부의 허용된 임시 변경·수행·FINISH/ACK·명시 복구. 다른 루틴 삭제라도 최신 배정을 바꾸면 주간 일정 guard로 전체 롤백한다. 구성 변경과 승인을 묶으면 전체 실패한다. stale expectedVersion은 no-op에도 기존 충돌이다. 미등록에서 빈 배열을 최초 등록하는 것은 등록된 일정의 no-op이 아니다.

START/관리 쓰기 모두 owner→routine→정의→session의 필요한 잠금 순서를 지킨다. 관리 먼저면 최신 계획 복사, START 먼저면 실제 관리 변경409다. 읽기 전용 조회와 다른 서비스의 격리를 일괄 변경하지 않았다. 만료가 필요한 ACTIVE는 기존 expiry/세션 조회가 AUTO_CLOSED로 확정하며, 관리 guard에 별도 만료 엔진을 넣지 않았다.

## 4. 테스트 추적과 픽스처 변경

| 필수 검증 | 재사용/추가한 검증 |
|---|---|
|1 다른 기기 읽기 전용 | 기존 takeover/EDITOR_OWNERSHIP_LOST·다른 기기 snapshot/finish 거절 통합 테스트, PWA 계정/세대/동기화·원본 보호 테스트 |
|2 원본 실제 구성 차단 | activeSourceRejectsActualPlanChangesWithoutWritingAnyPartialState의 세트/반복/제거/추가/유산소5개 입력. 구성+승인 전체 롤백·원본/이력 불변. 기존 장비 테스트를 새 차단 기대와 정상 임시 장비/승인으로 전환 |
|3 주간 배정 차단 | START-first SCHEDULE 경쟁 및 activeSourceAndScheduleNoopsKeepVersionsAndStaleVersionsStillFail. 다른 배정 루틴 삭제의 간접 변경도 별도 회귀로 차단·전체 롤백 검증. FINISH 뒤에는 기존 다음 월요일 예약이 정상 생성됨 |
|4~6 양방향 경쟁/InitialPlan | startLocksCurrentRoutineUntilInitialPlanIsCommitted와 concurrentSourceChangeCannotProduceAMixedInitialPlan을 PUT/DELETE/SCHEDULE3경로로 검증. 실제 pg_stat_activity 잠금 대기를 확인. START-first409/관리-first 최신 계획201 또는 삭제404 |
|7 승인 중량 | 기존 승인·재승인·응답 유실·승인 충돌과 새로운 원본 장비 차단 후 정상 승인 테스트. 승인 경로에 관리 guard를 추가하지 않음 |
|8 FINISH/ACK | 기존 동일 FINISH 재확인·단일 ACK·원문 보존·삭제 원본 뒤 동일 START 재확인 통합 테스트 |
|9 AUTO_CLOSED | 기존 정확한24시간 경계·재시작 expiry·만료 후409에서도 만료 커밋·SKIP LOCKED/재시도 테스트 |
|10 정정/복구 | 기존 종료 후 expectedVersion·tombstone·실제 측정/추가 부하·누락 복구·계정/기기 경계 테스트 |
|11 일반 통계 불변 | 수정하지 않은 PlanningCalculatorTests/StatisticsCalculatorTests 및 StatisticsIntegrationTests. 별도 디로딩 후보/주간 엔진 없음 |
|12 no-op/version | 원본/일정 no-op·stale 버전, owner 직렬화의 RoutineIntegrationTests, 기존 부분 성공/요청 유실 PWA 테스트 |

기존 경쟁 테스트의 대기 대상만 routine→users로 맞추고 단일 ACTIVE 검증은 유지했다. START 격리 검증도 같은 JPA/JDBC transaction/connection에서 READ_COMMITTED를 확인하도록 바꿨다. 기존 승인 충돌 테스트의 ‘ACTIVE 중 세트 변경으로 version 증가’ 픽스처는 실제 허용되는 이름 변경으로 전환해 승인·수행·이력 검증을 보존했다. 다른 구성/승인 혼합 픽스처도 기존 계획3세트를 유지하게 했다.

이미 삭제된 원본에서 수행을 잃지 않는 SOURCE_REMOVED 방어 테스트는 유지한다. 삭제 API가 먼저409임을 확인한 뒤, **일회성 DB에만 이전부터 삭제돼 있던 원본 상태를 직접 준비**해 기존 방어를 검증한다. 새 관리 API의 삭제 성공을 가정하거나 운영 데이터를 바꾸지 않는다.

### 실행 결과

| 검증 | 실제 결과 |
|---|---|
| `gradlew.bat test --console=plain` |322건,28 suite, 실패/오류/skip0. 컴파일·기존 완료 판정 단위 테스트 포함 |
| `gradlew.bat integrationTest --tests '*WorkoutSessionIntegrationTests' --tests '*StatisticsIntegrationTests' --tests '*RoutineIntegrationTests' --tests '*JpaJdbcCompatibilityIntegrationTests' --console=plain` | 최종196건 통과. 세션131·통계21·루틴27·혼합 영속성17, 실패/오류/skip0. 삭제에 의한 간접 배정 변경까지 보완한 마지막 실행은 같은 필터로 `test integrationTest … assemble`을 함께 수행 |
| `gradlew.bat assemble --console=plain` | 성공. 실제 검토 서버나 DB 실행 없이 bootJar/JAR 패키징 |
| PWA `npm test` |543건 전체 통과, 실패/skip0. 소스 변경 없이 기존 보호·컴포넌트/fixture 회귀 실행. 실제 검토 DB·iPhone 재검토 결과와 구분 |
| PWA `npm run build` / `npm run lint` | 모두 성공. build의 기존500kB chunk 권고 경고는 남아 있으나 실패 아님 |
| 문서/보존/diff 검사 | 변경 문서4개의 내부 링크·표·fence·공백 및 양쪽 `git diff --check` 통과. 최종 보존 대조는 아래 참조 |

첫 통합 실행은178건 중2건이 실패했다. 하나는 루틴 경쟁 테스트가 이전 routine 행 대기를 기대한 것이고, 다른 하나는 새 제거 테스트가 제거한 종목의 승인을 함께 보내400 요청 검증에 먼저 걸린 것이었다. 대기 대상을 users로 맞추고 제거 입력을 유효한 계약으로 바꾼 뒤 네 클래스195건을 모두 재실행해 통과했다. 이후 자체 검토에서 다른 배정 루틴 삭제의 간접 변경을 발견해 공통 guard와 회귀1건을 추가하고, 최종196건·단위 테스트·패키징을 다시 통과했다. 제품 guard를 우회하거나 기대 동작을 약화시키지 않았다.

PWA 첫 sandbox 실행은 loopback 연결 EACCES로 브라우저/프록시 테스트가 실패했다. 같은 소스에서 테스트 통신을 허용해 전체543건을 재실행했고 통과했다. Backend 테스트도 기존 Gradle 캐시 접근 권한을 허용해 실행했다. 초기 실패를 성공으로 숨기지 않으며 결과는 최종 재실행 기준이다. 이번 범위의 선택 통합 테스트 외 **전체 Backend integrationTest/check/build는 실행하지 않았다.**

## 5. 데이터·작업 보존

Backend/PWA는 main이며 시작 HEAD는 각각 `80327ca3b7db52e9cdedbbca8a153a667f021752`, `afd6ed95245cadae1a6c4212dd12a2c365c1dd2d`다. 작업 시작 시 양쪽 Git 상태와 각 파일 SHA-256을 기록했다. G1 대상과 이 보고서 외 파일·기존 T02-B 신규 API/태그/카탈로그·공식 문서/아이콘·이전 분석/구현 계획은 보존한다.

최종 대조에서 기존 Backend340개 중 이번 대상12개(API3·소스7·테스트2) 외328개, PWA412개 전체의 SHA-256이 시작과 일치했다. 위 대상 파일에 포함돼 있던 T02-B 태그/카탈로그/기록 계약도 유지한 채 G1 차이만 검토했다. 신규 파일은 이 보고서 하나이며, 양쪽 main/HEAD와 기존 미커밋 변경을 보존했다. Git은 기존 T02-B와 G1 변경이 함께 남아 있는 미커밋 상태다. stage/commit/push하지 않았다.

통합 테스트는 클래스별 이름이 명시된 일회성 Testcontainers PostgreSQL과 무작위 포트로 실행한다. 테스트 설정은 호스트5432/5433이 아님을 검증한다. 개발·검토 DB/볼륨·기존 영구 백업에는 접속·초기화·migration·컨테이너 재생성을 하지 않는다. 테스트 내부의 기존 V1~V23 적용과 픽스처 정리는 실제 DB 적용과 다르다. 상시 Vite/Backend 검토 서버도 시작하지 않는다.

## 6. 생략한 개발과 다음 단계

새 읽기 전용 운동 기능, 전역 잠금/기기 추적, 별도 판정 엔진·현재 누적 캐시, 미사용 공통 함수 추출을 만들지 않았다. PWA/IndexedDB3/schema2·ACK/writerEpoch·기존 고정 요청과 운동 원본은 변경하지 않았다.

G2~G3의 선행 보호는 이번 START/관리 쓰기 직렬화와 guard다. 다음에는 별도 요청 후 recorded_at의 version 경계/시각 이력 조회·신규 M1·첫 예정 START 출처·일별 검사/재검사를 구현하고 양쪽 API 소비자를 함께 전환해야 한다. 현재 다음 월요일 예약을 제거하거나 즉시 적용을 켜지 않았다. 실제 DB 적용·디로딩·G2 이후 착수·commit/push는 수행하지 않는다.
