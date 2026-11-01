# 표준 운동 카탈로그 조회 API — Backend 1단계

상태: V16 카탈로그5표·조회와 V17 루틴/세션 단일 표준 UUID 연결을 구현했다. 가입 개인20개 seed는 중단하고 개인 POST/PATCH는403으로 차단한다. 루틴/세션은 [루틴 계약](routine-management.md)·[세션 계약](workout-sessions.md)을 따른다. 검색 DTO schemaVersion=1은 계획/평가 schemaVersion=2와 별개다. 개인 DB 적용·Frontend/IndexedDB 전환은 아직 수행하지 않았다.

## 인증·HTTP

기존 세션 쿠키 인증을 사용한다. 익명/만료 세션은401 AUTHENTICATION_REQUIRED다. 공용 목록이므로 사용자별 사본·userId/dataGeneration 필드가 없다. 인증 정책·CSRF 설정을 바꾸지 않았다.

GET만 제공하며 query와 GET body는400 INVALID_REQUEST로 거부한다. 검색어를 서버로 보내는 검색 API가 아니다. 모든 성공·명시적 오류는 Cache-Control: no-store, 기존 X-Request-ID·보안 헤더를 유지한다. 관리자 쓰기 API는 없다.

## GET /api/v1/standard-exercises/search-data

```json
{
  "schemaVersion": 1,
  "exercises": [
    {
      "id": "11111111-1111-4111-8111-111111111111",
      "name": "불가리안 스플릿 스쿼트",
      "aliases": ["bulgarian split squat", "불스스"]
    }
  ]
}
```

- is_active=true인 표준 운동만 UUID 오름차순으로 반환한다. 이름이 같은 서로 다른 UUID는 별도 운동이다.
- 각 항목은 id/name/aliases뿐이다. 근육·제조사·미디어·개인 운동을 포함하지 않는다.
- 별칭은 해당 운동의 normalized_alias 오름차순이며 표시는 원래 alias 문자열이다. 서로 다른 운동의 같은 별칭은 허용한다.
- 별칭이 없는 운동의 aliases는 빈 배열이다. 카탈로그가 비면 `{"schemaVersion":1,"exercises":[]}`다.
- 운동·별칭 JPA 조회2개를 동일 read-only REPEATABLE_READ 트랜잭션에서 수행해 조합한다. 운동당 별도 조회나 서버 캐시는 없다.

## GET /api/v1/manufacturers

```json
{
  "manufacturers": [
    {"id": "22222222-2222-4222-8222-222222222222", "name": "파나타"}
  ]
}
```

활성 제조사만 UUID 오름차순으로 id/name을 반환한다. 운동 등록 여부와 독립적이다. 비면 `{"manufacturers":[]}`다. 단일 JPA 조회이며 루틴/진행 계획에서 선택할 nullable 제조사 ID를 제공한다.

## 오류

기존 운동 읽기 오류 처리기를 두 카탈로그 Controller 메서드에도 적용한다. 본문은 `{code}`다.

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | query·GET body 등 |
| 401 | AUTHENTICATION_REQUIRED | 기존 인증 실패·만료 |
| 403 | ACCESS_DENIED / CSRF_INVALID | 기존 Security 경계 |
| 503 | WORKOUT_DATA_UNAVAILABLE | 확인된 연결/timeout 등 일시 DB 장애 |
| 500 | WORKOUT_DATA_READ_FAILED | 예상 밖 조회·매핑·SQL 오류 |

SQL·예외 원문을 응답에 포함하지 않는다. Security 예외와 MVC의405/415 같은 프로토콜 응답은 기존 처리에 맡긴다.

## 데이터 등록 경계

V16은 데이터를 INSERT하지 않는다. 직접 SQL 등록은 별도 승인된 환경에서 관리자가 수행할 후속 작업이며 등록 스크립트·관리 UI·ROLE_ADMIN을 추가하지 않았다.

별칭 정규화 규칙은 NFKC→Unicode 공백열을 단일 공백으로→앞뒤 공백 제거→Locale.ROOT 소문자화다. `ExerciseAlias.create`가 이를 적용하고 표시용 원문은 유지한다. 예: `　ＢＥＮＣＨ  PRESS　` → `bench press`.

DB의 PK `(standard_exercise_id,normalized_alias)`는 같은 운동의 정규화 키 중복을 차단한다. 비공백 CHECK가 정규화를 대신하지 않으므로 **직접 SQL 등록자는 동일 규칙으로 생성한 normalized_alias를 공급해야 한다.** 다른 운동의 같은 키는 허용한다. 이름/별칭만으로 UUID를 병합하지 않는다.

muscle.group_code는 CHEST/BACK/SHOULDERS/ARMS/LEGS/CORE, exercise_muscle.role은 PRIMARY/SECONDARY다. 같은 운동의 복수 PRIMARY를 허용하며 기여율 컬럼·집계표는 없다. 정확한 표준 목록·근육 매핑은 이번 테스트 fixture로 확정한 것이 아니다.
