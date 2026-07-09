package com.meritz.dash.partsr;

import com.meritz.dash.common.GlobalExceptionHandler;
import com.meritz.dash.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = PartSrController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class PartSrControllerTest {

    @Autowired MockMvc mvc;
    @MockBean PartSrService service;

    @Test
    @DisplayName("period=202606 → 200, data.parts 배열, meta.period, meta.partCount, meta.outsourcingCount")
    void valid_period_returns_200_with_envelope() throws Exception {
        PartSrRow internalRow = new PartSrRow("2139", "IT개발팀", "P01", "금융상품",
                2, List.of("김동현", "김성엽"), 1.5,
                List.of(new SrClassCount("01", "개발요청", 10, 1.5)));
        PartSrRow outsourcingRow = new PartSrRow("9000", "외주", "P01", "금융상품",
                1, List.of("외주자"), 0.5,
                List.of(new SrClassCount("01", "개발요청", 2, 0.5)));
        PartSrResult result = new PartSrResult(List.of(internalRow), List.of(outsourcingRow));
        when(service.summary(eq("202606"), isNull())).thenReturn(result);

        mvc.perform(get("/api/v1/dashboard/part-sr").param("period", "202606"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.parts").isArray())
           .andExpect(jsonPath("$.data.parts.length()").value(1))
           .andExpect(jsonPath("$.data.parts[0].partCd").value("P01"))
           .andExpect(jsonPath("$.data.parts[0].partNm").value("금융상품"))
           .andExpect(jsonPath("$.data.parts[0].headcount").value(2))
           .andExpect(jsonPath("$.data.parts[0].srByClass[0].srCnt").value(10))
           .andExpect(jsonPath("$.data.outsourcing").isArray())
           .andExpect(jsonPath("$.data.outsourcing.length()").value(1))
           .andExpect(jsonPath("$.data.outsourcing[0].partCd").value("P01"))
           .andExpect(jsonPath("$.meta.period").value("202606"))
           .andExpect(jsonPath("$.meta.partCount").value(1))
           .andExpect(jsonPath("$.meta.outsourcingCount").value(1));
    }

    @Test
    @DisplayName("period=202606&part=P01 → service.summary('202606','P01') 호출, 200")
    void part_filter_passed_to_service() throws Exception {
        PartSrRow row = new PartSrRow("2139", "IT개발팀", "P01", "금융상품",
                1, List.of("김성엽"), 1.0, List.of());
        PartSrResult result = new PartSrResult(List.of(row), List.of());
        when(service.summary(eq("202606"), eq("P01"))).thenReturn(result);

        mvc.perform(get("/api/v1/dashboard/part-sr")
                .param("period", "202606").param("part", "P01"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.meta.partCount").value(1))
           .andExpect(jsonPath("$.meta.outsourcingCount").value(0));
    }

    @Test
    @DisplayName("period 없으면 400 (필수 파라미터)")
    void missing_period_returns_400() throws Exception {
        mvc.perform(get("/api/v1/dashboard/part-sr"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("period=202613 (월=13) → 서비스 IAE → 400")
    void invalid_period_month13_returns_400() throws Exception {
        when(service.summary(eq("202613"), any()))
                .thenThrow(new IllegalArgumentException("period는 YYYYMM 6자리 실제 월이어야 합니다: 202613"));
        mvc.perform(get("/api/v1/dashboard/part-sr").param("period", "202613"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("결과 빈 배열 → data.parts=[], data.outsourcing=[], meta.partCount=0, meta.outsourcingCount=0")
    void empty_result_returns_empty_arrays() throws Exception {
        when(service.summary(eq("202601"), isNull()))
                .thenReturn(new PartSrResult(List.of(), List.of()));

        mvc.perform(get("/api/v1/dashboard/part-sr").param("period", "202601"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.parts").isArray())
           .andExpect(jsonPath("$.data.parts.length()").value(0))
           .andExpect(jsonPath("$.data.outsourcing").isArray())
           .andExpect(jsonPath("$.data.outsourcing.length()").value(0))
           .andExpect(jsonPath("$.meta.partCount").value(0))
           .andExpect(jsonPath("$.meta.outsourcingCount").value(0));
    }

    @Test
    @DisplayName("outsourcing 행 srByClass 직렬화 확인")
    void outsourcing_row_serialized() throws Exception {
        PartSrRow outsourcingRow = new PartSrRow("9000", "외주", "P01", "금융상품",
                1, List.of("외주자"), 0.5,
                List.of(new SrClassCount("01", "개발요청", 2, 0.5)));
        PartSrResult result = new PartSrResult(List.of(), List.of(outsourcingRow));
        when(service.summary(eq("202606"), isNull())).thenReturn(result);

        mvc.perform(get("/api/v1/dashboard/part-sr").param("period", "202606"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.outsourcing[0].deptCd").value("9000"))
           .andExpect(jsonPath("$.data.outsourcing[0].headcount").value(1))
           .andExpect(jsonPath("$.data.outsourcing[0].memberNames[0]").value("외주자"))
           .andExpect(jsonPath("$.data.outsourcing[0].srByClass[0].srCls").value("01"))
           .andExpect(jsonPath("$.meta.outsourcingCount").value(1));
    }
}
