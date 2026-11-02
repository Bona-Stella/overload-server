# Overload — AGENTS.md

> Common Engineering Rules for AI Coding Agents
>
> Applies to: `overload-server`, `overload-pwa`
>
> Project: Overload — Weight Training Tracker
>
> Status: Active Engineering Guidelines

---

## 1. Purpose

이 문서는 Overload 프로젝트에서 AI 코딩 에이전트가
따라야 할 공통 개발 규칙과 작업 절차를 정의합니다.

AI 에이전트는 단순히 코드를 생성하는 것이 아니라,
기존 구조를 이해하고, 최소한의 변경으로 요구사항을 구현하며,
검증 가능한 결과를 제공해야 합니다.

모든 작업은 다음 원칙을 따릅니다.

**Understand → Plan → Implement → Verify → Review → Report**

### Core Principles

1. Correctness over convenience
2. Data integrity over implementation speed
3. Security by design
4. Simplicity over unnecessary abstraction
5. Consistency over personal preference
6. Maintainability over short-term optimization
7. Verified results over assumptions

---

## 2. Project Context

Overload는 웨이트 트레이닝 기록 및 관리 서비스입니다.

사용자는 운동 종목, 중량, 반복 횟수, 세트,
운동 루틴 및 운동 이력을 관리하고,
기록의 변화를 통해 운동 성과를 추적할 수 있습니다.

### Repositories

| Repository | Responsibility |
|:---|:---|
| `overload-server` | REST API 및 데이터 관리 |
| `overload-pwa` | 사용자 인터페이스 및 PWA |
 
프론트엔드 저장소명은 `overload-pwa`이며,
`overload-app`은 해당 저장소의 `package.json`에 정의된 npm 프로젝트명입니다.
기술 문서에서 프론트엔드를 `overload-app`으로 지칭하는 경우에도
이 저장소를 의미하며, 별도 저장소로 간주하지 않습니다.

### Technology Direction

Backend:
- Java
- Spring Boot
- Spring Data JPA
- PostgreSQL

Frontend:
- React
- TypeScript
- Vite
- PWA

Communication:
- HTTPS
- REST API
- JSON

위 목록과 기술별 문서의 기술 스택은 개발 방향을 포함하며,
모든 기술이 현재 설치되거나 기능으로 구현되었다는 의미는 아닙니다.

- 현재 도입된 기술과 버전은 각 저장소의 빌드 설정, Lockfile,
  관련 설정 및 실제 구현을 확인하여 판단합니다.
- 아직 도입되지 않은 기술과 기능은 도입 예정 또는 검토 대상으로 구분합니다.
- 주석 처리된 의존성이나 문서의 사용 지침만으로 도입 완료로 판단하지 않습니다.
- 예정된 도구를 사용하기 위해 임의로 설치하지 않고,
  의존성 검토 및 사전 승인 기준을 적용합니다.

README에 작성된 예정 기능을
이미 구현된 기능으로 간주하지 않습니다.

---

## 3. Required Engineering Documents

AI 에이전트는 코드 변경 전에
현재 저장소의 적용 규칙을 확인해야 합니다.

### Common Rules

Required:
- `AGENTS.md`

### Task 009 Current Design

통계 2B는 [진행 보고서](docs/tasks/statistics-tracking-phase-2b-report.md), [주간 일정 계약](docs/api/weekly-schedule.md), [통계 overview](docs/api/statistics.md)를 따릅니다. 최신 제품 정책은 [Statistics and Tracking v1.3](docs/product/Overload_Statistics_and_Tracking_Design_Specification_v1.3.md)입니다. 일정 최초 등록은 당일부터 즉시 적용하며 첫 주는 등록일~일요일 예정일만 분모에 포함하고, 기존 요일·빈도 변경은 다음 월요일, 동일 예정일은 최고 단일 세션 InitialPlan 달성률을 사용합니다. V19는 이력2표, V20은 최초 version의 평일 적용 제약만 보완하며 과거 일정 백필은 없습니다. 개인 DB V18·V19·V20 미적용·운영 분류 미검증 상태를 유지합니다. 이력 없는 날짜·실제 유산소 목표 증거 부족은 null/확인 불가이며 추정하지 않습니다. 2A endpoint 계약은 그대로 유지합니다.

보존된 통계 2A `/api/v1/statistics` 계약은 [진행 보고서](docs/tasks/statistics-tracking-phase-2a-report.md)와 [읽기 API](docs/api/statistics.md)를 따릅니다. 완료 기록의 실제 집계와 종료 세션 InitialPlan 계획만 구현했으며 주간 전체 계획/달성률은 null입니다. 성장선·실제 속도/계산 거리 저장·디로딩 연결을 완료로 간주하지 않습니다. 개인 DB V18 미적용·운영 분류 검토 미완료를 유지합니다.

통계·추적 및 디로딩 작업은 [통계 v1.3](docs/product/Overload_Statistics_and_Tracking_Design_Specification_v1.3.md), [디로딩 v1.3](docs/product/Overload_Deloading_Design_Specification_v1.3.md)과 [0·1단계 조사·정비 보고서](docs/tasks/statistics-deload-phase-0-1-report.md)를 함께 확인합니다. 최신 통계는 PRIMARY/SECONDARY를 분리하며 보조근 0.5 환산을 사용하지 않습니다. V18은 nullable joint_type 구조이며 NULL은 미분류, OTHER는 명시적 해당 없음입니다. 개인 DB V18 적용·정확한 운영 카탈로그 분류·통계/디로딩 기능 구현은 완료로 간주하지 않습니다. 미시작 과거 예정일의 계획을 임의 추정하지 않습니다. CHECK-08은 2026-11-02 사용자 결정으로 확정했습니다. Legs 없는 3일 균등형은 원본 유산소 주 1일이면 첫 운동일, 주 2일 이상이면 앞의 두 운동일에 하루 1종목씩 배치하며 구현은 후속입니다.

표준 운동 연결은 [최종 카탈로그 설계](docs/tasks/standard-exercise-catalog-design-analysis.md)와
[Backend 2단계 보고서](docs/tasks/standard-exercise-catalog-backend-phase-2-report.md)를 따릅니다.
V17부터 신규 루틴·세션은 standardExerciseId 단일 계약과 계획/평가 schemaVersion=2를 사용합니다.
장비 변경은 승인 중량·공식 비교 키를 바꾸지 않으며 완료 세트의 장비는 불변입니다.
개인 운동 seed는 중단하고 일반 개인 POST/PATCH는403으로 차단합니다.
V17의 격리 검증과 개인 DB 적용은 구분합니다. 2026-11-02 사용자 승인으로 기존 `127.0.0.1:5433/overload_dev`에 V16·V17을 정상 적용하고 개발용 카탈로그를 등록했습니다. [4단계 실제 적용·통합 검증 보고서](docs/tasks/standard-exercise-catalog-dev-phase-4-report.md)를 따릅니다. 기존 migration/이력·볼륨은 보존했으며 다른 DB나 후속 파괴적 변경은 별도 승인이 필요합니다.
Frontend/IndexedDB3·schema2 전환과 실제 앱 연동을 확인했습니다. 오프라인 접근 허용은 갱신을 직렬화하고 동일 계정·세대의 이미 허용된 nonce를 유지하며, 로그아웃·계정/세대 변경은 계속 즉시 차단합니다.

Task 009 및 이후 운동 쓰기·진행 세션·복구 작업 시 필수:
- [Task 009 현행 설계 v1.0](docs/tasks/task-009-redesign-v1.0.md)

일반 관리는 온라인 API, 진행 운동 보호는 전체 확정 스냅샷과 IndexedDB를 기준으로 합니다.
폐기된 범용 명령·Outbox·필드 병합·영수증/feed 구조를 다시 구현하지 않습니다.
일반 온라인 종목 생성·이름 변경·휴식 설정 변경은 [온라인 쓰기 API 계약](docs/api/workout-online-write.md)을 따릅니다.
루틴 관리 API는 [루틴 온라인 계약](docs/api/routine-management.md)을 따릅니다. 개인 개발 DB는 2026-11-01 사용자 승인으로 기존 테스트 데이터를 폐기하고 V15까지 재구성했습니다. [실제 재구성 보고서](docs/tasks/task-009-dev-v15-rebuild-report.md)를 따릅니다.
세션 START·조회·takeover(V12), snapshot·승인 요청/해결(V13), FINISH·최초 평가·AUTO_CLOSED(V14), 종료 후 정정·명시 복구·논리 삭제·현재 평가·승인 해결(V15)은 [세션 계약](docs/api/workout-sessions.md)을 따릅니다. V12~V15의 격리 검증과 개인 개발 DB의 승인된 V15 신규 구성을 완료했습니다. 다른 DB나 후속 파괴적 변경은 별도 승인이 필요합니다.
Frontend/IndexedDB/PWA 연결은 구현되어 있습니다. 조회·중복·takeover·실제 무변경 snapshot·승인 해결은 마지막 운동 동기화 시각을 연장하지 않습니다. 종료 후 쓰기는 expectedVersion으로 분리하고, 봉인 계획/평가와 최초 수행 승인 문맥을 유지합니다. 확인 불가 복구 주장은 UNVERIFIED입니다.
2026-10-31 사용자 승인으로 개인 로컬 `127.0.0.1:5433/overload_dev`에 V9/V10을 적용하고 기존 데이터를 보존했습니다.
근거는 [실제 적용 보고서](docs/tasks/task-009-v10-local-application-report.md)를 따릅니다. 다른 DB나 후속 파괴적 마이그레이션은 이 승인에 포함되지 않습니다.
Task 006의 운동·인증 정책은 v1.0에서 명시적으로 바꾼 부분을 제외하고 유지합니다.

### Task 006 Domain and Authentication Design

Task 007 관련 작업 시 필수:
- [Task 006 최종 설계 v0.3.1](docs/tasks/task-006-integrated-design-v0.3.1.md)

Task 009 v1.0에서 유지하는 운동 도메인·인증 정책은 이 명세를 따릅니다.
상세 운동 정책은 AGENTS.md에 중복 작성하지 않습니다.
확정 정책, 현재 구현 상태 및 남은 기술 계약은 구분합니다.

### Backend Rules

Repository: `overload-server`

Required:
- `docs/engineering/backend.md`

Backend 작업 시 해당 문서를 읽고
Java, Spring Boot, JPA, PostgreSQL,
API 및 테스트 규칙을 적용합니다.

### Frontend Rules

Repository: `overload-pwa`

Required:
- `docs/engineering/frontend.md`

Frontend 작업 시 해당 문서를 읽고
React, TypeScript, 상태 관리,
PWA, 모바일 UI 및 데이터 동기화 규칙을 적용합니다.

### Document Resolution

1. 현재 저장소를 확인합니다.
2. 루트 AGENTS.md를 읽습니다.
3. 해당 저장소의 기술별 규칙을 읽습니다.
4. 관련 코드, 설정, 테스트 및 문서를 확인합니다.
5. 필요한 범위의 규칙을 적용합니다.

다른 저장소의 문서를
현재 저장소에 존재한다고 가정하지 않습니다.

문서가 누락된 경우 이를 보고하고,
확인되지 않은 정책을 임의로 확정하지 않습니다.

---

## 4. Rules and Decision Priority

개발 중 서로 다른 요구사항이나 규칙이
충돌할 수 있습니다.

다음 원칙을 적용합니다.

1. 보안, 데이터 무결성 및 명시적인 제약을 준수합니다.
2. 사용자의 현재 요청과 완료 조건을 확인합니다.
3. Task 009 v1.0 현행 설계와 그 문서가 유지하는 Task 006 운동·인증 및 기존 API 계약을 존중합니다.
4. 공통 개발 규칙을 적용합니다.
5. 저장소별 기술 규칙을 적용합니다.
6. 기존 코드의 일관된 구현 패턴을 참고합니다.

기술별 규칙은 공통 규칙을 구체화합니다.

운동 쓰기·진행 세션·복구에서 기존 개발 문서와 충돌하면 Task 009 v1.0을 우선합니다.
그 밖의 Task 006 운동·인증 확정 정책은 유지합니다.
명세에 남아 있는 미확정 기술 계약을 임의로 확정하거나 미구현 기능을 완료로 간주하지 않습니다.

규칙 사이에 실질적인 충돌이 발생하면
임의로 판단하지 않고 충돌 내용과 영향을 보고하여 필요한 사항만 확인합니다.
충돌과 무관하게 진행 가능한 작업은 계속 수행합니다.

확정되지 않은 설계 사항은
기존 구현만을 근거로 무조건 확정하지 않습니다.

---

## 5. Standard Agent Workflow

### Autonomous Execution

사용자가 요청한 범위에서 일반적인 기능 구현, 테스트 작성 및 수정,
빌드, 오류 수정, 관련 문서 갱신과 자체 리뷰는 중간 승인 없이 수행합니다.
단계가 바뀌거나 계획을 공유했다는 이유만으로 사용자 응답을 기다리지 않습니다.

사전 승인 기준은 15절의 Restricted Operations를 따르며,
기술별 문서의 추가적인 명시적 승인 조건도 적용합니다.
승인이 필요한 작업만 보류하고 독립적으로 수행 가능한 작업은 계속합니다.
확정되지 않은 API·도메인·보안 정책을 임의로 결정할 권한은 포함하지 않습니다.

모든 변경은 다음 절차를 따릅니다.

### Step 1. Understand

작업 전 다음 사항을 확인합니다.

- 사용자의 요청사항
- 기대하는 결과와 완료 조건
- 적용되는 개발 규칙
- 현재 프로젝트 구조
- 관련 구현 및 의존성
- 관련 테스트 코드
- 기존 API 및 데이터 계약
- 변경에 따른 영향 범위

관련 파일을 확인하지 않은 상태에서
프로젝트 구조나 구현 방식을 추측하지 않습니다.

### Step 2. Plan

작업 계획에는 다음을 고려합니다.

- 해결할 문제
- 변경 대상 파일
- 필요한 구현 방법
- 다른 기능에 미치는 영향
- 데이터 변경 가능성
- 보안 및 호환성 위험
- 검증 방법

작업 계획은 내부적으로 수립하며,
단순한 작업은 짧게, 복잡한 작업은 단계별로 정리합니다.
필요한 진행 안내를 제공하되 계획 공유 자체를 승인 요청으로 취급하지 않습니다.
사전 승인 조건이 없으면 구현부터 검증 및 자체 리뷰까지 계속 수행합니다.

### Step 3. Implement

- 요청된 범위에서만 변경합니다.
- 기존 아키텍처와 코드 스타일을 존중합니다.
- 가능한 한 작고 독립적인 변경을 수행합니다.
- 불필요한 의존성을 추가하지 않습니다.
- 관련 없는 코드를 함께 리팩터링하지 않습니다.
- 데이터 손실 가능성을 고려합니다.
- 기존 기능의 호환성을 유지합니다.

### Step 4. Verify

변경 내용에 따라 다음을 검증합니다.

- 컴파일 또는 TypeScript 타입 검사
- 코드 포맷 및 정적 분석
- 단위 테스트
- 통합 테스트
- 프로젝트 빌드
- 주요 예외 및 경계 조건
- 보안 및 데이터 무결성

검증 명령은 실제 프로젝트 설정을 기준으로 실행합니다.

존재하지 않는 스크립트, 테스트 또는
검증 도구를 설치된 것으로 가정하지 않습니다.

### Step 5. Review

작업 완료 전 자체 검토를 수행합니다.

- 요구사항을 충족하는가?
- 기존 기능이 영향을 받는가?
- 불필요한 코드가 추가되었는가?
- 예외 처리가 누락되었는가?
- 인증 및 권한 검증이 적절한가?
- 데이터가 손실될 가능성이 있는가?
- 테스트 및 문서 업데이트가 필요한가?
- 변경 범위가 과도하지 않은가?

### Step 6. Report

모든 작업 완료 후 다음을 보고합니다.

18절의 보고 형식에 따라 변경 사항, 검증 결과, 해결한 오류,
승인 요청 여부 및 미해결 문제를 보고합니다.

실행하지 않은 테스트를
성공했다고 보고하지 않습니다.

---

## 6. Coding Standards

### 6.1 Readability

- 가독성을 우선합니다.
- 의도가 드러나는 이름을 사용합니다.
- 의미 없는 약어를 지양합니다.
- 복잡한 로직은 적절한 단위로 분리합니다.
- 코드를 이해하는 데 불필요한 추상화를 피합니다.

### 6.2 Naming

- 코드 식별자는 영어로 작성합니다.
- 도메인 용어를 일관되게 사용합니다.
- 기술별 네이밍은 해당 Engineering 문서를 따릅니다.
- 같은 개념에 여러 이름을 혼용하지 않습니다.

### 6.3 Single Responsibility

- 함수와 모듈은 명확한 책임을 갖습니다.
- 비즈니스 로직과 표현 로직을 구분합니다.
- 서로 다른 관심사를 불필요하게 결합하지 않습니다.
- 책임이 과도하게 증가하면 분리를 검토합니다.

### 6.4 Duplication

- 중복된 비즈니스 로직을 방치하지 않습니다.
- 재사용성이 확인된 코드는 적절하게 공통화합니다.
- 단순한 중복 제거를 위해 과도한 추상화를 만들지 않습니다.
- 기존 공통 기능을 우선적으로 확인합니다.

### 6.5 Comments

- 코드가 의도를 드러내도록 작성합니다.
- 복잡한 정책이나 설계 의도를 주석으로 설명합니다.
- 코드 동작을 그대로 반복하는 주석은 지양합니다.
- 코드 수정 시 관련 주석도 갱신합니다.

### 6.6 Dependencies

- 기존 의존성을 우선 활용합니다.
- 신규 의존성 추가 시 필요성을 검토합니다.
- 라이브러리 호환성과 유지보수 상태를 확인합니다.
- 안정적인 버전을 우선 사용합니다.
- 불필요한 대규모 버전 업데이트를 수행하지 않습니다.

주요 의존성 추가 여부와 영향은 도입 전에 검토하고,
15절의 사전 승인 기준을 적용합니다.

---

## 7. Architecture Principles

### 7.1 Separation of Concerns

- 각 계층과 모듈의 책임을 명확히 합니다.
- 비즈니스 로직과 데이터 접근 로직을 구분합니다.
- 컴포넌트 및 모듈 간 결합도를 낮춥니다.
- 순환 의존성을 피합니다.
- 기존 프로젝트 구조를 존중합니다.

### 7.2 Minimal Architecture

다음과 같은 과도한 구조를 필요 없이 도입하지 않습니다.

- 불필요한 추상화 계층
- 의미 없는 인터페이스
- 과도한 공통 Base 클래스
- 복잡한 이벤트 시스템
- 불필요한 마이크로서비스 분리
- 범용화를 위한 범용화

### 7.3 Architecture Changes

아키텍처 변경이 필요한 경우:

1. 기존 구조를 분석합니다.
2. 변경 이유를 명확하게 설명합니다.
3. 대안을 검토합니다.
4. 영향 범위를 확인합니다.
5. 필요한 테스트를 수행합니다.
6. 관련 문서를 업데이트합니다.

대규모 아키텍처 변경은 15절의 사전 승인 기준을 적용합니다.

---

## 8. Shared Domain Rules

백엔드와 프론트엔드는
동일한 도메인 개념을 사용해야 합니다.

### Domain Terminology

| Domain | Meaning |
|:---|:---|
| User | 사용자 |
| Exercise | 운동 종목 |
| WorkoutSession | 운동 세션 |
| WorkoutExercise | 세션 내 운동 종목 |
| WorkoutSet | 개별 운동 세트 |
| Routine | 운동 루틴 |
| RoutineExercise | 루틴 내 운동 종목 |
| Volume | 운동 볼륨 |
| Personal Record | 개인 최고 기록 |

### Domain Consistency

- 동일한 도메인에 서로 다른 의미를 부여하지 않습니다.
- 백엔드와 프론트엔드의 데이터 단위를 일치시킵니다.
- 중량은 기본적으로 kg 단위를 사용합니다.
- 0과 값이 없는 상태를 구분합니다.
- 날짜와 시각을 구분합니다.
- 시간대 정보를 명확하게 처리합니다.
- 운동 기록의 소유권을 명확하게 관리합니다.

### Calculation Rules

운동 볼륨, PR 및 통계 기준은
공통 도메인 계약을 기준으로 구현합니다.

계산 기준이 확정되지 않았다면
임의로 정책을 결정하지 않습니다.

서버에서 공식 계산을 담당하는 경우
프론트엔드는 해당 결과를 기준으로 표시합니다.

---

## 9. API Contract Rules

Overload는 프론트엔드와 백엔드를
REST API로 연결합니다.

### API Principles

- API 요청 및 응답 계약을 명확하게 유지합니다.
- JSON 데이터 타입과 필드 이름을 일관되게 사용합니다.
- API 변경 시 다른 저장소에 미치는 영향을 고려합니다.
- Breaking API Change는 영향과 호환성 대안을 검토하고 15절에 따라 사전 승인을 받습니다.
- 존재하지 않는 API를 구현된 것으로 가정하지 않습니다.
- 임의의 응답 구조를 확정된 계약으로 취급하지 않습니다.

### Contract Changes

API 계약 변경 시 다음을 검토합니다.

1. Endpoint 및 HTTP Method
2. Request DTO
3. Response DTO
4. Validation
5. Error Response
6. Authentication / Authorization
7. Backward Compatibility
8. Frontend / Backend Impact

현재 저장소에서 다른 저장소를
직접 수정할 수 없는 경우
필요한 연동 변경 사항을 결과에 명시합니다.

### Source of Truth

확정된 API 명세가 존재하면 이를 기준으로 사용합니다.

별도 API 명세가 없는 경우
현재 백엔드 구현과 관련 테스트를 확인합니다.

README의 API 예시는
최종 계약으로 간주하지 않습니다.

---

## 10. Data Integrity and Safety

사용자의 운동 기록은 서비스의 핵심 데이터입니다.

### Basic Rules

- 기록의 정확성을 우선합니다.
- 데이터 변경 전에 영향을 분석합니다.
- 중복 데이터 생성 가능성을 고려합니다.
- 데이터 삭제 및 덮어쓰기 작업을 신중하게 수행합니다.
- 다른 사용자 데이터에 접근하지 못하도록 합니다.
- 서버 데이터와 클라이언트 데이터의 일관성을 고려합니다.

### Offline Data

PWA에서 오프라인 데이터를 다루는 경우:

- 로컬 저장과 서버 저장을 구분합니다.
- 서버 저장 성공 전에는 저장 완료라고 표시하지 않습니다.
- 실패한 요청으로 입력 데이터를 불필요하게 삭제하지 않습니다.
- 중복 전송 및 충돌 가능성을 고려합니다.
- 브라우저 저장소를 영구 저장소로 가정하지 않습니다.

오프라인 동기화는 개발 단계에 따라 도입합니다.

초기 MVP에서 구현되지 않은 기능을
이미 지원하는 것으로 표현하지 않습니다.

---

## 11. Security Rules

### Authentication & Authorization

- 보호 대상 기능에는 적절한 인증 및 인가를 적용합니다.
- 사용자 ID를 클라이언트 입력만으로 신뢰하지 않습니다.
- 사용자별 데이터 접근 권한을 검증합니다.
- 인증 실패와 권한 부족을 구분합니다.
- 보안 기능을 임의로 우회하지 않습니다.

### Secret Management

다음 정보를 저장소에 커밋하지 않습니다.

- Password
- API Secret
- Access Token
- Refresh Token
- Private Key
- Database Credential
- Production Secret

민감한 정보는 환경변수 또는
적절한 비밀 관리 수단으로 관리합니다.

### Input and Output Safety

- 외부 입력을 신뢰하지 않습니다.
- 서버에서 입력 검증을 수행합니다.
- 민감한 정보를 로그에 출력하지 않습니다.
- 내부 시스템 정보를 외부 오류 응답에 노출하지 않습니다.
- 프론트엔드에서 XSS 위험을 고려합니다.
- 의도하지 않은 개인정보 노출을 방지합니다.

---

## 12. Error Handling and Logging

### Error Handling

- 예상 가능한 오류를 명시적으로 처리합니다.
- 오류를 조용히 무시하지 않습니다.
- 정상 처리와 실패 처리를 명확히 구분합니다.
- 문제를 해결할 수 있는 오류 메시지를 제공합니다.
- 내부 구현 상세정보를 사용자에게 노출하지 않습니다.

### Logging

- 문제를 추적할 수 있는 정보를 기록합니다.
- 과도한 로그를 생성하지 않습니다.
- 개인정보 및 인증 정보를 기록하지 않습니다.
- 기술별 로깅 방식은 전용 개발 규칙을 따릅니다.

---

## 13. Testing and Verification

### Testing Principles

- 핵심 비즈니스 로직에는 적절한 테스트를 작성합니다.
- 버그 수정 시 가능한 경우 회귀 테스트를 추가합니다.
- 정상 및 실패 시나리오를 함께 검증합니다.
- 테스트의 독립성과 재현성을 유지합니다.
- 테스트 실패를 무시하지 않습니다.
- 테스트를 통과시키기 위해 요구사항을 임의로 변경하지 않습니다.

### Verification Requirements

변경 범위에 따라 다음을 검증합니다.

- Static Analysis
- Type Checking / Compilation
- Unit Test
- Integration Test
- Build Verification
- Regression Test

### Backend Verification

`overload-server`에서는
Gradle Wrapper와 실제 프로젝트 설정을 확인합니다.

예시:

```bash
./gradlew test
./gradlew build
```

### Frontend Verification

`overload-pwa`에서는
`package.json`에 정의된 스크립트를 확인합니다.

예시:

```bash
npm run lint
npm run build
npm run test
```

위 명령은 예시입니다.

실제로 존재하지 않는 명령은
실행 가능한 것으로 가정하지 않습니다.

### Verification Failures

검증에 실패한 경우:

1. 실패 내용을 확인합니다.
2. 변경 코드와의 관련성을 분석합니다.
3. 기존 실패와 이번 변경으로 발생한 실패를 구분합니다.
4. 요청 범위 안에서 사전 승인 조건에 해당하지 않는 문제는
   중간 승인 없이 수정하고 관련 검증을 다시 실행합니다.
5. 테스트나 검사를 임의로 비활성화하거나 요구사항을 바꾸어 실패를 숨기지 않습니다.
6. 수정에 사전 승인이 필요하면 해당 작업만 보류하고,
   승인 요청과 해결하지 못한 문제를 보고합니다.

검증이 불가능한 경우
그 사유와 검증하지 못한 범위를 명시합니다.

### Local Process Cleanup

- 로컬 개발 서버, 미리보기 서버 또는 테스트 프로세스를 실행할 때
  자신이 시작한 프로세스의 PID나 실행 세션을 식별할 수 있도록 관리합니다.
- 검증 성공·실패 여부와 관계없이 검증 후 자신이 시작한 프로세스와
  관련 자식 프로세스를 종료하고 종료 여부를 확인합니다. Watch 모드도 포함합니다.
- 기존에 사용자가 실행한 프로세스는 임의로 종료하지 않습니다.
- 사용자가 계속 실행하도록 명시한 경우에는 유지한 프로세스를 보고합니다.
  종료하지 못한 경우에도 그 사실과 원인을 보고합니다.

---

## 14. Documentation Rules

### Document Responsibilities

| Document | Responsibility |
|:---|:---|
| `README.md` | 프로젝트 소개 및 실행 방법 |
| `AGENTS.md` | 공통 AI 개발 규칙 |
| `docs/engineering/backend.md` | 백엔드 전용 규칙 |
| `docs/engineering/frontend.md` | 프론트엔드 전용 규칙 |
| `docs/architecture/` | 주요 설계 결정 |
| `docs/api/` | API 계약 및 명세 |
| `docs/plans/` | 복잡한 작업의 실행 계획 |

문서 경로는 권장 구조이며
실제로 필요한 문서부터 생성합니다.

### Documentation Principles

- 문서는 실제 구현과 일치해야 합니다.
- 구현되지 않은 기능은 예정 상태로 구분합니다.
- 중요한 설계 결정을 문서화합니다.
- API 변경 시 관련 계약 문서를 갱신합니다.
- 불필요한 문서를 대량 생성하지 않습니다.
- 기존 문서를 무분별하게 복제하지 않습니다.

### Engineering Decision

중요한 설계 결정은 다음을 기록합니다.

- 문제 및 배경
- 선택한 해결책
- 대안 및 트레이드오프
- 변경에 따른 영향
- 검증 방법

모든 사소한 구현에
별도 설계 문서를 작성할 필요는 없습니다.

---

## 15. Git and Change Management

### Commit Convention

Conventional Commits 형식을 사용합니다.

| Type | Purpose |
|:---|:---|
| feat | 새로운 기능 |
| fix | 버그 수정 |
| refactor | 리팩터링 |
| docs | 문서 변경 |
| test | 테스트 변경 |
| chore | 유지보수 |
| perf | 성능 개선 |
| build | 빌드 및 의존성 |
| ci | CI/CD 구성 |

Examples:

```text
feat: add workout session creation
fix: prevent duplicate workout sets
refactor: simplify exercise service
test: add workout validation tests
docs: update engineering guidelines
```

### Git Rules

- 목적이 다른 변경을 불필요하게 혼합하지 않습니다.
- 불필요한 빌드 결과물을 커밋하지 않습니다.
- Lockfile을 임의로 삭제하지 않습니다.
- 기존의 사용자 변경 사항을 덮어쓰지 않습니다.
- 위험한 Git 명령을 무분별하게 실행하지 않습니다.
- 명시적인 지시 없이 원격 Push를 수행하지 않습니다.

### Restricted Operations

다음 작업은 실행 전에 사용자의 명시적인 승인을 받습니다.

- 파괴적인 DB Migration 및 기존 데이터 삭제·덮어쓰기 등 데이터 손실 위험이 있는 작업
- 대규모 아키텍처 변경
- 호환성을 깨는 API 변경 (Breaking API Change)
- 인증 및 보안 정책의 중대한 변경
- 운영 환경 설정 변경
- 프로덕션 배포
- 원격 저장소 Push
- 기존 작업을 삭제하는 Git 작업
- 새로운 주요 의존성 추가
- 사용자 요청 범위를 넘어서는 기능 추가나 변경 대상 확대

주요 의존성은 핵심 프레임워크, 데이터 저장소, 인증 도구,
빌드·테스트 체계 등 구조·보안·운영·유지보수에 큰 영향을 주는 의존성을 말합니다.
추가 전 필요성, 기존 도구로 가능한 대안, 호환성 및 영향을 검토합니다.
요청 범위 안의 세부 구현 선택이나 필요한 테스트 보완만으로
작업 범위가 확대되었다고 판단하지 않습니다.
격리된 일회성 테스트 데이터의 생성·정리는 일반 검증에 포함하되,
대상과 격리를 확인하고 기존 사용자·공유·운영 데이터를 변경하지 않습니다.

승인 요청에는 필요한 작업, 이유, 영향 범위, 위험과 대안을 제시하고
해당 작업은 응답을 받을 때까지 실행하지 않습니다.
일반적인 개발 요청이나 응답이 없는 상태를 위험 작업의 승인으로 간주하지 않습니다.
현재 요청이나 같은 작업에서 이미 명시적으로 승인된 범위는 재승인 없이 진행하되,
위험이나 범위가 달라지면 다시 승인을 받습니다.

---

## 16. Scope and Change Control

### Minimal Changes

- 요청 범위를 벗어나는 변경을 최소화합니다.
- 무관한 코드를 동시에 리팩터링하지 않습니다.
- 새 의존성은 필요성을 검토합니다.
- 이미 존재하는 기능을 중복 구현하지 않습니다.
- 확정되지 않은 미래 기능을 미리 구현하지 않습니다.

작업 범위 확대가 필요하면 영향을 먼저 검토하고 15절에 따라 승인을 받습니다.
승인을 기다리는 동안 기존 범위에서 안전하게 완료할 수 있는 작업은 진행합니다.

### Existing Changes

- 기존 변경 사항을 먼저 확인합니다.
- 다른 작업의 변경 사항을 임의로 되돌리지 않습니다.
- 충돌이 발생하면 원인을 확인합니다.
- 필요한 경우 사용자에게 영향을 보고합니다.

### No Assumptions

다음 사항을 임의로 가정하지 않습니다.

- 구현되지 않은 API
- 존재하지 않는 DB 테이블
- 설치되지 않은 라이브러리
- 설정되지 않은 테스트 스크립트
- 확정되지 않은 도메인 정책
- 구성되지 않은 배포 환경
- 존재하지 않는 CI 워크플로

---

## 17. Definition of Done

작업이 완료되었다고 판단하기 전에
다음 사항을 확인합니다.

### Functional

- [ ] 요청한 요구사항을 충족했는가?
- [ ] 예상한 동작이 올바르게 구현되었는가?
- [ ] 예외 및 경계 조건을 고려했는가?
- [ ] 기존 기능에 불필요한 영향이 없는가?

### Architecture

- [ ] 기존 아키텍처를 준수했는가?
- [ ] 계층 및 모듈 책임이 명확한가?
- [ ] 불필요한 추상화가 없는가?
- [ ] 새로운 의존성이 필요한가?

### Data and Security

- [ ] 데이터 무결성을 유지하는가?
- [ ] 사용자별 데이터 접근이 안전한가?
- [ ] 민감 정보가 노출되지 않는가?
- [ ] 데이터 손실 가능성을 고려했는가?

### Quality

- [ ] 적절한 테스트를 작성하거나 수정했는가?
- [ ] 실행 가능한 검증을 수행했는가?
- [ ] 빌드 및 정적 분석 결과를 확인했는가?
- [ ] 관련 문서를 갱신했는가?
- [ ] 검증하지 못한 항목을 보고했는가?
- [ ] 자신이 실행한 검증용 프로세스의 종료 여부를 확인했는가?
- [ ] 필요한 사전 승인을 받았고 승인 요청 여부와 처리 상태를 보고했는가?

---

## 18. Agent Response Format

작업을 완료한 AI 에이전트는
다음 형식을 기본으로 결과를 보고합니다.

### Summary

- 작업의 주요 결과

### Changes

- 생성 및 수정한 파일
- 주요 구현 내용

### Validation

- 실행한 검증 명령
- 테스트 및 빌드 결과
- 검증 중 발생한 오류, 원인 및 해결 내용 (없으면 없음)
- 실행하지 못한 검증과 이유
- 실행한 로컬 서버·테스트 프로세스의 종료 여부 (해당하는 경우)

### Notes

- 주요 설계 결정
- 승인 요청 여부와 처리 상태 (불필요·승인됨·대기 중) 및 보류한 작업
- 미해결 문제 및 제약사항 (없으면 없음)
- 남은 작업
- 다른 저장소와의 연동 필요사항

작업 규모에 맞게 간결하게 작성합니다.

작업이 완료되지 않았다면
완료되지 않은 상태를 명확하게 보고합니다.

---

## 19. CI/CD Status

현재 GitHub Actions 기반
자동 검증 워크플로는 별도 구축 예정입니다.

따라서:

- CI가 이미 설정되었다고 가정하지 않습니다.
- 자동 테스트가 실행되었다고 보고하지 않습니다.
- 변경 후 실행 가능한 로컬 검증을 수행합니다.
- CI/CD 설정은 별도 작업으로 진행합니다.
- 실제 워크플로가 추가되면 관련 규칙을 갱신합니다.

CI/CD 구성 이후에는 다음 사항을 검토합니다.

- Pull Request 자동 검증
- Lint 및 Static Analysis
- Unit / Integration Test
- Build Verification
- 필수 검증 통과 조건
- 배포 승인 및 권한 관리

현재 단계에서는 자동화 구성을
필수 완료 조건으로 취급하지 않습니다.

---

## 20. Final Principles

AI 에이전트는 프로젝트의 기존 맥락과
확정된 개발 규칙을 우선적으로 이해해야 합니다.

구현은 최소한의 변경으로 진행하고,
품질은 가능한 검증을 통해 확인해야 합니다.

특히 Overload에서는
사용자의 운동 기록을 정확하게 보존하는 것을
중요한 책임으로 취급합니다.

**Understand → Plan → Implement → Verify → Review → Report**

최종 목표는 단순한 코드 생성이 아니라
안전하고 일관되며 유지보수 가능한
소프트웨어를 지속적으로 개발하는 것입니다.
