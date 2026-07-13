package com.meritz.dash.issue;

import java.time.LocalDateTime;

/**
 * 이슈 상세 API 응답(DASH_ISSUE). BLOB(FILE_DATA)은 포함하지 않는다 — 이미지는 별도
 * {@code GET /issues/{id}/image} 로 조회한다. {@link #commentCount()} 는 댓글 수.
 */
public record IssueDetail(
        Long issueId,
        String screenId,
        String regEmpno,
        String errCntt,
        String fileNm,
        String fileCtype,
        boolean hasImage,
        String statCd,
        String prirCd,
        String rslvCntt,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy,
        long commentCount) {}
