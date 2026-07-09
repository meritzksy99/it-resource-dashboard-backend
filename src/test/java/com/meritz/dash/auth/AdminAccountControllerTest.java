package com.meritz.dash.auth;

import com.meritz.dash.common.GlobalExceptionHandler;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.config.WebConfig;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AdminAccountController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class AdminAccountControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthAdminService service;

    @Test void list_accounts_returns_data_array() throws Exception {
        when(service.listAccounts()).thenReturn(List.of(new AdminAccountRow(
                "9320", "홍길동", "00", "정상", 0, null, null, false, false)));
        mvc.perform(get("/api/v1/admin/accounts"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].empno").value("9320"))
           .andExpect(jsonPath("$.data[0].statusName").value("정상"));
    }

    @Test void unlock_ok() throws Exception {
        mvc.perform(post("/api/v1/admin/accounts/9320/unlock"))
           .andExpect(status().isOk());
        verify(service).unlock("9320");
    }

    @Test void reset_password_missing_returns_404() throws Exception {
        doThrow(new NotFoundException("없음")).when(service).resetPassword("NONE");
        mvc.perform(post("/api/v1/admin/accounts/NONE/reset-password"))
           .andExpect(status().isNotFound());
    }
}
