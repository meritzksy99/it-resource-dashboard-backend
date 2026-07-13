package com.meritz.dash.issue;

import java.time.LocalDateTime;

/**
 * 이슈 목록 API 응답 항목(DASH_ISSUE). BLOB(FILE_DATA)은 포함하지 않고 {@link #hasImage()} 로만 존재 여부를 알린다.
 */
public record IssueItem(
        Long issueId,
        String screenId,
        String regEmpno,
        String errCntt,
        boolean hasImage,
        String statCd,
        String prirCd,
        String rslvCntt,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy) {}
