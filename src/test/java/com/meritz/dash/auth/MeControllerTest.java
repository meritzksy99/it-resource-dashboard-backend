package com.meritz.dash.auth;

import com.meritz.dash.code.CommonCode;
import com.meritz.dash.common.GlobalExceptionHandler;
import com.meritz.dash.config.WebConfig;
import com.meritz.dash.developer.Developer;
import com.meritz.dash.mapper.app.CodeMapper;
import com.meritz.dash.mapper.app.DeveloperMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = MeController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class MeControllerTest {

    @Autowired MockMvc mvc;
    @MockBean DeveloperMapper developers;
    @MockBean CodeMapper codes;

    // 게이트웨이 인터셉터는 WebConfig 제외로 미적용 — 컨트롤러가 AuthContext.empno()를 읽으므로 직접 세팅
    @BeforeEach
    void setUpAuthContext() {
        AuthContext.set("9320", "03", "2139", "P03");
    }

    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void me_ok_returns_current_user_fields() throws Exception {
        when(developers.findByEmpno("9320")).thenReturn(
                new Developer("9320", "홍길동", "2139", "P03", "대리", "03", "Y", "01"));
        when(codes.findByGroup("EMP_ROLE")).thenReturn(List.of(
                new CommonCode("EMP_ROLE", "01", "팀장", 1),
                new CommonCode("EMP_ROLE", "02", "업무리더", 2),
                new CommonCode("EMP_ROLE", "03", "일반직원", 3)));

        mvc.perform(get("/api/v1/auth/me"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.empno").value("9320"))
           .andExpect(jsonPath("$.data.role").value("03"))
           .andExpect(jsonPath("$.data.roleName").value("일반직원"))
           .andExpect(jsonPath("$.data.name").value("홍길동"))
           .andExpect(jsonPath("$.data.partCd").value("P03"));
    }

    @Test
    void me_roleName_falls_back_to_roleCd_when_code_missing() throws Exception {
        when(developers.findByEmpno("9320")).thenReturn(
                new Developer("9320", "홍길동", "2139", "P03", "대리", "03", "Y", "01"));
        when(codes.findByGroup("EMP_ROLE")).thenReturn(List.of());

        mvc.perform(get("/api/v1/auth/me"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.roleName").value("03"));
    }

    @Test
    void me_without_auth_context_returns_401() throws Exception {
        AuthContext.clear();

        mvc.perform(get("/api/v1/auth/me"))
           .andExpect(status().isUnauthorized());
    }

    /** 인터셉터가 HR 미등록 사번을 403으로 거부하므로 정상 흐름에선 도달 불가 — 방어 분기도 동일하게 403. */
    @Test
    void me_unknown_empno_returns_403() throws Exception {
        when(developers.findByEmpno("9320")).thenReturn(null);

        mvc.perform(get("/api/v1/auth/me"))
           .andExpect(status().isForbidden());
    }
}
