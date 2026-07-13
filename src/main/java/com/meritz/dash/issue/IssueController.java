package com.meritz.dash.issue;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 이슈관리(화면 오류) API. 등록(스크린샷 첨부)·조회·수정·삭제 + 댓글. 인증(게이트웨이 인터셉터)만 필요,
 * 삭제/댓글 수정·삭제 권한(작성자 또는 ADMIN)은 서비스단에서 fail-closed 판정한다.
 */
@Tag(name = "Issue", description = "이슈관리(화면 오류) — 등록(스크린샷 첨부)·조회·수정·삭제 + 댓글")
@RestController
@RequestMapping("/api/v1/issues")
public class IssueController {

    private final IssueService service;

    public IssueController(IssueService service) {
        this.service = service;
    }

    @Operation(
        summary = "이슈 목록",
        description = """
                등록된 이슈(화면 오류) 목록을 **최신순(ISSUE_ID DESC)** 으로 반환한다. **인증(JWT)만 필요, 역할 제한 없음**.

                **필터** — `status`: 콤마 다중값(예: `status=OPEN,IN_PROGRESS`). 화이트리스트 \
                (OPEN, IN_PROGRESS, RESOLVED, CLOSED) 외 값이 하나라도 있으면 **400**. 미지정 시 전체. \
                `screenId`: 화면ID 일치 필터(선택).

                **응답** — 목록엔 BLOB(스크린샷)을 싣지 않고 `hasImage` 로 존재 여부만 알린다 \
                (이미지는 `GET /{id}/image` 로 별도 조회). meta={ total }.
                """
    )
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "성공.",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EX_LIST))))
    @GetMapping
    public ApiResponse<List<IssueItem>> list(
            @Parameter(description = "상태 콤마 다중값(OPEN,IN_PROGRESS,RESOLVED,CLOSED). 화이트리스트 외 400. 미지정=전체.",
                    example = "OPEN,IN_PROGRESS")
            @RequestParam(required = false) List<String> status,
            @Parameter(description = "화면ID 필터(선택).", example = "SCR-DASH-01")
            @RequestParam(required = false) String screenId) {
        List<IssueItem> items = service.list(status, screenId);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", items.size());
        return ApiResponse.of(items, meta);
    }

    @Operation(
        summary = "이슈 상세",
        description = """
                단건 상세를 반환한다. **인증(JWT)만 필요.** BLOB 은 싣지 않고 `hasImage`·`fileNm`·`fileCtype` \
                메타와 `commentCount`(댓글 수)를 포함한다. 대상 미존재 **404**.
                """
    )
    @GetMapping("/{id}")
    public ApiResponse<IssueDetail> get(
            @Parameter(description = "이슈 ID", example = "12") @PathVariable Long id) {
        return ApiResponse.of(service.get(id));
    }

    @Operation(
        summary = "스크린샷 원본 (바이너리)",
        description = """
                저장된(업로드 시 매직넘버로 정규화된) Content-Type 의 **바이너리(이미지 바이트)** 로 반환한다. \
                브라우저 스니핑/인라인 렌더링을 막기 위해 `X-Content-Type-Options: nosniff` + \
                `Content-Disposition: attachment` 를 붙인다. 이슈 미존재 또는 이미지 없음 **404**.

                **프론트 주의** — 인증 헤더가 필요하고 attachment 로 내려오므로 `<img src="...">` 에 URL 을 직접 \
                넣어 쓸 수 없다. `fetch(url, {headers})` → `res.blob()` → `URL.createObjectURL(blob)` 로 표시할 것.
                """
    )
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(
            @Parameter(description = "이슈 ID", example = "12") @PathVariable Long id) {
        IssueImage img = service.getImage(id);
        MediaType type = MediaType.parseMediaType(img.contentType());
        return ResponseEntity.ok()
                .contentType(type)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"issue-" + id + "." + extensionOf(img.contentType()) + "\"")
                .body(img.data());
    }

    /** 정규화된 Content-Type → 다운로드 파일 확장자(원본 파일명 헤더 사용 금지 — 헤더 인젝션 방지). */
    private static String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }

    @Operation(
        summary = "이슈 등록 (multipart)",
        description = """
                **multipart/form-data** 로 등록한다(성공 **201**, 등록자=인증 사용자). **인증(JWT)만 필요.**

                **필드** — `screenId`(필수)·`content`(오류설명, 필수) 누락/공백 **400**. \
                `priority`(선택): LOW/MEDIUM/HIGH/CRITICAL, 미지정 시 MEDIUM, 그 외 값 **400**. `image`(선택 파일).

                **image 검증** — 클라이언트 Content-Type 을 믿지 않고 **실제 바이트 매직넘버**로 판별해 \
                PNG/JPEG/GIF/WEBP 만 허용(그 외 **400**). 크기 제한 **5MB**(멀티파트 전역 설정, 초과 시 업로드 거부).
                """
    )
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201", description = "등록 성공.",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EX_DETAIL))))
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<IssueDetail> create(
            @Parameter(description = "화면ID(필수).", example = "SCR-DASH-01")
            @RequestParam("screenId") String screenId,
            @Parameter(description = "오류설명(필수).", example = "가동률 차트가 0%로 표시됨")
            @RequestParam("content") String content,
            @Parameter(description = "우선순위(선택, LOW/MEDIUM/HIGH/CRITICAL, 기본 MEDIUM).", example = "HIGH")
            @RequestParam(value = "priority", required = false) String priority,
            @Parameter(description = "스크린샷(선택). 실제 바이트가 PNG/JPEG/GIF/WEBP 매직넘버가 아니면 400. 최대 5MB.")
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return ApiResponse.of(service.create(screenId, content, priority, image));
    }

    @Operation(
        summary = "이슈 부분수정",
        description = """
                JSON 부분수정 — 전 필드 선택적(null=미변경): `content`, `status`, `priority`, `resolveContent`. \
                **인증자 전원 수정 가능**(협업 처리 — 작성자 제한 없음).

                **검증** — status/priority 화이트리스트 외 **400**. **`status='RESOLVED'` 전환 시 해결내용 필수** \
                — 요청의 `resolveContent` 또는 기존 저장값 중 하나는 있어야 함(둘 다 없으면 **400**). 대상 미존재 **404**.
                """
    )
    @PutMapping("/{id}")
    public ApiResponse<IssueDetail> update(
            @Parameter(description = "이슈 ID", example = "12") @PathVariable Long id,
            @RequestBody IssueUpdateRequest req) {
        return ApiResponse.of(service.update(id, req));
    }

    @Operation(
        summary = "이슈 삭제",
        description = "**작성자 본인 또는 ADMIN 만**(그 외 **403**, fail-closed). 대상 미존재 **404**. 성공 시 **204**(댓글도 함께 삭제)."
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{id}")
    public void delete(
            @Parameter(description = "이슈 ID", example = "12") @PathVariable Long id) {
        service.delete(id);
    }

    @Operation(
        summary = "댓글 목록",
        description = "해당 이슈의 댓글을 등록순(CMT_ID 오름차순)으로 반환. **인증(JWT)만 필요.** 이슈 미존재 **404**."
    )
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "성공.",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EX_COMMENTS))))
    @GetMapping("/{id}/comments")
    public ApiResponse<List<IssueComment>> comments(
            @Parameter(description = "이슈 ID", example = "12") @PathVariable Long id) {
        return ApiResponse.of(service.listComments(id));
    }

    @Operation(
        summary = "댓글 등록",
        description = "JSON `{content}` 필수(누락/공백 **400**). 이슈 미존재 **404**. 성공 **201**(작성자=인증 사용자). **인증(JWT)만 필요.**"
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{id}/comments")
    public ApiResponse<IssueComment> addComment(
            @Parameter(description = "이슈 ID", example = "12") @PathVariable Long id,
            @RequestBody CommentRequest req) {
        return ApiResponse.of(service.addComment(id, req.content()));
    }

    @Operation(
        summary = "댓글 수정",
        description = "**댓글 작성자 본인 또는 ADMIN 만**(그 외 **403**). `content` 누락/공백 **400**. 이슈/댓글 미존재(또는 댓글이 해당 이슈 소속이 아님) **404**."
    )
    @PutMapping("/{id}/comments/{commentId}")
    public ApiResponse<IssueComment> updateComment(
            @Parameter(description = "이슈 ID", example = "12") @PathVariable Long id,
            @Parameter(description = "댓글 ID", example = "3") @PathVariable Long commentId,
            @RequestBody CommentRequest req) {
        return ApiResponse.of(service.updateComment(id, commentId, req.content()));
    }

    @Operation(
        summary = "댓글 삭제",
        description = "**댓글 작성자 본인 또는 ADMIN 만**(그 외 **403**). 이슈/댓글 미존재 **404**. 성공 시 **204**."
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{id}/comments/{commentId}")
    public void deleteComment(
            @Parameter(description = "이슈 ID", example = "12") @PathVariable Long id,
            @Parameter(description = "댓글 ID", example = "3") @PathVariable Long commentId) {
        service.deleteComment(id, commentId);
    }

    // ── Swagger 응답 예시 ─────────────────────────────────────────────
    private static final String EX_LIST = """
            {
              "data": [
                {
                  "issueId": 12, "screenId": "SCR-DASH-01", "regEmpno": "9320",
                  "errCntt": "가동률 차트가 0%로 표시됨", "hasImage": true,
                  "statCd": "OPEN", "prirCd": "HIGH", "rslvCntt": null,
                  "createdAt": "2026-07-10T09:12:00", "createdBy": "9320",
                  "updatedAt": null, "updatedBy": null
                }
              ],
              "meta": { "total": 1 }
            }""";

    private static final String EX_DETAIL = """
            {
              "data": {
                "issueId": 12, "screenId": "SCR-DASH-01", "regEmpno": "9320",
                "errCntt": "가동률 차트가 0%로 표시됨",
                "fileNm": "screenshot.png", "fileCtype": "image/png", "hasImage": true,
                "statCd": "OPEN", "prirCd": "HIGH", "rslvCntt": null,
                "createdAt": "2026-07-10T09:12:00", "createdBy": "9320",
                "updatedAt": null, "updatedBy": null,
                "commentCount": 0
              },
              "meta": null
            }""";

    private static final String EX_COMMENTS = """
            {
              "data": [
                {
                  "cmtId": 3, "issueId": 12, "regEmpno": "9421",
                  "cmtCntt": "재현 확인했습니다. 집계 배치 누락으로 보입니다.",
                  "createdAt": "2026-07-10T10:00:00", "createdBy": "9421",
                  "updatedAt": null, "updatedBy": null
                }
              ],
              "meta": null
            }""";
}
