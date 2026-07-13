package com.meritz.dash.issue;

import io.swagger.v3.oas.annotations.media.Schema;

/** 댓글 등록/수정 요청 바디. */
public record CommentRequest(
        @Schema(description = "댓글 내용(필수, 누락/공백 400).", example = "재현 확인했습니다. 집계 배치 누락으로 보입니다.")
        String content) {}
