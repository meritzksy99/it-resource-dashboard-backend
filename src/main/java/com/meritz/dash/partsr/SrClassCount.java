package com.meritz.dash.partsr;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "SR 종류별 건수 및 M/M 합계")
public record SrClassCount(
        @Schema(description = "SR 분류 코드 (01 개발요청 · 02 유지보수 · 03 자료요청 · 04 데이터변경 · 05 원장변경 · 99 기타)")
        String srCls,
        @Schema(description = "SR 분류 명칭")
        String srClsNm,
        @Schema(description = "해당 분류의 SR 건수")
        int srCnt,
        @Schema(description = "해당 분류의 M/M 합계")
        double mm
) {}
