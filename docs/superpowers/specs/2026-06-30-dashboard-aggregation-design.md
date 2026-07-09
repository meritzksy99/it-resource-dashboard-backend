# IT 개발팀 리소스 대시보드 — C(대시보드 3종 + 집계) 설계서

- 작성일: 2026-06-30
- 선행: A(공통기반)+D(마스터·인력) 완료(main 머지). 이 문서는 **C 단계** 설계.
- 상위 규칙: `CLAUDE.md`(MUST). 전체 설계: `docs/superpowers/specs/2026-06-30-resource-dashboard-backend-design.md`.
- 원천 쿼리: `쿼리/쿼리.sql` (query1=개발자×SR유형 건수·M/M, query2=Top SR).

## 1. 목적 & 범위

기간계(Oracle, **조회 전용**)의 SR 데이터를 월 단위로 집계해 DB2 `DASH_*` 테이블에 적재하고,
세 대시보드 위젯(① 월별 개발량 추이 ② 리소스 M/M 가동률 ③ 주요 SR Top 5)과 **과거 달 수동 백필**을 제공한다.

### 1.1 이번 범위
- **코드 일원화 정리(V003)**: `EMP_STATUS` 코드화, `SR_CLS` 분류 매핑을 `CD_COMMON`에 저장(앱/쿼리 하드코딩 제거).
- **집계 테이블(V004)**: `DASH_DEV_AGG`, `DASH_RESOURCE`, `DASH_SR_PROJECT`, `BATCH_RUN_LOG`.
- **기간계 매퍼(SELECT 전용)**: query1/query2를 읽는 `mapper.legacy` 매퍼 신설 + JDBC 레벨 타임아웃 보강 + SELECT-only 가드 테스트.
- **집계 배치**: `period` 파라미터 멱등 배치(`@Scheduled` 일배치 + 수동 트리거 API로 과거 백필).
- **조회 API**: `dev-volume`, `resource`, `resource/overtime`, `sr-projects`, `POST/GET aggregations`.

### 1.2 범위 밖
- B(로컬 사번 로그인·역할 인가·인사 CRUD 화면·LDAP) — 별도 spec.
- 운영 19c 접속정보(prod 프로파일), Postman 컬렉션 — 후속.

## 2. 코드 일원화 (V003)

`CD_COMMON`에 부가속성 컬럼 **`ATTR1 VARCHAR2(30) NULL`** 추가(코드의 부모/분류 매핑용).

- **EMP_STATUS 코드화**: `CD_COMMON` `GRP_CD='EMP_STATUS'` 값을 `'01'=재직`, `'02'=휴직`으로 재정의.
  `HR_DEVELOPER.STATUS_CD`는 코드값 저장. CHECK 제약을 `('01','02')`로 교체, 기존 시드/데이터 `'재직'→'01'`, `'휴직'→'02'` 재코딩.
- **SR_CLS 분류 매핑**:
  - `GRP_CD='SR_CLS'` 행: `'01'=개발요청`, `'02'=유지보수`, `'03'=자료요청`, `'99'=기타`.
  - `GRP_CD='SR_TPCD'` 각 행의 `ATTR1` = 소속 SR_CLS 코드. (예: SR_TPCD `'1'`(개발요청)→`ATTR1='01'`, `'2'`(유지보수)→`'02'`, `'3'`(자료요청)→`'03'`, 그 외→`'99'`.)
  - 집계는 `기간계 SR_TPCD → CD_COMMON(SR_TPCD).ATTR1 → SR_CLS`로 분류. 앱/SQL에 CASE 하드코딩 금지.

> ⚠️ 기간계 `SR_TPCD` 실제 저장 자릿수(`'1'` vs `'01'`)는 구현 직전 `/ora-db`로 확인 후 `ATTR1` 매핑값을 맞춘다(`쿼리/쿼리.sql`은 `'01','10'` 표기, `TBCPPE097L00` 엑셀은 `1,2,3`).

## 3. 집계 테이블 (DB2, V004 — 19c 호환)

```
DASH_DEV_AGG    PK(PERIOD_YM, EMPNO, SR_CLS)
   SR_CNT NUMBER, JOB_MM NUMBER(7,2), +감사
   - 기간계 query1 그레인(EMPNO=SPIC_EMPNO). dev-volume(건수 롤업) + 개발자별 야근 소스.

DASH_RESOURCE   PK(PERIOD_YM, UNIT_TYPE, UNIT_ID)     UNIT_TYPE ∈ 'TEAM' | 'PART'
   HEADCOUNT NUMBER, AVAIL_HEADCOUNT NUMBER, AVAIL_MM NUMBER(9,2),
   USED_MM NUMBER(9,2), OVERTIME_MM NUMBER(9,2), +감사
   - 팀/파트 단위 리소스 '집계 시점 스냅샷'. 가동률=USED_MM÷AVAIL_MM(조회 시 파생, 분모 0 방어).
   - TEAM 행=팀 총합(메인 도넛), PART 행=파트별(드릴다운·팀별 가용량/야근).

DASH_SR_PROJECT PK(PERIOD_YM, SR_NO)
   TITL_CNTT, SR_TPCD, SR_TPCD_NAME, TOT_MM NUMBER(7,2), EMP_CNT NUMBER,
   PRCH_DPCD, DPCD, REG_DATE, RFLC_SCDL_DATE, +감사
   - 기간계 query2 그레인.

BATCH_RUN_LOG   PK(RUN_ID)
   PERIOD_YM, TRIGGER('SCHEDULED'|'MANUAL'), STATUS('OK'|'FAIL'),
   DEV_ROWS, SR_ROWS, STARTED_AT, FINISHED_AT, MSG
```

- 공통 감사컬럼/CHAR(1) 플래그/네이밍 규칙은 CLAUDE.md 3.5. DDL은 `db/migration/VNNN`.

## 4. 기간계 매퍼 (SELECT 전용)

- `mapper.legacy`에 query1/query2 매퍼 신설. **INSERT/UPDATE/DELETE/MERGE/DDL 절대 금지.** 값 주입 `#{}` 만.
- query1: `091⨝093`, `B.APRV_YN='Y'`, `SUBSTR(B.FIN_DATE,1,6)=#{periodYm}`,
  `SR_REG_STAT_CODE NOT IN (...)`, `SR_TPCD NOT IN ('15')`, `GROUP BY SPIC_EMPNO, SR_TPCD`(또는 분류) → `SR_CNT, SUM(JOB_EXEC_HOUR)`.
  SR_CLS 분류는 DB2 적재 단계에서 `CD_COMMON` 매핑으로 부여(기간계 SQL은 원천 SR_TPCD까지만).
- query2: `091⨝093 LEFT JOIN 097`, `SR_REG_STAT_CODE IN (...)`, `GROUP BY ...`, `HAVING SUM>=#{minMm}`, `ORDER BY TOT_MM DESC`.
- **JDBC 레벨 타임아웃 보강**: `datasource.legacy.data-source-properties`에 `oracle.jdbc.ReadTimeout`/`oracle.net.CONNECT_TIMEOUT` 추가(MyBatis `defaultStatementTimeout=5`와 이중 안전망).
- 실제 컬럼/타입/상태코드 목록은 구현 직전 `/ora-db`로 확인 후 작성(추측 금지, CLAUDE.md 3.5).

## 5. 집계 배치 (period 멱등)

```
AggregationService.run(periodYm):
  1) 기간계 read: query1(개발자별 SR_TPCD 건수·시간, FIN_DATE월=periodYm), query2(Top SR)
  2) M/M 환산(SUM(JOB_EXEC_HOUR)/166), SR_TPCD→SR_CLS 매핑(CD_COMMON)
  3) HR 스냅샷 계산: PART/TEAM별 HEADCOUNT, AVAIL_HEADCOUNT(DEV_YN='Y' AND STATUS='01'),
     AVAIL_MM, USED_MM(=Σ JOB_MM), OVERTIME_MM(=Σ max(개발자MM−1,0))
  4) DB2 MERGE upsert → DASH_DEV_AGG / DASH_RESOURCE(TEAM+PART행) / DASH_SR_PROJECT (PERIOD_YM 키 → 멱등)
  5) BATCH_RUN_LOG 기록
- @Scheduled(매일 02:00) → run(현재월).  POST /aggregations → run(지정월) 또는 from~to 순차 루프.
- 실패: 해당 월 롤백 + 로깅, API 서빙과 분리. 재실행 안전(멱등).
```

## 6. 조회 API (`/api/v1`, 전부 DB2 집계에서)

| Method | Path | 설명 |
|---|---|---|
| GET | `/dev-volume?unit=team\|part\|dev&period=6m\|12m&unitId=` | 월별 SR 건수 추이(SR_CLS 분해). **기본 6개월**. 팀→파트→개발자 드릴다운. HR 미매칭=**'미분류'** |
| GET | `/resource?period=YYYYMM&unit=team\|part&unitId=` | 단위별 인원/가용인원/가용 M/M/사용중 M/M/가동률/야근(DASH_RESOURCE 1행) |
| GET | `/resource/overtime?period=YYYYMM&part=` | 개발자별 야근 M/M 리스트(DASH_DEV_AGG 합) + 파트 평균 야근(DASH_RESOURCE) |
| GET | `/sr-projects?period=YYYYMM&minMm=0.6&type=&page=0&size=5` | Top SR(M/M≥기준, 내림차순, 5개씩) |
| POST | `/aggregations` `{periodYm}` 또는 `{from,to}` | 수동 집계(과거 백필) |
| GET | `/aggregations` | 집계 실행 이력(BATCH_RUN_LOG) |

- 응답 envelope `{data, meta}`, 에러 ProblemDetail(RFC7807), 월 레이블 `monthLabel`("26.05"), springdoc 문서화.
- `POST /aggregations`는 본 빌드 공개 → B에서 관리자 역할 게이트 추가(8장).

## 7. 계산·롤업 규칙 (테스트로 경계 고정)

- `1 M/M = 166h`(`app.mm.hours-per-month`, 하드코딩 금지). M/M=`SUM(JOB_EXEC_HOUR)/166`.
- 가용 M/M = (DEV_YN='Y' ∧ STATUS='01' 재직) 인원수 × 1. 가동률 = USED_MM ÷ AVAIL_MM (**분모 0 방어**).
- 야근(개발자) = `max(MM−1.0, 0)`. 파트 평균 야근 = OVERTIME_MM ÷ 파트 개발인원.
- Top 기준 = M/M ≥ `minMm`(기본 0.6 ≈ 100h/166), 내림차순.
- 롤업: `DASH_DEV_AGG.EMPNO ⨝ HR_DEVELOPER` → PART_CD/팀. **미매칭 → '미분류' 파트**(USED/OVERTIME은 TEAM 총합 포함, AVAIL=0).
- 과거월 가용분모는 **그 달 스냅샷**(DASH_RESOURCE) 사용(현재 HR 아님).
- 경계값: 0 / 1.0 정확히 / 1.0 초과(1.2→0.2) / 0.6 기준 / 분모 0.

## 8. 에러·장애격리·보안

- 메인/드릴다운은 DB2 집계 기반 → **기간계 장애와 격리**. 집계 배치만 기간계 실시간 read.
- 입력검증(`unit/period/minMm/periodYm/페이징`) 실패 → 400 ProblemDetail. `${}`는 화이트리스트 정렬/컬럼만.
- 내부 SQL/테이블명/스택트레이스 응답 노출 금지. 접속정보는 환경변수/프로파일.
- `POST /aggregations` 공개(본 빌드) → B에서 관리자 인가 필수.

## 9. 테스트 전략 (TDD)

- **계산 단위테스트**: M/M 환산(166), 야근 1.0 경계, 가동률 분모 0, Top 0.6 경계, SR_TPCD→SR_CLS 매핑.
- **기간계 매퍼 SELECT-only 가드**: mapper.legacy에 쓰기 SQL 부재 검증 + 별도 legacy 테스트 컨테이너.
- **배치 멱등성**: 같은 월 2회 = 동일 결과(MERGE).
- **롤업/스냅샷**: 미분류 버킷, 과거월 가용분모 스냅샷, TEAM=PART 합 정합.
- **컨트롤러 계약**: `@WebMvcTest` 파라미터 검증·envelope·ProblemDetail.
- 통합: Testcontainers(19c 호환), 머지 전 19c 컨테이너 재검증.

## 10. 빌드 분해(예상 plan 순서)

V003 코드정리 → V004 집계테이블 → 기간계 매퍼(SELECT-only) → 집계 배치(서비스+멱등) →
`POST/GET aggregations` → `sr-projects` → `resource`+`resource/overtime` → `dev-volume` → 통합·리뷰.

## 11. 미해결/후속

- 기간계 실제 컬럼/타입/상태코드 목록·SR_TPCD 자릿수 — 구현 직전 `/ora-db` 확인.
- DPCD(부서) ↔ 팀/파트 정의: 본 설계는 롤업을 HR_DEVELOPER(PART_CD) 기준. 팀=전체 1개 가정(다팀이면 TEAM_ID 도입).
- prod 프로파일/배치 시각/알림 채널 — 운영 단계.
