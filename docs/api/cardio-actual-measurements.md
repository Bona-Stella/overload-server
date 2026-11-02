# Actual Cardio Speed and Distance — Phase 2D

2026-11-03 (Asia/Seoul). [Statistics and Tracking v1.3](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.3.md) ST-47~52를 구현한다. 새 제품 정책이나 유산소 승인 평가를 만들지 않는다. 기존 실제 `activeMs`·timer·정정/복구·통계 경로를 사용한다. 기존 개인 DB에는 V18~V21을 적용하지 않았다.

## 선택적인 수행 결과 확장

Snapshot/FINISH/복구의 COMPLETED 유산소 `result`, PATCH records의 유산소 correction에 다음 **선택 필드**를 추가한다. 기존 필수 필드·endpoint·인증/CSRF·version/epoch/revision은 유지한다.

```json
{
  "timerMs": 1800000,
  "activeMs": 1800000,
  "corrected": false,
  "distanceKm": "4",
  "averageBpm": null,
  "measurement": { "actualSpeedKmh": "8", "distanceSource": "CALCULATED" }
}
```

- actualSpeedKmh는 사용자가 기록한 실제 km/h의 nullable decimal 문자열이다. 계획 intensity SPEED/PACE를 이 값으로 복사하지 않는다.
- distanceSource는 MANUAL/CALCULATED/null이다. null은 출처 미확인/거리 없음이며 과거 값에 MANUAL을 소급 지정하지 않는다. measurement 객체가 존재하면 정확히 두 키를 받는다. 신규 미입력·명시적 전체 삭제를 나타내는 `{actualSpeedKmh:null,distanceSource:null}`도 보존한다.
- 직접 입력 거리 우선이다. MANUAL은 기존 0.001~1000km 범위를 유지한다. 실제 속도가 있어도 직접 입력/정정한 비어 있지 않은 거리를 계산값으로 덮지 않는다.
- 거리 없이 실제 속도와 유효 activeMs가 있으면 `speed × activeMs / 3,600,000`으로 계산한다. 분/시간 전환과 같은 공식이다. DB numeric20,6에 맞춰 소수6자리 HALF_UP, 서버 BigDecimal/클라이언트 BigInt를 사용한다. CALCULATED가 이미 전송된 경우 동일 공식과 일치해야 한다. 미입력 상태로 전송하면 서버가 정규화한 뒤 원래의 동일 요청을 반복 수용한다.
- 계산 거리0은 알려진 계산 결과이며 미입력null과 다르다. 계산 결과는 numeric20,6의 저장 범위 내에서만 허용하고 초과를 오류로 알린다. 임의로1000km에 잘라 넣지 않는다. 실제 속도도 기존 decimal 문자열 정밀도/저장 범위를 따른다. 생리적 추정 속도·실제 속도 기본값은 추가하지 않는다.
- COMPLETED의 실제 시간은 기존1ms~24시간이다. 부족한 속도/시간으로 거리를 만들지 않는다. 타이머 일시정지는 제외하고 직접 정정한 activeMs를 사용한다. 원본 timerMs·startedAt·occurredAt은 불변이다.
- 거리/속도를 받는 종목은 기존 거리·SPEED/PACE 계약의 TREADMILL/BIKE/OUTDOOR다. STAIRS/OTHER의 시간 기록은 계속 지원하며 거리·속도 입력 범위를 임의 확장하지 않는다.

기존 JSON의 measurement 생략/null은 계속 수용한다. CardioValue의 null measurement는 JSON에서 **필드 자체를 생략**하여 과거5필드 직렬화/lastSnapshotHash를 유지한다. 기존 고정 snapshot/finish 바이트·schemaVersion2·최초/봉인 계획을 재작성하지 않는다. 동일 요청 재전송/낮은 revision/충돌 계약은 그대로다.

## 저장 구조 — V21

`cardio_results.measurement JSONB NULL` 한 컬럼만 추가한다. 위 두 키·형식·source·COMPLETED 상태를 검사한다. 기존 distance는 그대로 두며, CALCULATED일 때만0을 포함한 계산 결과와 정확한 수식 일치를 검증하도록 거리 CHECK를 확장한다. MANUAL 및 과거 출처 없는 거리는 기존 범위를 유지한다.

**백필/과거 행 UPDATE는 없다.** V1~V20 SQL·세션 JSON·승인 사건·수행/정정 이력·고정 hash를 보존한다. populated V20→V21 검증은 새 NULL 컬럼을 제외한 기존 모든 행과 migration 이력의 동일성을 검사한다. 이 검증은 임의 포트의 일회성 Testcontainers DB뿐이며 개인 DB 적용 승인이 아니다.

## 사후 정정·생략 필드의 보호

새 UI는 CALCULATED 거리를 직접 입력란의 값으로 가장하지 않는다. 실제 속도/시간을 수정하면 재계산 미리보기를 표시하며, 거리 입력란에 값을 넣으면 MANUAL로 잠근다. 과거 출처 미확인 거리의 입력란을 건드리지 않았다면 미확인 상태를 유지한다. 명시적 실제 속도/거리 제거도 별도 measurement 객체로 기록한다. 실패한 요청은 입력과 고정 원문을 유지한다.

기존 클라이언트가 measurement를 생략한 채 새 측정 기록을 수정해도 저장된 실제 속도를 지우지 않는다. 제출한 비어 있지 않은 거리도 교체하지 않는다. 이전 계산 거리와 같은 숫자를 제출했지만 시간 변경으로 계산 근거가 더 이상 맞지 않으면 **거리값은 유지하고 출처만 미확인으로 남긴다**. 그때도 CALCULATED라고 잘못 주장하지 않는다. 새 거리값을 명시적으로 바꾸면 직접 정정한 MANUAL이며, 명시적으로 비우면 남은 실제 속도/시간에 따라 계산할 수 있다. 감사 이력에는 변경 전/후 값을 모두 보존한다.

새 metadata를 제공한 수정은 요청의 명시 내용을 사용한다. 어떤 경우에도 기존 승인 중량, InitialPlan/봉인 AppliedPlan·최초 평가·lastSnapshotHash/종료 사실·기록된 장비/승인 문맥을 바꾸지 않는다. 실제 snapshot 변경 외 메타 조회로 마지막 운동 동기화 시각을 연장하지 않는다.

## 오프라인 계약

Frontend CardioResult/RecordEdit에 optional measurement, CardioTimer에 optional speed 초안만 추가한다. IndexedDB3/local schema2를 유지하며 upgrade/rewrite/자동 기본값 채우기를 하지 않는다. 구버전 타이머·취소 기록·고정 pendingSnapshot/finish·사후 쓰기 원문을 그대로 읽는다. 읽기 validator는 데이터를 정규화하지 않는다.

새 완료 기록은 실제 속도·활동 시간으로 계산한 값과 출처를 고정 snapshot에 함께 담는다. 이후 초안 입력이 바뀌어도 pending 원문은 바뀌지 않는다. ACK 유실은 기존 detail 확인/동일 바이트 재전송으로 처리한다. 취소·명시 복구의 비교에는 실제 속도/출처도 포함해 값 충돌을 숨기지 않는다.

## 실제 통계와 계획 비교

기존 `/statistics`의 응답 형태와 kind+name별 내역은 유지한다. `/statistics/overview`에 `cardioByKind` 배열을 추가한다.

`{kind,activeMs,distanceKm,manualDistanceKm,calculatedDistanceKm,unknownSourceDistanceKm,manualRecords,calculatedRecords,unknownSourceRecords,recordsWithoutDistance}`

시간/횟수는 Counter 문자열, 거리 합계는 decimal 문자열이며 해당 출처의 근거가 없으면null이다. 전체 유산소 실제 시간은 기존 cardioActiveMs로 합산하지만 **서로 다른 종류의 거리를 합친 총 km는 만들지 않는다**. 같은 종류의 서로 다른 이름·추가 유산소는 종류별 합계에 포함한다. 과거 거리는 합계에 남기되 출처 미확인 소계로 구분한다. 계획에 실제 속도/거리를 저장하거나, 계획값을 실제 수행으로 간주하지 않는다.

주간 목표 판정은 실제 시간이 원래 목표를 충족한 뒤, 실제 속도가 있으면 SPEED 목표 또는 `실제속도 × 목표PACE >= 60`을 비교할 수 있다. 속도가 없으면 계속 UNVERIFIABLE이다. 실제 경사/저항 증거는 이번에 추가하지 않아 해당 목표는 여전히 확인 불가로 남긴다. 공식 웨이트 승인/성과 평가를 변경하지 않는다.

기존 session.workout_date 기준으로 자정 이후 유산소도 시작일에 귀속한다. 정정/삭제/명시 복구·늦은 동기화 이후 원본에서 다시 집계한다. 추가·디로딩 유산소에도 동일한 실제 기록 집계 원칙을 적용하며 별도 정상/디로딩 필터를 넣지 않았다. 디로딩 구분/기간/임시 루틴 도메인 자체는3단계에서 연결하고 혼재 검증을 완료했다고 표시하지 않는다.

오류/권한은 기존 세션 API 그대로다. 잘못된 source/계산 주장/범위는400 INVALID_REQUEST, 시각·종목 모순은 기존422이며 DB 오류 원문은 노출하지 않는다.
