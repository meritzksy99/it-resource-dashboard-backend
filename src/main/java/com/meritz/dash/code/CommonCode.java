package com.meritz.dash.code;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "공통코드 항목")
public record CommonCode(
        @Schema(description = "코드 그룹 식별자", example = "SR_CLS") String grpCd,
        @Schema(description = "코드값", example = "01") String cdVal,
        @Schema(description = "코드 표시명", example = "개발요청") String cdNm,
        @Schema(description = "정렬 순서", example = "1") int sortNo) {}
