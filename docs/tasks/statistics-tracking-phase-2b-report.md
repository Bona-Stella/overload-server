# 통계·추적 — 2B 주간 일정·계획 이력·달성률

후속 커밋 상태: Backend `7e442e3`, Frontend `fd3e847`로 2B를 커밋했다. 커밋 검토·이후2C 구현/검증은 [2C 보고서](statistics-tracking-phase-2c-report.md)를 따른다. 아래 미커밋 설명은 커밋 전 검증 시점 기록이다.

작성일: 2026-11-02 (Asia/Seoul). **2B 구현·자동 검증 완료.** 통계·추적 전체나 디로딩 전체 완료 보고서가 아니다.

**현행 정책 정정은 6절을 우선한다.** 1~5절의 ‘최초 등록도 다음 월요일’ 및 V19/검증 파일 수는 정정 전 2B 완료 시점의 보존 기록이다. 현재 최초 등록은 당일 적용이고 기존 변경만 다음 월요일이며 최신 제품 문서는 통계·추적/디로딩 각각 v1.3이다.

## 1. 2A 커밋과 사용자 결정

양쪽 변경을 검토했고 개인 설정·빌드 산출물·의존성 변경은 없었다. 이전 검증 결과(Backend 단위226+통합609, Frontend460·lint·build)와 문서/공백을 확인한 뒤 다음 로컬 커밋을 생성했다. commit 전에 코드 검증을 다시 실행한 것으로 표시하지 않는다.

| 저장소 | 2A 커밋 | 범위 |
| --- | --- | --- |
| Backend | `84f52427a91befbbd87b66962531f7c2147999e3` | 15파일, 통계 읽기 API·계산·단위/HTTP 통합 테스트·기술 문서 |
| Frontend | `f8691156e636d5b7d2d20cc2338610bae396e454` | 8파일, 통계 화면·API 검증·SSR/단위 테스트·실제 계정 연결·문서 |

2B 시작 시 두 저장소 모두 clean이었다. 원격 push·배포는 하지 않았다. 이번 2B 변경은 이 커밋 뒤의 별도 작업 트리이며 아직 커밋하지 않았다.

명세가 정하지 않은 두 경계를 구현 전에 질문했고 사용자가 확정했다.

1. 주간 일정 최초 등록 및 요일·빈도 변경은 **다음 월요일부터** 적용.
2. 같은 예정일에 해당 루틴을 여러 번 수행하면 **가장 높은 단일 세션 달성률** 사용. 부분 세트/완료 종목을 세션 사이에서 합치지 않음.

승인된 결정만 [Statistics and Tracking v1.2](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.2.md)의 ST-15·20, AC-20~22·변경 이력에 반영했다. [v1.1](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.1.md)은 보존했다. 다른 제품 정책을 임의 변경하지 않았다.

## 2. 실제 구현과 변경 파일

| 영역 | 파일·내용 |
| --- | --- |
| DB | `src/main/resources/db/migration/V19__add_weekly_schedule_and_routine_plan_history.sql`: 이력2표·소유권/세대 FK·인덱스·UPDATE 금지 트리거. 데이터 백필/기존 행 변경 없음 |
| 주간 일정 | `routine/WeeklySchedule`, `WeeklyScheduleRequests`, `WeeklyScheduleService`, `WeeklyScheduleController`: 온라인 GET/PUT·최근20개/커서 페이지·다음 월요일·expectedVersion·계정 잠금·본인 루틴 검증 |
| 루틴 이력 | `routine/RoutinePlanHistory`, `RoutineService`, `RoutineRepository`: 기존 쓰기 트랜잭션에서 생성/변경/승인/삭제의 최종 계획 보존. 삭제는 다음 월요일 일정 제외까지 원자 처리 |
| 오류 | `RoutineFailure`, `RoutineErrors`: 일정 버전/시간대 충돌과 기존 인증/CSRF/DB 오류 계약 |
| 계획/달성률 | `statistics/PlanningRepository`, `PlanningCalculator`, `StatisticsOverview`: 예정일 이력 해석·첫 시작 계획 증거·단일 최고 InitialPlan 달성률·정확한 유리수·확인 불가 구분 |
| 통계 연결 | `StatisticsCalculator`, `StatisticsController`, `StatisticsResponse`, `StatisticsService`: 기존2A endpoint 보존, 신규 overview에 미시작 예정일 계획·주간 분모·달성률 연결 |
| Backend 검증 | `statistics/PlanningCalculatorTests`, `StatisticsIntegrationTests`, `routine/WeeklyScheduleRequestsTests`, `database/V19MigrationIntegrationTests`; 최신 V19 기대값과 DELETE 계정 잠금 경계에 맞춘 기존 회귀 보완 |
| Frontend | `features/statistics/weekly-schedule.ts`, `WeeklySchedulePanel.tsx`: 온라인 일정 예약·현재/예정·페이지별 이력·실패 시 입력 보존 |
| Frontend 통계 | `features/statistics/api.ts`, `StatisticsPanel.tsx`, `workout-online/WorkoutWorkspace.tsx`: overview scope 검증·달성률/날짜별 계획·당시 계획/기존 수행 상세·저장 후 재조회 |
| Frontend 검증 | `tests/statistics.test.mjs`: 기존7+신규4, 달성률·확인 불가 상태·버전 쓰기·7요일 폼·초안 보존/정확한 Counter |
| 기술 문서 | [주간 일정 계약](../api/weekly-schedule.md), [통계 계약](../api/statistics.md), [루틴 계약](../api/routine-management.md)의 계획 이력·DELETE 잠금/원자 예약, 이 보고서·2A 역사적 상태·양쪽 AGENTS/README |

최종 작업 트리 변경은 Backend39파일·Frontend8파일이다. 기존 스키마/회귀 기대값 수정은 `AccountProvisioningIntegrationTests`, `EmailOtpIntegrationTests`, `CatalogIntegrationTests`, `PostgresMigrationTests`, `V9MigrationCompatibilityTests`, `V10MigrationIntegrationTests`, `JpaJdbcCompatibilityIntegrationTests`, `SecuritySessionIntegrationTests`, `RoutineIntegrationTests`, `WorkoutSessionIntegrationTests`에 한정했다. 의존성·lockfile·개인 설정·빌드 산출물은 변경/포함하지 않았다.

## 3. 계산과 보호 규칙

- 등록 이전의 과거 일정은 UNKNOWN이다. 시작한 과거 세션의 InitialPlan만 보고 그 주의 미시작 예정일을 만들어내지 않는다. 명시한 휴식 일정은 REST이며 과거 자료 없음과 다르다.
- 일정은 날짜에 유효한 version, 루틴은 날짜별 기록된 최종 목표를 사용한다. 해당 루틴을 시작한 날짜에는 가장 이른 불변 InitialPlan을 계획 표시/계획 세트의 원본으로 사용한다. 논리 삭제/ACTIVE가 최초 계획의 역사적 증거를 지우지 않으며 실제 분자는 미삭제 종료 기록만 사용한다.
- 운동하지 않은 예정일도 계획일·계획 세트에 들어간다. 실제 수행이 없어도 분모에서 빼지 않는다. 루틴 삭제의 일정 제외도 다음 월요일부터이므로 이미 적용 중인 주의 분모를 지우지 않는다.
- 각 후보는 자기 InitialPlan의 계획 종목 UUID와 목표 세트/유산소 목표로 판정한다. 세트 미달은 종목0, 전체 최초 세트를 수행하면1이다. AppliedPlan 축소·추가 종목·추가 루틴·휴식일 수행은 계획 달성률을 채우지 않는다. 실제 운동량에는 계속 포함한다.
- 같은 예정일/루틴의 최고 단일 세션 비율만 사용한다. BigInteger 유리수로 합산해 4일 계획/유효2.25일은56.25%다. 주간 분모0일 때 달성률을 만들지 않는다. 월~일 정확히7일인 범위만 주간 달성률을 반환하며 새 월간 달성률 정책을 만들지 않았다.
- 시간·거리 목표는 보존 실제값과 비교한다. 실제 속도/경사/저항 또는 필수 거리 증거가 없으면 UNVERIFIABLE이며 완료나0으로 단정하지 않는다. 계획 속도를 실제 속도로 간주하지 않는다. 이런 날짜가 있으면 전체 달성률/유효일은 null이고 확인 가능한 상세는 남는다. 실제 속도 저장은 후속이다.
- 조회는 기존 workoutTransactionManager의 read-only REPEATABLE_READ다. 원래2A `/statistics` 응답은 유지하고 신규 `/statistics/overview`에 계획/달성률을 제공했다. 새로운 쓰기와 이력은 온라인 전용이며 범용 명령/Outbox/영수증을 만들지 않았다.
- 세션 InitialPlan·봉인 AppliedPlan·최초 평가·승인 사건·장비·기존 진행 세션의 DTO를 변경하지 않았다. IndexedDB DB3/schema2·고정 snapshot/finish·타이머·오프라인 허용 경계는 그대로다. 정정/삭제/명시 누락 복구는 기존 원본을 매번 읽어 통계를 다시 계산한다.

## 4. 검증

| 항목 | 현재 결과 |
| --- | --- |
| Backend 단위 전체 | `gradlew.bat --offline --no-daemon build`의 test: 235개 성공, 실패/error/skip0. 계획/이력·56.25%·최고 단일 세션·추가 운동 제외·확인 불가·시간대/파서 포함 |
| 관련 통합 재검증 | `gradlew.bat --offline --no-daemon integrationTest --tests '*WorkoutSessionIntegrationTests' --tests '*AccountProvisioningIntegrationTests' --tests '*RoutineIntegrationTests' --tests '*StatisticsIntegrationTests'`: 159개 성공, 실패/error/skip0. 신규 통계 HTTP11개에 삭제 이력 실패 시 원자 rollback 포함 |
| 신규 통합 | 실제 OTP 쿠키/CSRF를 사용하는 격리 PostgreSQL에서 일정 저장·충돌·범위·현재/예정/페이지·실제 START/FINISH·미수행 분모·주중 변경·삭제의 다음 주 반영·V19 보존 검증 통과 |
| Frontend 전체 | `npm test`: 464개 성공, 실패/skip/취소0 |
| Frontend lint/build | `npm run lint` 경고0, `npm run build` 성공, JS445.46kB/gzip127.33kB |
| Backend 전체 | 최종 `gradlew.bat --offline --no-daemon build` **성공(8분2초)**. 단위235개+통합617개, 실패/error/skip 모두0. compile·bootJar·jar·check 포함 |
| 변경/문서 검토 | 양쪽 `git diff --check` 통과. 신규 Java/SQL의 후행 공백0, 변경 문서의 로컬 링크 누락0. V1~V18 migration 변경 없음 |

해결한 검증 문제는 다음과 같다. 최초 전체 실행은 통합616개 중 기존 회귀6개(가입 스키마 목록1개·세대 초기화 fixture3개·DELETE 잠금 관찰2개)가 실패했다. START 성공 본문이 Detail 자체인데 테스트 helper가 래퍼로 읽은 문제도 실제 계약대로 수정했다. 기존 DELETE/PUT 동시성 테스트는 새 계정→루틴 잠금 순서에서 DELETE의 계정 잠금 대기를 관찰하도록 수정했고 단일 version/200·409 또는404·204 결과 검증은 유지했다. V19 최신 스키마 기대값에는 새2표·버전19를 반영했고 가입 시 이력/일정0행도 확인한다. 기존 격리 세대 초기화 fixture는 새 자식 이력2표를 부모 루틴/세대 변경 전에 정리하도록 보완했다. FK를 끄거나 제품 초기화 API를 만든 작업이 아니다. Frontend의 계획 없음 문구 기대값은 확인된 계획 범위에 맞췄다. 검사·요구사항을 비활성화하지 않았고 최종 전체 실행에서 모든 실패를 해소했다.

브라우저 DOM/E2E·iPhone/Android 실기기·실제 개인 DB 연동은 미실행이다. SSR을 실기기 검증으로 표시하지 않는다. 최종 build 후 Testcontainers PostgreSQL·Ryuk가 모두 제거됐고 이번 Gradle daemon/worker·앱 Java 프로세스도 남지 않았음을 확인했다. 3000/8080/5433의 수신 프로세스 없음. 기존 welog 컨테이너는 그대로이며 개인 `overload-local-postgres-1`은 기존 중지 상태를 유지했다. 별도 개발/preview 서버나 watch 프로세스는 시작하지 않았다.

## 5. DB·커밋·남은 작업

**이번에도 개인 DB에 V18을 적용하지 않았다. V19도 적용하지 않았다.** 개인 PostgreSQL을 시작/조회하지 않았고 별도 승인 없이 local Backend를 띄우지 않았다. 모든 migration 검증은 다른 이름/임의 포트의 일회성 DB이며 기존 사용자/공유 컨테이너·볼륨을 변경하지 않는다. V1~V18 SQL은 보존한다.

운영 카탈로그의 정확한 joint_type·PRIMARY/SECONDARY 분류 검증은 계속 남아 있다. 이력/달성률 계산 구현이 카탈로그 정확성 검증 완료를 뜻하지 않는다. 승인 중량을 자동으로 변경하거나 기존 운동 데이터를 옮기지 않았다.

2A는 위 양쪽 커밋에 포함됐고 2B는 별도 미커밋 변경이다. push·배포 없음. 정책 질문2개는 사용자 결정으로 해소됐으며 추가 정책 결정 대기 없음. 남은 통계 후속은 승인 중량 성장선·검색, 실제 유산소 속도/계산 거리 출처 저장, 명시적 맨몸 추가 부하다. 디로딩 기간/평가 분리 연결은3단계이며 이번2B로 완료 처리하지 않는다.

## 6. 최초 주간 일정 등록 시점 정책 정정 — 현행

사용자의 후속 명시 정정을 적용한다. 최초 등록은 서버 Clock과 일정 기준 시간대의 **등록일 즉시 적용**, 첫 주는 등록일~일요일의 남은 예정일만 분모다. 다음 주는 전체 월~일, 이미 등록한 일정의 요일/빈도 변경은 엄격한 다음 월요일이다. 다른 제품 정책은 변경하지 않았다.

### 변경 파일과 실제 계약

- `routine/WeeklyScheduleService.java`: 최초 저장 version1은 오늘, 이후 변경은 nextMonday. GET의 `nextEffectiveFrom`은 미등록 상태에서 오늘, 등록 후 다음 월요일이며 요청/DTO 필드는 그대로다. 최초 저장 후 current를 반환하고 pending은 없다.
- `statistics/PlanningRepository.java`: 기존 범위/직전 일정과 함께 최초 version1을 같은 SQL에서 읽어 최초 적용일의 증거를 유지한다. N+1/추가 query/과거 일정 생성은 없다.
- `statistics/PlanningCalculator.java`: 최초 적용일이 포함된 주의 등록 전 날짜만 BEFORE_REGISTRATION으로 분리한다. plan/count/effectiveDay는 null이고 분모·unknownPlanDays에서 제외한다. 이전 주 전체 등 근거 없는 과거는 UNKNOWN을 유지한다. 첫 주를 휴식 일정으로 소급 생성하지 않는다.
- `V20__allow_immediate_first_weekly_schedule.sql`: V19의 월요일 CHECK를 `version=1 OR ISO월요일`로 교체한다. 새 표/컬럼/백필/기존 행 UPDATE는 없다. 기존 V1~V19 migration SQL과 저장된 적용 날짜는 보존한다. 기존 V19 일정의 최초 날짜도 소급 당기지 않는다.
- Backend 테스트: `PlanningCalculatorTests`, `WeeklyScheduleRequestsTests`, `StatisticsIntegrationTests`, 신규 `V20MigrationIntegrationTests`; 최신 버전20 기대값만 바꾼 기존 `CatalogIntegrationTests`, `EmailOtpIntegrationTests`, `SecuritySessionIntegrationTests`, `JpaJdbcCompatibilityIntegrationTests`, `V9MigrationCompatibilityTests`, `V10MigrationIntegrationTests`, `PostgresMigrationTests`.
- Frontend `WeeklySchedulePanel.tsx`, `StatisticsPanel.tsx`, `api.ts`, `tests/statistics.test.mjs`: 최초 등록 즉시 적용/등록 전 제외 안내, BEFORE_REGISTRATION 파싱·표시, 기존 변경의 월요일 안내 회귀. IndexedDB·인증·오프라인/세션 쓰기는 변경하지 않는다.
- 기술 문서: `docs/api/weekly-schedule.md`, `statistics.md`, `routine-management.md`, 이 보고서·양쪽 AGENTS/README.
- 제품 기준서: [Statistics and Tracking v1.3](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.3.md) ST-15·AC-20/23~26·변경 이력, [Deloading v1.3](../product/Overload_Deloading_Design_Specification_v1.3.md) DL-06/09·수용 테스트·변경 이력. v1.1·v1.2 파일은 보존한다. DL-06/11의 기존 빈도 변경·분석 조건·DL-13 복귀 분모 등 다른 확정 정책은 유지하며 디로딩 누적 구현 완료로 표시하지 않는다.

### 검증 결과

| 검증 | 결과 |
| --- | --- |
| 정책/DB 집중 검증 | `gradlew.bat --offline --no-daemon test integrationTest --tests '*StatisticsIntegrationTests' --tests '*V20MigrationIntegrationTests' --tests '*PlanningCalculatorTests' --tests '*WeeklyScheduleRequestsTests'` 성공. 단위 전체 및 해당 격리 통합 실행 |
| 첫 주 | 목요일 월·목·금·토 등록→계획일3·유효일2·66.666667%, 미수행 토요일 포함. 등록 전 월요일 수행은 실제 세트에만 반영. 등록 전 월~수는 계획null/분모 제외 |
| 다음 주/기존 변경 | 동일 일정 다음 주4일, 첫 주 안에서 기존 일정 변경 시 다음 월요일만2일로 변경, 현재 주 응답/기존 version1 행 동일 |
| 경계/미확인 | 시간대별 자정·월요일 기존 변경·일요일 등록 후 남은 예정일0(달성률null)·등록 주 이전 UNKNOWN 보존 |
| DB 보존 | 격리된 채워진 V19→V20의 모든 기존 행/기존 migration 이력 동일·재적용0·이력 UPDATE 거부·후속 version의 평일 적용 거부 |
| Frontend 전체 | `npm test`: **466개 성공**, 실패/skip/취소0 |
| Frontend lint/build | `npm run lint` 경고0, `npm run build` 성공, JS445.71kB/gzip127.42kB |
| Backend 전체 | 최종 `gradlew.bat --offline --no-daemon build` **성공(7분35초)**. 단위**238개**·통합**620개**, 실패/error/skip 모두0. compile·bootJar·jar·check 포함 |
| 검토/정리 | 양쪽 `git diff --check` 통과·문서 로컬 링크 누락0·신규 Java/SQL 후행 공백0. Testcontainers PostgreSQL/Ryuk 제거·이번 Gradle/worker/앱 Java 프로세스 종료 확인. 3000/8080/5433 수신 없음, 개인 PostgreSQL은 중지 상태, 기존 welog 컨테이너 유지 |

이번 첫 전체 실행에서는 `PostgresMigrationTests.upgradesV2WithBothExistingMigrationChecksumsAndUserDataPreserved` 한 곳이 최신 버전19를 기대해 실제20과 달랐다(620개 중619개 성공). 누락된 기대값만20으로 수정하고 전체 build를 다시 실행해 위 최종 결과를 확인했다. 검사/요구사항을 비활성화하지 않았다.

기존 최고 단일 세션·InitialPlan·추가 운동 제외·실제 수행·승인 중량·오프라인 계약을 그대로 유지한다. 브라우저 DOM/E2E·실기기·개인 DB 연동은 이번에도 미실행이다. 개인 DB는 기존 V17이며 **V18·V19·V20 모두 미적용**이다. 운영 카탈로그 분류 검증은 남아 있다. 기존2B를 포함한 최종 작업 트리는 **Backend43파일·Frontend8파일**, 양쪽 staged0이며 미커밋이다. HEAD는 Backend `84f52427a91befbbd87b66962531f7c2147999e3`, Frontend `f8691156e636d5b7d2d20cc2338610bae396e454`로 2A 커밋을 유지한다. 새 커밋·push·배포 없음, 추가 승인/정책 결정 대기 없음.
