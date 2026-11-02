# 통계·추적 및 디로딩 — 0단계 조사 / 1단계 카탈로그 정비

작성일: 2026-11-02 (Asia/Seoul). **0단계 조사 완료. 1단계 구조 구현·자동 검증 완료. 정확한 운영 카탈로그 분류·개인 DB V18 적용은 미완료. 통계·디로딩 제품 기능은 아직 구현하지 않았다.**

이 문서는 0·1단계 당시의 조사/완료 기록이다. 후속 통계 구현의 현재 상태는 [2A 보고서](statistics-tracking-phase-2a-report.md)를 확인한다. 아래 2단계 체크리스트는 착수 전 기준이며 현재 구현 상태와 구분한다.

## 1. 요청·기준과 작업 경계

사용자 요청은 개발착수 지시서를 읽고 단계별로 진행하는 것이다. 지시서의 시작 단위인 0단계 조사와 1단계 카탈로그 정비를 수행했다. 첨부 문서가 언급한 기존 기능을 구현 완료로 간주하지 않고 실제 코드·스키마·화면 경로와 대조했다. 문서의 데이터 정비 지시를 기존 기록 삭제나 자동 분류의 승인으로 해석하지 않았다.

제품 정책 v1.1 원본은 Downloads에서 수정 없이 다음 위치로 복사했고 그대로 보존했다. 0·1단계 보고 뒤 사용자가 CHECK-08 배치안을 승인하여 별도 [디로딩 v1.2](../product/Overload_Deloading_Design_Specification_v1.2.md)에 DL-51·CHECK-08·수용 테스트·변경 이력을 반영했다. 현재 제품 기준은 통계 v1.1과 디로딩 v1.2이며, 정책 확정과 기능 구현 상태는 별개다.

| 문서 (제목만 영어 변환) | Downloads 원본 SHA-256 |
| --- | --- |
| [통계·추적 v1.1](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.1.md) | `F3E302993895B4958829563FACEBC627CC9A53E25F863F17A53BF05C95A91243` |
| [디로딩 v1.1](../product/Overload_Deloading_Design_Specification_v1.1.md) | `3193E3D09E6364E38884A9B54843D95CA7EFF907D82D8148116C151704E4689A` |

시작 시 Backend `8e6fd74`, Frontend `d33e156`, 양쪽 작업 트리는 clean이었다. Java 25·Spring Boot 4.1.2·Gradle 9.8.2·JPA/JDBC·Flyway·Testcontainers, React/TypeScript/Vite·native IndexedDB를 실제 빌드 설정에서 확인했다. 새 의존성을 추가하지 않았다. `AGENTS.md`, 양쪽 Engineering, Task 009 v1.0, Task 006의 관련 도메인 정책, 카탈로그 설계·Backend 2단계·실제 개발 DB 4단계 보고서와 루틴/세션 계약을 확인했다.

### 커밋 전 사용자 요청 반영

사용자 요청으로 기준서 3개(통계 v1.1, 디로딩 v1.1/v1.2)의 파일명과 맨 위 문서 제목만 영어로 변환했다. 본문·규칙 ID·제품 정책은 변경하지 않았으며 변환 전후 첫 줄 이후 내용의 동일성을 검사했다. 위 SHA-256은 제목 변환 전 Downloads 원본의 값이다. Downloads 원본 파일은 변경하지 않았다. 현재 기준은 `Overload_Statistics_and_Tracking_Design_Specification_v1.1.md`와 `Overload_Deloading_Design_Specification_v1.2.md`다. 개인 DB V18 미적용·정확한 운영 카탈로그 검증 미완료 상태는 유지한다.

신규 파일까지 포함한 staged 공백 검사에서는 제품 기준서 원문의 Markdown 줄바꿈용 끝 공백 2칸 13곳이 표시됐다. 제목만 변환하라는 요청에 따라 원문을 보존했으며 다른 파일의 공백 검사는 통과했다. 이전 untracked 파일 제외 diff 검사와 구분한다.

## 2. 실제 DB 조사

Backend나 Flyway를 실행하지 않고 기존 정지 컨테이너만 잠시 시작했다. `BEGIN READ ONLY` 안에서 카탈로그·이력·건수만 조회하고 정지 상태로 복원했다. 인증 정보·개인 식별 정보를 조회하거나 기록하지 않았다.

| 항목 | 실제 확인 결과 |
| --- | --- |
| 컨테이너 / 연결 대상 | `overload-local-postgres-1` / 기존 `127.0.0.1:5433/overload_dev` |
| DB / role / 클러스터 | `overload_dev` / `overload_dev` / `7701299869222748199` |
| 실제 Flyway | V1~V17 성공 17건. 파일 목록도 V17까지였으므로 신규 번호는 V18 |
| 카탈로그 | 활성 표준 운동 5개, 근육 연결 10개(PRIMARY 7 / SECONDARY 3) |
| 기존 업무 데이터 | 루틴 1개, 세션 2개, 세트 9행(유효 8), 유산소 결과 0행 |
| 미존재 구조 | 주간 일정·디로딩·통계 전용 테이블 없음 |
| 쓰기 / migration | 실행하지 않음. 개인 DB는 V17을 유지 |

5개 운동은 바벨/덤벨 벤치프레스, 불가리안 스플릿 스쿼트, 하이로우, 랫풀다운이다. 연결된 근육은 가슴·삼두·대퇴사두·둔근·광배·상부 등의 **개발 예시**다. 불가리안 스플릿 스쿼트와 하이로우는 PRIMARY 2개씩이다. 이는 복수 PRIMARY를 저장할 수 있다는 근거이며 해부학적 정확성이나 운영 분류 검토가 완료되었다는 근거는 아니다. 어깨·코어, 가슴 세부 구분 등 제품 자동 편성에 필요한 분류를 대표하지 않는다.

## 3. 개념별 실제 대응과 차이

Backend 소스 기준은 `src/main/java/com/overload/overload_server/`, Frontend는 `../overload-pwa/src/`다.

| 개념 | 실제 대응 / 이미 구현된 부분 | 남은 부분 |
| --- | --- | --- |
| InitialPlan | `workoutsession/InitialPlan.java`, `workout_sessions.initial_plan_snapshot`, schema2·DB 불변 trigger. 루틴 ID/version·출처 배치·종목·세트/반복·초기/승인 중량·유산소 목표 복사 | 최초 계획 종목 완료율 계산 재사용 함수·통계 API 없음 |
| AppliedPlan | `WorkoutSnapshotData.Plan`, `applied_plan_snapshot`, 진행 편집·최초 종료 봉인 | 달성률 분모로 사용하면 안 됨. 디로딩 임시 계획 출처 없음 |
| 실제 세트 | `performed_sets` + `session_exercises.standard_exercise_id`; 행 자체가 완료 사실. 실제 weight/reps·ordinal·장비·승인 문맥 저장 | `Σ(weight × reps)` 집계, 근육별/기간별 조회 없음 |
| 정정·삭제·복구 | `WorkoutRecordService`, `record_change_events`, expectedVersion, 논리 삭제·누락 사실 명시 복구·현재 평가 재계산 | 통계는 감사 사건을 더하지 않고 현재 유효 수행을 읽어야 함. 삭제한 세트의 자동 부활이나 전체 세션 복원 기능으로 확대 해석하지 않음 |
| 유산소 시간·거리 | `WorkoutSnapshotData.CardioValue(timerMs,activeMs,corrected,distanceKm,averageBpm)`, `cardio_results`, FE `workout-local/model.ts`·`ClosedWorkout.tsx` | **실제 속도 없음**, 계산/직접 입력 거리 출처 없음. 계획 intensity SPEED/PACE를 실제 속도로 대체할 수 없음 |
| 승인 중량 이력 | `routine/ProgressChangeEvent.java`, `progress_change_events`; previous/next·occurred/recorded 시각·배치·세션 출처 보존 | 실제 계정용 기간/종목별 성장선 조회 없음. 배치 FK→standardExerciseId 연결로 과거/제거 배치를 조회해야 함. 서로 다른 배치의 승인선을 임의 하나로 합치지 않음 |
| 근육 PRIMARY/SECONDARY | `catalog/Muscle`, `ExerciseMuscle`, `muscle.group_code`; 운동/근육 쌍 PK·복수 PRIMARY 허용 | 정확한 운영 데이터 없음. PRIMARY/SECONDARY 역할별 집계·대분류 중복 제거 구현 필요 |
| 시작 날짜 | `WorkoutSessionRequests.start`가 startedAt+IANA zone과 workoutDate 일치를 검사. DB 시작 문맥 불변 | ST-53~54와 일치. 시작일 기준 조회와 자정 경계 회귀를 통계에 연결해야 함 |
| 오프라인 동기화 | FE `workout-local` IndexedDB3/schema2·고정 snapshot/finish·ACK 재확인·명시 재진입·계정/세대 fence | 로컬 미동기화 실적은 서버 통계와 구분. 새 필드 도입 때 고정 요청 원문·초안/타이머를 보존해야 함 |
| 요일별 계획 | 실제 계정 `RoutineResponses.Detail`/`Routine`에는 요일·주간 빈도 없음. `RoutineController`도 루틴 CRUD만 제공 | `src/App.tsx`·`features/workout/model.ts`의 요일 schedule은 `/demo` 목업. 실제 계정에 서버 주간 일정/적용 시점 이력이 필요 |
| 과거 예정일 | 시작한 세션은 당시 InitialPlan 보존. routine.version은 동시성 counter이며 모든 과거 계획의 저장본이 아님 | **시작하지 않은 과거 날짜의 계획은 재현 불가.** 현재 루틴으로 소급 분모를 만들 수 없음 |
| BODYWEIGHT | 완료 당시 load_type과 숫자 weight 저장 | 별도 체중/추가 부하 의미 필드 없음. 기존 양수 weight를 근거 없이 추가 중량으로 확정하거나 체중 환산하면 안 됨 |
| 디로딩 | 실제 도메인·기간·임시 루틴·분석 설정·평가 제외 마커 없음 | DL-01~68 기능 신규 구현 필요. 기존 24시간 AUTO_CLOSED는 10일 디로딩 종료와 다른 기능 |
| 화면/이력 | 실제 계정 `WorkoutWorkspace`·`ClosedWorkout`·`WorkoutHistory`, 루틴 폼·수행 편집 UI 있음 | 통합 대시보드와 기간/근육/성장 상세 없음. `/demo`의 주간 루틴·승인 이력 화면은 재사용 후보이며 실제 API 연동 완료 기능이 아님 |

## 4. 정책 정합성 및 판단이 필요한 경계

### 이미 최신 정책으로 해결된 차이

- 이전 카탈로그 설계의 SECONDARY=0.5 제안은 ST-25~28·35와 충돌한다. 최신 v1.1에 따라 **역할별 분리 집계**로 저장소 설계를 수정했다. 제품 정책 재결정은 필요 없다. 기존 제품 통계 API는 없어 런타임의 계산 계약을 깨지 않는다.
- 시작 날짜 고정·자정 분할 없음·심야 제한 없음은 기존 시작 검증과 일치한다. 변경할 정책이 아니다.
- 스케줄/시점 이력·실제 유산소 속도/거리 출처·디로딩 마커 부족은 구현 차이다. 기존 승인·수행 계약을 보존하며 필요한 최소 구조를 설계한다. 범용 Outbox/명령/feed를 다시 도입하지 않는다.

### 임의 확정하지 않은 항목

1. **분류 데이터:** jointType과 정확한 PRIMARY/SECONDARY 데이터 검토가 필요하다(DL-39). 미확인 항목을 이름·장비·근육 개수로 채우지 않았다. 구조 검사를 통과한 것을 자동 편성 가능 판정으로 사용하지 않는다.
2. **재현 불가 과거 계획:** 새 이력 저장 이전의 미시작 예정일은 알 수 없다(ST-10·15의 구현 확인 항목). 실제 수행·보존 InitialPlan은 조회 가능하나 주간 전체 분모는 확인 불가로 남긴다. 현재 루틴으로 과거 계획을 생성하거나 0으로 단정하지 않는다. 과거 전체 달성률을 반드시 제공하려면 명시적인 보완 근거/정책 결정이 필요하다.
3. **승인선·맨몸 수치:** 같은 운동의 서로 다른 배치별 승인값을 한 값으로 합치는 정책과 기존 BODYWEIGHT 숫자를 추가 중량으로 재해석하는 정책은 정하지 않았다. 후속 조회는 출처를 보존하고 정보가 부족한 수치는 표시하지 않아야 한다.
4. **3일 균등형 유산소 배치(CHECK-08 / DL-51), 후속 결정 완료:** 0·1단계 보고 뒤 2026-11-02 사용자가 **“앞의 운동일부터 배치”**를 선택했다. Legs가 없는 3일 균등형에서 원본 유산소 주 1일이면 첫 운동일, 주 2일 이상이면 앞의 두 운동일에 하루 1종목씩 배치한다. 주 0일이면 생성하지 않고 원본 종목·DL-50·53을 유지한다. [디로딩 v1.2](../product/Overload_Deloading_Design_Specification_v1.2.md)에 반영했다. 해당 정책 결정 대기는 해소됐으며 자동 편성 구현·검증은 3단계 미완료다. v1.1 원본은 보존했다.

위 경계와 무관한 V18 구조·호환성·격리 검증은 계속 수행했다. 통계/디로딩 정책 전체를 다시 질문하거나 새로운 점수를 만들지 않는다.

## 5. 1단계 변경 및 호환성

| 변경 파일 | 내용 |
| --- | --- |
| `src/main/resources/db/migration/V18__add_standard_exercise_joint_type.sql` | nullable TEXT `joint_type`, COMPOUND/ISOLATION/OTHER CHECK, lock/statement timeout. NULL=검토 전, OTHER=명시적 해당 없음. 기본값·UPDATE·DELETE·자동 백필 없음 |
| `catalog/StandardExercise.java` | 문자열 enum `JointType` JPA 매핑·getter·명시 분류 factory. 기존 factory는 미분류 null 유지 |
| `database/V18MigrationIntegrationTests.java` | 실제 행이 있는 V17→18, 기존 모든 업무 행·이력·제약·인덱스·트리거 보존, 재실행0, 허용값/거부값, 구 INSERT 호환, 읽기 감사 SQL 검증 |
| `catalog/CatalogIntegrationTests.java` | 분류값 JPA round-trip·null 보존·search-data의 기존3필드 응답 유지 검증 |
| `database/V17MigrationIntegrationTests.java` | 역사적 V17 검증은 target17로 고정. 신규 migration 때문에 과거 검증 범위를 바꾸지 않음 |
| `database/PostgresMigrationTests.java`, `V9MigrationCompatibilityTests.java`, `V10MigrationIntegrationTests.java`, `auth/email/EmailOtpIntegrationTests.java`, `security/SecuritySessionIntegrationTests.java`, `persistence/JpaJdbcCompatibilityIntegrationTests.java` | 최신 전체 스키마 기대값과 현재 매핑 기동 target만18로 갱신. 역사적 V10 migration 자체 target10과 기존 보안·데이터 보존 검증 유지 |
| [카탈로그 품질 감사 SQL](../dev-data/standard-exercise-catalog-quality-audit.sql) | V18 이상 읽기 전용: 미분류·활성 PRIMARY 누락·비활성 근육·개발 예시 플래그. 해부학적 정확성 인증 도구가 아님 |
| [카탈로그 설계](standard-exercise-catalog-design-analysis.md), [조회 API](../api/standard-exercise-catalog.md) | 최신 역할 분리와 V18 계약·개인 DB 미적용 경계 반영 |
| `docs/product/` v1.1 원본 2개와 후속 디로딩 v1.2, 이 보고서, AGENTS/README | 정책 원본 보존·CHECK-08 사용자 결정·현행 작업 진입점·단계별 실제 상태 기록 |

**새 HTTP API나 기존 응답 변경 없음.** search-data는 schema1/id/name/aliases, 계획·평가·IndexedDB 문서는 schema2/DB3 그대로다. `joint_type` 없는 구 INSERT·미분류 기존 행을 허용한다. 일반 운동 선택·수행·기존 기록 조회를 미분류 때문에 차단하지 않는다. LARGE/SMALL·주요 의존성·새 관리자 기능은 추가하지 않았다. V1~V17 SQL은 수정하지 않았다.

개발용 V17 seed SQL은 과거 적용 원문·V17 guard를 보존했다. V18에서 재실행하거나 운영 seed로 사용하지 않는다. 신규 감사 SQL은 V18 테스트에서만 실행하고 V17 개인 DB에는 실행하지 않았다. 실제 운영 카탈로그를 확정하거나 개인 DB에 V18을 적용한 결과로 보고하지 않는다.

## 6. 통계 구현 체크리스트 및 다음 순서

아래 모두 **2단계 미구현**이다. 기존 기반을 재사용할 수 있다는 뜻과 기능 완료를 구분한다.

- [ ] ST-01~04: 확정 정책 범위·무점수·실제 구현 경계 준수
- [ ] ST-05~09: 이번 주 요약 → 운동 현황/근육/성장/기존 이력 상세, 최근3건
- [ ] ST-10~16: 당시 계획·실제·추가 운동 구분, 초과/분모0, 정정/동기화 재조회
- [ ] ST-17~23: InitialPlan 종목 완료율, 일일 cap1, 주간 루틴 달성률 cap100%
- [ ] ST-24~31: 계획/실제 세트, PRIMARY/SECONDARY 분리, 세부 복수 PRIMARY·대분류/전체 중복 제거
- [ ] ST-32~37: 실제 중량×실제 반복, 주/월·운동/근육 공통 그래프, BODYWEIGHT 표시 조건
- [ ] ST-38~42: 배치/표준 출처 보존 승인 이력·기간/검색/최근 종목, 디로딩 제외·빈 데이터
- [ ] ST-43~46: 주/월 실제 누적량, 보정/신규 점수 없음, 디로딩 기간 표시
- [ ] ST-47~52: 실제 유산소 시간·종류별 거리, 직접 거리 우선·계산 출처·사용자 수정 보존
- [ ] ST-53~57: 시작 날짜 귀속·기존 이력 연결·기간별 계획 분모·디로딩 평가 분리

2단계는 (a) 실제 계정 주간 일정과 적용 시점 이력의 최소 계약, (b) 유산소 실제 속도/거리 출처의 기존 고정 요청 호환, (c) 서버 집계/회귀, (d) 실제 계정 요약→상세 UI 순으로 진행한다. 과거 근거가 없는 계획 분모를 만드는 것으로 빈틈을 숨기지 않는다. 디로딩 실제 기록 집계·평가 제외는 기간/세션 마커가 생기는 3단계에서 연결하고 그 전에는 완료로 표시하지 않는다.

3단계는 DL-01~14 분석 설정/주수, DL-15~22 기간·시작/취소/완료, DL-23~39 원본·분할/근육/정렬, DL-40~53 감량/유산소, DL-54~64 일정/미리보기/UI, DL-65~68 이력·평가 분리 순서다. CHECK-08은 사용자 결정이 반영된 디로딩 v1.2를 따른다. 4단계는 정정/삭제/복구·오프라인·자정·계획 버전·중복 PRIMARY·BODYWEIGHT·거리 수정·10일 종료를 포함한 통합 검증과 문서 반영이다.

## 7. 문서 갱신 위치

- 현재 카탈로그 물리 구조·분류 경계: `docs/tasks/standard-exercise-catalog-design-analysis.md`, `docs/api/standard-exercise-catalog.md`에 이번 반영.
- 새 일정/루틴 계획 이력: 기존 `docs/api/routine-management.md`에 실제 계약 확정·구현 단계에서 반영.
- 유산소 필드·디로딩 세션·평가 제외·복구: 기존 `docs/api/workout-sessions.md`와 `docs/tasks/task-009-redesign-v1.0.md`에 해당 구현 단계에서 반영.
- 통계 조회: 기존 통계 API 계약이 없으므로 2단계에서 `docs/api/statistics.md`에 실제 구현한 endpoint/DTO·기간/소유권·분모 출처·제약을 기록할 예정. 이번에 구현된 API로 만들지 않음.
- Frontend 실제 연동·IndexedDB/PWA: `../overload-pwa/docs/tasks/`의 후속 단계 보고서와 필요한 Engineering 변경으로 실제 적용 시 반영. `/demo`와 실제 계정 기능을 혼동하지 않음.
- 제품 정책 변경은 해당 ST/DL/CHECK와 버전/이력을 사용자 결정 후 갱신. 후속 CHECK-08 결정은 디로딩 v1.2에 반영했고 v1.1 원본은 보존했다.

## 8. 검증·승인·정리

| 실행 | 결과 |
| --- | --- |
| Backend `gradlew.bat --offline --no-daemon test` | 단위 220개 성공, 실패/오류/skip 0 |
| Backend `gradlew.bat --offline --no-daemon build` | 최종 성공. 단위20 suite/220개 + PostgreSQL 통합28 suite/605개, 실패/오류/skip 0. 최초 실행의 4개 실패 수정 후 전체 재검증 통과 |
| 신규 V18 migration/감사 테스트 | 2개 성공. 기존 행·승인 중량·불변 계획·기존 제약 보존, enum CHECK·null·구 writer·감사 SQL 검증. 기존 HTTP/JPA 카탈로그 회귀도 성공 |
| Frontend `npm test` | 453개 성공, 실패/skip/취소 0 |
| Frontend `npm run lint`, `npm run build` | 성공. TypeScript+Vite 빌드, Frontend 제품 소스 변경 없음 |
| 정책 SHA-256·기존 migration 보존·문서 링크·`git diff --check` | 성공 |

첫 전체 회귀의 4개 실패 원인은 V10 호환성 테스트에서 현재 앱의 기동 target이17로 고정되어 새 Entity의 joint_type이 없었던 3개와 JPA/JDBC 재기동 검증의 최신 이력 기대 건수17이었던 1개다. 현재 매핑 기동만18로 변경하고 역사적 target10은 유지했다. 이력17→18 기대값도 수정했다. 기존 데이터 동일성·Hibernate validate·제약 검사를 비활성화하지 않았다. 실행 환경에서는 기존 Gradle 캐시 잠금 파일과 Docker pipe 접근에 샌드박스 제약이 있어 승인된 도구 권한으로 기존 캐시와 격리 Testcontainers를 사용했다. 외부 라이브러리를 설치하지 않았다.

이번에 미실행한 항목: 실제 개인 DB V18 적용, 브라우저/PWA·모바일 실기기 재검증, 아직 구현하지 않은 통계/디로딩 수용 테스트. 기존 API 응답과 Frontend 소스는 유지하며 서버 HTTP 통합 회귀·프론트 자동 검증 범위만 보고한다.

전체 실행 종료 뒤 Testcontainers 컨테이너가 남지 않았고 GradleDaemon/GradleWorker/이 저장소 Java 프로세스가 없음을 확인했다. 3000/8080/5433 listener도 없다. 개인 DB 컨테이너는 원래 정지 상태이며 기존 다른 컨테이너5개와 예전 정지 컨테이너를 보존했다. Frontend npm 검증 프로세스도 모두 exit0으로 종료했다. 새 서버·watch·브라우저는 시작하지 않았다. build/dist/log는 ignored 산출물이며 Frontend Git 작업 트리는 clean이다.

후속 CHECK-08 결정 반영은 문서만 변경했다. 문서 링크·버전/규칙/수용 테스트·원본 SHA-256·공백 검사를 수행했고, 코드·DB·API 변경이 없어 자동 테스트는 다시 실행하지 않았다. 새 프로세스를 시작하지 않았다.

승인 상태: 요청 범위의 가역적인 코드·문서·검증은 추가 승인 불필요. 기존 개인 DB는 읽기 전용 조사만 수행했고 원래 정지 상태로 복원했다. 파괴적 작업·다른 DB 변경·기존 데이터 보정·원격 push·배포는 실행하지 않았다. CHECK-08 제품 예외는 사용자 승인으로 확정됐다. 정확한 카탈로그 내용 검토와 통계·디로딩 구현은 남아 있다.
