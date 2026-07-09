package com.meritz.dash.worksite;

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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = WorkSiteController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class WorkSiteControllerTest {

    @Autowired MockMvc mvc;
    @MockBean WorkSiteService workSiteService;

    @Test
    @DisplayName("GET /api/v1/work-sites → 200, data 배열(url/name/description) + meta.count")
    void get_work_sites_ok() throws Exception {
        when(workSiteService.getActiveSites()).thenReturn(List.of(
                new WorkSite("https://gw.example.co.kr", "그룹웨어", "전자결재·메일·게시판 통합 그룹웨어"),
                new WorkSite("https://itsm.example.co.kr", "ITSM SR관리", "SR 등록·진행 현황 관리 시스템")));

        mvc.perform(get("/api/v1/work-sites"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(2))
           .andExpect(jsonPath("$.data[0].url").value("https://gw.example.co.kr"))
           .andExpect(jsonPath("$.data[0].name").value("그룹웨어"))
           .andExpect(jsonPath("$.data[0].description").value("전자결재·메일·게시판 통합 그룹웨어"))
           .andExpect(jsonPath("$.meta.count").value(2));
    }

    @Test
    @DisplayName("DTO 경계 — 내부 컬럼(siteId/useYn/sortNo/감사컬럼) 미노출")
    void get_work_sites_hides_internal_fields() throws Exception {
        when(workSiteService.getActiveSites()).thenReturn(List.of(
                new WorkSite("https://gw.example.co.kr", "그룹웨어", null)));

        mvc.perform(get("/api/v1/work-sites"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].siteId").doesNotExist())
           .andExpect(jsonPath("$.data[0].useYn").doesNotExist())
           .andExpect(jsonPath("$.data[0].sortNo").doesNotExist())
           .andExpect(jsonPath("$.data[0].createdAt").doesNotExist())
           .andExpect(jsonPath("$.data[0].createdBy").doesNotExist())
           .andExpect(jsonPath("$.data[0].updatedAt").doesNotExist())
           .andExpect(jsonPath("$.data[0].updatedBy").doesNotExist());
    }

    @Test
    @DisplayName("사이트가 하나도 없으면 → 200, 빈 배열 + meta.count=0")
    void get_work_sites_empty() throws Exception {
        when(workSiteService.getActiveSites()).thenReturn(List.of());

        mvc.perform(get("/api/v1/work-sites"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.length()").value(0))
           .andExpect(jsonPath("$.meta.count").value(0));
    }
}
