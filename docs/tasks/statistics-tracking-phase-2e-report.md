# 통계·추적 — 2E 맨몸 운동 명시적 추가 중량

작성일: 2026-11-03 (Asia/Seoul). **2E 구현·자동 검증 완료.** 통계 전체/디로딩 전체 완료 보고서가 아니다.

## 1. 2D 검토와 커밋

[2D 보고서](statistics-tracking-phase-2d-report.md), V21, 전체 변경 경로/staged diff와 실제 JUnit XML을 대조했다. Backend 단위246/통합629 실패·error·skip0, Frontend482/lint/build 성공 기록을 확인했다. 개인 설정·비밀·빌드 산출물·불필요한 파일·의존성/lockfile 변경 없이 다음 로컬 커밋을 생성했다.

| 저장소 | 2D 커밋 | 범위 |
| --- | --- | --- |
| Backend | `8faef2179253be9aa561a3568fbe1a63bf94eec9` | 34파일, V21·유산소 실제 측정·출처·통계·호환성·검증/문서 |
| Frontend | `c071df3505c11a0e6e3f48c8dd358725f2dd8363` | 18파일, 실제 속도·거리/출처·정정·오프라인/통계 UI·검증/문서 |

커밋 후 양쪽 clean을 확인하고2E를 시작했다. 2D 보고서 마지막 미커밋 표현은 당시 검증 시점 기록이다. 원격 push·배포는 하지 않았다.

## 2. 구현과 변경 파일

현행 제품 정책은 [통계 v1.3](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.3.md) ST-36·37 그대로다. 디로딩 v1.3·이전 버전 원본도 보존한다. 새로운 제품 정책 판단 대기는 없다. 실제 데이터/API/오프라인 상세는 [Bodyweight Additional Load](../api/bodyweight-additional-load.md)를 따른다.

| 영역 | 변경 파일/내용 |
| --- | --- |
| DB | 신규 `V22__add_bodyweight_additional_load.sql`: nullable bodyweight_load JSONB 한 컬럼·BODYWEIGHT/형식/범위 CHECK. 기존 행/weight/이력 백필 없음 |
| 수행 DTO/검증 | `WorkoutSnapshotData`, `WorkoutSnapshotRequests`, `WorkoutSnapshotEntities`: optional bodyweightLoad, 구9필드 JSON 보존·명시적 제거 구분 |
| 쓰기/사후 정정 | `WorkoutSnapshotService`, `WorkoutRecordData`, `WorkoutRecordRequests`, `WorkoutRecordService`: 구버전 생략값 보호·정정 감사·누락 복구와 충돌 비교. 기존 평가·승인 로직 수정 없음 |
| 통계 | `StatisticsRepository`, `StatisticsCalculator`: 명시적 추가 중량×실제 반복만 집계, 무근거 null/세트·반복, 종목/근육/group/주·월 공통 공식 |
| Backend 신규 검증 | `BodyweightLoadRequestsTests`, `V22MigrationIntegrationTests` |
| Backend 기존 검증 보완 | `StatisticsCalculatorTests`, `WorkoutSessionIntegrationTests`; 최신22 기대값만 보완한 Catalog/EmailOtp/PostgresMigration/V9/V10/JpaJdbcCompatibility/SecuritySession 테스트 |
| Frontend 계약 | `workout-online/types.ts`, `validation.ts`, 신규 `bodyweight-load.ts`, `closed-model.ts`: optional 근거·제거·복구/감사 응답 유실 비교 |
| 오프라인/입력 | `workout-local/types.ts`, `validation.ts`, `model.ts`, `LocalWorkoutScreen.tsx`: optional 추가 중량 초안·별도 입력·취소 원본/재완료·기존 고정 요청 보존 |
| 기록/통계 UI | `ClosedWorkout.tsx`, `statistics/StatisticsPanel.tsx`: 추가 부하 정정·표시/감사·무근거 총중량 제외 안내 |
| Frontend 검증 | 신규 `bodyweight-load.test.mjs`, 기존 `workout-sync.test.mjs`, `statistics.test.mjs`, `workout-online-ui.test.mjs` |
| 문서 | 공통 DB/API 계약·세션/통계 API·양쪽 engineering/AGENTS/README·2D 후속 커밋 상태·이 보고서 |

원래 weight와 별도 근거를 저장하므로 과거80kg 맨몸 기록을 추가 부하80kg로 만들지 않는다. 추가10kg×실제8회는80kg이며 사용자 체중을 더하지 않는다. 명시적0과 근거 없는 null은 다르다. 기존 승인 중량과 InitialPlan/AppliedPlan·봉인 최초 평가·완료 당시 장비·오프라인 허용/계정·세대/편집권·고정 요청 계약은 유지한다. PRIMARY 다중 매핑/group 중복 제거와 SECONDARY 분리를 유지한다.

## 3. 검증

| 검증 | 결과 |
| --- | --- |
| 집중 단위 | `gradlew.bat --offline --no-daemon test --tests '*BodyweightLoadRequestsTests' --tests '*StatisticsCalculatorTests'`: 성공. 일반/과거 모호 중량 제외·명시적 추가/0·정밀도·무효 필드 거절·구JSON 동일·주/월·다중근육/group dedup |
| 세션 통합 | 신규 저장·구클라이언트 생략·정정/제거·누락 복구/충돌·삭제 유지·비BODYWEIGHT 거절과 추가 중량만 정정해도 승인 평가 자격/결과가 동일한 **신규5사례 모두 성공**. 전체 WorkoutSessionIntegrationTests119개 성공 |
| 격리 V22 | 채워진 V21→V22 모든 기존 행/과거 migration 이력 동일, 새 필드NULL·재적용0, 유효/무효 객체·다른 장비/NULL 장비 거절 검증 성공 |
| Frontend 집중 | 신규 근거·오프라인 ACK 유실/동일 원문·기존 주간/통계·null 표시 등50개 성공 |
| Frontend 전체 | 최종 `npm test` **493개 성공**, 실패/skip/취소0. 추가 중량 별도 빈 입력·구중량 비전용·실제 기록/감사 표시 SSR 포함 |
| Frontend lint/build | lint 경고0, TypeScript/Vite build 성공(JS463.24kB/gzip132.25kB) |
| Backend 전체 | 최종 `gradlew.bat --offline --no-daemon build` **성공(8분20초)**. 실제 JUnit XML: 단위**259개**(26suite), 통합**635개**(33suite), 실패/error/skip 모두0. compile·bootJar·jar·check 성공 |
| 파일/문서 | 양쪽 `git diff --check` 통과. 변경 문서 상대 링크150개 누락0·신규 Java/SQL/TS/MJS 후행 공백0. 의존성/lockfile·개인 설정 변경 없음 |

검증 중 V22 테스트가 기존 완료 장비를 바꾸려다 V17의 장비 불변 트리거에 차단됐다. 기존 규칙은 그대로 두고 새 행 삽입으로 신규 CHECK를 검증하도록 테스트를 수정한 뒤 V22 검증이 통과했다. 검사/테스트를 비활성화하거나 정책을 바꾸지 않았다.

실기기/브라우저 E2E·개인 DB 연동은 미실행이며 SSR/프로토콜 검증과 구분한다.

최종 검증 후 Testcontainers PostgreSQL/Ryuk와 이번 Gradle daemon/worker·앱 Java 프로세스가 남지 않았음을 확인했다. 3000/8080/5433 수신 없음. 개인 `overload-local-postgres-1`은 기존 중지 상태이고 기존 welog 컨테이너는 유지했다. 별도 개발/preview 서버·watch는 시작하지 않았으며 임시 Frontend 테스트 출력 파일은 확인 후 삭제했다.

## 4. DB·커밋·남은 작업

아래 미커밋/HEAD는2E 구현·검증 완료 시점의 기록이다. 이후 사용자 요청에 따라 이 보고서를 포함한 Backend29파일과 Frontend17파일을 각각 로컬 커밋한다. 정확한 커밋 해시는 각 저장소 Git 이력과 커밋 완료 보고를 따른다. 개인 DB 적용·push·배포는 이 커밋 작업에 포함하지 않는다.

2D는 위 두 커밋으로 완료했고2E는 별도 미커밋 변경(**Backend29파일·Frontend17파일, 양쪽 staged0**)이다. HEAD는 위2D 커밋을 유지한다. 개인 DB는 기존 승인된 V17이며 **V18~V22 모두 미적용**이다. 개인 DB/PostgreSQL을 시작·조회하거나 해당 DB 대상 Backend를 실행하지 않았다. 테스트는 별도 이름/임의 포트의 일회성 DB만 사용했다. push·배포 없음. 새 승인 요청 불필요.

운영 카탈로그의 실제 PRIMARY/SECONDARY·대분류 분류 검증, 디로딩3단계 식별/일반 승인 분리·혼재 검증, iPhone/Android 실기기 검증이 남는다. 이번 요청의 확정 정책을 변경해야 할 문제는 없다.
