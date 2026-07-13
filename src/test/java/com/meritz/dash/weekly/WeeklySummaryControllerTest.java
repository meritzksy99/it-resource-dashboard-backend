package com.meritz.dash.weekly;

import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.common.GlobalExceptionHandler;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.config.WebConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** WeeklySummaryController 계약 테스트(@WebMvcTest) — TDD Red. */
@WebMvcTest(value = {WeeklySummaryController.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class WeeklySummaryControllerTest {

    @Autowired MockMvc mvc;
    @MockBean WeeklySummaryService service;

    private static WeeklySummary summary(Long id, String deptCd, String partCd) {
        return new WeeklySummary(id, "20260706", deptCd, partCd, "파트 취합 내용", "6002", null, null);
    }

    @Test
    @DisplayName("POST /api/v1/weekly-summaries → 항상 신규 201")
    void submit_returns_201() throws Exception {
        when(service.submit("20260706", "취합 내용", null, null, null))
                .thenReturn(summary(1L, "2735", "P12"));

        mvc.perform(post("/api/v1/weekly-summaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"week":"20260706","content":"취합 내용"}
                        """))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.sumId").value(1));
    }

    @Test
    @DisplayName("POST 같은 주·같은 파트 2회 제출 → 둘 다 201(다건 허용)")
    void submit_twice_both_return_201() throws Exception {
        when(service.submit("20260706", "취합 내용", null, null, null))
                .thenReturn(summary(1L, "2735", "P12"), summary(2L, "2735", "P12"));

        mvc.perform(post("/api/v1/weekly-summaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"week":"20260706","content":"취합 내용"}
                        """))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.sumId").value(1));

        mvc.perform(post("/api/v1/weekly-summaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"week":"20260706","content":"취합 내용"}
                        """))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.sumId").value(2));
    }

    @Test
    @DisplayName("POST ADMIN dept/part 미지정(서비스 IllegalArgumentException) → 400")
    void submit_admin_missing_dept_part_returns_400() throws Exception {
        doThrow(new IllegalArgumentException("dept/part 는 필수입니다"))
                .when(service).submit("20260706", "취합 내용", null, null, null);

        mvc.perform(post("/api/v1/weekly-summaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"week":"20260706","content":"취합 내용"}
                        """))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST rptIds 포함 → 201, 선택 보고 임베드(reports)")
    void submit_with_rpt_ids_returns_201_with_reports() throws Exception {
        when(service.submit("20260706", "취합 내용", List.of(11L, 12L), null, null))
                .thenReturn(new WeeklySummary(1L, "20260706", "2735", "P12", "취합 내용", "6002", null, null,
                        List.of(new WeeklySummary.Report(11L, "SR26000101", "SR 제목", "7451",
                                        "개인 보고 내용", "20260710", "20260710", null, null),
                                new WeeklySummary.Report(12L, "SR26000102", "SR 제목2", "7452",
                                        "개인 보고 내용2", "20260710", "20260710", null, null))));

        mvc.perform(post("/api/v1/weekly-summaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"week":"20260706","content":"취합 내용","rptIds":[11,12]}
                        """))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.sumId").value(1))
           .andExpect(jsonPath("$.data.reports.length()").value(2))
           .andExpect(jsonPath("$.data.reports[0].rptId").value(11))
           .andExpect(jsonPath("$.data.reports[0].srNo").value("SR26000101"));
    }

    @Test
    @DisplayName("POST rptIds 검증 실패(서비스 IllegalArgumentException) → 400")
    void submit_with_invalid_rpt_ids_returns_400() throws Exception {
        doThrow(new IllegalArgumentException("존재하지 않는 개인 보고입니다: 99"))
                .when(service).submit("20260706", "취합 내용", List.of(99L), null, null);

        mvc.perform(post("/api/v1/weekly-summaries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"week":"20260706","content":"취합 내용","rptIds":[99]}
                        """))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/weekly-summaries?week= 필수 파라미터 누락 → 400")
    void list_missing_week_returns_400() throws Exception {
        mvc.perform(get("/api/v1/weekly-summaries"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/weekly-summaries?week= → 200, envelope 확인")
    void list_ok() throws Exception {
        when(service.list("20260706", null))
                .thenReturn(new WeeklySummaryService.ListResult(List.of(summary(1L, "2735", "P12")), "part"));

        mvc.perform(get("/api/v1/weekly-summaries").param("week", "20260706"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").isArray())
           .andExpect(jsonPath("$.data[0].reports").isArray())     // 링크 없으면 빈 배열
           .andExpect(jsonPath("$.data[0].reports").isEmpty())
           .andExpect(jsonPath("$.meta.scope").value("part"))
           .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/weekly-summaries?week= → 각 취합본에 선택 보고 임베드")
    void list_embeds_selected_reports() throws Exception {
        WeeklySummary withReports = new WeeklySummary(1L, "20260706", "2735", "P12", "취합", "6002", null, null,
                List.of(new WeeklySummary.Report(11L, "SR26000101", "SR 제목", "7451",
                        "개인 보고 내용", "20260710", "20260711", "지연사유", "리더의견")));
        when(service.list("20260706", null))
                .thenReturn(new WeeklySummaryService.ListResult(List.of(withReports), "part"));

        mvc.perform(get("/api/v1/weekly-summaries").param("week", "20260706"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].reports[0].rptId").value(11))
           .andExpect(jsonPath("$.data[0].reports[0].srNo").value("SR26000101"))
           .andExpect(jsonPath("$.data[0].reports[0].regEmpno").value("7451"))
           .andExpect(jsonPath("$.data[0].reports[0].leaderCmt").value("리더의견"));
    }

    @Test
    @DisplayName("PUT /api/v1/weekly-summaries/{id} 본문 수정 → 200 (rptIds 미전송=링크 불변)")
    void update_ok() throws Exception {
        when(service.update(1L, "수정 본문", null)).thenReturn(summary(1L, "2735", "P12"));

        mvc.perform(put("/api/v1/weekly-summaries/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"content":"수정 본문"}
                        """))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.sumId").value(1));

        verify(service).update(1L, "수정 본문", null);
    }

    @Test
    @DisplayName("PUT /api/v1/weekly-summaries/{id} rptIds 교체 → 200, 서비스에 rptIds 전달")
    void update_with_rpt_ids_ok() throws Exception {
        when(service.update(1L, "수정 본문", List.of(11L, 12L))).thenReturn(summary(1L, "2735", "P12"));

        mvc.perform(put("/api/v1/weekly-summaries/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"content":"수정 본문","rptIds":[11,12]}
                        """))
           .andExpect(status().isOk());

        verify(service).update(1L, "수정 본문", List.of(11L, 12L));
    }

    @Test
    @DisplayName("PUT /api/v1/weekly-summaries/{id} rptIds 빈 배열 → 링크 전부 해제 요청으로 전달")
    void update_with_empty_rpt_ids_ok() throws Exception {
        when(service.update(1L, null, List.of())).thenReturn(summary(1L, "2735", "P12"));

        mvc.perform(put("/api/v1/weekly-summaries/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"rptIds":[]}
                        """))
           .andExpect(status().isOk());

        verify(service).update(1L, null, List.of());
    }

    @Test
    @DisplayName("PUT /api/v1/weekly-summaries/{id} 타 파트 02(서비스 ForbiddenException) → 403")
    void update_other_part_forbidden() throws Exception {
        doThrow(new ForbiddenException("본인 파트의 취합본만 수정할 수 있습니다"))
                .when(service).update(9L, "수정", null);

        mvc.perform(put("/api/v1/weekly-summaries/9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"content":"수정"}
                        """))
           .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/v1/weekly-summaries/{id} 미존재(서비스 NotFoundException) → 404")
    void update_missing_returns_404() throws Exception {
        doThrow(new NotFoundException("해당 파트 취합본을 찾을 수 없습니다: 999"))
                .when(service).update(999L, "수정", null);

        mvc.perform(put("/api/v1/weekly-summaries/999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"content":"수정"}
                        """))
           .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/weekly-summaries/{id} → 204")
    void delete_ok() throws Exception {
        mvc.perform(delete("/api/v1/weekly-summaries/1"))
           .andExpect(status().isNoContent());

        verify(service).delete(1L);
    }

    @Test
    @DisplayName("DELETE /api/v1/weekly-summaries/{id} 타 파트 02(서비스 ForbiddenException) → 403")
    void delete_other_part_forbidden() throws Exception {
        doThrow(new ForbiddenException("본인 파트의 취합본만 삭제할 수 있습니다"))
                .when(service).delete(9L);

        mvc.perform(delete("/api/v1/weekly-summaries/9"))
           .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/v1/weekly-summaries/{id} 미존재(서비스 NotFoundException) → 404")
    void delete_missing_returns_404() throws Exception {
        doThrow(new NotFoundException("해당 파트 취합본을 찾을 수 없습니다: 999"))
                .when(service).delete(999L);

        mvc.perform(delete("/api/v1/weekly-summaries/999"))
           .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/v1/weekly-summaries/{id}/final-comment → 200")
    void final_comment_ok() throws Exception {
        when(service.finalComment(1L, "수고했습니다")).thenReturn(summary(1L, "2139", "P01"));

        mvc.perform(put("/api/v1/weekly-summaries/1/final-comment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"comment":"수고했습니다"}
                        """))
           .andExpect(status().isOk());

        verify(service).finalComment(1L, "수고했습니다");
    }

    @Test
    @DisplayName("PUT /api/v1/weekly-summaries/{id}/final-comment 타 부서 01 → 403")
    void final_comment_other_dept_forbidden() throws Exception {
        doThrow(new ForbiddenException("본인 부서만 최종의견을 남길 수 있습니다"))
                .when(service).finalComment(9L, "의견");

        mvc.perform(put("/api/v1/weekly-summaries/9/final-comment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"comment":"의견"}
                        """))
           .andExpect(status().isForbidden());
    }
}
