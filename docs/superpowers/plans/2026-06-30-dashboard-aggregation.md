# C 대시보드 + 집계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기간계(Oracle, 조회 전용)의 SR 데이터를 월 단위로 DB2 `DASH_*`에 집계 적재하고, 대시보드 3위젯(개발량 추이·M/M 가동률·Top SR)과 과거 백필 API를 제공한다.

**Architecture:** A+D(2 DataSource·MyBatis·Flyway·envelope·ProblemDetail) 위에 얹는다. 기간계는 `mapper.legacy`의 SELECT 전용 매퍼로 query1/query2를 읽고, `AggregationService.run(periodYm)`가 계산·`CD_COMMON` 매핑·HR 스냅샷 후 DB2 `DASH_*`에 MERGE upsert(멱등)한다. 조회 API는 모두 DB2 집계에서 읽어 기간계 장애와 격리된다.

**Tech Stack:** Java 21, Spring Boot 3.3.x, MyBatis, Oracle 2개, Flyway, JUnit5+AssertJ+Mockito+Testcontainers(oracle-free). (A+D에서 구축 완료.)

## Global Constraints

- **기간계 매퍼는 SELECT만**(INSERT/UPDATE/DELETE/MERGE/DDL 금지). 값 주입 `#{}` 만, `${}`는 화이트리스트 정렬/컬럼만.
- 기간계 read는 `@Transactional("legacyTxManager", readOnly=true)`, DB2 쓰기는 `@Transactional("appTxManager")`. 기간계 풀≤8, MyBatis `defaultStatementTimeout=5` + JDBC `oracle.jdbc.ReadTimeout`.
- 메인/드릴다운은 **DB2 집계에서만** 읽는다(기간계 직접 호출 금지). 기간계 장애여도 대시보드 정상.
- `1 M/M = 166h` = `app.mm.hours-per-month`(하드코딩 금지). 야근=`max(MM−1.0,0)`. 가동률=USED÷AVAIL(**분모 0 방어**). Top 기준 `app.mm.top-min-mm`(기본 0.6).
- DB2 DDL은 `db/migration/VNNN__설명.sql` 새 파일(기존 수정 금지). **19c 호환**(BOOLEAN 금지, CHAR(1) 플래그, CHECK). 코드성 값은 `CD_COMMON` 참조(매직 문자열 금지).
- 응답 envelope `{data, meta}`. 에러 RFC7807 ProblemDetail. 월 레이블 `monthLabel`("26.05"). 전 엔드포인트 springdoc `@Operation`.
- DTO는 `record`, 컨트롤러는 DTO만 반환, 생성자 주입만. 기본 패키지 `com.meritz.dash`.
- 배치 멱등: 같은 `periodYm` 재실행 = 동일 결과(PERIOD_YM 키 MERGE).
- gradle 실행 셸 환경: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; export DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock; export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. 통합테스트 timeout 넉넉히(400000ms). `gradle.properties`/`.gitignore` 수정·커밋 금지.

---

## File Structure

```
src/main/resources/db/migration/
  V003__codeunify_status_srcls.sql      # CD_COMMON.ATTR1 추가, EMP_STATUS 코드화, SR_CLS 매핑
  V004__create_dash_tables.sql          # DASH_DEV_AGG, DASH_RESOURCE, DASH_SR_PROJECT, BATCH_RUN_LOG
src/main/java/com/meritz/dash/
  config/MmProperties.java               # @ConfigurationProperties("app.mm")
  mapper/legacy/LegacySrMapper.java      # 기간계 query1/query2 (SELECT only)
  aggregation/
    LegacyDevRow.java  LegacySrProjectRow.java   # 기간계 raw row records
    DevAgg.java  ResourceSnapshot.java  SrProject.java  BatchRunLog.java  # DB2 행 records
    AggregationService.java              # run(periodYm) 오케스트레이션 (멱등)
    AggregationController.java           # POST/GET /aggregations
    AggregationRequest.java              # {periodYm} 또는 {from,to}
  devvolume/  resource/  srproject/      # 위젯별 controller/service/record
  mapper/app/
    DashWriteMapper.java                 # DASH_* MERGE upsert + BATCH_RUN_LOG
    DevVolumeMapper.java  ResourceMapper.java  SrProjectMapper.java  CodeRefMapper.java
src/main/resources/mapper/legacy/LegacySrMapper.xml
src/main/resources/mapper/app/{DashWriteMapper,DevVolumeMapper,ResourceMapper,SrProjectMapper,CodeRefMapper}.xml
src/test/java/com/meritz/dash/
  support/LegacyFixture.java             # 테스트 legacy 컨테이너에 091/093/097 DDL+seed 로드
  ... 태스크별 IT/계약 테스트
src/test/resources/legacy-fixture/{ddl.sql,seed.sql}
```

> 비고: 기간계 실제 컬럼/타입/상태코드/SR_TPCD 자릿수는 **Task 4 Step 1에서 `/ora-db`로 확인**한 뒤 매퍼·픽스처를 확정한다. 본 계획의 legacy SQL은 `쿼리/쿼리.sql`(실제 동작 쿼리) 기준이다.

---

## Task 1: V003 — 코드 일원화(EMP_STATUS 코드화 + SR_CLS 매핑)

**Files:**
- Create: `src/main/resources/db/migration/V003__codeunify_status_srcls.sql`
- Test: `src/test/java/com/meritz/dash/config/CodeUnifyIT.java`

**Interfaces:**
- Produces: `CD_COMMON.ATTR1`(VARCHAR2(30) NULL). `GRP_CD='SR_CLS'` 코드(01/02/03/99), `GRP_CD='SR_TPCD'` 행의 `ATTR1`=SR_CLS코드, `GRP_CD='EMP_STATUS'` = '01'/'02'. `HR_DEVELOPER.STATUS_CD` 코드값('01'/'02') + CHECK 교체.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.meritz.dash.config;

import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class CodeUnifyIT extends AbstractOracleIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("V003: EMP_STATUS 코드화 + SR_CLS 매핑 + 재직코드 재코딩")
    void code_unify_applied() {
        // EMP_STATUS 코드값이 01/02
        Integer status = jdbc.queryForObject(
            "SELECT COUNT(*) FROM CD_COMMON WHERE GRP_CD='EMP_STATUS' AND CD_VAL IN ('01','02')", Integer.class);
        assertThat(status).isEqualTo(2);
        // SR_CLS 그룹 4건
        Integer srcls = jdbc.queryForObject(
            "SELECT COUNT(*) FROM CD_COMMON WHERE GRP_CD='SR_CLS'", Integer.class);
        assertThat(srcls).isEqualTo(4);
        // SR_TPCD '1'(개발요청)의 ATTR1 = '01'
        String attr1 = jdbc.queryForObject(
            "SELECT ATTR1 FROM CD_COMMON WHERE GRP_CD='SR_TPCD' AND CD_VAL='1'", String.class);
        assertThat(attr1).isEqualTo("01");
        // HR 시드 STATUS_CD가 '01'로 재코딩됨(재직)
        Integer active = jdbc.queryForObject(
            "SELECT COUNT(*) FROM HR_DEVELOPER WHERE STATUS_CD='01'", Integer.class);
        assertThat(active).isEqualTo(4);
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew test --tests '*CodeUnifyIT'` → FAIL.

- [ ] **Step 3: V003 마이그레이션 작성**

```sql
-- V003: 코드 일원화 (ATTR1 부가속성, EMP_STATUS 코드화, SR_CLS 매핑)
ALTER TABLE CD_COMMON ADD (ATTR1 VARCHAR2(30));

-- HR CHECK 제약 교체: 한글 → 코드('01'재직 / '02'휴직)
ALTER TABLE HR_DEVELOPER DROP CONSTRAINT CK_HR_STATUS;
UPDATE HR_DEVELOPER SET STATUS_CD = '01' WHERE STATUS_CD = '재직';
UPDATE HR_DEVELOPER SET STATUS_CD = '02' WHERE STATUS_CD = '휴직';
ALTER TABLE HR_DEVELOPER MODIFY (STATUS_CD DEFAULT '01');
ALTER TABLE HR_DEVELOPER ADD CONSTRAINT CK_HR_STATUS CHECK (STATUS_CD IN ('01','02'));

-- EMP_STATUS 코드화
UPDATE CD_COMMON SET CD_VAL='01' WHERE GRP_CD='EMP_STATUS' AND CD_VAL='재직';
UPDATE CD_COMMON SET CD_VAL='02' WHERE GRP_CD='EMP_STATUS' AND CD_VAL='휴직';

-- SR_CLS 분류 코드
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('SR_CLS','01','개발요청',1);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('SR_CLS','02','유지보수',2);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('SR_CLS','03','자료요청',3);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('SR_CLS','99','기타',9);

-- SR_TPCD → SR_CLS 매핑(ATTR1). 개발요청=01, 유지보수=02, 자료요청=03, 그외=99
UPDATE CD_COMMON SET ATTR1='01' WHERE GRP_CD='SR_TPCD' AND CD_VAL='1';
UPDATE CD_COMMON SET ATTR1='02' WHERE GRP_CD='SR_TPCD' AND CD_VAL='2';
UPDATE CD_COMMON SET ATTR1='03' WHERE GRP_CD='SR_TPCD' AND CD_VAL='3';
UPDATE CD_COMMON SET ATTR1='99' WHERE GRP_CD='SR_TPCD' AND CD_VAL IN ('5','17','18','19');
```

> ⚠️ 실제 기간계 `SR_TPCD` 자릿수 확인(Task 4) 후, 다를 경우 별도 마이그레이션으로 ATTR1 매핑 키를 보정한다(V003은 수정 금지).

- [ ] **Step 4: 통과 확인** — Run: `./gradlew test --tests '*CodeUnifyIT'` → PASS.

- [ ] **Step 5: 커밋**
```bash
git add src/main/resources/db/migration/V003__codeunify_status_srcls.sql src/test/java/com/meritz/dash/config/CodeUnifyIT.java
git commit -m "feat: V003 코드 일원화(EMP_STATUS 코드화 + SR_CLS 매핑 CD_COMMON.ATTR1)"
```

---

## Task 2: V004 — DASH 집계 테이블

**Files:**
- Create: `src/main/resources/db/migration/V004__create_dash_tables.sql`
- Test: `src/test/java/com/meritz/dash/config/DashTablesIT.java`

**Interfaces:**
- Produces: 테이블 `DASH_DEV_AGG(PERIOD_YM,EMPNO,SR_CLS,SR_CNT,JOB_MM,+감사)`, `DASH_RESOURCE(PERIOD_YM,UNIT_TYPE,UNIT_ID,HEADCOUNT,AVAIL_HEADCOUNT,AVAIL_MM,USED_MM,OVERTIME_MM,+감사)`, `DASH_SR_PROJECT(PERIOD_YM,SR_NO,TITL_CNTT,SR_TPCD,SR_TPCD_NAME,TOT_MM,EMP_CNT,PRCH_DPCD,DPCD,REG_DATE,RFLC_SCDL_DATE,+감사)`, `BATCH_RUN_LOG(RUN_ID,PERIOD_YM,TRIGGER,STATUS,DEV_ROWS,SR_ROWS,STARTED_AT,FINISHED_AT,MSG)`.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.meritz.dash.config;

import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class DashTablesIT extends AbstractOracleIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("V004: DASH 4테이블 생성 확인(빈 테이블 카운트 0)")
    void dash_tables_exist() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DASH_DEV_AGG", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DASH_RESOURCE", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DASH_SR_PROJECT", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_RUN_LOG", Integer.class)).isZero();
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*DashTablesIT'` → FAIL.

- [ ] **Step 3: V004 마이그레이션 작성**

```sql
-- V004: 대시보드 집계 테이블 (19c 호환)
CREATE TABLE DASH_DEV_AGG (
  PERIOD_YM  VARCHAR2(6)  NOT NULL,
  EMPNO      VARCHAR2(20) NOT NULL,
  SR_CLS     VARCHAR2(30) NOT NULL,
  SR_CNT     NUMBER(7)    DEFAULT 0 NOT NULL,
  JOB_MM     NUMBER(7,2)  DEFAULT 0 NOT NULL,
  CREATED_AT TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  UPDATED_AT TIMESTAMP,
  CONSTRAINT PK_DASH_DEV_AGG PRIMARY KEY (PERIOD_YM, EMPNO, SR_CLS)
);

CREATE TABLE DASH_RESOURCE (
  PERIOD_YM       VARCHAR2(6)  NOT NULL,
  UNIT_TYPE       VARCHAR2(10) NOT NULL,
  UNIT_ID         VARCHAR2(30) NOT NULL,
  HEADCOUNT       NUMBER(7)    DEFAULT 0 NOT NULL,
  AVAIL_HEADCOUNT NUMBER(7)    DEFAULT 0 NOT NULL,
  AVAIL_MM        NUMBER(9,2)  DEFAULT 0 NOT NULL,
  USED_MM         NUMBER(9,2)  DEFAULT 0 NOT NULL,
  OVERTIME_MM     NUMBER(9,2)  DEFAULT 0 NOT NULL,
  CREATED_AT TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  UPDATED_AT TIMESTAMP,
  CONSTRAINT PK_DASH_RESOURCE PRIMARY KEY (PERIOD_YM, UNIT_TYPE, UNIT_ID),
  CONSTRAINT CK_DASH_RES_UNIT CHECK (UNIT_TYPE IN ('TEAM','PART'))
);

CREATE TABLE DASH_SR_PROJECT (
  PERIOD_YM     VARCHAR2(6)   NOT NULL,
  SR_NO         VARCHAR2(20)  NOT NULL,
  TITL_CNTT     VARCHAR2(400),
  SR_TPCD       VARCHAR2(30),
  SR_TPCD_NAME  VARCHAR2(100),
  TOT_MM        NUMBER(7,2)   DEFAULT 0 NOT NULL,
  EMP_CNT       NUMBER(7)     DEFAULT 0 NOT NULL,
  PRCH_DPCD     VARCHAR2(30),
  DPCD          VARCHAR2(30),
  REG_DATE      VARCHAR2(8),
  RFLC_SCDL_DATE VARCHAR2(8),
  CREATED_AT TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  UPDATED_AT TIMESTAMP,
  CONSTRAINT PK_DASH_SR_PROJECT PRIMARY KEY (PERIOD_YM, SR_NO)
);
CREATE INDEX IX_DASH_SR_PROJECT_MM ON DASH_SR_PROJECT (PERIOD_YM, TOT_MM);

CREATE TABLE BATCH_RUN_LOG (
  RUN_ID      NUMBER GENERATED BY DEFAULT AS IDENTITY,
  PERIOD_YM   VARCHAR2(6)  NOT NULL,
  TRIGGER     VARCHAR2(10) NOT NULL,
  STATUS      VARCHAR2(10) NOT NULL,
  DEV_ROWS    NUMBER(7)    DEFAULT 0 NOT NULL,
  SR_ROWS     NUMBER(7)    DEFAULT 0 NOT NULL,
  STARTED_AT  TIMESTAMP    NOT NULL,
  FINISHED_AT TIMESTAMP,
  MSG         VARCHAR2(1000),
  CONSTRAINT PK_BATCH_RUN_LOG PRIMARY KEY (RUN_ID),
  CONSTRAINT CK_BATCH_TRIGGER CHECK (TRIGGER IN ('SCHEDULED','MANUAL')),
  CONSTRAINT CK_BATCH_STATUS CHECK (STATUS IN ('OK','FAIL'))
);
```

> `GENERATED BY DEFAULT AS IDENTITY`는 Oracle 12c+ 지원(19c OK). BOOLEAN 미사용.

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests '*DashTablesIT'` → PASS.

- [ ] **Step 5: 커밋**
```bash
git add src/main/resources/db/migration/V004__create_dash_tables.sql src/test/java/com/meritz/dash/config/DashTablesIT.java
git commit -m "feat: V004 DASH 집계 테이블(DEV_AGG/RESOURCE/SR_PROJECT/BATCH_RUN_LOG)"
```

---

## Task 3: MmProperties 설정값

**Files:**
- Create: `src/main/java/com/meritz/dash/config/MmProperties.java`
- Modify: `src/main/java/com/meritz/dash/DashApplication.java` (@ConfigurationPropertiesScan 또는 @EnableConfigurationProperties)
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/meritz/dash/config/MmPropertiesTest.java`

**Interfaces:**
- Produces: `MmProperties` with `int hoursPerMonth()` (166), `double overtimeThreshold()` (1.0), `double topMinMm()` (0.6). 빈 이름 기본.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.meritz.dash.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class MmPropertiesTest {

    @Test
    @DisplayName("app.mm 바인딩: 166h / 1.0 / 0.6")
    void binds() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.mm.hours-per-month", "166")
            .withProperty("app.mm.overtime-threshold", "1.0")
            .withProperty("app.mm.top-min-mm", "0.6");
        MmProperties p = Binder.get(env).bind("app.mm", MmProperties.class).get();
        assertThat(p.hoursPerMonth()).isEqualTo(166);
        assertThat(p.overtimeThreshold()).isEqualTo(1.0);
        assertThat(p.topMinMm()).isEqualTo(0.6);
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*MmPropertiesTest'` → FAIL.

- [ ] **Step 3: MmProperties 작성**

```java
package com.meritz.dash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.mm")
public record MmProperties(int hoursPerMonth, double overtimeThreshold, double topMinMm) {}
```

- [ ] **Step 4: DashApplication에 스캔 등록 + yml 추가**

`DashApplication`에 클래스 애너테이션 추가:
```java
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
```
`application.yml`의 `spring:` 위(최상위)에 추가:
```yaml
app:
  mm:
    hours-per-month: 166
    overtime-threshold: 1.0
    top-min-mm: 0.6
```

- [ ] **Step 5: 통과 확인** — `./gradlew test --tests '*MmPropertiesTest'` → PASS.

- [ ] **Step 6: 커밋**
```bash
git add src/main/java/com/meritz/dash/config/MmProperties.java src/main/java/com/meritz/dash/DashApplication.java src/main/resources/application.yml src/test/java/com/meritz/dash/config/MmPropertiesTest.java
git commit -m "feat: MmProperties(app.mm) 166h/야근1.0/Top0.6 설정값"
```

---

## Task 4: 기간계 SELECT 매퍼 + 테스트 픽스처 + SELECT-only 가드 + JDBC 타임아웃

**Files:**
- Modify: `src/main/resources/application.yml` (`datasource.legacy.data-source-properties`)
- Create: `src/main/java/com/meritz/dash/aggregation/LegacyDevRow.java`, `LegacySrProjectRow.java`
- Create: `src/main/java/com/meritz/dash/mapper/legacy/LegacySrMapper.java`
- Create: `src/main/resources/mapper/legacy/LegacySrMapper.xml`
- Create: `src/test/resources/legacy-fixture/ddl.sql`, `seed.sql`
- Create: `src/test/java/com/meritz/dash/support/LegacyFixture.java`
- Test: `src/test/java/com/meritz/dash/aggregation/LegacySrMapperIT.java`, `src/test/java/com/meritz/dash/mapper/legacy/LegacySelectOnlyGuardTest.java`

**Interfaces:**
- Produces: `record LegacyDevRow(String empno, String srTpcd, int srCnt, double jobHours)`.
- Produces: `record LegacySrProjectRow(String srNo, String titlCntt, String srTpcd, String srTpcdName, double totMm, int empCnt, String prchDpcd, String dpcd, String regDate, String rflcScdlDate)`.
- Produces: `LegacySrMapper.selectDevAgg(String periodYm) : List<LegacyDevRow>`; `selectSrProjects(String periodYm, double minMm) : List<LegacySrProjectRow>`.

- [ ] **Step 1: `/ora-db`로 기간계 실제 스키마 확인**

`/ora-db` 명령으로 `TBCPPE091M00`, `TBCPPE093L00`, `TBCPPE097L00`의 컬럼명/타입과 `SR_TPCD` 실제 저장 형식(`'1'` vs `'01'`), 사용 상태코드 목록을 조회한다. 결과를 보고서에 기록하고, 아래 SQL/픽스처의 컬럼·코드값을 실제에 맞춰 보정한다(불일치 시 보정이 우선).

- [ ] **Step 2: legacy 테스트 픽스처 작성(091/093/097 최소 스키마+데이터)**

`src/test/resources/legacy-fixture/ddl.sql` (기간계 모사 — 19c 호환, query1/query2가 참조하는 컬럼만):
```sql
CREATE TABLE TBCPPE091M00 (
  SR_NO VARCHAR2(20), TITL_CNTT VARCHAR2(400), SR_TPCD VARCHAR2(10),
  SR_REG_STAT_CODE VARCHAR2(2), DPCD VARCHAR2(10), PRCH_DPCD VARCHAR2(10),
  JOB_MANM VARCHAR2(20), REG_DATE VARCHAR2(8), RFLC_SCDL_DATE VARCHAR2(8)
);
CREATE TABLE TBCPPE093L00 (
  SR_NO VARCHAR2(20), SPIC_EMPNO VARCHAR2(20), JOB_EXEC_HOUR NUMBER,
  JOB_MANM VARCHAR2(20), FIN_DATE VARCHAR2(8), APRV_YN CHAR(1), MNPL_EMPNO VARCHAR2(20)
);
CREATE TABLE TBCPPE097L00 (SR_TPCD VARCHAR2(10), SR_TPCD_NAME VARCHAR2(100));
```
`src/test/resources/legacy-fixture/seed.sql` (periodYm=202605 대상 샘플 — 개발자 E0002 개발요청 1.0MM·E0003 1.3MM(야근0.3) 등 검증용):
```sql
INSERT INTO TBCPPE097L00 VALUES ('1','개발요청');
INSERT INTO TBCPPE097L00 VALUES ('2','유지보수');
INSERT INTO TBCPPE091M00 VALUES ('SR26000001','차세대 계좌개설','1','02','D101','D101','1.0','20260501','20260520');
INSERT INTO TBCPPE091M00 VALUES ('SR26000002','월마감 배치','2','03','D102','D102','0.5','20260502','20260521');
-- 093: FIN_DATE 202605, APRV_YN='Y'. E0002 160h(=1.0MM), E0003 208h(=1.253MM→ROUND 1.25, 야근0.25)
INSERT INTO TBCPPE093L00 VALUES ('SR26000001','E0002',160,'1.0','20260528','Y','E0002');
INSERT INTO TBCPPE093L00 VALUES ('SR26000001','E0003',208,'1.3','20260528','Y','E0003');
INSERT INTO TBCPPE093L00 VALUES ('SR26000002','E0002',80,'0.5','20260530','Y','E0002');
COMMIT;
```

`src/test/java/com/meritz/dash/support/LegacyFixture.java` — `AbstractOracleIT`의 싱글톤 컨테이너에 fixture를 한 번 로드(legacy DataSource로):
```java
package com.meritz.dash.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Connection;

public abstract class LegacyFixture extends AbstractOracleIT {

    @Autowired @Qualifier("legacyDataSource") DataSource legacyDs;

    @BeforeEach
    void loadFixtureOnce() throws Exception {
        try (Connection c = legacyDs.getConnection()) {
            try { c.createStatement().execute("SELECT 1 FROM TBCPPE091M00 WHERE ROWNUM=1"); return; }
            catch (Exception notLoaded) { /* 테이블 없음 → 로드 */ }
            ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy-fixture/ddl.sql"));
            ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy-fixture/seed.sql"));
        }
    }
}
```
> 단일 Testcontainers 컨테이너를 app/legacy가 공유하므로(설계상 동일), 기간계 모사 테이블도 같은 스키마에 만들어진다. DASH/HR(app)과 091/093/097(legacy 모사)은 테이블명이 겹치지 않아 충돌 없음.

- [ ] **Step 3: LegacySrMapperIT 실패 테스트 작성**

```java
package com.meritz.dash.aggregation;

import com.meritz.dash.mapper.legacy.LegacySrMapper;
import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegacySrMapperIT extends LegacyFixture {

    @Autowired LegacySrMapper mapper;

    @Test
    @DisplayName("selectDevAgg(202605): 개발자×SR_TPCD 건수·시간")
    void devAgg() {
        List<LegacyDevRow> rows = mapper.selectDevAgg("202605");
        // E0002: SR_TPCD '1' 160h + '2' 80h, E0003: '1' 208h
        assertThat(rows).extracting(LegacyDevRow::empno).contains("E0002", "E0003");
        double e0003hours = rows.stream().filter(r -> r.empno().equals("E0003")).mapToDouble(LegacyDevRow::jobHours).sum();
        assertThat(e0003hours).isEqualTo(208.0);
    }

    @Test
    @DisplayName("selectSrProjects(202605, 0.6): TOT_MM>=0.6 내림차순")
    void srProjects() {
        List<LegacySrProjectRow> rows = mapper.selectSrProjects("202605", 0.6);
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).totMm()).isGreaterThanOrEqualTo(rows.get(rows.size()-1).totMm());
    }
}
```

- [ ] **Step 4: 실패 확인** — `./gradlew test --tests '*LegacySrMapperIT'` → FAIL(매퍼 미존재).

- [ ] **Step 5: row record + 매퍼 인터페이스 + XML 작성**

`LegacyDevRow.java`:
```java
package com.meritz.dash.aggregation;
public record LegacyDevRow(String empno, String srTpcd, int srCnt, double jobHours) {}
```
`LegacySrProjectRow.java`:
```java
package com.meritz.dash.aggregation;
public record LegacySrProjectRow(String srNo, String titlCntt, String srTpcd, String srTpcdName,
        double totMm, int empCnt, String prchDpcd, String dpcd, String regDate, String rflcScdlDate) {}
```
`src/main/java/com/meritz/dash/mapper/legacy/LegacySrMapper.java`:
```java
package com.meritz.dash.mapper.legacy;

import com.meritz.dash.aggregation.LegacyDevRow;
import com.meritz.dash.aggregation.LegacySrProjectRow;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface LegacySrMapper {
    List<LegacyDevRow> selectDevAgg(@Param("periodYm") String periodYm);
    List<LegacySrProjectRow> selectSrProjects(@Param("periodYm") String periodYm, @Param("minMm") double minMm);
}
```
`src/main/resources/mapper/legacy/LegacySrMapper.xml` (`쿼리/쿼리.sql` 기반, SELECT만):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.meritz.dash.mapper.legacy.LegacySrMapper">

  <select id="selectDevAgg" resultType="com.meritz.dash.aggregation.LegacyDevRow">
    SELECT B.SPIC_EMPNO AS empno,
           A.SR_TPCD     AS srTpcd,
           COUNT(*)      AS srCnt,
           SUM(B.JOB_EXEC_HOUR) AS jobHours
      FROM TBCPPE091M00 A, TBCPPE093L00 B
     WHERE A.SR_NO = B.SR_NO
       AND B.APRV_YN = 'Y'
       AND SUBSTR(B.FIN_DATE,1,6) = #{periodYm}
       AND A.SR_REG_STAT_CODE NOT IN ('00','09','16','97','99','22')
       AND A.SR_TPCD NOT IN ('15')
     GROUP BY B.SPIC_EMPNO, A.SR_TPCD
  </select>

  <select id="selectSrProjects" resultType="com.meritz.dash.aggregation.LegacySrProjectRow">
    SELECT a.SR_NO AS srNo, a.TITL_CNTT AS titlCntt, a.SR_TPCD AS srTpcd,
           c.SR_TPCD_NAME AS srTpcdName,
           SUM(TO_NUMBER(TRIM(b.JOB_MANM))) AS totMm,
           COUNT(DISTINCT b.MNPL_EMPNO) AS empCnt,
           a.PRCH_DPCD AS prchDpcd, a.DPCD AS dpcd,
           a.REG_DATE AS regDate, a.RFLC_SCDL_DATE AS rflcScdlDate
      FROM TBCPPE091M00 a
      INNER JOIN TBCPPE093L00 b ON a.SR_NO = b.SR_NO
      LEFT  JOIN TBCPPE097L00 c ON a.SR_TPCD = c.SR_TPCD
     WHERE a.SR_REG_STAT_CODE IN ('02','03','04','05','17','06','07')
       AND SUBSTR(b.FIN_DATE,1,6) = #{periodYm}
     GROUP BY a.SR_NO, a.TITL_CNTT, a.SR_TPCD, c.SR_TPCD_NAME,
              a.PRCH_DPCD, a.DPCD, a.REG_DATE, a.RFLC_SCDL_DATE
    HAVING SUM(TO_NUMBER(TRIM(b.JOB_MANM))) >= #{minMm}
     ORDER BY totMm DESC
  </select>
</mapper>
```
> `쿼리.sql`의 query2엔 기간 필터가 없었으나, 월별 집계를 위해 `SUBSTR(b.FIN_DATE,1,6)=#{periodYm}`를 추가한다(설계 5장).

- [ ] **Step 6: JDBC 타임아웃 보강(application.yml)**

`datasource.legacy:` 아래에 추가:
```yaml
    data-source-properties:
      oracle.jdbc.ReadTimeout: 5000
      oracle.net.CONNECT_TIMEOUT: 3000
```

- [ ] **Step 7: SELECT-only 가드 테스트 작성(매퍼 XML 정적 검사)**

```java
package com.meritz.dash.mapper.legacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LegacySelectOnlyGuardTest {

    @Test
    @DisplayName("기간계 매퍼 XML에 쓰기 SQL(INSERT/UPDATE/DELETE/MERGE/DDL)이 없다")
    void legacy_is_select_only() throws Exception {
        Resource[] xmls = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:mapper/legacy/*.xml");
        assertThat(xmls).isNotEmpty();
        for (Resource r : xmls) {
            String body = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8).toUpperCase();
            assertThat(body)
                .doesNotContain("<INSERT").doesNotContain("<UPDATE").doesNotContain("<DELETE")
                .doesNotContain("MERGE ").doesNotContain("CREATE ").doesNotContain("DROP ").doesNotContain("ALTER ");
        }
    }
}
```

- [ ] **Step 8: 통과 확인** — `./gradlew test --tests '*LegacySrMapperIT' --tests '*LegacySelectOnlyGuardTest'` → PASS.

- [ ] **Step 9: 커밋**
```bash
git add src/main/java/com/meritz/dash/aggregation/LegacyDevRow.java src/main/java/com/meritz/dash/aggregation/LegacySrProjectRow.java src/main/java/com/meritz/dash/mapper/legacy src/main/resources/mapper/legacy src/main/resources/application.yml src/test/resources/legacy-fixture src/test/java/com/meritz/dash/support/LegacyFixture.java src/test/java/com/meritz/dash/aggregation/LegacySrMapperIT.java src/test/java/com/meritz/dash/mapper/legacy/LegacySelectOnlyGuardTest.java
git commit -m "feat: 기간계 SELECT 매퍼(query1/query2) + 픽스처 + SELECT-only 가드 + JDBC 타임아웃"
```

---

## Task 5: 집계 배치 서비스(멱등) + DASH write 매퍼

**Files:**
- Create: `src/main/java/com/meritz/dash/aggregation/DevAgg.java`, `ResourceSnapshot.java`, `SrProject.java`
- Create: `src/main/java/com/meritz/dash/mapper/app/DashWriteMapper.java`, `CodeRefMapper.java`
- Create: `src/main/resources/mapper/app/DashWriteMapper.xml`, `CodeRefMapper.xml`
- Create: `src/main/java/com/meritz/dash/aggregation/AggregationService.java`
- Test: `src/test/java/com/meritz/dash/aggregation/AggregationServiceIT.java`

**Interfaces:**
- Consumes: `LegacySrMapper`(Task4), `HR_DEVELOPER`(D), `CD_COMMON.ATTR1`(Task1), `MmProperties`(Task3).
- Produces: `AggregationService.run(String periodYm, String trigger) : long`(=runId). 멱등(같은 periodYm 재실행 = 동일 DASH 상태). DASH_DEV_AGG/DASH_RESOURCE(TEAM+PART)/DASH_SR_PROJECT MERGE + BATCH_RUN_LOG 기록.
- Produces: `CodeRefMapper.srClsByTpcd() : Map<String,String>`(SR_TPCD→SR_CLS via ATTR1).

- [ ] **Step 1: DB2 행 record 작성**

```java
// DevAgg.java
package com.meritz.dash.aggregation;
public record DevAgg(String periodYm, String empno, String srCls, int srCnt, double jobMm) {}
```
```java
// ResourceSnapshot.java
package com.meritz.dash.aggregation;
public record ResourceSnapshot(String periodYm, String unitType, String unitId,
        int headcount, int availHeadcount, double availMm, double usedMm, double overtimeMm) {}
```
```java
// SrProject.java
package com.meritz.dash.aggregation;
public record SrProject(String periodYm, String srNo, String titlCntt, String srTpcd, String srTpcdName,
        double totMm, int empCnt, String prchDpcd, String dpcd, String regDate, String rflcScdlDate) {}
```

- [ ] **Step 2: CodeRefMapper(SR_TPCD→SR_CLS) + XML**

```java
package com.meritz.dash.mapper.app;
import org.apache.ibatis.annotations.MapKey;
import java.util.Map;
public interface CodeRefMapper {
    @MapKey("srTpcd")
    Map<String, SrClsRef> srClsByTpcd();
    record SrClsRef(String srTpcd, String srCls) {}
}
```
`CodeRefMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.meritz.dash.mapper.app.CodeRefMapper">
  <select id="srClsByTpcd" resultType="com.meritz.dash.mapper.app.CodeRefMapper$SrClsRef">
    SELECT CD_VAL AS srTpcd, NVL(ATTR1,'99') AS srCls
      FROM CD_COMMON WHERE GRP_CD='SR_TPCD' AND USE_YN='Y'
  </select>
</mapper>
```

- [ ] **Step 3: DashWriteMapper + XML (MERGE upsert + 로그)**

```java
package com.meritz.dash.mapper.app;

import com.meritz.dash.aggregation.*;
import org.apache.ibatis.annotations.Param;

public interface DashWriteMapper {
    void deleteDevAgg(@Param("periodYm") String periodYm);
    void insertDevAgg(DevAgg row);
    void deleteResource(@Param("periodYm") String periodYm);
    void insertResource(ResourceSnapshot row);
    void deleteSrProject(@Param("periodYm") String periodYm);
    void insertSrProject(SrProject row);
    long insertRunStart(@Param("periodYm") String periodYm, @Param("trigger") String trigger);
    void updateRunFinish(@Param("runId") long runId, @Param("status") String status,
                         @Param("devRows") int devRows, @Param("srRows") int srRows, @Param("msg") String msg);
}
```
`DashWriteMapper.xml` (delete+insert로 period 멱등; IDENTITY 키 회수):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.meritz.dash.mapper.app.DashWriteMapper">
  <delete id="deleteDevAgg">DELETE FROM DASH_DEV_AGG WHERE PERIOD_YM=#{periodYm}</delete>
  <insert id="insertDevAgg">
    INSERT INTO DASH_DEV_AGG (PERIOD_YM,EMPNO,SR_CLS,SR_CNT,JOB_MM)
    VALUES (#{periodYm},#{empno},#{srCls},#{srCnt},#{jobMm})
  </insert>
  <delete id="deleteResource">DELETE FROM DASH_RESOURCE WHERE PERIOD_YM=#{periodYm}</delete>
  <insert id="insertResource">
    INSERT INTO DASH_RESOURCE (PERIOD_YM,UNIT_TYPE,UNIT_ID,HEADCOUNT,AVAIL_HEADCOUNT,AVAIL_MM,USED_MM,OVERTIME_MM)
    VALUES (#{periodYm},#{unitType},#{unitId},#{headcount},#{availHeadcount},#{availMm},#{usedMm},#{overtimeMm})
  </insert>
  <delete id="deleteSrProject">DELETE FROM DASH_SR_PROJECT WHERE PERIOD_YM=#{periodYm}</delete>
  <insert id="insertSrProject">
    INSERT INTO DASH_SR_PROJECT (PERIOD_YM,SR_NO,TITL_CNTT,SR_TPCD,SR_TPCD_NAME,TOT_MM,EMP_CNT,PRCH_DPCD,DPCD,REG_DATE,RFLC_SCDL_DATE)
    VALUES (#{periodYm},#{srNo},#{titlCntt},#{srTpcd},#{srTpcdName},#{totMm},#{empCnt},#{prchDpcd},#{dpcd},#{regDate},#{rflcScdlDate})
  </insert>
  <insert id="insertRunStart" useGeneratedKeys="true" keyProperty="runId" keyColumn="RUN_ID">
    INSERT INTO BATCH_RUN_LOG (PERIOD_YM,TRIGGER,STATUS,STARTED_AT)
    VALUES (#{periodYm},#{trigger},'OK',SYSTIMESTAMP)
  </insert>
  <update id="updateRunFinish">
    UPDATE BATCH_RUN_LOG SET STATUS=#{status}, DEV_ROWS=#{devRows}, SR_ROWS=#{srRows},
           MSG=#{msg}, FINISHED_AT=SYSTIMESTAMP WHERE RUN_ID=#{runId}
  </update>
</mapper>
```
> `insertRunStart`는 `@Param`이 있으므로 keyProperty 회수를 위해 매퍼 시그니처를 `long`이 아닌 void+param 방식 대신, 서비스에서 `Map` 파라미터로 runId를 회수한다. (구현 시: `insertRunStart`를 `void insertRunStart(BatchRunStart s)` 형태의 단일 파라미터 객체로 바꿔 keyProperty 회수. 아래 서비스 코드는 이를 반영.)

- [ ] **Step 4: AggregationServiceIT 실패 테스트(멱등 포함)**

```java
package com.meritz.dash.aggregation;

import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AggregationServiceIT extends LegacyFixture {

    @Autowired AggregationService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("run(202605): DASH 적재 + 멱등(2회=동일) + 야근 계산")
    void run_and_idempotent() {
        service.run("202605", "MANUAL");
        Integer dev1 = jdbc.queryForObject("SELECT COUNT(*) FROM DASH_DEV_AGG WHERE PERIOD_YM='202605'", Integer.class);
        // E0003: 208h/166=1.25MM → 야근 0.25 (PART/TEAM OVERTIME_MM 반영)
        Double teamOt = jdbc.queryForObject(
            "SELECT OVERTIME_MM FROM DASH_RESOURCE WHERE PERIOD_YM='202605' AND UNIT_TYPE='TEAM' AND UNIT_ID='ALL'", Double.class);
        assertThat(teamOt).isGreaterThan(0.0);

        service.run("202605", "MANUAL"); // 재실행
        Integer dev2 = jdbc.queryForObject("SELECT COUNT(*) FROM DASH_DEV_AGG WHERE PERIOD_YM='202605'", Integer.class);
        assertThat(dev2).isEqualTo(dev1); // 멱등

        Integer runs = jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_RUN_LOG WHERE PERIOD_YM='202605' AND STATUS='OK'", Integer.class);
        assertThat(runs).isEqualTo(2);
    }
}
```

- [ ] **Step 5: 실패 확인** — `./gradlew test --tests '*AggregationServiceIT'` → FAIL.

- [ ] **Step 6: AggregationService 구현**

```java
package com.meritz.dash.aggregation;

import com.meritz.dash.config.MmProperties;
import com.meritz.dash.mapper.app.CodeRefMapper;
import com.meritz.dash.mapper.app.DashWriteMapper;
import com.meritz.dash.mapper.legacy.LegacySrMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AggregationService {

    private final LegacySrMapper legacy;
    private final DashWriteMapper dash;
    private final CodeRefMapper codeRef;
    private final JdbcTemplate appJdbc;     // HR 스냅샷 집계용(app DS)
    private final MmProperties mm;

    public AggregationService(LegacySrMapper legacy, DashWriteMapper dash, CodeRefMapper codeRef,
                              JdbcTemplate appJdbc, MmProperties mm) {
        this.legacy = legacy; this.dash = dash; this.codeRef = codeRef; this.appJdbc = appJdbc; this.mm = mm;
    }

    /** 기간계 read → 계산 → DB2 MERGE. 같은 periodYm 재실행 시 delete+insert로 멱등. */
    @Transactional("appTxManager")
    public long run(String periodYm, String trigger) {
        BatchRunStart start = new BatchRunStart(periodYm, trigger);
        dash.insertRunStart(start);
        long runId = start.runId();
        try {
            // 1) 기간계 dev 집계(읽기 전용 트랜잭션은 매퍼 호출 자체가 legacy DS 사용)
            List<LegacyDevRow> devRows = legacy.selectDevAgg(periodYm);
            Map<String, CodeRefMapper.SrClsRef> clsMap = codeRef.srClsByTpcd();

            // 2) (empno, srCls) 집계
            Map<String, int[]> cntByKey = new HashMap<>();          // key=empno|srCls → [cnt]
            Map<String, double[]> hourByKey = new HashMap<>();      // key=empno|srCls → [hours]
            for (LegacyDevRow r : devRows) {
                String cls = clsMap.containsKey(r.srTpcd()) ? clsMap.get(r.srTpcd()).srCls() : "99";
                String key = r.empno() + "|" + cls;
                cntByKey.computeIfAbsent(key, k -> new int[1])[0] += r.srCnt();
                hourByKey.computeIfAbsent(key, k -> new double[1])[0] += r.jobHours();
            }
            dash.deleteDevAgg(periodYm);
            int devCount = 0;
            Map<String, Double> mmByEmp = new HashMap<>();          // 개발자 총 MM(야근 계산용)
            for (var e : cntByKey.entrySet()) {
                String[] k = e.getKey().split("\\|");
                double jobMm = round2(hourByKey.get(e.getKey())[0] / mm.hoursPerMonth());
                dash.insertDevAgg(new DevAgg(periodYm, k[0], k[1], e.getValue()[0], jobMm));
                mmByEmp.merge(k[0], jobMm, Double::sum);
                devCount++;
            }

            // 3) HR 스냅샷 + 사용중/야근 → DASH_RESOURCE(TEAM + PART)
            dash.deleteResource(periodYm);
            writeResourceSnapshots(periodYm, mmByEmp);

            // 4) Top SR
            dash.deleteSrProject(periodYm);
            List<LegacySrProjectRow> srRows = legacy.selectSrProjects(periodYm, mm.topMinMm());
            for (LegacySrProjectRow s : srRows) {
                dash.insertSrProject(new SrProject(periodYm, s.srNo(), s.titlCntt(), s.srTpcd(), s.srTpcdName(),
                        round2(s.totMm()), s.empCnt(), s.prchDpcd(), s.dpcd(), s.regDate(), s.rflcScdlDate()));
            }

            dash.updateRunFinish(runId, "OK", devCount, srRows.size(), null);
            return runId;
        } catch (RuntimeException ex) {
            dash.updateRunFinish(runId, "FAIL", 0, 0, ex.getMessage());
            throw ex;
        }
    }

    private void writeResourceSnapshots(String periodYm, Map<String, Double> mmByEmp) {
        // HR: empno → partCd, 개발가능·재직 여부
        List<Map<String,Object>> hr = appJdbc.queryForList(
            "SELECT EMPNO, PART_CD, DEV_YN, STATUS_CD FROM HR_DEVELOPER");
        Map<String,String> partByEmp = new HashMap<>();
        Map<String,int[]> headByPart = new HashMap<>();     // [headcount, availHeadcount]
        for (Map<String,Object> row : hr) {
            String empno = (String) row.get("EMPNO");
            String part = (String) row.get("PART_CD");
            partByEmp.put(empno, part);
            int[] h = headByPart.computeIfAbsent(part, k -> new int[2]);
            h[0]++; // headcount(재직 가정; STATUS '02'면 제외하려면 조건)
            boolean avail = "Y".equals(row.get("DEV_YN")) && "01".equals(row.get("STATUS_CD"));
            if (avail) h[1]++;
        }
        // 사용중/야근 파트 집계(미매칭 → '미분류')
        Map<String,double[]> usedOtByPart = new HashMap<>();   // [usedMm, overtimeMm]
        for (var e : mmByEmp.entrySet()) {
            String part = partByEmp.getOrDefault(e.getKey(), "미분류");
            double[] u = usedOtByPart.computeIfAbsent(part, k -> new double[2]);
            u[0] += e.getValue();
            u[1] += Math.max(e.getValue() - mm.overtimeThreshold(), 0);
        }
        int teamHead=0, teamAvail=0; double teamUsed=0, teamOt=0;
        Set<String> parts = new HashSet<>();
        parts.addAll(headByPart.keySet()); parts.addAll(usedOtByPart.keySet());
        for (String part : parts) {
            int head = headByPart.getOrDefault(part, new int[2])[0];
            int avail = headByPart.getOrDefault(part, new int[2])[1];
            double availMm = avail * 1.0;
            double used = usedOtByPart.getOrDefault(part, new double[2])[0];
            double ot = usedOtByPart.getOrDefault(part, new double[2])[1];
            dash.insertResource(new ResourceSnapshot(periodYm, "PART", part,
                    head, avail, round2(availMm), round2(used), round2(ot)));
            teamHead+=head; teamAvail+=avail; teamUsed+=used; teamOt+=ot;
        }
        dash.insertResource(new ResourceSnapshot(periodYm, "TEAM", "ALL",
                teamHead, teamAvail, round2(teamAvail*1.0), round2(teamUsed), round2(teamOt)));
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
```
`BatchRunStart.java` (keyProperty 회수용 가변 객체):
```java
package com.meritz.dash.aggregation;
public class BatchRunStart {
    private final String periodYm; private final String trigger; private long runId;
    public BatchRunStart(String periodYm, String trigger){ this.periodYm=periodYm; this.trigger=trigger; }
    public String getPeriodYm(){ return periodYm; } public String getTrigger(){ return trigger; }
    public long getRunId(){ return runId; } public void setRunId(long id){ this.runId=id; }
    public long runId(){ return runId; }
}
```
`DashWriteMapper.insertRunStart` 시그니처를 `void insertRunStart(BatchRunStart s)`로, XML을 `keyProperty="runId" keyColumn="RUN_ID"`, VALUES `#{periodYm},#{trigger}`로 맞춘다.

> 트랜잭션 주의: `run`은 `@Transactional("appTxManager")`. 기간계 매퍼 호출은 legacy DS(읽기)로 별도 동작하며 app 트랜잭션과 분리된다(XA 아님). 기간계는 읽기 전용이라 정합 위험 없음.

- [ ] **Step 7: 통과 확인** — `./gradlew test --tests '*AggregationServiceIT'` → PASS(멱등 2회 동일, 야근>0).

- [ ] **Step 8: 커밋**
```bash
git add src/main/java/com/meritz/dash/aggregation src/main/java/com/meritz/dash/mapper/app/DashWriteMapper.java src/main/java/com/meritz/dash/mapper/app/CodeRefMapper.java src/main/resources/mapper/app/DashWriteMapper.xml src/main/resources/mapper/app/CodeRefMapper.xml src/test/java/com/meritz/dash/aggregation/AggregationServiceIT.java
git commit -m "feat: 집계 배치 서비스 run(periodYm) — 기간계→DB2 멱등 적재(DEV_AGG/RESOURCE/SR_PROJECT)"
```

---

## Task 6: aggregations API (수동 집계 + 이력) + @Scheduled

**Files:**
- Create: `src/main/java/com/meritz/dash/aggregation/AggregationRequest.java`, `BatchRunLogView.java`
- Create: `src/main/java/com/meritz/dash/aggregation/AggregationController.java`
- Create: `src/main/java/com/meritz/dash/aggregation/AggregationScheduler.java`
- Create: `src/main/java/com/meritz/dash/mapper/app/BatchLogMapper.java` + XML
- Test: `src/test/java/com/meritz/dash/aggregation/AggregationControllerTest.java`

**Interfaces:**
- Consumes: `AggregationService.run`(Task5).
- Produces: `POST /api/v1/aggregations` body `{ "periodYm":"202605" }` 또는 `{ "from":"202601","to":"202605" }` → 201, data=실행된 periodYm 목록. `GET /api/v1/aggregations` → 이력 목록.
- Produces: `record AggregationRequest(String periodYm, String from, String to)` + `periods()` (단일 또는 from~to 전개, `YYYYMM` 형식 검증).

- [ ] **Step 1: AggregationRequest + periods() 단위테스트(경계)**

```java
package com.meritz.dash.aggregation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AggregationRequestTest {
    @Test @DisplayName("단일 periodYm")
    void single() {
        assertThat(new AggregationRequest("202605", null, null).periods()).containsExactly("202605");
    }
    @Test @DisplayName("from~to 전개(월 증가, 연도 넘김)")
    void range() {
        assertThat(new AggregationRequest(null, "202611", "202602").periods())
            .isEqualTo(List.of()); // from>to → 빈 목록 아님? 아래 구현은 from<=to만 전개
    }
    @Test @DisplayName("잘못된 형식 → IllegalArgumentException")
    void invalid() {
        assertThatThrownBy(() -> new AggregationRequest("2026-05", null, null).periods())
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```
> 위 range 테스트는 의도를 드러내기 위한 것이며, 구현은 `from<=to`일 때 오름차순 전개, `from>to`면 `IllegalArgumentException`. 아래 구현에 맞춰 테스트를 `assertThatThrownBy(...)`로 교정해 작성한다.

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*AggregationRequestTest'` → FAIL.

- [ ] **Step 3: AggregationRequest 구현**

```java
package com.meritz.dash.aggregation;

import java.util.ArrayList;
import java.util.List;

public record AggregationRequest(String periodYm, String from, String to) {

    private static void checkFmt(String ym) {
        if (ym == null || !ym.matches("\\d{6}")) {
            throw new IllegalArgumentException("periodYm은 YYYYMM 형식이어야 합니다: " + ym);
        }
        int mm = Integer.parseInt(ym.substring(4));
        if (mm < 1 || mm > 12) throw new IllegalArgumentException("월 범위 오류: " + ym);
    }

    public List<String> periods() {
        if (periodYm != null) { checkFmt(periodYm); return List.of(periodYm); }
        checkFmt(from); checkFmt(to);
        if (from.compareTo(to) > 0) throw new IllegalArgumentException("from은 to보다 클 수 없습니다.");
        List<String> out = new ArrayList<>();
        int y = Integer.parseInt(from.substring(0,4)), m = Integer.parseInt(from.substring(4));
        int ey = Integer.parseInt(to.substring(0,4)), em = Integer.parseInt(to.substring(4));
        while (y < ey || (y == ey && m <= em)) {
            out.add(String.format("%04d%02d", y, m));
            if (++m > 12) { m = 1; y++; }
        }
        return out;
    }
}
```
Step 1 테스트의 `range`/`invalid`를 이 구현에 맞게 교정: 정상 범위 전개 1건 + `from>to` 예외 1건 + 형식오류 예외 1건.

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests '*AggregationRequestTest'` → PASS.

- [ ] **Step 5: BatchLogMapper(이력 조회) + XML + view record**

```java
// BatchRunLogView.java
package com.meritz.dash.aggregation;
public record BatchRunLogView(long runId, String periodYm, String trigger, String status,
        int devRows, int srRows, String startedAt, String finishedAt, String msg) {}
```
```java
package com.meritz.dash.mapper.app;
import com.meritz.dash.aggregation.BatchRunLogView;
import java.util.List;
public interface BatchLogMapper { List<BatchRunLogView> findRecent(); }
```
`BatchLogMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.meritz.dash.mapper.app.BatchLogMapper">
  <select id="findRecent" resultType="com.meritz.dash.aggregation.BatchRunLogView">
    SELECT RUN_ID AS runId, PERIOD_YM AS periodYm, TRIGGER AS trigger, STATUS AS status,
           DEV_ROWS AS devRows, SR_ROWS AS srRows,
           TO_CHAR(STARTED_AT,'YYYY-MM-DD HH24:MI:SS') AS startedAt,
           TO_CHAR(FINISHED_AT,'YYYY-MM-DD HH24:MI:SS') AS finishedAt, MSG AS msg
      FROM BATCH_RUN_LOG ORDER BY RUN_ID DESC FETCH FIRST 50 ROWS ONLY
  </select>
</mapper>
```

- [ ] **Step 6: AggregationControllerTest 실패 테스트**

```java
package com.meritz.dash.aggregation;

import com.meritz.dash.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AggregationController.class)
@Import(GlobalExceptionHandler.class)
class AggregationControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AggregationService service;
    @MockBean com.meritz.dash.mapper.app.BatchLogMapper batchLog;

    @Test
    @DisplayName("POST /aggregations {periodYm} → 201 + 실행 목록")
    void run_single() throws Exception {
        when(service.run("202605","MANUAL")).thenReturn(1L);
        mvc.perform(post("/api/v1/aggregations").contentType("application/json")
                .content("{\"periodYm\":\"202605\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.periods[0]").value("202605"));
        verify(service).run("202605","MANUAL");
    }

    @Test
    @DisplayName("POST 잘못된 형식 → 400 ProblemDetail")
    void run_invalid() throws Exception {
        mvc.perform(post("/api/v1/aggregations").contentType("application/json")
                .content("{\"periodYm\":\"2026-05\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400));
    }
}
```

- [ ] **Step 7: 실패 확인** — `./gradlew test --tests '*AggregationControllerTest'` → FAIL.

- [ ] **Step 8: Controller + Scheduler 구현**

```java
package com.meritz.dash.aggregation;

import com.meritz.dash.common.ApiResponse;
import com.meritz.dash.mapper.app.BatchLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/aggregations")
public class AggregationController {

    private final AggregationService service;
    private final BatchLogMapper batchLog;

    public AggregationController(AggregationService service, BatchLogMapper batchLog) {
        this.service = service; this.batchLog = batchLog;
    }

    @Operation(summary = "수동 집계 실행(단일 월 또는 from~to)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String,Object>> run(@RequestBody AggregationRequest req) {
        List<String> periods = req.periods();   // 형식 오류 시 IllegalArgumentException → 400
        for (String p : periods) service.run(p, "MANUAL");
        return ApiResponse.of(Map.of("periods", periods, "count", periods.size()));
    }

    @Operation(summary = "집계 실행 이력")
    @GetMapping
    public ApiResponse<List<BatchRunLogView>> history() {
        return ApiResponse.of(batchLog.findRecent());
    }
}
```
`AggregationScheduler.java`:
```java
package com.meritz.dash.aggregation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class AggregationScheduler {
    private final AggregationService service;
    public AggregationScheduler(AggregationService service) { this.service = service; }

    @Scheduled(cron = "${app.aggregation.cron:0 0 2 * * *}")
    public void daily() {
        String ym = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        service.run(ym, "SCHEDULED");
    }
}
```
`DashApplication`에 `@EnableScheduling` 추가.

- [ ] **Step 9: 통과 확인** — `./gradlew test --tests '*AggregationControllerTest'` → PASS.

- [ ] **Step 10: 커밋**
```bash
git add src/main/java/com/meritz/dash/aggregation/AggregationRequest.java src/main/java/com/meritz/dash/aggregation/BatchRunLogView.java src/main/java/com/meritz/dash/aggregation/AggregationController.java src/main/java/com/meritz/dash/aggregation/AggregationScheduler.java src/main/java/com/meritz/dash/mapper/app/BatchLogMapper.java src/main/resources/mapper/app/BatchLogMapper.xml src/main/java/com/meritz/dash/DashApplication.java src/test/java/com/meritz/dash/aggregation/AggregationRequestTest.java src/test/java/com/meritz/dash/aggregation/AggregationControllerTest.java
git commit -m "feat: aggregations API(수동 집계+이력) + @Scheduled 일배치"
```

---

## Task 7: sr-projects API (Top SR)

**Files:**
- Create: `src/main/java/com/meritz/dash/srproject/SrProjectView.java`, `SrProjectService.java`, `SrProjectController.java`
- Create: `src/main/java/com/meritz/dash/mapper/app/SrProjectMapper.java` + XML
- Test: `src/test/java/com/meritz/dash/srproject/SrProjectMapperIT.java`, `SrProjectControllerTest.java`

**Interfaces:**
- Produces: `SrProjectMapper.findTop(@Param period, @Param minMm, @Param type, @Param offset, @Param size) : List<SrProjectView>`, `countTop(...)` for meta.
- Produces: `record SrProjectView(String srNo, String titlCntt, String srTpcd, String srTpcdName, double totMm, int empCnt, String prchDpcd, String dpcd)`.
- Produces: `GET /api/v1/sr-projects?period=YYYYMM&minMm=0.6&type=&page=0&size=5` → data 목록 + meta{page,size,totalElements,period}.

- [ ] **Step 1: SrProjectMapperIT 실패 테스트**

```java
package com.meritz.dash.srproject;

import com.meritz.dash.aggregation.AggregationService;
import com.meritz.dash.mapper.app.SrProjectMapper;
import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SrProjectMapperIT extends LegacyFixture {
    @Autowired AggregationService agg;
    @Autowired SrProjectMapper mapper;

    @BeforeEach void seedAgg() { agg.run("202605", "MANUAL"); }

    @Test @DisplayName("findTop: minMm 이상, totMm 내림차순, 5개씩")
    void top() {
        List<SrProjectView> list = mapper.findTop("202605", 0.6, null, 0, 5);
        assertThat(list).isNotEmpty();
        assertThat(list).allMatch(v -> v.totMm() >= 0.6);
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*SrProjectMapperIT'` → FAIL.

- [ ] **Step 3: View + Mapper + XML**

```java
// SrProjectView.java
package com.meritz.dash.srproject;
public record SrProjectView(String srNo, String titlCntt, String srTpcd, String srTpcdName,
        double totMm, int empCnt, String prchDpcd, String dpcd) {}
```
```java
package com.meritz.dash.mapper.app;
import com.meritz.dash.srproject.SrProjectView;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface SrProjectMapper {
    List<SrProjectView> findTop(@Param("period") String period, @Param("minMm") double minMm,
        @Param("type") String type, @Param("offset") int offset, @Param("size") int size);
    int countTop(@Param("period") String period, @Param("minMm") double minMm, @Param("type") String type);
}
```
`SrProjectMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.meritz.dash.mapper.app.SrProjectMapper">
  <sql id="cond">
    PERIOD_YM = #{period} AND TOT_MM &gt;= #{minMm}
    <if test="type != null and type != ''">AND SR_TPCD = #{type}</if>
  </sql>
  <select id="findTop" resultType="com.meritz.dash.srproject.SrProjectView">
    SELECT SR_NO AS srNo, TITL_CNTT AS titlCntt, SR_TPCD AS srTpcd, SR_TPCD_NAME AS srTpcdName,
           TOT_MM AS totMm, EMP_CNT AS empCnt, PRCH_DPCD AS prchDpcd, DPCD AS dpcd
      FROM DASH_SR_PROJECT WHERE <include refid="cond"/>
     ORDER BY TOT_MM DESC
     OFFSET #{offset} ROWS FETCH NEXT #{size} ROWS ONLY
  </select>
  <select id="countTop" resultType="int">
    SELECT COUNT(*) FROM DASH_SR_PROJECT WHERE <include refid="cond"/>
  </select>
</mapper>
```

- [ ] **Step 4: 매퍼 통과 확인** — `./gradlew test --tests '*SrProjectMapperIT'` → PASS.

- [ ] **Step 5: Service + Controller**

```java
package com.meritz.dash.srproject;

import com.meritz.dash.config.MmProperties;
import com.meritz.dash.mapper.app.SrProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class SrProjectService {
    private final SrProjectMapper mapper;
    private final MmProperties mm;
    public SrProjectService(SrProjectMapper mapper, MmProperties mm) { this.mapper = mapper; this.mm = mm; }

    public record Page(List<SrProjectView> items, int totalElements) {}

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public Page top(String period, Double minMm, String type, int page, int size) {
        if (period == null || !period.matches("\\d{6}")) throw new IllegalArgumentException("period는 YYYYMM");
        if (page < 0 || size < 1) throw new IllegalArgumentException("페이징 파라미터 오류");
        double floor = (minMm == null) ? mm.topMinMm() : minMm;
        return new Page(mapper.findTop(period, floor, type, page*size, size),
                        mapper.countTop(period, floor, type));
    }
}
```
```java
package com.meritz.dash.srproject;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sr-projects")
public class SrProjectController {
    private final SrProjectService service;
    public SrProjectController(SrProjectService service) { this.service = service; }

    @Operation(summary = "주요 SR Top(M/M 기준, 페이지당 기본 5)")
    @GetMapping
    public ApiResponse<java.util.List<SrProjectView>> top(
            @RequestParam String period,
            @RequestParam(required = false) Double minMm,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        SrProjectService.Page p = service.top(period, minMm, type, page, size);
        return ApiResponse.of(p.items(), Map.of("page", page, "size", size,
                "totalElements", p.totalElements(), "period", period));
    }
}
```

- [ ] **Step 6: SrProjectControllerTest(@WebMvcTest) — 200 + 400(period 누락/형식)**

```java
package com.meritz.dash.srproject;

import com.meritz.dash.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SrProjectController.class)
@Import(GlobalExceptionHandler.class)
class SrProjectControllerTest {
    @Autowired MockMvc mvc;
    @MockBean SrProjectService service;

    @Test void ok() throws Exception {
        when(service.top(eq("202605"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new SrProjectService.Page(List.of(
                new SrProjectView("SR1","제목","1","개발요청",1.2,2,"D101","D101")), 1));
        mvc.perform(get("/api/v1/sr-projects").param("period","202605"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].srNo").value("SR1"))
           .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test void missing_period() throws Exception {
        mvc.perform(get("/api/v1/sr-projects"))
           .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 7: 통과 확인** — `./gradlew test --tests '*SrProjectControllerTest'` → PASS.

- [ ] **Step 8: 커밋**
```bash
git add src/main/java/com/meritz/dash/srproject src/main/java/com/meritz/dash/mapper/app/SrProjectMapper.java src/main/resources/mapper/app/SrProjectMapper.xml src/test/java/com/meritz/dash/srproject
git commit -m "feat: sr-projects API(Top SR, minMm 필터, 페이지당 5)"
```

---

## Task 8: resource API (가동률 도넛 + 야근 상세)

**Files:**
- Create: `src/main/java/com/meritz/dash/resource/ResourceView.java`, `OvertimeView.java`, `ResourceService.java`, `ResourceController.java`
- Create: `src/main/java/com/meritz/dash/mapper/app/ResourceMapper.java` + XML
- Test: `src/test/java/com/meritz/dash/resource/ResourceMapperIT.java`, `ResourceControllerTest.java`

**Interfaces:**
- Produces: `ResourceMapper.findUnit(@Param period,@Param unitType,@Param unitId) : ResourceRow`; `findOvertimeByPart(@Param period,@Param part) : List<OvertimeView>`(개발자별 야근, DASH_DEV_AGG⨝HR).
- Produces: `record ResourceView(String periodYm,String unitType,String unitId,int headcount,int availHeadcount,double availMm,double usedMm,double overtimeMm,double utilization)`(utilization=used/avail, 분모0→0).
- Produces: `GET /api/v1/resource?period=YYYYMM&unit=team|part&unitId=` , `GET /api/v1/resource/overtime?period=YYYYMM&part=`.

- [ ] **Step 1: ResourceMapperIT 실패 테스트**

```java
package com.meritz.dash.resource;

import com.meritz.dash.aggregation.AggregationService;
import com.meritz.dash.mapper.app.ResourceMapper;
import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceMapperIT extends LegacyFixture {
    @Autowired AggregationService agg;
    @Autowired ResourceMapper mapper;

    @BeforeEach void seed() { agg.run("202605", "MANUAL"); }

    @Test @DisplayName("TEAM 스냅샷 + 야근 합 존재")
    void team() {
        var row = mapper.findUnit("202605", "TEAM", "ALL");
        assertThat(row).isNotNull();
        assertThat(row.usedMm()).isGreaterThan(0.0);
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*ResourceMapperIT'` → FAIL.

- [ ] **Step 3: row record + Mapper + XML**

```java
// ResourceRow.java (매퍼 결과)
package com.meritz.dash.resource;
public record ResourceRow(String periodYm, String unitType, String unitId,
        int headcount, int availHeadcount, double availMm, double usedMm, double overtimeMm) {}
```
```java
// OvertimeView.java
package com.meritz.dash.resource;
public record OvertimeView(String empno, String empNm, String partCd, double planMm, double overtimeMm) {}
```
```java
package com.meritz.dash.mapper.app;
import com.meritz.dash.resource.ResourceRow;
import com.meritz.dash.resource.OvertimeView;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface ResourceMapper {
    ResourceRow findUnit(@Param("period") String period, @Param("unitType") String unitType, @Param("unitId") String unitId);
    List<OvertimeView> findOvertimeByPart(@Param("period") String period, @Param("part") String part, @Param("threshold") double threshold);
}
```
`ResourceMapper.xml` (overtime은 DASH_DEV_AGG에서 개발자 MM 합 → 야근 계산, HR로 이름/파트):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.meritz.dash.mapper.app.ResourceMapper">
  <select id="findUnit" resultType="com.meritz.dash.resource.ResourceRow">
    SELECT PERIOD_YM AS periodYm, UNIT_TYPE AS unitType, UNIT_ID AS unitId,
           HEADCOUNT AS headcount, AVAIL_HEADCOUNT AS availHeadcount,
           AVAIL_MM AS availMm, USED_MM AS usedMm, OVERTIME_MM AS overtimeMm
      FROM DASH_RESOURCE
     WHERE PERIOD_YM=#{period} AND UNIT_TYPE=#{unitType} AND UNIT_ID=#{unitId}
  </select>
  <select id="findOvertimeByPart" resultType="com.meritz.dash.resource.OvertimeView">
    SELECT d.EMPNO AS empno, h.EMP_NM AS empNm, NVL(h.PART_CD,'미분류') AS partCd,
           SUM(d.JOB_MM) AS planMm,
           CASE WHEN SUM(d.JOB_MM) > #{threshold} THEN SUM(d.JOB_MM) - #{threshold} ELSE 0 END AS overtimeMm
      FROM DASH_DEV_AGG d LEFT JOIN HR_DEVELOPER h ON d.EMPNO = h.EMPNO
     WHERE d.PERIOD_YM=#{period}
       <if test="part != null and part != ''">AND NVL(h.PART_CD,'미분류') = #{part}</if>
     GROUP BY d.EMPNO, h.EMP_NM, h.PART_CD
     ORDER BY overtimeMm DESC
  </select>
</mapper>
```

- [ ] **Step 4: 매퍼 통과 확인** — `./gradlew test --tests '*ResourceMapperIT'` → PASS.

- [ ] **Step 5: Service + Controller (가동률 분모 0 방어)**

```java
package com.meritz.dash.resource;

import com.meritz.dash.config.MmProperties;
import com.meritz.dash.mapper.app.ResourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class ResourceService {
    private final ResourceMapper mapper;
    private final MmProperties mm;
    public ResourceService(ResourceMapper mapper, MmProperties mm) { this.mapper = mapper; this.mm = mm; }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public ResourceView unit(String period, String unit, String unitId) {
        if (period == null || !period.matches("\\d{6}")) throw new IllegalArgumentException("period는 YYYYMM");
        String unitType = "part".equalsIgnoreCase(unit) ? "PART" : "TEAM";
        String id = (unitType.equals("TEAM")) ? "ALL" : (unitId == null ? "" : unitId);
        ResourceRow r = mapper.findUnit(period, unitType, id);
        if (r == null) throw new IllegalArgumentException("해당 기간/단위 집계가 없습니다: " + period + "/" + unitType + "/" + id);
        double util = r.availMm() == 0 ? 0.0 : Math.round(r.usedMm() / r.availMm() * 1000.0) / 1000.0;
        return new ResourceView(r.periodYm(), r.unitType(), r.unitId(), r.headcount(), r.availHeadcount(),
                r.availMm(), r.usedMm(), r.overtimeMm(), util);
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<OvertimeView> overtime(String period, String part) {
        if (period == null || !period.matches("\\d{6}")) throw new IllegalArgumentException("period는 YYYYMM");
        return mapper.findOvertimeByPart(period, part, mm.overtimeThreshold());
    }
}
```
```java
// ResourceView.java
package com.meritz.dash.resource;
public record ResourceView(String periodYm, String unitType, String unitId, int headcount,
        int availHeadcount, double availMm, double usedMm, double overtimeMm, double utilization) {}
```
```java
package com.meritz.dash.resource;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/resource")
public class ResourceController {
    private final ResourceService service;
    public ResourceController(ResourceService service) { this.service = service; }

    @Operation(summary = "가동률(단위별 인원/가용/사용중/가동률/야근)")
    @GetMapping
    public ApiResponse<ResourceView> resource(@RequestParam String period,
            @RequestParam(defaultValue = "team") String unit,
            @RequestParam(required = false) String unitId) {
        return ApiResponse.of(service.unit(period, unit, unitId));
    }

    @Operation(summary = "야근 상세(개발자별 야근 M/M)")
    @GetMapping("/overtime")
    public ApiResponse<List<OvertimeView>> overtime(@RequestParam String period,
            @RequestParam(required = false) String part) {
        List<OvertimeView> list = service.overtime(period, part);
        double avg = list.isEmpty() ? 0.0 :
            Math.round(list.stream().mapToDouble(OvertimeView::overtimeMm).sum() / list.size() * 1000.0) / 1000.0;
        return ApiResponse.of(list, Map.of("period", period, "count", list.size(), "avgOvertimeMm", avg));
    }
}
```

- [ ] **Step 6: ResourceControllerTest(@WebMvcTest) — 분모 0 방어 + 400**

```java
package com.meritz.dash.resource;

import com.meritz.dash.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ResourceController.class)
@Import(GlobalExceptionHandler.class)
class ResourceControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ResourceService service;

    @Test void util_zero_when_no_avail() throws Exception {
        when(service.unit(eq("202605"), anyString(), any()))
            .thenReturn(new ResourceView("202605","TEAM","ALL",0,0,0.0,3.0,0.5,0.0));
        mvc.perform(get("/api/v1/resource").param("period","202605"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.utilization").value(0.0));
    }

    @Test void missing_period() throws Exception {
        mvc.perform(get("/api/v1/resource"))
           .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 7: 통과 확인** — `./gradlew test --tests '*ResourceControllerTest'` → PASS.

- [ ] **Step 8: 커밋**
```bash
git add src/main/java/com/meritz/dash/resource src/main/java/com/meritz/dash/mapper/app/ResourceMapper.java src/main/resources/mapper/app/ResourceMapper.xml src/test/java/com/meritz/dash/resource
git commit -m "feat: resource API(가동률 도넛 + 야근 상세, 분모 0 방어)"
```

---

## Task 9: dev-volume API (월별 개발량 추이 + 드릴다운)

**Files:**
- Create: `src/main/java/com/meritz/dash/devvolume/DevVolumePoint.java`, `DevVolumeService.java`, `DevVolumeController.java`
- Create: `src/main/java/com/meritz/dash/mapper/app/DevVolumeMapper.java` + XML
- Test: `src/test/java/com/meritz/dash/devvolume/DevVolumeMapperIT.java`, `DevVolumeControllerTest.java`

**Interfaces:**
- Produces: `DevVolumeMapper.byTeam/byPart/byDev(@Param fromYm,@Param srClsNameJoin...)` — 월별 SR_CLS별 건수. 본 계획은 단일 메서드 `findSeries(@Param unitType,@Param unitId,@Param fromYm) : List<DevVolumePoint>`로 통일.
- Produces: `record DevVolumePoint(String periodYm, String monthLabel, String srCls, String srClsName, int srCnt)`.
- Produces: `GET /api/v1/dev-volume?unit=team|part|dev&period=6m|12m&unitId=` → 월×SR_CLS 건수 시리즈.

- [ ] **Step 1: DevVolumeMapperIT 실패 테스트**

```java
package com.meritz.dash.devvolume;

import com.meritz.dash.aggregation.AggregationService;
import com.meritz.dash.mapper.app.DevVolumeMapper;
import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DevVolumeMapperIT extends LegacyFixture {
    @Autowired AggregationService agg;
    @Autowired DevVolumeMapper mapper;

    @BeforeEach void seed() { agg.run("202605", "MANUAL"); }

    @Test void team_series() {
        List<DevVolumePoint> pts = mapper.findSeries("TEAM", null, "202512");
        assertThat(pts).anyMatch(p -> p.periodYm().equals("202605") && p.srCnt() > 0);
        assertThat(pts).allMatch(p -> p.monthLabel().matches("\\d{2}\\.\\d{2}"));
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*DevVolumeMapperIT'` → FAIL.

- [ ] **Step 3: point record + Mapper + XML**

```java
// DevVolumePoint.java
package com.meritz.dash.devvolume;
public record DevVolumePoint(String periodYm, String monthLabel, String srCls, String srClsName, int srCnt) {}
```
```java
package com.meritz.dash.mapper.app;
import com.meritz.dash.devvolume.DevVolumePoint;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface DevVolumeMapper {
    List<DevVolumePoint> findSeries(@Param("unitType") String unitType,
        @Param("unitId") String unitId, @Param("fromYm") String fromYm);
}
```
`DevVolumeMapper.xml` (월×SR_CLS 건수 합. TEAM=전체, PART=HR PART_CD(미매칭 '미분류'), DEV=EMPNO. SR_CLS명은 CD_COMMON 조인. monthLabel=YY.MM):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.meritz.dash.mapper.app.DevVolumeMapper">
  <select id="findSeries" resultType="com.meritz.dash.devvolume.DevVolumePoint">
    SELECT d.PERIOD_YM AS periodYm,
           SUBSTR(d.PERIOD_YM,3,2) || '.' || SUBSTR(d.PERIOD_YM,5,2) AS monthLabel,
           d.SR_CLS AS srCls, NVL(c.CD_NM, d.SR_CLS) AS srClsName,
           SUM(d.SR_CNT) AS srCnt
      FROM DASH_DEV_AGG d
      LEFT JOIN HR_DEVELOPER h ON d.EMPNO = h.EMPNO
      LEFT JOIN CD_COMMON c ON c.GRP_CD='SR_CLS' AND c.CD_VAL = d.SR_CLS
     WHERE d.PERIOD_YM &gt;= #{fromYm}
       <if test="unitType == 'PART'">AND NVL(h.PART_CD,'미분류') = #{unitId}</if>
       <if test="unitType == 'DEV'">AND d.EMPNO = #{unitId}</if>
     GROUP BY d.PERIOD_YM, d.SR_CLS, c.CD_NM
     ORDER BY d.PERIOD_YM, d.SR_CLS
  </select>
</mapper>
```

- [ ] **Step 4: 매퍼 통과 확인** — `./gradlew test --tests '*DevVolumeMapperIT'` → PASS.

- [ ] **Step 5: Service(기간 계산) + Controller**

```java
package com.meritz.dash.devvolume;

import com.meritz.dash.mapper.app.DevVolumeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class DevVolumeService {
    private final DevVolumeMapper mapper;
    public DevVolumeService(DevVolumeMapper mapper) { this.mapper = mapper; }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<DevVolumePoint> series(String unit, String period, String unitId) {
        String unitType = switch (unit == null ? "team" : unit.toLowerCase()) {
            case "part" -> "PART"; case "dev" -> "DEV"; case "team" -> "TEAM";
            default -> throw new IllegalArgumentException("unit은 team|part|dev");
        };
        int months = switch (period == null ? "6m" : period) {
            case "6m" -> 6; case "12m" -> 12;
            default -> throw new IllegalArgumentException("period는 6m|12m");
        };
        if (!unitType.equals("TEAM") && (unitId == null || unitId.isBlank()))
            throw new IllegalArgumentException("part/dev 조회에는 unitId가 필요합니다.");
        String fromYm = LocalDate.now().minusMonths(months - 1L).format(DateTimeFormatter.ofPattern("yyyyMM"));
        return mapper.findSeries(unitType, unitId, fromYm);
    }
}
```
```java
package com.meritz.dash.devvolume;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dev-volume")
public class DevVolumeController {
    private final DevVolumeService service;
    public DevVolumeController(DevVolumeService service) { this.service = service; }

    @Operation(summary = "월별 개발량(SR 건수) 추이 + 드릴다운")
    @GetMapping
    public ApiResponse<List<DevVolumePoint>> series(
            @RequestParam(defaultValue = "team") String unit,
            @RequestParam(defaultValue = "6m") String period,
            @RequestParam(required = false) String unitId) {
        List<DevVolumePoint> pts = service.series(unit, period, unitId);
        return ApiResponse.of(pts, Map.of("unit", unit, "period", period, "count", pts.size()));
    }
}
```

- [ ] **Step 6: DevVolumeControllerTest(@WebMvcTest) — 200 + 400(잘못된 unit/period)**

```java
package com.meritz.dash.devvolume;

import com.meritz.dash.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DevVolumeController.class)
@Import(GlobalExceptionHandler.class)
class DevVolumeControllerTest {
    @Autowired MockMvc mvc;
    @MockBean DevVolumeService service;

    @Test void ok() throws Exception {
        when(service.series(eq("team"), eq("6m"), any()))
            .thenReturn(List.of(new DevVolumePoint("202605","26.05","01","개발요청",3)));
        mvc.perform(get("/api/v1/dev-volume").param("unit","team").param("period","6m"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].monthLabel").value("26.05"))
           .andExpect(jsonPath("$.data[0].srClsName").value("개발요청"));
    }

    @Test void bad_unit() throws Exception {
        when(service.series(eq("bad"), any(), any()))
            .thenThrow(new IllegalArgumentException("unit은 team|part|dev"));
        mvc.perform(get("/api/v1/dev-volume").param("unit","bad"))
           .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 7: 통과 확인** — `./gradlew test --tests '*DevVolumeControllerTest'` → PASS.

- [ ] **Step 8: 커밋**
```bash
git add src/main/java/com/meritz/dash/devvolume src/main/java/com/meritz/dash/mapper/app/DevVolumeMapper.java src/main/resources/mapper/app/DevVolumeMapper.xml src/test/java/com/meritz/dash/devvolume
git commit -m "feat: dev-volume API(월별 SR 건수 추이 + 팀/파트/개발자 드릴다운)"
```

---

## Task 10: 통합 점검 + 라이브 스모크 + 다각화 리뷰

**Files:** (코드 변경 없음 — 검증)

- [ ] **Step 1: 전체 빌드**

Run(env export 후): `./gradlew build`
Expected: BUILD SUCCESSFUL(전 테스트 PASS, 회귀 없음).

- [ ] **Step 2: 라이브 스모크(앱 DB + 서버)**

`docker start dash-oracle-app` → `JAVA_HOME=... APP_DB_PASSWORD=apppw LEGACY_DB_PASSWORD=legacypw ./gradlew bootRun`.
주의: **기간계 매퍼 실호출은 기간계(또는 모사 데이터)가 필요**하다. 스모크에서는 (a) `/dev-volume`,`/resource`,`/sr-projects`,`/aggregations`(이력)는 DB2만 있으면 200, (b) `POST /aggregations` 실집계는 기간계 접속(회사망/VPN) 또는 dash-oracle-app에 091/093/097 모사 데이터가 필요 → 모사 데이터로 검증하거나 회사 기간계로 1회 실행.
```bash
curl -s 'http://localhost:8080/api/v1/aggregations'                       # 이력(빈 배열 가능)
curl -s 'http://localhost:8080/api/v1/dev-volume?unit=team&period=6m'
curl -s 'http://localhost:8080/api/v1/resource?period=202605'
curl -s 'http://localhost:8080/api/v1/sr-projects?period=202605&minMm=0.6'
```
Swagger `http://localhost:8080/swagger-ui` 전 엔드포인트 노출 확인.

- [ ] **Step 3: 다각화 리뷰**

`/review-all` 실행. 특히 기간계 매퍼 SELECT-only, `${}` 부재, 분모 0 방어, 트랜잭션 매니저(app/legacy) 지정, DTO 경계, 멱등성 확인. Critical 0 확인.

- [ ] **Step 4: DoD 확인 + 마무리**

CLAUDE.md 9장 DoD 체크 후 finishing-a-development-branch로 정리.

---

## Self-Review (작성자 점검)

- **스펙 커버리지**: §2 코드정리=Task1, §3 DASH테이블=Task2, §7 설정=Task3, §4 기간계매퍼+가드+타임아웃=Task4, §5 배치=Task5, §6 API(aggregations=Task6, sr-projects=Task7, resource=Task8, dev-volume=Task9), §9 테스트=각 태스크+Task10. 누락 없음.
- **플레이스홀더**: 모든 스텝에 실제 코드/SQL/명령 포함. (Task5 `insertRunStart` keyProperty 회수는 `BatchRunStart` 객체로 명시.)
- **타입 일관성**: `LegacyDevRow`/`LegacySrProjectRow`(Task4) → `AggregationService`(Task5) 사용 일치. `DevAgg/ResourceSnapshot/SrProject`(Task5) ↔ DASH 컬럼 일치. `ResourceRow`/`ResourceView`(Task8), `DevVolumePoint`(Task9), `SrProjectView`(Task7) 시그니처 일관. `appTxManager`/`legacyTxManager` 일관.
- **남은 가정(구현 시 확인)**: 기간계 실제 컬럼/SR_TPCD 자릿수/상태코드(Task4 Step1 `/ora-db`), HR PART_CD↔팀 매핑(팀=ALL 단일 가정), `BatchRunStart` keyProperty 회수 시 `DashWriteMapper.insertRunStart(BatchRunStart)` 시그니처로 작성.
