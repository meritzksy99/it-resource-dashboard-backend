package com.meritz.dash.aggregation;

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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AggregationController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class AggregationControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    AggregationService service;

    @MockBean
    com.meritz.dash.mapper.app.BatchLogMapper batchLog;


    @Test
    @DisplayName("POST /aggregations {periodYm} → 201 + 실행 목록")
    void run_single() throws Exception {
        when(service.run("202605", "MANUAL")).thenReturn(1L);

        mvc.perform(post("/api/v1/aggregations")
                        .contentType("application/json")
                        .content("{\"periodYm\":\"202605\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.periods[0]").value("202605"))
                .andExpect(jsonPath("$.data.count").value(1));

        verify(service).run("202605", "MANUAL");
    }

    @Test
    @DisplayName("POST /aggregations {from,to} → 201 + 범위 실행 목록")
    void run_range() throws Exception {
        when(service.run(anyString(), eq("MANUAL"))).thenReturn(1L);

        mvc.perform(post("/api/v1/aggregations")
                        .contentType("application/json")
                        .content("{\"from\":\"202604\",\"to\":\"202606\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.count").value(3));

        verify(service, times(3)).run(anyString(), eq("MANUAL"));
    }

    @Test
    @DisplayName("POST 잘못된 형식 → 400 ProblemDetail")
    void run_invalid() throws Exception {
        mvc.perform(post("/api/v1/aggregations")
                        .contentType("application/json")
                        .content("{\"periodYm\":\"2026-05\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET /aggregations → 이력 목록 200")
    void history() throws Exception {
        when(batchLog.findRecent()).thenReturn(List.of(
                new BatchRunLogView(1L, "202605", "MANUAL", "OK", 100, 5,
                        "2026-05-01 02:00:00", "2026-05-01 02:01:00", null)
        ));

        mvc.perform(get("/api/v1/aggregations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].periodYm").value("202605"))
                .andExpect(jsonPath("$.data[0].trigType").value("MANUAL"));
    }
}
