package com.meritz.dash.worksite;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "업무사이트 바로가기 항목")
public record WorkSite(
        @Schema(description = "사이트 주소", example = "https://gw.example.co.kr") String url,
        @Schema(description = "화면명", example = "그룹웨어") String name,
        @Schema(description = "설명", example = "전자결재·메일·게시판 통합 그룹웨어") String description) {}
