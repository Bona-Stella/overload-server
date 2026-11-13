# Overload DB 설계서

## 기준과 범위

현행 Backend의 목표 스키마는 [Flyway V1~V35](../../src/main/resources/db/migration/)의 누적 결과다. 2026-11-09에 빈 일회용 PostgreSQL 18.6에서 V30의 `public` 카탈로그를 확인했다. V31 차이는 추가 DDL과 새 DB 적용·V30→V31 격리 전환 테스트를 대조했다. 반복 실행 migration은 없다. 실제 개발·개인·운영 DB의 적용 버전은 이번 조사에서 확인하지 않았다. [격리 검증 기록](../engineering/backend.md#125-다중-주간-일정-프리셋)

[ERD](erd.md)는 관계를, 이 문서는 컬럼과 제약조건 및 보존 규칙을 설명한다. 스키마 변경의 실행 기준은 Flyway migration이며 클라이언트 요청·응답은 [API 계약](../api/)을 따른다. DB 제약보다 좁은 쓰기 조건은 서버 코드와 함께 읽는다.

| 항목 | V31 검증 기준 |
| --- | ---: |
| 기본 테이블 | 40 (Overload 37, Spring Session 2, Flyway 1) |
| 컬럼 / NOT NULL | 379 / 281 |
| PK / FK / UNIQUE / CHECK | 40 / 55 / 23 / 177 |
| 인덱스 | 90 (제약조건 지원 63, 기타 27) |
| 사용자 트리거 / 뷰 | 22 / 1 |

V32는 표준 운동 상세·두 검수 상태·근육과 원본 연결을 추가한다. V33은 표준 운동과 유산소 기록 설정을 연결한다. V34는 ExerciseDB 원본 검수 대기, V35는 회원별 GIF 허용 행을 각각 분리한다. [표준 운동 인수인계](../engineering/standard-exercise-handoff.md)

컬럼의 `YES`/`NO`는 NULL 허용 여부다. `—`는 해당 표에서 별도 기본값, 키 또는 지원 제약조건이 없음을 뜻한다. 생성식은 기본값과 구분했다. 복합 키의 컬럼 조합과 순서는 제약조건 표를 따른다. FK는 PostgreSQL 기본 `MATCH SIMPLE`을 사용한다. `ON UPDATE`는 모두 `NO ACTION`이고, 삭제 동작은 각 FK에 표시했다. 인덱스 정의의 `public.`은 카탈로그에 출력된 스키마 한정 이름이다.

## 목차

- [계정·인증·휴식 설정](#1-계정인증휴식-설정)
- [OTP·로그인 세션](#2-otp로그인-세션)
- [운동 카탈로그·루틴](#3-운동-카탈로그루틴)
- [주간 프리셋·일정·일별 판단](#4-주간-프리셋일정일별-판단)
- [운동 세션·수행·정정·성장](#5-운동-세션수행정정성장)
- [디로딩 분석·기간·임시 계획](#6-디로딩-분석기간임시-계획)
- [Flyway 메타데이터](#7-flyway-메타데이터)
- [뷰와 데이터 보존](#8-뷰와-데이터-보존)
- [Flyway 변경 이력](#9-flyway-변경-이력)

## 1. 계정·인증·휴식 설정

사용자 소유권의 기준은 계정 ID다. 운동 도메인에는 현재 데이터 세대를 함께 저장하고 복합 FK로 보호한다.

[`users`](#users) · [`auth_identities`](#auth_identities) · [`user_reauthentication_emails`](#user_reauthentication_emails) · [`reauth_email_candidates`](#reauth_email_candidates) · [`google_identity_change_versions`](#google_identity_change_versions) · [`user_workout_settings`](#user_workout_settings)

### `users`

계정과 현재 운동 데이터 세대. 인증 연결과 운동 데이터의 소유권 기준이다.

근거 [V1](../../src/main/resources/db/migration/V1__create_users.sql) · [V2](../../src/main/resources/db/migration/V2__create_account_provisioning.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `data_generation` | `bigint` | NO | `0` | — | 소유 계정의 운동 데이터 세대. |
| `nickname` | `text` | YES | — | — | 계정 표시명. |
| `created_at` | `timestamp with time zone` | NO | `CURRENT_TIMESTAMP` | — | 생성 시각. |
| `updated_at` | `timestamp with time zone` | NO | `CURRENT_TIMESTAMP` | — | 마지막 갱신 시각. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `users_pkey` | `PRIMARY KEY (id)` |
| UNIQUE | `users_id_generation_unique` | `UNIQUE (id, data_generation)` |
| CHECK | `users_data_generation_nonnegative` | `CHECK ((data_generation >= 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `users_id_generation_unique` | `CREATE UNIQUE INDEX users_id_generation_unique ON public.users USING btree (id, data_generation)` | `users_id_generation_unique` |
| `users_pkey` | `CREATE UNIQUE INDEX users_pkey ON public.users USING btree (id)` | `users_pkey` |

### `auth_identities`

인증 공급자 식별자. 공급자·발급자·주체로 계정을 찾고, Google 재인증은 소유자를 함께 확인한다.

근거 [V2](../../src/main/resources/db/migration/V2__create_account_provisioning.sql) · [V5](../../src/main/resources/db/migration/V5__add_email_reauthentication.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `provider` | `text` | NO | — | — | 인증 공급자 코드. |
| `issuer` | `text` | NO | — | — | 발급자 식별자. |
| `subject` | `text` | NO | — | — | 발급자가 부여한 계정 식별자. |
| `created_at` | `timestamp with time zone` | NO | `CURRENT_TIMESTAMP` | — | 생성 시각. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `auth_identities_pkey` | `PRIMARY KEY (id)` |
| FK | `auth_identities_user_id_fkey` | `FOREIGN KEY (user_id) REFERENCES users(id)` · 삭제 `NO ACTION` |
| UNIQUE | `auth_identities_credential_unique` | `UNIQUE (provider, issuer, subject)` |
| UNIQUE | `auth_identities_owner_id_unique` | `UNIQUE (user_id, id)` |
| CHECK | `auth_identities_issuer_check` | `CHECK ((issuer !~ '^[[:space:]]*$'::text))` |
| CHECK | `auth_identities_provider_issuer` | `CHECK ((((provider = 'google'::text) AND (issuer = 'https://accounts.google.com'::text)) OR ((provider = 'email'::text) AND (issuer = 'overload:email'::text))))` |
| CHECK | `auth_identities_subject_check` | `CHECK ((subject !~ '^[[:space:]]*$'::text))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `auth_identities_credential_unique` | `CREATE UNIQUE INDEX auth_identities_credential_unique ON public.auth_identities USING btree (provider, issuer, subject)` | `auth_identities_credential_unique` |
| `auth_identities_owner_id_unique` | `CREATE UNIQUE INDEX auth_identities_owner_id_unique ON public.auth_identities USING btree (user_id, id)` | `auth_identities_owner_id_unique` |
| `auth_identities_pkey` | `CREATE UNIQUE INDEX auth_identities_pkey ON public.auth_identities USING btree (id)` | `auth_identities_pkey` |
| `auth_identities_user_id_index` | `CREATE INDEX auth_identities_user_id_index ON public.auth_identities USING btree (user_id)` | — |

### `user_reauthentication_emails`

사용자의 재인증 이메일과 검증 상태. 물리 계정 삭제 시 연쇄 삭제된다.

근거 [V6](../../src/main/resources/db/migration/V6__add_reauthentication_emails.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `user_id` | `uuid` | NO | — | PK, FK | 소유 계정 ID. |
| `id` | `uuid` | NO | — | UK | 행 식별자. |
| `email` | `text` | NO | — | — | 재인증 이메일 주소. |
| `status` | `text` | NO | `'VERIFIED'::text` | — | 행의 수명주기 상태. |
| `verified_at` | `timestamp with time zone` | NO | — | — | 연락처 검증 시각. |
| `verification_source` | `text` | NO | — | — | 연락처 검증 경로. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `user_reauthentication_emails_pkey` | `PRIMARY KEY (user_id)` |
| FK | `user_reauthentication_emails_user_id_fkey` | `FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE` |
| UNIQUE | `user_reauthentication_emails_id_key` | `UNIQUE (id)` |
| UNIQUE | `user_reauthentication_emails_user_id_id_key` | `UNIQUE (user_id, id)` |
| CHECK | `user_reauthentication_emails_email_check` | `CHECK ((email <> ''::text))` |
| CHECK | `user_reauthentication_emails_status_check` | `CHECK ((status = 'VERIFIED'::text))` |
| CHECK | `user_reauthentication_emails_verification_source_check` | `CHECK ((verification_source = ANY (ARRAY['HISTORICAL_OTP'::text, 'EMAIL_LOGIN_OTP'::text, 'GOOGLE_EMAIL_OTP'::text])))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `user_reauthentication_emails_id_key` | `CREATE UNIQUE INDEX user_reauthentication_emails_id_key ON public.user_reauthentication_emails USING btree (id)` | `user_reauthentication_emails_id_key` |
| `user_reauthentication_emails_pkey` | `CREATE UNIQUE INDEX user_reauthentication_emails_pkey ON public.user_reauthentication_emails USING btree (user_id)` | `user_reauthentication_emails_pkey` |
| `user_reauthentication_emails_user_id_id_key` | `CREATE UNIQUE INDEX user_reauthentication_emails_user_id_id_key ON public.user_reauthentication_emails USING btree (user_id, id)` | `user_reauthentication_emails_user_id_id_key` |

### `reauth_email_candidates`

Google 연결에 귀속된 재인증 이메일 등록 후보. 사용·만료·무효화 및 퇴역 식별자 근거를 보존한다. 계정 FK는 연쇄 삭제한다.

근거 [V6](../../src/main/resources/db/migration/V6__add_reauthentication_emails.sql) · [V8](../../src/main/resources/db/migration/V8__preserve_google_unlink_references.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `google_identity_id` | `uuid` | YES | — | FK | 현재 Google 인증 식별자 ID. |
| `google_subject` | `text` | NO | — | — | 검증 시점의 Google 주체 값. |
| `email` | `text` | NO | — | — | 재인증 이메일 주소. |
| `binding_hash` | `character(64)` | NO | — | — | 요청이 묶인 브라우저 바인딩 해시. |
| `expires_at` | `timestamp with time zone` | NO | — | — | 만료 시각. |
| `consumed_at` | `timestamp with time zone` | YES | — | — | 사용된 시각. |
| `retired_google_identity_id` | `uuid` | YES | — | — | 교체된 Google 식별자의 과거 UUID. |
| `invalidated_at` | `timestamp with time zone` | YES | — | — | 후보가 무효화된 시각. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `reauth_email_candidates_pkey` | `PRIMARY KEY (id)` |
| FK | `reauth_email_candidate_identity_fk` | `FOREIGN KEY (user_id, google_identity_id) REFERENCES auth_identities(user_id, id)` · 삭제 `NO ACTION` |
| FK | `reauth_email_candidates_user_id_fkey` | `FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE` |
| UNIQUE | `reauth_email_candidates_user_id_id_key` | `UNIQUE (user_id, id)` |
| CHECK | `reauth_email_candidates_check` | `CHECK ((((google_identity_id IS NOT NULL) AND (retired_google_identity_id IS NULL)) OR ((google_identity_id IS NULL) AND (retired_google_identity_id IS NOT NULL) AND (invalidated_at IS NOT NULL))))` |
| CHECK | `reauth_email_candidates_email_check` | `CHECK ((email <> ''::text))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `reauth_email_candidates_owner_index` | `CREATE INDEX reauth_email_candidates_owner_index ON public.reauth_email_candidates USING btree (user_id)` | — |
| `reauth_email_candidates_pkey` | `CREATE UNIQUE INDEX reauth_email_candidates_pkey ON public.reauth_email_candidates USING btree (id)` | `reauth_email_candidates_pkey` |
| `reauth_email_candidates_user_id_id_key` | `CREATE UNIQUE INDEX reauth_email_candidates_user_id_id_key ON public.reauth_email_candidates USING btree (user_id, id)` | `reauth_email_candidates_user_id_id_key` |

### `google_identity_change_versions`

Google 식별자 연결 변경 버전을 사용자별로 관리한다.

근거 [V8](../../src/main/resources/db/migration/V8__preserve_google_unlink_references.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `user_id` | `uuid` | NO | — | PK, FK | 소유 계정 ID. |
| `version` | `bigint` | NO | — | — | 행 또는 이력의 버전. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `google_identity_change_versions_pkey` | `PRIMARY KEY (user_id)` |
| FK | `google_identity_change_versions_user_id_fkey` | `FOREIGN KEY (user_id) REFERENCES users(id)` · 삭제 `NO ACTION` |
| CHECK | `google_identity_change_versions_version_check` | `CHECK ((version >= 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `google_identity_change_versions_pkey` | `CREATE UNIQUE INDEX google_identity_change_versions_pkey ON public.google_identity_change_versions USING btree (user_id)` | `google_identity_change_versions_pkey` |

### `user_workout_settings`

운동 휴식 프리셋과 버전. 사용자 및 데이터 세대 범위에서 참조된다.

근거 [V2](../../src/main/resources/db/migration/V2__create_account_provisioning.sql) · [V9](../../src/main/resources/db/migration/V9__add_workout_write_foundation.sql) · [V10](../../src/main/resources/db/migration/V10__remove_obsolete_workout_command_storage.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `user_id` | `uuid` | NO | — | PK, FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | FK | 소유 계정의 운동 데이터 세대. |
| `rest_low_seconds` | `integer` | NO | — | — | 낮은 강도 휴식 프리셋(초). |
| `rest_medium_seconds` | `integer` | NO | — | — | 중간 강도 휴식 프리셋(초). |
| `rest_high_seconds` | `integer` | NO | — | — | 높은 강도 휴식 프리셋(초). |
| `created_at` | `timestamp with time zone` | NO | `CURRENT_TIMESTAMP` | — | 생성 시각. |
| `updated_at` | `timestamp with time zone` | NO | `CURRENT_TIMESTAMP` | — | 마지막 갱신 시각. |
| `version` | `bigint` | NO | `0` | — | 행 또는 이력의 버전. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `user_workout_settings_pkey` | `PRIMARY KEY (user_id)` |
| FK | `user_workout_settings_owner_generation_fk` | `FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)` · 삭제 `NO ACTION` |
| CHECK | `user_workout_settings_data_generation_check` | `CHECK ((data_generation >= 0))` |
| CHECK | `user_workout_settings_rest_high_seconds_check` | `CHECK ((rest_high_seconds > 0))` |
| CHECK | `user_workout_settings_rest_low_seconds_check` | `CHECK ((rest_low_seconds > 0))` |
| CHECK | `user_workout_settings_rest_medium_seconds_check` | `CHECK ((rest_medium_seconds > 0))` |
| CHECK | `user_workout_settings_version_check` | `CHECK ((version >= 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `user_workout_settings_pkey` | `CREATE UNIQUE INDEX user_workout_settings_pkey ON public.user_workout_settings USING btree (user_id)` | `user_workout_settings_pkey` |

## 2. OTP·로그인 세션

OTP 발급·검증·한도와 Spring Session의 로그인 세션을 저장한다.

[`email_otp_limits`](#email_otp_limits) · [`email_otp_challenges`](#email_otp_challenges) · [`email_otp_login_claims`](#email_otp_login_claims) · [`email_otp_rate_limits`](#email_otp_rate_limits) · [`spring_session`](#spring_session) · [`spring_session_attributes`](#spring_session_attributes)

### `email_otp_limits`

이메일·목적별 활성 챌린지, 발급 간격과 실패 윈도우를 관리한다.

근거 [V4](../../src/main/resources/db/migration/V4__create_email_otp.sql) · [V5](../../src/main/resources/db/migration/V5__add_email_reauthentication.sql) · [V6](../../src/main/resources/db/migration/V6__add_reauthentication_emails.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `email_key` | `character(64)` | NO | — | PK, FK | 정규화된 이메일 제한 키. |
| `purpose` | `text` | NO | — | PK, FK | OTP 또는 요청 한도 목적. |
| `active_challenge_id` | `uuid` | YES | — | FK | 현재 활성 챌린지 ID. |
| `last_issued_at` | `timestamp with time zone` | YES | — | — | 최근 발급 시각. |
| `failed_attempts` | `integer` | NO | `0` | — | 검증 실패 횟수. |
| `failure_window_end` | `timestamp with time zone` | NO | — | — | 실패 집계 윈도우 종료 시각. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `email_otp_limits_pkey` | `PRIMARY KEY (email_key, purpose)` |
| FK | `email_otp_active_challenge_fk` | `FOREIGN KEY (email_key, purpose, active_challenge_id) REFERENCES email_otp_challenges(email_key, purpose, id)` · 삭제 `NO ACTION` |
| CHECK | `email_otp_limits_failed_attempts_check` | `CHECK (((failed_attempts >= 0) AND (failed_attempts <= 5)))` |
| CHECK | `email_otp_limits_purpose_check` | `CHECK ((purpose = ANY (ARRAY['LOGIN'::text, 'REAUTH'::text, 'CONTACT_ENROLL'::text])))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `email_otp_limits_pkey` | `CREATE UNIQUE INDEX email_otp_limits_pkey ON public.email_otp_limits USING btree (email_key, purpose)` | `email_otp_limits_pkey` |

### `email_otp_challenges`

이메일 OTP 챌린지와 검증 수명주기. 재인증에는 현재 연락처·인증 식별자와 퇴역 식별자를 구분한다.

근거 [V4](../../src/main/resources/db/migration/V4__create_email_otp.sql) · [V5](../../src/main/resources/db/migration/V5__add_email_reauthentication.sql) · [V6](../../src/main/resources/db/migration/V6__add_reauthentication_emails.sql) · [V7](../../src/main/resources/db/migration/V7__preserve_retired_email_otp_identity_references.sql) · [V8](../../src/main/resources/db/migration/V8__preserve_google_unlink_references.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `email_key` | `character(64)` | NO | — | FK | 정규화된 이메일 제한 키. |
| `purpose` | `text` | NO | — | FK | OTP 또는 요청 한도 목적. |
| `recipient` | `text` | NO | — | — | OTP 발송 주소. |
| `binding_hash` | `character(64)` | NO | — | — | 요청이 묶인 브라우저 바인딩 해시. |
| `verifier` | `bytea` | NO | — | — | OTP 검증에 쓰는 값. |
| `failed_attempts` | `integer` | NO | `0` | — | 검증 실패 횟수. |
| `state` | `text` | NO | — | — | 처리 또는 판단 상태. |
| `issued_at` | `timestamp with time zone` | NO | — | — | 챌린지 발급 시각. |
| `expires_at` | `timestamp with time zone` | NO | — | — | 만료 시각. |
| `accepted_at` | `timestamp with time zone` | YES | — | — | 챌린지 수락 시각. |
| `consumed_at` | `timestamp with time zone` | YES | — | — | 사용된 시각. |
| `reauth_user_id` | `uuid` | YES | — | FK | 재인증 대상 계정 ID. |
| `reauth_identity_id` | `uuid` | YES | — | FK | 현재 재인증 식별자 ID. |
| `reauth_contact_id` | `uuid` | YES | — | FK | 검증된 재인증 연락처 ID. |
| `retired_reauth_identity_id` | `uuid` | YES | — | — | 교체된 재인증 식별자의 과거 UUID. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `email_otp_challenges_pkey` | `PRIMARY KEY (id)` |
| FK | `email_otp_challenges_email_key_purpose_fkey` | `FOREIGN KEY (email_key, purpose) REFERENCES email_otp_limits(email_key, purpose)` · 삭제 `NO ACTION` |
| FK | `email_otp_challenges_reauth_user_id_reauth_contact_id_fkey` | `FOREIGN KEY (reauth_user_id, reauth_contact_id) REFERENCES user_reauthentication_emails(user_id, id)` · 삭제 `NO ACTION` |
| FK | `email_otp_reauth_identity_fk` | `FOREIGN KEY (reauth_user_id, reauth_identity_id) REFERENCES auth_identities(user_id, id)` · 삭제 `NO ACTION` |
| FK | `email_otp_reauth_user_fk` | `FOREIGN KEY (reauth_user_id) REFERENCES users(id)` · 삭제 `NO ACTION` |
| UNIQUE | `email_otp_challenges_email_key_purpose_id_key` | `UNIQUE (email_key, purpose, id)` |
| CHECK | `email_otp_challenges_check` | `CHECK ((expires_at > issued_at))` |
| CHECK | `email_otp_challenges_check1` | `CHECK (((state = 'CONSUMED'::text) = (consumed_at IS NOT NULL)))` |
| CHECK | `email_otp_challenges_check2` | `CHECK (((state <> 'ACTIVE'::text) OR (accepted_at IS NOT NULL)))` |
| CHECK | `email_otp_challenges_failed_attempts_check` | `CHECK (((failed_attempts >= 0) AND (failed_attempts <= 5)))` |
| CHECK | `email_otp_challenges_purpose_check` | `CHECK ((purpose = ANY (ARRAY['LOGIN'::text, 'REAUTH'::text, 'CONTACT_ENROLL'::text])))` |
| CHECK | `email_otp_challenges_recipient_check` | `CHECK ((recipient <> ''::text))` |
| CHECK | `email_otp_challenges_state_check` | `CHECK ((state = ANY (ARRAY['PENDING'::text, 'ACTIVE'::text, 'FAILED'::text, 'REVOKED'::text, 'CONSUMED'::text])))` |
| CHECK | `email_otp_challenges_verifier_check` | `CHECK ((octet_length(verifier) = 32))` |
| CHECK | `email_otp_reauth_binding_check` | `CHECK ((((purpose = 'LOGIN'::text) AND (reauth_user_id IS NULL) AND (reauth_identity_id IS NULL) AND (reauth_contact_id IS NULL) AND (retired_reauth_identity_id IS NULL)) OR ((purpose = 'REAUTH'::text) AND (reauth_user_id IS NOT NULL) AND (((reauth_identity_id IS NOT NULL) AND (reauth_contact_id IS NULL) AND (retired_reauth_identity_id IS NULL)) OR ((reauth_identity_id IS NULL) AND (reauth_contact_id IS NOT NULL) AND (retired_reauth_identity_id IS NULL)) OR ((reauth_identity_id IS NULL) AND (reauth_contact_id IS NULL) AND (retired_reauth_identity_id IS NOT NULL)))) OR ((purpose = 'CONTACT_ENROLL'::text) AND (reauth_user_id IS NOT NULL) AND (reauth_contact_id IS NULL) AND (((reauth_identity_id IS NOT NULL) AND (retired_reauth_identity_id IS NULL)) OR ((reauth_identity_id IS NULL) AND (retired_reauth_identity_id IS NOT NULL))))))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `email_otp_challenges_email_key_purpose_id_key` | `CREATE UNIQUE INDEX email_otp_challenges_email_key_purpose_id_key ON public.email_otp_challenges USING btree (email_key, purpose, id)` | `email_otp_challenges_email_key_purpose_id_key` |
| `email_otp_challenges_expiry_index` | `CREATE INDEX email_otp_challenges_expiry_index ON public.email_otp_challenges USING btree (expires_at)` | — |
| `email_otp_challenges_pkey` | `CREATE UNIQUE INDEX email_otp_challenges_pkey ON public.email_otp_challenges USING btree (id)` | `email_otp_challenges_pkey` |

### `email_otp_login_claims`

브라우저 바인딩별 로그인 챌린지 수령 기록. 챌린지 참조와 중복 수령 방지 근거다.

근거 [V4](../../src/main/resources/db/migration/V4__create_email_otp.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `binding_hash` | `character(64)` | NO | — | PK | 요청이 묶인 브라우저 바인딩 해시. |
| `challenge_id` | `uuid` | NO | — | FK, UK | 연결된 챌린지 ID. |
| `claimed_at` | `timestamp with time zone` | NO | — | — | 바인딩에 로그인 챌린지를 등록한 시각. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `email_otp_login_claims_pkey` | `PRIMARY KEY (binding_hash)` |
| FK | `email_otp_login_claims_challenge_id_fkey` | `FOREIGN KEY (challenge_id) REFERENCES email_otp_challenges(id)` · 삭제 `NO ACTION` |
| UNIQUE | `email_otp_login_claims_challenge_id_key` | `UNIQUE (challenge_id)` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `email_otp_login_claims_challenge_id_key` | `CREATE UNIQUE INDEX email_otp_login_claims_challenge_id_key ON public.email_otp_login_claims USING btree (challenge_id)` | `email_otp_login_claims_challenge_id_key` |
| `email_otp_login_claims_pkey` | `CREATE UNIQUE INDEX email_otp_login_claims_pkey ON public.email_otp_login_claims USING btree (binding_hash)` | `email_otp_login_claims_pkey` |

### `email_otp_rate_limits`

목적별 요청 제한 버킷. 챌린지·사용자에 대한 FK는 없다.

근거 [V4](../../src/main/resources/db/migration/V4__create_email_otp.sql) · [V5](../../src/main/resources/db/migration/V5__add_email_reauthentication.sql) · [V6](../../src/main/resources/db/migration/V6__add_reauthentication_emails.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `bucket_key` | `character(64)` | NO | — | PK | 요청 제한 버킷 키. |
| `purpose` | `text` | NO | — | PK | OTP 또는 요청 한도 목적. |
| `window_started_at` | `timestamp with time zone` | NO | — | — | 요청 집계 윈도우 시작 시각. |
| `hits` | `integer` | NO | — | — | 현재 윈도우의 요청 횟수. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `email_otp_rate_limits_pkey` | `PRIMARY KEY (bucket_key, purpose)` |
| CHECK | `email_otp_rate_limits_hits_check` | `CHECK ((hits >= 0))` |
| CHECK | `email_otp_rate_limits_purpose_check` | `CHECK ((purpose = ANY (ARRAY['LOGIN'::text, 'REAUTH'::text, 'CONTACT_ENROLL'::text])))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `email_otp_rate_limits_pkey` | `CREATE UNIQUE INDEX email_otp_rate_limits_pkey ON public.email_otp_rate_limits USING btree (bucket_key, purpose)` | `email_otp_rate_limits_pkey` |

### `spring_session`

Spring Session JDBC의 로그인 세션, 만료 정보, principal 검색 키.

근거 [V3](../../src/main/resources/db/migration/V3__create_spring_session.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `primary_id` | `character(36)` | NO | — | PK | Spring Session 내부 PK. |
| `session_id` | `character(36)` | NO | — | — | 클라이언트와 연결하는 Spring Session ID. |
| `creation_time` | `bigint` | NO | — | — | 세션 생성 시각의 epoch 밀리초. |
| `last_access_time` | `bigint` | NO | — | — | 마지막 접근 시각의 epoch 밀리초. |
| `max_inactive_interval` | `integer` | NO | — | — | 최대 비활성 기간(초). |
| `expiry_time` | `bigint` | NO | — | — | 세션 만료 시각의 epoch 밀리초. |
| `principal_name` | `character varying(100)` | YES | — | — | Spring Session principal 인덱스 키. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `spring_session_pk` | `PRIMARY KEY (primary_id)` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `spring_session_ix1` | `CREATE UNIQUE INDEX spring_session_ix1 ON public.spring_session USING btree (session_id)` | — |
| `spring_session_ix2` | `CREATE INDEX spring_session_ix2 ON public.spring_session USING btree (expiry_time)` | — |
| `spring_session_ix3` | `CREATE INDEX spring_session_ix3 ON public.spring_session USING btree (principal_name)` | — |
| `spring_session_pk` | `CREATE UNIQUE INDEX spring_session_pk ON public.spring_session USING btree (primary_id)` | `spring_session_pk` |

### `spring_session_attributes`

Spring Session이 직렬화한 세션 속성. 부모 세션 삭제 시 연쇄 삭제된다.

근거 [V3](../../src/main/resources/db/migration/V3__create_spring_session.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `session_primary_id` | `character(36)` | NO | — | PK, FK | 부모 로그인 세션 내부 PK. |
| `attribute_name` | `character varying(200)` | NO | — | PK | 세션 속성 이름. |
| `attribute_bytes` | `bytea` | NO | — | — | 직렬화한 세션 속성. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `spring_session_attributes_pk` | `PRIMARY KEY (session_primary_id, attribute_name)` |
| FK | `spring_session_attributes_fk` | `FOREIGN KEY (session_primary_id) REFERENCES spring_session(primary_id) ON DELETE CASCADE` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `spring_session_attributes_pk` | `CREATE UNIQUE INDEX spring_session_attributes_pk ON public.spring_session_attributes USING btree (session_primary_id, attribute_name)` | `spring_session_attributes_pk` |

## 3. 운동 카탈로그·루틴

현행 근력 선택은 표준 운동과 선택 제조사를 사용한다. 유산소 배치는 선택한 카탈로그 항목 또는 기존 이름·종류를 보존한다.

[`standard_exercise`](#standard_exercise) · [`exercise_alias`](#exercise_alias) · [`muscle`](#muscle) · [`exercise_muscle`](#exercise_muscle) · [`exercise_source`](#exercise_source) · [`manufacturer`](#manufacturer) · [`cardio_activity_catalog`](#cardio_activity_catalog) · [`exercise_definitions`](#exercise_definitions) · [`routines`](#routines) · [`routine_weight_placements`](#routine_weight_placements) · [`routine_cardio_placements`](#routine_cardio_placements) · [`routine_plan_history`](#routine_plan_history)

V33의 표준 유산소 연결은 `standard_exercise.cardio_activity_id` → `cardio_activity_catalog.id`를 사용한다. 한 설정을 여러 운동이 공유한다. `routine_cardio_placements.standard_exercise_id`와 CARDIO의 `session_exercises.standard_exercise_id`는 과거 데이터에서 NULL이다. 신규 연결 세션은 당시 설정 ID·측정 종류·이름을 함께 저장한다. `cardio_results`는 계속 기존 세션 활동 ID를 참조한다. 기존 JSON·해시·기록 행을 변환하는 SQL은 없다. [V33 SQL](../../src/main/resources/db/migration/V33__link_standard_cardio_exercises.sql)

#### ExerciseDB V1 검수 대기

`exercise_db_v1_candidate`는 대소문자를 구분하는 원본 ID를 PK로 사용한다. `source_file_sha256`과 1부터 시작하는 `source_position` 조합은 고유하다. `original`에는 원본 JSON 객체와 순서 있는 배열을, `findings`에는 규칙으로 분석한 후보 근거를 저장한다. `review_draft`는 별도의 내부 초안이며 `draft_version`으로 동시 편집을 검증한다. 표준 운동 연결은 명시적 결정 후 V32의 `exercise_source`에서 관리한다. V34는 빈 테이블만 생성한다. [V34 SQL](../../src/main/resources/db/migration/V34__stage_exercisedb_v1_candidates.sql)

### `standard_exercise`

표준 운동 카탈로그. 루틴 배치와 세션 운동이 참조하며 한국어 이름, 운동 유형, 기록 방식과 검수 상태를 저장한다.

근거 [V16](../../src/main/resources/db/migration/V16__create_standard_exercise_catalog.sql) · [V18](../../src/main/resources/db/migration/V18__add_standard_exercise_joint_type.sql) · [V32](../../src/main/resources/db/migration/V32__expand_standard_exercise_catalog.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `name` | `text` | NO | — | — | 표시 이름. |
| `is_active` | `boolean` | NO | `true` | — | 카탈로그 활성 여부. |
| `created_at` | `timestamp with time zone` | NO | `CURRENT_TIMESTAMP` | — | 생성 시각. |
| `updated_at` | `timestamp with time zone` | NO | `CURRENT_TIMESTAMP` | — | 마지막 갱신 시각. |
| `joint_type` | `text` | YES | — | — | 표준 운동의 관절 분류. |
| `exercise_type` | `text` | NO | `WEIGHT` | — | WEIGHT, CARDIO, STATIC_HOLD, STRETCH, OTHER. |
| `record_method` | `text` | NO | `SETS_REPS` | — | SETS_REPS, CARDIO, HOLD_DURATION, UNSPECIFIED. |
| `ppl_split` / `front_back_split` | `text` | YES | — | — | 운동별 PPL(PUSH/PULL/LEGS) 및 전면·후면(FRONT/BACK). |
| `instructions_ko` / `equipment_description` | `text` | YES | — | — | 한국어 운동 방법 및 선택적 기구 설명. |
| `representative_gif_key` | `text` | YES | — | — | 운동당 하나의 비공개 대표 GIF 참조 키. |
| `muscle_targets_reviewed` / `splits_reviewed` | `boolean` | NO | `false` | — | 서로 독립적인 근육 타겟·분할 검수 상태. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `standard_exercise_pkey` | `PRIMARY KEY (id)` |
| CHECK | `standard_exercise_joint_type_check` | `CHECK (((joint_type IS NULL) OR (joint_type = ANY (ARRAY['COMPOUND'::text, 'ISOLATION'::text, 'OTHER'::text]))))` |
| CHECK | `standard_exercise_name_check` | `CHECK ((name !~ '^[[:space:]]*$'::text))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `standard_exercise_pkey` | `CREATE UNIQUE INDEX standard_exercise_pkey ON public.standard_exercise USING btree (id)` | `standard_exercise_pkey` |

### `exercise_alias`

표준 운동의 검색용 별칭과 정규화 키.

근거 [V16](../../src/main/resources/db/migration/V16__create_standard_exercise_catalog.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `standard_exercise_id` | `uuid` | NO | — | PK, FK | 표준 근력 운동 ID. |
| `alias` | `text` | NO | — | — | 검색에 사용하는 별칭. |
| `normalized_alias` | `text` | NO | — | PK | 검색용으로 정규화한 별칭. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `exercise_alias_pkey` | `PRIMARY KEY (standard_exercise_id, normalized_alias)` |
| FK | `exercise_alias_standard_exercise_id_fkey` | `FOREIGN KEY (standard_exercise_id) REFERENCES standard_exercise(id)` · 삭제 `NO ACTION` |
| CHECK | `exercise_alias_alias_check` | `CHECK ((alias !~ '^[[:space:]]*$'::text))` |
| CHECK | `exercise_alias_normalized_alias_check` | `CHECK ((normalized_alias !~ '^[[:space:]]*$'::text))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `exercise_alias_pkey` | `CREATE UNIQUE INDEX exercise_alias_pkey ON public.exercise_alias USING btree (standard_exercise_id, normalized_alias)` | `exercise_alias_pkey` |

### `muscle`

근육 카탈로그와 그룹 코드. V32는 고정 UUID로 표준 근육 40개를 등록하며 기존 근육과 연결을 보존한다. 기본 분할은 운동별 분할을 자동 확정하는 데 사용하지 않는다.

근거 [V16](../../src/main/resources/db/migration/V16__create_standard_exercise_catalog.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `group_code` | `text` | NO | — | — | 근육 그룹 코드. |
| `default_ppl_split` / `default_front_back_split` | `text` | YES | — | — | 근육 기본 분할. 운동별 컬럼과 같은 허용값을 사용한다. |
| `name` | `text` | NO | — | — | 표시 이름. |
| `is_active` | `boolean` | NO | `true` | — | 카탈로그 활성 여부. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `muscle_pkey` | `PRIMARY KEY (id)` |
| CHECK | `muscle_group_code_check` | `CHECK ((group_code = ANY (ARRAY['CHEST'::text, 'BACK'::text, 'SHOULDERS'::text, 'ARMS'::text, 'LEGS'::text, 'CORE'::text])))` |
| CHECK | `muscle_name_check` | `CHECK ((name !~ '^[[:space:]]*$'::text))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `muscle_pkey` | `CREATE UNIQUE INDEX muscle_pkey ON public.muscle USING btree (id)` | `muscle_pkey` |

### `exercise_muscle`

표준 운동과 근육의 다대다 매핑 및 근육 역할.

근거 [V16](../../src/main/resources/db/migration/V16__create_standard_exercise_catalog.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `standard_exercise_id` | `uuid` | NO | — | PK, FK | 표준 근력 운동 ID. |
| `muscle_id` | `uuid` | NO | — | PK, FK | 근육 ID. |
| `role` | `text` | NO | — | — | 해당 표준 운동에서 근육이 맡는 역할. |
| `primary_order` | `integer` | YES | — | UNIQUE (운동, 순서) | PRIMARY의 양의 정수 순서. SECONDARY는 NULL. |

V32는 기존 PRIMARY의 순서만 근육 UUID 오름차순으로 채운다. 관리용 백엔드 서비스는 연결 전체를 트랜잭션에서 교체하며 PRIMARY 순서를 1부터 연속으로 부여한다.

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `exercise_muscle_pkey` | `PRIMARY KEY (standard_exercise_id, muscle_id)` |
| FK | `exercise_muscle_muscle_id_fkey` | `FOREIGN KEY (muscle_id) REFERENCES muscle(id)` · 삭제 `NO ACTION` |
| FK | `exercise_muscle_standard_exercise_id_fkey` | `FOREIGN KEY (standard_exercise_id) REFERENCES standard_exercise(id)` · 삭제 `NO ACTION` |
| UNIQUE | `exercise_muscle_primary_order_unique` | `UNIQUE (standard_exercise_id, primary_order)` |
| CHECK | `exercise_muscle_role_order_check` | PRIMARY는 1 이상, SECONDARY는 NULL. |
| CHECK | `exercise_muscle_role_check` | `CHECK ((role = ANY (ARRAY['PRIMARY'::text, 'SECONDARY'::text])))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `exercise_muscle_pkey` | `CREATE UNIQUE INDEX exercise_muscle_pkey ON public.exercise_muscle USING btree (standard_exercise_id, muscle_id)` | `exercise_muscle_pkey` |

### `exercise_source`

근거 [V32](../../src/main/resources/db/migration/V32__expand_standard_exercise_catalog.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 키 | 역할 |
| --- | --- | --- | --- | --- |
| `source_system` | `text` | NO | PK | EXERCISEDB_V1. |
| `source_exercise_id` | `text` | NO | PK | 원본 운동 식별자. |
| `standard_exercise_id` | `uuid` | NO | FK | `standard_exercise(id)` 참조. 한 표준 운동에 여러 원본을 연결할 수 있다. |
| `original_name` | `text` | NO | — | 원본 영어 운동명. |
| `original_gif_reference` | `text` | YES | — | 원본 GIF 주소 또는 참조 키. |

원본 ID와 표준 운동 UUID는 독립적이다. `standard_exercise_id`의 조회 인덱스가 있으며, 참조된 표준 운동을 자동 삭제하지 않는다.

### `exercise_gif_access`

회원별 GIF 열람을 허용하는 행이다. `user_id`는 UUID PK이며 `users(id)`를 참조한다. 계정 삭제는 해당 허용 행도 삭제한다. `granted_at`은 `timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP`다. V35는 빈 테이블을 만들며 기존·신규 회원의 기본 상태는 허용 행 없음이다. [V35 SQL](../../src/main/resources/db/migration/V35__add_exercise_gif_access.sql) · [부여·회수 SQL](../../deploy/local/gif-access.sql)

### `manufacturer`

장비 제조사 카탈로그. 루틴 배치와 완료 세트의 선택적 장비 기록이 참조한다.

근거 [V16](../../src/main/resources/db/migration/V16__create_standard_exercise_catalog.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `name` | `text` | NO | — | — | 표시 이름. |
| `is_active` | `boolean` | NO | `true` | — | 카탈로그 활성 여부. |
| `created_at` | `timestamp with time zone` | NO | `CURRENT_TIMESTAMP` | — | 생성 시각. |
| `updated_at` | `timestamp with time zone` | NO | `CURRENT_TIMESTAMP` | — | 마지막 갱신 시각. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `manufacturer_pkey` | `PRIMARY KEY (id)` |
| CHECK | `manufacturer_name_check` | `CHECK ((name !~ '^[[:space:]]*$'::text))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `manufacturer_pkey` | `CREATE UNIQUE INDEX manufacturer_pkey ON public.manufacturer USING btree (id)` | `manufacturer_pkey` |

### `cardio_activity_catalog`

유산소 카탈로그. 종류와 표시 분류를 가지며, V23에서 추가된 루틴 배치의 항목 참조 대상이다.

근거 [V23](../../src/main/resources/db/migration/V23__routine_tag_and_cardio_catalog.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `category` | `text` | NO | — | — | 유산소 카탈로그 표시 분류. |
| `name` | `text` | NO | — | — | 표시 이름. |
| `kind` | `text` | NO | — | — | 유형 구분자. |
| `active` | `boolean` | NO | `true` | — | 유산소 항목 활성 여부. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `cardio_activity_catalog_pkey` | `PRIMARY KEY (id)` |
| CHECK | `cardio_activity_catalog_category_check` | `CHECK ((category = ANY (ARRAY['RUNNING'::text, 'WALKING'::text, 'CYCLING'::text])))` |
| CHECK | `cardio_activity_catalog_kind_check` | `CHECK ((kind = ANY (ARRAY['TREADMILL'::text, 'BIKE'::text, 'OUTDOOR'::text, 'STAIRS'::text, 'OTHER'::text])))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `cardio_activity_catalog_pkey` | `CREATE UNIQUE INDEX cardio_activity_catalog_pkey ON public.cardio_activity_catalog USING btree (id)` | `cardio_activity_catalog_pkey` |

### `exercise_definitions`

과거 개인 운동 정의가 남아 있는 테이블. V17에서 현행 배치·세션의 참조를 표준 운동으로 교체했다. 이 테이블을 사용하는 현행 제품 경로는 소스에서 확인되지 않았다.

근거 [V2](../../src/main/resources/db/migration/V2__create_account_provisioning.sql) · [V9](../../src/main/resources/db/migration/V9__add_workout_write_foundation.sql) · [V10](../../src/main/resources/db/migration/V10__remove_obsolete_workout_command_storage.sql) · [V17](../../src/main/resources/db/migration/V17__connect_standard_exercises_to_workouts.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | FK | 소유 계정의 운동 데이터 세대. |
| `name` | `text` | NO | — | — | 표시 이름. |
| `created_at` | `timestamp with time zone` | NO | `CURRENT_TIMESTAMP` | — | 생성 시각. |
| `updated_at` | `timestamp with time zone` | NO | `CURRENT_TIMESTAMP` | — | 마지막 갱신 시각. |
| `version` | `bigint` | NO | `0` | — | 행 또는 이력의 버전. |
| `deleted_at` | `timestamp with time zone` | YES | — | — | 논리 삭제 시각. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `exercise_definitions_pkey` | `PRIMARY KEY (id)` |
| FK | `exercise_definitions_owner_generation_fk` | `FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)` · 삭제 `NO ACTION` |
| UNIQUE | `exercise_definitions_owner_generation_id_unique` | `UNIQUE (user_id, data_generation, id)` |
| CHECK | `exercise_definitions_data_generation_check` | `CHECK ((data_generation >= 0))` |
| CHECK | `exercise_definitions_name_check` | `CHECK ((name !~ '^[[:space:]]*$'::text))` |
| CHECK | `exercise_definitions_version_check` | `CHECK ((version >= 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `exercise_definitions_owner_generation_id_unique` | `CREATE UNIQUE INDEX exercise_definitions_owner_generation_id_unique ON public.exercise_definitions USING btree (user_id, data_generation, id)` | `exercise_definitions_owner_generation_id_unique` |
| `exercise_definitions_pkey` | `CREATE UNIQUE INDEX exercise_definitions_pkey ON public.exercise_definitions USING btree (id)` | `exercise_definitions_pkey` |

### `routines`

사용자·세대별 루틴 헤더. 수정 버전과 논리 삭제 시각을 유지한다.

근거 [V11](../../src/main/resources/db/migration/V11__create_routine_management.sql) · [V23](../../src/main/resources/db/migration/V23__routine_tag_and_cardio_catalog.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | FK | 소유 계정의 운동 데이터 세대. |
| `name` | `text` | NO | — | — | 표시 이름. |
| `version` | `bigint` | NO | `0` | — | 행 또는 이력의 버전. |
| `created_at` | `timestamp with time zone` | NO | — | — | 생성 시각. |
| `updated_at` | `timestamp with time zone` | NO | — | — | 마지막 갱신 시각. |
| `deleted_at` | `timestamp with time zone` | YES | — | — | 논리 삭제 시각. |
| `tag` | `text` | YES | — | — | 루틴 분류 태그. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `routines_pkey` | `PRIMARY KEY (id)` |
| FK | `routines_user_id_data_generation_fkey` | `FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)` · 삭제 `NO ACTION` |
| UNIQUE | `routines_user_id_data_generation_id_key` | `UNIQUE (user_id, data_generation, id)` |
| CHECK | `routines_data_generation_check` | `CHECK ((data_generation >= 0))` |
| CHECK | `routines_name_check` | `CHECK ((name !~ '^[[:space:]]*$'::text))` |
| CHECK | `routines_tag_check` | `CHECK ((tag = ANY (ARRAY['PUSH'::text, 'PULL'::text, 'LEGS'::text, 'FRONT'::text, 'BACK'::text, 'FULL'::text, 'UPPER'::text, 'LOWER'::text, 'CARDIO'::text, 'MIXED'::text])))` |
| CHECK | `routines_version_check` | `CHECK ((version >= 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `routines_pkey` | `CREATE UNIQUE INDEX routines_pkey ON public.routines USING btree (id)` | `routines_pkey` |
| `routines_user_id_data_generation_id_key` | `CREATE UNIQUE INDEX routines_user_id_data_generation_id_key ON public.routines USING btree (user_id, data_generation, id)` | `routines_user_id_data_generation_id_key` |

### `routine_weight_placements`

루틴의 근력 배치, 반복·세트 목표와 승인 중량. 제거한 배치는 삭제 시각으로 보존한다.

근거 [V11](../../src/main/resources/db/migration/V11__create_routine_management.sql) · [V17](../../src/main/resources/db/migration/V17__connect_standard_exercises_to_workouts.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | FK | 소유 계정의 운동 데이터 세대. |
| `routine_id` | `uuid` | NO | — | FK | 소속 또는 연결 루틴 ID. |
| `standard_exercise_id` | `uuid` | NO | — | FK | 표준 근력 운동 ID. |
| `position` | `integer` | NO | — | — | 계획 내 배치 순서. |
| `set_count` | `integer` | NO | — | — | 계획 세트 수. |
| `min_reps` | `integer` | NO | — | — | 계획 반복 수 하한. |
| `max_reps` | `integer` | NO | — | — | 계획 반복 수 상한. |
| `initial_weight` | `numeric(20,6)` | NO | — | — | 배치 최초 중량(kg). |
| `approved_weight` | `numeric(20,6)` | NO | — | — | 배치의 현재 승인 중량(kg). |
| `created_at` | `timestamp with time zone` | NO | — | — | 생성 시각. |
| `updated_at` | `timestamp with time zone` | NO | — | — | 마지막 갱신 시각. |
| `deleted_at` | `timestamp with time zone` | YES | — | — | 논리 삭제 시각. |
| `manufacturer_id` | `uuid` | YES | — | FK | 기록한 제조사 ID. |
| `load_type` | `text` | YES | — | — | 장비의 부하 방식. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `routine_weight_placements_pkey` | `PRIMARY KEY (id)` |
| FK | `routine_weight_placements_manufacturer_id_fkey` | `FOREIGN KEY (manufacturer_id) REFERENCES manufacturer(id)` · 삭제 `NO ACTION` |
| FK | `routine_weight_placements_user_id_data_generation_routine__fkey` | `FOREIGN KEY (user_id, data_generation, routine_id) REFERENCES routines(user_id, data_generation, id)` · 삭제 `NO ACTION` |
| FK | `routine_weight_standard_exercise_fk` | `FOREIGN KEY (standard_exercise_id) REFERENCES standard_exercise(id)` · 삭제 `NO ACTION` |
| UNIQUE | `routine_weight_placements_user_id_data_generation_routine_i_key` | `UNIQUE (user_id, data_generation, routine_id, id)` |
| CHECK | `routine_weight_placements_approved_weight_check` | `CHECK (((approved_weight >= (0)::numeric) AND (approved_weight < ('100000000000000'::bigint)::numeric)))` |
| CHECK | `routine_weight_placements_check` | `CHECK ((max_reps >= min_reps))` |
| CHECK | `routine_weight_placements_initial_weight_check` | `CHECK (((initial_weight >= (0)::numeric) AND (initial_weight < ('100000000000000'::bigint)::numeric)))` |
| CHECK | `routine_weight_placements_load_type_check` | `CHECK (((load_type IS NULL) OR (load_type = ANY (ARRAY['PLATE'::text, 'STACK'::text, 'DUMBBELL'::text, 'BODYWEIGHT'::text]))))` |
| CHECK | `routine_weight_placements_min_reps_check` | `CHECK ((min_reps > 0))` |
| CHECK | `routine_weight_placements_position_check` | `CHECK (("position" >= 0))` |
| CHECK | `routine_weight_placements_set_count_check` | `CHECK ((set_count > 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `routine_weight_active_position` | `CREATE UNIQUE INDEX routine_weight_active_position ON public.routine_weight_placements USING btree (routine_id, "position") WHERE (deleted_at IS NULL)` | — |
| `routine_weight_placements_pkey` | `CREATE UNIQUE INDEX routine_weight_placements_pkey ON public.routine_weight_placements USING btree (id)` | `routine_weight_placements_pkey` |
| `routine_weight_placements_user_id_data_generation_routine_i_key` | `CREATE UNIQUE INDEX routine_weight_placements_user_id_data_generation_routine_i_key ON public.routine_weight_placements USING btree (user_id, data_generation, routine_id, id)` | `routine_weight_placements_user_id_data_generation_routine_i_key` |

### `routine_cardio_placements`

루틴의 유산소 배치와 목표·장비 설정. 항목 선택과 별개로 계획 당시 이름·종류를 저장한다.

근거 [V11](../../src/main/resources/db/migration/V11__create_routine_management.sql) · [V23](../../src/main/resources/db/migration/V23__routine_tag_and_cardio_catalog.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | FK | 소유 계정의 운동 데이터 세대. |
| `routine_id` | `uuid` | NO | — | FK | 소속 또는 연결 루틴 ID. |
| `position` | `integer` | NO | — | — | 계획 내 배치 순서. |
| `kind` | `text` | NO | — | — | 유형 구분자. |
| `name` | `text` | NO | — | — | 표시 이름. |
| `target_seconds` | `integer` | NO | — | — | 유산소 목표 활동 시간(초). |
| `target_distance` | `numeric(20,6)` | YES | — | — | 유산소 목표 거리(km). |
| `intensity_mode` | `text` | YES | — | — | 강도 입력 방식. |
| `intensity_value` | `numeric(20,6)` | YES | — | — | 방식에 따른 목표 강도 값. |
| `incline` | `numeric(20,6)` | YES | — | — | 경사 설정값. |
| `resistance` | `numeric(20,6)` | YES | — | — | 저항 설정값. |
| `created_at` | `timestamp with time zone` | NO | — | — | 생성 시각. |
| `updated_at` | `timestamp with time zone` | NO | — | — | 마지막 갱신 시각. |
| `deleted_at` | `timestamp with time zone` | YES | — | — | 논리 삭제 시각. |
| `activity_id` | `uuid` | YES | — | FK | 선택한 유산소 카탈로그 항목 ID. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `routine_cardio_placements_pkey` | `PRIMARY KEY (id)` |
| FK | `routine_cardio_placements_activity_id_fkey` | `FOREIGN KEY (activity_id) REFERENCES cardio_activity_catalog(id)` · 삭제 `NO ACTION` |
| FK | `routine_cardio_placements_user_id_data_generation_routine__fkey` | `FOREIGN KEY (user_id, data_generation, routine_id) REFERENCES routines(user_id, data_generation, id)` · 삭제 `NO ACTION` |
| CHECK | `routine_cardio_placements_check` | `CHECK ((((intensity_mode IS NULL) AND (intensity_value IS NULL)) OR ((intensity_mode IS NOT NULL) AND (intensity_value IS NOT NULL) AND (((intensity_mode = 'SPEED'::text) AND ((intensity_value >= 0.1) AND (intensity_value <= (100)::numeric))) OR ((intensity_mode = 'PACE'::text) AND ((intensity_value >= 0.1) AND (intensity_value <= (120)::numeric)))))))` |
| CHECK | `routine_cardio_placements_check1` | `CHECK (((kind = ANY (ARRAY['TREADMILL'::text, 'BIKE'::text, 'OUTDOOR'::text])) OR ((target_distance IS NULL) AND (intensity_mode IS NULL))))` |
| CHECK | `routine_cardio_placements_check2` | `CHECK (((kind = 'TREADMILL'::text) OR (incline IS NULL)))` |
| CHECK | `routine_cardio_placements_check3` | `CHECK (((kind = 'BIKE'::text) OR (resistance IS NULL)))` |
| CHECK | `routine_cardio_placements_incline_check` | `CHECK (((incline >= (0)::numeric) AND (incline <= (50)::numeric)))` |
| CHECK | `routine_cardio_placements_kind_check` | `CHECK ((kind = ANY (ARRAY['TREADMILL'::text, 'BIKE'::text, 'OUTDOOR'::text, 'STAIRS'::text, 'OTHER'::text])))` |
| CHECK | `routine_cardio_placements_name_check` | `CHECK (((name !~ '^[[:space:]]*$'::text) AND (char_length(name) <= 100)))` |
| CHECK | `routine_cardio_placements_position_check` | `CHECK (("position" >= 0))` |
| CHECK | `routine_cardio_placements_resistance_check` | `CHECK (((resistance >= (1)::numeric) AND (resistance <= (100)::numeric)))` |
| CHECK | `routine_cardio_placements_target_distance_check` | `CHECK (((target_distance >= 0.001) AND (target_distance <= (1000)::numeric)))` |
| CHECK | `routine_cardio_placements_target_seconds_check` | `CHECK (((target_seconds >= 1) AND (target_seconds <= 86400)))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `routine_cardio_active_position` | `CREATE UNIQUE INDEX routine_cardio_active_position ON public.routine_cardio_placements USING btree (routine_id, "position") WHERE (deleted_at IS NULL)` | — |
| `routine_cardio_placements_pkey` | `CREATE UNIQUE INDEX routine_cardio_placements_pkey ON public.routine_cardio_placements USING btree (id)` | `routine_cardio_placements_pkey` |

### `routine_plan_history`

루틴 버전별 계획 JSON과 해당 버전의 삭제 여부. 과거 예정일 근거를 조회할 때 사용한다.

근거 [V19](../../src/main/resources/db/migration/V19__add_weekly_schedule_and_routine_plan_history.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | FK | 소유 계정의 운동 데이터 세대. |
| `routine_id` | `uuid` | NO | — | PK, FK | 소속 또는 연결 루틴 ID. |
| `routine_version` | `bigint` | NO | — | PK | 저장 당시 루틴 버전. |
| `recorded_at` | `timestamp with time zone` | NO | — | — | 서버에 근거가 기록된 시각. |
| `deleted` | `boolean` | NO | — | — | 해당 루틴 이력 버전의 삭제 상태. |
| `plan` | `jsonb` | NO | — | — | 해당 버전의 루틴 계획 JSON. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `routine_plan_history_pkey` | `PRIMARY KEY (routine_id, routine_version)` |
| FK | `routine_plan_history_user_id_data_generation_routine_id_fkey` | `FOREIGN KEY (user_id, data_generation, routine_id) REFERENCES routines(user_id, data_generation, id)` · 삭제 `NO ACTION` |
| CHECK | `routine_plan_history_data_generation_check` | `CHECK ((data_generation >= 0))` |
| CHECK | `routine_plan_history_plan_check` | `CHECK ((jsonb_typeof(plan) = 'object'::text))` |
| CHECK | `routine_plan_history_routine_version_check` | `CHECK ((routine_version >= 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `routine_plan_history_pkey` | `CREATE UNIQUE INDEX routine_plan_history_pkey ON public.routine_plan_history USING btree (routine_id, routine_version)` | `routine_plan_history_pkey` |
| `routine_plan_history_scope` | `CREATE INDEX routine_plan_history_scope ON public.routine_plan_history USING btree (user_id, data_generation, recorded_at, routine_id, routine_version)` | — |

| 트리거 | 정의 |
| --- | --- |
| `routine_plan_history_immutable` | `CREATE TRIGGER routine_plan_history_immutable BEFORE UPDATE ON public.routine_plan_history FOR EACH ROW EXECUTE FUNCTION preserve_weekly_planning_history()` |

## 4. 주간 프리셋·일정·일별 판단

V30에서 편집 가능한 프리셋과 현재 설정을 분리했다. 실제 적용 이력 및 일별 최초 근거는 기존 저장소에 남는다.

[`weekly_schedule_presets`](#weekly_schedule_presets) · [`weekly_schedule_state`](#weekly_schedule_state) · [`weekly_schedule_versions`](#weekly_schedule_versions) · [`workout_calendar_control`](#workout_calendar_control) · [`daily_training_assessments`](#daily_training_assessments)

### `weekly_schedule_presets`

요일별 루틴 배정과 표시 순서, 자동 순환 포함 여부, 선택적 저장 이름을 가진 사용자·세대별 프리셋.

근거 [V30](../../src/main/resources/db/migration/V30__create_weekly_schedule_presets.sql) · [V31](../../src/main/resources/db/migration/V31__add_weekly_schedule_preset_name.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `user_id` | `uuid` | NO | — | PK, FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | PK, FK | 소유 계정의 운동 데이터 세대. |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `display_order` | `smallint` | NO | — | — | 계정 안에서의 프리셋 표시 순서. |
| `rotation_included` | `boolean` | NO | `false` | — | 자동 순환 후보 포함 여부. |
| `preset_version` | `bigint` | NO | `0` | — | 프리셋 편집 버전. |
| `assignments` | `jsonb` | NO | `'[]'::jsonb` | — | 요일과 루틴 ID 배정 배열(JSON). |
| `name` | `character varying(100)` | YES | — | 선택적 저장 이름. 이름의 유일성 제약은 없다. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `weekly_schedule_presets_pkey` | `PRIMARY KEY (user_id, data_generation, id)` |
| FK | `weekly_schedule_presets_user_id_data_generation_fkey` | `FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)` · 삭제 `NO ACTION` |
| UNIQUE | `weekly_schedule_presets_user_id_data_generation_display_ord_key` | `UNIQUE (user_id, data_generation, display_order) DEFERRABLE` |
| CHECK | `weekly_schedule_presets_assignments_check` | `CHECK ( CASE WHEN (jsonb_typeof(assignments) = 'array'::text) THEN (jsonb_array_length(assignments) <= 7) ELSE false END)` |
| CHECK | `weekly_schedule_presets_display_order_check` | `CHECK (((display_order >= 1) AND (display_order <= 4)))` |
| CHECK | `weekly_schedule_presets_preset_version_check` | `CHECK ((preset_version >= 0))` |
| CHECK | `weekly_schedule_presets_name_check` | `CHECK (name !~ '^[[:space:]]*$')` · NULL 허용 |

V31은 기존 행을 재작성하거나 이름을 채우지 않는다. 이름을 저장하지 않은 행의 표시 이름은 서버 조회 시 표시 순번으로 생성한다. 이름이 바뀌면 해당 `preset_version`만 증가한다. 정규화와 버전 검증은 [이름 변경 API](../api/weekly-schedule.md#v31-프리셋-이름-변경)를 따른다.

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `weekly_schedule_presets_pkey` | `CREATE UNIQUE INDEX weekly_schedule_presets_pkey ON public.weekly_schedule_presets USING btree (user_id, data_generation, id)` | `weekly_schedule_presets_pkey` |
| `weekly_schedule_presets_user_id_data_generation_display_ord_key` | `CREATE UNIQUE INDEX weekly_schedule_presets_user_id_data_generation_display_ord_key ON public.weekly_schedule_presets USING btree (user_id, data_generation, display_order)` | `weekly_schedule_presets_user_id_data_generation_display_ord_key` |

### `weekly_schedule_state`

사용자·세대별 현재 활성 프리셋과 순환 설정, 버전 및 다음 순환 시각. 프리셋을 소유자 범위로 참조한다.

근거 [V30](../../src/main/resources/db/migration/V30__create_weekly_schedule_presets.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `user_id` | `uuid` | NO | — | PK, FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | PK, FK | 소유 계정의 운동 데이터 세대. |
| `active_preset_id` | `uuid` | NO | — | FK | 현재 활성 프리셋 ID. |
| `auto_enabled` | `boolean` | NO | `false` | — | 자동 순환 설정. |
| `state_version` | `bigint` | NO | `0` | — | 사용자 주간 일정 구성 버전. |
| `next_rotation_at` | `timestamp with time zone` | YES | — | — | 다음 순환의 현지 기준을 저장한 절대 시각. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `weekly_schedule_state_pkey` | `PRIMARY KEY (user_id, data_generation)` |
| FK | `weekly_schedule_state_user_id_data_generation_active_prese_fkey` | `FOREIGN KEY (user_id, data_generation, active_preset_id) REFERENCES weekly_schedule_presets(user_id, data_generation, id)` · 삭제 `NO ACTION` |
| CHECK | `weekly_schedule_state_check` | `CHECK ((auto_enabled OR (next_rotation_at IS NULL)))` |
| CHECK | `weekly_schedule_state_state_version_check` | `CHECK ((state_version >= 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `weekly_schedule_state_due` | `CREATE INDEX weekly_schedule_state_due ON public.weekly_schedule_state USING btree (next_rotation_at, user_id, data_generation) WHERE (next_rotation_at IS NOT NULL)` | — |
| `weekly_schedule_state_pkey` | `CREATE UNIQUE INDEX weekly_schedule_state_pkey ON public.weekly_schedule_state USING btree (user_id, data_generation)` | `weekly_schedule_state_pkey` |

### `weekly_schedule_versions`

실제로 적용된 주간 일정의 버전별 이력. V30의 프리셋 편집 상태와 별개로 보존한다.

근거 [V19](../../src/main/resources/db/migration/V19__add_weekly_schedule_and_routine_plan_history.sql) · [V20](../../src/main/resources/db/migration/V20__allow_immediate_first_weekly_schedule.sql) · [V24](../../src/main/resources/db/migration/V24__immediate_weekly_schedule_and_daily_review.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `user_id` | `uuid` | NO | — | PK, FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | PK, FK | 소유 계정의 운동 데이터 세대. |
| `version` | `bigint` | NO | — | PK | 행 또는 이력의 버전. |
| `recorded_at` | `timestamp with time zone` | NO | — | — | 서버에 근거가 기록된 시각. |
| `effective_from` | `date` | NO | — | — | 일정이 적용되는 첫 현지 날짜. |
| `time_zone` | `text` | NO | — | — | 해당 시점의 현지 시간대 ID. |
| `assignments` | `jsonb` | NO | — | — | 요일과 루틴 ID 배정 배열(JSON). |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `weekly_schedule_versions_pkey` | `PRIMARY KEY (user_id, data_generation, version)` |
| FK | `weekly_schedule_versions_user_id_data_generation_fkey` | `FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)` · 삭제 `NO ACTION` |
| CHECK | `weekly_schedule_versions_assignments_check` | `CHECK (((jsonb_typeof(assignments) = 'array'::text) AND (jsonb_array_length(assignments) <= 7)))` |
| CHECK | `weekly_schedule_versions_data_generation_check` | `CHECK ((data_generation >= 0))` |
| CHECK | `weekly_schedule_versions_time_zone_check` | `CHECK ((time_zone !~ '^[[:space:]]*$'::text))` |
| CHECK | `weekly_schedule_versions_version_check` | `CHECK ((version > 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `weekly_schedule_effective` | `CREATE INDEX weekly_schedule_effective ON public.weekly_schedule_versions USING btree (user_id, data_generation, effective_from, version DESC)` | — |
| `weekly_schedule_recorded` | `CREATE INDEX weekly_schedule_recorded ON public.weekly_schedule_versions USING btree (user_id, data_generation, recorded_at, version DESC)` | — |
| `weekly_schedule_versions_pkey` | `CREATE UNIQUE INDEX weekly_schedule_versions_pkey ON public.weekly_schedule_versions USING btree (user_id, data_generation, version)` | `weekly_schedule_versions_pkey` |

| 트리거 | 정의 |
| --- | --- |
| `weekly_schedule_versions_immutable` | `CREATE TRIGGER weekly_schedule_versions_immutable BEFORE UPDATE ON public.weekly_schedule_versions FOR EACH ROW EXECUTE FUNCTION preserve_weekly_planning_history()` |

### `workout_calendar_control`

일별 판단의 기준 일정 버전, 현지 시간대와 검토 구간을 관리한다.

근거 [V24](../../src/main/resources/db/migration/V24__immediate_weekly_schedule_and_daily_review.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `user_id` | `uuid` | NO | — | PK, FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | PK, FK | 소유 계정의 운동 데이터 세대. |
| `time_zone` | `text` | NO | — | — | 해당 시점의 현지 시간대 ID. |
| `baseline_version` | `bigint` | NO | — | FK | 일별 검토의 기준 일정 버전. |
| `review_from_date` | `date` | NO | — | — | 일별 판단이 적용되는 시작 날짜. |
| `reviewed_through` | `date` | NO | — | — | 순차 검토를 진행한 마지막 날짜. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `workout_calendar_control_pkey` | `PRIMARY KEY (user_id, data_generation)` |
| FK | `workout_calendar_control_user_id_data_generation_baseline__fkey` | `FOREIGN KEY (user_id, data_generation, baseline_version) REFERENCES weekly_schedule_versions(user_id, data_generation, version) DEFERRABLE INITIALLY DEFERRED` · 삭제 `NO ACTION` |
| FK | `workout_calendar_control_user_id_data_generation_fkey` | `FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)` · 삭제 `NO ACTION` |
| CHECK | `workout_calendar_control_check` | `CHECK ((reviewed_through >= (review_from_date - 1)))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `workout_calendar_control_pkey` | `CREATE UNIQUE INDEX workout_calendar_control_pkey ON public.workout_calendar_control USING btree (user_id, data_generation)` | `workout_calendar_control_pkey` |

### `daily_training_assessments`

예정일의 최초 START·MISS 근거와 현재 재검토 상태. 최초 근거와 상태 갱신을 구분한다.

근거 [V24](../../src/main/resources/db/migration/V24__immediate_weekly_schedule_and_daily_review.sql) · [V28](../../src/main/resources/db/migration/V28__enable_deload_sessions_and_plan_management.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `user_id` | `uuid` | NO | — | PK, FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | PK, FK | 소유 계정의 운동 데이터 세대. |
| `workout_date` | `date` | NO | — | PK | 운동 또는 예정일의 현지 날짜. |
| `first_session_id` | `uuid` | YES | — | FK | 예정일의 첫 START 세션 ID. |
| `first_missed_at` | `timestamp with time zone` | YES | — | — | 최초 MISS를 기록한 시각. |
| `missed_schedule_version` | `bigint` | YES | — | FK | 최초 MISS 당시 적용 일정 버전. |
| `missed_routine_id` | `uuid` | YES | — | FK | 최초 MISS 당시 예정 루틴 ID. |
| `missed_plan_version` | `bigint` | YES | — | — | 최초 MISS 당시 루틴 계획 버전. |
| `state` | `text` | NO | `'UNKNOWN'::text` | — | 처리 또는 판단 상태. |
| `revision` | `bigint` | NO | `0` | — | 저장된 판단의 개정 번호. |
| `needs_recheck` | `boolean` | NO | `true` | — | 후속 재검토 필요 여부. |
| `next_check_at` | `timestamp with time zone` | YES | — | — | 다음 일별 판단을 검토할 시각. |
| `reviewed_at` | `timestamp with time zone` | YES | — | — | 마지막 일별 검토 시각. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `daily_training_assessments_pkey` | `PRIMARY KEY (user_id, data_generation, workout_date)` |
| FK | `daily_training_assessments_user_id_data_generation_first_s_fkey` | `FOREIGN KEY (user_id, data_generation, first_session_id) REFERENCES workout_sessions(user_id, data_generation, id)` · 삭제 `NO ACTION` |
| FK | `daily_training_assessments_user_id_data_generation_fkey` | `FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)` · 삭제 `NO ACTION` |
| FK | `daily_training_assessments_user_id_data_generation_missed__fkey` | `FOREIGN KEY (user_id, data_generation, missed_schedule_version) REFERENCES weekly_schedule_versions(user_id, data_generation, version)` · 삭제 `NO ACTION` |
| FK | `daily_training_assessments_user_id_data_generation_missed_fkey1` | `FOREIGN KEY (user_id, data_generation, missed_routine_id) REFERENCES routines(user_id, data_generation, id)` · 삭제 `NO ACTION` |
| CHECK | `daily_training_assessments_check` | `CHECK (((first_missed_at IS NULL) OR ((missed_schedule_version IS NOT NULL) AND (missed_routine_id IS NOT NULL))))` |
| CHECK | `daily_training_assessments_revision_check` | `CHECK ((revision >= 0))` |
| CHECK | `daily_training_assessments_state_check` | `CHECK ((state = ANY (ARRAY['UNKNOWN'::text, 'PENDING_ACTIVE'::text, 'PERFORMED'::text, 'MISSED'::text, 'EMPTY'::text])))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `daily_training_assessments_pkey` | `CREATE UNIQUE INDEX daily_training_assessments_pkey ON public.daily_training_assessments USING btree (user_id, data_generation, workout_date)` | `daily_training_assessments_pkey` |
| `daily_training_recheck` | `CREATE INDEX daily_training_recheck ON public.daily_training_assessments USING btree (user_id, data_generation, workout_date) WHERE needs_recheck` | — |

| 트리거 | 정의 |
| --- | --- |
| `daily_training_origin_immutable` | `CREATE TRIGGER daily_training_origin_immutable BEFORE UPDATE ON public.daily_training_assessments FOR EACH ROW EXECUTE FUNCTION preserve_daily_training_origin()` |

## 5. 운동 세션·수행·정정·성장

세션 헤더와 확정 스냅샷, 수행 항목, 중량 승인 및 정정 감사를 저장한다. 일반과 디로딩 세션은 같은 테이블을 사용한다.

[`workout_sessions`](#workout_sessions) · [`session_exercises`](#session_exercises) · [`performed_sets`](#performed_sets) · [`cardio_results`](#cardio_results) · [`session_weight_approval_requests`](#session_weight_approval_requests) · [`progress_change_events`](#progress_change_events) · [`record_change_events`](#record_change_events)

### `workout_sessions`

세션의 원본 계획, 현재 적용 계획, 편집권, 스냅샷 재시도 근거 및 종료 상태. 원본 루틴·시작 일정·디로딩 임시 계획을 선택적으로 참조한다.

근거 [V12](../../src/main/resources/db/migration/V12__create_workout_sessions.sql) · [V13](../../src/main/resources/db/migration/V13__add_workout_snapshots.sql) · [V14](../../src/main/resources/db/migration/V14__add_workout_session_closure.sql) · [V15](../../src/main/resources/db/migration/V15__add_closed_workout_record_edits.sql) · [V17](../../src/main/resources/db/migration/V17__connect_standard_exercises_to_workouts.sql) · [V24](../../src/main/resources/db/migration/V24__immediate_weekly_schedule_and_daily_review.sql) · [V27](../../src/main/resources/db/migration/V27__create_deload_periods_and_temporary_plans.sql) · [V28](../../src/main/resources/db/migration/V28__enable_deload_sessions_and_plan_management.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | FK | 소유 계정의 운동 데이터 세대. |
| `source_routine_id` | `uuid` | YES | — | FK | 일반 세션의 원본 루틴 ID. |
| `status` | `text` | NO | `'ACTIVE'::text` | — | 행의 수명주기 상태. |
| `version` | `bigint` | NO | `0` | — | 행 또는 이력의 버전. |
| `started_at` | `timestamp with time zone` | NO | — | — | 실제 시작 시각. |
| `workout_date` | `date` | NO | — | — | 운동 또는 예정일의 현지 날짜. |
| `started_time_zone` | `text` | NO | — | — | START 당시 현지 날짜를 결정한 시간대. |
| `started_device_id` | `uuid` | NO | — | — | START를 보낸 기기 ID. |
| `editor_device_id` | `uuid` | NO | — | — | 현재 편집권을 가진 기기 ID. |
| `writer_epoch` | `bigint` | NO | `0` | — | 편집권 세대. |
| `local_revision` | `bigint` | NO | `0` | — | 현재 편집권에서 승인한 로컬 revision. |
| `initial_plan_snapshot` | `jsonb` | NO | — | — | START 시 확정한 최초 계획 JSON. |
| `last_workout_synced_at` | `timestamp with time zone` | NO | — | — | 마지막 운동 데이터 서버 동기화 시각. |
| `created_at` | `timestamp with time zone` | NO | — | — | 생성 시각. |
| `updated_at` | `timestamp with time zone` | NO | — | — | 마지막 갱신 시각. |
| `applied_plan_snapshot` | `jsonb` | NO | — | — | 현재 적용 또는 종료에 사용된 계획 JSON. |
| `last_snapshot_hash` | `text` | YES | — | — | 마지막 승인 스냅샷의 비교용 해시. |
| `last_snapshot_saved_at` | `timestamp with time zone` | YES | — | — | 마지막 승인 스냅샷 저장 시각. |
| `ended_at` | `timestamp with time zone` | YES | — | — | 운동을 종료했다고 제출한 시각. |
| `closed_at` | `timestamp with time zone` | YES | — | — | 서버가 FINISH 또는 AUTO_CLOSED를 확정한 시각. |
| `completion_assessment_snapshot` | `jsonb` | YES | — | — | 최초 FINISH의 평가 JSON. |
| `deleted_at` | `timestamp with time zone` | YES | — | — | 논리 삭제 시각. |
| `schedule_version_at_start` | `bigint` | YES | — | FK | START에 연결된 당시 일정 버전. |
| `deload_period_id` | `uuid` | YES | — | FK | 디로딩 세션이 속한 기간 ID. |
| `deload_plan_id` | `uuid` | YES | — | FK | 디로딩 세션이 참조한 임시 계획 ID. |
| `first_deload_activity_at` | `timestamp with time zone` | YES | — | — | 디로딩 세션의 최초 실제 활동 시각. |
| `abandoned_at` | `timestamp with time zone` | YES | — | — | 디로딩 세션을 포기 처리한 시각. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `workout_sessions_pkey` | `PRIMARY KEY (id)` |
| FK | `workout_deload_plan_scope` | `FOREIGN KEY (user_id, data_generation, deload_period_id, deload_plan_id) REFERENCES deload_temporary_plans(user_id, data_generation, period_id, id)` · 삭제 `NO ACTION` |
| FK | `workout_sessions_user_id_data_generation_fkey` | `FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)` · 삭제 `NO ACTION` |
| FK | `workout_sessions_user_id_data_generation_source_routine_id_fkey` | `FOREIGN KEY (user_id, data_generation, source_routine_id) REFERENCES routines(user_id, data_generation, id)` · 삭제 `NO ACTION` |
| FK | `workout_start_schedule_scope` | `FOREIGN KEY (user_id, data_generation, schedule_version_at_start) REFERENCES weekly_schedule_versions(user_id, data_generation, version)` · 삭제 `NO ACTION` |
| UNIQUE | `workout_sessions_user_id_data_generation_id_key` | `UNIQUE (user_id, data_generation, id)` |
| CHECK | `workout_abandoned_empty` | `CHECK (((abandoned_at IS NULL) OR ((deload_period_id IS NOT NULL) AND (first_deload_activity_at IS NULL) AND (deleted_at IS NOT NULL) AND (status <> 'ACTIVE'::text))))` |
| CHECK | `workout_deload_membership_pair` | `CHECK (((deload_period_id IS NULL) = (deload_plan_id IS NULL)))` |
| CHECK | `workout_sessions_abandoned_at_check` | `CHECK (((abandoned_at IS NULL) OR isfinite(abandoned_at)))` |
| CHECK | `workout_sessions_applied_plan_snapshot_check` | `CHECK (((jsonb_typeof(applied_plan_snapshot) = 'object'::text) AND COALESCE(((applied_plan_snapshot -> 'schemaVersion'::text) = '2'::jsonb), false)))` |
| CHECK | `workout_sessions_check` | `CHECK (((deleted_at IS NULL) OR (status = ANY (ARRAY['FINISHED'::text, 'AUTO_CLOSED'::text]))))` |
| CHECK | `workout_sessions_check1` | `CHECK (((first_deload_activity_at IS NULL) OR (deload_period_id IS NOT NULL)))` |
| CHECK | `workout_sessions_closure_check` | `CHECK ((((status = 'ACTIVE'::text) AND (ended_at IS NULL) AND (closed_at IS NULL) AND (completion_assessment_snapshot IS NULL)) OR ((status = 'FINISHED'::text) AND (ended_at IS NOT NULL) AND (ended_at >= started_at) AND (closed_at IS NOT NULL) AND (completion_assessment_snapshot IS NOT NULL) AND (jsonb_typeof(completion_assessment_snapshot) = 'object'::text) AND COALESCE(((completion_assessment_snapshot -> 'schemaVersion'::text) = '2'::jsonb), false)) OR ((status = 'AUTO_CLOSED'::text) AND (ended_at IS NULL) AND (closed_at IS NOT NULL) AND (completion_assessment_snapshot IS NULL))))` |
| CHECK | `workout_sessions_data_generation_check` | `CHECK ((data_generation >= 0))` |
| CHECK | `workout_sessions_first_deload_activity_at_check` | `CHECK (((first_deload_activity_at IS NULL) OR isfinite(first_deload_activity_at)))` |
| CHECK | `workout_sessions_initial_plan_snapshot_check` | `CHECK (((jsonb_typeof(initial_plan_snapshot) = 'object'::text) AND COALESCE(((initial_plan_snapshot -> 'schemaVersion'::text) = '2'::jsonb), false)))` |
| CHECK | `workout_sessions_last_snapshot_hash_check` | `CHECK ((last_snapshot_hash ~ '^[0-9a-f]{64}$'::text))` |
| CHECK | `workout_sessions_local_revision_check` | `CHECK ((local_revision >= 0))` |
| CHECK | `workout_sessions_status_check` | `CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'FINISHED'::text, 'AUTO_CLOSED'::text])))` |
| CHECK | `workout_sessions_version_check` | `CHECK ((version >= 0))` |
| CHECK | `workout_sessions_writer_epoch_check` | `CHECK ((writer_epoch >= 0))` |
| CHECK | `workout_source_kind` | `CHECK ((((deload_period_id IS NULL) AND (source_routine_id IS NOT NULL)) OR ((deload_period_id IS NOT NULL) AND (source_routine_id IS NULL) AND (schedule_version_at_start IS NULL))))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `workout_deload_activity` | `CREATE INDEX workout_deload_activity ON public.workout_sessions USING btree (user_id, data_generation, deload_period_id) WHERE (first_deload_activity_at IS NOT NULL)` | — |
| `workout_one_live_deload_plan` | `CREATE UNIQUE INDEX workout_one_live_deload_plan ON public.workout_sessions USING btree (deload_plan_id) WHERE ((deload_plan_id IS NOT NULL) AND (abandoned_at IS NULL))` | — |
| `workout_sessions_active_expiry` | `CREATE INDEX workout_sessions_active_expiry ON public.workout_sessions USING btree (last_workout_synced_at, id) WHERE (status = 'ACTIVE'::text)` | — |
| `workout_sessions_one_active_user` | `CREATE UNIQUE INDEX workout_sessions_one_active_user ON public.workout_sessions USING btree (user_id) WHERE (status = 'ACTIVE'::text)` | — |
| `workout_sessions_pkey` | `CREATE UNIQUE INDEX workout_sessions_pkey ON public.workout_sessions USING btree (id)` | `workout_sessions_pkey` |
| `workout_sessions_user_id_data_generation_id_key` | `CREATE UNIQUE INDEX workout_sessions_user_id_data_generation_id_key ON public.workout_sessions USING btree (user_id, data_generation, id)` | `workout_sessions_user_id_data_generation_id_key` |

| 트리거 | 정의 |
| --- | --- |
| `workout_deload_activity_immutable` | `CREATE TRIGGER workout_deload_activity_immutable BEFORE UPDATE ON public.workout_sessions FOR EACH ROW EXECUTE FUNCTION preserve_deload_activity()` |
| `workout_deload_membership_immutable` | `CREATE TRIGGER workout_deload_membership_immutable BEFORE UPDATE ON public.workout_sessions FOR EACH ROW EXECUTE FUNCTION preserve_deload_session_membership()` |
| `workout_schedule_origin_immutable` | `CREATE TRIGGER workout_schedule_origin_immutable BEFORE UPDATE ON public.workout_sessions FOR EACH ROW EXECUTE FUNCTION preserve_workout_schedule_origin()` |
| `workout_sessions_immutable_closure` | `CREATE TRIGGER workout_sessions_immutable_closure BEFORE UPDATE ON public.workout_sessions FOR EACH ROW EXECUTE FUNCTION preserve_workout_session_closure()` |
| `workout_sessions_immutable_start` | `CREATE TRIGGER workout_sessions_immutable_start BEFORE UPDATE ON public.workout_sessions FOR EACH ROW EXECUTE FUNCTION preserve_workout_session_initial_plan()` |
| `workout_training_dirty` | `CREATE TRIGGER workout_training_dirty AFTER UPDATE ON public.workout_sessions FOR EACH ROW EXECUTE FUNCTION mark_training_date_dirty()` |

### `session_exercises`

세션 안의 웨이트·유산소 항목 식별자와 당시 이름. 기존 미연결 유산소의 표준 운동 UUID는 NULL이다.

근거 [V13](../../src/main/resources/db/migration/V13__add_workout_snapshots.sql) · [V17](../../src/main/resources/db/migration/V17__connect_standard_exercises_to_workouts.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `session_id` | `uuid` | NO | — | FK | 소속 세션 ID. |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | FK | 소유 계정의 운동 데이터 세대. |
| `kind` | `text` | NO | — | — | 유형 구분자. |
| `standard_exercise_id` | `uuid` | YES | — | FK | 웨이트 또는 유산소의 표준 운동 UUID. |
| `cardio_activity_id` | `uuid` | YES | — | FK | 신규 연결 유산소의 기존 기록 설정 ID. |
| `cardio_kind` | `text` | YES | — | — | 새 유산소 선택 당시의 측정 종류. |
| `name_at_addition` | `text` | YES | — | — | 세션에 추가할 때 보존한 이름. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `session_exercises_pkey` | `PRIMARY KEY (id)` |
| FK | `session_exercise_standard_exercise_fk` | `FOREIGN KEY (standard_exercise_id) REFERENCES standard_exercise(id)` · 삭제 `NO ACTION` |
| FK | `session_exercises_user_id_data_generation_session_id_fkey` | `FOREIGN KEY (user_id, data_generation, session_id) REFERENCES workout_sessions(user_id, data_generation, id)` · 삭제 `NO ACTION` |
| UNIQUE | `session_exercises_session_id_id_kind_key` | `UNIQUE (session_id, id, kind)` |
| CHECK | `session_exercises_check` | WEIGHT는 표준 ID 필수, 유산소 설정·종류는 NULL. CARDIO는 기존 미연결 형태이거나 표준 UUID·설정 ID·측정 종류·이름을 함께 보존한다. |
| CHECK | `session_exercises_kind_check` | `CHECK ((kind = ANY (ARRAY['WEIGHT'::text, 'CARDIO'::text])))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `session_exercises_pkey` | `CREATE UNIQUE INDEX session_exercises_pkey ON public.session_exercises USING btree (id)` | `session_exercises_pkey` |
| `session_exercises_session_id_id_kind_key` | `CREATE UNIQUE INDEX session_exercises_session_id_id_kind_key ON public.session_exercises USING btree (session_id, id, kind)` | `session_exercises_session_id_id_kind_key` |

### `performed_sets`

완료한 근력 세트와 당시 중량·승인·장비 근거. 삭제 시각과 선택 맨몸 추가 부하를 보존한다.

근거 [V13](../../src/main/resources/db/migration/V13__add_workout_snapshots.sql) · [V15](../../src/main/resources/db/migration/V15__add_closed_workout_record_edits.sql) · [V17](../../src/main/resources/db/migration/V17__connect_standard_exercises_to_workouts.sql) · [V22](../../src/main/resources/db/migration/V22__add_bodyweight_additional_load.sql) · [V24](../../src/main/resources/db/migration/V24__immediate_weekly_schedule_and_daily_review.sql) · [V28](../../src/main/resources/db/migration/V28__enable_deload_sessions_and_plan_management.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `session_id` | `uuid` | NO | — | FK | 소속 세션 ID. |
| `session_exercise_id` | `uuid` | NO | — | FK | 소속 세션의 근력 항목 ID. |
| `kind` | `text` | NO | `'WEIGHT'::text` | FK | 유형 구분자. |
| `ordinal` | `integer` | NO | — | — | 항목 안의 세트 순번. |
| `weight` | `numeric(20,6)` | NO | — | — | 실제 수행 중량(kg). |
| `reps` | `integer` | NO | — | — | 실제 수행 반복 수. |
| `completed_at` | `timestamp with time zone` | NO | — | — | 세트 완료 시각. |
| `context_kind` | `text` | NO | — | — | 완료 시 선택한 승인 중량 문맥 종류. |
| `approval_request_id` | `uuid` | YES | — | FK | 연결된 중량 승인 요청 ID. |
| `approval_state_at_recording` | `text` | NO | — | — | 완료 기록 시점의 승인 상태. |
| `approved_weight_at_recording` | `numeric(20,6)` | YES | — | — | 완료 기록 시점의 승인 중량(kg). |
| `deleted_at` | `timestamp with time zone` | YES | — | — | 논리 삭제 시각. |
| `manufacturer_id` | `uuid` | YES | — | FK | 기록한 제조사 ID. |
| `load_type` | `text` | YES | — | — | 장비의 부하 방식. |
| `bodyweight_load` | `jsonb` | YES | — | — | 선택 맨몸 추가 부하 정보(JSON). |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `performed_sets_pkey` | `PRIMARY KEY (id)` |
| FK | `performed_sets_manufacturer_id_fkey` | `FOREIGN KEY (manufacturer_id) REFERENCES manufacturer(id)` · 삭제 `NO ACTION` |
| FK | `performed_sets_session_id_session_exercise_id_approval_req_fkey` | `FOREIGN KEY (session_id, session_exercise_id, approval_request_id) REFERENCES session_weight_approval_requests(session_id, session_exercise_id, id)` · 삭제 `NO ACTION` |
| FK | `performed_sets_session_id_session_exercise_id_kind_fkey` | `FOREIGN KEY (session_id, session_exercise_id, kind) REFERENCES session_exercises(session_id, id, kind)` · 삭제 `NO ACTION` |
| CHECK | `performed_sets_approval_state_at_recording_check` | `CHECK ((approval_state_at_recording = ANY (ARRAY['INITIAL_APPROVED'::text, 'APPLIED'::text, 'PENDING'::text, 'NOT_APPLIED'::text, 'TODAY_ONLY'::text, 'UNVERIFIED'::text])))` |
| CHECK | `performed_sets_approved_weight_at_recording_check` | `CHECK (((approved_weight_at_recording >= (0)::numeric) AND (approved_weight_at_recording < ('100000000000000'::bigint)::numeric)))` |
| CHECK | `performed_sets_bodyweight_load_check` | `CHECK (((bodyweight_load IS NULL) OR (((load_type = 'BODYWEIGHT'::text) AND (jsonb_typeof(bodyweight_load) = 'object'::text) AND (bodyweight_load ? 'additionalWeightKg'::text) AND ((bodyweight_load - 'additionalWeightKg'::text) = '{}'::jsonb) AND (((bodyweight_load -> 'additionalWeightKg'::text) = 'null'::jsonb) OR ((jsonb_typeof((bodyweight_load -> 'additionalWeightKg'::text)) = 'string'::text) AND ((bodyweight_load ->> 'additionalWeightKg'::text) ~ '^(0\|[1-9][0-9]{0,13})(\.[0-9]{0,5}[1-9])?$'::text)))) IS TRUE)))` |
| CHECK | `performed_sets_check` | `CHECK (((context_kind = 'APPROVAL_REQUEST'::text) = (approval_request_id IS NOT NULL)))` |
| CHECK | `performed_sets_check1` | `CHECK ((((context_kind = 'INITIAL_APPROVED'::text) AND (approval_state_at_recording = 'INITIAL_APPROVED'::text) AND (approved_weight_at_recording IS NOT NULL)) OR ((context_kind = 'TODAY_ONLY'::text) AND (approval_state_at_recording = 'TODAY_ONLY'::text) AND (approved_weight_at_recording IS NULL)) OR ((context_kind = 'UNVERIFIED'::text) AND (approval_state_at_recording = 'UNVERIFIED'::text) AND (approved_weight_at_recording IS NULL)) OR ((context_kind = 'APPROVAL_REQUEST'::text) AND (approval_state_at_recording = ANY (ARRAY['APPLIED'::text, 'PENDING'::text, 'NOT_APPLIED'::text])) AND ((approval_state_at_recording = 'APPLIED'::text) = (approved_weight_at_recording IS NOT NULL)))))` |
| CHECK | `performed_sets_context_kind_check` | `CHECK ((context_kind = ANY (ARRAY['INITIAL_APPROVED'::text, 'APPROVAL_REQUEST'::text, 'TODAY_ONLY'::text, 'UNVERIFIED'::text])))` |
| CHECK | `performed_sets_kind_check` | `CHECK ((kind = 'WEIGHT'::text))` |
| CHECK | `performed_sets_load_type_check` | `CHECK (((load_type IS NULL) OR (load_type = ANY (ARRAY['PLATE'::text, 'STACK'::text, 'DUMBBELL'::text, 'BODYWEIGHT'::text]))))` |
| CHECK | `performed_sets_ordinal_check` | `CHECK ((ordinal > 0))` |
| CHECK | `performed_sets_reps_check` | `CHECK ((reps > 0))` |
| CHECK | `performed_sets_weight_check` | `CHECK (((weight >= (0)::numeric) AND (weight < ('100000000000000'::bigint)::numeric)))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `performed_sets_active_ordinal` | `CREATE UNIQUE INDEX performed_sets_active_ordinal ON public.performed_sets USING btree (session_exercise_id, ordinal) WHERE (deleted_at IS NULL)` | — |
| `performed_sets_pkey` | `CREATE UNIQUE INDEX performed_sets_pkey ON public.performed_sets USING btree (id)` | `performed_sets_pkey` |
| `performed_sets_session` | `CREATE INDEX performed_sets_session ON public.performed_sets USING btree (session_id)` | — |

| 트리거 | 정의 |
| --- | --- |
| `performed_deload_activity` | `CREATE TRIGGER performed_deload_activity AFTER INSERT OR UPDATE ON public.performed_sets FOR EACH ROW EXECUTE FUNCTION remember_deload_activity()` |
| `performed_sets_immutable_equipment` | `CREATE TRIGGER performed_sets_immutable_equipment BEFORE UPDATE ON public.performed_sets FOR EACH ROW EXECUTE FUNCTION preserve_performed_set_equipment()` |
| `sets_training_dirty` | `CREATE TRIGGER sets_training_dirty AFTER INSERT OR UPDATE ON public.performed_sets FOR EACH ROW EXECUTE FUNCTION mark_training_date_dirty()` |

### `cardio_results`

유산소 실제 수행, 시간 및 선택 측정값. 기존 기록은 측정 출처를 추정해서 채우지 않는다.

근거 [V13](../../src/main/resources/db/migration/V13__add_workout_snapshots.sql) · [V21](../../src/main/resources/db/migration/V21__add_actual_cardio_measurements.sql) · [V24](../../src/main/resources/db/migration/V24__immediate_weekly_schedule_and_daily_review.sql) · [V28](../../src/main/resources/db/migration/V28__enable_deload_sessions_and_plan_management.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `session_id` | `uuid` | NO | — | FK | 소속 세션 ID. |
| `session_activity_id` | `uuid` | NO | — | FK | 소속 세션의 유산소 항목 ID. |
| `kind` | `text` | NO | `'CARDIO'::text` | FK | 유형 구분자. |
| `status` | `text` | NO | — | — | 행의 수명주기 상태. |
| `started_at` | `timestamp with time zone` | YES | — | — | 실제 시작 시각. |
| `occurred_at` | `timestamp with time zone` | NO | — | — | 실제 사건 시각. |
| `timer_ms` | `bigint` | YES | — | — | 타이머로 잰 시간(밀리초). |
| `active_ms` | `bigint` | YES | — | — | 실제 활동 시간(밀리초). |
| `corrected` | `boolean` | YES | — | — | 활동 시간을 수정했는지 나타내는 값. |
| `distance` | `numeric(20,6)` | YES | — | — | 저장된 실제 거리(km). |
| `average_bpm` | `integer` | YES | — | — | 평균 심박수(bpm). |
| `deleted_at` | `timestamp with time zone` | YES | — | — | 논리 삭제 시각. |
| `measurement` | `jsonb` | YES | — | — | 선택 실제 속도 및 거리 출처 정보(JSON). |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `cardio_results_pkey` | `PRIMARY KEY (id)` |
| FK | `cardio_results_session_id_session_activity_id_kind_fkey` | `FOREIGN KEY (session_id, session_activity_id, kind) REFERENCES session_exercises(session_id, id, kind)` · 삭제 `NO ACTION` |
| CHECK | `cardio_measurement_shape` | `CHECK (((measurement IS NULL) OR ((status = 'COMPLETED'::text) AND (jsonb_typeof(measurement) = 'object'::text) AND (measurement ?& ARRAY['actualSpeedKmh'::text, 'distanceSource'::text]) AND ((measurement - ARRAY['actualSpeedKmh'::text, 'distanceSource'::text]) = '{}'::jsonb) AND (((measurement -> 'actualSpeedKmh'::text) = 'null'::jsonb) OR ((jsonb_typeof((measurement -> 'actualSpeedKmh'::text)) = 'string'::text) AND ((measurement ->> 'actualSpeedKmh'::text) ~ '^(0\|[1-9][0-9]{0,13})(\.[0-9]{0,5}[1-9])?$'::text))) AND (((measurement -> 'distanceSource'::text) = 'null'::jsonb) OR ((measurement ->> 'distanceSource'::text) = ANY (ARRAY['MANUAL'::text, 'CALCULATED'::text]))) AND (((measurement ->> 'distanceSource'::text) IS NULL) OR (distance IS NOT NULL)))))` |
| CHECK | `cardio_results_active_ms_check` | `CHECK (((active_ms >= 1) AND (active_ms <= 86400000)))` |
| CHECK | `cardio_results_average_bpm_check` | `CHECK (((average_bpm >= 20) AND (average_bpm <= 300)))` |
| CHECK | `cardio_results_check` | `CHECK (((started_at IS NULL) OR (occurred_at >= started_at)))` |
| CHECK | `cardio_results_check1` | `CHECK ((((status = 'COMPLETED'::text) AND (started_at IS NOT NULL) AND (timer_ms IS NOT NULL) AND (active_ms IS NOT NULL) AND (corrected IS NOT NULL) AND (corrected OR (active_ms = timer_ms))) OR ((status = 'SKIPPED'::text) AND (timer_ms IS NULL) AND (active_ms IS NULL) AND (corrected IS NULL) AND (distance IS NULL) AND (average_bpm IS NULL))))` |
| CHECK | `cardio_results_distance_check` | `CHECK ( CASE WHEN ((measurement ->> 'distanceSource'::text) = 'CALCULATED'::text) THEN (((measurement ->> 'actualSpeedKmh'::text) IS NOT NULL) AND (distance IS NOT NULL) AND (distance >= (0)::numeric) AND (distance < ('100000000000000'::bigint)::numeric) AND (distance = round(((((measurement ->> 'actualSpeedKmh'::text))::numeric * (active_ms)::numeric) / (3600000)::numeric), 6))) ELSE ((distance >= 0.001) AND (distance <= (1000)::numeric)) END)` |
| CHECK | `cardio_results_kind_check` | `CHECK ((kind = 'CARDIO'::text))` |
| CHECK | `cardio_results_status_check` | `CHECK ((status = ANY (ARRAY['COMPLETED'::text, 'SKIPPED'::text])))` |
| CHECK | `cardio_results_timer_ms_check` | `CHECK (((timer_ms >= 0) AND (timer_ms <= '9007199254740991'::bigint)))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `cardio_results_active_activity` | `CREATE UNIQUE INDEX cardio_results_active_activity ON public.cardio_results USING btree (session_activity_id) WHERE (deleted_at IS NULL)` | — |
| `cardio_results_pkey` | `CREATE UNIQUE INDEX cardio_results_pkey ON public.cardio_results USING btree (id)` | `cardio_results_pkey` |
| `cardio_results_session` | `CREATE INDEX cardio_results_session ON public.cardio_results USING btree (session_id)` | — |

| 트리거 | 정의 |
| --- | --- |
| `cardio_deload_activity` | `CREATE TRIGGER cardio_deload_activity AFTER INSERT OR UPDATE ON public.cardio_results FOR EACH ROW EXECUTE FUNCTION remember_deload_activity()` |
| `cardio_training_dirty` | `CREATE TRIGGER cardio_training_dirty AFTER INSERT OR UPDATE ON public.cardio_results FOR EACH ROW EXECUTE FUNCTION mark_training_date_dirty()` |

### `session_weight_approval_requests`

세션 항목별 명시 중량 승인 요청과 처리 결과. 이전 요청과 해결 요청은 같은 세션·항목 안에서 참조한다.

근거 [V13](../../src/main/resources/db/migration/V13__add_workout_snapshots.sql) · [V15](../../src/main/resources/db/migration/V15__add_closed_workout_record_edits.sql) · [V28](../../src/main/resources/db/migration/V28__enable_deload_sessions_and_plan_management.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `session_id` | `uuid` | NO | — | FK | 소속 세션 ID. |
| `session_exercise_id` | `uuid` | NO | — | FK | 소속 세션의 근력 항목 ID. |
| `kind` | `text` | NO | `'WEIGHT'::text` | FK | 유형 구분자. |
| `sequence` | `bigint` | NO | — | — | 세션 운동 안의 요청 순번. |
| `previous_request_id` | `uuid` | YES | — | FK | 선행 승인 요청 ID. |
| `expected_routine_version` | `bigint` | NO | — | — | 제출이 기대한 루틴 버전. |
| `previous_weight` | `numeric(20,6)` | NO | — | — | 변경 전 중량(kg). |
| `next_weight` | `numeric(20,6)` | NO | — | — | 변경 후 중량(kg). |
| `occurred_at` | `timestamp with time zone` | NO | — | — | 실제 사건 시각. |
| `status` | `text` | NO | — | — | 행의 수명주기 상태. |
| `reason` | `text` | YES | — | — | 처리 사유. |
| `routine_version_before` | `bigint` | YES | — | — | 승인 처리 직전 루틴 버전. |
| `routine_version_after` | `bigint` | YES | — | — | 승인 처리 직후 루틴 버전. |
| `resolved_at` | `timestamp with time zone` | YES | — | — | 승인 요청을 해결한 시각. |
| `resolved_by_request_id` | `uuid` | YES | — | FK | 요청을 해결한 후속 요청 ID. |
| `created_after_closure` | `boolean` | NO | `false` | — | 세션 종료 후에 만들어진 요청인지 나타내는 값. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `session_weight_approval_requests_pkey` | `PRIMARY KEY (id)` |
| FK | `session_weight_approval_requ_session_id_session_exercise__fkey1` | `FOREIGN KEY (session_id, session_exercise_id, previous_request_id) REFERENCES session_weight_approval_requests(session_id, session_exercise_id, id)` · 삭제 `NO ACTION` |
| FK | `session_weight_approval_requ_session_id_session_exercise__fkey2` | `FOREIGN KEY (session_id, session_exercise_id, resolved_by_request_id) REFERENCES session_weight_approval_requests(session_id, session_exercise_id, id)` · 삭제 `NO ACTION` |
| FK | `session_weight_approval_reque_session_id_session_exercise__fkey` | `FOREIGN KEY (session_id, session_exercise_id, kind) REFERENCES session_exercises(session_id, id, kind)` · 삭제 `NO ACTION` |
| UNIQUE | `session_weight_approval_reque_session_id_session_exercise__key1` | `UNIQUE (session_id, session_exercise_id, sequence)` |
| UNIQUE | `session_weight_approval_reque_session_id_session_exercise_i_key` | `UNIQUE (session_id, session_exercise_id, id)` |
| CHECK | `session_weight_approval_requests_check` | `CHECK ((((status = 'APPLIED'::text) AND (reason IS NULL) AND (routine_version_before IS NOT NULL) AND (routine_version_after IS NOT NULL) AND (routine_version_before >= 0) AND (routine_version_after >= routine_version_before) AND (resolved_at IS NULL) AND (resolved_by_request_id IS NULL)) OR ((status = 'PENDING'::text) AND (reason IS NOT NULL) AND (routine_version_before IS NULL) AND (routine_version_after IS NULL) AND (resolved_at IS NULL) AND (resolved_by_request_id IS NULL)) OR ((status = 'NOT_APPLIED'::text) AND (reason IS NOT NULL) AND (reason = ANY (ARRAY['KEPT_CURRENT'::text, 'REAPPROVED'::text])) AND (routine_version_before IS NULL) AND (routine_version_after IS NULL) AND (resolved_at IS NOT NULL) AND ((reason = 'REAPPROVED'::text) = (resolved_by_request_id IS NOT NULL)))))` |
| CHECK | `session_weight_approval_requests_expected_routine_version_check` | `CHECK ((expected_routine_version >= 0))` |
| CHECK | `session_weight_approval_requests_kind_check` | `CHECK ((kind = 'WEIGHT'::text))` |
| CHECK | `session_weight_approval_requests_next_weight_check` | `CHECK (((next_weight >= (0)::numeric) AND (next_weight < ('100000000000000'::bigint)::numeric)))` |
| CHECK | `session_weight_approval_requests_previous_weight_check` | `CHECK (((previous_weight >= (0)::numeric) AND (previous_weight < ('100000000000000'::bigint)::numeric)))` |
| CHECK | `session_weight_approval_requests_sequence_check` | `CHECK ((sequence > 0))` |
| CHECK | `session_weight_approval_requests_status_check` | `CHECK ((status = ANY (ARRAY['APPLIED'::text, 'PENDING'::text, 'NOT_APPLIED'::text])))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `session_weight_approval_reque_session_id_session_exercise__key1` | `CREATE UNIQUE INDEX session_weight_approval_reque_session_id_session_exercise__key1 ON public.session_weight_approval_requests USING btree (session_id, session_exercise_id, sequence)` | `session_weight_approval_reque_session_id_session_exercise__key1` |
| `session_weight_approval_reque_session_id_session_exercise_i_key` | `CREATE UNIQUE INDEX session_weight_approval_reque_session_id_session_exercise_i_key ON public.session_weight_approval_requests USING btree (session_id, session_exercise_id, id)` | `session_weight_approval_reque_session_id_session_exercise_i_key` |
| `session_weight_approval_requests_pkey` | `CREATE UNIQUE INDEX session_weight_approval_requests_pkey ON public.session_weight_approval_requests USING btree (id)` | `session_weight_approval_requests_pkey` |

| 트리거 | 정의 |
| --- | --- |
| `deload_approval_guard` | `CREATE TRIGGER deload_approval_guard BEFORE INSERT OR UPDATE ON public.session_weight_approval_requests FOR EACH ROW EXECUTE FUNCTION reject_deload_approval()` |

### `progress_change_events`

루틴 배치의 공식 승인 중량 변경 사건. 배치 소유권과 승인 출처를 보존한다.

근거 [V11](../../src/main/resources/db/migration/V11__create_routine_management.sql) · [V13](../../src/main/resources/db/migration/V13__add_workout_snapshots.sql) · [V28](../../src/main/resources/db/migration/V28__enable_deload_sessions_and_plan_management.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | FK | 소유 계정의 운동 데이터 세대. |
| `routine_id` | `uuid` | NO | — | FK | 소속 또는 연결 루틴 ID. |
| `placement_id` | `uuid` | NO | — | FK | 변경된 근력 루틴 배치 ID. |
| `routine_name` | `text` | NO | — | — | 변경 사건 당시 루틴 이름. |
| `exercise_name` | `text` | NO | — | — | 변경 사건 당시 운동 이름. |
| `previous_weight` | `numeric(20,6)` | NO | — | — | 변경 전 중량(kg). |
| `next_weight` | `numeric(20,6)` | NO | — | — | 변경 후 중량(kg). |
| `occurred_at` | `timestamp with time zone` | NO | — | — | 실제 사건 시각. |
| `recorded_at` | `timestamp with time zone` | NO | — | — | 서버에 근거가 기록된 시각. |
| `source` | `text` | NO | — | — | 중량 변경 사건의 출처. |
| `session_id` | `uuid` | YES | — | FK | 소속 세션 ID. |
| `session_exercise_id` | `uuid` | YES | — | FK | 소속 세션의 근력 항목 ID. |
| `approval_request_id` | `uuid` | YES | — | FK, UK | 연결된 중량 승인 요청 ID. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `progress_change_events_pkey` | `PRIMARY KEY (id)` |
| FK | `progress_change_events_session_id_session_exercise_id_appr_fkey` | `FOREIGN KEY (session_id, session_exercise_id, approval_request_id) REFERENCES session_weight_approval_requests(session_id, session_exercise_id, id)` · 삭제 `NO ACTION` |
| FK | `progress_change_events_user_id_data_generation_routine_id__fkey` | `FOREIGN KEY (user_id, data_generation, routine_id, placement_id) REFERENCES routine_weight_placements(user_id, data_generation, routine_id, id)` · 삭제 `NO ACTION` |
| UNIQUE | `progress_change_events_approval_request_id_key` | `UNIQUE (approval_request_id)` |
| CHECK | `progress_change_events_check` | `CHECK ((previous_weight <> next_weight))` |
| CHECK | `progress_change_events_check1` | `CHECK ((((source = 'ROUTINE_EDITOR'::text) AND (session_id IS NULL) AND (session_exercise_id IS NULL) AND (approval_request_id IS NULL)) OR ((source = 'WORKOUT'::text) AND (session_id IS NOT NULL) AND (session_exercise_id IS NOT NULL) AND (approval_request_id IS NOT NULL))))` |
| CHECK | `progress_change_events_next_weight_check` | `CHECK (((next_weight >= (0)::numeric) AND (next_weight < ('100000000000000'::bigint)::numeric)))` |
| CHECK | `progress_change_events_previous_weight_check` | `CHECK (((previous_weight >= (0)::numeric) AND (previous_weight < ('100000000000000'::bigint)::numeric)))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `progress_change_events_approval_request_id_key` | `CREATE UNIQUE INDEX progress_change_events_approval_request_id_key ON public.progress_change_events USING btree (approval_request_id)` | `progress_change_events_approval_request_id_key` |
| `progress_change_events_pkey` | `CREATE UNIQUE INDEX progress_change_events_pkey ON public.progress_change_events USING btree (id)` | `progress_change_events_pkey` |
| `progress_change_events_session` | `CREATE INDEX progress_change_events_session ON public.progress_change_events USING btree (session_id) WHERE (session_id IS NOT NULL)` | — |

| 트리거 | 정의 |
| --- | --- |
| `deload_growth_guard` | `CREATE TRIGGER deload_growth_guard BEFORE INSERT OR UPDATE ON public.progress_change_events FOR EACH ROW EXECUTE FUNCTION reject_deload_approval()` |

### `record_change_events`

수행 기록의 정정·삭제 이력과 변경 전후 JSON. 세션 FK와 대상 종류·UUID를 함께 저장한다.

근거 [V13](../../src/main/resources/db/migration/V13__add_workout_snapshots.sql) · [V15](../../src/main/resources/db/migration/V15__add_closed_workout_record_edits.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `session_id` | `uuid` | NO | — | FK | 소속 세션 ID. |
| `record_kind` | `text` | NO | — | — | 정정 또는 삭제 대상의 기록 유형. |
| `record_id` | `uuid` | NO | — | — | 대상 기록 UUID. |
| `change_kind` | `text` | NO | — | — | 감사 사건의 조작 유형. |
| `before_value` | `jsonb` | YES | — | — | 조작 전 기록 JSON. |
| `after_value` | `jsonb` | YES | — | — | 조작 후 기록 JSON. |
| `recorded_at` | `timestamp with time zone` | NO | — | — | 서버에 근거가 기록된 시각. |
| `change_context` | `jsonb` | YES | — | — | 정정·삭제·복구의 처리 문맥. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `record_change_events_pkey` | `PRIMARY KEY (id)` |
| FK | `record_change_events_session_id_fkey` | `FOREIGN KEY (session_id) REFERENCES workout_sessions(id)` · 삭제 `NO ACTION` |
| CHECK | `record_change_events_change_context_check` | `CHECK (((change_context IS NULL) OR ((jsonb_typeof(change_context) = 'object'::text) AND COALESCE(((change_context -> 'schemaVersion'::text) = '1'::jsonb), false))))` |
| CHECK | `record_change_events_change_kind_check` | `CHECK ((change_kind = ANY (ARRAY['CORRECTED'::text, 'DELETED'::text, 'RECOVERED'::text])))` |
| CHECK | `record_change_events_check` | `CHECK ((((change_kind = 'RECOVERED'::text) AND (record_kind = ANY (ARRAY['SET'::text, 'CARDIO'::text])) AND (before_value IS NULL) AND (after_value IS NOT NULL) AND (change_context IS NOT NULL)) OR ((change_kind <> 'RECOVERED'::text) AND (before_value IS NOT NULL))))` |
| CHECK | `record_change_events_record_kind_check` | `CHECK ((record_kind = ANY (ARRAY['SET'::text, 'CARDIO'::text, 'SESSION'::text])))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `record_change_events_pkey` | `CREATE UNIQUE INDEX record_change_events_pkey ON public.record_change_events USING btree (id)` | `record_change_events_pkey` |
| `record_change_events_session_time` | `CREATE INDEX record_change_events_session_time ON public.record_change_events USING btree (session_id, recorded_at, id)` | — |

## 6. 디로딩 분석·기간·임시 계획

분석 설정은 버전과 epoch를, 실제 디로딩은 기간과 임시 계획을 사용한다. 계산 문맥을 읽는 뷰는 보존 규칙에서 설명한다.

[`deload_analysis_versions`](#deload_analysis_versions) · [`deload_weekly_assessments`](#deload_weekly_assessments) · [`deload_periods`](#deload_periods) · [`deload_temporary_plans`](#deload_temporary_plans)

### `deload_analysis_versions`

분석 활성 여부·epoch의 불변 버전 이력. 기간 종료 후의 재개 버전은 해당 완료 기간을 참조한다.

근거 [V25](../../src/main/resources/db/migration/V25__add_deload_analysis_settings_and_epochs.sql) · [V29](../../src/main/resources/db/migration/V29__complete_deload_period_lifecycle.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `user_id` | `uuid` | NO | — | PK, FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | PK, FK | 소유 계정의 운동 데이터 세대. |
| `version` | `bigint` | NO | — | PK | 행 또는 이력의 버전. |
| `enabled` | `boolean` | NO | — | — | 디로딩 분석 활성 여부. |
| `epoch` | `bigint` | NO | — | FK | 분석 누적 구간의 시작 버전. |
| `recorded_at` | `timestamp with time zone` | NO | — | — | 서버에 근거가 기록된 시각. |
| `epoch_started_at` | `timestamp with time zone` | NO | — | — | 분석 epoch 시작 시각. |
| `time_zone` | `text` | YES | — | — | 해당 시점의 현지 시간대 ID. |
| `reason` | `text` | NO | — | — | 처리 사유. |
| `completed_period_id` | `uuid` | YES | — | FK, UK | 분석 재개에 연결된 종료 기간 ID. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `deload_analysis_versions_pkey` | `PRIMARY KEY (user_id, data_generation, version)` |
| FK | `deload_analysis_completed_period_fk` | `FOREIGN KEY (user_id, data_generation, completed_period_id) REFERENCES deload_periods(user_id, data_generation, id)` · 삭제 `NO ACTION` |
| FK | `deload_analysis_versions_user_id_data_generation_epoch_fkey` | `FOREIGN KEY (user_id, data_generation, epoch) REFERENCES deload_analysis_versions(user_id, data_generation, version)` · 삭제 `NO ACTION` |
| FK | `deload_analysis_versions_user_id_data_generation_fkey` | `FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)` · 삭제 `NO ACTION` |
| UNIQUE | `deload_analysis_one_epoch_per_period` | `UNIQUE (completed_period_id)` |
| CHECK | `deload_analysis_completion_origin` | `CHECK (((reason = 'DELOAD_COMPLETED'::text) = (completed_period_id IS NOT NULL)))` |
| CHECK | `deload_analysis_versions_check` | `CHECK (((epoch >= 0) AND (epoch <= version)))` |
| CHECK | `deload_analysis_versions_check1` | `CHECK ((isfinite(epoch_started_at) AND (epoch_started_at <= recorded_at)))` |
| CHECK | `deload_analysis_versions_reason_check` | `CHECK ((reason = ANY (ARRAY['INITIAL'::text, 'ADOPTED_INITIAL'::text, 'TIME_ZONE_SET'::text, 'DISABLED'::text, 'ENABLED'::text, 'DELOAD_COMPLETED'::text])))` |
| CHECK | `deload_analysis_versions_recorded_at_check` | `CHECK (isfinite(recorded_at))` |
| CHECK | `deload_analysis_versions_time_zone_check` | `CHECK (deload_time_zone_valid(time_zone))` |
| CHECK | `deload_analysis_versions_version_check` | `CHECK ((version >= 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `deload_analysis_one_epoch_per_period` | `CREATE UNIQUE INDEX deload_analysis_one_epoch_per_period ON public.deload_analysis_versions USING btree (completed_period_id)` | `deload_analysis_one_epoch_per_period` |
| `deload_analysis_versions_pkey` | `CREATE UNIQUE INDEX deload_analysis_versions_pkey ON public.deload_analysis_versions USING btree (user_id, data_generation, version)` | `deload_analysis_versions_pkey` |

| 트리거 | 정의 |
| --- | --- |
| `deload_analysis_version_guard` | `CREATE TRIGGER deload_analysis_version_guard BEFORE INSERT OR UPDATE ON public.deload_analysis_versions FOR EACH ROW EXECUTE FUNCTION guard_deload_analysis_version()` |

### `deload_weekly_assessments`

epoch별 주간 분석 입력, 기여도 분수와 재검토 상태를 저장한다.

근거 [V25](../../src/main/resources/db/migration/V25__add_deload_analysis_settings_and_epochs.sql) · [V26](../../src/main/resources/db/migration/V26__add_excluded_deload_weekly_assessments.sql) · [V29](../../src/main/resources/db/migration/V29__complete_deload_period_lifecycle.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `user_id` | `uuid` | NO | — | PK, FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | PK, FK | 소유 계정의 운동 데이터 세대. |
| `epoch` | `bigint` | NO | — | PK, FK | 분석 누적 구간의 시작 버전. |
| `week_start` | `date` | NO | — | PK | 주간 판단의 현지 주 시작일. |
| `status` | `text` | NO | — | — | 행의 수명주기 상태. |
| `e_numerator` | `numeric` | YES | — | — | E 값의 정확한 분자. |
| `e_denominator` | `numeric` | YES | — | — | E 값의 정확한 분모. |
| `n` | `integer` | YES | — | — | 판단에서 집계한 N 값. |
| `operation` | `text` | YES | — | — | 주간 판단에 적용한 연산. |
| `contribution_numerator` | `numeric` | YES | — | — | 주간 기여도 분자. |
| `contribution_denominator` | `numeric` | YES | — | — | 주간 기여도 분모. |
| `rule_version` | `integer` | NO | — | — | 주간 판단에 적용한 규칙 버전. |
| `revision` | `bigint` | NO | — | — | 저장된 판단의 개정 번호. |
| `input_revision` | `text` | NO | — | — | 재검토 입력 개정 번호. |
| `needs_recheck` | `boolean` | NO | `false` | — | 후속 재검토 필요 여부. |
| `confirmed_at` | `timestamp with time zone` | YES | — | — | 주간 판단 확정 시각. |
| `updated_at` | `timestamp with time zone` | NO | — | — | 마지막 갱신 시각. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `deload_weekly_assessments_pkey` | `PRIMARY KEY (user_id, data_generation, epoch, week_start)` |
| FK | `deload_weekly_assessments_user_id_data_generation_epoch_fkey` | `FOREIGN KEY (user_id, data_generation, epoch) REFERENCES deload_analysis_versions(user_id, data_generation, version)` · 삭제 `NO ACTION` |
| CHECK | `deload_weekly_assessments_check` | `CHECK ((((status = 'PENDING'::text) AND (e_numerator IS NULL) AND (e_denominator IS NULL) AND (n IS NULL) AND (operation IS NULL) AND (contribution_numerator IS NULL) AND (contribution_denominator IS NULL) AND (confirmed_at IS NULL)) OR ((status = 'CONFIRMED'::text) AND (e_numerator IS NOT NULL) AND (e_denominator IS NOT NULL) AND (n IS NOT NULL) AND (operation IS NOT NULL) AND (contribution_numerator IS NOT NULL) AND (contribution_denominator IS NOT NULL) AND (confirmed_at IS NOT NULL) AND isfinite(confirmed_at) AND (confirmed_at <= updated_at) AND (e_numerator <= ((n)::numeric * e_denominator)) AND (((operation = 'RESET'::text) AND (e_numerator <= e_denominator) AND (contribution_numerator = (0)::numeric)) OR ((operation = 'ADD'::text) AND (e_numerator > e_denominator) AND (n > 0) AND (contribution_numerator <= contribution_denominator) AND (((contribution_numerator * e_denominator) * (n)::numeric) = (e_numerator * contribution_denominator))))) OR ((status = 'EXCLUDED'::text) AND (e_numerator IS NULL) AND (e_denominator IS NULL) AND (n IS NULL) AND (operation IS NULL) AND (contribution_numerator IS NULL) AND (contribution_denominator IS NULL) AND (confirmed_at IS NOT NULL) AND isfinite(confirmed_at) AND (confirmed_at <= updated_at))))` |
| CHECK | `deload_weekly_assessments_contribution_denominator_check` | `CHECK (((contribution_denominator > (0)::numeric) AND (scale(contribution_denominator) = 0) AND ((contribution_denominator)::text <> ALL (ARRAY['NaN'::text, 'Infinity'::text, '-Infinity'::text]))))` |
| CHECK | `deload_weekly_assessments_contribution_numerator_check` | `CHECK (((contribution_numerator >= (0)::numeric) AND (scale(contribution_numerator) = 0) AND ((contribution_numerator)::text <> ALL (ARRAY['NaN'::text, 'Infinity'::text, '-Infinity'::text]))))` |
| CHECK | `deload_weekly_assessments_e_denominator_check` | `CHECK (((e_denominator > (0)::numeric) AND (scale(e_denominator) = 0) AND ((e_denominator)::text <> ALL (ARRAY['NaN'::text, 'Infinity'::text, '-Infinity'::text]))))` |
| CHECK | `deload_weekly_assessments_e_numerator_check` | `CHECK (((e_numerator >= (0)::numeric) AND (scale(e_numerator) = 0) AND ((e_numerator)::text <> ALL (ARRAY['NaN'::text, 'Infinity'::text, '-Infinity'::text]))))` |
| CHECK | `deload_weekly_assessments_input_revision_check` | `CHECK ((input_revision !~ '^[[:space:]]*$'::text))` |
| CHECK | `deload_weekly_assessments_n_check` | `CHECK (((n >= 0) AND (n <= 7)))` |
| CHECK | `deload_weekly_assessments_operation_check` | `CHECK ((operation = ANY (ARRAY['RESET'::text, 'ADD'::text])))` |
| CHECK | `deload_weekly_assessments_revision_check` | `CHECK ((revision >= 0))` |
| CHECK | `deload_weekly_assessments_rule_version_check` | `CHECK ((rule_version > 0))` |
| CHECK | `deload_weekly_assessments_status_check` | `CHECK ((status = ANY (ARRAY['PENDING'::text, 'CONFIRMED'::text, 'EXCLUDED'::text])))` |
| CHECK | `deload_weekly_assessments_updated_at_check` | `CHECK (isfinite(updated_at))` |
| CHECK | `deload_weekly_assessments_week_start_check` | `CHECK ((isfinite(week_start) AND (EXTRACT(isodow FROM week_start) = (1)::numeric)))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `deload_weekly_assessments_pkey` | `CREATE UNIQUE INDEX deload_weekly_assessments_pkey ON public.deload_weekly_assessments USING btree (user_id, data_generation, epoch, week_start)` | `deload_weekly_assessments_pkey` |
| `deload_weekly_recheck` | `CREATE INDEX deload_weekly_recheck ON public.deload_weekly_assessments USING btree (user_id, data_generation, epoch, week_start) WHERE needs_recheck` | — |

| 트리거 | 정의 |
| --- | --- |
| `deload_weekly_epoch_guard` | `CREATE TRIGGER deload_weekly_epoch_guard BEFORE INSERT OR UPDATE ON public.deload_weekly_assessments FOR EACH ROW EXECUTE FUNCTION guard_deload_weekly_epoch()` |

### `deload_periods`

실제 디로딩 기간, 시작 원본, 조작 멱등성, 종료 일자 및 종료 사유를 저장한다.

근거 [V27](../../src/main/resources/db/migration/V27__create_deload_periods_and_temporary_plans.sql) · [V28](../../src/main/resources/db/migration/V28__enable_deload_sessions_and_plan_management.sql) · [V29](../../src/main/resources/db/migration/V29__complete_deload_period_lifecycle.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | FK | 소유 계정의 운동 데이터 세대. |
| `request_id` | `uuid` | NO | — | — | 시작 요청의 멱등성 키. |
| `request_hash` | `text` | NO | — | — | 시작 요청 비교용 해시. |
| `source_hash` | `text` | NO | — | — | 시작 원본 비교용 해시. |
| `started_at` | `timestamp with time zone` | NO | — | — | 실제 시작 시각. |
| `start_date` | `date` | NO | — | — | 기간의 현지 시작일. |
| `time_zone` | `text` | NO | — | — | 해당 시점의 현지 시간대 ID. |
| `recommended_end_date` | `date` | YES | STORED 생성식 `(start_date + 6)` | — | 기간의 현지 권장 종료일. |
| `maximum_end_date` | `date` | YES | STORED 생성식 `(start_date + 9)` | — | 기간의 현지 최대 종료일. |
| `status` | `text` | NO | — | — | 행의 수명주기 상태. |
| `version` | `bigint` | NO | — | — | 행 또는 이력의 버전. |
| `method` | `text` | NO | — | — | 시작 시 확정한 디로딩 적용 방식. |
| `split` | `text` | NO | — | — | 시작 시 확정한 운동 분할 방식. |
| `source_snapshot` | `jsonb` | NO | — | — | 시작에 사용한 프리셋·일정 및 계획 근거 JSON. |
| `closed_at` | `timestamp with time zone` | YES | — | — | 도메인에서 적용하는 종료 시각. |
| `closed_date` | `date` | YES | — | — | 도메인에서 적용하는 현지 종료일. |
| `updated_at` | `timestamp with time zone` | NO | — | — | 마지막 갱신 시각. |
| `current_method` | `text` | NO | — | — | 조작 후 현재 디로딩 방식. |
| `current_split` | `text` | NO | — | — | 조작 후 현재 운동 분할 방식. |
| `last_change_id` | `uuid` | YES | — | — | 마지막 계획 조작 요청 ID. |
| `last_change_hash` | `text` | YES | — | — | 마지막 계획 조작 요청 비교용 해시. |
| `end_reason` | `text` | YES | — | — | 기간 종료 사유. |
| `end_request_id` | `uuid` | YES | — | — | 명시 종료 요청의 멱등성 키. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `deload_periods_pkey` | `PRIMARY KEY (id)` |
| FK | `deload_periods_user_id_data_generation_fkey` | `FOREIGN KEY (user_id, data_generation) REFERENCES users(id, data_generation)` · 삭제 `NO ACTION` |
| UNIQUE | `deload_period_end_request` | `UNIQUE (user_id, data_generation, end_request_id)` |
| UNIQUE | `deload_periods_user_id_data_generation_id_key` | `UNIQUE (user_id, data_generation, id)` |
| UNIQUE | `deload_periods_user_id_data_generation_request_id_key` | `UNIQUE (user_id, data_generation, request_id)` |
| CHECK | `deload_period_end_metadata` | `CHECK ((((end_reason IS NULL) AND (end_request_id IS NULL)) OR ((end_reason IS NOT NULL) AND (((status = 'COMPLETED'::text) AND (end_reason = 'MANUAL_COMPLETION'::text) AND (end_request_id IS NOT NULL)) OR ((status = 'COMPLETED'::text) AND (end_reason = 'AUTOMATIC_COMPLETION'::text) AND (closed_date = maximum_end_date)) OR ((status = 'CANCELLED'::text) AND (end_reason = 'USER_CANCELLED'::text) AND (end_request_id IS NOT NULL))))))` |
| CHECK | `deload_periods_check` | `CHECK (((start_date = ((started_at AT TIME ZONE time_zone))::date) AND (started_at <= updated_at)))` |
| CHECK | `deload_periods_check1` | `CHECK ((((method = 'REST'::text) AND (split = 'REST'::text)) OR ((method = 'KEEP'::text) AND (split = 'ORIGINAL'::text)) OR ((method = 'THREE'::text) AND (split = ANY (ARRAY['PPL'::text, 'EQUAL'::text]))) OR ((method = 'TWO'::text) AND (split = ANY (ARRAY['FRONT_BACK'::text, 'EQUAL'::text])))))` |
| CHECK | `deload_periods_check2` | `CHECK ((((status = 'ACTIVE'::text) AND (closed_at IS NULL) AND (closed_date IS NULL)) OR ((status <> 'ACTIVE'::text) AND (closed_at IS NOT NULL) AND isfinite(closed_at) AND (closed_at >= started_at) AND (closed_at <= updated_at) AND (closed_date IS NOT NULL) AND isfinite(closed_date) AND ((closed_date >= start_date) AND (closed_date <= maximum_end_date)) AND (closed_date = ((closed_at AT TIME ZONE time_zone))::date))))` |
| CHECK | `deload_periods_check3` | `CHECK (((last_change_id IS NULL) = (last_change_hash IS NULL)))` |
| CHECK | `deload_periods_check4` | `CHECK ((((current_method = 'REST'::text) AND (current_split = 'REST'::text)) OR ((current_method = 'KEEP'::text) AND (current_split = 'ORIGINAL'::text)) OR ((current_method = 'THREE'::text) AND (current_split = ANY (ARRAY['PPL'::text, 'EQUAL'::text]))) OR ((current_method = 'TWO'::text) AND (current_split = ANY (ARRAY['FRONT_BACK'::text, 'EQUAL'::text])))))` |
| CHECK | `deload_periods_last_change_hash_check` | `CHECK (((last_change_hash IS NULL) OR (last_change_hash ~ '^[0-9a-f]{64}$'::text)))` |
| CHECK | `deload_periods_method_check` | `CHECK ((method = ANY (ARRAY['KEEP'::text, 'THREE'::text, 'TWO'::text, 'REST'::text])))` |
| CHECK | `deload_periods_request_hash_check` | `CHECK ((request_hash ~ '^[0-9a-f]{64}$'::text))` |
| CHECK | `deload_periods_source_hash_check` | `CHECK ((source_hash ~ '^[0-9a-f]{64}$'::text))` |
| CHECK | `deload_periods_source_snapshot_check` | `CHECK ((jsonb_typeof(source_snapshot) = 'object'::text))` |
| CHECK | `deload_periods_split_check` | `CHECK ((split = ANY (ARRAY['ORIGINAL'::text, 'PPL'::text, 'FRONT_BACK'::text, 'EQUAL'::text, 'REST'::text])))` |
| CHECK | `deload_periods_start_date_check` | `CHECK (isfinite(start_date))` |
| CHECK | `deload_periods_started_at_check` | `CHECK (isfinite(started_at))` |
| CHECK | `deload_periods_status_check` | `CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'COMPLETED'::text, 'CANCELLED'::text])))` |
| CHECK | `deload_periods_time_zone_check` | `CHECK (deload_time_zone_valid(time_zone))` |
| CHECK | `deload_periods_updated_at_check` | `CHECK (isfinite(updated_at))` |
| CHECK | `deload_periods_version_check` | `CHECK ((version >= 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `deload_one_active_period` | `CREATE UNIQUE INDEX deload_one_active_period ON public.deload_periods USING btree (user_id, data_generation) WHERE (status = 'ACTIVE'::text)` | — |
| `deload_period_end_request` | `CREATE UNIQUE INDEX deload_period_end_request ON public.deload_periods USING btree (user_id, data_generation, end_request_id)` | `deload_period_end_request` |
| `deload_periods_pkey` | `CREATE UNIQUE INDEX deload_periods_pkey ON public.deload_periods USING btree (id)` | `deload_periods_pkey` |
| `deload_periods_user_id_data_generation_id_key` | `CREATE UNIQUE INDEX deload_periods_user_id_data_generation_id_key ON public.deload_periods USING btree (user_id, data_generation, id)` | `deload_periods_user_id_data_generation_id_key` |
| `deload_periods_user_id_data_generation_request_id_key` | `CREATE UNIQUE INDEX deload_periods_user_id_data_generation_request_id_key ON public.deload_periods USING btree (user_id, data_generation, request_id)` | `deload_periods_user_id_data_generation_request_id_key` |

| 트리거 | 정의 |
| --- | --- |
| `deload_method_initial` | `CREATE TRIGGER deload_method_initial BEFORE INSERT ON public.deload_periods FOR EACH ROW EXECUTE FUNCTION initialize_deload_method()` |
| `deload_period_end_guard` | `CREATE TRIGGER deload_period_end_guard BEFORE UPDATE ON public.deload_periods FOR EACH ROW EXECUTE FUNCTION guard_deload_period_end()` |
| `deload_period_origin_immutable` | `CREATE TRIGGER deload_period_origin_immutable BEFORE UPDATE ON public.deload_periods FOR EACH ROW EXECUTE FUNCTION guard_deload_period_origin()` |

### `deload_temporary_plans`

기간별 임시 운동 계획. 최초·현재 계획과 삭제·실행 가능 날짜를 구분한다.

근거 [V27](../../src/main/resources/db/migration/V27__create_deload_periods_and_temporary_plans.sql) · [V28](../../src/main/resources/db/migration/V28__enable_deload_sessions_and_plan_management.sql)

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `id` | `uuid` | NO | — | PK | 행 식별자. |
| `user_id` | `uuid` | NO | — | FK | 소유 계정 ID. |
| `data_generation` | `bigint` | NO | — | FK | 소유 계정의 운동 데이터 세대. |
| `period_id` | `uuid` | NO | — | FK | 임시 계획이 속한 디로딩 기간 ID. |
| `position` | `integer` | NO | — | — | 계획 내 배치 순서. |
| `planned_date` | `date` | NO | — | — | 임시 계획의 예정일. |
| `version` | `bigint` | NO | `0` | — | 행 또는 이력의 버전. |
| `initial_plan` | `jsonb` | NO | — | — | 생성 시 보존한 최초 임시 계획 JSON. |
| `current_plan` | `jsonb` | NO | — | — | 편집으로 갱신된 임시 계획 JSON. |
| `deleted_at` | `timestamp with time zone` | YES | — | — | 논리 삭제 시각. |
| `live_date` | `date` | YES | STORED 생성식 `CASE WHEN (deleted_at IS NULL) THEN planned_date ELSE NULL::date END` | — | 삭제되지 않은 계획의 날짜를 노출하는 생성 컬럼. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `deload_temporary_plans_pkey` | `PRIMARY KEY (id)` |
| FK | `deload_temporary_plans_user_id_data_generation_period_id_fkey` | `FOREIGN KEY (user_id, data_generation, period_id) REFERENCES deload_periods(user_id, data_generation, id)` · 삭제 `NO ACTION` |
| UNIQUE | `deload_live_date` | `UNIQUE (period_id, live_date) DEFERRABLE INITIALLY DEFERRED` |
| UNIQUE | `deload_temporary_plans_user_id_data_generation_period_id_id_key` | `UNIQUE (user_id, data_generation, period_id, id)` |
| CHECK | `deload_temporary_plans_current_plan_check` | `CHECK ((jsonb_typeof(current_plan) = 'object'::text))` |
| CHECK | `deload_temporary_plans_deleted_at_check` | `CHECK (((deleted_at IS NULL) OR isfinite(deleted_at)))` |
| CHECK | `deload_temporary_plans_initial_plan_check` | `CHECK ((jsonb_typeof(initial_plan) = 'object'::text))` |
| CHECK | `deload_temporary_plans_planned_date_check` | `CHECK (isfinite(planned_date))` |
| CHECK | `deload_temporary_plans_position_check` | `CHECK ((("position" >= 0) AND ("position" <= 6)))` |
| CHECK | `deload_temporary_plans_version_check` | `CHECK ((version >= 0))` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `deload_live_date` | `CREATE UNIQUE INDEX deload_live_date ON public.deload_temporary_plans USING btree (period_id, live_date)` | `deload_live_date` |
| `deload_live_position` | `CREATE UNIQUE INDEX deload_live_position ON public.deload_temporary_plans USING btree (period_id, "position") WHERE (deleted_at IS NULL)` | — |
| `deload_temporary_plans_pkey` | `CREATE UNIQUE INDEX deload_temporary_plans_pkey ON public.deload_temporary_plans USING btree (id)` | `deload_temporary_plans_pkey` |
| `deload_temporary_plans_user_id_data_generation_period_id_id_key` | `CREATE UNIQUE INDEX deload_temporary_plans_user_id_data_generation_period_id_id_key ON public.deload_temporary_plans USING btree (user_id, data_generation, period_id, id)` | `deload_temporary_plans_user_id_data_generation_period_id_id_key` |

| 트리거 | 정의 |
| --- | --- |
| `deload_plan_origin_guard` | `CREATE TRIGGER deload_plan_origin_guard BEFORE INSERT OR UPDATE ON public.deload_temporary_plans FOR EACH ROW EXECUTE FUNCTION guard_deload_initial_plan()` |

## 7. Flyway 메타데이터

Flyway가 실행 이력을 관리한다.

[`flyway_schema_history`](#flyway_schema_history)

### `flyway_schema_history`

실행한 migration의 버전, 체크섬, 실행 시간과 성공 여부.

근거 Flyway가 migration 실행 시 관리하는 메타데이터.

| 컬럼 | PostgreSQL 자료형 | NULL | 기본값 / 생성식 | 키 | 역할 |
| --- | --- | --- | --- | --- | --- |
| `installed_rank` | `integer` | NO | — | PK | Flyway 설치 순서와 PK. |
| `version` | `character varying(50)` | YES | — | — | 행 또는 이력의 버전. |
| `description` | `character varying(200)` | NO | — | — | Flyway migration 설명. |
| `type` | `character varying(20)` | NO | — | — | Flyway migration 유형. |
| `script` | `character varying(1000)` | NO | — | — | 실행한 migration 스크립트 이름. |
| `checksum` | `integer` | YES | — | — | Flyway가 기록한 migration 체크섬. |
| `installed_by` | `character varying(100)` | NO | — | — | migration을 실행한 DB 사용자. |
| `installed_on` | `timestamp without time zone` | NO | `now()` | — | migration 설치 시각. |
| `execution_time` | `integer` | NO | — | — | migration 실행 시간(밀리초). |
| `success` | `boolean` | NO | — | — | 실행 성공 여부. |

| 종류 | 제약조건 | 정의 |
| --- | --- | --- |
| PK | `flyway_schema_history_pk` | `PRIMARY KEY (installed_rank)` |

| 인덱스 | 정의 | 지원 제약조건 |
| --- | --- | --- |
| `flyway_schema_history_pk` | `CREATE UNIQUE INDEX flyway_schema_history_pk ON public.flyway_schema_history USING btree (installed_rank)` | `flyway_schema_history_pk` |
| `flyway_schema_history_s_idx` | `CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success)` | — |

## 8. 뷰와 데이터 보존

### 계정 생성과 기존 계정 경계

`AccountProvisioningService`와 `AccountRepository`는 새 계정을 검증된 신원에 연결하고 같은 트랜잭션에서 휴식 설정, G4 분석 초기 버전(ON, version 0/epoch 0), 비어 있는 활성 프리셋과 자동 순환 OFF 상태를 저장한다. V25와 V30의 DDL은 기존 계정을 일괄 채우지 않는다. 기존 분석 미설정 계정은 명시적 내부 `T_adopt`부터 이행할 수 있다. 기존 프리셋 상태가 비어 있으면 주간 설정 조회·수정 경로에서 `PRESET_STATE_MISSING`을 반환한다. [계정 프로비저닝](../../src/main/java/com/overload/overload_server/account/AccountRepository.java) · [G4 기존 계정 이행](../api/deload-analysis.md) · [주간 일정 API](../api/weekly-schedule.md)

### 프리셋, 적용 이력과 자동 순환

- V30 프리셋은 계정·데이터 세대별 1~4번 표시 위치를 사용한다. 위치 유일성은 지연 가능한 제약이고, 상태의 활성 프리셋은 동일 소유자·세대 FK로 제한된다. DB는 배열 형태와 최대 7개를 검사한다. 서버는 요일 범위·중복, 삭제되지 않은 동일 범위 루틴, 활성 프리셋 삭제 제한, 마지막 프리셋 및 순환 포함 조건을 검사하고 사용자 행 잠금과 상태·프리셋 버전으로 변경을 직렬화한다.
- 실제 적용 이력은 `weekly_schedule_versions`에 저장한다. 최초 적용 또는 실제 배정 변경 시 시간대, 적용 날짜, 기록 시각과 배정을 추가하며 현재 루틴 계획도 `routine_plan_history`에 기록한다. 프리셋 ID만 바뀌고 배정이 같으면 기존 적용 버전을 유지한다. 삭제 가능한 비활성 프리셋의 행을 제거하고 표시 순서를 조정해도 적용 이력·최초 START/MISS·수행 기록은 남는다. 루틴을 논리 삭제하면 프리셋의 현재 배정에서 제거하고, 활성 배정이 바뀐 경우 새 적용 버전을 추가한다. V24가 정한 일별 검토 기준 이전의 과거를 새 판단으로 소급 생성하지 않는다.
- 자동 순환은 포함된 프리셋의 표시 순서와 `next_rotation_at`에 의존한다. 저장 시간대의 월요일 00:00부터 대상이 되며 실제 적용은 공통 작업의 다음 처리에서 일어난다. 서버 ACTIVE 세션이나 G5 보호가 있으면 보류한다. 서버가 늦게 처리한 일반 순환은 지난 월요일 횟수로 대상을 계산하되 실제 처리부터 적용한다. 공통 작업은 G5 자동 완료 → 순환 → G3 일별 검사 → G4 주간 확정 순서다.

[WeeklyScheduleStore](../../src/main/java/com/overload/overload_server/routine/WeeklyScheduleStore.java) · [WeeklyScheduleService](../../src/main/java/com/overload/overload_server/routine/WeeklyScheduleService.java) · [AppliedWeeklySchedule](../../src/main/java/com/overload/overload_server/routine/AppliedWeeklySchedule.java) · [WeeklyScheduleRotation](../../src/main/java/com/overload/overload_server/routine/WeeklyScheduleRotation.java) · [주간 일정 API](../api/weekly-schedule.md)

### 세션의 소속, 재시도와 보존

- START는 계정·세대와 원본 루틴 또는 디로딩 임시 계획을 확인해 클라이언트가 보낸 세션 ID, 최초 계획, 시작 기기·날짜·시간대, 일정 출처를 저장한다. 같은 ID로 같은 START를 재시도하면 기존 세션을 확인한다. 부분 고유 인덱스는 계정당 ACTIVE 하나와 포기 처리 전 임시 계획당 세션 하나를 강제한다. START 근거와 계획은 이후 편집·정정과 분리한다.
- snapshot은 편집 기기, `writer_epoch`, `local_revision`과 정규화한 `last_snapshot_hash`를 사용해 재시도와 충돌을 구별한다. FINISH는 최종 스냅샷·종료 시각·최초 평가를 보존해 동일 요청을 확인할 수 있다. 기간이 끝나거나 기록이 논리 삭제된 뒤에도 이미 확정된 FINISH 확인 경로를 유지한다. 마지막 실제 운동 동기화 이후 24시간이 지난 ACTIVE는 AUTO_CLOSED 대상이다. 현행 멱등성의 근거는 세션과 수행·승인·변경 이력에 있다.
- 완료 세트는 당시 장비·승인 문맥을 보존한다. V22의 선택 `bodyweight_load`는 명시한 추가 중량이며 기존 BODYWEIGHT 중량을 재해석하지 않는다. V21의 선택 `measurement`는 실제 속도와 거리 출처를 저장한다. 기존 기록의 알 수 없는 거리 출처는 비워 둔다. 두 선택 필드는 기존 확정 JSON 및 해시의 의미를 바꾸지 않는다.
- 종료 후 정정·삭제는 현재 행과 `record_change_events`의 전후 값을 남긴다. 미전송 수행의 명시 복구는 세션 버전과 저장된 출처를 확인하고 기존 최초 평가·종료 확인 정보를 보존한다. 공식 승인 사건은 `progress_change_events`에 저장되며 일반 배치의 승인 중량을 나타낸다. 디로딩 세션의 일반 승인과 승인 사건은 서버 및 V28 제약 트리거에서 차단한다.

[WorkoutSessionService](../../src/main/java/com/overload/overload_server/workoutsession/WorkoutSessionService.java) · [WorkoutSnapshotService](../../src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotService.java) · [WorkoutRecordService](../../src/main/java/com/overload/overload_server/workoutsession/WorkoutRecordService.java) · [세션 API](../api/workout-sessions.md) · [유산소 실제 측정](../api/cardio-actual-measurements.md) · [맨몸 추가 부하](../api/bodyweight-additional-load.md)

### 통계와 디로딩

- **통계.** `PlanningRepository`는 적용 일정과 루틴 계획 이력, 최초 START/MISS를 묶어 예정일 근거를 읽는다. `StatisticsRepository`는 종료된 세션의 최초·적용 계획과 삭제되지 않은 수행 기록에서 활동량과 달성률 입력을 읽는다. 일반 계획 달성률 및 G3/G4 일반 근거에서 디로딩을 제외하고, 실제 활동 통계에는 유효한 디로딩 수행을 포함한다. `GrowthRepository`는 승인 사건과 당시 이름을 배치별로 읽는다. 통계 응답과 성장선은 조회 시 산출하며, 일별·디로딩 주간 판단만 별도의 판단 행으로 저장한다.
- **분석과 epoch.** 설정 변경은 `deload_analysis_versions`에 새 버전을 추가한다. ON→OFF는 현재 epoch를 유지하고 OFF→ON은 새 epoch를 시작한다. 주간 확정은 epoch별 `deload_weekly_assessments`에 입력·상태·분수 기여도·규칙 버전을 저장한다. 원본 정정에 따른 dirty/재검토 상태와 유효한 확정 구간을 분리한다. V29의 일반 뷰 `deload_analysis_contexts`는 분석 버전의 컬럼 전체에 nullable `resume_date date`를 추가한다. 같은 계정·세대의 epoch 시작 버전을 찾아 해당 버전이 참조하는 완료 기간의 `closed_date + 1`을 반환한다. 뷰는 별도 행을 저장하지 않는다.
- **실제 기간.** V27~V29는 기간, 임시 계획, 실제 세션과 종료 처리를 추가한다. 기간 시작은 원본 프리셋 ID·버전과 해당 루틴 계획을 복사한다. V30에서 일반 활성 프리셋 A와 선택 편성 원본 B를 구분하며 B는 순환 제외 프리셋일 수 있다. `workout_sessions`의 기간·임시 계획 ID는 함께 저장되며 소유자·세대·기간까지 같은 임시 계획을 참조한다. 원본 계획, 현재 계획, 실제 세션 및 종료 기록은 구분해 보존한다.
- **종료와 복귀.** 시작 시 활성 A와 자동 순환 ON/OFF를 유지하고 다음 순환 시각을 비운다. 일반 접근 복귀일에 A를 사용하고, ON이면 복귀일보다 뒤의 첫 월요일을 새 순환 기준으로 저장한다. 중간 월요일을 순환에 소비하거나 소급 적용 이력을 생성하지 않는다. 유효 종료 시각·날짜와 실제 처리 시각(API `processedAt`에 사용하는 기간 `updated_at`)을 구분한다. 완료 시 분석 상태에 따라 재개 버전·epoch를 기록하며, 종료 후에도 임시 계획과 과거 수행은 조회 근거로 남는다.

[PlanningRepository](../../src/main/java/com/overload/overload_server/statistics/PlanningRepository.java) · [StatisticsRepository](../../src/main/java/com/overload/overload_server/statistics/StatisticsRepository.java) · [GrowthRepository](../../src/main/java/com/overload/overload_server/statistics/GrowthRepository.java) · [DeloadAnalysisRepository](../../src/main/java/com/overload/overload_server/deload/DeloadAnalysisRepository.java) · [DeloadWeeklyRepository](../../src/main/java/com/overload/overload_server/deload/DeloadWeeklyRepository.java) · [DeloadPeriodRepository](../../src/main/java/com/overload/overload_server/deload/DeloadPeriodRepository.java) · [DeloadPeriodLifecycle](../../src/main/java/com/overload/overload_server/deload/DeloadPeriodLifecycle.java) · [통계 API](../api/statistics.md) · [디로딩 분석 API](../api/deload-analysis.md) · [디로딩 기간 API](../api/deload-periods.md)

## 9. Flyway 변경 이력

| 버전 | 누적 결과를 이해할 때 볼 변경 |
| --- | --- |
| V1~V8 | 사용자·인증 연결·Spring Session·이메일 OTP 및 재인증. V7~V8은 퇴역 인증 참조를 보존한다. |
| V9~V10 | V9의 범용 운동 명령·동기화 기반 중 `workout_sync_state`, `sync_operations`, `workout_change_batches`와 두 `intent_stamp` 컬럼은 V10에서 제거됐다. |
| V11~V15 | 루틴, 세션, 전체 스냅샷, 최초 종료 평가, 종료 기록 정정·복구. |
| V16~V18 | 표준 카탈로그와 운동 FK 교체, 관절 분류. `exercise_definitions` 테이블 자체는 남아 있다. |
| V19~V24 | 적용 일정·루틴 계획 이력, 최초 일정과 변경의 즉시 적용, 실제 유산소 측정·맨몸 부하·유산소 카탈로그·일별 판단. |
| V25~V29 | 분석 버전·epoch와 주간 확정, 디로딩 v1.7 기간·임시 계획·세션·기간 종료 및 분석 재개. |
| V30 | 편집 가능한 다중 프리셋과 활성·순환 상태. 기존 적용 이력·계획·수행 기록은 같은 구조에 유지된다. |
| V31 | 주간 일정 프리셋의 nullable 이름 컬럼과 비공백 제약. 기존 행과 적용 이력을 보존한다. |
| V32 | 고정 UUID의 표준 근육 40개, 표준 운동 상세·독립 검수, 운동별 PRIMARY 순서, ExerciseDB 원본 참조. |
| V33 | 표준 유산소 운동과 기록 설정 연결, 루틴과 세션의 유산소 표준 UUID 및 세션의 당시 측정 종류 보존. |
| V34 | ExerciseDB V1 원본, 분석 근거, 검수 초안을 공개 카탈로그와 분리해 보관하는 빈 대기 테이블. |
| V35 | `exercise_gif_access` 회원 UUID별 열람 허용 행과 부여 시각. `user_id`는 PK이며 `users.id` 삭제 시 허용 행만 함께 삭제한다. 신규·기존 회원에 허용 행을 생성하지 않는다. |

JPA는 카탈로그, 설정, 루틴과 주요 세션·수행의 17개 테이블을 매핑한다. 다섯 세션 하위 엔티티의 일부 필드는 `nullable=false` 표기 없이 DB의 `NOT NULL` 제약을 따른다. 계정·OTP, 주간 일정, 통계 조회와 디로딩 분석·기간 저장소는 JDBC를 사용한다. 복합 FK, 부분 인덱스와 트리거는 Flyway 정의에서 관리한다. Hibernate는 `ddl-auto=validate`이며 Spring Session의 자체 초기화는 비활성화되어 있다. [세션 하위 엔티티](../../src/main/java/com/overload/overload_server/workoutsession/WorkoutSnapshotEntities.java) · [런타임 설정](../../src/main/resources/application.yaml) · [마이그레이션 통합 테스트](../../src/test/java/com/overload/overload_server/database/PostgresMigrationTests.java) · [Flyway SQL 전체](../../src/main/resources/db/migration/)

이전 논리 모델과 카탈로그 도입안은 작성 시점의 설계 근거다. 최종 물리 구조는 위 테이블별 근거 migration을 따른다. [Task 006-A 논리 모델](../tasks/task-006-model-mapping-report.md) · [표준 운동 카탈로그 도입안](../tasks/standard-exercise-catalog-design-analysis.md)

## 일반 운동 UI/API 연결에 따른 스키마 확인 — 2026-11-13

Backend a56103e는 기존 V35 관계를 사용하며 신규 migration/컬럼/테이블을 만들지 않았다. standard_exercise는 운동 종류, manufacturer는 독립 장비 제조사다. routine_weight_placements.manufacturer_id/load_type과 performed_sets.manufacturer_id/load_type은 배치 및 완료 당시 장비를 보존한다. 표준 이름을 제조사/모델별로 복제하는 데이터 정책을 적용하지 않는다. [검토 시드30종 표준화](../engineering/standard-exercise-handoff.md#일반-운동-검수-후-표준화--2026-11-13)는 스키마 변경이나 전체 운영 데이터 전환을 뜻하지 않는다.

workout_sessions의 최초 completion_assessment 보존과 record_change_events의 수행 변경 전후 기록은 유지한다. progress-assessment와 weightReview는 저장 사실에서 읽기 계산하는 DTO이며 DB 열이 아니다. weightReview는 공식 benchmark 자격/승인 사건을 바꾸지 않는다. 기구 세팅 메모 서버 컬럼은 없고 PWA 해당 기기·세션만 보관한다. 개인/보존 DB·이미 적용된 Flyway·사용자 데이터를 이번 문서 단계에서 변경하지 않았다.
