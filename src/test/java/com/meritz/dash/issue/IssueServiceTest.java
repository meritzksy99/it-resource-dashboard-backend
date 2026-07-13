package com.meritz.dash.issue;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.mapper.app.IssueMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IssueService 단위 테스트(Mockito). 권한 분기, RESOLVED 규칙(병합 후 유효 상태 기준),
 * 이미지 매직넘버 화이트리스트 검증, status 필터 파싱을 고정한다.
 */
class IssueServiceTest {

    private IssueMapper mapper;
    private IssueService service;

    @BeforeEach
    void setup() {
        mapper = mock(IssueMapper.class);
        service = new IssueService(mapper);
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    private static IssueDetail detail(long id, String regEmpno, String statCd, String rslvCntt) {
        return new IssueDetail(id, "SCRN001", regEmpno, "오류설명", null, null, false,
                statCd, "MEDIUM", rslvCntt, LocalDateTime.now(), regEmpno, null, null, 0L);
    }

    /** 유효 PNG 매직넘버(89 50 4E 47 0D 0A 1A 0A) + 더미 페이로드. */
    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    }

    /** 유효 JPEG 매직넘버(FF D8 FF) + 더미 페이로드. */
    private static byte[] jpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    }

    private static IssueComment comment(long cmtId, long issueId, String regEmpno) {
        return new IssueComment(cmtId, issueId, regEmpno, "댓글", LocalDateTime.now(), regEmpno, null, null);
    }

    // ── 목록: status 화이트리스트 파싱 ─────────────────────────────────

    @Test
    @DisplayName("list: 화이트리스트 내 status 다중값 → mapper.selectList 그대로 전달")
    void list_valid_status_filter() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectList(any(), any())).thenReturn(List.of());

        service.list(List.of("OPEN", "IN_PROGRESS"), "SCRN001");

        verify(mapper).selectList(List.of("OPEN", "IN_PROGRESS"), "SCRN001");
    }

    @Test
    @DisplayName("list: null statuses → 전체 조회(필터 없음)")
    void list_null_status_means_all() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectList(any(), any())).thenReturn(List.of());

        service.list(null, null);

        verify(mapper).selectList(null, null);
    }

    @Test
    @DisplayName("list: 화이트리스트 외 status(BOGUS) → IllegalArgumentException, mapper 미호출")
    void list_invalid_status_rejected() {
        AuthContext.set("7451", "03", null, null);

        assertThatThrownBy(() -> service.list(List.of("BOGUS"), null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).selectList(any(), any());
    }

    // ── 등록: 이미지 매직넘버 화이트리스트 검증(저장형 XSS 차단) ────────

    @Test
    @DisplayName("create: Content-Type 은 image/png 인데 실제 바이트가 텍스트 → IllegalArgumentException, mapper.insert 미호출")
    void create_rejects_declared_png_with_text_bytes() {
        AuthContext.set("7451", "03", null, null);
        MockMultipartFile fake = new MockMultipartFile("image", "shot.png", "image/png", "hi".getBytes());

        assertThatThrownBy(() -> service.create("SCRN001", "오류설명", null, fake))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("create: SVG 바이트(image/svg+xml, 스크립트 실행 가능) → IllegalArgumentException, mapper.insert 미호출")
    void create_rejects_svg_bytes() {
        AuthContext.set("7451", "03", null, null);
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes();
        MockMultipartFile svgFile = new MockMultipartFile("image", "shot.svg", "image/svg+xml", svg);

        assertThatThrownBy(() -> service.create("SCRN001", "오류설명", null, svgFile))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("create: 유효 PNG 바이트 → 정상 저장, 작성자=empno, priority 미지정시 MEDIUM, FILE_CTYPE=image/png")
    void create_accepts_valid_png_bytes_and_defaults_priority() {
        AuthContext.set("7451", "03", null, null);
        MockMultipartFile image = new MockMultipartFile("image", "shot.png", "image/png", pngBytes());

        service.create("SCRN001", "오류설명", null, image);

        ArgumentCaptor<IssueRow> captor = ArgumentCaptor.forClass(IssueRow.class);
        verify(mapper).insert(captor.capture());
        IssueRow row = captor.getValue();
        assertThat(row.getScreenId()).isEqualTo("SCRN001");
        assertThat(row.getRegEmpno()).isEqualTo("7451");
        assertThat(row.getErrCntt()).isEqualTo("오류설명");
        assertThat(row.getPrirCd()).isEqualTo("MEDIUM");
        assertThat(row.getFileData()).isEqualTo(pngBytes());
        assertThat(row.getFileCtype()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("create: 저장 FILE_CTYPE 은 클라이언트 헤더가 아닌 감지 포맷 정규화값(JPEG 바이트 → image/jpeg)")
    void create_normalizes_content_type_from_detected_bytes() {
        AuthContext.set("7451", "03", null, null);
        MockMultipartFile image = new MockMultipartFile(
                "image", "shot.bin", "application/octet-stream", jpegBytes());

        service.create("SCRN001", "오류설명", null, image);

        ArgumentCaptor<IssueRow> captor = ArgumentCaptor.forClass(IssueRow.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getFileCtype()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("create: 이미지 없이 등록 → fileData/fileCtype null 로 저장")
    void create_without_image() {
        AuthContext.set("7451", "03", null, null);

        service.create("SCRN002", "재현됨", "HIGH", null);

        ArgumentCaptor<IssueRow> captor = ArgumentCaptor.forClass(IssueRow.class);
        verify(mapper).insert(captor.capture());
        IssueRow row = captor.getValue();
        assertThat(row.getFileData()).isNull();
        assertThat(row.getFileCtype()).isNull();
        assertThat(row.getPrirCd()).isEqualTo("HIGH");
    }

    // ── 수정: RESOLVED 규칙 ─────────────────────────────────────────────

    @Test
    @DisplayName("update: status=RESOLVED + 요청 resolveContent 있음 → 정상 저장")
    void update_resolved_with_request_content_ok() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectById(1L)).thenReturn(detail(1L, "7451", "OPEN", null));

        service.update(1L, new IssueUpdateRequest(null, "RESOLVED", null, "해결했습니다"));

        ArgumentCaptor<IssueRow> captor = ArgumentCaptor.forClass(IssueRow.class);
        verify(mapper).update(captor.capture());
        assertThat(captor.getValue().getStatCd()).isEqualTo("RESOLVED");
        assertThat(captor.getValue().getRslvCntt()).isEqualTo("해결했습니다");
    }

    @Test
    @DisplayName("update: status=RESOLVED + 요청 resolveContent 없지만 기존 저장값 있음 → 정상 저장(기존값 유지)")
    void update_resolved_with_existing_content_ok() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectById(1L)).thenReturn(detail(1L, "7451", "IN_PROGRESS", "이미 해결내용 있음"));

        service.update(1L, new IssueUpdateRequest(null, "RESOLVED", null, null));

        verify(mapper).update(any());
    }

    @Test
    @DisplayName("update: status=RESOLVED 인데 요청/기존 모두 해결내용 없음 → IllegalArgumentException, mapper.update 미호출")
    void update_resolved_without_any_content_rejected() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectById(1L)).thenReturn(detail(1L, "7451", "OPEN", null));

        assertThatThrownBy(() -> service.update(1L, new IssueUpdateRequest(null, "RESOLVED", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).update(any());
    }

    @Test
    @DisplayName("update: 이미 RESOLVED + status 미포함 + resolveContent 공백 → IllegalArgumentException, mapper.update 미호출")
    void update_already_resolved_blanking_resolve_content_rejected() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectById(1L)).thenReturn(detail(1L, "7451", "RESOLVED", "기존 해결내용"));

        assertThatThrownBy(() -> service.update(1L, new IssueUpdateRequest(null, null, null, "")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).update(any());
    }

    @Test
    @DisplayName("update: 대상 이슈 없음 → NotFoundException")
    void update_missing_issue_not_found() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(999L, new IssueUpdateRequest("수정", null, null, null)))
                .isInstanceOf(NotFoundException.class);
        verify(mapper, never()).update(any());
    }

    // ── 이미지 조회: 매퍼 타입 미노출(IssueImage 로 래핑) ───────────────

    @Test
    @DisplayName("getImage: 매퍼 ImageData 를 IssueImage(data/contentType) 로 감싸 반환")
    void get_image_wraps_mapper_type() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectImage(1L)).thenReturn(new IssueMapper.ImageData(pngBytes(), "image/png"));

        IssueImage img = service.getImage(1L);

        assertThat(img.data()).isEqualTo(pngBytes());
        assertThat(img.contentType()).isEqualTo("image/png");
    }

    // ── 삭제 권한 ────────────────────────────────────────────────────

    @Test
    @DisplayName("delete: 작성자 본인 → mapper.delete 호출")
    void delete_by_author_ok() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectById(1L)).thenReturn(detail(1L, "7451", "OPEN", null));

        service.delete(1L);

        verify(mapper).delete(1L);
    }

    @Test
    @DisplayName("delete: 타인(작성자 아님, ADMIN 아님) → ForbiddenException, mapper.delete 미호출")
    void delete_by_other_forbidden() {
        AuthContext.set("9999", "03", null, null);
        when(mapper.selectById(1L)).thenReturn(detail(1L, "7451", "OPEN", null));

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).delete(anyLong());
    }

    @Test
    @DisplayName("delete: ADMIN → 작성자 아니어도 mapper.delete 호출")
    void delete_by_admin_ok() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectById(1L)).thenReturn(detail(1L, "7451", "OPEN", null));

        service.delete(1L);

        verify(mapper).delete(1L);
    }

    @Test
    @DisplayName("delete: 대상 이슈 없음 → NotFoundException")
    void delete_missing_not_found() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(999L)).isInstanceOf(NotFoundException.class);
        verify(mapper, never()).delete(anyLong());
    }

    // ── 댓글 수정/삭제 권한(작성자/ADMIN 동일 규칙) ───────────────────

    @Test
    @DisplayName("updateComment: 댓글 작성자 본인 → 정상 수정")
    void update_comment_by_author_ok() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectComment(1L, 5L)).thenReturn(comment(5L, 1L, "7451"));

        service.updateComment(1L, 5L, "수정된 댓글");

        verify(mapper).updateComment(any());
    }

    @Test
    @DisplayName("updateComment: 댓글 작성자 아님, ADMIN 아님 → ForbiddenException")
    void update_comment_by_other_forbidden() {
        AuthContext.set("9999", "03", null, null);
        when(mapper.selectComment(1L, 5L)).thenReturn(comment(5L, 1L, "7451"));

        assertThatThrownBy(() -> service.updateComment(1L, 5L, "수정 시도"))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).updateComment(any());
    }

    @Test
    @DisplayName("updateComment: ADMIN → 작성자 아니어도 정상 수정")
    void update_comment_by_admin_ok() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectComment(1L, 5L)).thenReturn(comment(5L, 1L, "7451"));

        service.updateComment(1L, 5L, "관리자 수정");

        verify(mapper).updateComment(any());
    }

    @Test
    @DisplayName("updateComment: 대상 댓글 없음 → NotFoundException")
    void update_comment_missing_not_found() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectComment(1L, 999L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateComment(1L, 999L, "x"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("deleteComment: 댓글 작성자 본인 → mapper.deleteComment 호출")
    void delete_comment_by_author_ok() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectComment(1L, 5L)).thenReturn(comment(5L, 1L, "7451"));

        service.deleteComment(1L, 5L);

        verify(mapper).deleteComment(1L, 5L);
    }

    @Test
    @DisplayName("deleteComment: 댓글 작성자 아님, ADMIN 아님 → ForbiddenException, mapper.deleteComment 미호출")
    void delete_comment_by_other_forbidden() {
        AuthContext.set("9999", "03", null, null);
        when(mapper.selectComment(1L, 5L)).thenReturn(comment(5L, 1L, "7451"));

        assertThatThrownBy(() -> service.deleteComment(1L, 5L)).isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).deleteComment(anyLong(), anyLong());
    }

    @Test
    @DisplayName("deleteComment: ADMIN → 작성자 아니어도 mapper.deleteComment 호출")
    void delete_comment_by_admin_ok() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectComment(1L, 5L)).thenReturn(comment(5L, 1L, "7451"));

        service.deleteComment(1L, 5L);

        verify(mapper).deleteComment(1L, 5L);
    }

    @Test
    @DisplayName("deleteComment: 대상 댓글 없음 → NotFoundException")
    void delete_comment_missing_not_found() {
        AuthContext.set("7451", "03", null, null);
        when(mapper.selectComment(1L, 999L)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteComment(1L, 999L)).isInstanceOf(NotFoundException.class);
        verify(mapper, never()).deleteComment(anyLong(), anyLong());
    }
}
