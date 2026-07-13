package com.meritz.dash.issue;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.mapper.app.IssueMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;

/**
 * 이슈관리(화면 오류) CRUD + 댓글 서비스.
 * <p>
 * 비즈니스 규칙({@code docs/superpowers/specs/2026-07-10-issue-management.md} 참조):
 * <ol>
 *   <li>{@code image} 는 실제 바이트 매직넘버가 PNG/JPEG/GIF/WEBP 인 경우만 허용(그 외 {@link IllegalArgumentException}(400)).
 *       저장 Content-Type 은 감지 포맷에서 정규화한 값(클라이언트 헤더 미신뢰 — 저장형 XSS 차단).</li>
 *   <li>수정 후 유효 상태가 {@code RESOLVED} 면 해결내용(요청 resolveContent 또는 기존 저장값)이 있어야 함(없거나 공백이면 400).</li>
 *   <li>status/priority 값은 화이트리스트({@code OPEN,IN_PROGRESS,RESOLVED,CLOSED} / {@code LOW,MEDIUM,HIGH,CRITICAL}) 외 400.</li>
 *   <li>작성자(REG_EMPNO)={@code AuthContext.empno()}. CREATED_BY/UPDATED_BY 도 empno.</li>
 *   <li>삭제 권한: {@code empno==REG_EMPNO || role==ADMIN} 아니면 {@code ForbiddenException}(403). 댓글도 동일.</li>
 * </ol>
 */
@Service
public class IssueService {

    private static final Set<String> STATUS_CODES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
    private static final Set<String> PRIORITY_CODES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final String DEFAULT_PRIORITY = "MEDIUM";
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_RESOLVED = "RESOLVED";
    private static final String ROLE_ADMIN = "ADMIN";

    private final IssueMapper mapper;

    public IssueService(IssueMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 이슈 목록. statuses 는 화이트리스트({@code OPEN,IN_PROGRESS,RESOLVED,CLOSED}) 검증 후 IN 필터,
     * null/빈 리스트면 전체. screenId 는 선택 필터. 최신순(ISSUE_ID DESC).
     *
     * @throws IllegalArgumentException statuses 에 화이트리스트 외 값이 있으면(400)
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<IssueItem> list(List<String> statuses, String screenId) {
        if (statuses != null) {
            for (String s : statuses) {
                if (!STATUS_CODES.contains(s)) {
                    throw new IllegalArgumentException(
                            "status 는 OPEN,IN_PROGRESS,RESOLVED,CLOSED 중 하나여야 합니다: " + s);
                }
            }
            if (statuses.isEmpty()) {
                statuses = null;
            }
        }
        return mapper.selectList(statuses, blankToNull(screenId));
    }

    /**
     * 이슈 상세.
     *
     * @throws NotFoundException 대상 이슈 없음(404)
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public IssueDetail get(Long issueId) {
        return requireIssue(issueId);
    }

    /**
     * 스크린샷 원본 조회. 매퍼 내부 타입을 노출하지 않고 {@link IssueImage} 로 감싼다.
     *
     * @throws NotFoundException 이슈 미존재 또는 이미지 없음(404)
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public IssueImage getImage(Long issueId) {
        IssueMapper.ImageData image = mapper.selectImage(issueId);
        if (image == null || image.fileData() == null) {
            throw new NotFoundException("이미지가 없습니다: " + issueId);
        }
        return new IssueImage(image.fileData(), image.fileCtype());
    }

    /**
     * 이슈 등록(multipart). screenId/content 필수, priority 미지정 시 MEDIUM.
     * image 지정 시 실제 바이트 매직넘버가 PNG/JPEG/GIF/WEBP 여야 하며(클라이언트 Content-Type 미신뢰),
     * 저장 FILE_CTYPE 은 감지 포맷에서 정규화한 값이다. 작성자=AuthContext.empno().
     *
     * @throws IllegalArgumentException screenId/content 누락, priority 화이트리스트 외,
     *         image 바이트가 허용 포맷(PNG/JPEG/GIF/WEBP)이 아님(400)
     */
    @Transactional(transactionManager = "appTxManager")
    public IssueDetail create(String screenId, String content, String priority, MultipartFile image) {
        String empno = AuthContext.empno();
        if (screenId == null || screenId.isBlank()) {
            throw new IllegalArgumentException("screenId 는 필수입니다");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content(오류설명) 는 필수입니다");
        }
        String prirCd = (priority == null || priority.isBlank()) ? DEFAULT_PRIORITY : priority;
        if (!PRIORITY_CODES.contains(prirCd)) {
            throw new IllegalArgumentException(
                    "priority 는 LOW,MEDIUM,HIGH,CRITICAL 중 하나여야 합니다: " + prirCd);
        }

        String fileNm = null;
        String fileCtype = null;
        byte[] fileData = null;
        if (image != null && !image.isEmpty()) {
            byte[] bytes;
            try {
                bytes = image.getBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("이미지 파일을 읽을 수 없습니다", e);
            }
            String detected = detectImageContentType(bytes);
            if (detected == null) {
                throw new IllegalArgumentException("image 는 PNG/JPEG/GIF/WEBP 형식만 허용됩니다");
            }
            fileNm = image.getOriginalFilename();
            fileCtype = detected;                                  // 클라이언트 헤더가 아닌 감지값 저장
            fileData = bytes;
        }

        IssueRow row = new IssueRow(screenId, empno, content, fileNm, fileCtype, fileData,
                STATUS_OPEN, prirCd, null, empno);
        mapper.insert(row);
        return mapper.selectById(row.getIssueId());
    }

    /**
     * 이슈 부분수정(content/status/priority/resolveContent, 전 필드 선택적). 협업 처리 — 작성자 제한 없음.
     * 요청 status 와 기존 상태를 병합한 <b>유효 상태</b>가 RESOLVED 면 해결내용(요청 resolveContent 또는
     * 기존 RSLV_CNTT)이 있어야 함 — 이미 RESOLVED 인 이슈의 해결내용을 공백으로 비우는 것도 400.
     *
     * @throws NotFoundException 대상 이슈 없음(404)
     * @throws IllegalArgumentException status/priority 화이트리스트 외, 유효 상태 RESOLVED 인데 해결내용 없음(400)
     */
    @Transactional(transactionManager = "appTxManager")
    public IssueDetail update(Long issueId, IssueUpdateRequest req) {
        IssueDetail existing = requireIssue(issueId);

        String status = blankToNull(req.status());
        if (status != null && !STATUS_CODES.contains(status)) {
            throw new IllegalArgumentException(
                    "status 는 OPEN,IN_PROGRESS,RESOLVED,CLOSED 중 하나여야 합니다: " + status);
        }
        String priority = blankToNull(req.priority());
        if (priority != null && !PRIORITY_CODES.contains(priority)) {
            throw new IllegalArgumentException(
                    "priority 는 LOW,MEDIUM,HIGH,CRITICAL 중 하나여야 합니다: " + priority);
        }
        String effectiveStatus = status != null ? status : existing.statCd();   // 병합 후 유효 상태로 검증
        String resolveContent = req.resolveContent() != null ? req.resolveContent() : existing.rslvCntt();
        if (STATUS_RESOLVED.equals(effectiveStatus) && (resolveContent == null || resolveContent.isBlank())) {
            throw new IllegalArgumentException("RESOLVED 상태에는 해결내용(resolveContent)이 필요합니다");
        }

        IssueRow row = new IssueRow(existing.screenId(), existing.regEmpno(),
                req.content() != null ? req.content() : existing.errCntt(),
                existing.fileNm(), existing.fileCtype(), null,
                status != null ? status : existing.statCd(),
                priority != null ? priority : existing.prirCd(),
                resolveContent, AuthContext.empno());
        row.setIssueId(issueId);
        mapper.update(row);
        return mapper.selectById(issueId);
    }

    /**
     * 이슈 삭제(댓글 cascade). 작성자 또는 ADMIN 만 가능.
     *
     * @throws NotFoundException 대상 이슈 없음(404)
     * @throws ForbiddenException 작성자도 ADMIN도 아님(403)
     */
    @Transactional(transactionManager = "appTxManager")
    public void delete(Long issueId) {
        IssueDetail existing = requireIssue(issueId);
        assertOwnerOrAdmin(existing.regEmpno(), "작성자 또는 ADMIN 만 삭제할 수 있습니다");
        mapper.delete(issueId);
    }

    /**
     * 댓글 목록(등록순 오름차순).
     *
     * @throws NotFoundException 대상 이슈 없음(404)
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<IssueComment> listComments(Long issueId) {
        requireIssue(issueId);
        return mapper.selectComments(issueId);
    }

    /**
     * 댓글 등록. 작성자=AuthContext.empno().
     *
     * @throws NotFoundException 대상 이슈 없음(404)
     * @throws IllegalArgumentException content 누락(400)
     */
    @Transactional(transactionManager = "appTxManager")
    public IssueComment addComment(Long issueId, String content) {
        requireIssue(issueId);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content(댓글 내용) 는 필수입니다");
        }
        String empno = AuthContext.empno();
        IssueCommentRow row = new IssueCommentRow(issueId, empno, content, empno);
        mapper.insertComment(row);
        return mapper.selectComment(issueId, row.getCmtId());
    }

    /**
     * 댓글 수정. 작성자 또는 ADMIN 만 가능.
     *
     * @throws NotFoundException 이슈/댓글 없음(404)
     * @throws ForbiddenException 작성자도 ADMIN도 아님(403)
     */
    @Transactional(transactionManager = "appTxManager")
    public IssueComment updateComment(Long issueId, Long commentId, String content) {
        IssueComment existing = requireComment(issueId, commentId);
        assertOwnerOrAdmin(existing.regEmpno(), "댓글 작성자 또는 ADMIN 만 수정할 수 있습니다");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content(댓글 내용) 는 필수입니다");
        }
        IssueCommentRow row = new IssueCommentRow(issueId, existing.regEmpno(), content, AuthContext.empno());
        row.setCmtId(commentId);
        mapper.updateComment(row);
        return mapper.selectComment(issueId, commentId);
    }

    /**
     * 댓글 삭제. 작성자 또는 ADMIN 만 가능.
     *
     * @throws NotFoundException 이슈/댓글 없음(404)
     * @throws ForbiddenException 작성자도 ADMIN도 아님(403)
     */
    @Transactional(transactionManager = "appTxManager")
    public void deleteComment(Long issueId, Long commentId) {
        IssueComment existing = requireComment(issueId, commentId);
        assertOwnerOrAdmin(existing.regEmpno(), "댓글 작성자 또는 ADMIN 만 삭제할 수 있습니다");
        mapper.deleteComment(issueId, commentId);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────

    private IssueDetail requireIssue(Long issueId) {
        IssueDetail found = mapper.selectById(issueId);
        if (found == null) {
            throw new NotFoundException("해당 이슈를 찾을 수 없습니다: " + issueId);
        }
        return found;
    }

    private IssueComment requireComment(Long issueId, Long commentId) {
        IssueComment found = mapper.selectComment(issueId, commentId);
        if (found == null) {
            throw new NotFoundException("해당 댓글을 찾을 수 없습니다: " + commentId);
        }
        return found;
    }

    /** 작성자 본인 또는 ADMIN 이 아니면 403 (fail-closed). */
    private static void assertOwnerOrAdmin(String regEmpno, String message) {
        String empno = AuthContext.empno();
        if (empno.equals(regEmpno) || ROLE_ADMIN.equals(AuthContext.role())) {
            return;
        }
        throw new ForbiddenException(message);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 실제 바이트 매직넘버 기반 이미지 포맷 감지(화이트리스트). 허용 포맷이면 정규화된
     * Content-Type(image/png 등), 감지 실패·그 외 포맷(SVG 등 스크립트 실행 가능 포맷 포함)이면 null.
     * 신규 라이브러리 없이 수동 검사한다.
     */
    private static String detectImageContentType(byte[] b) {
        if (b == null) {
            return null;
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return "image/png";                                    // 89 50 4E 47
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";                                   // FF D8 FF
        }
        if (b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "image/gif";                                    // 47 49 46 38
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";                                   // RIFF....WEBP
        }
        return null;
    }
}
