# DML 점검·개선 관리 — 설계서 (2026-07-08)

## 1. 목적
개발팀이 매주 기간계에 올라온 **DML성 SR**(SR유형 = 데이타변경'18' / 원장변경'19')을 한곳에 모아
**부서·파트 단위로 점검**하고, 필요한 건은 **개선건으로 등록**해 개선방안·완료여부까지 추적한다.
데이터는 **매주 월요일 배치**가 기간계에서 읽어 우리 앱 DB(DB2/app)에 적재한다.

## 2. 데이터 원천 (기간계, SELECT 전용)
사용자가 확정한 `쿼리/DML월별조회쿼리.sql`을 충실히 반영한다. 핵심:
- FROM `TBCPPE091M00 A` JOIN `TBCPPE093L00 B` ON SR_NO JOIN `TBCPPE097L00 C` ON SR_TPCD
  LEFT JOIN `TBCPPU001I00 D` ON A.PRCH_EMPNO = D.EMPNO  (`DISTINCT`으로 093 fan-out 제거)
- WHERE `A.SR_TPCD IN ('18','19')` AND `SUBSTR(A.REG_DATE,1,6) = :baseYm`
- **원 쿼리의 `AND D.BLNG_DPCD = :PRED_DPCD`(부서필터)는 제거** — 담당자를 HR_DEVELOPER와 매칭해 파트/부서를 확정하는 방식으로 대체.
- SELECT에 **`A.PRCH_EMPNO`(IT담당자 사번)를 추가**(HR 매칭 키).
- 컬럼: SR_NO, SR_TPCD_NAME(C), 업무상세분류명(TBCPPE091D02+TBCPPE108C01 서브쿼리, NVL '미등록'),
  SR_REG_STAT_CODE, TITL_CNTT, MSG_CNTT(내용), CUST_INFO_YN,
  요청자(FLNM/BLNG_DPCD via TBCPPU001I00, RQSR_EMPNO), 실제요청자(FLNM, TRTH_RQST_DPCD),
  IT담당자(PRCH_EMPNO→FLNM, D.BLNG_DPCD→TBCPPD001M00.DPNM), REG_DATE, RFLC_SCDL_DATE, PROS_CMPT_DATE.

> 기간계 규칙 준수(CLAUDE.md 3.2): 매퍼는 SELECT만, `#{}` 바인드, `legacyTxManager` readOnly 래핑, 5초 타임아웃.
> 기간계가 죽어도 조회 API(DB2 스냅샷 기반)는 정상 동작(장애 격리).

## 3. 신규 테이블 (V016, Oracle 19c 호환 DDL)
스냅샷(배치 전용)과 사용자 입력(점검/개선)을 **분리**해 배치 재실행이 사용자 입력을 덮지 않게 한다.

### 3.1 `DASH_DML_SR` — 배치 스냅샷 (배치가 소유·MERGE로 갱신)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| SR_NO | VARCHAR2(11) | PK |
| BASE_YM | VARCHAR2(6) | SUBSTR(REG_DATE,1,6) — 월 필터 |
| SR_TPCD | VARCHAR2(2) | 18/19 |
| SR_TPCD_NAME | VARCHAR2(40) | 유형명 |
| BSWR_DETL_NAME | VARCHAR2(100) | 업무상세분류명 |
| SR_REG_STAT_CODE | VARCHAR2(2) | 상태코드 |
| TITL_CNTT | VARCHAR2(400) | 제목 |
| MSG_CNTT | VARCHAR2(2048) | 내용 |
| CUST_INFO_YN | CHAR(1) | 개인(신용)정보유무 |
| RQSR_EMPNO / RQSR_NM / RQSR_DPCD | VARCHAR2(9)/(40)/(4) | 요청자 |
| TRTH_RQST_NM / TRTH_RQST_DPCD | VARCHAR2(40)/(4) | 실제요청자 |
| PIC_EMPNO / PIC_NM / PIC_DPCD / PIC_DPNM | VARCHAR2(9)/(40)/(4)/(40) | IT담당자(=PRCH_EMPNO) |
| DEV_DEPT_CD / DEV_PART_CD | VARCHAR2(30) | HR_DEVELOPER 매칭 결과(스코프용) |
| REG_DATE / RFLC_SCDL_DATE / PROS_CMPT_DATE | VARCHAR2(8) | 등록일/반영예정일/종료처리일 |
| SYNCED_AT | TIMESTAMP | 마지막 배치 반영시각 |
| CREATED_AT/BY, UPDATED_AT/BY | 감사 | |

제약/인덱스: `PK_DASH_DML_SR`, `CK_DASH_DML_SR_CUST`(CUST_INFO_YN IN 'Y','N' 또는 NULL 허용),
`IX_DASH_DML_SR_SCOPE(BASE_YM, DEV_DEPT_CD, DEV_PART_CD)`.

### 3.2 `DASH_DML_CHECK` — 점검/개선 (사용자 입력, 배치가 절대 안 건드림)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| SR_NO | VARCHAR2(11) | PK, FK→DASH_DML_SR |
| CHECK_YN | CHAR(1) DEFAULT 'N' | 점검여부 |
| IMPROVE_YN | CHAR(1) DEFAULT 'N' | 개선대상 등록여부 |
| IMPROVE_PLAN | VARCHAR2(2000) | 개선방안 |
| PLAN_CMPT_DATE | VARCHAR2(8) | 완료예정일 |
| CMPT_YN | CHAR(1) DEFAULT 'N' | 완료여부 |
| REMARK | VARCHAR2(1000) | 비고 |
| CREATED_AT/BY, UPDATED_AT/BY | 감사 | |

제약: `PK_DASH_DML_CHECK`, `FK_DASH_DML_CHECK_SR`, 각 플래그 `CK_...`(IN 'Y','N').
조회 시 `DASH_DML_SR LEFT JOIN DASH_DML_CHECK` — CHECK 행이 없으면 미점검('N')으로 표시.

## 4. 배치 (기간계 read → app write, 멱등)
- `DmlSrLegacyReader.read(baseYm)` → `legacyTxManager` readOnly로 기간계 조회.
- `DmlSyncService.sync(baseYm, triggeredBy)`:
  1. 기간계에서 해당 월 18/19 SR 조회.
  2. 각 행 `PIC_EMPNO`를 HR_DEVELOPER와 매칭 → `DEV_DEPT_CD/DEV_PART_CD` 확정. **미매칭(개발팀 담당 아님)은 제외.**
  3. `MERGE INTO DASH_DML_SR` (SR_NO 키): 매칭 시 배치 컬럼만 UPDATE + SYNCED_AT 갱신, 신규 시 INSERT. **DASH_DML_CHECK는 손대지 않음.**
  4. `@Transactional("appTxManager")`. 실패 롤백+로깅.
- 스케줄러: `@Scheduled(cron="${app.dml-sync.cron:0 0 3 * * MON}")` — 이번달(`YYYYMM`) 기준. `AggregationScheduler`와 동일 패턴.
- 반환: 조회건/적재건 수(배치 로그).

## 5. API (`/api/v1/dml-srs`, envelope + ProblemDetail + springdoc)
| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | `/api/v1/dml-srs?baseYm=&unit=dept\|part&partCd=&checked=` | 스냅샷+점검/개선 목록(상태·점검여부 포함) | 조회 스코프 |
| PATCH | `/api/v1/dml-srs/{srNo}/check` `{checkYn}` | 점검여부 토글(upsert CHECK 행) | 쓰기 스코프 |
| PUT | `/api/v1/dml-srs/{srNo}/improvement` `{improvePlan,planCmptDate,cmptYn,remark}` | 개선건 등록/수정(IMPROVE_YN='Y') | 쓰기 스코프 |
| POST | `/api/v1/dml-srs/sync?baseYm=` | 수동/백필 배치 | 팀장(01)·ADMIN |

- 기본 `baseYm` = 이번달. `unit=dept`(부서 전체) 기본, `unit=part`+`partCd`로 파트별.
- 응답 `meta`: { baseYm, scope, total, checkedCount, improveCount }.
- 쓰기 시 대상 SR의 `DEV_DEPT_CD/DEV_PART_CD`가 **본인 스코프 밖이면 403**(fail-closed). 대상 SR 미존재 404.

### RBAC (dev-srs와 동일, AuthContext empno/role/deptCd/partCd)
- **조회**: 03=본인 담당(PIC_EMPNO=본인) · 02=본인 파트 · 01=본인 부서 · ADMIN=전체.
- **쓰기(check/improvement/sync)**: 02=본인 파트 · 01=본인 부서 · ADMIN=전체 · **03=403**. sync는 01·ADMIN만.

## 6. 계산/경계 규칙
- 점검률 = 점검(Y) ÷ 대상 건수(분모 0 방어). (응답 meta 노출용, 서비스 계산)
- 미매칭 담당자 SR 제외, 매칭 SR만 스코프 계산 포함.

## 7. 컴포넌트 (패키지 `com.meritz.dash.dml`)
- `DmlSrLegacyRow`(record), `DmlSr`(스냅샷 record), `DmlCheck`(record), `DmlSrItem`(응답 record).
- `mapper.legacy.DmlSrLegacyMapper` + `mapper/legacy/DmlSrLegacyMapper.xml` (SELECT-only).
- `DmlSrLegacyReader`(legacyTxManager 래핑).
- `mapper.app.DmlSrMapper` + `mapper/app/DmlSrMapper.xml` (MERGE upsert, list join, check/improvement upsert).
- `DmlSyncService`, `DmlSyncScheduler`, `DmlSrService`(RBAC), `DmlSrController`.
- `DmlSyncProperties`(`app.dml-sync.cron`).

## 8. 테스트 (TDD Red→Green→Refactor)
- **Mapper IT**(legacy-fixture): DML 쿼리 18/19·월 필터, DISTINCT, 업무상세분류명 서브쿼리.
- **Sync IT**(app): MERGE 멱등(재실행해도 CHECK 보존), HR 미매칭 제외.
- **Service 단위**: RBAC 조회/쓰기 스코프(03/02/01/ADMIN, fail-closed 403), 점검률 계산.
- **Controller 계약**(@WebMvcTest): 200/403/404, envelope, springdoc 예시.

## 9. Definition of Done
- Red→Green→Refactor, 경계 테스트 통과 / 기간계 매퍼 SELECT-only / DTO 경계·ProblemDetail·springdoc /
  `/review-all` Critical 0 / `./gradlew build` 통과.
