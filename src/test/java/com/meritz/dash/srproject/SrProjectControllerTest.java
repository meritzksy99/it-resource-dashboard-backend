package com.meritz.dash.srproject;

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

import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = SrProjectController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class SrProjectControllerTest {
    @Autowired MockMvc mvc;
    @MockBean SrProjectService service;

    @Test void ok() throws Exception {
        when(service.top(eq("202605"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new SrProjectService.Page(List.of(
                new SrProjectView("SR1","제목","1","개발요청",1.2,2,"D101","D101")), 1));
        mvc.perform(get("/api/v1/sr-projects").param("period","202605"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].srNo").value("SR1"))
           .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test void missing_period() throws Exception {
        mvc.perform(get("/api/v1/sr-projects"))
           .andExpect(status().isBadRequest());
    }

    @Test void very_large_page_returns_empty_without_overflow() throws Exception {
        // page=2147483647 (Integer.MAX_VALUE) → offset 오버플로 없이 빈 목록
        when(service.top(eq("202605"), any(), any(), eq(2147483647), eq(5)))
            .thenReturn(new SrProjectService.Page(List.of(), 1));
        mvc.perform(get("/api/v1/sr-projects")
                .param("period", "202605")
                .param("page", "2147483647"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").isEmpty());
    }
}
