package com.meritz.dash.auth;

import com.meritz.dash.common.GlobalExceptionHandler;
import com.meritz.dash.config.WebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class,
    excludeAutoConfiguration = {},
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthService authService;

    @Test
    void login_ok() throws Exception {
        when(authService.login(any())).thenReturn(
            new LoginResult("tok", "E0001", "01", "팀장", "김팀장", true));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"empno\":\"E0001\",\"password\":\"E0001\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.token").value("tok"))
           .andExpect(jsonPath("$.data.pwdResetRequired").value(true));
    }

    @Test
    void login_blank_empno_400() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"password\":\"x\"}"))
           .andExpect(status().isBadRequest());
    }
}
