# 계정 프로비저닝·운영 초기값 (Task 007-1B)

현재는 **인증 검증 후 호출할 서버 내부 경로**만 구현되어 있다.
`AccountProvisioningService.provision(VerifiedIdentity, nickname)`은 계정 생성·재진입을 처리한다.
이메일 OTP 접점은 [007-1C-2](email-otp.md), Google OIDC 검증 접점은 [007-1C-3A](google-oidc.md)에 구현했다.
기존 실제 Google 브라우저 로그인 검증은 [3A-LIVE](../tasks/task-007-1c-3a-live-validation-report.md)의 당시 기록이다. 현재 Frontend 연동은 구현됐으며 최신 코드 조합의 실제 연동·실기기 검증은 별도6B 범위다.
공개 계정 생성 HTTP API, 테스트용 HTTP 우회 경로, 앱 시작 시 임의 계정/seed 생성은 없다.
007-1C-3B-1의 [인증 수단 조회·이메일 재인증](email-reauthentication.md)은 소유 identity 조회만 사용하며
프로비저닝/seed를 호출하거나 계정·신원을 연결·병합·이동·삭제하지 않는다.
007-1C-1의 보안·세션 기반과 검증 후 내부 호출 접점은 [보안·세션 문서](security-session.md)를 따른다.

## 신원 입력 계약

`VerifiedIdentity`는 요청 DTO나 인증 증명이 아니다. 007-1C의 신뢰할 수 있는 검증 코드가
검증을 마친 결과만 생성·전달해야 한다. 생성자의 형식 검사만으로 로그인 성공을 판단하지 않는다.

| 제공자 | issuer / subject |
|---|---|
| google | 검증된 `iss`의 `accounts.google.com` / `https://accounts.google.com`을 `https://accounts.google.com`으로 통일. 검증된 `sub`는 대소문자 포함 그대로 유지 |
| email | 고정 issuer `overload:email`. 검증 주소의 도메인만 Locale.ROOT 소문자화, 로컬 부분의 대소문자·점·플러스 태그는 보존 |

신원은 `(provider, issuer, subject)` 전체로 식별한다. Google 표시 이메일을 저장·비교하거나
주소 일치를 이유로 계정을 연결/병합하지 않는다. 이메일의 앞뒤 공백을 조용히 제거하거나
Gmail 점·플러스 별칭을 합치지 않는다. 입력은 비어 있거나 공백·제어문자를 포함할 수 없다.
이메일 형식 검사는 내부 계약의 최소 모양 검사이며, 완전한 주소 검증/OTP 구현이 아니다.
007-1C는 같은 주소 처리 규칙을 OTP 발급·검증·신원 조회에 일관되게 적용해야 한다.
IDN·국제화 주소 등 미구현 전달/검증 규칙을 이 단계에서 지원한다고 간주하지 않는다.
신원 객체의 `toString()`은 실제 subject를 노출하지 않는다.

## 원자성·동시성

1. 새로운 READ COMMITTED 트랜잭션에서 신원으로 계정을 찾는다. 재진입 시 사용자 행을
   `FOR UPDATE`로 잠그고 현재 UUID·세대·닉네임을 반환한다. 기존 종목/설정/닉네임을 덮어쓰지 않는다.
2. 신원이 없으면 서버 UUID로 users와 auth_identity를 삽입한다.
3. `auth_identities_credential_unique`를 지정한 `ON CONFLICT DO NOTHING`으로 동일 신원의
   동시 생성 경쟁을 처리한다. 다른 PK/FK/CHECK 오류를 성공으로 바꾸지 않는다.
4. 신원 삽입 성공 시 휴식 설정1행을 같은 트랜잭션에서 삽입하고 커밋한다. V17 이후 개인 운동 seed는 생성하지 않는다.
   어느 단계에서든 SQL 오류가 나면 전체를 롤백한다.
5. 경쟁에서 신원 삽입이 0행이면 그 요청의 users도 롤백한 후, 새 트랜잭션에서 승자의 계정을 읽는다.
   승자 계정이 그 사이 제거됐다면 오류를 전달하며 무한 재시도/자동 계정 복구는 하지 않는다.

서비스는 `REQUIRES_NEW`로 자기 트랜잭션을 소유한다. 호출자의 다른 쓰기와 함께 커밋되는 계약이 아니다.
007-1C에서 인증 검증 후 **외부 쓰기 트랜잭션을 잡지 않은 경로**로 호출한다.
외부 트랜잭션이 사용자 잠금을 보유한 상태로 호출하거나 연결 풀을 고갈시키는 중첩 호출을 만들지 않는다.
명시적 [EMAIL 연결·해제](email-identities.md)는 별도 서비스가 사용자 락과 이메일 소유 증명을 검증하며,
프로비저닝/seed를 호출하지 않는다. 기존 신원 조회는 사용자 락 이후 credential 소유권도 새로 확인한다.
계정 생성 정책과 충돌 시 전체 롤백은 유지한다. [Google LINK/UNLINK](google-identities.md)도
별도 기존 계정 변경이며 프로비저닝/seed를 호출하지 않는다.

## V2 스키마 이력과 현재 소유권·세대

다음은 V2가 추가한 구조의 기록이다. 이후 루틴·세션 등 스키마는 후속 migration으로 확장됐다. 개인 운동의 Java Entity·Repository·DTO·GET·쓰기 구현은 제거됐지만 `exercise_definitions` 테이블·기존 데이터·Flyway 이력은 남아 있다. 이 문서 정리는 테이블 삭제나 개인 DB 적용 승인이 아니다.

V1은 수정하지 않는다. `V2__create_account_provisioning.sql`은 다음만 추가한다:

- `users`: `(id, data_generation)` 복합 UNIQUE. 기존 행이나 세대·닉네임·시각을 변경하지 않는다.
- `auth_identities`: UUID PK, users FK, google/email 제공자와 고정 issuer 조합 CHECK,
  issuer/subject NOT NULL·공백만 있는 값 거부, credential 복합 UNIQUE, 생성 시각.
  계정 유지와 함께 존속하므로 운동 데이터 세대를 복제하지 않는다. 사용자별 참조 탐색을 위한 user_id 인덱스가 있다.
- `exercise_definitions`: UUID PK, user_id·data_generation·표시명·생성/변경 시각.
  현재 `(user_id, data_generation)`를 users에 복합 FK로 참조한다.
  이름은 비어 있을 수 없지만 **UNIQUE는 없다**. 당시 개인 웨이트 기본 목록을 저장했으며 현재 가입 경로에서는 삽입하지 않는다.
- `exercise_definitions`의 `(user_id, data_generation, id)` 복합 UNIQUE는
  종목을 참조하는 관계가 소유자·세대를 함께 검증할 수 있는 키다. 사용자·세대별 탐색도 이 인덱스를 사용한다.
  현재 신규 루틴·세션은 공용 표준 카탈로그 UUID를 사용한다.
- `user_workout_settings`: user_id PK (계정당 1행), 현재 user_id·data_generation 복합 FK,
  휴식 세 필드 INTEGER NOT NULL CHECK > 0, 생성/변경 시각.
- UUID는 서버에서 새로 생성한다. 생성/변경 시각은 TIMESTAMPTZ와 CURRENT_TIMESTAMP 기본값이며,
  향후 편집 구현에서 updated_at을 명시적으로 갱신한다. 삭제/갱신 cascade는 없다.

FK는 잘못된 소유자·세대의 **관계 연결**을 거부한다. SQL에 임의의 유효한 user_id를 넣는 호출자의
권한까지 증명하지는 않는다. AccountRepository는 패키지 내부에 제한되어 있고 신원 조회·새 계정 기본값을 처리한다.
운동 읽기/편집은 인증 principal의 user_id와 서버 세대를 조건으로 사용하며 클라이언트 user_id를 신뢰하지 않는다.

전체 초기화는 미구현이다. 구현 시 사용자 행의 `SELECT ... FOR UPDATE` 잠금과 이전 운동 데이터·설정·FK 관계를 고려한 명시적 계약이 필요하다. 세대 증가·데이터 삭제·새 기본값 생성 작업을 이 문서로 승인하지 않는다.
현재의 즉시 검사 FK는 자식 행을 둔 채 세대만 바꾸는 작업을 거부한다.
신원·닉네임은 유지한다. 기존 신원 재진입은 seed를 다시 실행하지 않으므로 초기화와 새 가입 처리를 혼동하지 않는다.

## 승인된 운영 초기값

`AccountSeed.WORKOUT_SETTINGS`는 휴식 기본값180/120/90초만 정의한다. 사용자·신원·설정을 생성하며 개인 운동20개를 복사하던 seed는 제거됐다. 운동 선택은 [표준 카탈로그](../api/standard-exercise-catalog.md)를 사용한다.

`WorkoutSettings`의 목표 반복 **상한** 구간은 다음과 같다:

| 상한 | 필드 | 초기 초 |
|---|---|---|
| 1~10 | rest_low_seconds | 180 |
| 11~12 | rest_medium_seconds | 120 |
| 13 이상 | rest_high_seconds | 90 |

사용자가 편집할 앱 초기값이며 운동 처방이 아니다. 가입 시 루틴·요일 일정·세션·실제 수행·승인 사건을 자동 생성하지 않는다. 해당 테이블과 API의 현재 구현은 각 [루틴](../api/routine-management.md)·[세션](../api/workout-sessions.md)·[일정](../api/weekly-schedule.md) 계약을 따른다.
기존 localStorage·`createInitialState()`·개발 목업은 읽거나 이관하지 않는다.

## 검증과 후속 연동

[로컬 PostgreSQL 문서](local-postgresql.md)의 `test`, `integrationTest`, `build` 명령으로 검증한다.
테스트는 일회성 실제 PostgreSQL을 사용한다. 프로비저닝 테스트의 SQL 실패 트리거·경쟁 제어 잠금·
소유권 FK 검사용 참조 테이블은 **테스트 DB에만** 만들며 애플리케이션과 버전 SQL에는 포함하지 않는다.

실제 계정 운동은 Frontend `features/workout-local/model.ts:restSeconds`의 **≤10 / 11~12 / ≥13**을 사용한다. `features/workout/model.ts`와 `features/settings/SettingsScreen.tsx`의 **≤8 / 9~12 / ≥13**은 `/demo` 경로의 예시이며 실제 계정의 휴식 계약으로 사용하지 않는다. 데모 저장 데이터를 실제 계정으로 이관하거나 전송하지 않는다.

## 다음 단계 전달 사항

- `VerifiedIdentity` 생성은 실제 인증 성공을 뜻하지 않는다. 007-1C는 Google 토큰 또는 이메일 소유 증명을
  검증한 신원만 프로비저닝 서비스에 전달해야 한다.
- Google과 이메일 신원은 이메일 주소가 같아도 자동 연결·병합하지 않는다.
- 최신 코드의 실제 Backend·Frontend 연결과 브라우저·실기기 검증은6B에서 별도로 수행한다. 과거 단계의 통과를 최신 조합 검증 완료로 간주하지 않는다.

## 기술 근거

- [Google issuer 및 sub 규약](https://developers.google.com/identity/openid-connect/openid-connect#validatinganidtoken)
- [PostgreSQL INSERT / ON CONFLICT](https://www.postgresql.org/docs/18/sql-insert.html)
- [Spring 트랜잭션 전파](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
