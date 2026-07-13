package com.meritz.dash.weekly;

import com.meritz.dash.mapper.app.WeeklyReportMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WeeklyReportMapper 통합 테스트(app DB — DASH_WKLY_RPT/DASH_WKLY_SUM, V021 마이그레이션).
 * <p>
 * <b>주의(Red 단계)</b>: {@code mapper/app/WeeklyReportMapper.xml} 은 아직 namespace 만 있고
 * 실제 SQL statement 가 없다. 이 테스트는 <b>컴파일 확인</b>까지가 목표이며, Green 단계에서
 * XML 에 statement 를 채운 뒤 실제로 실행/통과시킨다.
 */
class WeeklyReportMapperIT extends AbstractOracleIT {

    @Autowired WeeklyReportMapper mapper;
    @Autowired @Qualifier("appDataSource") DataSource appDataSource;

    /** 멱등 시드(PartSrMapperIT 관례): 재사용 컨테이너의 직전 실행 잔여행을 먼저 정리해 UK 충돌을 방지. */
    @BeforeEach
    void cleanupFixtures() {
        JdbcTemplate jdbc = new JdbcTemplate(appDataSource);
        jdbc.update("DELETE FROM DASH_WKLY_RPT WHERE WEEK_YMD = '20260706' AND REG_EMPNO = '7451'"
                + " AND SR_NO IN ('SR26000101','SR26000102','SR26000103')");
        jdbc.update("DELETE FROM DASH_WKLY_SUM WHERE WEEK_YMD = '20260706'"
                + " AND DEPT_CD = '2139' AND PART_CD = 'P01'");
    }

    @Test
    @DisplayName("report insert 후 주차(week) 필터로 select 하면 삽입한 건만 조회된다")
    void insert_and_select_by_week_filter() {
        WeeklyReportInsert insert = new WeeklyReportInsert(
                "20260706", "SR26000101", "증권 잔고 정정", "7451", "2139", "P01",
                "정정 완료", "20260710", "20260710", null, "7451");

        mapper.insertReport(insert);
        assertThat(insert.getRptId()).isNotNull();

        List<WeeklyReportRow> rows = mapper.selectReportsByWeek("20260706", "2139", "P01", "7451");

        assertThat(rows).extracting(WeeklyReportRow::srNo).contains("SR26000101");
    }

    @Test
    @DisplayName("selectReportById: 방금 등록한 건을 단건 조회")
    void select_by_id_after_insert() {
        WeeklyReportInsert insert = new WeeklyReportInsert(
                "20260706", "SR26000102", "제목", "7451", "2139", "P01",
                "내용", "20260710", "20260710", null, "7451");
        mapper.insertReport(insert);

        WeeklyReportRow row = mapper.selectReportById(insert.getRptId());

        assertThat(row).isNotNull();
        assertThat(row.srNo()).isEqualTo("SR26000102");
    }

    @Test
    @DisplayName("UK_DASH_WKLY_RPT_WK_SR_EMP: (week,srNo,regEmpno) 중복 INSERT → DuplicateKeyException")
    void unique_constraint_violation_on_duplicate() {
        WeeklyReportInsert first = new WeeklyReportInsert(
                "20260706", "SR26000103", "제목", "7451", "2139", "P01",
                "내용", "20260710", "20260710", null, "7451");
        mapper.insertReport(first);

        WeeklyReportInsert duplicate = new WeeklyReportInsert(
                "20260706", "SR26000103", "제목", "7451", "2139", "P01",
                "다른 내용", "20260711", "20260710", null, "7451");

        assertThatThrownBy(() -> mapper.insertReport(duplicate))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("summary 다건 INSERT: 같은 (week,dept,part) 2회 INSERT → 2행, SUM_ID 각각 회수·SUM_ID DESC 정렬")
    void summary_multiple_inserts_for_same_week_part() {
        WeeklySummaryInsert first = new WeeklySummaryInsert("20260706", "2139", "P01", "1차 취합", "5355", "5355");
        WeeklySummaryInsert second = new WeeklySummaryInsert("20260706", "2139", "P01", "2차 취합", "5355", "5355");

        mapper.insertSummary(first);
        mapper.insertSummary(second);

        assertThat(first.getSumId()).isNotNull();
        assertThat(second.getSumId()).isNotNull().isNotEqualTo(first.getSumId());

        List<WeeklySummaryRow> rows = mapper.selectSummariesByWeek("20260706", "2139", "P01");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).sumId()).isGreaterThan(rows.get(1).sumId());   // 최신(SUM_ID DESC) 우선
        assertThat(rows.get(0).sumCntt()).isEqualTo("2차 취합");
    }

    @Test
    @DisplayName("summary update/delete: 본문(SUM_CNTT)만 수정(TEAM_CMT 보존) 후 삭제")
    void summary_update_then_delete() {
        WeeklySummaryInsert insert = new WeeklySummaryInsert("20260706", "2139", "P01", "본문", "6002", "6002");
        mapper.insertSummary(insert);
        mapper.updateFinalComment(insert.getSumId(), "팀장의견", "5355");

        mapper.updateSummary(insert.getSumId(), "수정 본문", "6002");

        WeeklySummaryRow row = mapper.selectSummaryById(insert.getSumId());
        assertThat(row.sumCntt()).isEqualTo("수정 본문");
        assertThat(row.teamCmt()).isEqualTo("팀장의견");        // TEAM_CMT 는 건드리지 않음

        mapper.deleteSummary(insert.getSumId());
        assertThat(mapper.selectSummaryById(insert.getSumId())).isNull();
    }

    // ── 취합본-개인보고 링크(DASH_WKLY_SUM_RPT, V023) ─────────────────

    private Long insertReport(String srNo) {
        WeeklyReportInsert insert = new WeeklyReportInsert(
                "20260706", srNo, "제목 " + srNo, "7451", "2139", "P01",
                "내용", "20260710", "20260710", null, "7451");
        mapper.insertReport(insert);
        return insert.getRptId();
    }

    private Long insertSummary(String cntt) {
        WeeklySummaryInsert insert = new WeeklySummaryInsert("20260706", "2139", "P01", cntt, "6002", "6002");
        mapper.insertSummary(insert);
        return insert.getSumId();
    }

    private long countLinks(Long sumId) {
        return new JdbcTemplate(appDataSource)
                .queryForObject("SELECT COUNT(*) FROM DASH_WKLY_SUM_RPT WHERE SUM_ID = ?", Long.class, sumId);
    }

    @Test
    @DisplayName("링크 insert 후 selectSummaryReports 임베드 조회: 조인 컬럼 매핑 + RPT_ID 오름차순")
    void link_insert_then_embedded_select() {
        Long rpt1 = insertReport("SR26000101");
        Long rpt2 = insertReport("SR26000102");
        Long sumId = insertSummary("취합본");

        mapper.insertSummaryReports(sumId, List.of(rpt2, rpt1));   // 입력 순서와 무관하게 RPT_ID ASC

        List<WeeklySummaryReportRow> rows = mapper.selectSummaryReports(List.of(sumId));
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(WeeklySummaryReportRow::rptId).containsExactly(rpt1, rpt2);
        assertThat(rows.get(0).sumId()).isEqualTo(sumId);
        assertThat(rows.get(0).srNo()).isEqualTo("SR26000101");
        assertThat(rows.get(0).srTitl()).isEqualTo("제목 SR26000101");
        assertThat(rows.get(0).regEmpno()).isEqualTo("7451");
        assertThat(rows.get(0).rptCntt()).isEqualTo("내용");
        assertThat(rows.get(0).planDate()).isEqualTo("20260710");
    }

    @Test
    @DisplayName("selectReportsForValidation: rptId 목록으로 (rptId, weekYmd, deptCd, partCd) 조회")
    void select_reports_for_validation() {
        Long rpt1 = insertReport("SR26000101");

        List<WeeklyReportKey> keys = mapper.selectReportsForValidation(List.of(rpt1, 999999999L));

        assertThat(keys).hasSize(1);                               // 미존재 id 는 결과에 없음
        assertThat(keys.get(0).rptId()).isEqualTo(rpt1);
        assertThat(keys.get(0).weekYmd()).isEqualTo("20260706");
        assertThat(keys.get(0).deptCd()).isEqualTo("2139");
        assertThat(keys.get(0).partCd()).isEqualTo("P01");
    }

    @Test
    @DisplayName("summary 삭제 → FK ON DELETE CASCADE 로 링크 자동 정리")
    void summary_delete_cascades_links() {
        Long rpt1 = insertReport("SR26000101");
        Long sumId = insertSummary("취합본");
        mapper.insertSummaryReports(sumId, List.of(rpt1));
        assertThat(countLinks(sumId)).isEqualTo(1);

        mapper.deleteSummary(sumId);

        assertThat(countLinks(sumId)).isZero();
    }

    @Test
    @DisplayName("개인 보고 삭제 → FK ON DELETE CASCADE 로 해당 링크만 자동 삭제(코드 불필요)")
    void report_delete_cascades_links() {
        Long rpt1 = insertReport("SR26000101");
        Long rpt2 = insertReport("SR26000102");
        Long sumId = insertSummary("취합본");
        mapper.insertSummaryReports(sumId, List.of(rpt1, rpt2));

        mapper.deleteReport(rpt1);

        List<WeeklySummaryReportRow> rows = mapper.selectSummaryReports(List.of(sumId));
        assertThat(rows).extracting(WeeklySummaryReportRow::rptId).containsExactly(rpt2);
    }

    @Test
    @DisplayName("교체 시나리오: deleteSummaryReports 후 재-insert 하면 새 선택만 남는다")
    void replace_links_scenario() {
        Long rpt1 = insertReport("SR26000101");
        Long rpt2 = insertReport("SR26000102");
        Long sumId = insertSummary("취합본");
        mapper.insertSummaryReports(sumId, List.of(rpt1));

        mapper.deleteSummaryReports(sumId);
        mapper.insertSummaryReports(sumId, List.of(rpt2));

        List<WeeklySummaryReportRow> rows = mapper.selectSummaryReports(List.of(sumId));
        assertThat(rows).extracting(WeeklySummaryReportRow::rptId).containsExactly(rpt2);
    }
}
