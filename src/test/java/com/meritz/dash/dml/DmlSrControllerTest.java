package com.meritz.dash.dml;

import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.common.GlobalExceptionHandler;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = DmlSrController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class DmlSrControllerTest {

    @Autowired MockMvc mvc;
    @MockBean DmlSrService service;
    @MockBean DmlSyncService syncService;

    private static DmlSrItem item(String srNo, String checkYn, String improveYn) {
        return new DmlSrItem(srNo, "202607", "18", "자료정정", "장기보험금",
                "08", "계약자 정보 정정", "고객 요청에 따른 주소 정정", "Y",
                "김요청", "2139", "박실요청", "2735",
                "9320", "김성엽", "IT개발팀", "D101", "P01",
                "20260701", "20260710", "20260702",
                checkYn, improveYn, null, null, "N", null);
    }

    @Test
    @DisplayName("① GET /api/v1/dml-srs?baseYm=202607 → 200, data 배열 + meta{baseYm,scope,total,checkedCount,improveCount}")
    void overview_ok() throws Exception {
        when(service.overview(eq("202607"), isNull(), isNull()))
                .thenReturn(new DmlSrService.ListResult(
                        List.of(item("SR26000101", "Y", "N")), "all", 1, 1, 0));

        mvc.perform(get("/api/v1/dml-srs").param("baseYm", "202607"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").isArray())
           .andExpect(jsonPath("$.data[0].srNo").value("SR26000101"))
           .andExpect(jsonPath("$.data[0].checkYn").value("Y"))
           .andExpect(jsonPath("$.meta.baseYm").value("202607"))
           .andExpect(jsonPath("$.meta.scope").value("all"))
           .andExpect(jsonPath("$.meta.total").value(1))
           .andExpect(jsonPath("$.meta.checkedCount").value(1))
           .andExpect(jsonPath("$.meta.improveCount").value(0));
    }

    @Test
    @DisplayName("① GET baseYm 미지정 → 이번 달로 overview 호출, 200")
    void overview_defaults_baseYm_to_current_month() throws Exception {
        when(service.overview(anyString(), isNull(), isNull()))
                .thenReturn(new DmlSrService.ListResult(List.of(), "all", 0, 0, 0));

        mvc.perform(get("/api/v1/dml-srs"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").isArray())
           .andExpect(jsonPath("$.meta.total").value(0));

        verify(service).overview(Mockito.matches("\\d{6}"), isNull(), isNull());
    }

    @Test
    @DisplayName("② GET /api/v1/dml-srs/inspections → 200, inspections(baseYm, partCd) 호출")
    void inspections_ok() throws Exception {
        when(service.inspections(eq("202607"), isNull()))
                .thenReturn(new DmlSrService.ListResult(
                        List.of(item("SR26000101", "N", "N")), "part", 1, 0, 0));

        mvc.perform(get("/api/v1/dml-srs/inspections").param("baseYm", "202607"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].srNo").value("SR26000101"))
           .andExpect(jsonPath("$.meta.scope").value("part"));

        verify(service).inspections("202607", null);
    }

    @Test
    @DisplayName("③ GET /api/v1/dml-srs/improvements → 200, improvements(baseYm, partCd) 호출")
    void improvements_ok() throws Exception {
        when(service.improvements(eq("202607"), eq("P01")))
                .thenReturn(new DmlSrService.ListResult(
                        List.of(item("SR26000101", "Y", "Y")), "part", 1, 1, 1));

        mvc.perform(get("/api/v1/dml-srs/improvements")
                        .param("baseYm", "202607").param("partCd", "P01"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].improveYn").value("Y"))
           .andExpect(jsonPath("$.meta.improveCount").value(1));

        verify(service).improvements("202607", "P01");
    }

    @Test
    @DisplayName("PATCH /api/v1/dml-srs/{srNo}/check {checkYn:Y} → 200, setCheck 호출")
    void check_ok() throws Exception {
        mvc.perform(patch("/api/v1/dml-srs/SR26000101/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkYn\":\"Y\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.srNo").value("SR26000101"))
           .andExpect(jsonPath("$.data.checkYn").value("Y"));

        verify(service).setCheck("SR26000101", "Y");
    }

    @Test
    @DisplayName("PATCH /api/v1/dml-srs/{srNo}/improve-target {improveYn:Y} → 200, setImproveTarget 호출")
    void improve_target_ok() throws Exception {
        mvc.perform(patch("/api/v1/dml-srs/SR26000101/improve-target")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"improveYn\":\"Y\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.srNo").value("SR26000101"))
           .andExpect(jsonPath("$.data.improveYn").value("Y"));

        verify(service).setImproveTarget("SR26000101", "Y");
    }

    @Test
    @DisplayName("PUT /api/v1/dml-srs/{srNo}/improvement → 200, saveImprovement 호출")
    void improvement_ok() throws Exception {
        mvc.perform(put("/api/v1/dml-srs/SR26000101/improvement")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"improvePlan\":\"권한 점검 프로세스 개선\",\"planCmptDate\":\"20260731\"," +
                         "\"cmptYn\":\"N\",\"remark\":\"7월 내 완료 예정\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.srNo").value("SR26000101"))
           .andExpect(jsonPath("$.data.improveYn").value("Y"));

        verify(service).saveImprovement("SR26000101", "권한 점검 프로세스 개선", "20260731", "N", "7월 내 완료 예정");
    }

    @Test
    @DisplayName("스코프 밖 SR 점검 시도 → 403 ProblemDetail")
    void check_forbidden_returns_403_problem_detail() throws Exception {
        doThrow(new ForbiddenException("본인 파트의 SR 만 입력할 수 있습니다"))
                .when(service).setCheck(eq("SR26000999"), any());

        mvc.perform(patch("/api/v1/dml-srs/SR26000999/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkYn\":\"Y\"}"))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.status").value(403))
           .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("미존재 SR 점검 시도 → 404 ProblemDetail")
    void check_missing_sr_returns_404_problem_detail() throws Exception {
        doThrow(new NotFoundException("해당 SR을 찾을 수 없습니다: SRNONE"))
                .when(service).setCheck(eq("SRNONE"), any());

        mvc.perform(patch("/api/v1/dml-srs/SRNONE/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkYn\":\"Y\"}"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.status").value(404))
           .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("잘못된 checkYn(X) → 400 ProblemDetail (서비스 IllegalArgumentException)")
    void check_invalid_yn_returns_400() throws Exception {
        doThrow(new IllegalArgumentException("checkYn 은 'Y' 또는 'N' 이어야 합니다"))
                .when(service).setCheck(eq("SR26000101"), eq("X"));

        mvc.perform(patch("/api/v1/dml-srs/SR26000101/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkYn\":\"X\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/v1/dml-srs/sync?baseYm=202606 → 200, sync(baseYm, MANUAL) 호출")
    void sync_ok() throws Exception {
        when(syncService.sync("202606", "MANUAL"))
                .thenReturn(new DmlSyncService.SyncResult("202606", 3, 2));

        mvc.perform(post("/api/v1/dml-srs/sync").param("baseYm", "202606"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.baseYm").value("202606"))
           .andExpect(jsonPath("$.data.fetched").value(3))
           .andExpect(jsonPath("$.data.matched").value(2));
    }
}
