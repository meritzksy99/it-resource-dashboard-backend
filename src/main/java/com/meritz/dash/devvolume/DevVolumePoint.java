package com.meritz.dash.devvolume;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "월별 SR 건수 + 개발량(M/M) 데이터 포인트 (막대차트용)")
public record DevVolumePoint(
        @Schema(description = "집계 월 (YYYYMM)", example = "202606") String periodYm,
        @Schema(description = "월 표시 라벨 (YY.MM)", example = "26.06") String monthLabel,
        @Schema(description = "SR 분류코드: 01 개발요청 · 02 유지보수 · 03 자료요청 · 99 기타", example = "01") String srCls,
        @Schema(description = "SR 분류명", example = "개발요청") String srClsName,
        @Schema(description = "해당 월/분류의 SR 건수", example = "12") int srCnt,
        @Schema(description = "해당 월/분류의 개발량 M/M (계획공수 합계 = Σ 투입시간 ÷ 166)", example = "3.75") double jobMm) {}
