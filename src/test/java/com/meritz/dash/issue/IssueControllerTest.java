package com.meritz.dash.issue;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IssueController 계약 테스트. WebConfig(인증 인터셉터) 제외, service 는 MockBean 으로 delegation 만 검증한다.
 */
@WebMvcTest(value = IssueController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class IssueControllerTest {

    @Autowired MockMvc mvc;
    @MockBean IssueService service;

    private static IssueItem item(long id, boolean hasImage) {
        return new IssueItem(id, "SCRN001", "7451", "버튼이 안 눌려요", hasImage, "OPEN", "MEDIUM", null,
                LocalDateTime.now(), "7451", null, null);
    }

    private static IssueDetail detail(long id) {
        return new IssueDetail(id, "SCRN001", "7451", "버튼이 안 눌려요", "shot.png", "image/png", true,
                "OPEN", "MEDIUM", null, LocalDateTime.now(), "7451", null, null, 2L);
    }

    private static IssueComment comment(long cmtId, long issueId) {
        return new IssueComment(cmtId, issueId, "7451", "확인했습니다", LocalDateTime.now(), "7451", null, null);
    }

    // ── 목록 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/issues → 200, data 배열 + meta.total, hasImage 포함")
    void list_ok() throws Exception {
        when(service.list(isNull(), isNull())).thenReturn(List.of(item(1L, true), item(2L, false)));

        mvc.perform(get("/api/v1/issues"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").isArray())
           .andExpect(jsonPath("$.data[0].issueId").value(1))
           .andExpect(jsonPath("$.data[0].hasImage").value(true))
           .andExpect(jsonPath("$.data[1].hasImage").value(false))
           .andExpect(jsonPath("$.meta.total").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/issues?status=OPEN,IN_PROGRESS → 콤마 다중값이 service.list 로 전달")
    void list_status_filter_parsed() throws Exception {
        when(service.list(eq(List.of("OPEN", "IN_PROGRESS")), isNull())).thenReturn(List.of());

        mvc.perform(get("/api/v1/issues").param("status", "OPEN,IN_PROGRESS"))
           .andExpect(status().isOk());

        verify(service).list(List.of("OPEN", "IN_PROGRESS"), null);
    }

    @Test
    @DisplayName("GET /api/v1/issues?status=BOGUS → 화이트리스트 외, service 가 400 던지면 ProblemDetail 400")
    void list_status_whitelist_violation_returns_400() throws Exception {
        doThrow(new IllegalArgumentException("status 는 OPEN,IN_PROGRESS,RESOLVED,CLOSED 중 하나여야 합니다"))
                .when(service).list(eq(List.of("BOGUS")), isNull());

        mvc.perform(get("/api/v1/issues").param("status", "BOGUS"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400));
    }

    // ── 상세 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/issues/{id} → 200, hasImage/commentCount 포함")
    void get_ok() throws Exception {
        when(service.get(1L)).thenReturn(detail(1L));

        mvc.perform(get("/api/v1/issues/1"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.issueId").value(1))
           .andExpect(jsonPath("$.data.hasImage").value(true))
           .andExpect(jsonPath("$.data.commentCount").value(2));
    }

    @Test
    @DisplayName("GET /api/v1/issues/{id} 존재하지 않는 이슈 → 404 ProblemDetail")
    void get_missing_returns_404() throws Exception {
        doThrow(new NotFoundException("해당 이슈를 찾을 수 없습니다: 999"))
                .when(service).get(999L);

        mvc.perform(get("/api/v1/issues/999"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.status").value(404));
    }

    // ── 이미지 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/issues/{id}/image → 200, 저장 Content-Type + nosniff + attachment(정규화 확장자) 헤더")
    void image_ok_with_security_headers() throws Exception {
        byte[] bytes = {1, 2, 3, 4};
        when(service.getImage(1L)).thenReturn(new IssueImage(bytes, "image/png"));

        mvc.perform(get("/api/v1/issues/1/image"))
           .andExpect(status().isOk())
           .andExpect(content().contentType(MediaType.IMAGE_PNG))
           .andExpect(header().string("X-Content-Type-Options", "nosniff"))
           .andExpect(header().string("Content-Disposition", "attachment; filename=\"issue-1.png\""))
           .andExpect(content().bytes(bytes));
    }

    @Test
    @DisplayName("GET /api/v1/issues/{id}/image JPEG → Content-Disposition 확장자 jpg(정규화 CT 에서 유도)")
    void image_jpeg_attachment_extension() throws Exception {
        when(service.getImage(2L)).thenReturn(new IssueImage(new byte[]{9}, "image/jpeg"));

        mvc.perform(get("/api/v1/issues/2/image"))
           .andExpect(status().isOk())
           .andExpect(content().contentType(MediaType.IMAGE_JPEG))
           .andExpect(header().string("Content-Disposition", "attachment; filename=\"issue-2.jpg\""));
    }

    @Test
    @DisplayName("GET /api/v1/issues/{id}/image 이미지 없음 → 404 ProblemDetail")
    void image_missing_returns_404() throws Exception {
        doThrow(new NotFoundException("이미지가 없습니다: 1")).when(service).getImage(1L);

        mvc.perform(get("/api/v1/issues/1/image"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.status").value(404));
    }

    // ── 등록 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/issues (multipart, 이미지 포함) → 201 + 생성 이슈")
    void create_multipart_with_image_returns_201() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "shot.png", "image/png", new byte[]{1, 2, 3});
        when(service.create(eq("SCRN001"), eq("버튼이 안 눌려요"), isNull(), any())).thenReturn(detail(10L));

        mvc.perform(multipart("/api/v1/issues")
                        .file(image)
                        .param("screenId", "SCRN001")
                        .param("content", "버튼이 안 눌려요"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.issueId").value(10));
    }

    @Test
    @DisplayName("POST /api/v1/issues (multipart, 이미지 없음) → 201")
    void create_multipart_without_image_returns_201() throws Exception {
        when(service.create(eq("SCRN002"), eq("오류 재현됨"), eq("HIGH"), isNull())).thenReturn(detail(11L));

        mvc.perform(multipart("/api/v1/issues")
                        .param("screenId", "SCRN002")
                        .param("content", "오류 재현됨")
                        .param("priority", "HIGH"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.issueId").value(11));
    }

    @Test
    @DisplayName("POST /api/v1/issues 이미지 Content-Type 이 image/* 아님 → service 400 던지면 ProblemDetail 400")
    void create_non_image_content_type_returns_400() throws Exception {
        MockMultipartFile notImage = new MockMultipartFile("image", "note.txt", "text/plain", "hi".getBytes());
        doThrow(new IllegalArgumentException("image 는 image/* Content-Type 이어야 합니다"))
                .when(service).create(anyString(), anyString(), any(), any());

        mvc.perform(multipart("/api/v1/issues")
                        .file(notImage)
                        .param("screenId", "SCRN001")
                        .param("content", "설명"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400));
    }

    // ── 수정 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT /api/v1/issues/{id} → 200, 수정 결과 반환")
    void update_ok() throws Exception {
        when(service.update(eq(1L), any())).thenReturn(detail(1L));

        mvc.perform(put("/api/v1/issues/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.issueId").value(1));
    }

    @Test
    @DisplayName("PUT /api/v1/issues/{id} RESOLVED인데 해결내용 없음 → service 400 던지면 ProblemDetail 400")
    void update_resolved_without_content_returns_400() throws Exception {
        doThrow(new IllegalArgumentException("RESOLVED 로 변경하려면 해결내용이 필요합니다"))
                .when(service).update(eq(1L), any());

        mvc.perform(put("/api/v1/issues/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RESOLVED\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PUT /api/v1/issues/{id} 존재하지 않는 이슈 → 404 ProblemDetail")
    void update_missing_returns_404() throws Exception {
        doThrow(new NotFoundException("해당 이슈를 찾을 수 없습니다: 999"))
                .when(service).update(eq(999L), any());

        mvc.perform(put("/api/v1/issues/999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"수정\"}"))
           .andExpect(status().isNotFound());
    }

    // ── 삭제 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/issues/{id} 작성자/ADMIN → 204")
    void delete_ok_returns_204() throws Exception {
        mvc.perform(delete("/api/v1/issues/1"))
           .andExpect(status().isNoContent());

        verify(service).delete(1L);
    }

    @Test
    @DisplayName("DELETE /api/v1/issues/{id} 작성자도 ADMIN도 아님 → 403 ProblemDetail")
    void delete_forbidden_returns_403() throws Exception {
        doThrow(new ForbiddenException("작성자 또는 ADMIN 만 삭제할 수 있습니다")).when(service).delete(1L);

        mvc.perform(delete("/api/v1/issues/1"))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("DELETE /api/v1/issues/{id} 존재하지 않는 이슈 → 404 ProblemDetail")
    void delete_missing_returns_404() throws Exception {
        doThrow(new NotFoundException("해당 이슈를 찾을 수 없습니다: 999")).when(service).delete(999L);

        mvc.perform(delete("/api/v1/issues/999"))
           .andExpect(status().isNotFound());
    }

    // ── 댓글 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/issues/{id}/comments → 200, 오름차순 목록")
    void comments_list_ok() throws Exception {
        when(service.listComments(1L)).thenReturn(List.of(comment(1L, 1L), comment(2L, 1L)));

        mvc.perform(get("/api/v1/issues/1/comments"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data").isArray())
           .andExpect(jsonPath("$.data[0].cmtId").value(1))
           .andExpect(jsonPath("$.data[1].cmtId").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/issues/{id}/comments → 201")
    void comment_create_returns_201() throws Exception {
        when(service.addComment(eq(1L), eq("확인했습니다"))).thenReturn(comment(5L, 1L));

        mvc.perform(post("/api/v1/issues/1/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"확인했습니다\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.cmtId").value(5));
    }

    @Test
    @DisplayName("POST /api/v1/issues/{id}/comments 이슈 미존재 → 404 ProblemDetail")
    void comment_create_missing_issue_returns_404() throws Exception {
        doThrow(new NotFoundException("해당 이슈를 찾을 수 없습니다: 999"))
                .when(service).addComment(eq(999L), anyString());

        mvc.perform(post("/api/v1/issues/999/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"테스트\"}"))
           .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/v1/issues/{id}/comments/{commentId} → 200")
    void comment_update_ok() throws Exception {
        when(service.updateComment(eq(1L), eq(5L), eq("수정된 댓글"))).thenReturn(comment(5L, 1L));

        mvc.perform(put("/api/v1/issues/1/comments/5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"수정된 댓글\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.cmtId").value(5));
    }

    @Test
    @DisplayName("PUT /api/v1/issues/{id}/comments/{commentId} 작성자/ADMIN 아님 → 403")
    void comment_update_forbidden_returns_403() throws Exception {
        doThrow(new ForbiddenException("댓글 작성자 또는 ADMIN 만 수정할 수 있습니다"))
                .when(service).updateComment(eq(1L), eq(5L), anyString());

        mvc.perform(put("/api/v1/issues/1/comments/5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"수정 시도\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/v1/issues/{id}/comments/{commentId} → 204")
    void comment_delete_ok_returns_204() throws Exception {
        mvc.perform(delete("/api/v1/issues/1/comments/5"))
           .andExpect(status().isNoContent());

        verify(service).deleteComment(1L, 5L);
    }

    @Test
    @DisplayName("DELETE /api/v1/issues/{id}/comments/{commentId} 작성자/ADMIN 아님 → 403")
    void comment_delete_forbidden_returns_403() throws Exception {
        doThrow(new ForbiddenException("댓글 작성자 또는 ADMIN 만 삭제할 수 있습니다"))
                .when(service).deleteComment(eq(1L), eq(5L));

        mvc.perform(delete("/api/v1/issues/1/comments/5"))
           .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/v1/issues/{id}/comments/{commentId} 존재하지 않는 댓글 → 404")
    void comment_delete_missing_returns_404() throws Exception {
        doThrow(new NotFoundException("해당 댓글을 찾을 수 없습니다: 5"))
                .when(service).deleteComment(eq(1L), eq(5L));

        mvc.perform(delete("/api/v1/issues/1/comments/5"))
           .andExpect(status().isNotFound());
    }
}
