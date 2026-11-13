# Approved Weight Growth API — Phase 2C

2026-11-02 (Asia/Seoul). [Statistics and Tracking v1.4](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.4.md) ST-38~42를 구현한다. 제품 정책 버전은 변경하지 않는다. 새로운 승인·평가·집계 표·migration 없이 기존 `progress_change_events`를 읽는다. 개인 DB V18~V20 미적용 상태를 유지한다.

## 공통 계약

세 endpoint는 GET·기존 쿠키 인증·본인 현재 dataGeneration·no-store·요청 ID 계약이다. GET body, 중복/미지정 필드, 클라이언트 userId/dataGeneration은400이다. `period=1|3|6|all`, 유효 IANA `timeZone`이 필수다. 페이지 endpoint의 선택 `offset`은 정규 비음수 정수이며 int/다음 페이지 산술 overflow를 거절한다. 목록 offset은 검색 조건별로 관리하고 조건 변경 시0부터 읽는다.

기간은 서버 Clock의 요청 시간대 오늘에서 달력 1/3/6개월 전 날짜(월말 보정) 00:00부터 오늘 포함/내일00:00 미만이다. all은 하한이 없다. 응답 공통 필드는 `{userId,dataGeneration,period,range:{fromDate:null|Date,toDateExclusive:Date,timeZone}}`다. 승인 사건의 선택·그래프 시각은 보존 `occurred_at`, 서버 반영 시각은 별도 `recorded_at`으로 표시한다. 수행 종목 검색의 기간 귀속은 기존 `workout_date`, 최근 순서는 시작 시각이다. 업로드 날짜로 수행 날짜를 대체하지 않는다.

## GET /api/v1/statistics/growth/exercises

공통 query에 선택 `q`(trim 후 최대100자), `offset`을 받는다. 현재 루틴 목록이나 활성 카탈로그로 제한하지 않는다. 기간 내 현재 유효 종료 세션의 실제 WEIGHT 완료 세트가 있는 종목과 기간 내 승인 사건이 있는 종목을 합쳐 표준 운동 ID별로 반환한다. 추가 운동·비활성 표준 운동·삭제 루틴/배치의 승인 이력도 포함한다. 실제 수행은 세션/세트 논리 삭제를 반영한다.

`{...공통,query,offset,exercises:[{standardExerciseId,name,lastPerformedAt,lastApprovalAt}],nextOffset:null|integer}`

최근 수행 우선(lastPerformedAt DESC NULLS LAST), 이어 최근 승인·표준UUID 순이다. 현재 표준 이름과 당시 InitialPlan/추가 종목 이름/승인 사건 이름에서 대소문자 무관 부분 검색한다. `%`/`_`는 와일드카드가 아니며 SQL 문자열에 요청값을 삽입하지 않는다. 실제 완료 없는 계획이나 승인 없는 현재 중량을 검색 사실로 만들지 않는다. 50개+다음 페이지 존재 여부를 읽는다.

## GET /api/v1/statistics/growth/exercises/{standardId}

query는 period/timeZone뿐이다. 본인 수행 또는 승인 이력으로 접근 근거가 없으면404 GROWTH_EXERCISE_NOT_FOUND다. 선택 기간 내 승인 기록이 없어도 과거 접근 근거가 있으면 빈 placements를 반환한다.

`{...공통,standardExerciseId,name,placements:[{routineId,placementId,routineName,deleted,lastApprovalAt}]}`

배치는 최근 승인·배치UUID 순이다. deleted는 부모 또는 배치의 논리 삭제이며 조회 제외 조건이 아니다. UI는 하나의 배치를 명시 선택한다. 같은 표준 운동/이름/장비라는 이유로 서로 다른 배치의 승인값을 평균·최댓값·하나의 성장선으로 합치지 않는다. 장비 변경도 기존 배치의 공식 승인 계보를 재정의하지 않는다.

## GET /api/v1/statistics/growth/exercises/{standardId}/placements/{placementId}

공통 query와 선택 offset을 받는다. 본인·현재 세대·선택 표준 운동·배치가 모두 일치해야 한다. 해당 기간에 승인 근거가 없는 배치는404 GROWTH_PLACEMENT_NOT_FOUND다.

`{...공통,standardExerciseId,placement:위 배치,offset,points:[Point],nextOffset:null|integer}`

Point는 `{id,previousWeightKg,approvedWeightKg,occurredAt,recordedAt,source,sessionId,connectFromPrevious,previousPointId}`다. 중량은 정확한 decimal 문자열, source는 기존 ROUTINE_EDITOR/WORKOUT이며 sessionId는 운동 중 승인인 경우 기존 상세 링크다. 최신 발생 시각/UUID 순100개, 필요 시 이전 페이지를 더 읽는다. 페이지는 매번 현재 원본의 일관된 스냅샷이며 페이지 사이 새 사건 발생 시 중복/이동 가능성을 UI에서 UUID로 중복 제거한다. 일부만 로드했음을 표시한다.

그래프는 next_weight만 점으로 표시한다. initial_weight/current approved_weight 또는 기간 밖 값을 첫 점으로 만들지 않는다. 연결 근거는 같은 배치·선택 기간의 인접 사건에 대해 previous_weight=앞 사건 next_weight이고 발생/서버 기록 시각이 모두 엄격히 증가할 때만 true다. 같은 시각·역전/불일치는 점을 보존하되 선으로 연결하지 않는다. `previousPointId`가 실제 로드한 앞 점 UUID와도 일치해야 연결하므로 페이지 사이 끼어든 사건을 건너뛰어 선을 만들지 않는다. UUID를 승인 적용 순서로 간주하지 않는다. 이 그래프는 보존 사건의 발생 시각별 변경 기록이며 중간 시점의 승인 상태를 보간하지 않는다.

실제 수행 중량·현재 평가·TODAY_ONLY·PENDING/NOT_APPLIED 요청을 승인 사건 대신 사용하지 않는다. 기존 세션 정정·삭제·복구가 이미 적용한 불변 승인 사건을 소급 취소하지 않는 계약도 유지한다. 해당 사건이 가리키는 세션의 논리 삭제만으로 일반 승인 성장 이력을 지우지 않는다.

## 데이터 경계와 디로딩

모든 소유 자료는 user_id/current generation을 제한하고, 사건의 표준 운동 연결은 불변 FK `(user,generation,routine,placement)`와 배치의 불변 standard_exercise_id를 재사용한다. 조회는 workoutTransactionManager/read-only REPEATABLE_READ, ORM 만료·타이머·마지막 동기화 갱신·승인 쓰기가 없다. 검색은2개, 배치 목록은3개, 점 조회는4개 DB query로 고정되며 브라우저에 DB/승인 원본을 캐싱하지 않는다.

**디로딩 수행은 일반 승인 성장선에서 제외한다는 정책을 유지한다.** 2C 당시 V20 도메인에는 디로딩 유형·기간·임시 루틴 식별 및 디로딩 쓰기가 없었다. 현재 일반 루틴 쓰기가 만든 ROUTINE_EDITOR/WORKOUT 승인 사건만 읽으며, 실제 수행량/루틴 이름에서 디로딩을 추정하지 않는다. 임의의 디로딩 표/필드를 새로 만들거나 이미 도입된 것으로 가정하지 않았다. 후속3단계는 디로딩이 일반 승인 사건을 생성하지 못하게 보호하고, 명시적 분류 계약을 조회에 연결한 뒤 혼재 DB 검증을 수행해야 한다. 이번2C를 그 검증 완료로 표시하지 않는다.

오류는400 INVALID_REQUEST,401 AUTHENTICATION_REQUIRED, 위404,503 WORKOUT_DATA_UNAVAILABLE,500 WORKOUT_DATA_READ_FAILED다. 사용자/세대가 바뀐 응답은 Frontend에서 폐기한다. 일반 API·2A/2B·IndexedDB3/schema2·고정 snapshot/finish·오프라인 permit은 그대로다.

[G5-2](../tasks/deload-g5-2-report.md)에서 불변 디로딩 소속을 연결했다. 디로딩 세션의 일반 승인 요청/문맥/해결과 성장 사건은 API·DB에서 차단되며 일반 비교 benchmark에서도 제외한다. 실제 세트·볼륨·근육 및 이력은 기존 실제 운동 통계에 포함된다. 이 성장 조회는 계속 일반 승인 사건만 사용한다.

## 종료 화면 권고와 성장 기록의 구분 — 2026-11-13

일반 운동 UI의 weightReview와 ACTIVE progress-assessment는 사용자 판단을 돕는 읽기 결과다. 특히 미완료 세트의 DECREASE 권고는 공식 benchmark 자격·승인 중량·진행 중량 변경 사건을 새로 만들지 않는다. 이 문서의 성장 조회는 기존 공식 승인 사건을 사용한다. 현재 루틴 값으로 과거 승인 문맥을 덮어쓰지 않는다. [공식 평가와 화면 권고 계약](workout-sessions.md#종료-화면의-중량-검토-권고--공식-평가와-분리)
