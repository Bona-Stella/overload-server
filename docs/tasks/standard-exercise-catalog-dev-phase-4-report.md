# 표준 운동 카탈로그 — 4단계 개발 DB 적용·실제 앱 통합 검증

적용·검증일: 2026-11-02 (Asia/Seoul). 상태: **완료**.

## 1. 결과와 적용 전 상태

사용자가 지정한 개인 개발 DB에 **삭제·재생성 없이 기존 V16·V17을 적용**했다. 실제 메일 OTP·쿠키·CSRF를 사용해 같은 개발 DB에 연결된 앱을 검증했다. 브라우저는 전용 테스트 프로필만 사용했다.

| 항목 | 확인 결과 |
| --- | --- |
| Backend 시작 HEAD / 상태 | `e664564081fb5a61bbaa9a0f02635b6b9c9957f7` / clean |
| Frontend 시작 HEAD / 상태 | `351ab756b6f37dd992bae16b5c085a643dc3cb56` / clean |
| 실제 JDBC | `jdbc:postgresql://127.0.0.1:5433/overload_dev` |
| DB / role / PostgreSQL | overload_dev / overload_dev / 18.6 |
| 클러스터 식별자 | `7701299869222748199`, Docker 내부와 TCP 접속 결과 일치 |
| 기존 컨테이너 | `overload-local-postgres-1`, `a3901933942976a1400f9f2f40b07035126860434d9634e8a2179a29233a5e1b` |
| 바인딩·볼륨 | 127.0.0.1:5433 → 5432, 기존 `overload-local_postgres_data` |
| 격리 | 다른 프로젝트 PostgreSQL은 별도 컨테이너·5432·별도 bind mount |
| 적용 전 Flyway | V15, 성공 이력15개, 기존 checksum validate 통과 |
| 적용 전 데이터 | public24표 중 Flyway 이력 제외 업무23표 **모두0행** |
| 다른 연결·DB | 다른 client0, prepared transaction0, replication slot0. 클러스터에는 overload_dev와 기본 postgres/template DB만 존재 |
| 프로세스·초기 상태 | 관련 Backend/Vite 및 8080/3000 listener 없음. 대상 PostgreSQL은 정지 상태 |

[V15 재구성 기록](task-009-dev-v15-rebuild-report.md), [Backend 1단계](standard-exercise-catalog-backend-phase-1-report.md)·[2단계](standard-exercise-catalog-backend-phase-2-report.md), [Frontend 3단계](../../../overload-pwa/docs/tasks/standard-exercise-catalog-frontend-phase-3-report.md), 현행 설계·API DTO·validation과 두 저장소의 AGENTS/Engineering을 확인했다. 이전 단계의 격리 DB 성공을 개인 DB 적용으로 간주하지 않았다.

## 2. 실제 migration과 무결성

| 항목 | 결과 |
| --- | --- |
| V16 | 적용 성공, checksum `1522731350`, 카탈로그5표 생성 |
| V17 | 적용 성공, checksum `1127784358`, 표준 FK·장비·schema2 CHECK·장비 불변 트리거 |
| 추가 적용 | **2개** |
| 최종 Flyway | V17 / pending0 / validate 통과 |
| 재migrate | 추가0개. 실제 앱 재기동 및 종료 후에도 확인 |
| V1~V15 이력 | version/script/checksum/installed_rank/시각/실행시간 등 전체 필드 동일 |
| V1~V17 파일 | 적용 전후 SHA-256 **17/17 동일**, 원문 수정 없음 |
| 기존 인덱스 | 57개 보존. 기존 컬럼 rename 반영만 허용해 정의 대조 |
| 제약 | 기존 개인 FK2개를 표준 FK로 교체, 컬럼 rename과 계획/평가 CHECK3개 외 기존 제약 보존. 미검증 제약·invalid/not-ready index0 |
| 트리거 | 기존 시작/최초 종료 불변2개 보존, 완료 장비 불변1개 추가·활성 |
| JSON | 계획/평가 schema2. 감사 changeContext의 별도 schema1은 유지 |
| DB 자체 | 기존 OID·owner·locale·다른 DB metadata 보존, public29표 |

기존 migration만 정상 실행했다. Flyway clean/repair/baseline·ignore 패턴 우회, 수작업 구조 보정, 기존 데이터 삭제, Docker 볼륨 삭제는 없었다. 일회성 Java/Gradle 도구와 비교 자료는 ignored `build/catalog-phase-4/`에 있다.

## 3. 개발용 카탈로그

등록 원문: [개발용 SQL](../dev-data/standard-exercise-catalog.sql). 정확한 개발 DB·role·클러스터·V17을 확인하는 guard와 단일 트랜잭션을 사용한다. 고정 UUID가 충돌하면 덮어쓰지 않고 검증 실패로 중단한다. 같은 SQL을 두 번 실행해도 중복 행·기존 값 변경이 없었다.

| 종류 | 최종 건수 |
| --- | --- |
| standard_exercise | 5 |
| exercise_alias | 10, 운동별 한국어1·영어1 |
| manufacturer | 2 |
| muscle | 6, 이름에 ‘개발 예시’ 표시 |
| exercise_muscle | 10, PRIMARY7·SECONDARY3 |

| 고정 운동 UUID | 이름 | 별칭 예시 |
| --- | --- | --- |
| d1700000-0000-4000-8000-000000000001 | 바벨 벤치프레스 | 바벨벤치프레스 / Barbell Bench Press |
| d1700000-0000-4000-8000-000000000002 | 덤벨 벤치프레스 | 덤벨벤치 / Dumbbell Bench Press |
| d1700000-0000-4000-8000-000000000003 | 불가리안 스플릿 스쿼트 | 불스스 / Bulgarian Split Squat |
| d1700000-0000-4000-8000-000000000004 | 하이로우 | 하이 로우 / High Row |
| d1700000-0000-4000-8000-000000000005 | 랫풀다운 | 랫 풀다운 / Lat Pulldown |

제조사는 `d1740000-0000-4000-8000-000000000001` 파나타, `...0002` 해머스트렝스다. 근육 UUID는 `d1720000-...0001`부터0006까지 사용한다. CHEST/ARMS/LEGS/BACK의 거친 예시이며 운영 분류를 확정한 데이터가 아니다.

제품 `ExerciseAlias.create`와 실제10행의 normalized_alias를 대조해 NFKC·Unicode 공백·Locale.ROOT 소문자 규칙 일치를 확인했다. 같은 운동/정규화 별칭 및 운동/근육 쌍 PK를 유지한다. 불가리안 스플릿 스쿼트와 하이로우에 각각 PRIMARY2개가 존재한다. 제조사와 운동 사이의 직접 연결은 만들지 않았다.

실제 유효 완료8세트를 `performed_sets → session_exercises → exercise_muscle → muscle`로 조회했다. 개발 예시 광배·상부 등 각각8세트/기여치8.0을 확인했다. 복수 PRIMARY의 근육별 기여가 겹치는 구조를 확인하는 읽기 검증이며 새 통계 API·공식 운영 분류를 추가하지 않았다.

## 4. 실제 Backend·인증

기존 production bean과 local profile을 loopback8080에서 기동했다. `.env`의 datasource를 사용하고 JDBC/클러스터를 다시 대조했다. Flyway validate·Hibernate `ddl-auto=validate`·매핑 Entity 조회가 통과했다.

기존 `.env`는 메일 발송 비활성 상태였다. 사용자에게 수신 주소와 코드를 받아 **검증 JVM에서만 실제 발송을 활성화**했다. 실제 Resend sender와 OTP 검증을 사용했으며 fake sender·test principal·인증/CSRF 우회가 없다. 영구 `.env`·비밀·쿠키 설정은 변경하지 않았다. 수신 주소/코드 임시 입력 파일은 제거했고 보고서에는 인증 비밀을 넣지 않았다.

| 실제 요청 | 결과 |
| --- | --- |
| GET health | 200 UP |
| 익명 표준 검색 | 401 |
| 실제 OTP 로그인·서버 세션 | 성공, 신규 개발 계정1개, dataGeneration=`0` |
| 인증된 search-data / manufacturers | 200, 운동5·제조사2 |
| 일반 개인 운동 POST / PATCH | 실제 CSRF를 포함해 모두403 ACCESS_DENIED |
| 가입 휴식·세대 | 180/120/90, 모든 루틴/세션/설정 DTO의 세대0 일치 |
| 로그아웃 / 재조회 | 성공 / auth session401 |

검증 중 테스트 계정의 휴식만1초로 바꿨고 완료 후 정상 API로 180/120/90을 복구했다. 최종 인증 세션0, 로그인 화면 CSRF를 위한 익명 세션1개다.

## 5. 실제 Frontend 시나리오

기존 Vite3000을 실제 Backend8080에 연결해 실행했다. 설치형 동작은 같은 Backend를 프록시하는 loopback3080의 **실제 dist**와 별도 Edge 프로필로 검사했다. 테스트 host는 대상 ready metadata를 검사하며 인증 요청을 기록하지 않는다. 장애 주입은 성공 커밋 뒤 ACK를503으로 가리는 방식이며 Backend 자체를 바꾸지 않는다.

다음9개 묶음을 실제 API/앱에서 통과했다.

1. 실제 앱 부팅의 DB2→3 일회 전환·구 테스트 문서/허용 폐기.
2. 실제 OTP/쿠키/CSRF·세대·휴식·카탈로그·개인 쓰기403.
3. 운동명/한국어·영어 별칭 검색·중복 없는 후보·추가 HTTP0, 두 표준 운동으로 루틴 생성, 장비 편집·명시 승인·삭제/RESTORE와 배치 UUID 보존.
4. 장비 재선택 없는 START, 완료 당시 장비 보존, 루틴/승인 원본 불변, snapshot ACK 유실·동일 본문 duplicate.
5. 서버 완전 중지·오프라인 추가 완료·강제 프로세스 종료·명시 cold 재진입·DB3 원본 유지·waiting 업데이트·검증 후 고정 원문 우선 재송신.
6. FINISH ACK 유실/동일 본문 재확인, 종료 조회, 중량/반복 정정, 세트 논리 삭제, 당시 장비 감사·삭제 tombstone 복구 차단·최초 평가 보존.
7. 다른 writer의 종료 후 구 미전송 원본3세트 명시 복구, 원래 UUID/수치/장비 보존, 이미 저장된 사실의 재복구0건, 최초/현재 평가 분리.
8. 다른 앱 sentinel IndexedDB/localStorage/Cache 불변, schema2·DB3 반복 열기 보존, 390px overflow 없음·Manifest 정상.
9. 실제 로그아웃401·오프라인 접근 해제·로컬 원본 유지·휴식 기본값 복구.

중단 뒤 같은 프로필·계정·루틴·기록으로 이어서 검증했다. 반복 실행 횟수를 성공 항목 수에 더하지 않았다. 실제 삭제된 세트는 복구 후보로 되살리지 않았다. 별도 **서버 누락 사실**을 명시 복구했다.

## 6. API·DB·장비 원본 대조

검증 계정1·루틴1·배치2·종료 세션2를 남겼다. 세트9행 중 유효8·논리 삭제1, 감사5건(정정1·삭제1·복구3), 루틴 승인 사건1건이다. 개인 exercise_definitions는0행이다.

| 세션/세트 | 현재 수치 | 완료 당시 제조사/부하 | 확인 |
| --- | --- | --- | --- |
| 첫 세션1 | 63 kg ×9, 원래62.5×8에서 정정 | 파나타 / PLATE | 장비 원본·정정 전후 감사 유지 |
| 첫 세션2 | 62.5 kg ×8 | 파나타 / PLATE | 이후 계획 변경 영향 없음 |
| 첫 세션3 | 62.5 kg ×8 | 해머스트렝스 / STACK | 변경 후 완료 |
| 첫 세션4·5 | 각각62.5 kg ×9 | 해머스트렝스 / STACK | 재실행·오프라인 추가 수행 원본 |
| 첫 세션6 | 62.5 kg ×9, 논리 삭제 | 해머스트렝스 / STACK | 삭제 감사·UUID·장비 유지 |
| 둘째 세션1~3 | 각각62.5 kg ×8, 명시 복구 | null / STACK | 미지정도 원본 사실로 정확히 유지 |

둘째 세션의 오프라인 제조사 목록에는 새 제조사 후보가 없으므로 미지정과 STACK을 선택하는 사례로 검증했다. 이 값은 실제 로컬 원본과 DB가 일치한다. 최종 루틴의 하이로우 배치 UUID는 동일하고 최초60 kg·승인62.5 kg·파나타/PLATE다. 세션 장비 편집이 루틴을 자동 변경하지 않았다.

API와 DB의 유효8세트 UUID·수치·장비를 대조했고, 보존된 로컬 원본9세트의 장비도 삭제 행까지 모두 일치했다. 최초 계획/평가는 schema2로 봉인됐고 사후 정정·복구의 현재 평가와 구분된다. 미완료 불가리안 스플릿 스쿼트로 전체 계획 미완료 판단은 유지했다.

## 7. 발견한 실제 결함과 최소 수정

정상 보존된 진행 운동을 cold offline으로 열지 못하는 실제 사례에서 **IndexedDB offlineResume와 localStorage fence nonce 불일치**를 확인했다. 같은 scope의 허용 갱신이 겹치거나, 새 localStorage 쓰기가 강제 종료로 유실되면 독립적으로 커밋된 포인터와 어긋날 수 있었다. 원본 운동 데이터는 손상되지 않았다.

Frontend `offline-access.ts`만 다음과 같이 보강했다.

- 허용 갱신을 직렬화하고 실행 시점의 인가·계정/세대를 다시 검사한다.
- 이미 허용된 같은 scope는 기존 nonce를 유지해 재진입 때마다 durable fence를 교체하지 않는다.
- deny/identity/scope 변경의 즉시 차단·nonce 교체·IDB 허용 제거와 명시 사용자 동의는 유지한다.

회귀3개를 추가했다: 동시 commit의 짝 보존, 대기 중 logout의 재허용 차단, 마지막 localStorage 쓰기 유실 후 기존 durable fence로 재진입. 기존 코드의 실패를 재현한 후 수정본 통과를 확인했다. 수정된 실제 dist를 정상 waiting→모든 창 종료→재실행으로 적용하고 강제 종료/cold 재진입·재송신을 실제 개발 DB에서 다시 통과했다.

Backend 제품 코드·migration·의존성·인증 정책은 수정하지 않았다.

## 8. 회귀와 검증 도구 구분

| 실행 | 최종 결과 |
| --- | --- |
| Backend `gradlew --offline --no-daemon --rerun-tasks test` | 20 suite / **220개**, 실패·오류·skip0 |
| Frontend `npm test` | **453개**, 실패·skip·취소0 |
| Frontend `npm run lint`, `npm run build` | 통과. TypeScript+Vite, JS425.49kB/gzip122.16kB |
| 기존 standalone 저장소/검색 브라우저 회귀 | **9묶음 통과**: 원자 전환·rollback·DB3·blocked/versionchange·구 앱·IME/키보드/touch·복원 |
| 실제 개발 DB·dist 통합 | 위9묶음 통과 |
| migration/DB 무결성·문서 링크·diff | 통과 |

과거 Backend 전체822개(단위220·PostgreSQL 통합602)나 5C/5D 전체 브라우저 묶음을 이번에 반복했다고 주장하지 않는다. 기존 DB 없는 단위 회귀와 이번 실제 DB 시나리오를 실행했다. 다른 PostgreSQL/Testcontainers DB·볼륨을 새로 만들지 않았다. iPhone/Android 실기기·Google OAuth·24시간 실제 대기는 미수행이다.

제품 결함과 별도로 일회성 도구를 수정한 항목:

- 최신 validate를 migrate 전 호출하면 예상 pending16/17이 거부됐다. V15 checksum 검증 후 정상 validate-on-migrate를 사용했다. ignore/repair 우회 없음.
- installed_rank의 text 별칭 정렬을 정수 순서로 바로잡고 기존15개 전체 필드를 재대조했다. 감사 schema1을 계획 schema2와 혼동하지 않도록 비교 범위를 한정했다. 실제 기존 이력 변경은 없었다.
- 최초 SW 설치 직후 강제 claim을 기대하지 않고 정상 재탐색/모든 창 종료로 제어를 확인하도록 테스트를 수정했다.
- 자동 FINISH 재확인·온라인 재연결이 이미 성공해 수동 버튼이 사라진 경우를 정상 흐름으로 처리했다. 복구 결과는 임의 제조사 상수 대신 완료 당시 원본 전체와 대조했다.
- 읽기 도구의 컬럼명을 실제 `*_snapshot`으로 맞췄고 Edge 재시작의 디버깅 포트를 다시 읽도록 보강했다. 검사나 요구사항을 비활성화하지 않았다.

## 9. 변경 파일·근거

Backend 추적 대상 변경: 개발 SQL, 이 보고서, AGENTS/README/Backend Engineering/카탈로그 API의 현행 적용 상태. Frontend 변경: `offline-access.ts`, 회귀3개, 실제 개발 DB용 별도 테스트 host/runner와 현행 문서. 기존 구현의 구조 변경이나 관리자 UI·API·캐시 라이브러리 추가는 없다.

실제 변경 파일13개(저장소 기준):

```text
overload-server/
  AGENTS.md
  README.md
  docs/api/standard-exercise-catalog.md
  docs/dev-data/standard-exercise-catalog.sql
  docs/engineering/backend.md
  docs/tasks/standard-exercise-catalog-dev-phase-4-report.md
overload-pwa/
  AGENTS.md
  README.md
  docs/engineering/frontend.md
  src/features/workout-local/offline-access.ts
  tests/pwa-offline.test.mjs
  tests/fixtures/dev-catalog-v17-server.mjs
  tests/fixtures/run-dev-catalog-v17-browser.mjs
```

주요 ignored 근거:

- Backend `build/catalog-phase-4/`: before/after JSON, migration 파일 SHA, 무결성 비교, migrate/verify/final-validate 로그, 실제 기동 로그, 카탈로그·세트·감사 읽기 결과, API/DB 비교, 실제 단위 테스트 집계.
- Frontend `logs/catalog-phase4*`: 브라우저 단계별 로그, 최종 result/domain-results JSON, 실제 화면 PNG, nonce 진단과 red/green 회귀, 최종 test/lint/build 로그. 일반 개인 브라우저 저장소는 사용하지 않았다.

## 10. 정리·승인·다음 단계

Backend·Vite·정적 테스트 host·기록된 테스트 Edge를 종료했다. 해당 프로세스와 3000/3079/3080/8080/5433 listener가 남지 않았음을 확인했다. Backend 종료 후 DB client0을 확인하고 기존 `overload-local-postgres-1`을 원래 정지 상태로 복원했다. V17·카탈로그·검증 기록은 기존 볼륨에 영속 보존된다. 기존 사용자 실행 컨테이너5개는 같은 ID/실행 상태를 유지한다. 다른 프로젝트 PostgreSQL의 bind mount와 대상 전용 volume의 실제 source도 서로 다름을 확인했다.

OTP·수신 주소 임시 입력 파일은 제거했다. 격리 프로필과 검사 JSON/PNG는 ignored logs에 검증 근거로 남겼으며 개인 브라우저 저장소를 삭제하지 않았다. 최종 logout 이후 인증 세션은0이고 익명 CSRF 세션1개만 남아 있다.

사용자가 승인한 개인 DB 정상 migration·소량 데이터 등록·테스트 계정/기록 생성·최소 결함 수정 범위에서 완료했다. 추가 승인 대기 없음. commit·push·운영 배포·다른 DB/볼륨 변경은 없다.

기존 볼륨에는 V17·개발 카탈로그·이번 검증 기록을 보존한다. 운영 카탈로그와 근육 분류는 별도 검토 후 작성하며 이 개발 SQL을 운영 seed로 사용하지 않는다. 다음 단계는 iPhone/Android 실기기의 설치·잠금/복귀·네이티브 IME와 오프라인 재실행 검증이다.

개발 환경을 다시 사용할 때는 기존 PostgreSQL 컨테이너를 시작하고 local Backend·Frontend를 실행한다. 영구 `.env`의 OTP 발송 플래그는 기존 false 상태이므로 새 이메일 로그인이 필요하면 개발자가 실제 발송을 명시적으로 활성화해야 한다. 이번 검증의 JVM override는 종료와 함께 해제됐다.
