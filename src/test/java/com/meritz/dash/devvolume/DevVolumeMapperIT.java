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
    @Autowired DevVolumeService devVolumeService;

    @BeforeEach void seed() throws Exception { agg.run("202605", "MANUAL"); }

    @Test void all_series() {
        List<DevVolumePoint> pts = mapper.findSeries("ALL", null, "202512");
        assertThat(pts).anyMatch(p -> p.periodYm().equals("202605") && p.srCnt() > 0);
        assertThat(pts).allMatch(p -> p.monthLabel().matches("\\d{2}\\.\\d{2}"));
        // 월/SR분류별 M/M(jobMm)도 함께 조회되어야 한다 — 건수와 별개로 계획공수 합계
        assertThat(pts).allMatch(p -> p.jobMm() >= 0.0);
        assertThat(pts).anyMatch(p -> p.jobMm() > 0.0);
    }

    @Test void dept_series_filters_to_dept() {
        // D101 부서만 조회 — DASH_DEV_AGG ⨝ HR_DEVELOPER DEPT_CD='D101'
        List<DevVolumePoint> pts = mapper.findSeries("DEPT", "D101", "202512");
        assertThat(pts).isNotEmpty();
        // 전체와 건수 동일하거나 적어야
        List<DevVolumePoint> all = mapper.findSeries("ALL", null, "202512");
        long deptTotal = pts.stream().mapToLong(DevVolumePoint::srCnt).sum();
        long allTotal  = all.stream().mapToLong(DevVolumePoint::srCnt).sum();
        assertThat(deptTotal).isLessThanOrEqualTo(allTotal);
    }

    @Test void part_series_filters_to_part() {
        // D101-P01 파트만 (DEPT_CD||'-'||PART_CD)
        List<DevVolumePoint> pts = mapper.findSeries("PART", "D101-P01", "202512");
        assertThat(pts).isNotEmpty();
        List<DevVolumePoint> dept = mapper.findSeries("DEPT", "D101", "202512");
        long partTotal = pts.stream().mapToLong(DevVolumePoint::srCnt).sum();
        long deptTotal = dept.stream().mapToLong(DevVolumePoint::srCnt).sum();
        assertThat(partTotal).isLessThanOrEqualTo(deptTotal);
    }

    @Test void dev_series_filters_to_dev() {
        List<DevVolumePoint> pts = mapper.findSeries("DEV", "E0002", "202512");
        assertThat(pts).isNotEmpty();
    }

    @Test void find_dept_part_by_empno_returns_dept_and_part() {
        // 업무리더(02) 파트원 여부 확인용 — V002 시드: E0002=D101/P01, E0003=D101/P02
        DevDeptPart e2 = mapper.findDeptPartByEmpno("E0002");
        assertThat(e2).isNotNull();
        assertThat(e2.deptCd()).isEqualTo("D101");
        assertThat(e2.partCd()).isEqualTo("P01");

        DevDeptPart e3 = mapper.findDeptPartByEmpno("E0003");
        assertThat(e3).isNotNull();
        assertThat(e3.partCd()).isEqualTo("P02");
    }

    @Test void find_dept_part_by_empno_unknown_returns_null() {
        assertThat(mapper.findDeptPartByEmpno("NO_SUCH")).isNull();
    }

    @Test void service_unit_all_ok() {
        List<DevVolumePoint> pts = devVolumeService.series("all", "6m", null);
        assertThat(pts).isNotEmpty();
    }

    @Test void service_unit_dept_ok() {
        List<DevVolumePoint> pts = devVolumeService.series("dept", "6m", "D101");
        assertThat(pts).isNotEmpty();
    }

    @Test void service_unit_part_ok() {
        List<DevVolumePoint> pts = devVolumeService.series("part", "6m", "D101-P01");
        assertThat(pts).isNotEmpty();
    }

    @Test void service_bad_unit_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> devVolumeService.series("bad", "6m", null)
        );
    }

    @Test void service_dept_missing_unitId_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> devVolumeService.series("dept", "6m", null)
        );
    }
}
