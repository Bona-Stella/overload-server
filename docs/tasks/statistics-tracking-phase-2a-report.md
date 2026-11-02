# 통계·추적 — 2A 실제 기록 읽기 집계와 화면

작성일: 2026-11-02 (Asia/Seoul). **2A 구현·자동 검증 완료, 2단계 전체는 진행 중**. 이 문서는 2A의 실제 구현과 한계를 기록하며 2단계 전체 완료를 뜻하지 않는다.

후속 현재 상태: 2A는 Backend `84f5242`, Frontend `f869115`로 커밋했고 주간 일정·달성률의 이후 구현은 [2B 보고서](statistics-tracking-phase-2b-report.md)를 따른다. 아래 잔여 항목과 미커밋 설명은 해당 단계의 시점과 구분한다.

## 1. 0·1단계 커밋과 제목 변경

- Backend 시작 HEAD `8e6fd74`, Frontend `d33e156`. Backend 변경20파일은 앞선 작업의 코드·테스트·설계뿐이었고 Frontend는 clean이었다.
- 로컬 커밋 **`7139ceac0ce1aa947be1ebbb5d662fb2c962a78e`**, `feat(catalog): add joint classification and phase 0-1 design baseline`.
- 포함 범위: V18/StandardExercise·품질 감사 SQL·마이그레이션/HTTP/JPA 회귀·0·1단계 보고서·기술 문서·제품 기준서3개·AGENTS/README. `.env`·개인 설정·build/dist/log는 포함하지 않았다. Frontend는 커밋 대상이 없었다.
- 기준서 파일명과 최상위 문서 제목만 영어로 바꿨다. [Statistics and Tracking v1.1](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.1.md), [Deloading v1.2](../product/Overload_Deloading_Design_Specification_v1.2.md), 보존용 Deloading v1.1이다. 제목 이후 본문 동일성을 검사했으며 정책 본문·규칙 ID·버전은 번역하지 않았다. Downloads 원본은 수정하지 않았다.
- 코드 변경 없는 커밋 전에는 직전 전체 검증 결과(Backend 단위220+통합605 / Frontend453·lint·build)를 재확인했고 제목/링크·staged 범위를 검사했다. 원문 Markdown hard break13개는 보존했다. 테스트를 다시 실행한 것으로 표시하지 않는다.

## 2. 이번 구현

신규 migration/의존성 없이 기존 InitialPlan·완료 세트·유산소 결과·표준 근육 연결과 인증·기존 이력 화면을 재사용했다. [실제 API 계약](../api/statistics.md)을 함께 작성했다.

| 위치 | 변경 |
| --- | --- |
| Backend `statistics/StatisticsQuery`, `StatisticsController`, `StatisticsService`, `StatisticsRepository` | 날짜/쿼리 엄격 검증, principal·현재 세대, read-only REPEATABLE_READ·6개 고정 조회, 기존 관계형 유효 실적 읽기 |
| Backend `statistics/StatisticsCalculator`, `StatisticsResponse` | 실제 중량×반복, 종료 세션 InitialPlan 계획, 전체·날짜·운동·세부 근육·역할별 대분류, 유산소 시간/종목별 직접 거리, 최신3건 |
| Backend `statistics/StatisticsFailure`, `workout/WorkoutReadErrors` | 기존 읽기 HTTP 오류·no-store 계약 재사용. 인증 정책 변경 없음 |
| Backend `statistics/StatisticsCalculatorTests`, `StatisticsIntegrationTests` | 계산 경계·실제 인증 HTTP·격리 PostgreSQL·소유권·자정·삭제/정정/누락 사실·무변경 읽기/트랜잭션 |
| Frontend `src/features/statistics/api.ts` | 기존 authenticated transport 재사용, scope/기간/정확한 decimal·counter 런타임 검증, 월~일·달력 월 구간 |
| Frontend `StatisticsPanel.tsx`, `statistics.css` | 이번 주 요약→기간별 실제 운동량/운동별·근육별 상세, 동일 세트/총중량 UI, SECONDARY 분리, 유산소 시간·종목별 거리, 최근3건 |
| Frontend `WorkoutWorkspace.tsx`, `ClosedWorkout.tsx` | 실제 계정 경로·기존 상세/이력 이동·exit guard 연결. `/demo` 데이터나 오프라인 기록을 통계로 이관하지 않음 |
| Frontend `tests/statistics.test.mjs` | 범위/기간·큰 decimal·변조 응답·달력 경계·SSR 정보 구조·맨몸/계획 없는 상태 회귀 |

0·1단계 커밋과 2A 작업 트리는 분리했다. 2A 최초 보고 시에는 미커밋 상태였으며, 후속 사용자 요청으로 Backend와 Frontend 각각 로컬 커밋 대상으로 검토했다. 실제 커밋 해시는 후속 2B 보고서에 기록한다. 원격 push·배포는 수행하지 않는다.

## 3. 제품 기준서 대응 및 남은 일

| ST 항목 | 실제 상태 |
| --- | --- |
| 05~09 | 이번 주 요약·주/월 실제 상세·최근3건/기존 이력 연결 구현. 성장 추적 상세는 미구현 |
| 10~16,24,29 | 종료 세션의 InitialPlan과 실제를 구분하고 추가/초과·계획0·현재 유효 정정/삭제/복구 반영. **미시작 날짜를 포함한 주간 전체 계획은 아직 없음** |
| 25~28,30~35 | 복수 PRIMARY·SECONDARY 분리·대분류 및 전체 중복 제거, 실제 weight×reps, 운동/근육 공통 그래프 구현 |
| 36~37 | 의미가 불명확한 BODYWEIGHT 중량을 추정하지 않고 제외/표시. 명시적 추가 부하 구분·합산은 후속. 확인 가능한 중량만 소계로 표시 |
| 38~42 | 기존 승인 사실은 보존. 성장선·검색/기간별 조회는 미구현 |
| 43~49,52~55 | 실제 주/월 세트·유산소 시간·종목별 직접 거리, 시작 날짜 귀속·기존 이력 연결 구현. 전체 계획과 유산소 계획 목표 비교·디로딩 표시는 후속 |
| 17~23,50~51,56~57 | 전체 주간 루틴 달성률·실제 속도/계산 거리 저장·디로딩 연결은 미구현 |

계획 없는 과거 날짜를 현재 루틴으로 메우지 않는다. `plannedWorkoutDays`/`routineAchievementPercent`는 null이며 UI에도 확인 불가로 표시한다. 계획 세트는 종료 기록의 시작 계획 범위임을 명시한다. BODYWEIGHT의 기존 숫자를 새 정책 없이 추가 중량으로 재해석하지 않는다. 제품 정책은 이번에 변경하지 않았다.

다음 구현 단위: 실제 계정용 주간 일정/적용 시점 이력 → InitialPlan 종목 완료 판정/주간 분모 → 승인 중량 이력의 배치별 성장선·과거 종목 검색 → 유산소 실제 속도/거리 출처의 기존 고정 요청 호환 저장. 디로딩 자체와 평가 제외 연결은 3단계에서 구현하며 선행 완료로 간주하지 않는다. 정책 추가/충돌이 발견되면 해당 항목과 대안을 별도로 보고한다.

## 4. 검증 및 제한

| 실행 | 최종 결과 |
| --- | --- |
| Backend `gradlew.bat --offline --no-daemon test integrationTest --tests '*Statistics*'` | 단위226개(신규6 포함)·신규 PostgreSQL HTTP 통합4개 통과. 마지막 task의 테스트 선택 옵션과 전체 단위 실행을 구분 |
| Backend `gradlew.bat --offline --no-daemon build` | **성공**. 단위21 suite/226개 + PostgreSQL 통합29 suite/609개, 실패/오류/skip0. 전체835개 검증 결과 확인 |
| Frontend `npm test` | **460개 성공**, 실패/skip/취소0(신규7 포함) |
| Frontend `node --test tests/statistics.test.mjs` | 마지막 UI 보완 후 신규7개 재확인 통과 |
| Frontend `npm run lint`, `npm run build` | 마지막 코드/CSS 기준 경고0·성공. JS435.66kB/gzip124.98kB |
| 문서 링크·영어 제목 이후 정책 본문 동일성·양쪽 `git diff --check` | 통과. 새 migration·기존 migration 수정 없음 |

신규 통계 통합 테스트의 정정·삭제·누락 수행 복구 데이터는 격리 SQL fixture로 준비하고 새 HTTP 읽기 결과를 검증했다. 기존 수행 쓰기 API 자체의 검증은 전체 workoutsession 회귀에 포함하며 이 fixture를 새 기록 쓰기 기능 구현으로 보고하지 않는다.

첫 신규 통합 검증의 실패2개는 테스트 fixture 문제였다. 미리 만든 ACTIVE 때문에 다음 fixture 생성이 사용자별 ACTIVE UNIQUE에 걸렸고, Spring Session의 대문자 SQL을 통계 읽기 트랜잭션으로 오인해 읽기 전용 assertion이 실패했다. 생성 순서와 관찰 대소문자 범위만 고쳤으며 실제 UNIQUE·인증·읽기 전용 검사를 유지한 재실행에서4개 모두 통과했다. Frontend에서는 invalidateScope 콜백 인자 타입을 맞추고 effect 의존성 경고를 해소했다.

브라우저 DOM 상호작용·iPhone/Android 실기기·실제 개인 DB 연동은 이번 단위에서 검증하지 않았다. SSR 자동 검증을 실제 브라우저 검증으로 표시하지 않는다. 기존 오프라인 큐·DB3/schema2·고정 요청 원문은 변경하지 않았다.

전체 검증 뒤 일회성 Testcontainers가 남지 않았고 GradleDaemon/GradleWorker/이 저장소 Java 프로세스 및 3000/8080/5433 listener가 없음을 확인했다. npm 명령은 모두 종료했고 별도 개발/미리보기 서버·watch·브라우저는 시작하지 않았다. 기존 개인 DB 컨테이너는 계속 정지 상태이며 다른 사용자 컨테이너를 보존했다.

## 5. DB·카탈로그·승인 상태

**기존 개인 DB에는 V18을 적용하지 않았다.** 이번 2A에서는 개인 DB를 시작하거나 조회하지 않았으며 새 migration도 없다. V18 코드는 격리 테스트에서만 검증한다. 운영 카탈로그의 정확한 분류 검토는 계속 남아 있으며 집계 구현으로 분류 검토가 완료된 것은 아니다. 새 local Backend를 개인 DB에 연결해 실행하면 Flyway의 대기 V18이 자동 적용될 수 있으므로 실제 적용과 격리 검증을 계속 구분한다.

요청 범위 내 읽기 기능·테스트·문서 구현에 추가 승인은 불필요하다. 운영 환경·사용자 데이터·승인 중량을 변경하지 않았다. 현재 정책 결정 대기 사항은 없으며, 미구현 기능과 검증 한계를 명시했다.
