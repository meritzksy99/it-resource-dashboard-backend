package com.meritz.dash.auth;

import com.meritz.dash.common.GlobalExceptionHandler;
import com.meritz.dash.config.WebConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AuthV2Controller.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class AuthV2ControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthPolicyService service;

    // @Auth 인터셉터는 WebConfig 제외로 미적용 — /password가 AuthContext.empno()를 호출하므로 직접 세팅
    @BeforeEach
    void setUpAuthContext() {
        AuthContext.set("9320", "03", null, null);
    }

    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test void login_ok_returns_token_and_pwdReset() throws Exception {
        when(service.login(any())).thenReturn(new LoginResult("tok", "9320", "03", "일반직원", "홍길동", true));
        mvc.perform(post("/api/v2/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"empno\":\"9320\",\"password\":\"9320\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.token").value("tok"))
           .andExpect(jsonPath("$.data.pwdResetRequired").value(true));
    }

    @Test void login_locked_returns_403_with_errorCode() throws Exception {
        when(service.login(any())).thenThrow(AuthPolicyException.locked());
        mvc.perform(post("/api/v2/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"empno\":\"9320\",\"password\":\"x\"}"))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.errorCode").value("ACCOUNT_LOCKED"));
    }

    @Test void password_policy_violation_returns_400_code() throws Exception {
        doThrow(AuthPolicyException.policyViolation("복잡도 미달"))
                .when(service).changePassword(any(), any());
        mvc.perform(post("/api/v2/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"Old123!x\",\"newPassword\":\"weak\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.errorCode").value("PASSWORD_POLICY_VIOLATION"));
    }
}
