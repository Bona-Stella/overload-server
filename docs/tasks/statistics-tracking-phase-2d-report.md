# 통계·추적 — 2D 유산소 실제 속도·거리

작성일: 2026-11-03 (Asia/Seoul). **2D 구현·자동 검증 완료.** 통계 전체·디로딩 전체 완료 보고서가 아니다.

## 1. 2C 검토와 커밋

2C 보고서와 변경 전체 경로·staged diff·JUnit XML을 확인했다. Backend 단위240/통합625 실패·error·skip0, Frontend473/lint/build 성공 기록과 승인 사건·배치 분리·검색·그래프 구현을 대조했다. 개인 설정/비밀/빌드 산출물/lockfile을 제외하고 다음 로컬 커밋을 만들었다.

| 저장소 | 2C 커밋 | 범위 |
| --- | --- | --- |
| Backend | `ee1f04a01f84db87746764f0eabd176bb7670329` | 14파일, 승인 사건의 배치별 성장 추적 |
| Frontend | `7cc48dce85d02141b116064eec0a3b2d6439207f` | 7파일, 기간·검색·배치 선택·성장 그래프 |

커밋 후 양쪽 clean을 확인하고2D를 시작했다. 2C 보고서의 미커밋 표현은 그 단계 검증 시점 기록이다. push·배포 없음. 제품 기준서는 [통계 v1.3](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.3.md) ST-47~52를 유지하며 새 정책 판단 대기는 없다.

## 2. 구현과 변경 파일

| 영역 | 실제 변경 |
| --- | --- |
| DB | `V21__add_actual_cardio_measurements.sql`: cardio_results.measurement nullable JSONB 하나·형식/계산 CHECK. 백필/기존 행 수정 없음 |
| 수행 결과 | `WorkoutSnapshotData`, `CardioMeasurements`, `WorkoutSnapshotRequests`, `WorkoutSnapshotEntities`, `WorkoutSnapshotService`: 선택 metadata·실제 속도/시간 계산·수동 우선·생략 필드 보호·원래 JSON/hash 유지 |
| 사후 정정 | `WorkoutRecordData`, `WorkoutRecordRequests`, `WorkoutRecordService`: 기존 version/감사 이력에서 실제 속도·출처 수정/제거·원래 timer/계획/평가 보존 |
| 통계 | `StatisticsCalculator`, `StatisticsRepository`, `StatisticsOverview`, `StatisticsService`, `PlanningCalculator`: source별/종류별 거리·전체 실제 시간·실제 속도 있는 목표만 비교 |
| Backend 검증 | 신규 `CardioMeasurementsTests`, `V21MigrationIntegrationTests`, 기존 `WorkoutSessionIntegrationTests`, `StatisticsCalculatorTests`, `PlanningCalculatorTests`; 최신21 기대값만 보완한 Catalog/EmailOtp/PostgresMigration/V9/V10/JpaJdbcCompatibility/SecuritySession 테스트 |
| Frontend 계약 | `workout-online/types.ts`, `validation.ts`, 신규 `cardio-measurement.ts`, `closed-model.ts`: 선택 측정 필드·정확한 BigInt 계산·복구 비교 |
| 오프라인 | `workout-local/types.ts`, `validation.ts`, `model.ts`, `controller.ts`, `LocalWorkoutScreen.tsx`: optional 실제 속도 초안·계산 미리보기·완료값/출처·기존 고정 원문 보존 |
| 사후 기록 UI | `ClosedWorkout.tsx`: 실제 속도/직접·계산·미확인 거리 표시·계산/직접 입력 정정·감사 이력 표시 |
| 통계 UI | `statistics/api.ts`, `StatisticsPanel.tsx`: overview의 optional cardioByKind 수용, 종류별·출처별 합계와 거리 미입력 구분 |
| Frontend 검증 | 신규 `tests/cardio-measurement.test.mjs`, 기존 `workout-sync.test.mjs`, `statistics.test.mjs` |
| 문서 | [실제 DB/API·클라이언트 계약](../api/cardio-actual-measurements.md), 세션/통계 API·양쪽 engineering/AGENTS/README·2C 후속 상태·이 보고서 |

InitialPlan·AppliedPlan/schema2·세션 날짜·체중/웨이트 승인·공식 비교·IndexedDB3·인증/CSRF·편집권·24시간·고정 snapshot/finish·의존성은 변경하지 않았다. 실제 값은 기존 시간/거리 경로와 nullable metadata로만 확장했다.

## 3. 데이터 근거와 호환성

- 직접 입력/정정 거리 우선이며 실제 속도×활동 ms/3,600,000으로만 계산한다. 목표 속도는 실제값으로 쓰지 않는다. 소수6자리 HALF_UP·BigDecimal/BigInt이며 부족한 정보는null이다. 계산0과 미입력은 다르다.
- 출처는 MANUAL/CALCULATED/null이다. 과거 measurement는NULL 그대로이며 과거 직접 입력이라고 소급 분류하지 않는다. 알려진 과거 거리 자체는 통계에서 지우지 않는다.
- measurement가 없으면 JSON에서 새 키를 생략해 과거5필드 직렬화와 canonical hash를 유지한다. 고정 요청 원문·구버전 IndexedDB 문서를 재작성/업그레이드하지 않는다.
- 새 UI는 계산 거리를 수동 입력란에 가장하지 않으며, 직접 입력하면 다시 계산값으로 덮지 않는다. 기존 출처 미확인 거리를 건드리지 않았다면 미확인 상태를 유지한다. 구버전 쓰기가 metadata를 생략해도 기존 실제 속도와 제출 거리값을 보존한다. 근거가 깨진 CALCULATED 주장은 미확인으로 내려놓고 감사 이력에 전후 값을 남긴다.
- 전체 실제 시간은 합산하고 거리는 종류별/출처별이다. 추가 유산소도 동일하며 디로딩 종류 연결은 후속3단계다. 실제 경사/저항은 새로 추정하지 않는다.

## 4. 검증

| 검증 | 현재 결과 |
| --- | --- |
| 계산/JSON | 직접 거리 우선·4km 예시·반올림·0·정보 부족·거짓 계산/범위 거절·5필드 JSON 동일성·생략 필드/정정 보호 단위 검증 통과 |
| 격리 V21 | 채워진 V20→V21의 기존 모든 행/이력 동일·measurement NULL·재적용0·잘못된 계산 거절·수동/계산0 검증 통과 |
| 집중 통합 | `gradlew.bat --offline --no-daemon test integrationTest --tests '*WorkoutSessionIntegrationTests' --tests '*StatisticsIntegrationTests' --tests '*V21MigrationIntegrationTests'` 성공. 단위246개·해당 통합133개. 실제 측정·시간 정정·수동 덮어쓰기 방지·실패 rollback·자정 시작일 귀속·고정 FINISH 재전송·목표 속도 비추정·기존 세션/승인 회귀 |
| Frontend 전체 | `npm test`: **482개 성공**, 실패/skip/취소0 |
| Frontend lint/build | `npm run lint` 경고0, `npm run build` 성공, JS461.23kB/gzip131.63kB |
| Backend 전체 | 최종 `gradlew.bat --offline --no-daemon build` **성공(8분52초)**. 단위**246개**·통합**629개**, 실패/error/skip 모두0. compile·bootJar·jar·check 성공 |
| 파일/문서 | 양쪽 `git diff --check` 통과. 변경 문서 링크 누락0·신규 Java/SQL 후행 공백0. 의존성/lockfile/개인 설정 변경 없음 |

검증 과정에서 신규 테스트의 Instant import 누락과 기존 calculate helper 이름 충돌을 수정했다. 자정/활동 테스트 시각은 기존 startedAt 이후로 맞췄다. 요구사항·검사·기존 테스트를 비활성화하지 않았다. 첫 V21/통계 집중 검증 후 새 실제 세션 검증까지 단계적으로 확장했다.

DOM/E2E·iPhone/Android 실기기·개인 DB 연동은 미실행이다. SSR/프로토콜 테스트를 실기기 검증으로 표시하지 않는다.

최종 검증 후 Testcontainers PostgreSQL/Ryuk와 이번 Gradle daemon/worker·앱 Java 프로세스가 남지 않았음을 확인했다. 3000/8080/5433 수신 없음. 개인 `overload-local-postgres-1`은 기존 중지 상태이고 기존 welog 컨테이너는 유지했다. 별도 개발/preview 서버·watch는 시작하지 않았으며 임시 Frontend 테스트 출력 파일은 확인 후 삭제했다.

## 5. DB·커밋·남은 작업

개인 DB는 기존 승인된 V17이며 **V18·V19·V20·V21 모두 미적용**이다. 개인 PostgreSQL을 시작/조회하거나 실제 DB 대상 Backend를 실행하지 않았다. migration 검증은 임의 포트/별도 이름의 일회성 Testcontainers만 사용했다. 운영 카탈로그 분류 검증은 남아 있다.

2C는 위 두 커밋이고2D는 별도 미커밋 변경(**Backend34파일·Frontend18파일, 양쪽 staged0**)이다. HEAD는 Backend `ee1f04a01f84db87746764f0eabd176bb7670329`, Frontend `7cc48dce85d02141b116064eec0a3b2d6439207f`를 유지한다. 원격 push·배포 없음. 명시적 맨몸 추가 부하·디로딩3단계 식별/평가 분리/혼재 검증·실기기 검증은 후속이다. 새로운 정책 결정 대기는 없다.
