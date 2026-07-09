package com.meritz.dash.code;

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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = CodeController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class CodeControllerTest {

    @Autowired MockMvc mvc;
    @MockBean CodeService codeService;

    @Test
    @DisplayName("GET /api/v1/codes?grpCd=SR_TPCD → data 배열 + meta.count")
    void get_codes_ok() throws Exception {
        when(codeService.getCodes("SR_TPCD"))
                .thenReturn(List.of(new CommonCode("SR_TPCD", "1", "개발요청", 1)));
        mvc.perform(get("/api/v1/codes").param("grpCd", "SR_TPCD"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].cdNm").value("개발요청"))
           .andExpect(jsonPath("$.meta.count").value(1));
    }

    @Test
    @DisplayName("grpCd 파라미터 완전 누락 → 400 (스프링 자동 처리)")
    void get_codes_missing_grp() throws Exception {
        mvc.perform(get("/api/v1/codes"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("grpCd 빈 문자열 → 400 ProblemDetail (서비스 IllegalArgumentException → GlobalExceptionHandler)")
    void get_codes_blank_grp_problem_detail() throws Exception {
        when(codeService.getCodes(""))
                .thenThrow(new IllegalArgumentException("grpCd는 필수입니다."));
        mvc.perform(get("/api/v1/codes").param("grpCd", ""))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400))
           .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("POST /api/v1/codes 정상 요청 → 201, data.cdNm 반환")
    void post_code_returns_201() throws Exception {
        when(codeService.create(any()))
                .thenReturn(new CommonCode("TEST", "V1", "테스트", 0));
        mvc.perform(post("/api/v1/codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"grpCd\":\"TEST\",\"cdVal\":\"V1\",\"cdNm\":\"테스트\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.cdNm").value("테스트"));
    }

    @Test
    @DisplayName("POST grpCd 누락된 JSON → 400")
    void post_missing_grpCd_returns_400() throws Exception {
        mvc.perform(post("/api/v1/codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cdVal\":\"V1\",\"cdNm\":\"이름\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST useYn 잘못된 값(X) → 400")
    void post_invalid_useYn_returns_400() throws Exception {
        mvc.perform(post("/api/v1/codes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"grpCd\":\"TEST\",\"cdVal\":\"V1\",\"cdNm\":\"이름\",\"useYn\":\"X\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400));
    }
}
