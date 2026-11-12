# Overload 31종목 카탈로그 등록 계획

첨부한 31종목의 카탈로그와 6일 루틴을 검토 환경에서 복원하는 자료다. `catalog.json`에는 표준 운동과 제조사·기구 참조 및 하이 로우 원본 메타데이터가 있다. 사용자 후속 수정, 원래 완료 반복 수, 개인 기구 설정은 로컬 전용 `handoff.local.json`에 기록한다. 기구 설명은 공개 API에도 전달되는 필드이므로 확인된 기구 정보만 사용한다.

## 실제 운동 데이터로 검토

“더미 데이터 대신 실제 운동 데이터로 확인” 요청에는 이 승인 자료를 사용한다. 전용 V35 PostgreSQL에 표준 운동 31종을 검수 완료로 등록한다. 지정 테스트 계정으로 실제 OTP 인증을 거쳐 루틴 API에 웨이트 33건·유산소 4건을 등록하고 Asia/Seoul 기준 월~토 주간 일정을 활성화한다. 일요일은 미배정이다. 계정에 회원별 GIF 열람 권한을 부여한다. 하이 로우의 원본 출처만 연결하며 실제 GIF 파일 제공과 대표 지정은 별도 권리·미디어 설정에 따른다.

Backend와 PWA를 형제 디렉터리로 두고 Docker Desktop, JDK 25, Node.js와 기존 PWA 의존성을 준비한다. Backend 루트의 PowerShell에서 실행한다.

```powershell
.\docs\dev-data\overload-31-catalog\review.ps1 -Action Start
.\docs\dev-data\overload-31-catalog\review.ps1 -Action Status
.\docs\dev-data\overload-31-catalog\review.ps1 -Action Stop
```

JDK 25 경로를 직접 지정하려면 `Start`에 `-JavaHome`을 전달한다. `Start`가 표시한 루프백 주소에서 **검토 계정으로 로그인**을 누른다. 인증 코드는 로컬 테스트 전달기로만 처리한다. 동일 실행에 다시 접속하면 현재 DB를 사용한다. 검토가 끝나면 `Stop`으로 테스트 서비스와 임시 DB를 종료한다.

### 초기화 후 복원

DB가 없어졌거나 빈 환경에서 다시 검토할 때 `Start`로 새 DB를 준비한다. 아래 JSON을 함께 보관한다. `*.local.json`은 Git에서 제외되므로 PC 이동 시 별도로 복사한다. Docker 볼륨이나 `build/` 안의 실행 로그는 복원 자료가 아니다.

| 파일 | 복원 범위 |
| --- | --- |
| `catalog.json` | 운동 31종, 별칭, 근육 순서, 분할, 기구·제조사, 원본 참조 |
| `handoff.local.json` | 사용자의 검수 승인과 원래 기록·설정 |
| `routine-plans.local.json` | 테스트 계정, GIF 권한, 시간대, 요일별 루틴·중량·러닝 목표 |

복원 시 현재 DB에서 생성된 회원 UUID에 권한을 다시 부여한다. 기존 사용자가 검토 중 새로 입력한 운동 기록이나 루틴 편집은 이 승인 자료에 자동 기록되지 않는다. 원래 수행 반복 수는 인수인계 JSON에 보관하며, 수행 날짜가 정해지지 않은 기록은 자동 생성하지 않는다. V35 이후 DB 계약이 변경되면 동일한 JSON을 기준으로 마이그레이션과 복원 경로를 새 격리 DB에서 먼저 검증한다.

## 미리보기

Backend 저장소 루트의 PowerShell에서 실행한다. Node.js가 출력하는 SQL은 트랜잭션 끝에서 롤백된다.

```powershell
node .\docs\dev-data\overload-31-catalog\preview.mjs
```

첨부 JSON과 ExerciseDB 원본 파일을 첫째·둘째 인수로 지정하면 31종목, 웨이트 29종과 유산소 2종, 루틴 33·4건, 40개 근육명, 하이 로우 원본 729번을 함께 대조한다. 표준명·분할·부하 유형·펜듈럼 스쿼트 제조사는 사용자 후속 결정을 기대값에 반영한다. 파일 경로 인수를 생략하면 저장소의 카탈로그 초안으로 미리보기 SQL을 출력한다. SQL 실행 대상은 이미 검증된 V35 격리 DB를 사용한다. psql에서 파일로 실행할 때는 `-v ON_ERROR_STOP=1`을 지정한다.

## 사용자 승인 및 루틴 적용 준비

- `preview.mjs --approve`는 사용자가 카탈로그 근육·분할 검수를 승인한 로컬 인수인계 파일과 원본 경로를 인수로 받아 롤백하는 SQL을 출력한다. 두 검수 상태를 함께 갱신하기 전에 해당 표준 운동의 명칭, 운동 방법, 기구, 분할, 관절 유형, 별칭, 근육 및 순서가 계획과 일치하는지 검사한다.
- `routine-plans.local.json`은 6일의 루틴 적용값이다. 각 대상의 표준 UUID·제조사 ID와 계정 세대를 확인한 뒤 기존 루틴 API 계약에 맞춰 사용한다. 유산소는 5km 이상 2,100초, 5km 미만 1,200초를 목표 시간으로 사용한다. 야외 러닝의 5~6km 범위는 원본에 보존한다. 루틴의 단일 거리 칸은 비워 두고 페이스를 적용값에 유지한다.
- 실제 적용 전에 대상 DB와 계정, Flyway 이력 및 백업 복원 가능성을 확인한다. 현재 루틴과 활성 일정, 과거 수행 기록은 보존한다.

## 기존 카탈로그 해석

- 표의 UUID는 신규 초안에 사용하는 안정 ID다. 기존 운동의 정확한 동일성을 확인했다면 셋째 인수로 `{"review_key":"기존 UUID"}` 형태의 JSON을 전달해 UUID를 재사용할 수 있다. 같은 이름의 기존 후보를 확인한 결과 별도 운동이면 해당 키의 값을 JSON null로 지정한다. 단순 공백 정규화 또는 비슷한 이름은 검토 신호다.
- 재사용한 운동의 현재 설명·검수·별칭·근육·분할은 보존한다. 필요한 콘텐츠 갱신은 현재 판본과 영향 범위를 대조한 뒤 기존 내부 검수 서비스로 처리한다.
- 머신 숄더 프레스 두 변형은 표준명이 같고 UUID와 제조사 후보가 다르다. 사용자가 갱신한 분류표의 15번 Hammer Strength는 스택형, 18번 Arsenal Strength는 원판형으로 매핑한다. 기존 `manufacturer`의 정확한 이름 후보를 우선 사용한다. V17의 `STACK`, `PLATE`, `DUMBBELL`, `BODYWEIGHT`와 제조사 UUID는 이후 승인된 루틴 배치에 적용할 매핑이며 표준 운동의 전역 기본값으로 사용하지 않는다. 유산소의 표준 UUID는 V23의 기존 트레드밀·야외 `RUNNING` 활동 ID와 각각 연결한다.
- 일반 미리보기에서 두 검수 플래그는 신규 운동에 false다. 승인 미리보기에서 두 플래그를 함께 검증한다. PRIMARY 순서와 SECONDARY 중복 검사에 V32 근육 마스터를 사용한다. 웨이트 29종은 다관절 15종과 단관절 14종으로 분류한다. 머신 업도미널 크런치와 캡틴스 체어 레그 레이즈는 PPL LEGS·전면이다. 유산소 2종은 PPL·전후면·관절 분류를 미지정으로 둔다. 단계별 한국어는 사용자 확인을 바탕으로 작성된 초안이다.
- 카탈로그 검색·자동완성은 NFKC, Unicode 공백 제거, 소문자화를 이름·별칭에 함께 적용한다. DB 별칭 키는 기존 공백 축약 계약을 유지한다. 같은 이름의 결과에는 변형을 구분할 수 있는 별칭을 표시한다.
- 하이 로우에만 `exercise_source`로 원본을 연결한다. 기존 연결이 다른 UUID를 가리키거나 원본 메타데이터가 다르면 트랜잭션을 중단한다. 대표 GIF 지정은 보류한다. 원본의 발판 표현과 사용자가 확인한 허벅지 고정 패드의 관계도 추가 검수 사항이다.

## 근육 후보 검수

Hammer Strength의 원판형 하이 로우 제품 설명은 능형근·승모근·후면삼각근을 포함한 등 상부와 광배근을 언급한다. 초안의 중부승모근 세분화와 후면삼각근의 보조 순위는 검수 후보로 유지했다. [Hammer Strength High Row](https://www.lifefitness.com/en-us/catalog/strength-training/plate-loaded/plate-loaded-iso-lateral-high-row)

Panatta의 Dips Press는 제품 계열별로 어깨 관여 설명이 다르다. Freeweight One은 전면삼각근을, Fit Evo는 후면삼각근을 언급한다. 두 자료 모두 상완삼두근 외측두가 우선이라고 명시하지 않는다. 딥스·프레스·오버헤드 익스텐션의 세부 갈래와 정확한 모델은 검수 대기다. [Freeweight One](https://www.panattasport.com/en/freeweight-one/dips-press/) · [Fit Evo](https://www.panattasport.com/en/fit-evo/dips-press/)

## 표준 UUID 매핑

빈 V35 카탈로그의 신규 초안 경로다. 다른 대상에서의 재사용 UUID는 미리보기 결과로 확인한다.

| 검수 키 | 표준명 | 신규 표준 UUID | 제조사 참조 | 배치 부하 유형 |
| --- | --- | --- | --- | --- |
| pull_convergent_lat_pulldown | 컨버전트 랫풀다운 | 3cd1e160-1eab-8d1c-8dfc-add040a039a4 | Panatta | STACK |
| pull_dy_row | D.Y. 로우 | 47c986a7-a7e6-897e-8692-4afaee5e788f | Hammer Strength | PLATE |
| pull_multi_row | 멀티 로우 | 47f25354-b992-84ae-8a7d-ca2cb27a6075 | Arsenal Strength | PLATE |
| pull_machine_pullover | 머신 풀오버 | 78205b20-c816-8ed4-8b33-71192d75c735 | Technogym | PLATE |
| pull_cable_face_pull | 케이블 페이스 풀 | 006c0c92-33ea-8ee3-8abb-17530ec23fd9 | NewTech | STACK |
| pull_incline_dumbbell_curl | 인클라인 덤벨 컬 | 5c4e9704-0e72-8b99-83f8-bd52b2f5a2e8 | — | DUMBBELL |
| pull_machine_high_row | 머신 하이 로우 | 2d04629b-b651-811b-8e66-3d56102988a8 | Hammer Strength | PLATE |
| pull_circular_row | 서큘러 로우 | 01e72a10-6bb8-8a8e-884c-002b1f39277d | Panatta | STACK |
| pull_mts_front_pulldown | MTS 프론트 풀다운 | c543db1b-ed73-84fe-88eb-3c9082334404 | Hammer Strength | STACK |
| pull_machine_rear_delt_fly | 머신 리어 델트 플라이 | 9f11e362-dbfc-8b1a-897e-2b5406f71930 | NewTech | STACK |
| pull_standing_hammer_curl | 스탠딩 덤벨 해머 컬 | f3ecf8de-67eb-85e1-8cf6-1765512fe23f | — | DUMBBELL |
| push_incline_chest_press | 인클라인 체스트 프레스 | b40244d0-46e2-87fa-8d24-5743d2dda914 | NewTech | PLATE |
| push_super_vertical_chest_press | 슈퍼 버티컬 체스트 프레스 | bd07bf4d-9eac-87f5-8ddf-de7984d44034 | Panatta | PLATE |
| push_incline_fly | 머신 인클라인 플라이 | a65e10f0-146f-891f-87f8-0d2841b0b40d | Arsenal Strength | PLATE |
| push_hammer_shoulder_press | 머신 숄더 프레스 | 32ed1719-0c4b-89d1-8c36-58618013eb8c | Hammer Strength | STACK |
| push_standing_machine_lateral_raise | 스탠딩 머신 레터럴 레이즈 | af156b7e-2496-87c3-8c15-7f31c5878160 | NewTech | STACK |
| push_machine_overhead_triceps_extension | 머신 오버헤드 트라이셉스 익스텐션 | aaa6f7c3-0b02-8a32-8488-e682f39260ae | NewTech | PLATE |
| push_arsenal_shoulder_press | 머신 숄더 프레스 | f6fc6164-3d3c-863e-83d2-0068d1da9aac | Arsenal Strength | PLATE |
| push_machine_dips_press | 머신 딥스 프레스 | c15d6b5b-40c2-8c9d-812a-53ad1db4d01c | Panatta | PLATE |
| push_dumbbell_lateral_raise | 덤벨 레터럴 레이즈 | e3ef7b2e-a8e9-869d-8a08-86b4e022bad3 | — | DUMBBELL |
| push_cable_overhead_triceps_extension | 케이블 오버헤드 트라이셉스 익스텐션 | e113fff3-d45f-8a4e-8d0c-3445914f4929 | NewTech | STACK |
| legs_pendulum_squat | 펜듈럼 스쿼트 | 44160d3b-640b-8062-8cf7-541ae0a5ee54 | Arsenal Strength | PLATE |
| legs_romanian_deadlift | 루마니안 데드리프트 | 73eb4b15-21d2-8a8f-8724-20a0fe912f63 | — | PLATE |
| legs_leg_extension | 레그 익스텐션 | 0f4a3e33-a61e-8f1e-8537-463e334a10ba | NewTech | STACK |
| legs_lying_leg_curl | 라잉 레그 컬 | 34e41144-9213-8c35-87c3-f8be2ad58abe | NewTech | STACK |
| legs_standing_calf_raise | 스탠딩 카프 레이즈 | ccb5bd7f-73e8-8f98-8b1e-96fdabc2ba8b | NewTech | PLATE |
| legs_machine_crunch | 머신 업도미널 크런치 | 85d532b1-009e-84b6-8efd-b19b5d27659f | NewTech | STACK |
| legs_rogers_hip_press | 힙 프레스 | b773e2d2-b363-8602-8c74-1a8443330e4d | Rogers | PLATE |
| legs_captains_chair_leg_raise | 캡틴스 체어 레그 레이즈 | 44d8cc67-2aff-83c9-8423-b7d77f93612d | NewTech | BODYWEIGHT |
| cardio_treadmill_running | 트레드밀 러닝 | 2759cc0f-a8e0-866c-84cd-2a52e0aca65e | Health One | — |
| cardio_outdoor_running | 야외 러닝 | 2ba68fb8-e8b1-8f70-8a4b-715d26ed34e9 | — | — |
