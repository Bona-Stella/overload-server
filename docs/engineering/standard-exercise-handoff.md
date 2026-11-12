# Overload 표준 운동 카탈로그 인수인계

2026-11-11 최종 통합 검증 기준이다. Backend HEAD `061ede4`, Frontend HEAD `5543e7f`의 1~4-C 작업 트리를 확인했다. 현재 목표 스키마는 **Flyway V35**다. 이 문서의 격리 검증과 실제 DB 적용 이력은 별도로 관리한다.

## 단계별 상태

| 단계 | 구현 범위 |
| --- | --- |
| 1 · V32 | 기존 표준 운동에 상세·독립 검수·분할을 추가했다. 안정 UUID의 표준 근육 40개, 순서 있는 복수 PRIMARY와 순서 없는 SECONDARY, 표준 운동과 원본의 다대일 연결을 지원한다. |
| 2 · V33 | 일반 카탈로그와 새 루틴 선택을 분리했다. 표준 유산소 UUID를 기존 유산소 기록 설정 ID와 별개로 유지하며 루틴·세션에 연결한다. |
| 3 | 완료 세트당 PRIMARY 1.0, SECONDARY 0.5로 근육별 기여를 계산한다. 동일 상위 근육군의 실제 세트는 한 번 센다. 과거 통계 조회는 현재 매핑을 사용하고 원본 기록은 보존한다. |
| 4-A | 인증 사용자의 공개 운동 상세 조회와 서버 내부 검수·수정 서비스를 구현했다. 기존 출처에서 대표 GIF 참조를 선택할 수 있다. |
| 4-B · V34 | 원본과 검수 초안을 공개 카탈로그와 분리했다. 원본 1,500건은 일회용 DB에서만 가져왔고 재실행 시 추가 저장과 누락이 각각 0건이었다. 중복·매핑·기록 방식은 검수 후 명시적으로 결정한다. |
| 4-C · V35 | 회원 UUID별 GIF 허용 목록과 로컬 Docker Nginx의 인증·정적 전송 경로를 구현했다. 기본 제공 설정은 꺼져 있다. |

[V32 표준 근육과 출처](../../src/main/resources/db/migration/V32__expand_standard_exercise_catalog.sql) · [V33 유산소](../../src/main/resources/db/migration/V33__link_standard_cardio_exercises.sql) · [V34 검수 대기](../../src/main/resources/db/migration/V34__stage_exercisedb_v1_candidates.sql) · [V35 GIF 열람 권한](../../src/main/resources/db/migration/V35__add_exercise_gif_access.sql)

40개 표준 타겟은 가슴 4, 등 7, 어깨 8, 팔 8, 하체 10, 코어 3이다. 한 운동의 여러 PRIMARY에는 1부터 순서를 지정한다. SECONDARY에는 순서를 지정하지 않으며 같은 운동·근육에 두 역할을 중복 지정하지 않는다. 공개 조건은 활성 상태와 근육·분할 검수 완료다. 분할을 의도적으로 미지정할 수 있다. 기록 미지원 운동도 공개 상세 조회가 가능하며, 신규 선택과 저장된 루틴·세션의 수행 경계는 독립적이다. [카탈로그 계약](../api/standard-exercise-catalog.md) · [통계 계약](../api/statistics.md)

디로딩 v1.7은 운동별 분할과 전체 PRIMARY를 사용하며 중복 없음, 부분 중복, 완전 중복 순으로 후보를 비교한다. 감량, 일일 상한, 일정, 종료, 승인 중량 보호를 유지한다. 분할·매핑을 수정해도 이미 시작한 기간의 확정 계획과 수행 기록은 보존한다. [디로딩 계약](../api/deload-periods.md)

## 확정 운영 정책과 데이터 관리

- 개발자가 코드·내부 서비스·검증된 SQL로 표준 운동, 분류, 연결 및 검수 상태를 관리한다. 관리자 계정·권한 체계·HTTP API·UI는 당분간 범위에서 제외한다.
- 원본 1,500건은 ExerciseDB V1 검수 대기 자료다. 가져오기 미리보기와 일회용 DB 저장 검증은 `exerciseDbIntake`를 사용한다. 원본과 가공 초안을 분리하고, 같은 원본의 재실행은 기존 초안과 연결을 보존한다. 개발자가 검수한 연결만 기존 표준 UUID에 추가하거나 새 표준 운동을 만든다. 새 운동은 두 검수가 미완료다. [원본 가져오기와 내부 검수](../api/standard-exercise-catalog.md#데이터-등록-경계)
- 내부 서비스로 검수할 때는 현재 판본으로 저장하고, 근력 운동을 근육 검수 완료로 바꾸기 전에 활성 PRIMARY와 순서를 확인한다. 승인된 직접 SQL을 사용할 때도 같은 공개·검수·참조 규칙을 적용한다.
- 일반 공개 목록과 상세는 활성 상태 및 두 검수 완료를 요구한다. `SETS_REPS`는 웨이트 선택, `CARDIO`는 활성 기록 설정과 선택 조건을 만족할 때 유산소 선택에 들어간다. `HOLD_DURATION`과 `UNSPECIFIED`는 공개 상태이면 정보 조회가 가능하다. 검수 취소는 신규 선택을 막으며, 저장된 루틴·세션의 참조는 보존한다. 유산소의 표준 운동 UUID와 기존 `activityId`는 역할이 서로 다르므로 모두 유지한다. [공개·선택 계약](../api/standard-exercise-catalog.md#일반-카탈로그와-유산소-선택)
- GIF 권한은 운동 정보 조회 및 계정 역할과 별개인 회원 UUID 허용 목록이다. 실제 GIF 사용·재배포는 권리 확인 전까지 미승인 상태이며, 제공 기능은 기본 비활성이다.

## 실제 DB 적용 상태

V32~V35와 ExerciseDB 원본 가져오기는 일회용 PostgreSQL에서 검증했다. 개인 개발 DB의 마지막 승인 적용 기록은 2026-11-02의 V17이다. 보존 검토 DB의 마지막 직접 확인은 V24다. 이 작업에서는 개인 개발·보존·운영 DB에 접속하거나 적용하지 않았다. 실제 적용 전 대상 DB·현재 Flyway 이력·백업과 복원 가능성·승인 범위를 확인한다. 앱 시작 시 대기 migration이 자동 실행될 수 있으며, 기존 표준 운동은 V32 검수 상태를 확인하기 전까지 신규 선택에서 제외될 수 있다. [테스트 환경과 보존 경계](test-environment.md)

## 로컬 GIF 제공

1. 기존 로컬 DB 설정과 분리된 일회용 PostgreSQL에 V35까지 적용한다. Spring Boot는 그 격리 DB를 사용하고 Docker에서 도달 가능한 호스트의 로컬 포트로 실행한다.
2. [예시 환경 설정](../../deploy/local/.env.example)을 Git에서 제외되는 `deploy/local/.env`로 복사해 Git 밖의 **합성 GIF** 폴더 절대 경로를 `OVERLOAD_GIF_DIR`로 지정한다. 개인 미디어 경로·토큰·GIF 파일은 저장소에 넣지 않는다. 호스트 포트와 Backend 주소는 필요시 `OVERLOAD_GIF_NGINX_PORT`, `OVERLOAD_GIF_BACKEND_UPSTREAM`으로 설정한다. 로컬 Docker Desktop의 기본 Backend 주소는 `host.docker.internal:8080`이다. Linux의 경우 호스트 도달 방식을 별도로 맞춘다.
3. 32바이트 난수의 64자리 소문자 hex 토큰을 `OVERLOAD_GIF_PROXY_TOKEN`에 넣고 Spring Boot 프로세스에도 같은 값을 전달한다. Compose의 env 파일은 이미 실행 중인 호스트 JVM에 자동 적용되지 않는다. `OVERLOAD_GIF_SERVING_ENABLED`는 Spring Boot 시작 시 적용하는 독립 플래그다. 기본값은 false이며, 합성 파일을 쓰는 격리 검증에서만 true로 실행한다.
4. 저장소 루트에서 `docker compose --env-file deploy/local/.env -f deploy/local/compose.yml up -d`를 실행한다. Nginx는 loopback 포트만 공개하고 GIF 폴더를 읽기 전용으로 연결한다. 이 Compose는 PostgreSQL을 시작하지 않는다. 확인 후 같은 인자의 `down`으로 이 Compose만 종료한다. 처음 검증하는 환경에서는 Compose가 고정한 Nginx 이미지가 Docker에 준비되어 있어야 한다.
5. 허용하거나 회수할 회원 UUID와 접속 대상 DB를 확인한 후 `psql -X -d <확인한-격리-DB> -v user_id=<회원-UUID> -v grant_access=true -f deploy/local/gif-access.sql`을 실행한다. `grant_access=false`는 그 회원 권한만 회수한다. 기존 회원과 신규 회원의 기본값은 허용 행이 없는 상태다. [Compose](../../deploy/local/compose.yml) · [권한 관리 SQL](../../deploy/local/gif-access.sql)

`/media/exercises/v1/{sourceExerciseId}.gif`는 대소문자를 구분하는 정규 형식만 받는다. Nginx의 내부 `auth_request`가 기존 세션 쿠키, 서버에서 구성한 원본 ID, 프록시 토큰으로 Spring Boot에 확인한다. Spring은 회원 허용 행과 활성·검수 완료 운동의 현재 대표 출처를 확인한다. 동일 참조를 여러 출처가 공유해 대표 ID가 모호하면 차단한다. 이 경로는 회원 권한과 해당 원본의 대표 상태를 검증하며 화면에 표시 중인 운동을 전달받지 않는다. Spring은 파일 바이트를 읽거나 전송하지 않는다. Nginx는 Spring의 명시적 204 승인에만 보호 파일 경로를 열고 `Cache-Control: no-store`를 붙인다. 내부 경로, 경로 조작과 디렉터리 목록은 차단한다. PWA Service Worker의 설치·조회 캐시에 보호 경로를 넣지 않는다. [Nginx 구성](../../deploy/local/nginx/default.conf.template) · [Nginx 인증 모듈 동작](https://nginx.org/en/docs/http/ngx_http_auth_request_module.html)

`gradlew.bat integrationTest --tests "*ExerciseGifIntegrationTests"`는 합성 GIF, 별도 Docker Compose 프로젝트, 일회용 DB로 전송과 차단을 검증한다. Node와 Chrome이 있는 경우 `OVERLOAD_GIF_TEST_CHROME`에 Chrome 실행 파일의 절대 경로를 전달하면 같은 격리 환경의 브라우저 검증도 실행한다.

## 최종 검증 결과

2026-11-11에 이전 결과를 재사용하지 않고 Backend 단위 446건, 전체 통합 871건과 패키징을 다시 실행했다. 실패와 건너뜀은 각각 0건이었다. PWA lint·프로덕션 빌드·자동 테스트 549건도 통과했다.

- 기존 웨이트·유산소의 선택·저장, 검수 취소 후 기존 참조, 승인 중량·수행 기록, 통계와 봉인된 디로딩 계획을 재검증했다.
- 같은 원본 파일을 V35 일회용 DB에 처음 저장한 결과는 1,500건이었다. 재실행의 추가 저장, 누락, 새 표준 운동과 출처 연결은 모두 0건이었다.
- 실제 Nginx에서 합성 GIF의 응답 바이트와 no-store, 권한 부여·회수, 로그아웃·만료, 비공개·비대표 출처, Backend 장애와 비활성화를 재검증했다. 실제 Chrome은 격리 계정의 SESSION 쿠키로 200을 받았고 쿠키가 없을 때는 401을 받았다.
- 실제 Chrome의 별도 `sync`와 `boundaries` 실행에서 오프라인 재시작, 동일 Snapshot·FINISH 재전송, 무중복 저장, 계정·세션 경계와 이전 편집 기기 차단을 재검증했다. PWA의 근육 기여 1.5세트와 실제 3세트 분리 표시도 실제 응답으로 확인했다.

## 후속 순서와 배포 경계

- 실제 ExerciseDB 파일의 이용·재배포 권리, 실제 제공 대상, 운영 파일 배치와 접근 범위를 먼저 확정한다. 개인 서버의 기존 Nginx·Docker Compose·Cloudflare 구성은 이 로컬 구성과 분리한다. 별도 배포 검토에서 Backend 내부 검사 경로의 외부 접근, 비밀 토큰 관리와 검증된 업스트림 제한을 확인한다.
- 실제 서버의 HTTPS·동일 출처 세션 쿠키 동작을 검증한다. Cloudflare가 `/media/exercises/` 요청을 캐시하지 않도록 명시적인 캐시 우회 규칙을 적용하고 회원 권한 회수 직후 새 요청이 차단되는지 확인한다.
- 실제 DB 마이그레이션과 데이터 공개, 실제 GIF 배포는 별도 승인 범위다. 관리자 기능은 현행 후속 작업에 포함하지 않는다.

## 실제 운동 데이터 검토

더미 데이터 대신 실제 운동 데이터로 화면을 확인할 때는 승인된 31종목 검토 자료를 사용한다. 전용 V35 검토 DB에 계정, 월~토 루틴·활성 일정과 회원별 GIF 권한을 복원할 수 있다. 개인 개발·보존·운영 DB에는 적용하지 않는다. 원본 운동 정보와 요일별 적용값은 DB 밖의 JSON에 보관한다. [검토 시작·종료 및 초기화 후 복원](../dev-data/overload-31-catalog/README.md#실제-운동-데이터로-검토)

## 다음 작업인 사용자 UI 연결

1. 기존 설정의 공개 카탈로그 검색과 웨이트·유산소 신규 선택은 연동되어 있다. 상세 화면은 표준 UUID로 `GET /api/v1/standard-exercises/{exerciseId}`를 호출할 수 있다. 호출 허용 목록·응답 타입과 검증, 조회 가능 여부와 `selectableForRoutine`을 구분한 UI 처리는 다음 단계에서 연결한다.
2. 표준 상세 UI는 `instructionSteps`, 순서 있는 PRIMARY, SECONDARY와 분할값을 사용한다. 기존 목록은 `instructionsKo` 문자열을 제공한다. 상세 DTO의 기구 설명과 기록 방식은 표준 운동 정보이며 제조사·부하 방식 선택은 루틴 배치와 세션에 속한다. 데이터 제공 예시와 오류는 현행 계약을 따른다. [운동 상세 API](../api/standard-exercise-catalog.md#get-apiv1standard-exercisesexerciseid)
3. 현재 상세 응답에는 원본 ID나 GIF URL이 없으며 표준 UUID로 보호 GIF 경로를 찾는 공개 계약도 없다. 상세 텍스트 UI는 연결할 수 있다. GIF를 화면에 붙이기 전에 권리와 대표 경로 전달 계약을 확정한다. 표준 UUID로 원본 ID를 만들거나 외부 원본 URL을 화면에 넣지 않는다. 같은 출처의 요청과 권한·파일 미제공 UI 처리는 그 계약에 맞춘다.
4. Overview의 `cardioByCategory`는 Backend 응답에 있고 현행 PWA 통계 화면은 `cardioByKind`를 사용한다. 이번 통합 문서 정리 이후 UI를 검토·승인하고 기존 개발 절차에 따라 실제 API를 연결한다. [프런트엔드 개발 절차](../../../overload-pwa/docs/engineering/frontend.md#181-기본-ui-개발-절차)
