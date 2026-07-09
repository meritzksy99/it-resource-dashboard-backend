package com.meritz.dash.developer;

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

import org.springframework.http.MediaType;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = DeveloperController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class DeveloperControllerTest {

    @Autowired MockMvc mvc;
    @MockBean DeveloperService service;

    @Test
    @DisplayName("GET /api/v1/developers → data 배열 + meta.count")
    void list_ok() throws Exception {
        when(service.list(any(), any(), any(), any())).thenReturn(List.of(
                new Developer("E0002", "이개발", "D101", "P01", "과장", "03", "Y", "재직")));
        mvc.perform(get("/api/v1/developers"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].empno").value("E0002"))
           .andExpect(jsonPath("$.meta.count").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/developers/{empno} 미존재 → 400 ProblemDetail (status+detail 포함)")
    void get_not_found() throws Exception {
        when(service.get("E9999")).thenThrow(new IllegalArgumentException("사번 E9999 인력이 없습니다."));
        mvc.perform(get("/api/v1/developers/E9999"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400))
           .andExpect(jsonPath("$.detail").value("사번 E9999 인력이 없습니다."));
    }

    @Test
    @DisplayName("POST /api/v1/developers 정상 → 201")
    void create_ok() throws Exception {
        when(service.create(any()))
            .thenReturn(new Developer("E9001", "신규자", "D101", "P03", "사원", "03", "Y", "재직"));
        mvc.perform(post("/api/v1/developers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"empno\":\"E9001\",\"empNm\":\"신규자\",\"devYn\":\"Y\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.empno").value("E9001"));
    }

    @Test
    @DisplayName("POST empno 누락 → 400 ProblemDetail")
    void create_invalid() throws Exception {
        mvc.perform(post("/api/v1/developers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"empNm\":\"이름만\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST 최소필드(statusCd 생략) → 201 (기본값 01 적용)")
    void create_minimal_fields_returns_201() throws Exception {
        when(service.create(any()))
            .thenReturn(new Developer("E9002", "최소자", null, null, null, null, "Y", "01"));
        mvc.perform(post("/api/v1/developers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"empno\":\"E9002\",\"empNm\":\"최소자\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.empno").value("E9002"));
    }

    @Test
    @DisplayName("POST statusCd=03 잘못된 값 → 400")
    void create_invalid_statusCd_returns_400() throws Exception {
        mvc.perform(post("/api/v1/developers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"empno\":\"E9003\",\"empNm\":\"테스터\",\"statusCd\":\"03\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400));
    }

    // ── dept 필터 신규 테스트 (Red 단계: service.list가 4인자를 받기 전까지 컴파일 에러) ──

    @Test
    @DisplayName("GET ?dept=2139 → service.list('2139', null, null, null) 호출 + 해당 부서 결과 반환")
    void dept_filter_only() throws Exception {
        when(service.list(eq("2139"), isNull(), isNull(), isNull())).thenReturn(List.of(
                new Developer("E2139", "부서2139", "2139", "P01", "사원", "03", "Y", "재직")));
        mvc.perform(get("/api/v1/developers").param("dept", "2139"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].deptCd").value("2139"))
           .andExpect(jsonPath("$.meta.count").value(1));
        verify(service).list(eq("2139"), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("GET ?part=P01 → service.list(null, 'P01', null, null) 호출")
    void part_filter_only() throws Exception {
        when(service.list(isNull(), eq("P01"), isNull(), isNull())).thenReturn(List.of(
                new Developer("E0002", "이개발", "D101", "P01", "과장", "03", "Y", "재직")));
        mvc.perform(get("/api/v1/developers").param("part", "P01"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].partCd").value("P01"));
        verify(service).list(isNull(), eq("P01"), isNull(), isNull());
    }

    @Test
    @DisplayName("GET ?dept=2139&part=P01 → service.list('2139', 'P01', null, null) 호출")
    void dept_and_part_filter() throws Exception {
        when(service.list(eq("2139"), eq("P01"), isNull(), isNull())).thenReturn(List.of(
                new Developer("E2139", "부서2139", "2139", "P01", "사원", "03", "Y", "재직")));
        mvc.perform(get("/api/v1/developers").param("dept", "2139").param("part", "P01"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].deptCd").value("2139"))
           .andExpect(jsonPath("$.data[0].partCd").value("P01"));
        verify(service).list(eq("2139"), eq("P01"), isNull(), isNull());
    }

    @Test
    @DisplayName("GET 파라미터 없음 → service.list(null, null, null, null) 호출 (전체 조회)")
    void no_filter_returns_all() throws Exception {
        when(service.list(isNull(), isNull(), isNull(), isNull())).thenReturn(List.of(
                new Developer("E0001", "김팀장", "D101", "P01", "부장", "01", "N", "재직"),
                new Developer("E0002", "이개발", "D101", "P01", "과장", "03", "Y", "재직")));
        mvc.perform(get("/api/v1/developers"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.meta.count").value(2));
        verify(service).list(isNull(), isNull(), isNull(), isNull());
    }
}
