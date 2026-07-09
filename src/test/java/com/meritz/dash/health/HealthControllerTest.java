package com.meritz.dash.health;

import com.meritz.dash.common.GlobalExceptionHandler;
import com.meritz.dash.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = HealthController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class HealthControllerTest {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("GET /api/v1/health → data.status=UP")
    void health_returns_up() throws Exception {
        mvc.perform(get("/api/v1/health"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.status").value("UP"))
           .andExpect(jsonPath("$.data.timestamp").exists());
    }
}
