# G4-5 과거 정정·후속 주 재계산

2026-11-07 착수, 2026-11-08 마감, Asia/Seoul. G4-5 구현 및 승인된 V26 경계 처리를 정리한다. [디로딩 v1.7 DL-09~14](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md), [G4 준비5절](../../../overload-pwa/docs/tasks/deload-v17-backend-readiness-analysis.md#5-g4-개발-범위의존성검증-계획), [G4-3](deload-g4-3-report.md)·[G4-4](deload-g4-4-report.md)·[설정 API](../api/deload-analysis.md)를 따른다.

시작은 양쪽 `feature/weekly-schedule-daily-review-t02b`·clean, Backend `14deeb97cfd73425768ff4e632e399dcde61e082`, PWA `91a64f55f206e759bb1d6f3022aa79217985616c`였다. 기능 변경은 Backend에만 둔다. G4-6 HTTP 누적 조회, G5/G6, main 병합, push는 범위 밖이다.

## 1. 재계산 구조

| 기존 모듈 | 확장한 책임 |
|---|---|
| [DeloadWeeklyRepository](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyRepository.java) | V25/V26 행과 날짜7개 source revision을 재사용. 변경 주와 같은 epoch의 후속 저장 행을 `needs_recheck`로 영속 표시. 확정 행의 과거 E/N·기여·입력은 새 확정 전까지 보존. CONFIRMED/EXCLUDED CAS 재확정 및 동일 입력 표식 해소 |
| [DeloadWeeklyFinalization](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyFinalization.java) | 가장 이른 gap/dirty 주부터 날짜순 처리. 계정당 최대4주, 배치 최대20계정 유지. rollback 후 첫 미해결 주·후속 표식을 보존하고 다음 tick에서 재개 |
| [DeloadWeeklyPolicy](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyPolicy.java) | 기존 정확분수 RESET/ADD·연속 prefix fold 재사용. EXCLUDED만 가산/RESET 없이 통과하며 dirty면 동일하게 차단. 현재 누적 캐시·차액 가산 없음 |
| [DeloadCandidateCalculator](../../src/main/java/com/overload/overload_server/deload/DeloadCandidateCalculator.java) | ON 날짜가 아니고 실제 후보도 ACTIVE도 없는 저장 여행 날짜를 분석 날짜로 간주하지 않도록 보완. 수행이 모두 삭제된 빈 세션만 남았다고 ON 구간 밖을 N0 RESET으로 만들지 않음 |

단일 세션 InitialPlan 판정, 최고 단일 세션 선택, G3 PERFORMED/MISSED·일반 통계·START/FINISH·승인 중량·운동 원본 쓰기에는 변경이 없다. 원본 변경 탐지는 기존 V24 trigger/START 표식과 G4-4 full revision vector를 사용한다. G3 dirty 해제 전 주간 invalidation hook은 그대로 재사용한다.

누적 예: 이전 ADD 3 → RESET → ADD 1의 기존 누적1을, 정상 복구로 중간 주 E=3/2·N=2·ADD=3/4로 바꾸면 **3+3/4+1=19/4=4.75**가 된다. 기존 총합1에0.75만 더하지 않는다. 다시 해당 복구 기록을 논리삭제하면 RESET을 재적용하여 후속 누적1로 돌아간다.

## 2. 보류·동시성·epoch

UNKNOWN/ACTIVE/G3 검사 미완료면 과거 숫자를 지우지 않고 재검사 상태를 유지한다. 누적은 그 앞의 신뢰 가능한 prefix에서 멈춘다. 뒤의 확정 ADD를 건너뛰어 사용하지 않는다. 입력이 동일한 재검사 표식은 이중 원본 확인 후 CAS로 해소하고 E/N·기여·입력·확정 시각은 다시 쓰지 않는다.

owner FOR UPDATE·READ_COMMITTED·CAS를 재사용한다. 기존 정상 기록 쓰기의 owner SHARE/UPDATE와 직렬화하고, 두 번 계산한 입력 식별과 현재 설정을 비교한다. 도중 원본 변경·CAS 실패·DB 예외는 stale 확정 또는 부분 창 commit을 막는다. 예외 시 전체 처리 창 rollback 후 짧은 별도 owner 트랜잭션으로 재시도 표식을 보존한다.

분석 OFF면 처리하지 않는다. 다시 ON된 경우 현재 epoch를 우선 처리하고, 이미 확정 결과가 있었던 이전 epoch의 변경도 그 epoch 안에서만 정정한다. 이전 epoch는 마지막 기존 확정 주까지만 재평가하며 OFF 이후 미공개 주를 새로 과거 확정하지 않는다. 현재 누적 fold는 현재 epoch만 사용한다. 최초 ON/T_adopt 이전 실제 세션은 정정 후에도 가산하지 않는다. 설정 이력·hard epoch는 수정하지 않는다.

## 3. V25 필수 상태 계약 부족과 승인된 V26

다음 경계를 발견하여 V25만 사용하던 첫 검증에서는 데이터 손실 없이 보류했다.

1. epoch는 분석 시간대 월요일에 시작한다.
2. 여행 시간대의 저장 workoutDate가 직전 일요일인 실제 수행이 ON 이후 시작되어 이전 주에 포함된다. 이는 G4-3의 기존 계약이다.
3. 이전 주를 저장한 뒤 그 주의 마지막 실제 수행을 정상적으로 논리삭제한다.
4. 남은 주는 ON 날짜도 실제 후보도 없어 `OUTSIDE_ON_INTERVAL`이다. DL-10에 따라 정상 N0 RESET으로 바꿀 수 없다.

V25 CHECK는 숫자가 없는 PENDING과 숫자 있는 CONFIRMED RESET/ADD만 허용했다. 임의 RESET은 정책 위반이고 영구 PENDING은 후속 누적을 막는다. 저장된 주를 삭제하거나 fold 시작을 뒤로 옮기면 기존 결과·재검사 진행 근거를 잃는다. 이를 분석·보고한 뒤 사용자가 **V26의 EXCLUDED 상태 추가를 명시적으로 승인**했다.

[V26](../../src/main/resources/db/migration/V26__add_excluded_deload_weekly_assessments.sql)은 기존 V1~V25와 데이터를 수정하지 않고 상태 CHECK와 결과 조합 CHECK만 확장한다. E/N·operation·contribution은 모두 NULL이다. 기존 주간 PK/epoch FK·입력 식별/revision·재검사 표식을 유지하며 기존 `confirmed_at`을 분석 제외 판정의 확정 시각으로 사용한다. 확정 시각은 유한하고 updated_at 이하여야 한다. 일반 N0 RESET의 산술 제약과 다른 컬럼 제약도 유지한다. migration에 DML이나 기존 계정 이행은 없다.

서버는 날짜/후보가 없고 pending이 정확히 OUTSIDE_ON_INTERVAL 하나인 결과만 EXCLUDED로 확정한다. UNKNOWN/ACTIVE/검사 미완료는 이 상태로 우회하지 않는다. owner 잠금 아래 입력을 두 번 읽고 전체 source vector·canonical hash·설정을 확인한 뒤 CAS 저장한다. 실제 수행이 다시 복구되면 새 revision을 감지하여 EXCLUDED를 dirty로 차단하고 정상 E/N·CONFIRMED로 재진입한다. 재삭제하면 다시 EXCLUDED가 된다. 행 삭제나 시작 주 이동은 없다.

fold의 confirmedThrough는 EXCLUDED를 포함한 연속적으로 해결된 위치다. EXCLUDED는 유효 훈련 주수나 RESET 횟수를 뜻하지 않는다. 배치의 processed/confirmed 카운터도 처리 완료 행 수이며 누적 주수로 사용하지 않는다. V26은 임시 Testcontainers에서만 적용했고 기존 검토 DB에는 적용하지 않는다.

## 4. 검증

[G4-5 격리 통합](../../src/test/java/com/overload/overload_server/deload/DeloadHistoricalRecalculationIntegrationTests.java)은 무작위 비밀번호·호스트 포트의 별도 Testcontainers PostgreSQL에서만 실행한다. 인증된 브라우저 클라이언트와 CSRF를 사용하고 실제 `/workout-sessions/{id}/records` PATCH·`recoveries` POST를 호출한다. 최초 fixture 구성과 fault injection만 해당 임시 DB의 SQL을 사용한다. 기존 검토 데이터와 구분한다.

검증 범위:

- RESET→ADD 4.75 및 ADD→RESET 후속 복원, 정확7/10, 무변경 ACK 멱등
- 웨이트 삭제·새 UUID 복구, tombstone ID 재사용422 거절, 최초 계획·평가·workoutDate·hash 보존
- 유산소 목표 미달·근거 부족 UNKNOWN·정상 정정·삭제·새 UUID 복구
- 최초 후보0 주에 닫힌 세션의 지연 수행 복구, 마지막 실제 후보 삭제 후 정상 N0
- 같은 날짜 MISS+실제 수행은 N1, G3 dirty-clear 후 주간 표식과 최초 MISS 보존
- 입력 사이 새 변경으로 STALE 방지, 두 worker·정상 HTTP 정정 owner 잠금 직렬화
- 중간 UNKNOWN/ACTIVE 보류와 후속 ADD 차단
- 7주 재계산의4주/3주 분할, 실제 격리 Spring 컨텍스트 재시작 후 재개
- 세 번째 재계산 실패 시 창 전체 rollback·기존 수치/dirty 표식 보존 후 재시도
- OFF 정지·재ON 새 epoch·T_adopt 이전 비소급
- 여행 날짜의 마지막 수행 삭제 → EXCLUDED → 새 UUID 실제 복구로 CONFIRMED 재진입 → 재삭제
- EXCLUDED 동일 입력 표식 해소의 확정 시각 보존, 제외 CAS 실패 rollback, 두 입력 사이 새 수행 도착 시 STALE 보호
- V25→V26에서 기존 설정/주간 ADD·RESET·PENDING/루틴/세션 JSON·hash 및 V1~V25 checksum 보존, 재실행 멱등, 잘못된 EXCLUDED 수치/시각 거절

초기 검증에서 SQL `day` 별칭 파싱 오류와 테스트 DTO의 내부 scope/외부 flat 요청 혼동을 수정했다. ACTIVE fixture는 실제 START가 호출하는 `WorkoutCalendarRepository.recordStart`를 재사용하도록 보완했다. 빈 여행 세션이 분석 밖 주를 N0로 오판하는 경계도 위와 같이 수정했다. 제품 판정이나 기존 API 검증을 완화하지 않았다.

V26 승인 전에도 전체 단위401건·격리 통합732건을 통과했다. 승인 후 EXCLUDED 경계와 V25→V26 보존 테스트를 추가했고, 최신 스키마를 검증하는 fixture/회귀의 기대 버전만26으로 맞췄다. 기존 V25 단독 업그레이드는 target25로 유지하며 보존/인증/도메인 assertion을 완화하지 않았다. 추가 테스트의 람다 캡처 변수 오류를 수정한 뒤 전용 격리·migration 전체 검증을 통과했다.

최종 Java15개·SQL1개 파일의 SHA-256을 고정하고 전체 검증 명령을 완료했다. 최종 실행의 test UP-TO-DATE는 직전 동일 코드의 전체 단위403건 결과를 재사용한다. 전체 격리 통합과 assemble은 최종 코드로 실행했다. 검증 전후16개 파일 해시는 동일하다.

| 최종 검증 | 결과 |
|---|---|
| 전체 단위 | 35 suite·403건, 실패/오류/skip 0 |
| 전체 격리 통합 | 39 suite·736건, 실패/오류/skip 0 |
| 전용 검증 포함 | G4-4 격리23건·G4-5 격리16건·V26 업그레이드/보존·Policy 단위18건 |
| assemble | BUILD SUCCESSFUL, 최종 전체 명령11분10초 |
| 문서5개 | 내부 파일 링크·fence 검사 통과 |
| diff | git diff --check 및 신규 파일 공백 검사 통과 |
| 민감 정보 | 추가 코드의 자격 정보 패턴0건; fixture는 임의 비밀번호 사용 |

최종 명령은 gradlew.bat test integrationTest assemble --offline --console=plain이다. 초기 AGENTS.md52행의 기존 공백은 HEAD와 동일하게 보존했고 새 공백 오류는 없다.

## 5. 보존 환경 및 남은 작업

시작 시 기존 검토 PostgreSQL 컨테이너 `overload-home-review-b4972e78`는 stopped/exited, AutoRemove=false였다. 이를 기동하거나 DB SQL로 확인하지 않았다. V24 및 루틴9/세션15/세트28/유산소1은 이전 확인 기록이며 이번 실측으로 표현하지 않는다.

기존 `.local-backups` dump4개 SHA-256, 컨테이너 ID/상태/볼륨은 전후 일치했다. 기존 컨테이너는 계속 exited/AutoRemove=false이며 SQL 접근 없이 보존했다. PWA 기능 파일·HEAD·브랜치는 전후 일치했다. Backend 원격 참조도 동일하다. 다만 PWA에 origin/feature/weekly-schedule-daily-review-t02b 참조가 추가되어 있었으며 값은 기존 PWA HEAD와 같다. 로컬 reflog는 2026-11-07 22:01:57 생성만 기록하고 원인/실행 주체는 확인하지 못했다. 이 작업에서는 fetch/push나 원격 참조 생성 명령을 실행하지 않았다. 이 관찰을 원격 불변으로 보고하지 않는다. Backend 변경 파일을 제외한 원본 처리 코드와 V1~V25는 동일하다. 기존 Backend PID60208(02:45:15), PWA PID63500(02:45:23)의 시작 시각도 같으며 시작·중지·재시작하지 않았다. 일회성 Testcontainers와 Gradle 테스트 worker는 모두 종료됐다. 최초/승인 후 전후 증거는 PWA Git 제외 logs/g4-5-environment-before.json 및 logs/g4-5-approved-environment-before.json·after.json에 두며 commit 대상이 아니다.

기존 V1~V25 수정·실제 DB 적용·T_adopt 이행·PWA 변경·push는 없다. 신규 V26만 승인 범위에서 작성하여 임시 DB에서 검증한다. G4-1 HTTP `confirmedWeeks`는 계속null이고 최종 조회/재계산 상태 응답은 G4-6이다. G5 기간/완료 hard reset·무수행 취소의 누적 보존 연결은 후속 책임이다.

관련21파일(기능 코드4·테스트/fixture11·문서5·V26 1개)은 최종 전체 검증 후 Backend 로컬 커밋으로 보존한다. 시작 HEAD는 위에 기록하며 새 commit hash와 최종 상태는 완료 보고에서 제공한다. G4-6 이후 개발이나 main 병합은 별도 지시 전에는 진행하지 않는다.
