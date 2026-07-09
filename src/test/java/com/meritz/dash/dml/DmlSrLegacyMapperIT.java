package com.meritz.dash.dml;

import com.meritz.dash.mapper.legacy.DmlSrLegacyMapper;
import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DmlSrLegacyMapper.selectDmlSrs 기간계 통합 테스트(legacy-fixture).
 * DML성 SR = SR_TPCD IN ('18' 데이타변경, '19' 원장변경) + 기준월(REG_DATE 앞 6자리) 필터.
 * <p>
 * 싱글톤(재사용) 컨테이너 대비: 091 누락 컬럼은 멱등 ALTER 로,
 * DML 쿼리 전용 참조 테이블(인사/부서/업무상세분류)은 멱등 CREATE 로 보강하고,
 * 이 테스트 전용 행(SR 'DML0000001'~'DML0000003', 사번 'E9001', 097 유형 18/19)만
 * 매번 DELETE 후 INSERT 한다(공유 seed.sql 은 건드리지 않음).
 * 공유 seed 의 다른 SR 이 섞일 수 있으므로 contains/doesNotContain 로 검증한다.
 */
class DmlSrLegacyMapperIT extends LegacyFixture {

    @Autowired DmlSrLegacyMapper mapper;

    @Autowired
    @Qualifier("legacyDataSource")
    DataSource ds;

    /** 이번달(yyyyMM) — REG_DATE 접두어와 selectDmlSrs 인자로 함께 사용 */
    private final String baseYm = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));

    @BeforeEach
    void setupDmlRows() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);

            // 재사용 컨테이너 대비 — DML 쿼리가 쓰는 091 컬럼 멱등 보강 (컬럼당 ALTER 1회)
            addColumnIfMissing(s, "RQSR_EMPNO VARCHAR2(9)");
            addColumnIfMissing(s, "TRTH_RQST_EMPNO VARCHAR2(9)");
            addColumnIfMissing(s, "TRTH_RQST_DPCD VARCHAR2(4)");
            addColumnIfMissing(s, "CUST_INFO_YN VARCHAR2(1)");
            addColumnIfMissing(s, "PROS_CMPT_DATE VARCHAR2(8)");

            // 재사용 컨테이너 대비 — DML 쿼리 전용 참조 테이블 멱등 생성
            createTableIfMissing(s, "CREATE TABLE TBCPPU001I00 (EMPNO VARCHAR2(9), FLNM VARCHAR2(40), BLNG_DPCD VARCHAR2(4))");
            createTableIfMissing(s, "CREATE TABLE TBCPPD001M00 (DPCD VARCHAR2(4), DPNM VARCHAR2(40))");
            createTableIfMissing(s, "CREATE TABLE TBCPPE091D02 (SR_NO VARCHAR2(11), SR_BSWR_DETL_DVCD VARCHAR2(6))");
            createTableIfMissing(s, "CREATE TABLE TBCPPE108C01 (SR_BSWR_DETL_DVCD VARCHAR2(6), SR_BSWR_DETL_DIV_NAME VARCHAR2(100))");

            deleteTestRows(s);

            // 인사/부서 참조 행 — 이름/부서명 스칼라 서브쿼리가 non-null 로 풀리도록
            s.executeUpdate("INSERT INTO TBCPPU001I00 (EMPNO, FLNM, BLNG_DPCD) VALUES ('E9001','홍길동','D101')");
            s.executeUpdate("INSERT INTO TBCPPD001M00 (DPCD, DPNM) VALUES ('D101','테스트팀')");

            // 097 SR 유형 코드 — 18/19 는 공유 seed 에 없으므로 이 테스트가 소유(DELETE 후 INSERT 로 멱등)
            s.executeUpdate("INSERT INTO TBCPPE097L00 (SR_TPCD, SR_DETL_TPCD, SR_TPCD_NAME, USE_YN) VALUES ('18','0000','데이타변경','Y')");
            s.executeUpdate("INSERT INTO TBCPPE097L00 (SR_TPCD, SR_DETL_TPCD, SR_TPCD_NAME, USE_YN) VALUES ('19','0000','원장변경','Y')");

            // DML0000001: 데이타변경('18'), 이번달 등록 → 조회 대상
            insert091(s, "DML0000001", 9101, "18", "제목A", "내용A");
            // DML0000002: 원장변경('19'), 이번달 등록 → 조회 대상
            insert091(s, "DML0000002", 9102, "19", "제목B", "내용B");
            // DML0000003: 개발요청('01') — DML 아님, 같은 달이어도 제외되어야 함
            insert091(s, "DML0000003", 9103, "01", "제목C", "내용C");

            // 093 승인 작업이력 — INNER JOIN 이 풀리도록 SR 별 1행 이상.
            // DML0000001 은 2행(담당자 상이)으로 fan-out 을 유발 → DISTINCT 중복 제거 검증.
            insert093(s, "DML0000001", 9101, "E9001");
            insert093(s, "DML0000001", 9101, "E9002");
            insert093(s, "DML0000002", 9102, "E9001");
            insert093(s, "DML0000003", 9103, "E9001");
            c.commit();
        }
    }

    @AfterEach
    void cleanupDmlRows() throws Exception {
        // 싱글톤 컨테이너 오염 방지 — 이 테스트가 넣은 행만 제거해 base seed 상태로 복원
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            deleteTestRows(s);
            c.commit();
        }
    }

    private void deleteTestRows(Statement s) throws SQLException {
        s.executeUpdate("DELETE FROM TBCPPE093L00 WHERE SR_NO IN ('DML0000001','DML0000002','DML0000003')");
        s.executeUpdate("DELETE FROM TBCPPE091M00 WHERE SR_NO IN ('DML0000001','DML0000002','DML0000003')");
        s.executeUpdate("DELETE FROM TBCPPE097L00 WHERE SR_TPCD IN ('18','19')");
        s.executeUpdate("DELETE FROM TBCPPU001I00 WHERE EMPNO = 'E9001'");
        s.executeUpdate("DELETE FROM TBCPPD001M00 WHERE DPCD = 'D101'");
    }

    private void insert091(Statement s, String srNo, int srno, String srTpcd, String titl, String msg) throws SQLException {
        s.executeUpdate(("""
            INSERT INTO TBCPPE091M00
              (SR_NO, SRNO, SR_TPCD, SR_DETL_TPCD, SR_REG_STAT_CODE, DPCD, PRCH_DPCD,
               SPIC_EMPNO, PRCH_EMPNO, RQSR_EMPNO, TRTH_RQST_EMPNO, TRTH_RQST_DPCD, CUST_INFO_YN,
               TITL_CNTT, MSG_CNTT, JOB_MANM, REG_DATE, RFLC_SCDL_DATE, PROS_CMPT_DATE, MNPL_EMPNO)
            VALUES ('%s', %d, '%s', '0000', '04', 'D101', 'D101',
                    'E9001', 'E9001', 'E9001', 'E9001', 'D101', 'N',
                    '%s', '%s', NULL, '%s15', NULL, NULL, 'E9001')""")
                .formatted(srNo, srno, srTpcd, titl, msg, baseYm));
    }

    private void insert093(Statement s, String srNo, int srno, String spicEmpno) throws SQLException {
        s.executeUpdate(("""
            INSERT INTO TBCPPE093L00
              (SR_NO, SRNO, SPIC_EMPNO, JOB_EXEC_HOUR, JOB_MANM, FIN_DATE, APRV_YN, MNPL_EMPNO)
            VALUES ('%s', %d, '%s', '8', '0.1', '%s28', 'Y', '%s')""")
                .formatted(srNo, srno, spicEmpno, baseYm, spicEmpno));
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
    @DisplayName("기준월 DML성 SR(18/19)만 조회 — 비DML(01) 제외, 093 fan-out 중복 없음")
    void dmlSrs_onlyType18And19_noDuplicates() {
        List<DmlSrLegacyRow> rows = mapper.selectDmlSrs(baseYm);

        assertThat(rows).extracting(DmlSrLegacyRow::srNo)
                .contains("DML0000001", "DML0000002")
                .doesNotContain("DML0000003"); // SR_TPCD '01' 은 DML 아님

        // DML0000001 은 093 승인이력 2행(fan-out 원인) → DISTINCT 로 SR 당 정확히 1행
        List<DmlSrLegacyRow> ours = rows.stream()
                .filter(r -> r.srNo().startsWith("DML00000"))
                .toList();
        assertThat(ours).hasSize(2);
        assertThat(ours).extracting(DmlSrLegacyRow::srNo).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("DML0000001: 유형/내용/IT담당자(사번·이름·부서명)/고객정보여부 매핑")
    void dmlSr_columnMapping() {
        List<DmlSrLegacyRow> rows = mapper.selectDmlSrs(baseYm);

        DmlSrLegacyRow r = only(rows, "DML0000001");
        assertThat(r.srTpcd()).isEqualTo("18");
        assertThat(r.srTpcdName()).isEqualTo("데이타변경");
        assertThat(r.titlCntt()).isEqualTo("제목A");
        assertThat(r.msgCntt()).isEqualTo("내용A");
        assertThat(r.custInfoYn()).isEqualTo("N");
        assertThat(r.picEmpno()).isEqualTo("E9001");
        assertThat(r.picNm()).isEqualTo("홍길동");   // TBCPPU001I00 조인 확인
        assertThat(r.picDpcd()).isEqualTo("D101");
        assertThat(r.picDpnm()).isEqualTo("테스트팀"); // TBCPPD001M00 조인 확인
        assertThat(r.rqsrEmpno()).isEqualTo("E9001");
        assertThat(r.rqsrNm()).isEqualTo("홍길동");
        assertThat(r.regDate()).isEqualTo(baseYm + "15");

        DmlSrLegacyRow r2 = only(rows, "DML0000002");
        assertThat(r2.srTpcd()).isEqualTo("19");
        assertThat(r2.srTpcdName()).isEqualTo("원장변경");
    }

    @Test
    @DisplayName("SR번호 오름차순 정렬")
    void dmlSrs_orderedBySrNo() {
        List<DmlSrLegacyRow> rows = mapper.selectDmlSrs(baseYm);
        assertThat(rows).extracting(DmlSrLegacyRow::srNo).isSorted();
    }

    private static DmlSrLegacyRow only(List<DmlSrLegacyRow> rows, String srNo) {
        return rows.stream().filter(r -> r.srNo().equals(srNo)).findFirst().orElseThrow();
    }
}
