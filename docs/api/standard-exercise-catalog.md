# 표준 운동 카탈로그 API

2026-11-02 통계·디로딩 착수 보완: V18은 `standard_exercise.joint_type`만 추가한다. Java `jointType`은 COMPOUND/ISOLATION/OTHER이며 null은 검토 전 미분류다. OTHER로 자동 보완하지 않는다. search-data schemaVersion=1 및 id/name/aliases 응답은 그대로다. 기존 writer의 컬럼 생략 INSERT도 허용한다. V18 격리 검증과 개인 DB 적용은 별개이며 개인 DB는 이번 작업에서 V17을 유지한다. [0·1단계 보고서](../tasks/statistics-deload-phase-0-1-report.md)를 따른다.

상태: V16 카탈로그5표·조회와 V17 루틴/세션 단일 표준 UUID 연결을 구현했다. 가입 개인20개 seed는 중단하고 개인 POST/PATCH는403으로 차단한다. 루틴/세션은 [루틴 계약](routine-management.md)·[세션 계약](workout-sessions.md)을 따른다. 검색 DTO schemaVersion=1은 계획/평가 schemaVersion=2와 별개다. 2026-11-02 개인 개발 DB V17 적용과 Frontend/IndexedDB3 실제 연동을 확인했다. [4단계 보고서](../tasks/standard-exercise-catalog-dev-phase-4-report.md)와 [개발용 등록 SQL](../dev-data/standard-exercise-catalog.sql)을 따른다. 개발 예시는 운영 카탈로그 확정 데이터가 아니다.

## 인증·HTTP

기존 세션 쿠키 인증을 사용한다. 익명/만료 세션은401 AUTHENTICATION_REQUIRED다. 공용 목록이므로 사용자별 사본·userId/dataGeneration 필드가 없다. 인증 정책·CSRF 설정을 바꾸지 않았다.

카탈로그는 인증된 GET을 제공한다. query와 GET body는 400 INVALID_REQUEST로 거부하며 이름·별칭 검색은 클라이언트가 처리한다. 모든 성공·명시적 오류는 Cache-Control: no-store, 기존 X-Request-ID·보안 헤더를 유지한다. 개발자가 내부 서비스·검증된 SQL로 데이터를 관리한다. 관리자 계정·조회/쓰기 HTTP 경로·UI는 현행 범위에서 제외한다.

## GET /api/v1/standard-exercises/search-data

```json
{
  "schemaVersion": 1,
  "exercises": [
    {
      "id": "11111111-1111-4111-8111-111111111111",
      "name": "불가리안 스플릿 스쿼트",
      "aliases": ["bulgarian split squat", "불스스"],
      "muscles": []
    }
  ]
}
```

- 활성 상태이고 근육 타겟과 분할 검수를 모두 마친 `SETS_REPS` 운동을 UUID 오름차순으로 반환한다. 이름이 같은 서로 다른 UUID는 별도 운동이다.
- 신규 운동은 근육 타겟·분할 검수가 모두 미완료다. 두 검수 상태는 독립적이며 분할값이 `null`이어도 분할 검수를 완료할 수 있다. 내부 검수 서비스에서 `SETS_REPS`의 근육 검수를 완료하려면 활성 PRIMARY가 하나 이상 필요하다. 다른 기록 방식은 근육 연결 없이도 근육 검수를 완료할 수 있다. 읽기 API는 활성 상태, 저장된 검수 상태와 각 경로의 기록 방식·선택 조건을 기준으로 판정한다.
- 각 항목은 id/name/aliases/muscles로 구성한다. 제조사, ExerciseDB 원본 ID, GIF 주소·참조 키, 개인 운동은 포함하지 않는다.
- 별칭은 해당 운동의 normalized_alias 오름차순이며 표시는 원래 alias 문자열이다. 서로 다른 운동의 같은 별칭은 허용한다.
- 별칭이 없는 운동의 aliases는 빈 배열이다. 카탈로그가 비면 `{"schemaVersion":1,"exercises":[]}`다.
- 운동·별칭·근육 관계를 같은 read-only REPEATABLE_READ 트랜잭션에서 조회한다. 운동당 별도 조회나 서버 캐시는 없다.

## 일반 카탈로그와 유산소 선택

- `GET /api/v1/standard-exercises/catalog`는 활성 상태와 두 검수를 충족한 운동을 기록 방식과 관계없이 `{exercises:[...]}`로 반환한다. 각 항목은 기존 이름·별칭·근육과 함께 `exerciseType`, `recordMethod`, nullable `instructionsKo`, `equipmentDescription`을 제공한다. 미연결 유산소와 조회 전용 운동도 공개 조건을 충족하면 포함한다.
- `GET /api/v1/standard-exercises/cardio-choices`는 공개된 `CARDIO` 표준 운동 중 활성 유산소 기록 설정에 연결되고 표준명이 100자 이하인 항목을 `{activities:[{id,activityId,category,name,kind,aliases}]}`로 반환한다. `id`는 표준 운동 UUID이고 `activityId`는 기존 기록 설정 UUID다. 여러 운동이 한 기록 설정을 공유할 수 있다. 신규 배치 저장 시 서버는 이 두 ID 및 선택 당시의 이름·`kind` 일치를 검증한다.
- `search-data`는 기존 세트·반복 선택 전용 계약이다. 설정의 일반 검색과 루틴·세션의 유산소 선택에는 서로 다른 응답을 사용한다. 검색은 한국어명과 별칭을 기존 NFKC·공백·대소문자 규칙으로 정규화하며 UUID별로 결과를 유지한다.
- 세 경로 모두 인증 세션과 `no-store` 응답을 사용한다. 일반 카탈로그에는 원본 ID와 GIF 참조를 포함하지 않는다.

## GET /api/v1/standard-exercises/{exerciseId}

활성 상태이고 두 검수를 마친 표준 운동을 인증 세션에서 UUID로 조회한다. 미검수·비활성·존재하지 않는 UUID는 같은 `404 EXERCISE_NOT_FOUND`를 반환한다. 잘못된 UUID는 `400 INVALID_REQUEST`다. 응답에 출처, 원본 운동 ID, GIF 주소·저장 키를 포함하지 않는다.

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "name": "바벨 벤치프레스",
  "aliases": ["bench press", "벤치"],
  "exerciseType": "WEIGHT",
  "recordMethod": "SETS_REPS",
  "equipmentDescription": "바벨",
  "jointType": "COMPOUND",
  "primaryMuscles": [
    {"id": "d3200000-0000-4000-8000-000000000002", "groupCode": "CHEST", "name": "대흉근 중부", "primaryOrder": 1}
  ],
  "secondaryMuscles": [
    {"id": "d3200000-0000-4000-8000-000000000023", "groupCode": "ARMS", "name": "상완삼두근 장두"}
  ],
  "pplSplit": "PUSH",
  "frontBackSplit": null,
  "instructionSteps": ["바를 잡는다.", "바를 민다."],
  "selectableForRoutine": true
}
```

- `equipmentDescription`, `jointType`, `pplSplit`, `frontBackSplit`은 nullable이다. 별칭·근육·운동 방법이 없으면 해당 배열은 비어 있다. PRIMARY는 저장 순서로 반환하며 SECONDARY에는 순서 필드가 없다.
- 운동 방법은 기존 `instructions_ko`의 비어 있지 않은 줄을 원래 순서대로 반환한다. 줄의 앞뒤 공백을 제거하고, 번호·문장부호로 내용을 분할하지 않는다.
- `selectableForRoutine`은 공개된 `SETS_REPS` 또는 위 유산소 선택 조건을 만족한 `CARDIO`에서 true다. `HOLD_DURATION`, `UNSPECIFIED` 및 선택 조건을 충족하지 않는 유산소는 공개 상태에서 상세 조회가 가능하며 선택 값은 false다.

## 서버 내부 검수 서비스

`CatalogReviewService.get`은 비공개 운동을 포함해 UUID, 현재 내용, 검수 상태, 활성 상태, V33 기록 설정 ID와 연결 출처를 조회한다. 여러 ExerciseDB 출처가 한 UUID에 연결될 수 있다. 각 출처의 원본 ID·영문명·GIF 참조와 대표 GIF 키는 서버 내부에서만 반환한다.

`replace`는 읽어 온 `updatedAt`과 전체 수정 내용을 받는다. 수정 가능한 내용은 이름·별칭, 운동 유형과 기록 방식, 기구 설명·관절 분류·한국어 운동 방법, 순서 있는 PRIMARY UUID 목록과 SECONDARY UUID 목록, 두 분할값과 두 검수 상태다. 운동 UUID, 활성 상태, 기존 유산소 기록 설정 ID와 원본 출처 메타데이터는 읽기 전용이다.

- `SETS_REPS`의 근육 검수 완료는 활성 PRIMARY가 하나 이상 있어야 한다. 유산소 등 다른 기록 방식은 연결이 없어도 완료할 수 있다. 두 검수는 독립적이고 분할값은 NULL인 상태로 승인할 수 있다.
- 대표 GIF 선택은 해당 운동에 연결된 원본 출처의 ID로만 지정한다. GIF 참조가 있어야 하며 검증한 참조를 V32 대표 GIF 키에 저장한다. 선택에 NULL을 주면 기존 대표 키를 해제한다.
- 동일 운동의 동시 편집은 행 잠금과 `updatedAt` 비교로 처리하며 이미 바뀐 판본은 내부 `WRITE_CONFLICT`로 거절한다. 운동 상세·별칭·근육 연결·검수 상태는 한 트랜잭션에서 저장한다.
- 검수 취소는 신규 공개·선택에 즉시 반영한다. 저장된 루틴·세션 참조와 수행 기록은 계속 사용할 수 있다.

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

기존 운동 읽기 오류 처리기를 카탈로그 조회에도 적용한다. 본문은 `{code}`다.

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | query·GET body 등 |
| 401 | AUTHENTICATION_REQUIRED | 기존 인증 실패·만료 |
| 403 | ACCESS_DENIED / CSRF_INVALID | 기존 Security 경계 |
| 404 | EXERCISE_NOT_FOUND | 상세 UUID 없음 또는 비공개 |
| 503 | WORKOUT_DATA_UNAVAILABLE | 확인된 연결/timeout 등 일시 DB 장애 |
| 500 | WORKOUT_DATA_READ_FAILED | 예상 밖 조회·매핑·SQL 오류 |

SQL·예외 원문을 응답에 포함하지 않는다. Security 예외와 MVC의405/415 같은 프로토콜 응답은 기존 처리에 맡긴다.

## 데이터 등록 경계

### ExerciseDB V1 검수 대기

원본 및 후보는 공개 카탈로그와 별개의 V34 대기 테이블에 있다. 원본 ID·영어명·기구·부위·주동근·보조근·영어 안내 단계·GIF URL을 유지하며, SHA-256은 입력 파일의 바이트 기준으로 계산한다. 검수 대기 서비스는 원본과 후보를 읽고 한국어명·방법, 별칭, 기구 설명, 운동 유형·기록 방식, 근육의 후보 초안을 별도로 저장한다. 후보 점검 및 가져오기에는 번역이나 외부 URL 조회가 필요하지 않다.

백엔드 저장소에서 `gradlew.bat exerciseDbIntake --args='"<exercises.json 경로>"'`로 미리보기를 실행한다. 명시적인 저장 검증은 같은 인자에 `--sha256=<기대 SHA-256> --store-isolated`를 추가한다. 저장 검증은 새 일회용 PostgreSQL에만 쓰며 종료 시 정리된다. 파일 불일치나 중복 원본 ID는 거절한다. 저장 트랜잭션은 저장 행 수와 누락·불일치 ID를 비교한다. 같은 입력을 다시 실행하면 기존 원본·초안·연결은 보존하고 누락된 원본만 저장한다.

정확한 이름, 정규화 이름, 동일 단계, 제한된 변형명, 근육 태그 불일치와 동작·기록 단서 충돌은 검수 근거다. 40개 기준에 대한 직접 후보·세부 판정 필요·미대응은 원본 역할과 별도로 확인한다. 이름, 안내, 자동 후보에 의해 연결·병합·공개 상태가 바뀌지 않는다.

내부 연결은 기존 표준 UUID를 선택하거나 저장한 초안을 명시적으로 새 UUID로 생성한다. 기존 UUID 연결은 해당 표준 운동의 이름·별칭·매핑·기록 설정을 유지하며, 이미 다른 운동에 연결된 원본 ID는 거절한다. 새 표준 운동은 두 검수가 미완료다. 출처의 GIF URL은 내부 대표 참조 선택과 보호 미디어 권한 검증에 사용하며 사용자 응답에 포함되지 않는다.

V16 migration 자체는 데이터를 INSERT하지 않는다. [개발용 등록 SQL](../dev-data/standard-exercise-catalog.sql)은 승인된 V17 개발 환경에서 사용한 역사적 자료다. 실제 DB의 적용·등록에는 대상, 이력, 백업, 승인 범위를 별도로 확인한다. [운영 정책과 적용 상태](../engineering/standard-exercise-handoff.md#실제-db-적용-상태)

별칭 정규화 규칙은 NFKC→Unicode 공백열을 단일 공백으로→앞뒤 공백 제거→Locale.ROOT 소문자화다. `ExerciseAlias.create`가 이를 적용하고 표시용 원문은 유지한다. 예: `　ＢＥＮＣＨ  PRESS　` → `bench press`.

DB의 PK `(standard_exercise_id,normalized_alias)`는 같은 운동의 정규화 키 중복을 차단한다. 비공백 CHECK가 정규화를 대신하지 않으므로 **직접 SQL 등록자는 동일 규칙으로 생성한 normalized_alias를 공급해야 한다.** 다른 운동의 같은 키는 허용한다. 이름/별칭만으로 UUID를 병합하지 않는다.

muscle.group_code는 CHEST/BACK/SHOULDERS/ARMS/LEGS/CORE, exercise_muscle.role은 PRIMARY/SECONDARY다. 같은 운동의 복수 PRIMARY를 허용하며 기여율 컬럼·집계표는 없다. 정확한 표준 목록·근육 매핑은 이번 테스트 fixture로 확정한 것이 아니다.

## 근육과 기록 방식

`GET /api/v1/standard-exercises/search-data`의 schemaVersion=1과 id/name/aliases는 유지하며 각 운동에 `muscles:[{id,groupCode,name,role}]`를 추가한다. 실제 exercise_muscle와 muscle 관계를 조회하며 PRIMARY/SECONDARY를 구분한다. PRIMARY는 관리자가 저장한 순서대로 먼저 반환한다. SECONDARY는 순서를 저장하지 않는다. 매핑이 없는 항목은 빈 배열이다. 프론트는 PRIMARY만 주된 설명으로 쓰며 별칭이나 이름으로 분류하지 않는다.

V32의 `CARDIO`, `HOLD_DURATION`, `UNSPECIFIED`는 표준 카탈로그에 저장할 수 있다. 이 세 방식은 현행 세트·반복 선택 API에서 제외한다. [기존 루틴 계약](routine-management.md)과 [세션 계약](workout-sessions.md)은 저장된 참조를 계속 사용할 수 있게 한다. V32는 기존 표준 운동을 일괄 검수하지 않으므로 적용 직후에는 기존 운동도 두 검수를 마칠 때까지 신규 선택 목록에서 제외된다. V35의 보호 GIF 열람 권한은 운동 상세 조회와 독립적이다. [로컬 GIF 제공](../engineering/standard-exercise-handoff.md#로컬-gif-제공)

`GET /api/v1/cardio-activities`는 인증된 카탈로그 조회(no-store)이며 `{activities:[{id,category,name,kind}]}`를 응답한다. 상위 분류는 RUNNING/WALKING/CYCLING이다. 신규 카탈로그ID 연결은 routine-management.md의 V23 계약을 따른다. 과거 유산소는 재분류하지 않는다.

## 보호 GIF 전송과 UI 계약

회원별 V35 열람 권한은 일반 운동 정보 조회와 독립적이다. 로컬 Nginx는 `/media/exercises/v1/{sourceExerciseId}.gif`의 정규 경로를 받아 기존 세션으로 내부 확인을 요청한다. Spring은 활성화 상태·회원 허용 행·현재 대표 출처를 검사하고, Nginx가 승인된 로컬 파일을 `no-store`로 전송한다. 공개 카탈로그·UUID 상세는 원본 ID와 GIF 주소를 제공하지 않으며, 현재 표준 UUID에서 보호 경로를 찾는 공개 계약은 없다.

V35 내부 검토에서는 하이 로우 한 종목에 검토 전용 UUID 매핑을 사용했다. 같은 출처의 Vite 프록시가 정규 GIF 요청과 기존 세션을 루프백 Nginx에 전달한다. 이 설정은 검토 전용이며, 회원 권한·대표 출처 확인은 기존 Spring과 Nginx가 수행한다. 정식 서비스의 대표 주소 전달과 파일 제공은 별도다. [내부 검토와 사용자 UI 연결](../engineering/standard-exercise-handoff.md#사용자-ui-연결과-후속-범위)

아래는 **로컬 Nginx 외부 응답** 기준이다. 실제 파일 권리와 운영 제공은 [배포 경계](../engineering/standard-exercise-handoff.md#후속-순서와-배포-경계)를 확인한다.

| HTTP | 로컬 보호 경로 응답 조건 |
| --- | --- |
| 200 | 정규 경로의 `GET` 또는 `HEAD`에서 내부 승인과 로컬 파일 확인이 모두 성공 |
| 401 | 로그인 세션 없음 또는 만료 |
| 403 | 회원 미허용 등 접근 거부. 내부 요청 검증과 회원 허용 확인을 통과한 원본 ID가 현재 대표 출처가 아니면 Spring은 404를 반환하고 Nginx는 외부 403으로 변환 |
| 404 | 내부 승인 후 파일 부재 또는 비정규 보호 경로 |
| 405 | 정규 보호 경로에 `GET`·`HEAD` 이외 메서드 사용 |
| 500 | 인증 상류에 연결할 수 없는 상황에서 격리 통합 테스트로 확인한 응답 |

Spring의 내부 204와 명시적 승인 헤더가 모두 일치해야 Nginx가 보호 파일 경로를 연다. 내부 판정과 외부 파일 전송의 상태 코드는 별개다. 외부 보호 응답에는 `Cache-Control: no-store`가 적용되며, PWA Service Worker는 `/media/exercises/`를 사전 캐시와 캐시 조회에서 제외한다. [Spring Security 세션](../engineering/security-session.md#http-보안-계약) · [Nginx 구성](../../deploy/local/nginx/default.conf.template) · [격리 GIF 테스트](../../src/test/java/com/overload/overload_server/catalog/ExerciseGifIntegrationTests.java)
