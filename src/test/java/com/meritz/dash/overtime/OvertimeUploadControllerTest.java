package com.meritz.dash.overtime;

import com.meritz.dash.auth.Auth;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/overtime/uploads 컨트롤러 계약 테스트.
 * (JWT 인터셉터는 WebConfig 제외로 미적용 — 역할 강제는 @Auth 어노테이션 계약 + OvertimeRbacIT에서 검증)
 */
@WebMvcTest(value = OvertimeUploadController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class OvertimeUploadControllerTest {

    @Autowired MockMvc mvc;
    @MockBean OvertimeUploadService service;

    private static MockMultipartFile xlsx() {
        return new MockMultipartFile("file", "야근양식.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("업로드 성공 → 200, data.saved=저장건수, meta.period")
    void upload_returns_200_with_saved_count() throws Exception {
        when(service.upload(eq("202606"), any())).thenReturn(14);

        mvc.perform(multipart("/api/v1/overtime/uploads")
                        .file(xlsx())
                        .param("period", "202606"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.saved").value(14))
           .andExpect(jsonPath("$.meta.period").value("202606"));
    }

    @Test
    @DisplayName("period 형식 오류 → 서비스 IAE → 400 ProblemDetail")
    void invalid_period_returns_400() throws Exception {
        when(service.upload(eq("2026"), any()))
                .thenThrow(new IllegalArgumentException("period는 YYYYMM 6자리 숫자여야 합니다: 2026"));

        mvc.perform(multipart("/api/v1/overtime/uploads")
                        .file(xlsx())
                        .param("period", "2026"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("period 파라미터 누락 → 400")
    void missing_period_returns_400() throws Exception {
        mvc.perform(multipart("/api/v1/overtime/uploads").file(xlsx()))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("file 파트 누락 → 400")
    void missing_file_returns_400() throws Exception {
        mvc.perform(multipart("/api/v1/overtime/uploads").param("period", "202606"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName(".xlsx 아닌 파일 → 서비스 IAE → 400")
    void non_xlsx_returns_400() throws Exception {
        when(service.upload(eq("202606"), any()))
                .thenThrow(new IllegalArgumentException(".xlsx 파일만 업로드할 수 있습니다: a.csv"));

        MockMultipartFile csv = new MockMultipartFile("file", "a.csv", "text/csv", new byte[]{1});
        mvc.perform(multipart("/api/v1/overtime/uploads")
                        .file(csv)
                        .param("period", "202606"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("@Auth 계약 — 업로드는 팀장(01)/ADMIN 만 (인터셉터가 그 외 403)")
    void upload_endpoint_requires_teamlead_or_admin_roles() throws Exception {
        Method m = OvertimeUploadController.class.getMethod(
                "upload", String.class, org.springframework.web.multipart.MultipartFile.class);
        Auth auth = m.getAnnotation(Auth.class);

        assertThat(auth).as("@Auth 어노테이션 필수").isNotNull();
        assertThat(auth.roles()).containsExactlyInAnyOrder("01", "ADMIN");
    }
}
