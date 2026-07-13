package com.meritz.dash.issue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code PUT /api/v1/issues/{id}} 요청 바디. 전 필드 선택적 부분수정(null=미변경).
 * status='RESOLVED' 로 바꿀 때는 이 요청의 resolveContent 또는 기존 저장값 중 하나는 있어야 한다(둘 다 없으면 400).
 */
public record IssueUpdateRequest(
        @Schema(description = "오류설명(선택, null=미변경).", example = "가동률 차트가 0%로 표시됨 — 신규 입사자 포함 시")
        String content,
        @Schema(description = "상태(선택, null=미변경). OPEN/IN_PROGRESS/RESOLVED/CLOSED 외 400. "
                + "RESOLVED 전환 시 해결내용(요청 resolveContent 또는 기존 저장값) 필수 — 둘 다 없으면 400.",
                example = "RESOLVED")
        String status,
        @Schema(description = "우선순위(선택, null=미변경). LOW/MEDIUM/HIGH/CRITICAL 외 400.", example = "HIGH")
        String priority,
        @Schema(description = "해결내용(선택, null=미변경). RESOLVED 전환 시 필수 참조.",
                example = "집계 배치에 신규 입사자 인원 반영")
        String resolveContent) {}
