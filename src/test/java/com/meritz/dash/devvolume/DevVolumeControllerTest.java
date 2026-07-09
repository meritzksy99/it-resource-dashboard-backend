package com.meritz.dash.devvolume;

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

@WebMvcTest(value = DevVolumeController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class DevVolumeControllerTest {
    @Autowired MockMvc mvc;
    @MockBean DevVolumeService service;

    @Test void unit_all_ok() throws Exception {
        when(service.series(eq("all"), eq("6m"), isNull()))
            .thenReturn(List.of(new DevVolumePoint("202605", "26.05", "01", "개발요청", 3, 2.5)));
        mvc.perform(get("/api/v1/dev-volume").param("unit", "all").param("period", "6m"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].monthLabel").value("26.05"))
           .andExpect(jsonPath("$.data[0].srClsName").value("개발요청"))
           .andExpect(jsonPath("$.data[0].srCnt").value(3))
           .andExpect(jsonPath("$.data[0].jobMm").value(2.5));
    }

    @Test void unit_dept_ok() throws Exception {
        when(service.series(eq("dept"), eq("6m"), eq("D101")))
            .thenReturn(List.of(new DevVolumePoint("202605", "26.05", "01", "개발요청", 2, 1.2)));
        mvc.perform(get("/api/v1/dev-volume")
                .param("unit", "dept").param("period", "6m").param("unitId", "D101"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].srCnt").value(2));
    }

    @Test void unit_part_ok() throws Exception {
        when(service.series(eq("part"), eq("6m"), eq("D101-P01")))
            .thenReturn(List.of(new DevVolumePoint("202605", "26.05", "01", "개발요청", 1, 0.8)));
        mvc.perform(get("/api/v1/dev-volume")
                .param("unit", "part").param("period", "6m").param("unitId", "D101-P01"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].srCnt").value(1));
    }

    @Test void unit_dev_ok() throws Exception {
        when(service.series(eq("dev"), eq("6m"), eq("E0002")))
            .thenReturn(List.of(new DevVolumePoint("202605", "26.05", "01", "개발요청", 1, 0.5)));
        mvc.perform(get("/api/v1/dev-volume")
                .param("unit", "dev").param("period", "6m").param("unitId", "E0002"))
           .andExpect(status().isOk());
    }

    @Test void dept_missing_unitId_returns_400() throws Exception {
        when(service.series(eq("dept"), any(), isNull()))
            .thenThrow(new IllegalArgumentException("dept/part/dev 조회에는 unitId가 필요합니다"));
        mvc.perform(get("/api/v1/dev-volume").param("unit", "dept"))
           .andExpect(status().isBadRequest());
    }

    @Test void bad_unit_returns_400() throws Exception {
        when(service.series(eq("bad"), any(), any()))
            .thenThrow(new IllegalArgumentException("unit은 all|dept|part|dev"));
        mvc.perform(get("/api/v1/dev-volume").param("unit", "bad"))
           .andExpect(status().isBadRequest());
    }

    @Test void unit_param_omitted_defaults_to_all_returns_200() throws Exception {
        when(service.series(eq("all"), eq("6m"), isNull()))
            .thenReturn(List.of(new DevVolumePoint("202605", "26.05", "01", "개발요청", 3, 2.5)));
        mvc.perform(get("/api/v1/dev-volume"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].srCnt").value(3));
    }
}
