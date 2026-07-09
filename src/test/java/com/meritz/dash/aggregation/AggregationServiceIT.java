package com.meritz.dash.aggregation;

import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AggregationServiceIT extends LegacyFixture {

    @Autowired AggregationService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDashTables() {
        jdbc.execute("DELETE FROM BATCH_RUN_LOG WHERE PERIOD_YM='202605'");
        jdbc.execute("DELETE FROM DASH_DEV_AGG WHERE PERIOD_YM='202605'");
        jdbc.execute("DELETE FROM DASH_RESOURCE WHERE PERIOD_YM='202605'");
        jdbc.execute("DELETE FROM DASH_SR_PROJECT WHERE PERIOD_YM='202605'");
    }

    @AfterEach
    void cleanDashTablesAfter() {
        jdbc.execute("DELETE FROM BATCH_RUN_LOG WHERE PERIOD_YM='202605'");
        jdbc.execute("DELETE FROM DASH_DEV_AGG WHERE PERIOD_YM='202605'");
        jdbc.execute("DELETE FROM DASH_RESOURCE WHERE PERIOD_YM='202605'");
        jdbc.execute("DELETE FROM DASH_SR_PROJECT WHERE PERIOD_YM='202605'");
    }

    @Test
    @DisplayName("run(202605): DASH 적재 + 멱등(2회=동일) + 야근 계산")
    void run_and_idempotent() throws Exception {
        service.run("202605", "MANUAL");
        Integer dev1 = jdbc.queryForObject("SELECT COUNT(*) FROM DASH_DEV_AGG WHERE PERIOD_YM='202605'", Integer.class);
        // E0003: 208h/166=1.25MM → 야근 0.25 (PART/ALL OVERTIME_MM 반영)
        Double teamOt = jdbc.queryForObject(
            "SELECT OVERTIME_MM FROM DASH_RESOURCE WHERE PERIOD_YM='202605' AND UNIT_TYPE='ALL' AND UNIT_ID='ALL'", Double.class);
        assertThat(teamOt).isGreaterThan(0.0);

        service.run("202605", "MANUAL"); // 재실행
        Integer dev2 = jdbc.queryForObject("SELECT COUNT(*) FROM DASH_DEV_AGG WHERE PERIOD_YM='202605'", Integer.class);
        assertThat(dev2).isEqualTo(dev1); // 멱등

        Integer runs = jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_RUN_LOG WHERE PERIOD_YM='202605' AND STATUS='OK'", Integer.class);
        assertThat(runs).isEqualTo(2);
    }

    @Test
    @DisplayName("run(202605): ALL/DEPT/PART 3계층 행 생성 + ALL=Σ DEPT USED_MM")
    void run_three_tier_rows_and_consistency() throws Exception {
        service.run("202605", "MANUAL");

        // 1) UNIT_TYPE 별 행 존재 확인
        Integer allCount  = jdbc.queryForObject(
            "SELECT COUNT(*) FROM DASH_RESOURCE WHERE PERIOD_YM='202605' AND UNIT_TYPE='ALL'", Integer.class);
        Integer deptCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM DASH_RESOURCE WHERE PERIOD_YM='202605' AND UNIT_TYPE='DEPT'", Integer.class);
        Integer partCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM DASH_RESOURCE WHERE PERIOD_YM='202605' AND UNIT_TYPE='PART'", Integer.class);
        assertThat(allCount).isEqualTo(1);
        assertThat(deptCount).isGreaterThanOrEqualTo(1);  // D101 최소 1개
        assertThat(partCount).isGreaterThanOrEqualTo(2);  // D101-P01, D101-P02 최소 2개

        // 2) DEPT 'D101' 행: USED_MM > 0
        Double deptUsed = jdbc.queryForObject(
            "SELECT USED_MM FROM DASH_RESOURCE WHERE PERIOD_YM='202605' AND UNIT_TYPE='DEPT' AND UNIT_ID='D101'", Double.class);
        assertThat(deptUsed).isNotNull().isGreaterThan(0.0);

        // 3) PART 'D101-P01' 행 존재
        Integer p01Count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM DASH_RESOURCE WHERE PERIOD_YM='202605' AND UNIT_TYPE='PART' AND UNIT_ID='D101-P01'", Integer.class);
        assertThat(p01Count).isEqualTo(1);

        // 4) ALL USED_MM = Σ DEPT USED_MM 정합 (round2 오차 허용)
        Double allUsed = jdbc.queryForObject(
            "SELECT USED_MM FROM DASH_RESOURCE WHERE PERIOD_YM='202605' AND UNIT_TYPE='ALL' AND UNIT_ID='ALL'", Double.class);
        Double sumDeptUsed = jdbc.queryForObject(
            "SELECT SUM(USED_MM) FROM DASH_RESOURCE WHERE PERIOD_YM='202605' AND UNIT_TYPE='DEPT'", Double.class);
        assertThat(allUsed).isNotNull();
        assertThat(sumDeptUsed).isNotNull();
        assertThat(allUsed).isEqualByComparingTo(sumDeptUsed);
    }
}
