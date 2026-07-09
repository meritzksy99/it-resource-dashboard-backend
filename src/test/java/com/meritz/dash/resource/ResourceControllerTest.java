package com.meritz.dash.resource;

import com.meritz.dash.common.GlobalExceptionHandler;
import com.meritz.dash.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = ResourceController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class ResourceControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ResourceService service;

    // ───── 기존 단일 조회(period + unit) — 컨트롤러 변경 후 unitRange로 위임 ─────

    @Test
    @DisplayName("unit=all + period만 있으면 200, data[0].unitType=ALL")
    void unit_all_returns_200() throws Exception {
        when(service.unitRange(eq("202605"), isNull(), isNull(), eq("all"), isNull()))
            .thenReturn(new ResourceRangeResult(
                List.of(new ResourceView("202605", "ALL", "ALL", 3, 3, 3.0, 2.4, 0.3, 0.8)),
                "202605", "202605", "ALL", "ALL"));
        mvc.perform(get("/api/v1/resource").param("period", "202605").param("unit", "all"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].unitType").value("ALL"));
    }

    @Test
    @DisplayName("unit=dept + unitId=D101 + period → 200, data[0].unitId=D101")
    void unit_dept_returns_200() throws Exception {
        when(service.unitRange(eq("202605"), isNull(), isNull(), eq("dept"), eq("D101")))
            .thenReturn(new ResourceRangeResult(
                List.of(new ResourceView("202605", "DEPT", "D101", 4, 4, 4.0, 3.2, 0.3, 0.8)),
                "202605", "202605", "DEPT", "D101"));
        mvc.perform(get("/api/v1/resource")
                .param("period", "202605").param("unit", "dept").param("unitId", "D101"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].unitType").value("DEPT"))
           .andExpect(jsonPath("$.data[0].unitId").value("D101"));
    }

    @Test
    @DisplayName("unit=part + unitId=D101-P01 + period → 200, data[0].unitType=PART")
    void unit_part_returns_200() throws Exception {
        when(service.unitRange(eq("202605"), isNull(), isNull(), eq("part"), eq("D101-P01")))
            .thenReturn(new ResourceRangeResult(
                List.of(new ResourceView("202605", "PART", "D101-P01", 2, 2, 2.0, 1.5, 0.0, 0.75)),
                "202605", "202605", "PART", "D101-P01"));
        mvc.perform(get("/api/v1/resource")
                .param("period", "202605").param("unit", "part").param("unitId", "D101-P01"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].unitType").value("PART"))
           .andExpect(jsonPath("$.data[0].unitId").value("D101-P01"));
    }

    @Test
    @DisplayName("unit=dept이고 unitId 누락 시 서비스 IAE → 400")
    void unit_dept_without_unitId_returns_400() throws Exception {
        when(service.unitRange(eq("202605"), isNull(), isNull(), eq("dept"), isNull()))
            .thenThrow(new IllegalArgumentException("dept/part 조회에는 unitId가 필요합니다"));
        mvc.perform(get("/api/v1/resource").param("period", "202605").param("unit", "dept"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unit=part이고 unitId 누락 시 서비스 IAE → 400")
    void unit_part_without_unitId_returns_400() throws Exception {
        when(service.unitRange(eq("202605"), isNull(), isNull(), eq("part"), isNull()))
            .thenThrow(new IllegalArgumentException("dept/part 조회에는 unitId가 필요합니다"));
        mvc.perform(get("/api/v1/resource").param("period", "202605").param("unit", "part"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("availMm=0이면 data[0].utilization=0.0")
    void util_zero_when_no_avail() throws Exception {
        when(service.unitRange(eq("202605"), isNull(), isNull(), anyString(), any()))
            .thenReturn(new ResourceRangeResult(
                List.of(new ResourceView("202605", "ALL", "ALL", 0, 0, 0.0, 3.0, 0.5, 0.0)),
                "202605", "202605", "ALL", "ALL"));
        mvc.perform(get("/api/v1/resource").param("period", "202605"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].utilization").value(0.0));
    }

    @Test
    @DisplayName("period·from·to 모두 없으면 400")
    void missing_period() throws Exception {
        mvc.perform(get("/api/v1/resource"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("unit 파라미터 값이 잘못되면(xyz) 서비스 IAE → 400")
    void unit_invalid_value_returns_400() throws Exception {
        when(service.unitRange(eq("202605"), isNull(), isNull(), eq("xyz"), isNull()))
            .thenThrow(new IllegalArgumentException("unit은 all|dept|part"));
        mvc.perform(get("/api/v1/resource").param("period", "202605").param("unit", "xyz"))
           .andExpect(status().isBadRequest());
    }

    // ───── 신규: from/to 범위 조회 ─────

    @Test
    @DisplayName("from=202601·to=202603·unit=dept·unitId=D101 → service.unitRange 호출, 배열 반환, meta.count=2")
    void range_from_to_returns_list() throws Exception {
        List<ResourceView> views = List.of(
                new ResourceView("202601", "DEPT", "D101", 4, 4, 4.0, 3.2, 0.0, 0.8),
                new ResourceView("202602", "DEPT", "D101", 4, 4, 4.0, 3.6, 0.0, 0.9)
        );
        when(service.unitRange(isNull(), eq("202601"), eq("202603"), eq("dept"), eq("D101")))
                .thenReturn(new ResourceRangeResult(views, "202601", "202603", "DEPT", "D101"));

        mvc.perform(get("/api/v1/resource")
                        .param("from", "202601")
                        .param("to", "202603")
                        .param("unit", "dept")
                        .param("unitId", "D101"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").isArray())
           .andExpect(jsonPath("$.data.length()").value(2))
           .andExpect(jsonPath("$.meta.count").value(2));
    }

    @Test
    @DisplayName("period=202605만 있으면 service.unitRange 호출 후 배열 반환")
    void range_period_only_returns_list() throws Exception {
        when(service.unitRange(eq("202605"), isNull(), isNull(), anyString(), any()))
                .thenReturn(new ResourceRangeResult(
                    List.of(new ResourceView("202605", "ALL", "ALL", 3, 3, 3.0, 2.4, 0.0, 0.8)),
                    "202605", "202605", "ALL", "ALL"));

        mvc.perform(get("/api/v1/resource").param("period", "202605"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").isArray())
           .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("from·to·period 모두 없으면 400")
    void range_missing_all_params_returns_400() throws Exception {
        mvc.perform(get("/api/v1/resource"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("from=202603·to=202601(역순) → service IAE → 400")
    void range_from_after_to_returns_400() throws Exception {
        when(service.unitRange(isNull(), eq("202603"), eq("202601"), anyString(), any()))
                .thenThrow(new IllegalArgumentException("from은 to보다 이전이어야 합니다"));

        mvc.perform(get("/api/v1/resource")
                        .param("from", "202603")
                        .param("to", "202601"))
           .andExpect(status().isBadRequest());
    }

    // ───── W1: meta from/to 유효구간 일원화 검증 ─────

    @Test
    @DisplayName("?period=202605&from=202601(to없음) → from/to 불완전 지정이므로 서비스 IAE → 400")
    void meta_from_to_partial_returns_400() throws Exception {
        // period=202605, from=202601, to=null → hasFrom && !hasTo → IAE "from과 to는 함께 지정해야 합니다"
        when(service.unitRange(eq("202605"), eq("202601"), isNull(), anyString(), any()))
            .thenThrow(new IllegalArgumentException("from과 to는 함께 지정해야 합니다"));
        mvc.perform(get("/api/v1/resource")
                .param("period", "202605").param("from", "202601"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("period만 있으면 meta.from=meta.to=period(서비스 유효구간 일원화)")
    void meta_from_to_uses_service_effective_range_period_only() throws Exception {
        when(service.unitRange(eq("202605"), isNull(), isNull(), anyString(), any()))
            .thenReturn(new ResourceRangeResult(
                List.of(new ResourceView("202605", "ALL", "ALL", 3, 3, 3.0, 2.4, 0.0, 0.8)),
                "202605", "202605", "ALL", "ALL"));
        mvc.perform(get("/api/v1/resource").param("period", "202605"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.meta.from").value("202605"))
           .andExpect(jsonPath("$.meta.to").value("202605"));
    }

    // ───── W4c: from 단독(to 없음) → 400 ─────

    @Test
    @DisplayName("from만 있고 to 없으면 서비스 IAE → 400")
    void from_without_to_returns_400() throws Exception {
        when(service.unitRange(isNull(), eq("202601"), isNull(), anyString(), any()))
            .thenThrow(new IllegalArgumentException("from과 to는 함께 지정해야 합니다"));
        mvc.perform(get("/api/v1/resource").param("from", "202601"))
           .andExpect(status().isBadRequest());
    }

    // ───── 야근 관련 테스트 (HR_OVERTIME 시간 기반 계약) ─────

    @Test
    @DisplayName("overtime dept 필터 → 200, data[0].otMinutes/overtimeHours, meta.avgOvertimeHours=3.2")
    void overtime_dept_filter() throws Exception {
        List<OvertimeView> list = List.of(new OvertimeView("E001", "홍길동", "P01", 774, 12.9));
        when(service.overtimeSummary(eq("202605"), eq("D101"), isNull()))
            .thenReturn(new OvertimeSummary(list, 3.2));
        mvc.perform(get("/api/v1/resource/overtime")
                .param("period", "202605").param("dept", "D101"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(1))
           .andExpect(jsonPath("$.data[0].otMinutes").value(774))
           .andExpect(jsonPath("$.data[0].overtimeHours").value(12.9))
           .andExpect(jsonPath("$.meta.avgOvertimeHours").value(3.2));
    }

    @Test
    @DisplayName("overtime dept+part 필터 → 200, meta.avgOvertimeHours=1.5")
    void overtime_dept_and_part_filter() throws Exception {
        List<OvertimeView> list = List.of(new OvertimeView("E002", "이개발", "P01", 300, 5.0));
        when(service.overtimeSummary(eq("202605"), eq("D101"), eq("P01")))
            .thenReturn(new OvertimeSummary(list, 1.5));
        mvc.perform(get("/api/v1/resource/overtime")
                .param("period", "202605").param("dept", "D101").param("part", "P01"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.meta.avgOvertimeHours").value(1.5));
    }

    @Test
    @DisplayName("overtime dept 없이 part만 → 서비스 IAE → 400")
    void overtime_part_without_dept_returns_400() throws Exception {
        when(service.overtimeSummary(eq("202605"), isNull(), eq("P01")))
            .thenThrow(new IllegalArgumentException("part 조회에는 dept가 필요합니다"));
        mvc.perform(get("/api/v1/resource/overtime")
                .param("period", "202605").param("part", "P01"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("overtime avgOvertimeHours는 야근자 수가 아닌 스코프 인원 기준 분모")
    void overtime_avg_uses_scope_headcount_not_list_size() throws Exception {
        // 3명 팀 중 1명만 야근(180분=3h) — avgOvertimeHours = 3/3=1.0 ≠ 3/1
        List<OvertimeView> list = List.of(new OvertimeView("E001", "홍길동", "P01", 180, 3.0));
        when(service.overtimeSummary(eq("202605"), isNull(), isNull()))
            .thenReturn(new OvertimeSummary(list, 1.0));  // 180/60/3명 = 1.0
        mvc.perform(get("/api/v1/resource/overtime").param("period", "202605"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.meta.avgOvertimeHours").value(1.0))
           .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("overtime period 누락 시 400")
    void overtime_missing_period() throws Exception {
        mvc.perform(get("/api/v1/resource/overtime"))
           .andExpect(status().isBadRequest());
    }

    // ───── 신규: 개발자별 가용률 /developers ─────

    @Test
    @DisplayName("empno 없이 period만 → 전체 개발자 200, meta.count=2")
    void developers_all_returns_200() throws Exception {
        when(service.developerUtil(eq("202606"), isNull())).thenReturn(List.of(
                new DeveloperUtilView("7451", "홍길동", "2139", "P01", 1.0, 1.2, 1.2),
                new DeveloperUtilView("7452", "김철수", "2139", "P02", 1.0, 0.0, 0.0)
        ));
        mvc.perform(get("/api/v1/resource/developers").param("period", "202606"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(2))
           .andExpect(jsonPath("$.data[0].empno").value("7451"))
           .andExpect(jsonPath("$.data[0].utilization").value(1.2))
           .andExpect(jsonPath("$.meta.count").value(2));
    }

    @Test
    @DisplayName("empno 지정 시 해당 개발자만 → 200, data.length=1")
    void developers_specific_returns_200() throws Exception {
        when(service.developerUtil(eq("202606"), eq("7451"))).thenReturn(List.of(
                new DeveloperUtilView("7451", "홍길동", "2139", "P01", 1.0, 0.8, 0.8)
        ));
        mvc.perform(get("/api/v1/resource/developers")
                .param("period", "202606").param("empno", "7451"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(1))
           .andExpect(jsonPath("$.data[0].empno").value("7451"));
    }

    @Test
    @DisplayName("developers period 누락 시 400")
    void developers_missing_period_returns_400() throws Exception {
        mvc.perform(get("/api/v1/resource/developers"))
           .andExpect(status().isBadRequest());
    }
}
