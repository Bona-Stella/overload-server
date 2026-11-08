# G5-2 디로딩 운동 실행·기록·임시 계획 관리

2026-11-08, Asia/Seoul. [디로딩 v1.7](../../../overload-pwa/docs/product/Overload_Deloading_Design_Specification_v1.7.md), [G5-1](deload-g5-1-report.md), [운동 세션 API](../api/workout-sessions.md#g5-2-디로딩-세션), [기간·임시 계획 API](../api/deload-periods.md)를 따른다. Backend `c0a87e5d870ea7aca9d8457f1105e1b1d3aaf609`, PWA `91a64f55f206e759bb1d6f3022aa79217985616c`, 양쪽 `feature/weekly-schedule-daily-review-t02b` clean에서 시작했다.

## 세션과 원본 출처

`POST /api/v1/workout-sessions/deload`에서 계획 ID/버전, 현재 세대, 세션 ID와 기존 편집 기기·시각 입력을 받는다. 사용자/기간/유형은 서버가 결정한다. owner FOR UPDATE 아래 ACTIVE 기간과 미수행 계획, 기간 시간대의 오늘/예정일, 최대 10일, 단일 ACTIVE 세션을 검증한다. 최초201, 같은 START200이며 다른 출처·기기·시각·버전으로 같은 ID를 사용하면 충돌이다. 일반 START·계획 변경과 경합할 수 없고 공통 세션·exercise ID·InitialPlan을 한 트랜잭션에서 저장한다.

기존 V12의 일반 `source_routine_id`는 NOT NULL이고 scoped routine FK를 가졌다. V28은 디로딩에만 이 필드/일반 일정 version을 NULL로 허용하는 조건부 제약을 둔다. 일반의 기존 FK와 필수 조건은 유지한다. 디로딩은 V27의 `deload_period_id`/`deload_plan_id` 쌍 및 scoped FK를 사용한다. 소속·원본·수행 날짜·시간대는 기존 DB 불변 trigger와 세션 모델로 고정한다.

`InitialPlan`의 디로딩 `routineId`는 null이고, `deloadOrigin`이 기간/계획/버전·계획 날짜·실행 시점 목표·최초 편성의 원본 루틴/배치들을 봉인한다. 압축형의 서로 다른 원본 종목을 한 일반 루틴으로 위장하거나 루틴 목록에 복제하지 않는다. 일반 InitialPlan과 고정 snapshot hash 형식은 유지한다.

## 실제 수행과 일반 평가 분리

공통 snapshot·ACK·writerEpoch·takeover·FINISH·24시간 AUTO_CLOSED와 종료 뒤 정정·논리삭제·명시 복구를 재사용한다. 디로딩은 부분 수행으로도 FINISH하고 실제 기록만 남긴다. 최초 평가는 `deload-record-only-v1` / `DELOAD_SESSION` / `UNAVAILABLE`, 전체 완료 여부는 null이다. 현재 평가도 제외한다. 완료한 세트의 시간·장비와 불변 시작/종료 사실은 기존 검증으로 보호한다.

디로딩의 웨이트 세트는 `TODAY_ONLY`, 승인 요청 목록은 빈 배열이다. snapshot/FINISH·복구·열린/닫힌 승인 해결에서 일반 승인 요청/문맥을 거절한다. DB도 디로딩 소속 승인 요청/성장 사건 INSERT/UPDATE를 차단한다. 일반 평가 benchmark, 일반 계획 후보, G3 사실 및 dirty, G4 후보에는 명시적 소속을 기준으로 제외한다. G4 기간 정지·확정 prefix는 유지한다. 전체 유효 실적·근육 통계·이력은 계속 포함하며, `GET /api/v1/workout-sessions/deload/{period}`는 현재 사용자/세대의 해당 기간 세션만 반환한다.

V28의 `first_deload_activity_at`은 실제 WEIGHT 세트 또는 양의 COMPLETED 유산소가 저장된 트랜잭션에서 최초 한 번 설정한다. 별도 기간 캐시나 이벤트 테이블 없이 기간 `hasActivity`가 소속 세션의 표식 존재를 조회한다. START/SKIPPED/무수행 종료는 표식을 만들지 않고 논리 삭제·정정·복구가 이미 저장된 표식을 지우거나 변경하지 않는다. 원본 저장 실패 시 표식도 롤백된다. G5-3은 이 값을 현재 화면의 수행량과 구분해 취소 자격을 판단할 수 있다.

0 수행의 `/abandon`은 해당 세션만 정리한다. 동일 편집 기기/epoch/revision과 버전, 명시 confirm이 필요하고 최초 수행 표식이 있으면 거절한다. 완료/자동 종료된 세션의 기존 종료 사실을 바꾸지 않으며 ACTIVE 0 수행은 AUTO_CLOSED로 정리한다. 원본 JSON·소속은 남기고 이력에서 제외하며 계획을 다시 사용할 수 있다. 재시도는 시각/버전을 연장하지 않는다. G6 클라이언트는 알려진 미동기화 수행·고정 요청을 먼저 보호하고 ACK 전 로컬 원본을 버리지 않는다. 서버는 미전송 타 기기 상태를 추정하지 않는다.

## 임시 계획 편집 및 7~10일 경계

V28은 계획의 `current_plan`을 초기 감량 목표에서 초기화하고 최초 `initial_plan`을 동결한다. 입력은 기존 루틴/카탈로그 계획 검증을 공유하지만 저장은 별도 임시 계획이다. 수정 중량/세트/반복/유산소 목표를 재감량하지 않는다. 살아 있는 날짜의 지연 유일 제약으로 빈 날짜 이동과 실제 두 계획 맞교환을 구분하고, 일부 저장 없이 원자 처리한다. 이미 유효 세션이 연결된 계획은 DB 및 API에서 고정한다. 미수행 계획 삭제는 논리 삭제이며 전부 삭제해도 기간은 ACTIVE다.

기간 expectedVersion, 계획 version, owner 잠금을 사용한다. 마지막 변경 요청 ID/내용의 동일 재시도는 추가 변경 없이 현재 응답을 돌려주고, 오래된 버전은 재적용되지 않는다. 8~10일차는 기존 미수행 계획 이동/수정/삭제만 가능하며 10일 밖의 날짜는 거절한다. 방식 교체는 실제 수행 표식 및 유효 세션이 없을 때만 가능하다. 새 자동 편성 슬롯 전체가 남은 최초 7일에 들어가야 한다. REST 전환은 새 계획 없이 유지한다. 기간 최초 출처는 그대로 두고 현재 방식만 별도로 기록한다.

## 검증

| 검증 | 결과 |
|---|---|
| 전체 Backend 단위 | 39 suite·429건 통과 |
| 전체 Backend 격리 통합 | 42 suite·800건 통과 |
| G5-2 전용 | 도메인 단위3건·API 격리22건·V27→V28 보존1건 포함 |
| assemble·문서·공백 | 통과. 전체 실행 9분 11초, 최종 Java/SQL 295개 해시 일치 |

최종 `gradlew.bat test integrationTest assemble --offline --console=plain`에서 실패·오류·skip은 0건이다.

전용 검증은 다중 원본 START/불변 JSON, 단일 ACTIVE/버전/소유권/자정, 고정 snapshot/ACK, 부분 FINISH, takeover/AUTO_CLOSED/복구, 무수행 정리, 웨이트·유산소 영구 표식·삭제 후 보존, 표식 포함 rollback, 승인 거절, 실제 통계/이력/기간 소속, 일반 G3/G4 제외, 계획 변경·재시도·경합·맞교환·전부 삭제와 최초7일/최대10일을 포함한다. V27→28은 기존 행/JSON/hash/checksum과 최초 편성 보존, 기존 정상 FK/소속 제약을 확인한다. 기존 G0~G5-1 회귀는 전체 suite에 포함한다.

중간에 신규 V28 PL/pgSQL 날짜 분기의 구문을 수정했다. 최초 전체 실행에서 V10 역사 테스트의 **현재 앱 기동**이 V25로 고정되어 V28 매핑을 읽지 못했다. V10 이전/이후 역사 SQL 검증은 그대로 두고 현재 매핑을 검사하는 앱만 최신 migration으로 기동하도록 수정했다. 기존 migration 파일이나 데이터 보호 assertion은 변경하지 않았다.

## 보존 및 후속

Testcontainers의 무작위 포트/비밀번호만 사용했다. 기존 검토 DB는 중지 상태를 유지하며 시작·SQL 조회·migration·seed·복원을 실행하지 않는다. V24와 기존 데이터 개수는 과거 확인 기록이다. 검토 컨테이너 ID·중지 상태·볼륨과 백업 4개 SHA-256이 시작 기준과 일치했다. 기존 Backend PID60208와 PWA PID63500의 시작 시각을 확인했다. PWA HEAD·추적 파일, 양쪽 원격 참조, V1~V27 파일 해시도 일치했다. 이번 격리 컨테이너와 테스트 worker는 종료됐다. 로그/증거/덤프/빌드 산출물은 Git에 포함하지 않는다.

G5-3에 기간 직접/조기 완료, 수행 전 취소, 10일차 종료, ACTIVE/오프라인 경계, 완료 당일 일반 START 금지, 완료 새 epoch 및 정지 검사 최종 재개가 남는다. G5-2는 새 START와 계획 날짜의 10일 범위를 검증하되 기간을 종료하거나 분석을 재개하지 않는다. 실제 DB 적용·부분 배포·PWA/G6·push·main 병합은 하지 않는다.
