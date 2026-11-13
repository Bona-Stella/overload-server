# Task 009 — 최종 통합 검증 보고서

검증일: 2026-11-01 (Asia/Seoul). 대상: 최종 Backend V15 및 Frontend 5E 커밋.

## 1. 결론과 검증 범위

**Task 009 핵심 구현의 통합 검증은 통과했다.** 최종 코드의 자동 테스트 1,222개가 실패·오류·skip 없이 통과했고, 프로덕션 React/PWA·실제 쿠키/CSRF·IndexedDB·Backend·격리 PostgreSQL을 연결한 브라우저 검증 28개 묶음도 통과했다. 데이터 유실·중복 기록·권한 혼동·최초 평가 불변성 위반은 재현되지 않았다. 제품 코드와 migration 원문을 변경하지 않았다.

이 판정은 **Windows Edge와 격리 PostgreSQL에서의 개발 완료 판정**이다. 실제 HTTPS 배포, iPhone Safari/홈 화면 앱, Android 실기기 설치·잠금·복귀와 개인 DB 적용을 완료했다는 뜻은 아니다. 출시 전 수동 확인과 개인 DB 적용은 다음 단계 승인이 필요하다.

확인한 기준: 양쪽 AGENTS/Engineering, Task 006 v0.3.1의 유지되는 운동·인증 정책, Task 008 조회·공통 HTTP·JPA/JDBC 계약, Task 009 v1.0과 후속 승인 보완, 온라인 쓰기·루틴·세션 API, V11~V15 SQL 및 Frontend 5A~5E 보고서/실제 코드. 과거 Outbox·명령 영수증/feed 설계를 현행 요구사항으로 사용하지 않았다.

## 2. HEAD와 Git 상태

| 저장소 | branch / HEAD | 시작 상태 |
|---|---|---|
| Backend | main / `fc2db21e41637886abfd58e577175f2f33b5ba8b` | clean |
| Frontend | main / `984899c2d5545483026b6014a0361e91a18ade96` | clean |

Frontend 최신 커밋은 `feat(pwa): add offline workout resume and safe app shell`이며 5E 파일·보고서가 커밋되어 있었다. 5E 보고서 말미의 미커밋 표시는 작성 당시의 기록이며 현재 Git 상태와 구분했다. 예상하지 못한 변경 없이 검증을 시작했다.

검증 후 HEAD는 동일하다. Frontend는 clean, Backend는 **이 보고서 1개만 신규 미추적 파일**이다. 추가 하네스·임시 migration 테스트·빌드·로그·브라우저 프로필은 기존 ignored `build/`, `logs/`에만 생성했다. 스테이징·커밋·Push 없음. 의존성·lockfile·기존 테스트·제품 코드·기존 SQL 원문 변경 없음.

## 3. 전체 테스트 및 빌드

| 실행 범위 | 실행 | 통과 | 실패 / 오류 / skip |
|---|---:|---:|---|
| Backend 단위·DB 없는 HTTP/context | 207 | 207 | 0 / 0 / 0 |
| Backend 실제 PostgreSQL 전체 통합 | 577 | 577 | 0 / 0 / 0 |
| 추가 V11→V15 일괄 migration | 1 | 1 | 0 / 0 / 0 |
| Frontend Node·모델·SSR 전체 회귀 | 437 | 437 | 0 / 0 / 0 |
| 합계 | **1,222** | **1,222** | **0 / 0 / 0** |

브라우저의 28개 검증 묶음은 위 자동 테스트 수에 합산하지 않았다. SSR 테스트를 브라우저 DOM 검증으로 표현하지 않는다.

실행 명령:

```powershell
# overload-server; 기존 JDK/Gradle 캐시 사용
$env:JAVA_HOME='C:\Users\Home\.jdks\temurin-25.0.5'
.\gradlew.bat --offline --no-daemon test integrationTest build
# build/에만 만든 추가 검증 sourceSet과 전용 task
.\gradlew.bat --offline --no-daemon -I build/task-009-final.init.gradle finalMigrationTest

# overload-pwa
npm test
npm run lint
npm run build
```

Backend `BUILD SUCCESSFUL`, 5분 47초. `test`·`integrationTest`는 실제 실행됐고 기존 컴파일 4개 task만 UP-TO-DATE였다. 추가 migration 검증은 20초에 통과했다. Frontend lint·TypeScript·Vite 프로덕션 빌드 통과. 실제 설치 Vite는 8.3.5다.

577개 PostgreSQL 테스트의 구성:

| 범위 | 수 |
|---|---:|
| OTP·Google/이메일 신원·재인증 | 315 |
| 계정 프로비저닝·보안 세션/CSRF | 21 |
| migration | 22 |
| JPA/JDBC 혼용 | 17 |
| 루틴 | 23 |
| 기존 운동 조회 | 22 |
| 운동 정의·설정 온라인 쓰기 | 52 |
| START·snapshot·FINISH·AUTO_CLOSED·정정/복구/목록 | 105 |

모든 실제 DB 테스트는 Testcontainers PostgreSQL 18.6이다. 테스트 local overlay는 개인 `.env`를 import하지 않고 fixture URL·합성 비밀값을 사용한다. 실행 셸에 외부 SPRING/OVERLOAD/OTP/GOOGLE/RESEND/SERVER 환경변수도 없음을 확인했다. 실제 Google/Resend 계정 호출은 하지 않았다.

## 4. 신규 설치와 V11→V15 업그레이드

- `PostgresMigrationTests.migratesEmptyDatabaseOnceAndPreservesUsersAcrossRestart`: 빈 DB에서 V1~V15 적용, 24개 테이블·15개 성공 이력·현재 버전15·재부팅 보존·Flyway validate와 재실행 무변경을 확인했다.
- 기존 V11/V12/V13/V14/V15 테스트: 단계별 모든 이전 컬럼값·이력·체크섬, 최초 계획, 승인 요청/사건, 완료 수행, ACTIVE/FINISHED/AUTO_CLOSED 상태를 보존하는 검증이 전부 통과했다.
- 이번 추가 `FinalV11ToV15MigrationTests`: 새 `overload_final_v11_v15_test` DB에 V11까지 적용한 뒤 사용자·개인 종목·설정·루틴·배치·승인 중량·ROUTINE_EDITOR 승인 사건을 저장했다. **V12~V15 네 개를 한 번에 적용**한 뒤 V11의 모든 테이블에 대해 기존 컬럼 전체 행과 Flyway 이력 전체를 비교했다. 배치 ID·initial/approved 중량·승인 사건의 출처/전후 값·계보가 그대로였다. validate 및 두 번째 migrate 실행 수0도 확인했다.
- 추가 검증은 기존 V12 migration fixture를 바탕으로 `build/task-009-final-src/`에만 작성했다. 새 컬럼은 비교에서 분리하고 기존 컬럼을 전부 비교했다. 테스트 DB명과 매핑 포트가 개인 5432/5433이 아님을 assert했다.
- SQL 원문은 Git 기준 변경0이며 V1~V15 SHA-256 목록을 로컬 증빙에 저장했다. checksum repair, history 삭제, clean/baseline 우회는 사용하지 않았다.

**개인 `overload_dev`에는 접속하지 않았고 migration을 적용하지 않았다.** 따라서 개인 DB의 현재 버전·백업·실데이터 업그레이드는 이번 결과에 포함되지 않는다.

## 5. 실제 제품 UI의 전체 운동 흐름

프로덕션 `dist`를 `http://127.0.0.1:3080`에서 제공했다. Backend fixture는 새 사용자0명·V15·실제 시스템 Clock을 확인한 뒤 시작했다. PostgreSQL은 `overload_browser_isolated`, 임의 host port1579, Backend는 loopback port1693이었다. 실제 Edge **156.0.4314.58**의 전용 headless 프로필만 사용했다. 실제 OTP 검증·쿠키 세션·CSRF·제품 React UI·native IndexedDB·Web Locks·SW를 사용했다. 테스트 OTP 발송기 외에 인증 우회나 제품 보안 완화는 없다.

최종 전용 하네스 `logs/task-009-final/core-flow.mjs`: **12개 검증 묶음 통과**. 하나의 배포 앱에서 아래 흐름을 이어서 수행했다. 누락 복구는 같은 앱 안의 별도 세션으로 실제 구 epoch 사본을 만들었다.

| 사용자 흐름 | 실제 결과 |
|---|---|
| 1. 신규 사용자 로그인 | 제품 이메일·OTP 입력과 서버 인증 성공 |
| 2. 운동 정의·루틴 생성 | 제품 폼에서 새 개인 종목과 웨이트2세트+유산소 루틴 생성, API 저장 확인 |
| 3. 루틴 선택·START | 제품 START, 불변 InitialPlan·서버 세션·실제 IDB 문서 생성 |
| 4. 중량·반복 입력/완료 | 62.5kg×8회와 65kg×9회 완료, 로컬 저장과 서버 수행 일치 |
| 5. 휴식·유산소 | 휴식 상태 생성/종료, 유산소 시작·일시정지·확인·완료와 원본 시간 저장 |
| 6. IDB·snapshot | 완료 수행을 실제 V13 API로 저장하고 ACK 확인, FINISH ACK도 IDB에서 확인 |
| 7. 명시 승인·PENDING | 첫 승인 APPLIED; 다른 클라이언트 역할의 정상 API 루틴 변경 후 다음 승인 PENDING. 제품 KEEP_CURRENT로 해결하며 당시 수행 문맥 불변 |
| 8. FINISH·최초 평가 | 제품 종료 Dialog, 실제 FINISHED와 최초 평가 표시 |
| 9. 종료 이력 | 실제 목록 API와 제품 이력 버튼으로 상세 진입 |
| 10. 종료 후 정정·삭제 | 제품 Dialog에서 첫 세트11회 정정, 두 번째 세트 논리 삭제. CORRECTED/DELETED 이력 확인 |
| 11. 누락 완료 수행 복구 | 실제 오프라인 완료→다른 deviceId의 takeover→서버 빈 세션 FINISH 후 제품 비교 화면에서 구 사본의 한 수행만 명시 선택 복구 |
| 12. 최초/현재 평가 분리 | 최초 eligible=false/합계0/sessionVersion2 보존. 복구 뒤 현재 eligible=true/합계8/sessionVersion3, 구 원본 문서 바이트 내용 불변 |

외부 루틴 수정·takeover·빈 세션 FINISH는 테스트 하네스가 동일한 쿠키/CSRF의 정상 API로 만든 경쟁 상황이다. 실제 두 물리 기기를 사용했다고 표현하지 않는다. 복구 자체·정정·삭제·승인 충돌 해결은 제품 UI로 수행했다.

본 세션의 InitialPlan·봉인 AppliedPlan·최초 평가·endedAt을 정정/삭제 뒤 비교했다. 삭제/수치 충돌 원본은 복구 checkbox가 비활성인 것도 확인했다. 390×844 viewport에서 가로 overflow0이며 PNG를 직접 확인했다.

## 6. 오프라인·재연결·복구 실패 경계

5A~5D의 기존 브라우저 전체를 반복하지 않았다. 해당 보고서의 START ACK 유실·유산소/복구/FINISH 응답 유실·REAPPROVE·추가 동시성 근거와 최종 코드의 전체 회귀를 재사용했다. 마지막 5E의 앱 셸·오프라인 경계는 최종 dist에서 기존 하네스를 재사용해 **주 흐름12개+추가4개**를 재실행했다. 기존 증빙을 덮지 않도록 결과 경로만 `logs/task-009-final/pwa/`로 분리했다.

| 경계 | 이번 실행 및 재사용 근거 |
|---|---|
| 서버 응답 유실·동일 요청 | 실제 서버 커밋 뒤 snapshot ACK 차단, cold 재실행 후 원래 JSON 그대로 재전송·FINISH. 최종 Backend의 duplicate/reversal/불일치 검사 통과. START·FINISH·복구 응답 유실은 5A/5D 근거와 최종 회귀 재사용 |
| IndexedDB 저장 실패 | 실제 put 실패 주입, 완료 수·확정 snapshot 불변과 완료 UI 미확정 |
| 오프라인 완전 종료·재실행 | 정적 서버도 중지하고 Edge 프로세스 강제 종료 후 같은 프로필 재시작. 명시 동의 전 비공개, 이후 초안·완료·타이머 앵커·고정 요청 복원 |
| 오프라인 종료·재연결 | 원래 endedAt/최종 snapshot 유지, 서버 종료 미확인 표시. 인증→현재 계정/세대/ACTIVE/편집권 조회가 첫 쓰기에 선행 |
| 24시간 AUTO_CLOSED | **해당 일회성 DB의 last_workout_synced_at만 25시간 전으로 변경**해 실제 서버 만료 경로 실행. 자동 snapshot/FINISH0건, 원래 로컬 종료/복구 사본 보존. 실제 24시간을 기다린 실험은 아님 |
| 다른 기기 takeover | 정상 takeover API의 다른 deviceId/epoch 관측 후 구 sender 중단. 명시 가져오기에서 새 사본은 서버 사실만 포함, 구 원본 자동 병합 없음 |
| 동일 브라우저 다중 탭 | 실제 두 탭 Web Lock 경쟁, 두 번째 입력 읽기 전용 |
| 로그아웃·계정 변경 | 실제 로그아웃 뒤 cold 재실행 진입 차단, 원본 유지. 다른 OTP 계정에서 이전 모든 문서 바이트 불변·비노출 |
| 세대 변경 | 테스트 프록시가 읽기 DTO의 새 세대를 주입, 구 사본 격리·cold 차단. 실제 reset API E2E는 아님. Backend 실제 세대 잠금/쓰기 검증은 전체 PostgreSQL 테스트로 확인 |
| 확실한 인증 거부 | fixture가 해당 테스트 사용자 서버 세션을 폐기한 실제401 뒤 cold 허용 해제. 네트워크 장애와 구별 |
| PWA 업데이트 | 다른 worker 바이트 설치 후 waiting 유지, 기존 controller와 IDB 운동/FINISH 원본 불변. 강제 새로고침 없음 |

주 흐름은 Edge5회 실행(강제 종료2회 포함), 추가 경계는3회 실행했다. 최초 설치 후 실제 worker 제어와 정적 Cache Storage도 확인했다.

## 7. 데이터 보존·권한·동시성

| 불변 조건 | 증거와 결과 |
|---|---|
| InitialPlan 불변 | DB immutable-start trigger·변조/소스 변경 회귀, 실제 UI 전후 JSON 동일 |
| FINISHED AppliedPlan 봉인 | V14 immutable-closure trigger·종료 후 금지 입력 검사, UI 정정/복구 전후 동일 |
| 최초 평가 불변·현재 평가 분리 | FINISH duplicate·현재 benchmark 재계산·정정/복구 테스트, 실제 UI 최초0→현재8 구분 |
| tombstone·이력 보존 | UUID/ordinal 충돌·부활 차단·전체 롤백 테스트, UI 삭제 후 복구 선택 불가 |
| 승인 사건·당시 문맥 보존 | 연속 승인·외부 충돌·새 REAPPROVE·종료 후 해결 회귀. UI KEEP_CURRENT 전후 recordedContexts 동일 |
| 구 epoch 미전송 원본 | 실제 takeover와 복구 후 원래 IDB 문서 내용 동일, retag/자동 복사 없음 |
| AUTO_CLOSED 무재개·가짜 완료 없음 | 만료 정확 경계·예약 작업 재시작·늦은 FINISH 경쟁 테스트, 실제 자동 쓰기 차단 |
| 종료/삭제 데이터 무단 재전송 없음 | 서버 상태/세대/epoch 검증과 FE 고정 요청 가드, 삭제 세션 사후 쓰기/자동 복구 거부 |
| 사용자·세대 격리 | 본인 principal·현재 세대 FK/조회, 실제 계정 전환/401, 세대 계약 주입과 Backend 잠금 회귀 |
| 서버 확인 전 성공 없음 | IDB 실패·ACK 실패 모델 회귀, 실제 미확인 FINISH/저장 실패 UI |
| 사용자 확인 없는 병합 없음 | 별도 구 사본 보존·선택 복구·tombstone/슬롯 충돌 표시 및 거절 |

PostgreSQL 동시성 테스트는 실제 lock wait와 SQL 장애 주입을 사용한다. 같은 사용자 START·snapshot↔takeover·동일 revision의 동시 요청·FINISH↔만료·같은 expectedVersion 정정·재승인↔루틴 편집의 한 결과만 커밋됨을 확인했다. 수행·승인·audit·revision·최초 평가·종료 메타의 부분 커밋은 없었다. 확인 불가 승인 주장의 UNVERIFIED 보존/공식 평가 제외는 최종 서버 통합 테스트와 FE 복구 모델 회귀로 검증했다.

브라우저 fixture 종료 전 읽기 전용 SQL 재확인: ACTIVE3 / AUTO_CLOSED1 / FINISHED5, 잘못된 AUTO_CLOSED 종료 메타0, 세트 tombstone2, CORRECTED2 / DELETED2 / RECOVERED2. 승인 결과 APPLIED2 / NOT_APPLIED2 이후에도 수행의 PENDING 문맥2개가 보존되어 있었다. 이는 하네스 작성 중 재실행까지 누적된 **일회성 합성 데이터**이며 최종 단일 사용자 시나리오의 건수로 해석하지 않는다.

## 8. PWA 배포와 실기기 확인

- 빌드 shell ID `173ec0a2702908b137039a75`, 허용 정적 자산9개의 SHA-256을 실제 dist와 모두 재계산해 일치 확인했다. 브라우저 Cache Storage에도 동일한 공개 정적 파일9개만 있었다.
- worker는 동일 origin GET의 정확한 파일 목록만 처리한다. query·외부 origin·쓰기·API·인증·OAuth·복귀 경로는 캐시하지 않는다. 전체 회귀의 worker 모델 테스트와 실제 인증/운동 요청 후 캐시 목록으로 확인했다.
- `/`와 `/account` navigation만 공개 index로 처리한다. 다른 임의 URL까지 offline SPA fallback으로 지원한다고 주장하지 않는다. 실제 호스팅에서도 이 두 제품 경로는 index로 보내고 API/OAuth/복귀 경로는 HTML fallback에서 제외해야 한다.
- runtime 개인화 HTML 캐시 없음. 해시 불일치·없는 DB 생성 abort·미지원 향후 IDB schema 설치 중단, 업데이트 대기·강제 claim/reload 부재를 테스트했다. activate는 전용 정적 캐시만 정리하며 운동 DB를 변경하지 않는다.
- 운영에서는 HTTPS·루트 scope·정상 MIME·index와 해시 자산의 동일 릴리스·재검증 가능한 sw.js가 필요하다. 열린 구 버전을 위한 이전 해시 자산 보존도 필요하다. loopback 검증은 공개 HTTPS/CDN/프록시 배포 검증을 대체하지 않는다. Secure 쿠키·OAuth redirect·CSRF도 실제 배포 origin에서 확인해야 한다. [MDN Service Worker](https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API)
- 390px viewport의 실제 제품 화면, 설치 안내, manifest·설치 가능성 진단 오류0은 확인했다. OS 설치 창을 통한 설치 성공·실제 터치/키보드/잠금은 확인하지 않았다.

| 출시 전 수동 확인 | 상태 |
|---|---|
| 실제 HTTPS 배포의 인증·CSRF·OAuth, MIME/cache 헤더, 직접 `/account` 진입 | 미실행: 배포 대상/설정 미제공 |
| iPhone Safari 및 홈 화면 설치·standalone cold 실행 | 미실행: 실기기 없음 |
| Android 설치·standalone·백그라운드/배터리·업데이트 | 미실행: 실기기 없음 |
| 실제 화면 잠금·앱 전환·키보드·저장소 압박/정리 | 미실행: 실기기 없음 |
| Windows OS 설치 전체 | 미실행: headless 설치 진단만 수행 |

Safari 탭의 IndexedDB가 홈 화면 앱에 복사된다고 가정하지 않는다. 설치 앱에서 온라인 인증과 운동 열기를 먼저 수행해야 한다. [WebKit Safari 17.2](https://webkit.org/blog/14787/webkit-features-in-safari-17-2/). IndexedDB/캐시의 OS 정리·사용자 삭제 이후 미전송 기록의 영구 보존은 보장할 수 없다. [WebKit 저장 정책](https://webkit.org/blog/14403/updates-to-storage-policy/)

## 9. 발견 사항·검증 중 오류

| 심각도 | 내용 | 처리 |
|---|---|---|
| P0/P1 | 이번 범위에서 재현된 데이터 유실·중복·권한/평가 불변성 제품 결함 없음 | 제품 수정 없음 |
| P2 문서 | Backend AGENTS.md:112, backend.md:28, Task009 v1.0:34, Task006 문서 서두가 여전히 Frontend/IndexedDB를 미구현으로 표시한다. Frontend README 과거 roadmap에도 미완료 체크가 남아 실제 현황과 혼동될 수 있다 | 본 보고서에서 현행 HEAD/검증 결과를 명시. 원문은 이번 검증에서 수정하지 않음; 최종 문서 정리 대상으로 남김 |
| 환경/하네스 | 기본 sandbox의 Gradle 캐시 lock·Docker pipe 접근 거부 | 해당 격리 검증 명령을 승인된 실행 권한으로 수행. 개인 DB 대체/검사 생략 없음 |
| 하네스 | 첫 방문 SW가 즉시 페이지를 제어할 것으로 잘못 기대 | ready 뒤 정상 navigation으로 제어 시작 확인. 제품의 강제 claim 부재 유지 |
| 하네스 | 평가 repsSum의 문자열 계약을 숫자로 비교 | 기대값만 문자열로 수정 후 최종 12개 묶음 완주. 제품 변경 없음 |

제품 검증의 미해결 실패0. 실기기·실배포·실제 reset API·외부 OAuth/메일 발송 검증은 위 제한대로 남는다. 새 정책·주요 의존성·기능을 추가하지 않았다.

## 10. 개인 DB 적용 전 준비와 승인 경계

이번 작업의 검증·격리 데이터 생성/정리는 사용자 요청 범위에서 수행했다. 개인 DB 접속/적용·운영 설정 변경·배포·커밋·Push 승인은 요청하거나 실행하지 않았다.

**다음 작업은 별도 승인 후 진행해야 한다.** 마지막 개인 적용 근거는 V9/V10 보고서다. 이번에는 개인 DB에 접속하지 않았으므로 V11 적용 여부와 현재 버전을 확정하지 않는다. V12~V15만 적용하면 된다고 가정하지 않는다.

1. 승인 범위를 실제 host/port/database·적용 버전·기존 데이터 보존 요구와 함께 확정한다.
2. 해당 DB의 Flyway 이력/체크섬·현재 버전·테이블·사용자/루틴/승인/진행 세션 현황을 읽기 전용 점검한다. 불일치는 repair/초기화로 덮지 않는다.
3. 인증/운동 데이터를 포함한 일관된 백업을 생성하고, 개인 DB와 분리된 새 PostgreSQL에서 복원 가능성을 확인한다. credential/OTP/세션 원문을 보고서나 Git에 넣지 않는다.
4. **복원 사본**에 실제로 필요한 V11~V15 범위를 적용해 기존 모든 행·체크섬·승인 계보·상태를 전후 비교하고 최신 Backend 부팅/읽기를 검증한다. 이번 합성 fixture 통과를 실제 백업 검증으로 대체하지 않는다.
5. 실행 중 쓰기/구 Backend/구 PWA와의 전환 시간을 정하고, 로컬 미전송 원본의 보존과 버전 호환성을 확인한다. 자동 병합이나 DB downgrade를 복구 계획으로 가정하지 않는다.
6. 검토한 백업·복원·적용 명령·사후 점검·복구 절차를 승인받은 뒤 개인 DB에 적용한다. 이후 실제 운영 설정/모바일 검증은 별도 확인한다.

판정: **핵심 개발과 이 보고서 범위의 최종 통합 검증은 완료로 수용 가능하다.** 문서 상태 정리, 실제 배포/실기기 확인, 개인 DB 적용은 남은 별도 단계다. 다음 단계 승인 전까지 여기서 중단한다.

## 11. 로컬 증빙과 정리

Backend ignored 증빙:

- `build/task-009-final-backend.log`, `task-009-final-counts.json`, `task-009-final-integration-suites.json`
- `build/test-results/test/`, `integrationTest/`, `finalMigrationTest/`의 JUnit XML
- `build/task-009-final-migration.log`, `task-009-final.init.gradle`, `task-009-final-src/`
- `build/task-009-final-browser-backend.log`, `task-009-final-ready.json`, `task-009-final-sql.log`, `task-009-final-migration-sha256.json`

Frontend ignored 증빙:

- `logs/task-009-final/{npm-test.log,lint.log,build.log,core-flow.mjs,core-browser.log,core-result.json,core-evidence.json,core-mobile.png,summary.json}`
- `logs/task-009-final/{pwa-run.mjs,pwa-browser.log,pwa-boundary.log}` 및 `pwa/{browser-result.json,boundary-browser-result.json,mobile-workout.png}`
- 기존 5A~5E 보고서의 상세 검증 근거는 각 보고서에서 연결한다. 테스트 프로필·쿠키·일회용 인증 파일은 Git에 포함하지 않는다.

Backend fixture의 정상 stop과 `BUILD SUCCESSFUL`을 확인했다. 새 PostgreSQL/Ryuk과 직접 시작한 Java/Gradle/Node/전용 Edge가 종료됐으며 전용1693/3080 listener0, ready/OTP 파일 제거를 확인했다. 기본 사용자 컨테이너5개는 시작과 같은 ID `81a6b4d6aca2`, `908b9728eec1`, `97473d622cad`, `1079a897c476`, `927921a2c15b`로 그대로 실행 중이다. 기존 컨테이너·사용자 데이터·브라우저 프로필은 변경하거나 삭제하지 않았다.

## 후속 일반 운동 UI/API 마감 참조 — 2026-11-13

이 문서의 Task009 당시 검증 수치/적용 범위는 역사 기록이다. 이후 일반 운동 UI 실제 API 연결과 사용자 피드백 코드는 Backend a56103e39e9821657da3ef98b047f3ce136116c8 / PWA15a0f6bee4421f799cd691fa435bc8103c1e28e2에 반영됐다. 기존 전체 snapshot·고정 FINISH·정정/선택 복구를 재사용하고 읽기 전용 ACTIVE 잠정 평가와 종료 weightReview를 추가했다. 공식 기준 자격·최초 평가·DB V35는 유지한다.

당시 최신 보고는 Frontend585/585·Backend단위453/453·DB통합180/180·실제API14개그룹·Lint/TypeScript/Build 통과이며 이번 문서 동기화에서 재실행하지 않았다. 검토 서비스는 볼륨/백업 보존 후 종료했다. [정확한 완료 범위·검증·미구현·실기기 경계](../../../overload-pwa/docs/tasks/ui-v09-workout-api-integration.md#최종-마감--2026-11-13)를 확인한다. 기존 단계 보고를 전체 제품/운영 배포 승인으로 확대하지 않는다.
