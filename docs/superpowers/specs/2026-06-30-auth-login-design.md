# IT 개발팀 리소스 대시보드 — B(로그인/인증/인가) 설계서

- 작성일: 2026-06-30
- 선행: A(공통기반)+D(마스터·인력)+C(대시보드·집계) 완료(main).
- 상위 규칙: `CLAUDE.md`(MUST). 인증 참고문서 `ad계정설정.txt`의 **LDAP은 채택하지 않음**(로컬 DB 로그인만).

## 1. 목적 & 범위

DB의 인사(`HR_DEVELOPER`) 기반 **로컬 사번 로그인**과 **JWT** 발급/검증으로, 역할(팀장/업무리더/일반직원)에 따라
민감 API 접근을 제어한다. LDAP/AD 연동은 쓰지 않는다.

### 1.1 이번 범위
- 사번 로그인(ID=사번, 초기 비번=사번) + JWT 발급(역할 claim).
- 비밀번호 **첫 로그인 강제변경 + 본인 변경**(공용 엔드포인트).
- **계정 자동 프로비저닝**: 재직 직원 전원 로그인 가능(앱 기동 시 생성).
- 인가: **민감 API만 보호**(인사 쓰기·집계 트리거 = 팀장). 조회는 인증만 되면 허용.
- 경량 구현: jjwt + BCrypt, 전체 Spring Security 미도입.

### 1.2 범위 밖
- LDAP/AD 연동, 관리자 비번 초기화, 비번 분실 자가복구(이메일 등), 리프레시 토큰, 계정 잠금 정책(FAIL_CNT 컬럼은 두되 잠금 로직은 후속).

## 2. 기술 결정 (CLAUDE.md 2장 — 라이브러리 추가 승인)
- `io.jsonwebtoken:jjwt-api/jjwt-impl/jjwt-jackson`(JWT 발급·검증).
- `org.springframework.security:spring-security-crypto`(BCrypt 해시만 사용, 전체 시큐리티 미도입).
- 토큰 검증/인가는 **`HandlerInterceptor` + `@Auth` 애너테이션**(경량). DTO record·생성자주입·envelope·ProblemDetail 등 기존 규약 동일.

## 3. 데이터 모델 (DB2, V007 — 19c 호환)

```
AUTH_ACCOUNT   PK(EMPNO)            -- HR_DEVELOPER(EMPNO)와 1:1(논리적 연결, FK 선택)
   PASSWORD_HASH VARCHAR2(100) NOT NULL,   -- BCrypt
   PWD_RESET_YN  CHAR(1) DEFAULT 'Y' NOT NULL,  -- 'Y'=초기/강제변경 필요
   LAST_LOGIN_AT TIMESTAMP,
   FAIL_CNT      NUMBER(4) DEFAULT 0 NOT NULL,
   CREATED_AT TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL, CREATED_BY VARCHAR2(30) DEFAULT 'SYSTEM' NOT NULL,
   UPDATED_AT TIMESTAMP, UPDATED_BY VARCHAR2(30),
   CONSTRAINT CK_AUTH_PWD_RESET CHECK (PWD_RESET_YN IN ('Y','N'))
```
- 역할/이름/파트는 `HR_DEVELOPER`(ROLE_CD/EMP_NM/PART_CD)에서 읽는다(중복 저장 안 함).
- **계정 프로비저닝(앱)**: `AccountProvisioner`(ApplicationRunner)가 기동 시 `HR_DEVELOPER` 재직(STATUS_CD='01') 직원 중 `AUTH_ACCOUNT` 없는 사번에 대해 `PASSWORD_HASH=BCrypt(사번)`, `PWD_RESET_YN='Y'`로 생성. 멱등(누락분만). 런타임 신규 입사자는 다음 기동 시 자동 생성.

## 4. 인증 흐름 · JWT

### 4.1 엔드포인트 (`/api/v1/auth`)
| Method | Path | 인증 | 설명 |
|---|---|---|---|
| POST | `/auth/login` | 공개 | `{empno, password}` → BCrypt 검증 → JWT 발급 + LAST_LOGIN_AT 갱신. 실패 401. |
| POST | `/auth/password` | 인증(본인) | `{oldPassword, newPassword}` → old 검증 후 새 해시 + `PWD_RESET_YN='N'`. 첫강제·자발 공용. |
| GET | `/auth/me` | 인증 | 토큰 기반 현재 사용자(empno/role/name/part/pwdResetRequired). |

- 로그인 응답 `data`: `{ token, empno, role, roleName, name, pwdResetRequired }`.
- 비번 정책(최소): newPassword 8자 이상, 사번과 동일 금지(첫 변경 의미). 검증 실패 400.

### 4.2 JWT (`app.jwt`)
- HS256. `app.jwt.secret`(env `JWT_SECRET`, 32바이트+), `app.jwt.expiration`(env, 기본 86400000=24h).
- claims: `sub`=사번, `role`(01/02/03), `roleName`, `name`, `partCd`, `pwdReset`(bool), `iat`, `exp`.
- `JwtService.generate(empno, role, roleName, name, partCd, pwdReset) : String`, `validate(token) : Claims`(만료/위조 시 null).

## 5. 인가 (민감 API만 보호)

`JwtAuthInterceptor`(HandlerInterceptor)가 컨트롤러 진입 전 토큰을 검증하고, `@Auth` 메타에 따라 역할을 확인한다.

- **공개**(토큰 불필요): `/api/v1/auth/login`, `/api/v1/health`, `/swagger-ui/**`, `/v3/api-docs/**`.
- **인증 필요(역할 무관)**: 그 외 전부 — codes, developers(GET), dev-volume, resource, sr-projects, aggregations(GET), `/auth/me`, `/auth/password`.
- **팀장(ROLE_CD '01')만**: `POST/PUT/DELETE /api/v1/developers`, `POST /api/v1/aggregations`.
- 토큰 없음/만료/위조 → **401**, 역할 부족 → **403**, 모두 `ProblemDetail`.
- 인증된 요청의 사용자 정보는 요청 스코프(`AuthContext`/request attribute)로 전달(필요 컨트롤러에서 사용).

### 5.1 적용 방식
- `@Auth`(애너테이션, 기본=인증 필요) / `@Auth(roles={"01"})`(역할 제한) 를 컨트롤러 메서드/클래스에 부여.
- 인터셉터는 공개 경로 화이트리스트는 통과, 그 외엔 `Authorization: Bearer` 파싱→`JwtService.validate`→실패 401. `@Auth(roles=...)` 있으면 토큰 role과 대조→불일치 403.

## 6. 보안
- 비번은 **BCrypt 해시만 저장**(평문 금지). 응답/로그에 해시·비번 노출 금지.
- `JWT_SECRET`/DB 접속정보는 환경변수(소스 커밋 금지). 기본 secret은 dev 더미(`change-me…`), 운영 .env 필수.
- 로그인 실패 메시지는 "아이디 또는 비밀번호가 올바르지 않습니다"로 통일(사번 존재 여부 노출 금지).
- ProblemDetail에 내부 사유(스택/SQL) 노출 금지.

## 7. 테스트 전략 (TDD)
- **단위**: BCrypt 일치/불일치, `JwtService` generate→validate(역할/claims), 만료·위조 토큰 → null. 비번 정책(8자/사번동일 금지) 경계.
- **인증 계약**(@WebMvcTest): 로그인 200(+pwdResetRequired)/401, `/auth/password` 200·old불일치 400, `/auth/me` 200.
- **인가**: 보호 엔드포인트 무토큰→401, 일반직원 토큰으로 인사 POST→403, 팀장 토큰→허용. 공개 경로(health/login)→토큰 없이 200.
- **프로비저너 IT**: HR 재직 직원 수만큼 AUTH_ACCOUNT 생성, 재기동 멱등(중복 생성 없음), 초기 비번=사번 검증.
- **회귀**: 보호 적용 후 기존 조회/쓰기 엔드포인트가 토큰과 함께 정상 동작.

## 8. 빌드 분해(예상 plan 순서)
의존성 추가 + `app.jwt` 설정 → V007 AUTH_ACCOUNT → BCrypt PasswordEncoder 빈 + JwtService(단위) → AccountProvisioner(프로비저닝 IT) → AuthService + login/me/password 컨트롤러(계약) → `@Auth` + JwtAuthInterceptor 인가(무토큰/역할) → 기존 쓰기 엔드포인트에 `@Auth(roles=01)` 적용 + 회귀 → 통합/스모크/리뷰.

## 9. 미해결/후속
- 계정 잠금(FAIL_CNT 임계), 리프레시 토큰, 관리자 비번 초기화, 비번 분실 복구 — 후속.
- 역할별 **데이터 범위 필터**(팀장 전체/업무리더 파트/일반 본인)는 이번 범위 밖(현재는 조회는 인증만). 필요 시 후속 spec.
- 운영 `JWT_SECRET`/프로파일, 프론트 토큰 저장·401 재로그인(프론트 영역).
