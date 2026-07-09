# IT 개발팀 리소스 현황 대시보드 — 백엔드 설계서

- 작성일: 2026-06-30
- 대상: IT 개발팀 리소스 현황 대시보드의 **백엔드(API)**. 프론트엔드 화면은 만들지 않는다(Swagger UI / Postman으로 검증).
- 상위 규칙: `CLAUDE.md`(MUST 제약), API 규약은 `docs/API.md`.
- 원천 요건: `prd/초안요건서.rtf`, 실제 기간계 쿼리 `쿼리/쿼리.sql`.

## 1. 목적 & 범위

개발팀의 **① 월별 개발량 추이 · ② 리소스(M/M) 가동률 · ③ 주요 SR Top 5**를 한 화면에 제공하는
내부 대시보드의 백엔드. 기간계(운영 SR Oracle)는 **조회 전용**으로 읽고, 대시보드 앱 자신의
데이터(집계 결과·인력 시드)는 별도 **DB2(Oracle)** 에 저장한다.

### 1.1 이번 빌드 범위 (A + D + C)

- **A. 공통기반**: 2 DataSource(기간계 read-only / DB2 CRUD) · MyBatis 분리 · HikariCP 분리 ·
  응답 envelope · ProblemDetail · `GET /health` · 일배치(`@Scheduled`) 스켈레톤.
- **D. 마스터·인력 (먼저 구축)**:
  - `CD_COMMON` — 공통코드 테이블(`GRP_CD`='SR_TPCD' 등, 값→이름) + SR유형 시드 + 조회 API
  - `HR_DEVELOPER` — 인력 테이블(사번·이름·부서·파트·직급·역할·개발여부·상태) + 초기 시드(팀장/일반직원) + CRUD API
  - 부서↔파트 매핑 및 가용 M/M 분모는 **인력 테이블 기준**.
- **C. 대시보드 3종 + 집계**:
  - `dev-volume` — 월별 개발량(SR 건수) 추이, 팀→파트→개발자 드릴다운
  - `resource` — M/M 가동률 도넛 + 야근 상세
  - `sr-project` — 주요 SR Top 5 (M/M 기준 필터, 5개씩)
  - **수동 집계 트리거** — 특정 월(또는 월 범위)을 지정해 집계를 on-demand 실행(과거 데이터 백필·재집계)

### 1.2 이번 범위 밖 (별도 spec — B)

로컬 사번 로그인(JWT, 초기 비번=사번, 비번 초기화) · **역할 기반 인가(팀장/업무리더/일반직원)** ·
LDAP/AD 연동. D에서 만드는 `HR_DEVELOPER`는 인력 데이터(역할 값 포함)를 **저장만** 하고,
그 역할로 **접근을 제어(인가)** 하는 것은 B의 몫이다. B는 D의 `HR_DEVELOPER`에 로그인 컬럼
(비밀번호 해시·초기화 플래그 등)을 **확장**해 재사용한다.

### 1.3 빌드 순서

**A(공통기반) → D(마스터·인력: 공통코드 + 인력 테이블·API) → C(대시보드 3종)**.
C의 집계·드릴다운이 D의 인력/코드 마스터를 참조하므로 D를 먼저 완성한다. B(인증·인가)는 이후 별도 사이클.

## 2. 기술 스택 (CLAUDE.md 2장 고정)

Java 21 · Spring Boot 3.x · Gradle(Groovy) · MyBatis · Oracle 2개(기간계 조회전용 / DB2 CRUD) ·
HikariCP(DataSource별 분리) · JUnit5 + AssertJ + Mockito · springdoc-openapi(Swagger UI) · Postman 컬렉션.
테스트 DB는 Oracle 23ai Free(도커/Testcontainers)이나 **운영은 19c** → 19c 호환 문법만 사용.

## 3. 아키텍처

```
                  ┌────────────────── Spring Boot 3.x (JDK 21) ──────────────────┐
 [기간계 Oracle]  │  legacyDataSource (read-only, 풀 ≤8, stmt timeout 5s)         │
   091/093/097 ◄──┼──  mapper.legacy : 집계 배치 원천 읽기 (SELECT 전용)            │
                  │                                                              │
 [DB2 Oracle]     │  appDataSource(@Primary) : CRUD                              │
   DASH_DEV_AGG ◄─┼──  mapper.app : 대시보드 조회 + 집계 저장 + HR 조회            │
   DASH_SR_PROJECT│                                                              │
   HR_DEVELOPER   │  ┌─────────────────────────────────────────────────────────┐│
   BATCH_RUN_LOG  │  │ AggregationService.run(periodYm):                        ││
                  │  │   기간계 read(query1,query2) → 계산 → DB2 MERGE upsert     ││
   @Scheduled ────┼─►│   (period 키 멱등) → BATCH_RUN_LOG 기록                    ││
   POST /aggregations│└─────────────────────────────────────────────────────────┘│
                  │  REST /api/v1/* : envelope + ProblemDetail + springdoc        │
                  └────────────────────────────────────────────────────────────────┘
                            ▲ Swagger UI / Postman (프론트 화면 없음)
```

### 3.1 DataSource 2개 (CLAUDE.md 3.1 — MUST)

`@Bean`으로 두 세트를 직접 구성(자동설정 의존 X). 각자 `SqlSessionFactory` · `TxManager` ·
`@MapperScan`(`mapper.legacy` / `mapper.app`) · XML 폴더(`mapper/legacy` / `mapper/app`) 분리.

| 이름 | 대상 | 권한 | 용도 |
|---|---|---|---|
| `legacyDataSource` | 기간계 | **SELECT 전용** | 집계 배치 원천 읽기 |
| `appDataSource` `@Primary` | DB2 | CRUD | 대시보드 집계 저장·조회, 인력 시드 |

### 3.2 기간계 절대 규칙 (CLAUDE.md 3.2 — MUST)

- 기간계 매퍼에 INSERT/UPDATE/DELETE/MERGE/DDL **절대 금지. SELECT만.**
- 기간계 조회 `@Transactional(value="legacyTxManager", readOnly=true)`, DB2 쓰기 `@Transactional("appTxManager")`.
- 기간계 Hikari `read-only:true`, 풀 작게(≤8), connection-timeout 짧게, **statement timeout 5초**.
- 값 주입은 바인드 변수 `#{}` 만. `${}`는 화이트리스트 검증된 정렬/컬럼명만.
- 기간계가 죽어도 대시보드(DB2 기반)는 정상 동작(장애 격리).

### 3.3 읽기 경로 — 이번 빌드 결정

세 위젯과 드릴다운은 모두 **DB2 집계 테이블**에서 읽는다. 집계 배치가 query1을 **개발자 그레인**으로
저장하므로 파트/개발자 드릴다운까지 DB2로 충분하다. 기간계 **실시간** 조회는 **집계 배치(스케줄·수동 트리거)**
경로에서만 사용한다.

> 비고: CLAUDE.md 3.3은 "상세 드릴다운=기간계 실시간"을 기본으로 두었다. 본 빌드는 집계가 이미
> 개발자 그레인을 갖는 점을 이용해 드릴다운도 DB2 집계로 제공한다(단순화·장애격리). 실시간 정합이
> 필요해지면 상세 경로만 기간계 실시간으로 분리한다.

## 4. 데이터 모델

### 4.1 기간계 (읽기 전용 — 우리가 만들지 않음)

`쿼리/쿼리.sql`의 실제 컬럼 기준. **구현 시 `/ora-db`로 실제 스키마(테이블/컬럼/타입)를 재확인**한 뒤
매퍼/DTO를 작성한다(CLAUDE.md 3.5 — 컬럼명 추측 금지). 테스트는 같은 역할의 도커 Oracle로 재현한다.

| 테이블 | 용도 | 핵심 컬럼(확인 대상) |
|---|---|---|
| `TBCPPE091M00` | SR 마스터 | `SR_NO, TITL_CNTT, SR_TPCD, SR_REG_STAT_CODE, DPCD, PRCH_DPCD, REG_DATE, RFLC_SCDL_DATE` |
| `TBCPPE093L00` | 계획라인 | `SR_NO, SPIC_EMPNO, JOB_EXEC_HOUR, JOB_MANM, FIN_DATE, APRV_YN, MNPL_EMPNO` |
| `TBCPPE097L00` | SR유형코드 | `SR_TPCD, SR_TPCD_NAME` |

**원천 쿼리 의미(`쿼리/쿼리.sql`):**

- **query1 (개발자×SR유형 건수·M/M)**: `091⨝093`, `B.APRV_YN='Y'`,
  `SUBSTR(B.FIN_DATE,1,6)=대상월`, 상태코드 `SR_REG_STAT_CODE NOT IN ('00','09','16','97','99','22')`,
  `SR_TPCD NOT IN ('15')`. `SPIC_EMPNO`별 + SR_CLS별로 `COUNT(*)=SR_CNT`,
  `ROUND(SUM(JOB_EXEC_HOUR)/166,2)=JOB_MM`.
  - SR_CLS 분류: `('01','10')→'01' 개발요청`, `('03','04','05')→'02' 유지보수`, `'09'→'03' 자료요청`, `else '99'`.
- **query2 (Top SR)**: `091⨝093 LEFT JOIN 097`, `SR_REG_STAT_CODE IN ('02','03','04','05','17','06','07')`,
  `SR_NO`별 `SUM(TO_NUMBER(TRIM(JOB_MANM)))=TOT_MM`, `COUNT(DISTINCT MNPL_EMPNO)=EMP_CNT`,
  `HAVING TOT_MM >= 기준`, `ORDER BY TOT_MM DESC`.

### 4.2 DB2 (신규 — `db/migration/V001__*.sql`, 19c 호환)

```
DASH_DEV_AGG       월별 기본집계 (query1 그레인)
   PK(PERIOD_YM, EMPNO, SR_CLS)
   SR_CNT NUMBER, JOB_MM NUMBER(7,2), + 감사컬럼
   → dev-volume(건수 롤업) + resource(EMPNO별 MM→야근, 파트 사용중 MM) 둘 다 파생

DASH_SR_PROJECT    월별 Top SR (query2 그레인)
   PK(PERIOD_YM, SR_NO)
   TITL_CNTT, SR_TPCD, SR_TPCD_NAME, TOT_MM NUMBER(7,2), EMP_CNT NUMBER,
   PRCH_DPCD, DPCD, REG_DATE, RFLC_SCDL_DATE, + 감사컬럼

HR_DEVELOPER       인력 마스터  PK(EMPNO)
   EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN CHAR(1), STATUS_CD, + 감사컬럼
   → 파트/팀 롤업 매핑(인력 기준) + 가용 M/M 분모(DEV_YN='Y' AND STATUS_CD='재직' 인원수)
   ※ ROLE_CD는 '팀장'/'일반직원' 등 값을 저장만 한다(인가는 B의 몫). B에서 로그인 컬럼 확장.

CD_COMMON          공통코드  PK(GRP_CD, CD_VAL)
   CD_NM, SORT_NO, USE_YN CHAR(1), + 감사컬럼
   → 예: GRP_CD='SR_TPCD', CD_VAL='1', CD_NM='개발요청'. SR유형·상태코드 등 코드성 값 통제

BATCH_RUN_LOG      집계 실행이력  PK(RUN_ID)
   PERIOD_YM, TRIGGER('SCHEDULED'|'MANUAL'), STATUS('OK'|'FAIL'),
   DEV_ROWS, SR_ROWS, STARTED_AT, FINISHED_AT, MSG
```

- 네이밍/공통컬럼(`CREATED_AT/BY, UPDATED_AT/BY`)/플래그 `CHAR(1) 'Y'/'N'`/제약·인덱스 규칙은 CLAUDE.md 3.5.
- DDL은 버전 파일로 관리, 기존 파일 수정 금지(변경은 새 파일). 운영/테스트 동일 적용.

### 4.3 초기 시드 (DB2, `db/migration` 또는 별도 seed)

- **`CD_COMMON` — SR유형(`GRP_CD='SR_TPCD'`)**: 기간계 `TBCPPE097L00` 기준
  `1 개발요청 / 2 유지보수 / 3 자료요청 / 5 인프라SR / 17 고객안내 발송 / 18 데이타변경 / 19 원장변경`.
  (필요 시 `GRP_CD='SR_DETL_TPCD'` 상세코드도 동일 방식으로 시드.)
- **`HR_DEVELOPER` — 임시 시드**: 팀장 1명(`DEV_YN='N'`, ROLE_CD='팀장'), 일반직원 N명
  (`DEV_YN='Y'`, ROLE_CD='일반직원', 파트 배정, STATUS_CD='재직'). 가용 M/M 분모·드릴다운 테스트용.
- query1의 SR_CLS(개발요청/유지보수/자료요청/기타) 분류 규칙은 SR_TPCD→SR_CLS 매핑이며,
  `CD_COMMON`에 매핑 컬럼을 두거나 설정으로 관리한다(구현 시 결정).

## 5. API 엔드포인트 (`/api/v1`)

응답 envelope `{ "data":..., "meta":... }`, 에러 RFC 7807 `ProblemDetail`, 월 레이블 `monthLabel`("26.05"),
전부 springdoc 문서화. 검증 실패 → 400.

| Method | Path | 설명 | 소스 |
|---|---|---|---|
| GET | `/health` | 서버 가동 확인(테스트환경 프론트용). `{status:"UP", timestamp}` 만. 인증 불필요. | — |
| GET | `/codes?grpCd=SR_TPCD` | 공통코드 조회(그룹별 값→이름) | DB2 |
| GET | `/developers?part=&devYn=&status=&page=&size=` | 인력 목록 조회 | DB2 |
| GET | `/developers/{empno}` | 인력 단건 조회 | DB2 |
| POST | `/developers` | 인력 등록 | DB2 |
| PUT | `/developers/{empno}` | 인력 수정 | DB2 |
| DELETE | `/developers/{empno}` | 인력 삭제(또는 상태 비활성) | DB2 |
| GET | `/dev-volume?unit=team\|part\|dev&period=6m\|12m&unitId=` | 월별 개발량(SR 건수) 추이 + 드릴다운(SR유형별 포함) | DB2 |
| GET | `/resource?period=YYYYMM` | 가동률(사용중/가용/%) 도넛 데이터 | DB2 |
| GET | `/resource/overtime?period=YYYYMM&part=` | 야근 상세(개발자 리스트 + 파트 평균 야근) | DB2 |
| GET | `/sr-projects?period=YYYYMM&minMm=0.6&type=&page=0&size=5` | Top SR(기준 이상, M/M 내림차순, 5개씩) | DB2 |
| POST | `/aggregations` | 수동 집계 실행. body `{ "periodYm":"202605" }` 또는 `{ "from":"202601","to":"202605" }` | 기간계→DB2 |
| GET | `/aggregations` | 집계 실행 이력 | DB2 `BATCH_RUN_LOG` |

> `POST /aggregations`는 이번엔 **공개**다(인증 B 미적용). B 단계에서 **역할 게이트(관리자)** 를 추가한다 — 8장 보안.

### 5.1 헬스 체크

테스트 환경에서 "서버가 떴는지"를 프론트에 알리는 단순 용도. 컴포넌트별 DB 상태/프로브는 두지 않는다.

```json
{ "data": { "status": "UP", "timestamp": "2026-06-30T09:00:00Z" } }
```

## 6. 집계 배치 (스케줄 + 수동 공유)

```
AggregationService.run(periodYm):
  1) 기간계 read: query1(개발자×SR유형 건수·MM, FIN_DATE월=periodYm)
                  query2(Top SR, periodYm)
  2) 계산/정규화 (파트/팀 롤업은 조회 시 HR_DEVELOPER 조인)
  3) DB2 MERGE upsert → DASH_DEV_AGG / DASH_SR_PROJECT  (PERIOD_YM 키 → 멱등)
  4) BATCH_RUN_LOG 기록(트리거·건수·상태)
```

- `@Scheduled`(매일) → `run(현재월)`.
- `POST /aggregations` → `run(지정월)`, 범위 요청이면 `from~to` 각 월 순차 `run`.
- 멱등성: 같은 월 재실행 = 동일 결과(MERGE). 실패 시 해당 월 롤백 + 로깅, API 서빙과 분리.

## 7. 계산/환산 규칙 (테스트로 경계 고정 — CLAUDE.md 4장)

- `1 M/M = 166h` → `app.mm.hours-per-month`(하드코딩 금지). M/M = `SUM(JOB_EXEC_HOUR)/166`.
- 가용 M/M = (DEV_YN='Y' ∧ STATUS_CD='재직') 인원수 × 1 M/M.
- 사용중 M/M = 대상월 진행중 SR 계획 M/M 합. 가동률 = 사용중 ÷ 가용 (**분모 0 방어**).
- 야근 M/M(개발자) = `max(투입 M/M − 1.0, 0)`. 파트 평균 야근 = Σ(야근) ÷ 파트 개발인원.
- Top 기준 = M/M ≥ `minMm`(기본 0.6 ≈ 100h/166), M/M 내림차순.
- 경계값: 0 / 1.0 정확히 / 1.0 초과(예 1.2→0.2) / 0.6 기준 경계.

## 8. 에러 처리 · 장애 격리 · 보안

- 기간계 장애 시에도 대시보드 메인(DB2 기반)은 정상 동작(장애 격리). `/health`는 단순 UP만 본다.
- 입력검증(`unit`, `period`, `minMm`, `periodYm`, 페이징) 실패 → 400 ProblemDetail. `${}` 금지(정렬/컬럼 화이트리스트만).
- 예외 → `@RestControllerAdvice` → `ProblemDetail`. 내부 SQL/테이블명/스택트레이스 응답 노출 금지.
- 접속정보/비밀번호는 소스 커밋 금지(환경변수/외부설정), 프로파일 `local/dev/prod` 분리.
- `POST /aggregations`는 본 빌드에서 공개 → **B 단계에서 관리자 역할 인가 필수**(현 단계 알려진 위험으로 명시).

## 9. 테스트 전략 (TDD — CLAUDE.md 5장)

- **계산 단위테스트(최우선)**: M/M 환산(166 설정 주입), 야근 1.0 경계, 가동률 분모 0, Top 0.6 경계.
- **컨트롤러 계약**: `@WebMvcTest` — 파라미터 검증 400, envelope/필드, ProblemDetail 포맷.
- **매퍼 통합**: Testcontainers Oracle(19c 호환). **기간계 매퍼 SELECT-only 검증**(쓰기 SQL 부재).
- **배치 멱등성**: 같은 월 2회 실행 = 동일 결과.
- 읽기 경로: 메인/드릴다운이 DB2 집계를 읽는지 검증.

## 10. 미해결/후속 확인 항목

- 기간계 실제 컬럼명/타입 — 구현 직전 `/ora-db`로 확인(특히 `FIN_DATE` 타입, `JOB_MANM` 문자열 여부).
- **SR_TPCD 자릿수**: `쿼리/쿼리.sql`은 `IN ('01','10')`(2자리), `TBCPPE097L00`은 `1/2/3/17`(비패딩).
  실제 저장 형식을 `/ora-db`로 확인해 `CD_COMMON` 시드와 매퍼 조건을 통일한다.
- 부서(DPCD/PRCH_DPCD) ↔ 파트(PART_CD) 매핑 규칙 — HR_DEVELOPER 시드 설계 시 확정.
- dev-volume 드릴다운에서 파트 식별을 기간계 부서코드로 할지 HR_DEVELOPER 파트로 할지(인력 기준 권장).
- 운영 기간계/DB2 접속정보(host/service/계정) — prod 프로파일.
- B(인증/인사/역할) 시작 시 `HR_DEVELOPER` → 인사/로그인 테이블 확장 스키마.
