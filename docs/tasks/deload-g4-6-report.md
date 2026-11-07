# G4-6 디로딩 분석 조회 API 및 전체 통합 검증

2026-11-08, Asia/Seoul. [v1.7 DL-05~14](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md), [G4 준비5절](../../../overload-pwa/docs/tasks/deload-v17-backend-readiness-analysis.md#5-g4-개발-범위의존성검증-계획), [G4-1](deload-g4-1-report.md)·[2](deload-g4-2-report.md)·[3](deload-g4-3-report.md)·[4](deload-g4-4-report.md)·[5](deload-g4-5-report.md), [통계 v1.4](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.4.md)를 기준으로 한다.

시작은 양쪽 `feature/weekly-schedule-daily-review-t02b`·clean, Backend `cb1b407d3d63a03a562c08be15317b6127cdb818`, PWA `91a64f55f206e759bb1d6f3022aa79217985616c`였다. Backend G4만 구현하며 기존 V1~V26·실제 DB·PWA는 변경하지 않는다. G5/G6·main 병합·push는 하지 않는다.

## 1. 실제 조회 연결

| 모듈 | 역할 |
|---|---|
| [DeloadAnalysisService](../../src/main/java/com/overload/overload_server/deload/DeloadAnalysisService.java) | 기존 View 필드/Counter·Fraction 문자열 보존. GET/PUT 응답에 실제 읽기 결과와 최소 범위·추천 메타데이터 연결 |
| [DeloadAnalysisQuery](../../src/main/java/com/overload/overload_server/deload/DeloadAnalysisQuery.java) | 호출자 snapshot의 현재 epoch 저장 행·source revision 감사, 신뢰 가능한 prefix·상태·정확8주 추천. 설정/주간 결과/dirty 쓰기 없음 |
| [DeloadWeeklyRepository](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyRepository.java) | 기존 CHANGED revision 비교를 SELECT에서 effective_dirty로 사용. PENDING placeholder는 완료 결과처럼 감사하지 않음. 현재 epoch의 최초 여행 날짜 경계만 제한 조회 |
| [DeloadWeeklyPolicy](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyPolicy.java)·[Finalization](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyFinalization.java) | 기존 fold를 그대로 공유하고 첫 주 경계 함수를 추출해 GET과 Job이 같은 범위를 사용 |

기존 내부 accumulated()는 dirty 표식을 저장하므로 GET에서 호출하지 않는다. 원본 InitialPlan 판정이나 후보별 완료율/E/N을 GET마다 다시 계산하지 않는다. 저장된 주간 행과 날짜7개 revision 벡터를 대조하고 최초 주보다 앞선 실제 후보/ACTIVE 존재를 확인하는 최소 조회만 한다. 별도 누적 캐시·새 scheduler·migration·범용 이벤트 시스템은 없다.

## 2. 응답·상태·추천

정확한 JSON 예시와 상태표는 [현행 API 계약](../api/deload-analysis.md#get-apiv1deload-analysis)을 따른다. 기존 필드를 유지하고 confirmedFrom/confirmedThrough/blockedAt/recommendationThreshold/recommendationReady를 추가했다. NOT_IMPLEMENTED는 제거했다.

- DISABLED: OFF. 누적·범위는null, 추천false. 원본·G3·일반 통계 유지.
- AWAITING_TIME_ZONE: 시간대 미확정. 누적/null과 초기 기준0을 구분.
- INITIAL: 아직 다음 월04시 확정 대상 주가 없음. epochInitialWeeks=0/1, confirmedWeeks와 범위=null.
- CONFIRMED: 마지막 due 주까지 연속된 해결 결과. 정확분수와 범위 제공.
- AWAITING_REVIEW: 첫 미해결 주의 필요한 G3 cursor/날짜 검사/시간대 근거 대기.
- PENDING: due gap/주간 미확정. Job 대기 또는 UNKNOWN/ACTIVE 등을 포함.
- RECALCULATING: 기존 확정 suffix/재검사 표식/원본 revision 변화로 재평가가 필요한 상태. worker 실행 여부를 단정하지 않음.

보류/재계산 중에는 첫 미해결 월요일을 blockedAt으로 주고 그 이전 prefix만 제공한다. 첫 해결 결과가 없으면 confirmedWeeks/범위는 null이다. 확정 RESET0은 0/1과 non-null 범위로 구분한다. EXCLUDED는 해결 범위에 포함되지만 가산도 RESET도 하지 않는다. confirmedThrough는 마지막 해결 주의 월요일이며 그 주 일요일까지를 뜻한다. 이전 epoch 결과는 현재 누적에 합산하지 않는다.

추천 기준은 정확분수8/1이다. 전체 due 범위가 CONFIRMED이고 정확 누적≥8일 때만 recommendationReady=true다. prefix가8 이상이어도 뒤에 보류/재계산 주가 있으면false다. 표시 반올림·게이지 clamp로 판정하지 않고8 초과 누적을 보존한다. 추천은 강제 시작/수동 시작 자격/실제 기간 생성이 아니며 G5의 네 조건과 별개다.

## 3. 읽기·동시성·보안

GET은 기존 read-only REPEATABLE_READ의 한 snapshot에서 principal 사용자·현재 dataGeneration·설정·주간 결과·revision을 읽는다. GET의 owner 잠금·dirty 저장/해제·계정 초기화·G3 cursor 전진은 없다. 현재 설정의 version은 설정 expectedVersion이지 분석 결과 revision이 아니다. 결과는 같은 설정 version에서도 바뀔 수 있고 응답은 no-store다.

동시 Job/설정/정정은 snapshot 시작 전후 한 시점으로 일관되게 보인다. 조회 중 새 쓰기가 commit돼도 그 응답은 이전 snapshot을 유지하며 다음 GET에 새 상태가 보인다. 설정과 다른 epoch/세대의 점수를 섞지 않는다. PUT의 owner 잠금·READ_COMMITTED·expectedVersion/시간대/no-op/CSRF 계약은 유지하며 같은 View를 반환한다. 누락 설정은 기존500 DELOAD_SETTINGS_MISSING이고 조용히 ON을 생성하지 않는다. 기존 query/body 거절·code-only 오류·인증 경계도 유지한다.

HTTP 인증 세션의 접근 시각 저장 등 기존 인프라와 분석 도메인 쓰기를 구분한다. 반복 GET 전후 분석 설정/주간/일별/달력/세션/수행/정정 이벤트 전체 행을 비교하며, JDBC에서 transaction_read_only=on 및 repeatable read를 직접 확인했다.

## 4. 발견·해결한 경계와 테스트

새 분수 테스트는 기존 int 전용 factory 대신 BigInteger 생성자를 사용하도록 수정했다. 첫 fixture 실패는 로그인 후 시간을1초 역행시켜 인증이 거절된 경우와 ACTIVE fixture 뒤에 다른 세션을 일시 ACTIVE로 생성한 경우였다. 로그인 전 경계 시각을 설정하고 종료 세션들을 먼저 준비했다. 새 세대 fixture는 기존 휴식 설정 FK를 지키도록 같은 임시 트랜잭션에서 자식 설정을 함께 준비했다. 인증/단일ACTIVE/소유권 제약을 완화하지 않았다.

조회 안전성 검토에서 **기존 범위 앞 여행 날짜의 늦은 ACTIVE**가 최초 주 탐색에서 빠져 CONFIRMED로 보이는 경계를 실패 테스트로 재현했다. GET·Job이 공유하는 탐색에 ACTIVE를 보류 후보로 포함했다. 실제 수행으로 N에 가산하지 않는다. 수행 없는 AUTO_CLOSED가 확정되면 기존 V26 EXCLUDED로 해결하여 원래 범위로 진행한다. START/FINISH·G3·InitialPlan 정책은 변경하지 않는다.

[전용 단위](../../src/test/java/com/overload/overload_server/deload/DeloadAnalysisQueryTests.java)는 초기/null vs RESET0, 보류·dirty/gap·후속 ADD 차단, EXCLUDED, 반올림하면8인8미만·정확8·8초과, 보류 중 추천 금지,04시 이전 범위를 검증한다.

[전용 격리 API](../../src/test/java/com/overload/overload_server/deload/DeloadAnalysisQueryIntegrationTests.java)는 실제 인증/CSRF와 Workout 기록 수정·복구 API를 사용한다. 신규 기본ON/시간대,04시 전후,3/4·여러 주·E1 RESET,7/8/9주 추천과 보류 시false,EXCLUDED 삭제/복구, UNKNOWN/ACTIVE/재계산 prefix,4.75 복원,OFF/재ON epoch,T_adopt 비소급,미등록 일정, G3 검사 대기, 반복 읽기/placeholder 무변경, Job·OFF·정정 경합, 현재 세대/타 계정 분리, query/body/누락 설정 오류를 검증한다. fixture 구성·상태 전이/fault injection의 SQL은 별도 임시 DB에만 사용한다.

G4-1 기존13건과 새 G4-6 API19건은 전용 검증을 통과했다. G4-3~5 전용도 중간 회귀에서 통과했으며 원래 테스트는 유지한다. G4-1의 NOT_IMPLEMENTED 기대값만 INITIAL로 갱신했다. 최종 Java8개 파일의 SHA-256을 고정한 뒤 gradlew.bat test integrationTest assemble --offline --console=plain을 완료했다. 검증 전후 코드 해시가 동일하다.

| 최종 검증 | 결과 |
|---|---|
| 전체 단위 | 36 suite·412건, 실패/오류/skip 0 |
| 전체 격리 통합 | 40 suite·755건, 실패/오류/skip 0 |
| G4-6 전용 포함 | 단위9건·격리 API19건 |
| 기존 회귀 | G4-1~5·G3·일반 통계·운동/승인/인증·V25→V26 보존 및 제약 모두 통과 |
| assemble | 성공, 최종 전체 명령8분54초 |
| 문서·diff | 변경 문서5개 내부 파일 링크/fence/JSON 예시·diff 및 신규 행 공백 검사 통과 |
| 민감 정보 | 추가 코드 자격 정보 패턴0건, Testcontainers는 무작위 비밀번호 사용 |

커밋 대상은 Backend13파일(기능 코드5·테스트3·문서5)이다. 기존 G4-1의 상태 기대값 외 테스트 본문과 제품/보안 assertion은 보존한다. 검증용 로그·환경 증거·manifest는 기존 ignore 경로에 두며 commit 대상이 아니다.

## 5. 환경 보존 및 G5-1 전 경계

기존 검토 컨테이너 overload-home-review-b4972e78는 시작 시 exited/AutoRemove=false였고 기동·SQL 접근·migration·seed·복원하지 않았다. 스키마 V24 및 루틴9/세션15/세트28/유산소1은 이전 확인 기록이며 이번 실측이 아니다. 기존 컨테이너 ID/중지 상태/볼륨/AutoRemove=false와 백업4개 SHA-256이 전후 동일했다. 기존 Backend PID60208·시작2026-11-07 02:45:15와 PWA PID63500·시작02:45:23도 동일하며 재시작하지 않았다. PWA 추적 파일·HEAD·브랜치와 양쪽 원격 참조도 일치했다. Java8개 외 기존 보호 코드·설정·migration26개는 동일하고 새 migration은 없다. 검증용 Spring 서버·worker·일회성 Testcontainers는 모두 종료됐다. 증거는 PWA Git 제외 logs/g4-6-environment-before.json·after.json에 보관한다. 검증에는 무작위 비밀번호·포트의 Testcontainers만 사용한다.

G4-6은 기존 V1~V26과 DB 이행을 변경하지 않는다. 실제 적용에는 대상/가입·관리 쓰기 경계/영구 백업·복원/명시적 T_adopt와 별도 승인이 여전히 필요하다. 기존 V24 검토 서버를 새 빌드로 재시작하지 않는다.

G5-1은 기간/명시적 일반·디로딩 세션 소속·일반 검사 정지 이력·완료 새 epoch·무수행 취소 보존 연결이 남는다. G4만으로 실제 디로딩 혼재/기간 보호가 완료된 것은 아니다. PWA 준비 보고서/정책 문서의 당시 G4 미구현 상태 문구는 역사 기록이며 이번 실제 구현은 Backend 현행 계약/보고서가 근거다. PWA/UI는 이번 요청대로 수정하지 않았다. G5/G6·실제DB 적용·push·main 병합은 별도 지시 전에는 진행하지 않는다.

관련 Backend 변경만 로컬 커밋으로 보존하며 새 commit hash와 양쪽 최종 Git 상태는 완료 보고로 제공한다. G4-6 범위의 미해결 오류는 없고 G5 이후 작업은 시작하지 않는다.
