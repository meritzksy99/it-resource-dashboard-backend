package com.meritz.dash.resource;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인원/M/M/가동률 요약 (단위별)")
public record ResourceView(
        @Schema(description = "집계 월 (YYYYMM)", example = "202606") String periodYm,
        @Schema(description = "조회 단위: ALL 전사 · DEPT 부서 · PART 부서-파트", example = "DEPT") String unitType,
        @Schema(description = "단위 식별자: ALL이면 'ALL' / 부서이면 부서코드(2139) / 부서-파트이면 '2139-P01'", example = "2139") String unitId,
        @Schema(description = "재직 인원수", example = "30") int headcount,
        @Schema(description = "개발 가능(재직 개발자) 인원수", example = "25") int availHeadcount,
        @Schema(description = "가용 M/M = 개발가능인원 × 1.0 (1 M/M = 166시간 = 1인 1개월)", example = "25.0") double availMm,
        @Schema(description = "사용 중 M/M = 해당 월 계획공수 합계 (투입시간 ÷ 166)", example = "22.5") double usedMm,
        @Schema(description = "야근 M/M 합계 = Σ max(개인 M/M − 1.0, 0)", example = "3.2") double overtimeMm,
        @Schema(description = "가동률 = usedMm ÷ availMm (1.0 초과 = 과부하)", example = "0.90") double utilization) {}
