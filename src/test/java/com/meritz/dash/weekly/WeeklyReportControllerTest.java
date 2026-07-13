package com.meritz.dash.weekly;

import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.common.ConflictException;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WeeklyReportController 계약 테스트(@WebMvcTest) — TDD Red.
 * <p>
 * 서비스는 {@code @MockBean} 이므로 아래 성공/에러 경로 테스트 자체는 지금 당장 통과할 수 있다
 * (컨트롤러는 이미 구현됨). 단, 409(ConflictException) 케이스는 실측상 GlobalExceptionHandler 의
 * {@code @ExceptionHandler(Exception.class)} catch-all 이 먼저 매칭되어 500 이 반환되므로 Red 로 남는다
 * (구현체 담당자는 GlobalExceptionHandler 를 건드리지 않고 이 문제를 해결할 방법을 찾아야 한다).
 */
@WebMvcTest(value = {WeeklyReportController.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class WeeklyReportControllerTest {

    @Autowired MockMvc mvc;
    @MockBean WeeklyReportService service;

    private static WeeklyReport report(Long id) {
        return new WeeklyReport(id, "20260706", "SR26000101", "증권 잔고 정정", "7451", "2139", "P01",
                "정정 완료", "20260710", "20260710", null, null);
    }

    @Test
    @DisplayName("POST /api/v1/weekly-reports → 201, envelope data 확인")
    void create_ok() throws Exception {
        when(service.create("20260706", "SR26000101", "정정 완료", "20260710", null))
                .thenReturn(report(1L));

        mvc.perform(post("/api/v1/weekly-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"week":"20260706","srNo":"SR26000101","content":"정정 완료","planDate":"20260710"}
                        """))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.rptId").value(1))
           .andExpect(jsonPath("$.data.srNo").value("SR26000101"));
    }

    @Test
    @DisplayName("POST 반영예정일이 SR 예정일과 달라도 지연사유 없이 → 201(지연사유는 항상 선택)")
    void create_without_delay_reason_returns_201() throws Exception {
        when(service.create("20260706", "SR26000101", "내용", "20260720", null))
                .thenReturn(new WeeklyReport(2L, "20260706", "SR26000101", "증권 잔고 정정", "7451",
                        "2139", "P01", "내용", "20260720", "20260710", null, null));

        mvc.perform(post("/api/v1/weekly-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"week":"20260706","srNo":"SR26000101","content":"내용","planDate":"20260720"}
                        """))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.rptId").value(2))
           .andExpect(jsonPath("$.data.delayRsn").doesNotExist());
    }

    @Test
    @DisplayName("POST SR 미존재(서비스 IllegalArgumentException) → 400")
    void create_sr_not_found_returns_400() throws Exception {
        doThrow(new IllegalArgumentException("SR 없음"))
                .when(service).create(eq("20260706"), eq("SRNONE"), eq("내용"), eq("20260710"), eq(null));

        mvc.perform(post("/api/v1/weekly-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"week":"20260706","srNo":"SRNONE","content":"내용","planDate":"20260710"}
                        """))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST 중복 등록(서비스 ConflictException) → 409")
    void create_duplicate_returns_409() throws Exception {
        doThrow(new ConflictException("이미 등록된 주간보고입니다"))
                .when(service).create(eq("20260706"), eq("SR26000101"), eq("내용"), eq("20260710"), eq(null));

        mvc.perform(post("/api/v1/weekly-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"week":"20260706","srNo":"SR26000101","content":"내용","planDate":"20260710"}
                        """))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("GET /api/v1/weekly-reports?week= 필수 파라미터 누락 → 400")
    void list_missing_week_returns_400() throws Exception {
        mvc.perform(get("/api/v1/weekly-reports"))
           .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/weekly-reports?week= → 200, envelope data/meta 확인")
    void list_ok() throws Exception {
        when(service.list("20260706", null, null))
                .thenReturn(new WeeklyReportService.ListResult(List.of(report(1L)), "self"));

        mvc.perform(get("/api/v1/weekly-reports").param("week", "20260706"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").isArray())
           .andExpect(jsonPath("$.data[0].srNo").value("SR26000101"))
           .andExpect(jsonPath("$.meta.week").value("20260706"))
           .andExpect(jsonPath("$.meta.scope").value("self"))
           .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/weekly-reports/{id} → 200")
    void get_ok() throws Exception {
        when(service.get(1L)).thenReturn(report(1L));

        mvc.perform(get("/api/v1/weekly-reports/1"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.rptId").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/weekly-reports/{id} 스코프 밖 → 403 ProblemDetail")
    void get_forbidden_returns_403() throws Exception {
        doThrow(new ForbiddenException("스코프 밖 주간보고입니다")).when(service).get(9L);

        mvc.perform(get("/api/v1/weekly-reports/9"))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("GET /api/v1/weekly-reports/{id} 미존재 → 404 ProblemDetail")
    void get_missing_returns_404() throws Exception {
        doThrow(new NotFoundException("해당 주간보고를 찾을 수 없습니다: 999")).when(service).get(999L);

        mvc.perform(get("/api/v1/weekly-reports/999"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("PUT /api/v1/weekly-reports/{id} → 200, update 위임")
    void update_ok() throws Exception {
        when(service.update(1L, "수정 내용", null, null)).thenReturn(report(1L));

        mvc.perform(put("/api/v1/weekly-reports/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"content":"수정 내용"}
                        """))
           .andExpect(status().isOk());

        verify(service).update(1L, "수정 내용", null, null);
    }

    @Test
    @DisplayName("PUT /api/v1/weekly-reports/{id} 타파트 02 → 403")
    void update_forbidden_returns_403() throws Exception {
        doThrow(new ForbiddenException("본인 파트만 수정할 수 있습니다"))
                .when(service).update(eq(9L), eq("수정"), eq(null), eq(null));

        mvc.perform(put("/api/v1/weekly-reports/9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"content":"수정"}
                        """))
           .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/v1/weekly-reports/{id}/leader-comment → 200, leaderComment 위임")
    void leader_comment_ok() throws Exception {
        when(service.leaderComment(1L, "고생했습니다")).thenReturn(report(1L));

        mvc.perform(put("/api/v1/weekly-reports/1/leader-comment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"comment":"고생했습니다"}
                        """))
           .andExpect(status().isOk());

        verify(service).leaderComment(1L, "고생했습니다");
    }

    @Test
    @DisplayName("DELETE /api/v1/weekly-reports/{id} → 204")
    void delete_ok() throws Exception {
        mvc.perform(delete("/api/v1/weekly-reports/1"))
           .andExpect(status().isNoContent());

        verify(service).delete(1L);
    }

    @Test
    @DisplayName("DELETE /api/v1/weekly-reports/{id} 작성자/ADMIN 아님 → 403")
    void delete_forbidden_returns_403() throws Exception {
        doThrow(new ForbiddenException("작성자 또는 ADMIN 만 삭제할 수 있습니다")).when(service).delete(9L);

        mvc.perform(delete("/api/v1/weekly-reports/9"))
           .andExpect(status().isForbidden());
    }
}
