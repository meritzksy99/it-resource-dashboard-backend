# 로그인 비밀번호 정책 설계 (Password Policy)

> 작성일 2026-07-09. 대상: IT 개발팀 리소스 대시보드 백엔드.
> 규칙 요약: 기존 API 수정분은 **v2 신설**, 신규 API는 **v1**. 기존 v1 auth는 무손상 유지(A안).

## 1. 목표

전달받은 보안 정책 5종을 로그인/계정 관리에 반영한다.

1. **비밀번호 복잡도** — 최소 8자, 영문 대문자+소문자+숫자+특수문자 모두 포함. (변경 시점 검증)
2. **90일 변경주기** — 마지막 변경 후 90일 초과 시 만료 → 강제 변경 유도.
3. **직전 비밀번호 재사용 금지** — 직전 1개 재사용 불가.
4. **휴면 계정** — 3개월 이상 미사용 계정은 로그인 차단, 관리자만 해제.
5. **로그인 실패 잠금** — 10회 이상 실패 시 사용 제한, 관리자만 해제.

최초 로그인은 아이디=사번, 비번=사번이며 이후 비밀번호 변경 시 위 정책이 적용된다.
최초 로그인/만료 시 프런트가 비번 변경 팝업을 띄우도록 `pwdResetRequired=true`를 반환한다(기존 필드 재사용).

## 2. 버전/호환 정책 (핵심 제약)

- 기존 API를 **수정하는 경우 v2로 새로 만든다.** 신규 API는 v1로 만든다.
- **A안(확정)**: 기존 v1 `auth/login`·`auth/password`는 **완전 무손상 유지**(정책 미적용). 프런트가 v2로 이전하면 v1은 추후 제거.
- v1 login/password는 `@Deprecated` + Swagger에 "정책 미적용, v2 권장" 명시.
- **알려진 한계(문서화)**: v1 login이 살아있는 동안은 잠금·휴면이 우회 가능하다. 정책 실효는 프런트의 v2 전환에 의존한다.

### API 라우팅

| 엔드포인트 | 버전 | 비고 |
|---|---|---|
| `POST /api/v1/auth/login` | v1 유지(변경 X) | 기존 계약 그대로, `@Deprecated` |
| `POST /api/v2/auth/login` | 신규 v2 | 잠금·휴면·만료 정책 적용 |
| `POST /api/v1/auth/password` | v1 유지(변경 X) | 기존(min8+≠사번), `@Deprecated` |
| `POST /api/v2/auth/password` | 신규 v2 | 복잡도·재사용 정책 적용 |
| `GET /api/v1/auth/me` | v1 유지 | 수정 없음(`pwdResetRequired` 이미 존재) |
| `GET /api/v1/admin/accounts` | 신규 v1 | 계정 목록(상태/실패횟수/최근로그인/만료) |
| `POST /api/v1/admin/accounts/{empno}/unlock` | 신규 v1 | 잠금·휴면 공통 해제 |
| `POST /api/v1/admin/accounts/{empno}/reset-password` | 신규 v1 | 사번으로 초기화 + 강제 변경 |

## 3. 데이터 모델 — `AUTH_ACCOUNT` 변경 (마이그레이션 `V017`)

19c 호환(신규 파일, 기존 파일 수정 금지). `BOOLEAN` 금지, 플래그는 `CHAR(1)`/코드 컬럼.

추가 컬럼:

| 컬럼 | 타입 | 용도 |
|---|---|---|
| `STATUS_CD` | `CHAR(2)` DEFAULT `'00'` NOT NULL, CK in (`'00'`,`'01'`,`'02'`) | `00`=정상, `01`=잠금(실패10회), `02`=휴면(3개월 미사용) |
| `PASSWORD_CHANGED_AT` | `TIMESTAMP` | 90일 만료 계산 기준 |
| `PREV_PASSWORD_HASH` | `VARCHAR2(100)` | 직전 1개 재사용 금지 |

기존 컬럼 활용: `LAST_LOGIN_AT`(휴면 판정 기준), `FAIL_CNT`(현재 미사용 → 잠금 로직에 연결).

백필: 기존 행은 `PASSWORD_CHANGED_AT = SYSTIMESTAMP`, `STATUS_CD='00'`으로 설정하여 배포 직후 즉시 만료/휴면되지 않게 한다.

제약/네이밍: `CK_AUTH_STATUS CHECK (STATUS_CD IN ('00','01','02'))`.

## 4. 설정 (`@ConfigurationProperties`, 하드코딩 금지)

```yaml
app:
  auth:
    password:
      min-length: 8
      max-age-days: 90
    lockout:
      max-fail: 10
    dormant-days: 90   # 3개월
```

바인딩: `AuthPolicyProperties`(`@ConfigurationProperties("app.auth")`). 하위 `password`/`lockout`/`dormant-days`.

## 5. 컴포넌트 구조

기존 `AuthService`는 v1 컨트롤러가 그대로 사용(무손상). 정책 로직은 신규 클래스로 분리한다.

- `PasswordPolicy` — 복잡도 검증(순수 로직, 설정 주입). `validate(rawPassword)` → 위반 시 `PasswordPolicyException`.
- `AuthPolicyService` — v2 login/password 오케스트레이션(잠금/휴면/만료 판정, 실패 카운트, 재사용 검사).
- `AuthAdminService` — 관리자 목록/해제/초기화.
- 매퍼 확장: `AuthAccountMapper`에 상태/실패/이력 관련 SELECT·UPDATE 추가(신규 XML 문 추가, 기존 문 무손상).

## 6. 흐름 상세

### 6.1 v2 로그인 (`AuthPolicyService.login`)

응답 형태는 v1 `LoginResult`와 동일(`{ token, empno, role, roleName, name, pwdResetRequired }`).

1. ADMIN 설정 계정 지름길(기존과 동일, 정책 미적용).
2. 계정 로드. `STATUS_CD='01'` → **403 `ACCOUNT_LOCKED`**(토큰 없음). `STATUS_CD='02'` → **403 `ACCOUNT_DORMANT`**.
3. **지연 휴면 판정**: `STATUS_CD='00'`이고 `LAST_LOGIN_AT`가 `now - dormant-days`보다 과거이면 `STATUS_CD='02'`로 저장 후 403 `ACCOUNT_DORMANT`. (`LAST_LOGIN_AT`가 null이면 계정 생성 이후 기준으로 판정하지 않고 통과 — 신규 계정 보호)
4. 비밀번호 검증. 오답 → `FAIL_CNT+1`; `FAIL_CNT >= max-fail`이면 `STATUS_CD='01'`로 저장. **401 `INVALID_CREDENTIALS`** (ProblemDetail에 남은 시도횟수 `remainingAttempts` 포함).
5. 정답 → `FAIL_CNT=0`로 리셋, `LAST_LOGIN_AT=now` 갱신.
6. **만료 판정**: `PWD_RESET_YN='Y'` 또는 `PASSWORD_CHANGED_AT`가 `now - max-age-days`보다 과거이면 `pwdResetRequired=true`. 어느 경우든 토큰은 발급.

### 6.2 v2 비밀번호 변경 (`POST /api/v2/auth/password`)

요청 DTO는 v1과 동일(`ChangePasswordRequest { oldPassword, newPassword }`).
검증 순서(각 실패는 해당 코드로):

1. `oldPassword`가 현재 해시와 일치(아니면 401 `INVALID_CREDENTIALS`).
2. **복잡도**: 길이 ≥ min-length, 대문자·소문자·숫자·특수문자 각 1개 이상 → 위반 시 400 `PASSWORD_POLICY_VIOLATION`. (사번은 숫자만이라 자동 실패 → 기본 비번 재설정 원천 차단)
3. **재사용**: new가 현재 해시 또는 `PREV_PASSWORD_HASH`와 일치하면 400 `PASSWORD_REUSE`.
4. 성공 시: `PREV_PASSWORD_HASH = 기존 PASSWORD_HASH`, `PASSWORD_HASH = encode(new)`, `PWD_RESET_YN='N'`, `PASSWORD_CHANGED_AT=now`.

특수문자 정의: 정규식 `[^A-Za-z0-9]` (영숫자 외 1자 이상). 대/소/숫자 각각 `[A-Z]`,`[a-z]`,`[0-9]`.

### 6.3 관리자 API (`AdminAccountController`, `@Auth(roles={"ADMIN"})`)

설정 ADMIN 계정만 호출 가능.

- `GET /api/v1/admin/accounts` — `List<AdminAccountRow>`: empno, 이름, statusCd, statusName, failCnt, lastLoginAt, passwordChangedAt, expired(계산), dormant(계산). envelope `{data, meta}`.
- `POST /api/v1/admin/accounts/{empno}/unlock` — `STATUS_CD='00'`, `FAIL_CNT=0`, `LAST_LOGIN_AT=now`(휴면 시계 리셋). 잠금·휴면 공통 해제. 대상 없으면 404.
- `POST /api/v1/admin/accounts/{empno}/reset-password` — `PASSWORD_HASH=encode(사번)`, `PWD_RESET_YN='Y'`, `PASSWORD_CHANGED_AT=now`, `STATUS_CD='00'`, `FAIL_CNT=0`, `PREV_PASSWORD_HASH=null`. 대상 없으면 404.

## 7. 에러 처리

신규 예외 → 기존 `GlobalExceptionHandler`에서 `ProblemDetail`로 변환, `errorCode` 속성 추가(프런트 분기용).

| 예외 | HTTP | errorCode |
|---|---|---|
| `AccountLockedException` | 403 | `ACCOUNT_LOCKED` |
| `AccountDormantException` | 403 | `ACCOUNT_DORMANT` |
| `PasswordPolicyException` | 400 | `PASSWORD_POLICY_VIOLATION` |
| `PasswordReuseException` | 400 | `PASSWORD_REUSE` |
| (로그인 오답) `UnauthorizedException`(기존) | 401 | `INVALID_CREDENTIALS` (+`remainingAttempts`) |

## 8. 인터셉터/라우팅

`WebConfig`: `addPathPatterns`에 `/api/v2/**` 추가, `excludePathPatterns`에 `/api/v2/auth/login` 추가(로그인은 공개). `/api/v2/auth/password`·`/api/v1/admin/**`는 인터셉트(인증 필요, admin은 `@Auth(roles={"ADMIN"})`).

## 9. 휴면 판정 방식

지연(lazy) 판정 — 로그인 시점에만 검사. 별도 스케줄러 없음(휴면은 로그인 시도 시에만 의미). 관리자 목록의 `dormant` 플래그는 `LAST_LOGIN_AT` 기준으로 조회 시 계산.

## 10. 테스트 (TDD: Red→Green→Refactor)

- **PasswordPolicy 단위**: 7자(실패)/8자(경계), 대/소/숫자/특수 각 누락 케이스, 모두 충족(성공).
- **AuthPolicyService 로그인**: 잠금 계정→예외, 지연 휴면→예외+STATUS 저장, 오답 누적→10회에서 잠금, 정답→FAIL_CNT 리셋+LAST_LOGIN 갱신, 만료(PWD_RESET_YN=Y/90일초과)→pwdResetRequired.
- **v2 비번 변경**: 복잡도 위반 400 코드, 재사용(현재/직전) 400 코드, 성공 시 PREV 이동·PASSWORD_CHANGED_AT 갱신.
- **컨트롤러 계약**(`@WebMvcTest`): v2 login 403/401 + errorCode, v2 password 400 코드, admin 목록/해제/초기화 200 및 비ADMIN 403.
- **매퍼 IT**(Testcontainers): 신규 컬럼 read/write, 상태/실패/이력 UPDATE.
- **v1 무손상 회귀**: 기존 v1 login/password 테스트 그대로 통과.

## 11. Definition of Done

- [ ] Red→Green→Refactor, 경계/정책 테스트 통과
- [ ] 기존 v1 auth 계약·테스트 무손상
- [ ] DTO 경계 유지, ProblemDetail+errorCode, springdoc 문서화(구현 후 작성)
- [ ] `V017` 19c 호환 DDL, 기존 마이그레이션 파일 무수정
- [ ] `/review-all` Critical 0건
- [ ] `./gradlew build` 통과

## 12. 잔여 위험 및 하드닝 결정 (최종 리뷰 반영, 2026-07-09)

최종 보안/코드 리뷰(Critical 0) 결과 아래 트레이드오프를 사용자와 확정했다.

- **① v1 로그인 정책 우회 — A안 유지(문서화만).** 기존 `/api/v1/auth/login`은 무손상이라 잠금·휴면·만료가 적용되지 않는다. 즉 v2에서 잠긴 계정도 v1으로는 로그인 시도가 가능하고 `FAIL_CNT`가 오르지 않는다. **정책의 실효는 프런트의 v2 전환 + (필요 시) 네트워크단 v1 차단에 의존한다.** v1은 `@Deprecated`로 표기했고, 브루트포스 방어가 실요건이 되면 v1 조기 폐기 또는 v1에 잠금 최소 반영을 재검토한다. **알려진 수용 위험.**
- **② 계정 열거 방지 — 수정함.** 로그인 실패 응답의 `remainingAttempts`가 미존재 계정(max)과 실존 계정 첫 오답(max−1)을 구분시키던 문제를, 미존재/`dev==null` 경로에서도 더미 BCrypt 비교(타이밍 평준화) + 동일 `remainingAttempts` 값 반환으로 중화했다. (반복 프로빙 시 실존 계정의 잠금 진행은 여전히 신호가 될 수 있음 — inherent, 수용.)
- **③ 강제 비밀번호 변경 서버 강제 — 수정함.** `pwdReset=true` 토큰은 인터셉터에서 자기계정 경로(`/auth/me`, `/auth/password`) 외 모든 업무 API 접근을 403 `PASSWORD_RESET_REQUIRED`로 차단한다. 초기화/최초 비번(=사번) 상태의 토큰으로 대시보드·인사 데이터에 접근하지 못하게 한다.
