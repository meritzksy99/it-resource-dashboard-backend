package com.meritz.dash.issue;

import java.time.LocalDateTime;

/** 이슈 댓글 API 응답 항목(DASH_ISSUE_CMT). */
public record IssueComment(
        Long cmtId,
        Long issueId,
        String regEmpno,
        String cmtCntt,
        LocalDateTime createdAt,
        String createdBy,
        LocalDateTime updatedAt,
        String updatedBy) {}
