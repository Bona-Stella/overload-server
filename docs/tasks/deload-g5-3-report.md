# G5-3 디로딩 완료·취소·자동 종료와 분석 재개

2026-11-08, Asia/Seoul. [디로딩 v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md), [G5-1](deload-g5-1-report.md), [G5-2](deload-g5-2-report.md), [G4-6](deload-g4-6-report.md)의 기존 경계를 따랐다. 시작 기준은 Backend `8c64057ab687`, PWA `91a64f55f206`, 양쪽 `feature/weekly-schedule-daily-review-t02b`의 clean 상태였다.

## 상태 전이와 API

| 요청 | 검증과 결과 | 일반 접근 |
|---|---|---|
| 직접 완료 | 세대·기간 버전·ACTIVE 세션 부재를 검증한다. 첫날이나 완전 휴식이어도 COMPLETED와 MANUAL_COMPLETION을 기록한다. | 완료 다음 고정 로컬 날짜부터 열린다. |
| 무수행 취소 | 최초 실제 웨이트 또는 양의 유산소 표식이 있으면 논리 삭제 이후에도 거절한다. 연결된 빈 세션은 기존 abandon의 원본/버전 확인이 필요하다. CANCELLED와 USER_CANCELLED를 기록한다. | 성공 직후 열린다. |
| 자동 완료 | 10일차가 끝난 뒤 마감된 기간을 검색한다. ACTIVE 세션이 있으면 건너뛰고 FINISH 또는 공통 AUTO_CLOSED 후 재시도한다. COMPLETED와 AUTOMATIC_COMPLETION을 기록한다. | 안전한 종료 처리가 끝난 뒤 열린다. |

기간의 고정 IANA 시간대에서 11일차의 시작을 마감으로 삼는다. 자동 완료의 `closedAt`은 마감 직전 마이크로초, `closedDate`는 10일차, `processedAt`은 실제 처리 시각이다. 시작일을 1일로 계산하고 7~10일차에 기존 기간을 유지한다. 기존 리뷰 tick이 기간 → G3 → G4 순서로 처리한다. owner 잠금과 기간 버전으로 수동·자동 요청을 직렬화하고 한 트랜잭션에서 상태·재검사·epoch를 기록한다. 성공한 종료 요청은 같은 ID·버전·명령으로 재시도할 수 있다.

기간 상세와 이력을 현재 소유자/세대로 조회한다. 완전 휴식 기간과 삭제된 임시 계획의 불변 원본도 종료 상세에 남는다. 현재 기간에는 완료 당일의 COMPLETED를 포함한다. 일반 START는 기간 시간대의 서버 날짜 및 요청의 실제 시작 시각으로 권한을 검사하며 세션의 여행 수행일은 기존대로 저장한다. 디로딩 세션의 InitialPlan·소속·실적·승인 제외·FINISH ACK 및 명시적 복구는 기존 세션 계약을 사용한다. G6에서는 알려진 로컬 미전송 데이터와 고정 요청을 종료나 abandon 전에 확인해야 한다. [기간 API](../api/deload-periods.md) · [세션 API](../api/workout-sessions.md#g5-2-디로딩-세션)

## 분석과 스키마

- V29는 종료 사유/요청 ID와 완료 기간을 참조하는 epoch를 추가한다. 종료 시각·날짜와 처리 시각은 기존 기간 열을 사용한다. 종료 행과 epoch 연결을 제약으로 보호한다. 이전 V1~V28 파일과 기존 행은 재작성하지 않는다.
- ON 상태의 완료는 `DELOAD_COMPLETED`로 새 epoch를 시작한다. `epochStartedAt`은 유효 종료 시각이고 `analysisResumesOn`은 다음 고정 로컬 날짜다. `epochInitialWeeks=0/1`과 주간 결과가 없는 `confirmedWeeks=null`을 구분한다. OFF 상태는 그대로 유지한다. 사용자가 다시 ON 하면 기존 `ENABLED` 전이를 따른다.
- 새 epoch는 이전 누적과 디로딩 소속 실적을 가져오지 않는다. 종료 다음 날 이후의 실제 세션을 저장 수행일로 묶고 같은 월~일 주의 나머지 날짜도 평가한다. 이전 epoch의 미해결 행은 이미 검사한 주 범위에서 해소할 수 있고 새 누적에는 합산하지 않는다.
- 취소는 이전 epoch·신뢰 누적을 유지한다. G3는 과거 정지 날짜와 지연 자동 종료로 일반 접근이 잠겼던 온전한 날짜에 새 MISS를 만들지 않는다. G4는 보호 날짜를 일반 미수행 N으로 세지 않는다. 확인된 보호 주의 E≤1은 EXCLUDED로 통과하고 정상 N0주는 RESET한다. 실제 일반 후보로 E>1이면 기존 ADD를 적용한다. 비보호 날짜의 UNKNOWN·dirty·검사 미완료는 기존 보류·revision·CAS를 따른다. [분석 API](../api/deload-analysis.md)

## 검증

기간·세션·업그레이드 집중 검증에서 첫날 완료, 4.75주 경계, 7~10일 유지, 11일차 복귀, 동시 worker/수동 완료, 웨이트·유산소·논리 삭제, 명시 정리, ACK·지연 복구, 원자적 rollback·재시작, 월요일 04시 및 소유권·CSRF를 검사했다. DST 봄·가을 전환은 고정 시간대의 마감 계산 단위 테스트로 검증한다.

| 검증 | 결과 |
|---|---|
| 전체 Backend 단위 | 40개 suite, 432건 통과 |
| 전체 Backend 격리 통합 | 42개 suite, 816건 통과 |
| V29 migration | 빈 DB 적용·재시작과 V28→V29 업그레이드 통과. 기존 행과 V1~V28 체크섬을 비교했다. |
| assemble·문서 링크·diff | 통과 |

최종 전체 `gradlew.bat test integrationTest assemble --offline --console=plain`은 9분 34초가 걸렸고 실패·오류·skip은 0건이다. 초기 전체 실행에서 DB를 끈 두 테스트 컨텍스트에 새 서비스 모킹이 필요했다. 계정 생성의 물리 테이블 검사도 V29의 읽기 전용 뷰를 집계하지 않도록 보완했다. 스테이징 검사에서 지적한 V29의 마지막 빈 줄을 정리한 뒤 migration 격리 테스트 16건과 assemble을 다시 통과했다.

## 적용 범위

검증 DB는 임시 Testcontainers PostgreSQL이다. 기존 검토 컨테이너의 ID·종료 상태·볼륨과 백업 4개의 SHA-256을 시작 전 기록과 비교했다. 검토 DB의 V24는 이전 조회 기록을 기준으로 하며 이번에는 DB에 접속하지 않았다. V1~V28 파일 해시와 PWA 추적 소스·HEAD도 일치했다. 이전에 기록한 Java/Vite 프로세스의 PID와 시작 시각을 유지했다. G6 UI, 실제 DB 적용과 main 병합은 별도 단계다.
