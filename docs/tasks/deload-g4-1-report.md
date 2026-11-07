# G4-1 디로딩 분석 설정·epoch·API·DDL 구현

2026-11-07, Asia/Seoul. 기준은 [디로딩 v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md)과 [준비 보고서5.1~5.7](../../../overload-pwa/docs/tasks/deload-v17-backend-readiness-analysis.md#51-실제-코드-대조와-재사용)이다. **G4-1 설정/epoch만 구현**하며 InitialPlan 판정 추출·독립 후보·E/N·04시 확정/재계산·G5/G6는 구현하지 않았다.

## 1. 시작 상태와 구현 범위

양쪽 `feature/weekly-schedule-daily-review-t02b`, 시작 HEAD는 Backend `5c9d48efd453bf335156eba276d90332ce2a0f49`, PWA `91a64f55f206e759bb1d6f3022aa79217985616c`였고 작업 트리는 clean이었다. PWA 소스·UI·문서는 변경하지 않는다. 승인된 범위의 Backend 소스·V25·테스트·API/개발 문서만 체크포인트 대상이다.

| 모듈 | 변경 |
|---|---|
| `deload/DeloadAnalysisState` | 불변 설정 전이, ON/OFF·시간대 확정·epoch·시각 역행/버전 overflow |
| `DeloadAnalysisRepository/Service` | 최신 이력 조회, 현재세대/owner 잠금, 명시적 이행, 기존 START/일정 트랜잭션에서 시간대 확정 |
| `DeloadAnalysisRequests/Controller/Errors` | strict JSON/Counter/IANA·인증 principal·GET/PUT·code-only 오류 |
| AccountRepository | 기존 신규 가입 트랜잭션에서 INITIAL 행 생성. 기존 로그인 재진입 경로는 seed하지 않음 |
| WeeklyScheduleService·WorkoutSessionService 및 오류 처리 | 기존 owner 잠금 안의 최초 시간대 확정. 원본·ACTIVE·고정 ACK 경로 유지 |
| V25 | 설정/epoch 이력·최소 주간 결과 저장 스키마와 scope/전이/수치 제약. 자동 데이터 이행 없음 |

설정은 최신 `deload_analysis_versions` 행 하나의 해석으로 조회한다. 별도 current 설정/누적 캐시·일별 점수·범용 이벤트 시스템은 없다. 분석의 JDBC는 기존 트랜잭션 관리자를 사용하며 JPA/카탈로그/휴식 설정 구조를 바꾸지 않는다.

## 2. 초기 상태·전이와 시간대

신규 계정은 기본ON·version0/epoch0·INITIAL, `epoch_started_at=recorded_at=users.created_at`이다. identity 경쟁 패자와 분석 seed 실패는 사용자·신원·휴식·분석을 함께 rollback한다. 재로그인은 기존 선택을 보존한다.

ON→OFF는 기존 epoch/시각을 유지한다. 실제 OFF→ON은 새 설정 version을 epoch 식별자로 사용하고 서버 전환 시각부터 초기0으로 시작한다. 동일 값은 no-op이고 옛 expectedVersion은409 후 GET 재확인한다. 주간 계산이 없어 과거 기록을 읽어 더하는 코드 자체가 없다. 후속 G4는 이 구간 경계를 이용해 활성화 전/OFF 실적의 소급 가산을 막아야 한다.

가입에는 공통 시간대 입력이 없으므로 ON/초기 시각을 먼저 보장하고 `timeZone=null`을 표시한다. JVM UTC fallback은 없다. 명시적 설정 쓰기·최초 일정·최초 일반 START가 기존 owner 잠금에서 시간대를 확정한다. 일정이 있으면 그 고정 시간대를 사용한다. 최초 확정은 version만 증가하며 epoch 시각은 유지한다. 실패 시 전체 rollback, 중복 START ACK에는 변화가 없다.

분석 시간대가 이미 확정된 뒤 다른 수행 시간대로 START해도 세션의 `startedTimeZone/workoutDate`를 다시 쓰지 않는다. 설정/일정 시간대 충돌은 거절한다. START·설정 경쟁 테스트는 하나의 분석 시간대/epoch만 남고 수행 원본 시간대가 보존되는지 확인한다.

## 3. 실제 API와 계산 경계

정확한 요청/응답·오류는 [API 계약](../api/deload-analysis.md)을 따른다.

- GET `/api/v1/deload-analysis`: 인증 사용자/현재세대, read-only REPEATABLE_READ, 본문/query 없음. 설정을 생성하지 않는다.
- PUT `/api/v1/deload-analysis/settings`: `{dataGeneration,expectedVersion,enabled,timeZone}`만 허용. cookie/CSRF·owner FOR UPDATE·세대·존재·version·시간대·전이 순서로 검증한다.
- Counter는 long 십진 문자열이며 userId/epoch/누적/기준 시각은 입력받지 않는다. 성공과 오류는 no-store/code-only 관례를 따른다.
- 응답의 `epochInitialWeeks=0/1`은 초기값이다. `confirmedWeeks=null`이며 실제 계산 완료값으로 주장하지 않는다. DISABLED/AWAITING_TIME_ZONE/NOT_IMPLEMENTED를 구분한다. 마지막 확정 누적/범위·재계산은 G4-2~6 후속이다.

신규 전이 시각은 owner 잠금 후 PostgreSQL `clock_timestamp()`를 사용한다. 기존 운동/일정 Clock과 요청 JSON을 변경하지 않는다. OFF는 일반 START/FINISH·일별 검사·통계와 별개이며 실제 디로딩 기간을 종료시키지 않는다. G5 기간 API는 아직 없다.

## 4. V25와 기존 미설정 계정

V1~V24 원본은 보존한다. 신규 V25는 다음 두 표를 추가하며 기존 표의 데이터/JSON/hash·Flyway checksum을 수정하지 않는다.

- `deload_analysis_versions`: owner/generation/version PK, users scope FK·epoch 시작 version 자기 FK, enabled/epoch/기준 시각/시간대/reason. 연속 version·유효 전이·최초 epoch·고정 시간대와 이력 UPDATE 불가를 검증한다.
- `deload_weekly_assessments`: owner/generation/epoch/월요일 PK, 시작 epoch FK, E/기여의 정확 정수 분자·분모, N·RESET/ADD·확정/보류·rule/revision/시각. 비정수/NaN/Infinity·0분모·잘못된 수치/소유범위/중복을 거절한다. 계산·확정 job/실제 주간 저장 코드는 없다.

**스키마 적용과 기존 계정 이행은 별개다.** V25에는 INSERT/백필·startup hook이 없다. 내부 `adoptExisting(owner,generation,T_adopt)`는 명시적 이행 시각이 있을 때만 ADOPTED_INITIAL·초기ON/0을 한 번 생성한다. 사용자 생성 시각으로 소급하지 않고 기존 선택을 보존한다. 이행 시각의 부재/생성 전/미래를 거부하며 동시 호출과 재실행은 한 행만 남긴다. HTTP로 노출하지 않는다.

현재 운영용 T_adopt는 정하지 않았고 **기존 검토·개인·운영 DB에서는 이행이나 V25를 실행하지 않았다.** 실제 적용은 대상/시각 확정·가입/관리 쓰기 정지·영구 백업·격리 복원 검증·별도 승인 이후다. V24 보존 검토 서버는 새 빌드로 재시작하지 않는다.

## 5. 검증 내용과 회귀 수정

G4-1 단위 테스트는 전이/epoch·no-op·시간대·시각 역행/overflow·엄격한 입력/정확 Counter를 검증한다. 전용 격리 통합 테스트는 실제 인증/CSRF·초기 상태·재로그인·경쟁 요청·부재 오류·명시적 이행/재실행·스키마 제약·일정 rollback을 검증한다. 기존 계정 프로비저닝 경쟁/실패 테스트에 분석 seed 단계를 포함했다.

기존 운동 격리 회귀에 OFF 중 START/FINISH·부분 수행 PERFORMED·확정 MISSED·실제 일반 통계 유지, START 실패 시 시간대 rollback, START/설정 동시성·고정 ACK 보존을 추가했다. PlanningCalculator/G3 판정·snapshot/ACK/writerEpoch·인증 정책은 수정하지 않았다.

첫 전체 실행은667건 중299건이 실패했다. 원인은 새 FK를 고려하지 않은 임시 테스트 DB 정리 순서, 최신 migration24 단정, V9에서 현재 프로비저닝을 실행하던 역사 fixture 및 같은 FK를 남긴 삭제 fixture였다. 격리 fixture의 자식 정리/스키마 검증을 V25에 맞췄고, 과거 데이터는 과거 스키마용 SQL로 준비해 기존 migration 검증을 유지했다. 제품 보호 assertion을 삭제하거나 하위 버전 호환 코드를 추가하지 않았다. 그 뒤 전체 단위·격리 통합·assemble을 재실행해 통과했다.

최종 명령은 Java25의 Gradle Wrapper `test integrationTest assemble --offline --console=plain`이다. 단위30suite/338건, 격리 통합36suite/684건이며 실패·오류·skip은 모두0이다. 전용 G4 단위15건·통합13건도 이 결과에 포함된다. assemble 성공, 실행 시간9분29초다. 기존 unchecked/class sharing/Gradle 안내 외 새 빌드 실패는 없다. PWA는 무변경이므로 npm 검증을 재실행하지 않았다.

## 6. 보존 환경과 G4-2 경계

실행 전후 기존 보존 라벨의 검토 컨테이너/볼륨, V24, 루틴9·세션15·실제세트28·유산소1을 포함한10개 도메인/판정 표의 지문과 dump4개 해시가 모두 같았다. 읽기 전용 조회만 했다. 실제 검증은 무작위 포트의 Testcontainers DB만 사용했다. 기존 Backend60208/java·PWA63500/node와 시작 시각을 유지했고 테스트 worker는 종료됐다. 기존 검토 서버 시작/종료/재시작·seed·migration·운동 정정은 하지 않았다. 개인/운영DB에는 접속하지 않았다.

체크포인트 범위는42파일(기능 Java12·V25 1·테스트/fixture23·문서6)이다. V1~V24와 PWA는 그대로이며 로그/캡처/빌드/백업/환경 파일은 제외한다. 변경 문서의 링크·fence와 diff/staged 공백을 검증하고 관련 파일만 로컬 커밋한다. push·이력 재작성은 하지 않는다.

G4-2부터 공통 InitialPlan 판정 추출을 시작할 수 있지만 이번에는 구현하지 않는다. 후속 기술 작업은 실제 후보의 ON 구간 필터, 일정 없는 계정 탐색, 정확 E/N·월04시 확정·dirty/revision 소비·같은 epoch 후속 주 재계산이다. G5 정지/완료/취소·소속과 G6 PWA 실제 연결은 별도다. 실제 DB 전환 시각·승인도 미실행으로 남긴다.
