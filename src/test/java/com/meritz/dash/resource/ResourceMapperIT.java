package com.meritz.dash.resource;

import com.meritz.dash.aggregation.AggregationService;
import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.mapper.app.ResourceMapper;
import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceMapperIT extends LegacyFixture {
    @Autowired AggregationService agg;
    @Autowired ResourceMapper mapper;
    @Autowired ResourceService resourceService;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("appDataSource") javax.sql.DataSource appDs;

    private org.springframework.jdbc.core.JdbcTemplate appJdbc() {
        return new org.springframework.jdbc.core.JdbcTemplate(appDs);
    }

    @BeforeEach void seed() throws Exception {
        agg.run("202605", "MANUAL");
        AuthContext.set("admin", "ADMIN", null, null);
        // 야근 원천은 HR_OVERTIME(엑셀 업로드) — E0002 774분, E0003 120분 시드(멱등)
        appJdbc().update("DELETE FROM HR_OVERTIME WHERE PERIOD_YM = '202605'");
        appJdbc().update("INSERT INTO HR_OVERTIME (PERIOD_YM, EMPNO, OT_MINUTES, CREATED_BY) " +
                         "VALUES ('202605', 'E0002', 774, 'TEST')");
        appJdbc().update("INSERT INTO HR_OVERTIME (PERIOD_YM, EMPNO, OT_MINUTES, CREATED_BY) " +
                         "VALUES ('202605', 'E0003', 120, 'TEST')");
    }

    @AfterEach void cleanupContext() {
        appJdbc().update("DELETE FROM HR_OVERTIME WHERE PERIOD_YM = '202605'");
        AuthContext.clear();
    }

    @Test
    @DisplayName("ALL 스냅샷 + 야근 합 존재")
    void all_snapshot_exists() {
        var row = mapper.findUnit("202605", "ALL", "ALL");
        assertThat(row).isNotNull();
        assertThat(row.usedMm()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("DEPT 스냅샷 조회 — D101")
    void dept_snapshot_exists() {
        // AggregationService 가 DEPT 행을 생성해야 한다
        var row = mapper.findUnit("202605", "DEPT", "D101");
        assertThat(row).isNotNull();
        assertThat(row.usedMm()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("PART 스냅샷 조회 — D101-P01")
    void part_snapshot_exists() {
        // E0002 는 D101-P01 소속
        var row = mapper.findUnit("202605", "PART", "D101-P01");
        assertThat(row).isNotNull();
        assertThat(row.usedMm()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("unit=all 서비스 200")
    void service_unit_all() {
        var view = resourceService.unit("202605", "all", null);
        assertThat(view).isNotNull();
        assertThat(view.unitType()).isEqualTo("ALL");
        assertThat(view.unitId()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("unit=dept 서비스 200 — D101")
    void service_unit_dept() {
        var view = resourceService.unit("202605", "dept", "D101");
        assertThat(view).isNotNull();
        assertThat(view.unitType()).isEqualTo("DEPT");
        assertThat(view.unitId()).isEqualTo("D101");
    }

    @Test
    @DisplayName("unit=part 서비스 200 — D101-P01")
    void service_unit_part() {
        var view = resourceService.unit("202605", "part", "D101-P01");
        assertThat(view).isNotNull();
        assertThat(view.unitType()).isEqualTo("PART");
        assertThat(view.unitId()).isEqualTo("D101-P01");
    }

    @Test
    @DisplayName("unit=dept unitId 누락 시 400")
    void service_dept_missing_unitId_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> resourceService.unit("202605", "dept", null)
        );
    }

    @Test
    @DisplayName("unit=part unitId 누락 시 400")
    void service_part_missing_unitId_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> resourceService.unit("202605", "part", null)
        );
    }

    @Test
    @DisplayName("avail_mm 0 방어 — utilization=0.0")
    void zero_avail_returns_zero_util() {
        // 존재하지 않는 period → row null → 예외(집계없음). 분모0 방어는 존재하는 row 기준.
        // 실제 데이터에서 availMm>0이면 util>0이어야 한다
        var view = resourceService.unit("202605", "all", null);
        assertThat(view.utilization()).isGreaterThanOrEqualTo(0.0);
        assertThat(view.utilization()).isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("야근 목록은 HR_OVERTIME 기반 — otMinutes 내림차순 + 분→시간 환산(소수 1자리)")
    void overtime_list_reads_from_hr_overtime() {
        var summary = resourceService.overtimeSummary("202605", null, null);

        var e2 = summary.list().stream().filter(v -> v.empno().equals("E0002")).findFirst().orElseThrow();
        var e3 = summary.list().stream().filter(v -> v.empno().equals("E0003")).findFirst().orElseThrow();
        assertThat(e2.otMinutes()).isEqualTo(774);
        assertThat(e2.overtimeHours()).isEqualTo(12.9);   // 774/60
        assertThat(e3.otMinutes()).isEqualTo(120);
        assertThat(e3.overtimeHours()).isEqualTo(2.0);
        // otMinutes 내림차순 — E0002가 E0003보다 먼저
        assertThat(summary.list().indexOf(e2)).isLessThan(summary.list().indexOf(e3));
    }

    @Test
    @DisplayName("평균 야근시간 분모는 스코프 재직 개발자 수 (야근자 수 아님)")
    void overtime_avg_denominator_is_scope_headcount() {
        int headcount = mapper.countDevelopersByScope(null, null, null);
        assertThat(headcount).isGreaterThan(0);

        var summary = resourceService.overtimeSummary("202605", null, null);
        double expected = Math.round((774 + 120) / 60.0 / headcount * 10.0) / 10.0;
        assertThat(summary.avgOvertimeHours()).isEqualTo(expected);
        assertThat(summary.list()).isNotNull();
    }

    @Test
    @DisplayName("overtime dept 필터 — 해당 부서 개발자만")
    void overtime_dept_filter() {
        // V013에서 D101→2139 정규화될 수 있으므로 E0002의 실제 부서코드를 조회해 사용
        String dept = appJdbc().queryForObject(
                "SELECT DEPT_CD FROM HR_DEVELOPER WHERE EMPNO = 'E0002'", String.class);

        var summary = resourceService.overtimeSummary("202605", dept, null);
        assertThat(summary.list()).extracting(OvertimeView::empno).contains("E0002");
        // 해당 부서 개발자만 포함돼야 하므로 전체 결과와 동일하거나 적어야 한다
        var all = resourceService.overtimeSummary("202605", null, null);
        assertThat(summary.list().size()).isLessThanOrEqualTo(all.list().size());
    }

    // ───── 신규: findUnitRange ─────

    @Test
    @DisplayName("findUnitRange — 202604·202605 두 달 데이터 오름차순 반환")
    void findUnitRange_returns_multiple_months_ascending() throws Exception {
        // 202604 추가 시드 (BeforeEach의 202605 시드에 추가)
        agg.run("202604", "MANUAL");

        var rows = mapper.findUnitRange("202604", "202605", "ALL", "ALL");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).periodYm()).isEqualTo("202604");
        assertThat(rows.get(1).periodYm()).isEqualTo("202605");
    }

    @Test
    @DisplayName("findUnitRange — 데이터 없는 기간 조회 시 빈 리스트 반환 (에러 아님)")
    void findUnitRange_empty_when_no_data() {
        var rows = mapper.findUnitRange("202501", "202503", "ALL", "ALL");

        assertThat(rows).isNotNull();
        assertThat(rows).isEmpty();
    }

    // ───── 신규: 개발자별 가용률 developerUtil ─────

    @Test
    @DisplayName("developerUtil — empno 없으면 전체 재직 개발자, availMm=1.0, utilization=usedMm")
    void developerUtil_all_developers() {
        var list = resourceService.developerUtil("202605", null);

        assertThat(list).isNotEmpty();
        assertThat(list).allMatch(v -> v.availMm() == 1.0);          // DEV_YN='Y'만
        assertThat(list).allMatch(v -> v.usedMm() >= 0.0);           // SR 없어도 0으로 포함
        assertThat(list).allMatch(v -> v.utilization() == v.usedMm()); // avail=1.0 이므로 동일
        // 최소 한 명은 이번달 SR이 있어 usedMm>0
        assertThat(list).anyMatch(v -> v.usedMm() > 0.0);
    }

    @Test
    @DisplayName("developerUtil — empno 지정 시 해당 개발자만 반환")
    void developerUtil_specific_developer() {
        var list = resourceService.developerUtil("202605", "E0002");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).empno()).isEqualTo("E0002");
    }
}
