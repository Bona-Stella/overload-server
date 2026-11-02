# 통계·추적 — 2C 승인 중량 성장 추적

후속 커밋 상태: Backend `ee1f04a`, Frontend `7cc48dc`로2C를 커밋했다. 커밋 검토·이후2D는 [2D 보고서](statistics-tracking-phase-2d-report.md)를 따른다. 아래 미커밋 설명은 커밋 전 검증 시점 기록이다.

구현 시작: 2026-11-02, 최종 검증: 2026-11-03 (Asia/Seoul). **2C 일반 승인 성장 추적 구현·자동 검증 완료.** 통계 전체·디로딩 전체 완료 보고서가 아니다.

## 1. 2B 커밋 검토와 결과

2B 보고서6절과 실제 JUnit XML을 대조했다: Backend 단위238·통합620 모두 실패/error/skip0, Frontend466·lint·build 성공. V19는 이력2표, V20은 최초 version1의 평일 적용 CHECK만 보완하며 기존 행 백필/UPDATE가 없다. 통계/디로딩 v1.3의 최초 당일 적용·첫 주 부분 분모·기존 변경의 다음 월요일 및 최고 단일 세션 정책과 구현을 확인했다. 개인 DB V18~V20 미적용·운영 카탈로그 분류 미검증은 유지했다.

| 저장소 | 2B 로컬 커밋 | 포함 |
| --- | --- | --- |
| Backend | `7e442e362e3a64951ddc988202a061e9dfae4f36` | 43파일, versioned weekly plans and achievement tracking |
| Frontend | `fd3e84739272cf87b6e02124616cc135552e2b05` | 8파일, weekly schedules and plan achievement views |

staged 전체 경로와 diff를 검토해 개인 설정/비밀/산출물/lockfile 변경은 없었다. 제품 원문에서 이어진 의도적 Markdown 줄바꿈 공백11개는 보존했으며 그 외 코드 공백 검사를 통과했다. 2B 커밋 후 양쪽 clean을 확인한 뒤 2C를 시작했다. 이전2B 보고서의 미커밋 표현은 검증 시점의 보존 기록이며 이 표가 이후 커밋 상태다. push·배포는 하지 않았다.

## 2. 실제 구현과 변경 파일

- Backend `statistics/GrowthQuery.java`, `GrowthResponse.java`, `GrowthRepository.java`, `GrowthService.java`, `StatisticsController.java`: 1/3/6개월·전체, literal 과거 이름 검색·최근 수행 우선·삭제 루틴 보존·배치 목록·개별 배치의 승인 이력 페이지 API.
- `GrowthQueryTests.java`, `StatisticsIntegrationTests.java`: 달력/시간대·엄격한 입력·실제 OTP/CSRF 승인 쓰기 재사용·동일 운동의 서로 다른 루틴/배치·기간·삭제·추가 수행 검색·페이지·모호한 시각·권한·read-only. 기존 `WorkoutSessionIntegrationTests`에는 실제 순차 WORKOUT 승인3건의 성장 조회와 세션/이력 무변경 검증을 추가했다.
- Frontend `features/statistics/growth.ts`, `GrowthPanel.tsx`, `StatisticsPanel.tsx`, `api.ts`, `tests/growth.test.mjs`: 요약→성장 상세, 기간 선택/과거 검색/배치 선택, SVG 그래프·정확한 decimal/선 연결 검증·이벤트 상세·기존 세션 링크·페이지/오류/abort·scope 검사.
- [실제 API·데이터 계약](../api/approved-weight-growth.md), 통계 API 링크·양쪽 AGENTS/README·이 보고서·2B 후속 상태 안내.

DB 표·컬럼·migration·현재 승인 중량·승인 쓰기/평가·실제 수행/정정·오프라인/IndexedDB/타이머·의존성은 바꾸지 않았다. 제품 기준서는 [통계 v1.3](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.3.md) ST-38~42를 그대로 적용한다. 새로운 제품 정책을 확정하지 않았다.

## 3. 승인 성장선의 근거

성장값은 progress_change_events의 next_weight와 보존 occurred_at이다. recorded_at도 구분해 표시한다. 표준 운동 ID는 FK로 보존된 배치에서 연결하며 **routineId+placementId별로 분리**한다. 같은 운동의 다른 배치를 하나의 선으로 합치거나 초기/현재 중량으로 과거 점을 만들지 않는다. 검색은 선택 기간의 유효 수행과 승인 이력을 합치며 InitialPlan/추가 종목/승인 당시 이름도 조회한다. 수행 없는 계획은 실제 이력으로 만들지 않는다.

같은 배치 안에서도 앞 사건 UUID·이전/다음 중량·발생/기록 시각의 연속 근거가 맞아야 선을 잇는다. 동일 시각/역전/불일치는 점으로 유지한다. 서버가 계산한 previousPointId가 실제 로드한 앞 점과 달라도 연결하지 않는다. 기간 밖 baseline·미로딩 사건·UUID 순서로 승인 적용 순서를 추정하지 않는다. 검색50건·점100건 단위로 더 읽으며 일부 로드 상태를 안내한다. 기존 승인 사실은 세션 정정/삭제로 지우지 않는 계약을 유지한다.

**디로딩 제외 정책은 유지한다.** 현재 V20에는 디로딩 식별/쓰기 자체가 없어 혼재 자료를 실제로 분류·배제했다고 주장하지 않는다. 일반 승인 사건의 기존 source만 허용하고 수행량·이름으로 디로딩을 추정하지 않는다. 후속3단계의 명시적 분류 및 일반 승인 사건 생성 차단을 연결한 뒤 혼재 검증이 필요하다. 별도 정책 결정 대기는 없다.

## 4. 검증

| 검증 | 현재 결과 |
| --- | --- |
| Backend 집중 | `gradlew.bat --offline --no-daemon test integrationTest --tests '*StatisticsIntegrationTests'` 성공. 단위 전체240개 및 통계 통합18개 |
| Frontend 전체 | `npm test`:473개 성공, 실패/skip/취소0. source/배치/시각/정확한 decimal·누락 predecessor·비진행 페이지 응답 거절 포함 |
| Frontend lint/build | `npm run lint` 경고0, `npm run build` 성공, JS456.95kB/gzip130.37kB |
| Backend 전체 | 최종 `gradlew.bat --offline --no-daemon build` **성공(10분31초)**. 단위**240개**·통합**625개**, 실패/error/skip 모두0. 과거 InitialPlan 이름 검색 보완·실제 순차 WORKOUT 승인3건 조회·세션/승인 무변경 회귀 포함. compile·bootJar·jar·check 성공 |
| 변경 검토 | 양쪽 `git diff --check` 통과, 변경 문서 링크 누락0·신규 Java/SQL 후행 공백0. V1~V20 migration 변경 없음 |

읽기 전용 테스트에서 인증 계층 쿼리까지 통계 트랜잭션으로 검사해 발생한 실패는 통계 소유자 조회/성장 CTE만 관찰하도록 보완했고 HTTP 인증 검증은 유지했다. SVG의 title을 여러 텍스트 child로 준 탓에 점 설명이 빈 값이 된 문제는 단일 문자열로 고쳤다. 카탈로그 이름 변경 후 최초 계획의 당시 이름이 검색되지 않을 수 있는 경로를 InitialPlan fallback으로 보완하고 해당 회귀를 추가했다. 검사/요구사항을 끄거나 기존 계약을 바꾸지 않았다.

DOM/E2E·실기기·실제 개인 DB 연동은 미실행이며 SSR을 브라우저 검증으로 표시하지 않는다. 최종 build 종료 후 Testcontainers PostgreSQL/Ryuk 제거·Gradle daemon/worker 및 앱 Java 프로세스 종료를 확인했다. 3000/8080/5433 수신 없음. 개인 `overload-local-postgres-1`은 기존 중지 상태이고 기존 welog 컨테이너는 유지했다. 별도 개발/preview 서버·watch 프로세스는 시작하지 않았다. 임시 Frontend 테스트 출력 파일은 확인 후 삭제했다.

## 5. 커밋·DB·남은 작업

2B는 위 두 커밋에 포함됐다. 2C는 별도 미커밋 작업 트리(**Backend14파일·Frontend7파일, 양쪽 staged0**)이며 HEAD는 표의2B 커밋 그대로다. 개인 PostgreSQL을 시작하거나 V18·V19·V20을 적용하지 않았다. 격리 Testcontainers만 사용한다. 원격 push·배포 없음. 운영 카탈로그 분류 검증은 남아 있다.

통계 후속은 실제 유산소 속도/자동 거리·출처 저장, 명시적 맨몸 추가 부하, 디로딩3단계 연결이다. 2C는 일반 승인 성장 추적 구현이며 디로딩 혼재 검증/통계 전체 완료가 아니다.
