# Overload ERD

이 관계도는 격리 PostgreSQL 18.6에서 확인한 V30 구조에 [V31 이름 컬럼](../../src/main/resources/db/migration/V31__add_weekly_schedule_preset_name.sql)을 반영한 목표 스키마다. 실제 개발 DB의 적용 버전은 별도 확인이 필요하다. 컬럼 전체, 제약조건 이름, 인덱스와 보존 규칙은 [DB 설계서](schema.md)를 참고한다.

- 상세 관계도의 선은 PostgreSQL FK이며, 관계 이름은 자식의 FK 컬럼 순서다. 상위 개요는 이 중 대표 관계를 표시한다.
- 부모 쪽 `||`는 자식 행의 참조가 필수이고 `|o`는 일부 FK 컬럼이 NULL일 수 있음을 뜻한다. 자식 쪽 `o|`는 FK가 자식의 PK 또는 UNIQUE 조합을 포함해 부모당 최대 한 행이라는 뜻이며, `o{`는 최대 여러 행을 뜻한다. 부분 인덱스나 특정 상태로 제한된 유일성은 [설계서](schema.md)에서 확인한다.
- 실선은 자식 PK에 FK 전체가 포함된 식별 관계다. 점선은 그 외의 물리 FK다. `PK`/`FK`/`UK`는 키 구성 컬럼 표시이며 단일 컬럼의 `UK`만 별도 표기했다.
- FK의 삭제 기본 동작은 `NO ACTION`이다. `CASCADE` 세 곳은 계정·인증 및 로그인 세션 절에 표시했다.

## 전체 관계 개요

```mermaid
erDiagram
    direction LR
    users ||..o{ auth_identities : "인증 식별자"
    users ||..o| user_workout_settings : "휴식 설정"
    spring_session ||--o{ spring_session_attributes : "세션 속성 (CASCADE)"
    standard_exercise ||--o{ exercise_muscle : "운동별 근육"
    muscle ||--o{ exercise_muscle : "근육별 운동"
    users ||..o{ routines : "계정의 루틴"
    standard_exercise ||..o{ routine_weight_placements : "표준 운동"
    routines ||..o{ routine_weight_placements : "근력 배치"
    routines ||..o{ routine_cardio_placements : "유산소 배치"
    routines ||..o{ routine_plan_history : "계획 버전"
    users ||--o{ weekly_schedule_presets : "편집 프리셋"
    weekly_schedule_presets ||..o| weekly_schedule_state : "활성 프리셋"
    users ||--o{ weekly_schedule_versions : "적용 이력"
    weekly_schedule_versions |o..o{ workout_sessions : "시작 일정"
    routines |o..o{ workout_sessions : "원본 루틴"
    workout_sessions |o..o{ daily_training_assessments : "예정일 최초 시작"
    workout_sessions ||..o{ session_exercises : "세션 운동"
    session_exercises ||..o{ performed_sets : "근력 세트"
    session_exercises ||..o{ cardio_results : "유산소 수행"
    workout_sessions ||..o{ record_change_events : "정정 감사"
    routine_weight_placements ||..o{ progress_change_events : "공식 승인 성장"
    users ||..o{ deload_periods : "디로딩 기간"
    deload_periods ||..o{ deload_temporary_plans : "임시 계획"
    deload_temporary_plans |o..o{ workout_sessions : "디로딩 세션"
    users ||--o{ deload_analysis_versions : "분석 설정 이력"
    deload_periods |o..o| deload_analysis_versions : "완료 후 분석 재개"
    deload_analysis_versions ||--o{ deload_weekly_assessments : "epoch별 주간 판단"
```

## 기능별 상세 관계도

### 1. 계정·인증·휴식 설정

데이터 세대가 필요한 참조는 `users(id, data_generation)`을 사용한다. `user_reauthentication_emails`와 `reauth_email_candidates`의 직접 계정 FK에 삭제 연쇄가 있다.

```mermaid
erDiagram
    direction TB
    users {
        uuid id PK
    }
    auth_identities {
        uuid id PK
        uuid user_id FK
    }
    user_reauthentication_emails {
        uuid user_id PK, FK
        uuid id UK
    }
    reauth_email_candidates {
        uuid id PK
        uuid user_id FK
        uuid google_identity_id FK
    }
    google_identity_change_versions {
        uuid user_id PK, FK
    }
    user_workout_settings {
        uuid user_id PK, FK
        bigint data_generation FK
    }
    users ||..o{ auth_identities : "user_id"
    users ||--o| google_identity_change_versions : "user_id"
    auth_identities |o..o{ reauth_email_candidates : "user_id, google_identity_id"
    users ||..o{ reauth_email_candidates : "user_id (CASCADE)"
    users ||--o| user_reauthentication_emails : "user_id (CASCADE)"
    users ||..o| user_workout_settings : "user_id, data_generation"
```

### 2. OTP·로그인 세션

한도 행과 활성 챌린지의 상호 참조, 재인증 연락처·식별자의 소유자 범위를 표시한다. Spring Session 속성은 세션 물리 삭제 시 연쇄 삭제된다.

```mermaid
erDiagram
    direction TB
    email_otp_limits {
        character(64) email_key PK, FK
        text purpose PK, FK
        uuid active_challenge_id FK
    }
    email_otp_challenges {
        uuid id PK
        character(64) email_key FK
        text purpose FK
        uuid reauth_user_id FK
        uuid reauth_identity_id FK
        uuid reauth_contact_id FK
    }
    email_otp_login_claims {
        character(64) binding_hash PK
        uuid challenge_id FK, UK
    }
    email_otp_rate_limits {
        character(64) bucket_key PK
        text purpose PK
    }
    spring_session {
        character(36) primary_id PK
    }
    spring_session_attributes {
        character(36) session_primary_id PK, FK
        varchar(200) attribute_name PK
    }
    email_otp_limits ||..o{ email_otp_challenges : "email_key, purpose"
    user_reauthentication_emails |o..o{ email_otp_challenges : "reauth_user_id, reauth_contact_id"
    auth_identities |o..o{ email_otp_challenges : "reauth_user_id, reauth_identity_id"
    users |o..o{ email_otp_challenges : "reauth_user_id"
    email_otp_challenges |o..o| email_otp_limits : "email_key, purpose, active_challenge_id"
    email_otp_challenges ||..o| email_otp_login_claims : "challenge_id"
    spring_session ||--o{ spring_session_attributes : "session_primary_id (CASCADE)"
```

### 3. 운동 카탈로그·루틴

`exercise_muscle`은 표준 운동과 근육의 N:M 연결이며 역할을 저장한다. `exercise_definitions`는 V30에도 존재하지만 루틴·세션의 선택 경로는 `standard_exercise`다.

```mermaid
erDiagram
    direction TB
    standard_exercise {
        uuid id PK
    }
    exercise_alias {
        uuid standard_exercise_id PK, FK
        text normalized_alias PK
    }
    muscle {
        uuid id PK
    }
    exercise_muscle {
        uuid standard_exercise_id PK, FK
        uuid muscle_id PK, FK
    }
    manufacturer {
        uuid id PK
    }
    cardio_activity_catalog {
        uuid id PK
    }
    exercise_definitions {
        uuid id PK
        uuid user_id FK
        bigint data_generation FK
    }
    routines {
        uuid id PK
        uuid user_id FK
        bigint data_generation FK
    }
    routine_weight_placements {
        uuid id PK
        uuid user_id FK
        bigint data_generation FK
        uuid routine_id FK
        uuid standard_exercise_id FK
        uuid manufacturer_id FK
    }
    routine_cardio_placements {
        uuid id PK
        uuid user_id FK
        bigint data_generation FK
        uuid routine_id FK
        uuid activity_id FK
    }
    routine_plan_history {
        uuid user_id FK
        bigint data_generation FK
        uuid routine_id PK, FK
        bigint routine_version PK
    }
    standard_exercise ||--o{ exercise_alias : "standard_exercise_id"
    users ||..o{ exercise_definitions : "user_id, data_generation"
    muscle ||--o{ exercise_muscle : "muscle_id"
    standard_exercise ||--o{ exercise_muscle : "standard_exercise_id"
    cardio_activity_catalog |o..o{ routine_cardio_placements : "activity_id"
    routines ||..o{ routine_cardio_placements : "user_id, data_generation, routine_id"
    routines ||..o{ routine_plan_history : "user_id, data_generation, routine_id"
    manufacturer |o..o{ routine_weight_placements : "manufacturer_id"
    routines ||..o{ routine_weight_placements : "user_id, data_generation, routine_id"
    standard_exercise ||..o{ routine_weight_placements : "standard_exercise_id"
    users ||..o{ routines : "user_id, data_generation"
```

### 4. 주간 프리셋·일정·일별 판단

활성 프리셋 FK는 같은 사용자·세대의 프리셋을 가리킨다. 적용 이력과 프리셋은 물리 FK로 연결되지 않는다. 일별 최초 START·MISS는 아래의 개별 FK로 보존한다.

```mermaid
erDiagram
    direction TB
    weekly_schedule_presets {
        uuid user_id PK, FK
        bigint data_generation PK, FK
        uuid id PK
        varchar(100) name "nullable"
    }
    weekly_schedule_state {
        uuid user_id PK, FK
        bigint data_generation PK, FK
        uuid active_preset_id FK
    }
    weekly_schedule_versions {
        uuid user_id PK, FK
        bigint data_generation PK, FK
        bigint version PK
    }
    workout_calendar_control {
        uuid user_id PK, FK
        bigint data_generation PK, FK
        bigint baseline_version FK
    }
    daily_training_assessments {
        uuid user_id PK, FK
        bigint data_generation PK, FK
        date workout_date PK
        uuid first_session_id FK
        bigint missed_schedule_version FK
        uuid missed_routine_id FK
    }
    workout_sessions |o..o{ daily_training_assessments : "user_id, data_generation, first_session_id"
    users ||--o{ daily_training_assessments : "user_id, data_generation"
    weekly_schedule_versions |o..o{ daily_training_assessments : "user_id, data_generation, missed_schedule_version"
    routines |o..o{ daily_training_assessments : "user_id, data_generation, missed_routine_id"
    users ||--o{ weekly_schedule_presets : "user_id, data_generation"
    weekly_schedule_presets ||..o| weekly_schedule_state : "user_id, data_generation, active_preset_id"
    users ||--o{ weekly_schedule_versions : "user_id, data_generation"
    weekly_schedule_versions ||..o| workout_calendar_control : "user_id, data_generation, baseline_version"
    users ||--o| workout_calendar_control : "user_id, data_generation"
```

### 5. 운동 세션·수행·정정·성장

세션 종류와 소속을 복합 FK로 제한한다. 디로딩 기간 ID는 임시 계획을 향한 복합 FK의 일부다. 정정 대상 레코드 ID와 계획 JSON 내부 키는 하단 논리 관계를 참고한다.

```mermaid
erDiagram
    direction TB
    workout_sessions {
        uuid id PK
        uuid user_id FK
        bigint data_generation FK
        uuid source_routine_id FK
        bigint schedule_version_at_start FK
        uuid deload_period_id FK
        uuid deload_plan_id FK
    }
    session_exercises {
        uuid id PK
        uuid session_id FK
        uuid user_id FK
        bigint data_generation FK
        uuid standard_exercise_id FK
    }
    performed_sets {
        uuid id PK
        uuid session_id FK
        uuid session_exercise_id FK
        text kind FK
        uuid approval_request_id FK
        uuid manufacturer_id FK
    }
    cardio_results {
        uuid id PK
        uuid session_id FK
        uuid session_activity_id FK
        text kind FK
    }
    session_weight_approval_requests {
        uuid id PK
        uuid session_id FK
        uuid session_exercise_id FK
        text kind FK
        uuid previous_request_id FK
        uuid resolved_by_request_id FK
    }
    progress_change_events {
        uuid id PK
        uuid user_id FK
        bigint data_generation FK
        uuid routine_id FK
        uuid placement_id FK
        uuid session_id FK
        uuid session_exercise_id FK
        uuid approval_request_id FK, UK
    }
    record_change_events {
        uuid id PK
        uuid session_id FK
    }
    session_exercises ||..o{ cardio_results : "session_id, session_activity_id, kind"
    manufacturer |o..o{ performed_sets : "manufacturer_id"
    session_weight_approval_requests |o..o{ performed_sets : "session_id, session_exercise_id, approval_request_id"
    session_exercises ||..o{ performed_sets : "session_id, session_exercise_id, kind"
    session_weight_approval_requests |o..o| progress_change_events : "session_id, session_exercise_id, approval_request_id"
    routine_weight_placements ||..o{ progress_change_events : "user_id, data_generation, routine_id, placement_id"
    workout_sessions ||..o{ record_change_events : "session_id"
    standard_exercise |o..o{ session_exercises : "standard_exercise_id"
    workout_sessions ||..o{ session_exercises : "user_id, data_generation, session_id"
    session_weight_approval_requests |o..o{ session_weight_approval_requests : "session_id, session_exercise_id, previous_request_id"
    session_weight_approval_requests |o..o{ session_weight_approval_requests : "session_id, session_exercise_id, resolved_by_request_id"
    session_exercises ||..o{ session_weight_approval_requests : "session_id, session_exercise_id, kind"
    deload_temporary_plans |o..o{ workout_sessions : "user_id, data_generation, deload_period_id, deload_plan_id"
    users ||..o{ workout_sessions : "user_id, data_generation"
    routines |o..o{ workout_sessions : "user_id, data_generation, source_routine_id"
    weekly_schedule_versions |o..o{ workout_sessions : "user_id, data_generation, schedule_version_at_start"
```

### 6. 디로딩 분석·기간·임시 계획

분석 버전의 자기 참조가 epoch의 시작 버전을 보장한다. 완료한 기간은 분석 재개 버전에 연결된다. 세션의 임시 계획 참조는 운동 세션 관계도에 표시했다.

```mermaid
erDiagram
    direction TB
    deload_analysis_versions {
        uuid user_id PK, FK
        bigint data_generation PK, FK
        bigint version PK
        bigint epoch FK
        uuid completed_period_id FK, UK
    }
    deload_weekly_assessments {
        uuid user_id PK, FK
        bigint data_generation PK, FK
        bigint epoch PK, FK
        date week_start PK
    }
    deload_periods {
        uuid id PK
        uuid user_id FK
        bigint data_generation FK
    }
    deload_temporary_plans {
        uuid id PK
        uuid user_id FK
        bigint data_generation FK
        uuid period_id FK
    }
    deload_periods |o..o| deload_analysis_versions : "user_id, data_generation, completed_period_id"
    deload_analysis_versions ||..o{ deload_analysis_versions : "user_id, data_generation, epoch"
    users ||--o{ deload_analysis_versions : "user_id, data_generation"
    users ||..o{ deload_periods : "user_id, data_generation"
    deload_periods ||..o{ deload_temporary_plans : "user_id, data_generation, period_id"
    deload_analysis_versions ||--o{ deload_weekly_assessments : "user_id, data_generation, epoch"
```

### 7. Flyway 메타데이터

애플리케이션 테이블에 대한 FK가 없는 Flyway 실행 이력이다.

```mermaid
erDiagram
    direction TB
    flyway_schema_history {
        integer installed_rank PK
    }
```

## 논리 관계와 뷰

| 저장 위치 | 연결 대상 | 연결 근거 |
| --- | --- | --- |
| `weekly_schedule_presets.assignments`, `weekly_schedule_versions.assignments` | `routines` | 요일→루틴 UUID를 JSON에 저장한다. 프리셋 쓰기에서 현재 계정·세대의 루틴을 확인한다. 적용 이력에는 프리셋 ID FK가 없다. |
| `deload_periods.source_snapshot` | 프리셋 및 원본 루틴 계획 | 시작 때 복사한 근거로 읽는다. 저장된 원본 프리셋 ID에 FK를 걸지 않는다. |
| `daily_training_assessments.missed_plan_version` | `routine_plan_history` | 계정·세대·루틴·버전으로 과거 계획을 조회한다. 일정 및 루틴 FK는 물리적이지만 계획 버전 조합을 강제하는 FK는 없다. |
| 루틴 계획 이력, 세션의 최초·적용·평가 계획, 디로딩 임시 계획 JSON | 배치·운동·목표·수행 근거 | 스냅샷 내부의 식별자와 당시 값은 API와 서버가 검증·보존한다. JSON 내부 필드에는 FK가 없다. |
| `record_change_events.record_kind` / `record_id` | `performed_sets` 또는 `cardio_results` | 종류에 따라 해석되는 UUID다. 감사 행의 세션 FK만 존재한다. |
| `spring_session.principal_name` | 로그인 계정 | Spring Session의 검색 키이며 계정 FK는 없다. |
| `email_otp_challenges.retired_reauth_identity_id`, `reauth_email_candidates.retired_google_identity_id` | 퇴역 인증 식별자의 과거 UUID | 교체 후에도 참조 근거를 보존하는 값이다. 활성 식별자의 복합 FK와 구분한다. |
| `deload_analysis_contexts` | `deload_analysis_versions`, `deload_periods` | V29 조회 뷰다. 분석 버전의 epoch 시작 행을 찾아 그 행이 연결한 완료 기간에서 재개일을 읽는다. |

주간 통계와 승인 중량 성장 응답은 당시 일정, 최초 계획, 종료·수행 기록과 승인 사건을 읽어 계산한다. 영속화하는 일별 판단과 디로딩 주간 분석은 각 기능의 입력·판단 근거다. [통계와 디로딩 보존 규칙](schema.md#통계와-디로딩)
