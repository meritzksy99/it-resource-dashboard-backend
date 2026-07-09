package com.meritz.dash.dml;

import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DmlSyncService.sync 통합 테스트 — 기간계(legacy fixture) → HR 매칭 → DASH_DML_SR MERGE.
 * <p>
 * AbstractOracleIT 는 두 DataSource 를 같은 컨테이너로 가리키므로
 * 기간계 모사 테이블과 app 테이블(Flyway)이 한 스키마에 공존한다.
 * 싱글톤(재사용) 컨테이너 대비: 091 누락 컬럼 멱등 ALTER + 참조 테이블 멱등 CREATE
 * ({@code DmlSrLegacyMapperIT} 와 동일 패턴), 이 테스트 전용 행만 DELETE 후 INSERT 로 멱등 시딩.
 * <p>
 * 시나리오:
 *   DMLSYNC001 담당 E9001(HR 有, P01) / DMLSYNC002 담당 E9002(HR 有, P02)
 *   / DMLSYNC003 담당 ZZZZ(HR 無 → 제외).
 */
class DmlSyncIT extends LegacyFixture {

    @Autowired DmlSyncService service;

    @Autowired
    @Qualifier("appDataSource")
    DataSource appDs;

    @Autowired
    @Qualifier("legacyDataSource")
    DataSource legacyDs;

    /** 이번달(yyyyMM) — REG_DATE 접두어와 sync 인자로 함께 사용 */
    private final String baseYm = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));

    private JdbcTemplate appJdbc() {
        return new JdbcTemplate(appDs);
    }

    @BeforeEach
    void seed() throws Exception {
        // (a) 재사용 컨테이너 대비 — legacy fixture 스키마 멱등 보강 + (c) legacy 시딩
        try (Connection c = legacyDs.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);

            addColumnIfMissing(s, "RQSR_EMPNO VARCHAR2(9)");
            addColumnIfMissing(s, "TRTH_RQST_EMPNO VARCHAR2(9)");
            addColumnIfMissing(s, "TRTH_RQST_DPCD VARCHAR2(4)");
            addColumnIfMissing(s, "CUST_INFO_YN VARCHAR2(1)");
            addColumnIfMissing(s, "PROS_CMPT_DATE VARCHAR2(8)");

            createTableIfMissing(s, "CREATE TABLE TBCPPU001I00 (EMPNO VARCHAR2(9), FLNM VARCHAR2(40), BLNG_DPCD VARCHAR2(4))");
            createTableIfMissing(s, "CREATE TABLE TBCPPD001M00 (DPCD VARCHAR2(4), DPNM VARCHAR2(40))");
            createTableIfMissing(s, "CREATE TABLE TBCPPE091D02 (SR_NO VARCHAR2(11), SR_BSWR_DETL_DVCD VARCHAR2(6))");
            createTableIfMissing(s, "CREATE TABLE TBCPPE108C01 (SR_BSWR_DETL_DVCD VARCHAR2(6), SR_BSWR_DETL_DIV_NAME VARCHAR2(100))");

            deleteLegacyTestRows(s);

            // 097 SR 유형 코드 — 18/19 는 공유 seed 에 없으므로 이 테스트가 소유
            s.executeUpdate("INSERT INTO TBCPPE097L00 (SR_TPCD, SR_DETL_TPCD, SR_TPCD_NAME, USE_YN) VALUES ('18','0000','데이타변경','Y')");
            s.executeUpdate("INSERT INTO TBCPPE097L00 (SR_TPCD, SR_DETL_TPCD, SR_TPCD_NAME, USE_YN) VALUES ('19','0000','원장변경','Y')");

            // 인사/부서 참조 행 — 이름/부서명 스칼라 서브쿼리가 non-null 로 풀리도록
            s.executeUpdate("INSERT INTO TBCPPU001I00 (EMPNO, FLNM, BLNG_DPCD) VALUES ('E9001','홍길동','2139')");
            s.executeUpdate("INSERT INTO TBCPPU001I00 (EMPNO, FLNM, BLNG_DPCD) VALUES ('E9002','김철수','2139')");
            s.executeUpdate("INSERT INTO TBCPPD001M00 (DPCD, DPNM) VALUES ('2139','IT개발팀')");

            // DMLSYNC001/002: 담당자가 HR_DEVELOPER 재직자 → 매칭 대상
            insert091(s, "DMLSYNC001", 9201, "E9001");
            insert091(s, "DMLSYNC002", 9202, "E9002");
            // DMLSYNC003: 담당자 ZZZZ 는 HR 에 없음 → 조회는 되지만 매칭 제외
            insert091(s, "DMLSYNC003", 9203, "ZZZZ");

            // 093 승인 작업이력 — INNER JOIN 이 풀리도록 SR 별 1행
            insert093(s, "DMLSYNC001", 9201);
            insert093(s, "DMLSYNC002", 9202);
            insert093(s, "DMLSYNC003", 9203);
            c.commit();
        }

        // (b) HR_DEVELOPER(app) 재직 개발자 시딩 — 멱등 위해 DELETE 후 INSERT
        JdbcTemplate j = appJdbc();
        deleteAppTestRows(j);
        j.update("INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, ROLE_CD, DEV_YN, STATUS_CD) " +
                 "VALUES ('E9001', '홍길동', '2139', 'P01', '03', 'Y', '01')");
        j.update("INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, ROLE_CD, DEV_YN, STATUS_CD) " +
                 "VALUES ('E9002', '김철수', '2139', 'P02', '03', 'Y', '01')");
    }

    @AfterEach
    void cleanup() throws Exception {
        // 싱글톤 컨테이너 오염 방지 — 이 테스트가 넣은 행만 제거
        try (Connection c = legacyDs.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            deleteLegacyTestRows(s);
            c.commit();
        }
        deleteAppTestRows(appJdbc());
    }

    private void deleteLegacyTestRows(Statement s) throws SQLException {
        s.executeUpdate("DELETE FROM TBCPPE093L00 WHERE SR_NO IN ('DMLSYNC001','DMLSYNC002','DMLSYNC003')");
        s.executeUpdate("DELETE FROM TBCPPE091M00 WHERE SR_NO IN ('DMLSYNC001','DMLSYNC002','DMLSYNC003')");
        s.executeUpdate("DELETE FROM TBCPPE097L00 WHERE SR_TPCD IN ('18','19')");
        s.executeUpdate("DELETE FROM TBCPPU001I00 WHERE EMPNO IN ('E9001','E9002')");
        s.executeUpdate("DELETE FROM TBCPPD001M00 WHERE DPCD = '2139'");
    }

    private void deleteAppTestRows(JdbcTemplate j) {
        // FK: DASH_DML_CHECK → DASH_DML_SR 순서로 삭제
        j.update("DELETE FROM DASH_DML_CHECK WHERE SR_NO IN ('DMLSYNC001','DMLSYNC002','DMLSYNC003')");
        j.update("DELETE FROM DASH_DML_SR WHERE SR_NO IN ('DMLSYNC001','DMLSYNC002','DMLSYNC003')");
        j.update("DELETE FROM HR_DEVELOPER WHERE EMPNO IN ('E9001','E9002')");
    }

    private void insert091(Statement s, String srNo, int srno, String picEmpno) throws SQLException {
        s.executeUpdate(("""
            INSERT INTO TBCPPE091M00
              (SR_NO, SRNO, SR_TPCD, SR_DETL_TPCD, SR_REG_STAT_CODE, DPCD, PRCH_DPCD,
               SPIC_EMPNO, PRCH_EMPNO, RQSR_EMPNO, TRTH_RQST_EMPNO, TRTH_RQST_DPCD, CUST_INFO_YN,
               TITL_CNTT, MSG_CNTT, JOB_MANM, REG_DATE, RFLC_SCDL_DATE, PROS_CMPT_DATE, MNPL_EMPNO)
            VALUES ('%s', %d, '18', '0000', '04', '2139', '2139',
                    '%s', '%s', 'E9001', 'E9001', '2139', 'N',
                    '제목-%s', '내용-%s', NULL, '%s10', NULL, NULL, '%s')""")
                .formatted(srNo, srno, picEmpno, picEmpno, srNo, srNo, baseYm, picEmpno));
    }

    private void insert093(Statement s, String srNo, int srno) throws SQLException {
        s.executeUpdate(("""
            INSERT INTO TBCPPE093L00
              (SR_NO, SRNO, SPIC_EMPNO, JOB_EXEC_HOUR, JOB_MANM, FIN_DATE, APRV_YN, MNPL_EMPNO)
            VALUES ('%s', %d, 'E9001', '8', '0.1', '%s28', 'Y', 'E9001')""")
                .formatted(srNo, srno, baseYm));
    }

    private void addColumnIfMissing(Statement s, String colDef) {
        try {
            s.executeUpdate("ALTER TABLE TBCPPE091M00 ADD (" + colDef + ")");
        } catch (SQLException alreadyExists) {
            // ORA-01430: column being added already exists — 재사용 컨테이너에서는 정상
        }
    }

    private void createTableIfMissing(Statement s, String createSql) {
        try {
            s.executeUpdate(createSql);
        } catch (SQLException alreadyExists) {
            // ORA-00955: name is already used by an existing object — 재사용 컨테이너에서는 정상
        }
    }

    @Test
    @DisplayName("HR_DEVELOPER 재직자 담당 건만 매칭·저장 — HR 에 없는 담당자(ZZZZ)는 제외")
    void sync_matches_only_hr_developers() {
        DmlSyncService.SyncResult r = service.sync(baseYm, "TEST");

        assertThat(r.baseYm()).isEqualTo(baseYm);
        assertThat(r.fetched()).isEqualTo(3);
        assertThat(r.matched()).isEqualTo(2);

        List<Map<String, Object>> rows = appJdbc().queryForList(
                "SELECT SR_NO, DEV_DEPT_CD, DEV_PART_CD FROM DASH_DML_SR " +
                "WHERE SR_NO IN ('DMLSYNC001','DMLSYNC002','DMLSYNC003') ORDER BY SR_NO");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("SR_NO")).isEqualTo("DMLSYNC001");
        assertThat(rows.get(0).get("DEV_DEPT_CD")).isEqualTo("2139");
        assertThat(rows.get(0).get("DEV_PART_CD")).isEqualTo("P01");
        assertThat(rows.get(1).get("SR_NO")).isEqualTo("DMLSYNC002");
        assertThat(rows.get(1).get("DEV_PART_CD")).isEqualTo("P02");
        // ZZZZ 담당 건은 저장되지 않아야 한다
        assertThat(rows).extracting(m -> m.get("SR_NO")).doesNotContain("DMLSYNC003");
    }

    @Test
    @DisplayName("재동기화는 스냅샷만 갱신하고 사용자 점검 입력(DASH_DML_CHECK)은 보존 — 멱등")
    void resync_preserves_user_check() {
        JdbcTemplate j = appJdbc();

        service.sync(baseYm, "TEST");
        Timestamp firstSyncedAt = j.queryForObject(
                "SELECT SYNCED_AT FROM DASH_DML_SR WHERE SR_NO = 'DMLSYNC001'", Timestamp.class);
        assertThat(firstSyncedAt).isNotNull();

        // 사용자가 점검 완료 표시
        j.update("INSERT INTO DASH_DML_CHECK (SR_NO, CHECK_YN, IMPROVE_YN, CMPT_YN) " +
                 "VALUES ('DMLSYNC001', 'Y', 'N', 'N')");

        // 재동기화 (배치 재실행 시나리오)
        DmlSyncService.SyncResult r2 = service.sync(baseYm, "TEST");
        assertThat(r2.matched()).isEqualTo(2);

        // 스냅샷은 여전히 존재하고 SYNCED_AT 이 갱신됨 (MERGE UPDATE 경로)
        Integer srCount = j.queryForObject(
                "SELECT COUNT(*) FROM DASH_DML_SR WHERE SR_NO = 'DMLSYNC001'", Integer.class);
        assertThat(srCount).isEqualTo(1);
        Timestamp secondSyncedAt = j.queryForObject(
                "SELECT SYNCED_AT FROM DASH_DML_SR WHERE SR_NO = 'DMLSYNC001'", Timestamp.class);
        assertThat(secondSyncedAt).isNotNull();
        assertThat(secondSyncedAt.before(firstSyncedAt)).isFalse();

        // 점검 입력은 배치가 건드리지 않아 그대로 보존
        String checkYn = j.queryForObject(
                "SELECT CHECK_YN FROM DASH_DML_CHECK WHERE SR_NO = 'DMLSYNC001'", String.class);
        assertThat(checkYn).isEqualTo("Y");
    }
}
