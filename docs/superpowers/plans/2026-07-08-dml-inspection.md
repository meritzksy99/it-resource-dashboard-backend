# DML 점검·개선 관리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 매주 월요일 배치가 기간계의 DML성 SR(유형 18/19)을 앱 DB에 적재하고, 부서·파트 단위 점검 및 개선건 등록/추적을 RBAC API로 제공한다.

**Architecture:** 기간계(SELECT-only, legacyTxManager readOnly) → HR_DEVELOPER 매칭으로 파트/부서 확정 → `DASH_DML_SR`(스냅샷) MERGE upsert. 점검/개선 입력은 별도 `DASH_DML_CHECK`에 저장(배치가 안 건드려 멱등). 조회/쓰기는 dev-srs와 동일한 AuthContext 기반 RBAC.

**Tech Stack:** Java 21, Spring Boot 3.x, MyBatis(XML), Oracle(2 DataSource), JUnit5+AssertJ+Mockito, Testcontainers(gvenzl/oracle-free), springdoc.

## Global Constraints
- Java 21 / Spring Boot 3.x / MyBatis(XML), Gradle Groovy DSL.
- 기간계 매퍼는 **SELECT만**. 값 주입은 `#{}` 바인드만. `legacyTxManager` readOnly.
- DB2(app) 쓰기는 `@Transactional("appTxManager")`. 조회는 readOnly.
- DDL은 **19c 호환만**(BOOLEAN 금지, 플래그는 `CHAR(1) 'Y'/'N'`). 새 마이그레이션은 **새 파일** `V016__...sql`.
- 네이밍: 테이블 `DASH_*`, 제약 `PK_/FK_/CK_/IX_`. 감사컬럼 `CREATED_AT/BY, UPDATED_AT/BY`.
- DTO는 `record`, 컨트롤러는 DTO만 반환. 생성자 주입. 매직넘버 금지.
- 응답 envelope `ApiResponse.of(data, meta)`, 에러 `ProblemDetail`, 모든 엔드포인트 springdoc.
- 패키지 루트: `com.meritz.dash.dml`. 매퍼 스캔: 기간계=`com.meritz.dash.mapper.legacy`, app=`com.meritz.dash.mapper.app`, XML=`mapper/legacy`·`mapper/app`.

---

## File Structure
- Create `src/main/resources/db/migration/V016__create_dml_tables.sql` — 두 테이블 DDL.
- Create `src/main/java/com/meritz/dash/dml/DmlSrLegacyRow.java` — 기간계 조회 row.
- Create `src/main/java/com/meritz/dash/dml/DmlSr.java` — 스냅샷(app) row.
- Create `src/main/java/com/meritz/dash/dml/DmlSrItem.java` — API 응답 item(스냅샷+점검/개선).
- Create `src/main/java/com/meritz/dash/config/DmlSyncProperties.java` — `app.dml-sync.cron`.
- Create `src/main/java/com/meritz/dash/mapper/legacy/DmlSrLegacyMapper.java` + `src/main/resources/mapper/legacy/DmlSrLegacyMapper.xml`.
- Create `src/main/java/com/meritz/dash/dml/DmlSrLegacyReader.java`.
- Create `src/main/java/com/meritz/dash/mapper/app/DmlSrMapper.java` + `src/main/resources/mapper/app/DmlSrMapper.xml`.
- Create `src/main/java/com/meritz/dash/dml/DmlSyncService.java`, `DmlSyncScheduler.java`.
- Create `src/main/java/com/meritz/dash/dml/DmlSrService.java`.
- Create `src/main/java/com/meritz/dash/dml/DmlSrController.java` (+ request DTOs `CheckRequest`, `ImprovementRequest` as nested records).
- Tests: `DmlSrLegacyMapperIT`, `DmlSyncIT`, `DmlSrServiceTest`, `DmlSrControllerTest`.

## Dependency graph (for parallel execution)
- Task 1 (foundation) FIRST.
- Task 2 (legacy mapper+reader) ∥ Task 3 (app mapper) — both need only Task 1.
- Task 4 (sync) needs 2+3. Task 5 (read/write service) needs 3. → 4 ∥ 5.
- Task 6 (controller) needs 5.

---

## Task 1: Foundation — 마이그레이션 + records + properties

**Files:**
- Create: `src/main/resources/db/migration/V016__create_dml_tables.sql`
- Create: `src/main/java/com/meritz/dash/dml/DmlSrLegacyRow.java`, `DmlSr.java`, `DmlSrItem.java`
- Create: `src/main/java/com/meritz/dash/config/DmlSyncProperties.java`

**Interfaces produced (later tasks rely on these EXACT names/types):**
```java
// DmlSrLegacyRow — 기간계 조회 결과 1행
public record DmlSrLegacyRow(
    String srNo, String srTpcd, String srTpcdName, String bswrDetlName,
    String statusCode, String titlCntt, String msgCntt, String custInfoYn,
    String rqsrEmpno, String rqsrNm, String rqsrDpcd,
    String trthRqstNm, String trthRqstDpcd,
    String picEmpno, String picNm, String picDpcd, String picDpnm,
    String regDate, String rflcScdlDate, String prosCmptDate) {}

// DmlSr — DASH_DML_SR 스냅샷(HR 매칭 후 저장 단위). baseYm/devDeptCd/devPartCd 추가.
public record DmlSr(
    String srNo, String baseYm, String srTpcd, String srTpcdName, String bswrDetlName,
    String statusCode, String titlCntt, String msgCntt, String custInfoYn,
    String rqsrEmpno, String rqsrNm, String rqsrDpcd, String trthRqstNm, String trthRqstDpcd,
    String picEmpno, String picNm, String picDpcd, String picDpnm,
    String devDeptCd, String devPartCd,
    String regDate, String rflcScdlDate, String prosCmptDate) {}

// DmlSrItem — API 응답(스냅샷 + 점검/개선). 점검/개선 없으면 기본값.
public record DmlSrItem(
    String srNo, String baseYm, String srTpcd, String srTpcdName, String bswrDetlName,
    String statusCode, String titlCntt, String msgCntt, String custInfoYn,
    String rqsrNm, String rqsrDpcd, String trthRqstNm, String trthRqstDpcd,
    String picEmpno, String picNm, String picDpnm, String devDeptCd, String devPartCd,
    String regDate, String rflcScdlDate, String prosCmptDate,
    String checkYn, String improveYn, String improvePlan, String planCmptDate,
    String cmptYn, String remark) {}
```

- [ ] **Step 1: Write V016 migration**
```sql
-- V016__create_dml_tables.sql — DML 점검·개선 (19c 호환)
CREATE TABLE DASH_DML_SR (
  SR_NO            VARCHAR2(11)  NOT NULL,
  BASE_YM          VARCHAR2(6),
  SR_TPCD          VARCHAR2(2),
  SR_TPCD_NAME     VARCHAR2(40),
  BSWR_DETL_NAME   VARCHAR2(100),
  SR_REG_STAT_CODE VARCHAR2(2),
  TITL_CNTT        VARCHAR2(400),
  MSG_CNTT         VARCHAR2(2048),
  CUST_INFO_YN     VARCHAR2(1),
  RQSR_EMPNO       VARCHAR2(9),
  RQSR_NM          VARCHAR2(40),
  RQSR_DPCD        VARCHAR2(4),
  TRTH_RQST_NM     VARCHAR2(40),
  TRTH_RQST_DPCD   VARCHAR2(4),
  PIC_EMPNO        VARCHAR2(9),
  PIC_NM           VARCHAR2(40),
  PIC_DPCD         VARCHAR2(4),
  PIC_DPNM         VARCHAR2(40),
  DEV_DEPT_CD      VARCHAR2(30),
  DEV_PART_CD      VARCHAR2(30),
  REG_DATE         VARCHAR2(8),
  RFLC_SCDL_DATE   VARCHAR2(8),
  PROS_CMPT_DATE   VARCHAR2(8),
  SYNCED_AT        TIMESTAMP,
  CREATED_AT       TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CREATED_BY       VARCHAR2(30),
  UPDATED_AT       TIMESTAMP,
  UPDATED_BY       VARCHAR2(30),
  CONSTRAINT PK_DASH_DML_SR PRIMARY KEY (SR_NO)
);
CREATE INDEX IX_DASH_DML_SR_SCOPE ON DASH_DML_SR (BASE_YM, DEV_DEPT_CD, DEV_PART_CD);

CREATE TABLE DASH_DML_CHECK (
  SR_NO          VARCHAR2(11) NOT NULL,
  CHECK_YN       CHAR(1) DEFAULT 'N' NOT NULL,
  IMPROVE_YN     CHAR(1) DEFAULT 'N' NOT NULL,
  IMPROVE_PLAN   VARCHAR2(2000),
  PLAN_CMPT_DATE VARCHAR2(8),
  CMPT_YN        CHAR(1) DEFAULT 'N' NOT NULL,
  REMARK         VARCHAR2(1000),
  CREATED_AT     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CREATED_BY     VARCHAR2(30),
  UPDATED_AT     TIMESTAMP,
  UPDATED_BY     VARCHAR2(30),
  CONSTRAINT PK_DASH_DML_CHECK PRIMARY KEY (SR_NO),
  CONSTRAINT FK_DASH_DML_CHECK_SR FOREIGN KEY (SR_NO) REFERENCES DASH_DML_SR (SR_NO),
  CONSTRAINT CK_DML_CHECK_YN   CHECK (CHECK_YN   IN ('Y','N')),
  CONSTRAINT CK_DML_IMPROVE_YN CHECK (IMPROVE_YN IN ('Y','N')),
  CONSTRAINT CK_DML_CMPT_YN    CHECK (CMPT_YN    IN ('Y','N'))
);
```

- [ ] **Step 2: Write the three records + DmlSyncProperties** (code as in Interfaces block above; properties below)
```java
// DmlSyncProperties.java
package com.meritz.dash.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "app.dml-sync")
public record DmlSyncProperties(String cron) {
    public DmlSyncProperties {
        if (cron == null || cron.isBlank()) cron = "0 0 3 * * MON";
    }
}
```
Register: add `DmlSyncProperties.class` to the existing `@EnableConfigurationProperties({...})` list (search `@EnableConfigurationProperties` in `src/main/java`). If the project uses `@ConfigurationPropertiesScan`, no edit needed — verify.

- [ ] **Step 3: add application.yml default** — under `app:` add:
```yaml
  dml-sync:
    cron: ${DML_SYNC_CRON:0 0 3 * * MON}
```

- [ ] **Step 4: compile** — `./gradlew compileJava -q` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(dml): V016 tables + records + sync properties"`

---

## Task 2: 기간계 매퍼(SELECT-only) + Reader + IT

**Files:**
- Create: `src/main/java/com/meritz/dash/mapper/legacy/DmlSrLegacyMapper.java`
- Create: `src/main/resources/mapper/legacy/DmlSrLegacyMapper.xml`
- Create: `src/main/java/com/meritz/dash/dml/DmlSrLegacyReader.java`
- Test: `src/test/java/com/meritz/dash/dml/DmlSrLegacyMapperIT.java`

**Interfaces:**
- Consumes: `DmlSrLegacyRow` (Task 1).
- Produces:
```java
public interface DmlSrLegacyMapper { List<DmlSrLegacyRow> selectDmlSrs(@Param("baseYm") String baseYm); }
public class DmlSrLegacyReader { public List<DmlSrLegacyRow> read(String baseYm); } // @Transactional("legacyTxManager", readOnly=true)
```

- [ ] **Step 1: Write failing IT** — extends `LegacyFixture`; @BeforeEach idempotent-insert 2 DML SRs (SR_TPCD '18' 이번달 REG_DATE, '19'), 1 non-DML ('01'); @AfterEach delete them. Assert `selectDmlSrs("<yyyymm>")` returns the two 18/19 rows (contains srNo), excludes '01', maps msgCntt/picEmpno. Match `DevSrMapperIT` idempotent-ALTER style (add PRCH_EMPNO/MSG_CNTT if missing). Assert DISTINCT (no fan-out from 093).
- [ ] **Step 2: Run, expect FAIL** — `./gradlew test --tests '*DmlSrLegacyMapperIT' ` → FAIL (mapper missing).
- [ ] **Step 3: Write mapper interface + XML** — XML faithful to `쿼리/DML월별조회쿼리.sql` minus `:PRED_DPCD`, plus `A.PRCH_EMPNO AS picEmpno`:
```xml
<select id="selectDmlSrs" resultType="com.meritz.dash.dml.DmlSrLegacyRow">
  SELECT DISTINCT
    A.SR_NO AS srNo, A.SR_TPCD AS srTpcd, C.SR_TPCD_NAME AS srTpcdName,
    (SELECT NVL(DT.SR_BSWR_DETL_DIV_NAME,'미등록') FROM TBCPPE091D02 S, TBCPPE108C01 DT
      WHERE S.SR_NO=A.SR_NO AND S.SR_BSWR_DETL_DVCD=DT.SR_BSWR_DETL_DVCD(+) AND ROWNUM<=1) AS bswrDetlName,
    A.SR_REG_STAT_CODE AS statusCode, A.TITL_CNTT AS titlCntt, A.MSG_CNTT AS msgCntt, A.CUST_INFO_YN AS custInfoYn,
    (SELECT F.FLNM FROM TBCPPU001I00 F WHERE F.EMPNO=A.RQSR_EMPNO) AS rqsrNm,
    A.RQSR_EMPNO AS rqsrEmpno,
    (SELECT F2.BLNG_DPCD FROM TBCPPU001I00 F2 WHERE F2.EMPNO=A.RQSR_EMPNO) AS rqsrDpcd,
    (SELECT F3.FLNM FROM TBCPPU001I00 F3 WHERE F3.EMPNO=A.TRTH_RQST_EMPNO) AS trthRqstNm,
    A.TRTH_RQST_DPCD AS trthRqstDpcd,
    A.PRCH_EMPNO AS picEmpno,
    (SELECT F4.FLNM FROM TBCPPU001I00 F4 WHERE F4.EMPNO=A.PRCH_EMPNO) AS picNm,
    D.BLNG_DPCD AS picDpcd,
    (SELECT DD.DPNM FROM TBCPPD001M00 DD WHERE DD.DPCD=D.BLNG_DPCD) AS picDpnm,
    A.REG_DATE AS regDate, A.RFLC_SCDL_DATE AS rflcScdlDate, A.PROS_CMPT_DATE AS prosCmptDate
  FROM TBCPPE091M00 A
    JOIN TBCPPE093L00 B ON A.SR_NO=B.SR_NO
    JOIN TBCPPE097L00 C ON A.SR_TPCD=C.SR_TPCD
    LEFT JOIN TBCPPU001I00 D ON A.PRCH_EMPNO=D.EMPNO
  WHERE A.SR_TPCD IN ('18','19')
    AND SUBSTR(A.REG_DATE,1,6) = #{baseYm}
  ORDER BY A.SR_NO
</select>
```
> NOTE for fixture: `TBCPPE091D02`,`TBCPPE108C01`,`TBCPPU001I00`,`TBCPPD001M00` may not exist in legacy-fixture. Add minimal CREATE (idempotent) to the IT `@BeforeEach` OR to `src/test/resources/legacy-fixture/ddl.sql` so the query runs. Insert 1 matching name/dept row so name columns are non-null in assertions. Keep the D02/108 subquery resilient (LEFT via NVL '미등록').
- [ ] **Step 4: Write DmlSrLegacyReader** (mirror `DevSrLegacyReader`, `@Transactional(transactionManager="legacyTxManager", readOnly=true)`).
- [ ] **Step 5: Run IT, expect PASS** — `./gradlew test --tests '*DmlSrLegacyMapperIT'` → PASS.
- [ ] **Step 6: Commit** — `git commit -am "feat(dml): legacy SELECT-only mapper + reader + IT"`

---

## Task 3: app 매퍼(MERGE upsert + 조회 + 점검/개선) + XML

**Files:**
- Create: `src/main/java/com/meritz/dash/mapper/app/DmlSrMapper.java`
- Create: `src/main/resources/mapper/app/DmlSrMapper.xml`

**Interfaces:**
- Consumes: `DmlSr`, `DmlSrItem` (Task 1).
- Produces:
```java
public interface DmlSrMapper {
  int mergeSnapshot(DmlSr sr);                                  // MERGE ON SR_NO, 배치컬럼만 갱신
  List<DmlSrItem> selectList(@Param("baseYm") String baseYm,
      @Param("deptCd") String deptCd, @Param("partCd") String partCd,
      @Param("empno") String empno, @Param("checked") String checked); // nullable 필터
  ScopeRef findScopeRef(@Param("srNo") String srNo);            // 대상 SR 의 dev dept/part (쓰기 RBAC용). 없으면 null
  int upsertCheck(@Param("srNo") String srNo, @Param("checkYn") String checkYn, @Param("actor") String actor);
  int upsertImprovement(@Param("srNo") String srNo, @Param("improvePlan") String improvePlan,
      @Param("planCmptDate") String planCmptDate, @Param("cmptYn") String cmptYn,
      @Param("remark") String remark, @Param("actor") String actor);
  record ScopeRef(String srNo, String devDeptCd, String devPartCd, String picEmpno) {}
}
```

- [ ] **Step 1..N (TDD via Task 4 Sync IT covers mergeSnapshot; add focused XML).** MERGE:
```xml
<update id="mergeSnapshot">
  MERGE INTO DASH_DML_SR t
  USING (SELECT #{srNo} SR_NO FROM DUAL) s ON (t.SR_NO = s.SR_NO)
  WHEN MATCHED THEN UPDATE SET
     BASE_YM=#{baseYm}, SR_TPCD=#{srTpcd}, SR_TPCD_NAME=#{srTpcdName}, BSWR_DETL_NAME=#{bswrDetlName},
     SR_REG_STAT_CODE=#{statusCode}, TITL_CNTT=#{titlCntt}, MSG_CNTT=#{msgCntt}, CUST_INFO_YN=#{custInfoYn},
     RQSR_EMPNO=#{rqsrEmpno}, RQSR_NM=#{rqsrNm}, RQSR_DPCD=#{rqsrDpcd},
     TRTH_RQST_NM=#{trthRqstNm}, TRTH_RQST_DPCD=#{trthRqstDpcd},
     PIC_EMPNO=#{picEmpno}, PIC_NM=#{picNm}, PIC_DPCD=#{picDpcd}, PIC_DPNM=#{picDpnm},
     DEV_DEPT_CD=#{devDeptCd}, DEV_PART_CD=#{devPartCd},
     REG_DATE=#{regDate}, RFLC_SCDL_DATE=#{rflcScdlDate}, PROS_CMPT_DATE=#{prosCmptDate},
     SYNCED_AT=SYSTIMESTAMP, UPDATED_AT=SYSTIMESTAMP, UPDATED_BY='BATCH'
  WHEN NOT MATCHED THEN INSERT
     (SR_NO,BASE_YM,SR_TPCD,SR_TPCD_NAME,BSWR_DETL_NAME,SR_REG_STAT_CODE,TITL_CNTT,MSG_CNTT,CUST_INFO_YN,
      RQSR_EMPNO,RQSR_NM,RQSR_DPCD,TRTH_RQST_NM,TRTH_RQST_DPCD,PIC_EMPNO,PIC_NM,PIC_DPCD,PIC_DPNM,
      DEV_DEPT_CD,DEV_PART_CD,REG_DATE,RFLC_SCDL_DATE,PROS_CMPT_DATE,SYNCED_AT,CREATED_BY)
     VALUES (#{srNo},#{baseYm},#{srTpcd},#{srTpcdName},#{bswrDetlName},#{statusCode},#{titlCntt},#{msgCntt},#{custInfoYn},
      #{rqsrEmpno},#{rqsrNm},#{rqsrDpcd},#{trthRqstNm},#{trthRqstDpcd},#{picEmpno},#{picNm},#{picDpcd},#{picDpnm},
      #{devDeptCd},#{devPartCd},#{regDate},#{rflcScdlDate},#{prosCmptDate},SYSTIMESTAMP,'BATCH')
</update>
```
selectList: `DASH_DML_SR t LEFT JOIN DASH_DML_CHECK k ON t.SR_NO=k.SR_NO`, `WHERE t.BASE_YM=#{baseYm}` + `<if>` filters (`deptCd`,`partCd`,`empno`→`t.PIC_EMPNO`,`checked`→`NVL(k.CHECK_YN,'N')`). SELECT `NVL(k.CHECK_YN,'N') AS checkYn` 등 점검/개선 컬럼(널이면 'N'/null). ORDER BY t.SR_NO.
findScopeRef: `SELECT SR_NO srNo, DEV_DEPT_CD devDeptCd, DEV_PART_CD devPartCd, PIC_EMPNO picEmpno FROM DASH_DML_SR WHERE SR_NO=#{srNo}`.
upsertCheck / upsertImprovement: MERGE INTO DASH_DML_CHECK ON SR_NO — check: set CHECK_YN; improvement: set IMPROVE_YN='Y', IMPROVE_PLAN, PLAN_CMPT_DATE, CMPT_YN, REMARK; NOT MATCHED INSERT with sensible defaults + CREATED_BY=#{actor}, UPDATED_BY=#{actor}.
- [ ] **Step: compile** `./gradlew compileJava -q`.
- [ ] **Commit** — `git commit -am "feat(dml): app mapper (MERGE snapshot, list join, check/improvement upsert)"`

---

## Task 4: 동기화 서비스 + 스케줄러 + Sync IT

**Files:**
- Create: `src/main/java/com/meritz/dash/dml/DmlSyncService.java`, `DmlSyncScheduler.java`
- Test: `src/test/java/com/meritz/dash/dml/DmlSyncIT.java`

**Interfaces:**
- Consumes: `DmlSrLegacyReader.read` (Task 2), `DmlSrMapper.mergeSnapshot` (Task 3), `DevSrScopeMapper.findRefs(null,null)` (existing).
- Produces: `public SyncResult sync(String baseYm, String trigger)` where `record SyncResult(String baseYm, int fetched, int matched)`.

- [ ] **Step 1: Write failing Sync IT** (`@SpringBootTest`, app+legacy fixtures). Seed legacy 3 DML SRs: PIC_EMPNO=E0002(개발자, HR_DEVELOPER 有), PIC_EMPNO=E0003(有 다른 파트), PIC_EMPNO='ZZZZ'(HR 無). Seed HR_DEVELOPER E0002(dept 2139/part P01), E0003(2139/P02). Call `sync(ym,"TEST")`. Assert: fetched=3, matched=2(ZZZZ 제외), DASH_DML_SR has 2 rows with DEV_PART_CD P01/P02. Then set a CHECK row (CHECK_YN='Y') for E0002's SR; **re-run sync**; assert snapshot updated AND CHECK_YN still 'Y' (멱등·보존).
- [ ] **Step 2: Run, expect FAIL** — `./gradlew test --tests '*DmlSyncIT'`.
- [ ] **Step 3: Implement DmlSyncService** — `@Transactional("appTxManager")`. Steps: rows=legacyReader.read(baseYm); build `Map<empno,HrRef>` from `scopeMapper.findRefs(null,null)`; for each row, ref=map.get(picEmpno); if null skip; else map to `DmlSr` with baseYm=SUBSTR(regDate,0,6), devDeptCd=ref.deptCd(), devPartCd=ref.partCd(); `mapper.mergeSnapshot(sr)`. Return counts. Log start/finish (SLF4J).
- [ ] **Step 4: Implement DmlSyncScheduler** — mirror `AggregationScheduler`; `@Scheduled(cron="${app.dml-sync.cron:0 0 3 * * MON}")`, baseYm=이번달, try/catch log.
- [ ] **Step 5: Run IT, expect PASS**.
- [ ] **Step 6: Commit** — `git commit -am "feat(dml): weekly sync service + scheduler + idempotent IT"`

---

## Task 5: 조회/쓰기 서비스(RBAC) + 단위 테스트

**Files:**
- Create: `src/main/java/com/meritz/dash/dml/DmlSrService.java`
- Test: `src/test/java/com/meritz/dash/dml/DmlSrServiceTest.java`

**Interfaces:**
- Consumes: `DmlSrMapper` (Task 3), `AuthContext`, `ForbiddenException` (existing).
- Produces:
```java
public record ListResult(List<DmlSrItem> items, String scope, int total, long checkedCount, long improveCount) {}
public ListResult list(String baseYm, String unit, String partCd, String checked);
public void setCheck(String srNo, String checkYn);            // 쓰기 RBAC
public void saveImprovement(String srNo, String plan, String planCmptDate, String cmptYn, String remark);
```

- [ ] **Step 1: Write failing unit tests** (mock `DmlSrMapper`; set `AuthContext`):
  - list 03: scope="self", calls selectList(empno=self). 01: scope="dept", selectList(deptCd=본인부서). 02+unit=part: partCd=본인파트. 02 요청 partCd≠본인파트 → ForbiddenException. ADMIN: no dept/part filter.
  - setCheck: 02 대상 SR findScopeRef devPartCd=본인파트 → upsertCheck 호출. 02 타파트 → Forbidden, upsert 미호출. 03 → Forbidden. 대상 없음(null) → 404류 예외(NotFound). 01 다른부서 → Forbidden.
  - baseYm 기본값: null이면 이번달(YYYYMM) — 주입 가능하게 `Clock` 또는 인자 처리(테스트는 명시 baseYm 전달).
  - checkedCount/improveCount = items 중 checkYn/improveYn=='Y' 카운트.
- [ ] **Step 2: Run, expect FAIL**.
- [ ] **Step 3: Implement DmlSrService** — `@Transactional("appTxManager")` (조회 readOnly=true, 쓰기 별도 메서드 default). RBAC:
  - `list`: resolve filters by role (ADMIN=none; 01=deptCd=AuthContext.deptCd(); 02=partCd(요청 partCd 있으면 본인파트만 허용, 아니면 본인파트), 03=empno=self, fail-closed: dept/part null이면 self). checked pass-through.
  - write guard `assertCanWrite(srNo)`: role 03 → Forbidden; ref=mapper.findScopeRef(srNo); null → NotFoundException; ADMIN ok; 01 ref.devDeptCd==dept else Forbidden; 02 ref.devPartCd==part else Forbidden.
  - `setCheck` validate checkYn∈{Y,N}; `saveImprovement` validate cmptYn∈{Y,N}. actor=AuthContext.empno().
- [ ] **Step 4: Run, expect PASS**.
- [ ] **Step 5: Commit** — `git commit -am "feat(dml): read/write service with RBAC + unit tests"`

---

## Task 6: 컨트롤러 + DTO + springdoc + 계약 테스트

**Files:**
- Create: `src/main/java/com/meritz/dash/dml/DmlSrController.java`
- Test: `src/test/java/com/meritz/dash/dml/DmlSrControllerTest.java`

**Interfaces:**
- Consumes: `DmlSrService` (Task 5), `DmlSyncService` (Task 4), `ApiResponse`.

- [ ] **Step 1: Write failing @WebMvcTest** — mock DmlSrService/DmlSyncService. GET returns 200 + envelope(data array, meta{baseYm,scope,total,checkedCount,improveCount}); PATCH check 200; PUT improvement 200; forbidden path → 403 ProblemDetail; missing SR → 404. Interceptor/Auth: follow `DevSrControllerTest` setup (standalone or import).
- [ ] **Step 2: Run, expect FAIL**.
- [ ] **Step 3: Implement controller**:
  - `@Tag(name="DmlSr")`, `@RestController @RequestMapping("/api/v1/dml-srs")`.
  - `GET` params baseYm(optional), unit(default "dept"), partCd(optional), checked(optional Y/N) → `ApiResponse.of(items, meta)`; rich `@Operation` (무엇/권한/필터/응답) + 200 example.
  - `PATCH /{srNo}/check` body `record CheckRequest(String checkYn)` → service.setCheck.
  - `PUT /{srNo}/improvement` body `record ImprovementRequest(String improvePlan,String planCmptDate,String cmptYn,String remark)` → service.saveImprovement.
  - `POST /sync` param baseYm(optional) `@Auth(roles={"01","ADMIN"})` → DmlSyncService.sync.
- [ ] **Step 4: Run, expect PASS**.
- [ ] **Step 5: full build** — `./gradlew build` → BUILD SUCCESSFUL.
- [ ] **Step 6: Commit** — `git commit -am "feat(dml): controller + DTOs + springdoc + contract tests"`

---

## Self-Review notes
- Spec §2 쿼리 → Task 2. §3 테이블 → Task 1. §4 배치 → Task 4. §5 API/RBAC → Task 5·6. §6 점검률(checkedCount/improveCount) → Task 5. §8 테스트 4종 → Tasks 2,4,5,6.
- 재직 필터/HR 매칭은 기존 `DevSrScopeMapper`(STATUS_CD='01') 재사용 — 신규 스코프 매퍼 없음.
- 이름/타입 일관성: `DmlSrLegacyRow`→(sync)→`DmlSr`→(mapper)→`DmlSrItem` 필드명 일치 확인.
- `POST /sync`만 `@Auth(roles)` 강제, 나머지 쓰기 스코프는 서비스단 fail-closed(대상 SR의 DEV_DEPT/PART 기준).
