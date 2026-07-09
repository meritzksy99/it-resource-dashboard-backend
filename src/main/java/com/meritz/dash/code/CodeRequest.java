package com.meritz.dash.code;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CodeRequest(
        @NotBlank @Schema(description = "코드 그룹", example = "TESTGRP") String grpCd,
        @NotBlank @Schema(description = "코드값", example = "01") String cdVal,
        @NotBlank @Schema(description = "코드명", example = "테스트") String cdNm,
        @Schema(description = "정렬 순서 (기본 0)", example = "1") Integer sortNo,
        @Pattern(regexp = "Y|N", message = "useYn은 Y 또는 N")
        @Schema(description = "사용여부 Y/N (기본 Y)", example = "Y") String useYn,
        @Schema(description = "부가속성1 (선택)", example = "추가속성값") String attr1) {

    public int effectiveSortNo() {
        return sortNo == null ? 0 : sortNo;
    }

    public String effectiveUseYn() {
        return useYn == null ? "Y" : useYn;
    }
}
