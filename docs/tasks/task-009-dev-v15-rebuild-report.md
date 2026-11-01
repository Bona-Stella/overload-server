# Task 009 — 개인 개발 DB 초기화 및 V15 재구성

적용일: 2026-11-01 (Asia/Seoul).

## 결과

사용자가 기존 계정·운동 종목·루틴 등 전체를 폐기 가능한 테스트 데이터로 확인하고 명시 승인한 **`127.0.0.1:5433/overload_dev`만 삭제 후 재생성**했다. 기존 V1~V15를 새 DB에 순서대로 적용했으며 Flyway validate·Hibernate 스키마 검증·기본 조회가 통과했다. 백업·복원·이관은 이번 사용자 선택에 따라 수행하지 않았다. 이전 계정과 운동 데이터는 의도적으로 삭제되었다.

| 항목 | 최종 결과 |
|---|---|
| PostgreSQL | 18.6, 기존 Overload 전용 컨테이너 |
| DB / owner | overload_dev / overload_dev |
| 인코딩·locale | UTF8 / en_US.utf8, 기존 값 유지 |
| Flyway | 현재 V15, V1~V15 성공15개, pending0 |
| validate / 재migrate | 통과 / 추가 적용0 |
| public 테이블 | 24개: Flyway 이력1개 + 업무 테이블23개 |
| 초기 데이터 | 업무 테이블23개 전체0행, 계정·신원·종목·루틴·세션·승인 사건0 |
| 제약·인덱스 | 미검증 제약0, invalid/not-ready index0 |
| 세션 보호 | 사용자별 ACTIVE 부분 UNIQUE와 최초 계획/최초 종료 불변 trigger2개 확인 |
| 최신 Backend | 실제 로컬 DB 기동·Hibernate validate·JPA 엔티티12개 빈 조회 통과 |
| 프로세스 | 검증 Backend 종료, DB 연결0 확인 후 컨테이너를 원래 정지 상태로 복원 |

## 사전 확인과 대상 격리

- Backend 시작 HEAD `04ac1f5a2e10cf2e4ee716031308c50dff0f965f`, Frontend `984899c2d5545483026b6014a0361e91a18ade96`. 양쪽 작업 트리 clean이었다.
- 실제 local profile과 `.env`의 비밀이 아닌 DB명·포트·role을 확인했다. 외부 SPRING/OVERLOAD/POSTGRES 등의 환경변수 덮어쓰기는 없었다. 비밀번호·인증 비밀은 출력하거나 문서에 저장하지 않았다.
- 기존 컨테이너 `overload-local-postgres-1`, ID `a3901933942976a1400f9f2f40b07035126860434d9634e8a2179a29233a5e1b`, image `postgres:18.6-alpine`, Compose `overload-local/postgres`를 확인했다.
- 바인딩은 **127.0.0.1:5433 → container5432**, 볼륨은 기존 `overload-local_postgres_data`였다. 다른 프로젝트의 PostgreSQL은 별도 컨테이너/호스트5432였다.
- 작업 전 해당 컨테이너는 정지 상태였으며 관련 Backend와 8080/3000 listener가 없었다. 관련 없는 기존 Node 프로세스는 종료하지 않았다.
- 해당 컨테이너만 시작한 뒤 읽기 전용 검사에서 현재 V11, 계정4·신원5·종목80·루틴0·승인 사건0과 예상 Overload 테이블18개를 확인했다. V1~V11의 checksum validate도 통과했다. 과거 V10 보고서의 상태를 현재 DB 버전으로 가정하지 않았다.
- 해당 클러스터에는 overload_dev와 표준 postgres/template0/template1만 있었다. 사용자 schema는 public만, extension은 plpgsql만, 다른 client·prepared transaction·replication slot·개별 DB role setting은0이었다. 예상 밖의 프로젝트 테이블/DB는 없었다.
- JDBC가 **127.0.0.1:5433**으로 실제 TCP 접속한 클러스터 식별자와 Docker 내부 조회 결과를 대조했다. DB명만 같은 다른 서버를 대상으로 처리하지 않았다.

## 초기화와 migration

기존 프로젝트 의존성을 사용하는 일회성 Java 도구를 ignored `build/task-009-dev-rebuild/`에 작성했다. 제품 코드·의존성·migration SQL은 변경하지 않았다.

1. destructive 작업 직전 대상·클러스터·기존 데이터 건수·무연결·V11 이력을 다시 확인했다.
2. maintenance `postgres` 연결에서 overload_dev의 새 연결을 차단한 뒤 기존 연결0을 확인했다. 종료할 앱/DB client가 없어서 강제 kill이나 `pg_terminate_backend`를 사용하지 않았다.
3. **리터럴 이름 `overload_dev`만 DROP DATABASE 후 CREATE DATABASE**했다. OWNER·UTF8·locale을 유지하고 새 DB OID와 빈 public을 확인했다. FORCE·CASCADE·볼륨 삭제·Flyway clean/repair/baseline 우회는 사용하지 않았다. 다른 DB metadata는 전후 동일했다.
4. 기존 V1~V15 적용15개, validate 통과, current15/pending0, 두 번째 migrate0을 확인했다. V9가 만든 폐기 구조는 기존 V10이 정상 제거했다.
5. 최종 정확한 테이블 목록과 업무 데이터0을 비교했다. CHECK95·FK31·NOT NULL175·PK24·UNIQUE16개가 있으며 미검증 제약0이다. 전체 인덱스가 valid/ready이고 `workout_sessions_immutable_start`, `workout_sessions_immutable_closure`가 활성이다.
6. V1~V15 SQL의 작업 전후 SHA-256 **15/15 일치**를 확인했다.

## 최신 Backend 연결 및 기본 조회

최신 Backend의 실제 production bean을 local profile과 확인된 datasource로 시작했다. HTTP는 loopback 임의 포트2372를 사용했다. 테스트 principal·보안 우회·모의 DB를 사용하지 않았다. 외부 인증 제공자 호출을 피하기 위해 해당 검증 JVM에서만 Google/메일 발송을 비활성화했으며 `.env`나 제품 설정 파일은 바꾸지 않았다.

- Backend datasource의 DB명·클러스터가 재구성한 대상과 일치했다.
- Flyway validate와 Hibernate `ddl-auto=validate`가 통과했다.
- JPA에 매핑된12개 엔티티 모두 실제 빈 데이터 조회가 성공했다.
- `GET /api/v1/health`: **200**, `{"status":"UP"}`.
- 비로그인 `GET /api/v1/auth/session`, `/api/v1/exercises`, `/api/v1/routines`, `/api/v1/workout-sessions/active`: 모두 **401**. 새 빈 DB에서도 기존 인증 경계가 유지됐다.
- 종료 후 업무 테이블23개 전체0행과 다른 DB 연결0을 재확인했다. 검증용 계정·OTP·루틴·세션을 만들지 않았다.

실제 사용자 로그인 후 보호 API의200 조회, Google/Resend 호출, Frontend/모바일 E2E는 이번 DB 기동 점검에서 반복하지 않았다. 이전 최종 통합 검증의 1,222개 자동 테스트·브라우저28개를 다시 실행하지 않았다.

## 검증 도구 오류와 해결

첫 실행은 DB 재생성과 V15 적용·스키마 검사까지 성공한 뒤, 개발 의존성 DevTools가 일회성 launcher의 main을 재실행하려 했다. 재실행된 도구는 예상 V11 테이블 수 검사에서 중단되어 **두 번째 초기화는 실행되지 않았다**. 첫 실행 로그의 RECREATE_PASS·15개 적용·SCHEMA_PASS와 실제 V15/빈 DB를 독립 조회해 확인했다. 재실행의 `dropped=false` 상태는 앞선 성공한 초기화를 취소했다는 뜻이 아니다.

파괴적 도구를 다시 실행하지 않고, **DROP/CREATE/ALTER/DELETE가 없는 별도 검증 launcher**에서 `spring.devtools.restart.enabled=false`를 JVM에만 지정했다. 이 검증은 정상 기동·조회·종료까지 BUILD SUCCESSFUL로 끝났다. 제품 코드나 영구 설정 변경은 없다.

## 정리·Git·남은 사항

- 새 Java/Gradle·Backend와 모든 DB 연결을 종료했다. 직접 시작했던 PostgreSQL 컨테이너도 원래 `exited` 상태로 복원했다. 5433/2372 listener0이다. **재구성된 V15 DB는 기존 볼륨에 영속 보존**되며 DB를 사용하려면 기존 컨테이너를 다시 시작해야 한다.
- 기존 컨테이너 ID·볼륨은 유지했다. 다른 프로젝트의 기존 실행 컨테이너5개도 동일 ID·실행 상태로 보존했다. 다른 DB·Docker 볼륨·백업 파일·브라우저 IndexedDB에는 손대지 않았다.
- 도구·로그·schema/이력·최종 결과는 ignored `build/task-009-dev-rebuild/`에 있다. `run.log`는 최초 도구 종료까지, `verify.log`와 `final-result.txt`는 최종 성공 결과를 기록한다. 인증 비밀은 보고서에 포함하지 않는다.
- 이 보고서와 현재 로컬 DB 상태 안내만 갱신했다. 제품 코드·테스트·migration·설정·lockfile 변경, 전체 회귀 재실행, commit·Push 없음.
- 브라우저에 이전 계정/구 세대/구 epoch의 로컬 운동 사본이 남아 있을 수 있다. 서버 계정은 폐기됐으므로 이를 새 계정으로 자동 병합하거나 복원하지 않는다. 브라우저 데이터 삭제는 이번 승인 범위에 포함하지 않아 수행하지 않았다.
- 미해결 DB·스키마·기동 오류 없음. 새 계정 생성/실제 인증 사용은 별도 사용자 동작이다. 이번 초기화 승인은 이 개발 DB의 일회성 재구성에만 적용되며 다른 DB·후속 파괴적 작업의 포괄 승인이 아니다.
