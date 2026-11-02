# Bodyweight Additional Load — Phase 2E

2026-11-03 (Asia/Seoul). [Statistics and Tracking v1.3](../product/Overload_Statistics_and_Tracking_Design_Specification_v1.3.md) ST-36·37의 구현 계약이다. 제품 정책과 기준서 버전을 변경하지 않는다. 기존 [세션 계약](workout-sessions.md), [통계 API](statistics.md), [2D 유산소 측정](cardio-actual-measurements.md)을 유지한다.

## DB와 기록 근거

`V22__add_bodyweight_additional_load.sql`은 `performed_sets.bodyweight_load JSONB NULL` 한 컬럼과 CHECK만 추가한다. UPDATE·기본값·백필·과거 weight 재해석은 없다. V1~V21은 수정하지 않는다. 기존 `weight`, 승인 중량/문맥, 완료 당시 장비, InitialPlan·AppliedPlan, 최초 평가와 hash는 보존한다.

값은 `{"additionalWeightKg":"10.000001"}` 또는 `{"additionalWeightKg":null}`다. SQL NULL은 미기록/과거 형식, 객체의 null은 사용자의 명시적 제거다. 객체는 추가 키 없이 이 키 하나만 허용하며, 완료 당시 `load_type='BODYWEIGHT'`일 때만 허용한다. 숫자는 기존 decimal 문자열 규칙(0 이상, 정수 최대14자리·소수 최대6자리, 지수/후행0 금지)을 사용한다. SQL CHECK는 3값 논리의 NULL 우회를 막고, 숫자 JSON·음수·범위/정밀도 초과·다른 장비의 추가 부하를 거절한다. 명시적 문자열 `"0"`은 알려진0이며 미기록과 다르다.

숫자 컬럼 대신 nullable 객체 하나를 택한 이유는 **생략과 명시적 제거를 구분하면서 기존 고정 JSON/hash를 보존**하기 위해서다. 별도 presence 컬럼·통계 테이블·의존성은 추가하지 않는다.

## 기존 API의 선택 필드 확장

새 endpoint는 없다. 다음 기존 경로의 세트에 선택 `bodyweightLoad`를 추가한다.

| 경로 | 확장 위치 |
| --- | --- |
| `PUT /api/v1/workout-sessions/{id}/snapshot` | `snapshot.performedSets[]` |
| `POST /api/v1/workout-sessions/{id}/finish` | `snapshot.performedSets[]` |
| `PATCH /api/v1/workout-sessions/{id}/records` | `setCorrections[]` |
| `POST /api/v1/workout-sessions/{id}/recoveries` | `performedSets[]` |
| `GET /api/v1/workout-sessions/{id}` | `snapshot.state.performedSets[]` |
| `GET /api/v1/workout-sessions/{id}/record-change-events` | SET 변경의 `before`/`after` |

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "weightKg": "80",
  "reps": 8,
  "bodyweightLoad": { "additionalWeightKg": "10" }
}
```

위는 정정 원소의 예다. `weightKg=80`을 추가 중량으로 사용하지 않으며 해당 세트의 통계는 `10×8=80kg`다. 일반 웨이트는 기존 `weightKg×reps`를 사용한다. 세션 수행 원소는 기존 id/종목/순번/시각/승인 문맥/장비 필드가 그대로 필요하다.

- 새 세트에서 필드 생략 또는 `bodyweightLoad:null`은 추가 중량 미기록이다. 조회와 canonical 직렬화는 해당 키를 생략하므로 이전9필드 JSON과 동일하다.
- 기존 세트의 snapshot·FINISH·사후 정정에서 필드 생략/null은 저장된 추가 중량을 유지한다. 구버전 클라이언트가 기존 weight/reps를 수정해도 새 근거를 삭제하지 않는다. 삭제하려면 `bodyweightLoad:{additionalWeightKg:null}`을 명시한다.
- `weightKg`, reps, context의 원래 평가 규칙은 변경하지 않는다. 추가 중량은 승인 사건이나 자동 증량을 만들지 않고 공식 승인 중량 성장선에도 합치지 않는다. 최초/현재 승인 평가의 비교 입력은 기존 중량 그대로다.
- 정정은 기존 expectedVersion·세션 잠금·트랜잭션/감사 이력을 사용한다. 추가 중량만 바뀌어도 before/after를 남긴다. 잘못된 형식/비BODYWEIGHT 확장은 400 INVALID_REQUEST이며 원래 인증·소유자/세대·CSRF·편집권·충돌/삭제 오류 경계를 유지한다.
- 명시적 누락 복구는 원래 UUID·장비·시각·중량과 추가 중량 근거를 함께 보존한다. 이미 존재하는 동일 UUID에서 추가 부하가 다르거나 생략된 복구 주장은 RECORD_ID_CONFLICT다. 삭제 UUID는 RECORD_DELETED로 유지하며 복구로 되살리지 않는다. 확인 불가 승인 문맥은 기존 UNVERIFIED 규칙을 따른다.

## 집계와 표시

`StatisticsRepository`는 관계형 미삭제 현재 세트의 `bodyweight_load->>'additionalWeightKg'`만 읽고, `StatisticsCalculator.Count` 하나로 summary·day·exercise·muscle·group에 동일 기준을 적용한다. 두 통계 endpoint 및 주/월 범위에 같은 공식이 적용된다.

- BODYWEIGHT + 명시적 값: `additionalWeightKg × 실제 reps`.
- BODYWEIGHT + 미기록/제거: 세트·반복은 정상 집계, volume 계산에서 제외.
- 그 외 장비/기존 nullable 장비: 기존 `weight × 실제 reps` 유지.

사용자 체중·현재 승인 중량·계획 중량·의미 불명확한 과거 weight로 추가 부하를 만들지 않는다. `setsWithoutVolume`은 추가 중량 근거가 없는 BODYWEIGHT 세트 수이고, 계산 가능한 세트가 없으면 `volumeLoadKg=null`이다. 일부만 알면 알려진 소계와 제외 안내를 표시한다. 명시적0만 있는 경우의 합계0과 근거 없는 null을 구분한다. 실제 기록 범위·시작일 귀속·추가 종목 포함·PRIMARY 다중 매핑·group/role 중복 제거·SECONDARY 별도 표시는 그대로다.

공통 WeightChart는 모든 행이 null이면 총중량 전환을 숨기며 세트·반복만 표시한다. 혼합 화면의 null 행도 `0 kg` 대신 `중량 기록 없음`으로 표시한다. 개별 완료 기록과 감사 이력은 원래 weight를 보존하면서 별도의 명시적 추가 중량/근거 없음 문구를 보여 준다.

## Frontend와 오프라인

`PerformedSet`·`RecordEdit.setCorrections`에 optional `BodyweightLoad`를 추가한다. `SetDraft.additionalWeight?:string`은 정확한 입력 텍스트를 저장하며 완료 시 기존 decimal 정규화만 적용한다. BODYWEIGHT 세트에 별도 빈 선택 입력란을 보여 주고 기존 weight/체중/승인값/이전 세트에서 자동 채우지 않는다. 일반 웨이트 입력·평가/승인 제어는 그대로다.

사후 정정은 추가 중량란을 건드렸을 때만 확장 필드를 보낸다. 비우면 명시적 null 객체를 보내며 반복만 고치는 경우 기존 근거를 유지한다. 취소 원본·초안·재완료, 복구 충돌 비교, 감사 이력과 응답 유실 확인에도 추가 부하를 포함한다. 구조화된 감사 객체를 참조 동일성이 아닌 값으로 비교한다.

IndexedDB 버전3/local schema2는 유지한다. 기존 문서를 백필/정규화하거나 `pendingSnapshot.requestJson`·FINISH/사후 쓰기의 고정 요청 원문을 재작성하지 않는다. 확장 필드 없는 구버전 문서/요청도 그대로 허용하고 신규 문서는 실제 명시적 입력만 선택적으로 포함한다. 오프라인 재시작·전송 ACK 유실 시 동일 원문 재전송·같은 UUID·단일 원격 기록의 기존 계약을 유지한다.

개인 DB V18~V22는 미적용이다. 새 Backend로 개인 DB 대상 앱을 실행하지 않는다. 검증은 임의 포트·별도 DB명의 Testcontainers만 사용한다. 운영 카탈로그 분류 검증과 디로딩3단계 식별/승인 분리·실기기 검증은 별도 잔여 작업이다.
