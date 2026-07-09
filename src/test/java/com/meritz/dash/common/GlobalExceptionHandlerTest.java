package com.meritz.dash.common;

import com.meritz.dash.config.WebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.BoomController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = WebConfig.class
        )
)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.BoomController.class})
class GlobalExceptionHandlerTest {

    @RestController
    static class BoomController {
        @GetMapping("/test/boom")
        public String boom() {
            throw new RuntimeException("DB 연결 실패 — 내부 에러");
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void unhandled_exception_returns_500_with_generic_message() throws Exception {
        mvc.perform(get("/test/boom"))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.detail").value("서버 내부 오류가 발생했습니다"))
           .andExpect(result -> {
               String body = result.getResponse().getContentAsString();
               assertThat(body).doesNotContain("DB 연결 실패");
               assertThat(body).doesNotContain("내부 에러");
           });
    }
}
