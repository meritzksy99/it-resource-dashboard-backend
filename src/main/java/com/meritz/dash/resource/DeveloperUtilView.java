package com.meritz.dash.resource;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "개발자별 가용률(가동률) 요약")
public record DeveloperUtilView(
        @Schema(description = "사번", example = "7451") String empno,
        @Schema(description = "성명", example = "홍길동") String empNm,
        @Schema(description = "부서코드 (미지정 시 '미분류')", example = "2139") String deptCd,
        @Schema(description = "파트코드 (미지정 시 '미분류')", example = "P01") String partCd,
        @Schema(description = "가용 M/M = 개발가능 1인 = 1.0", example = "1.0") double availMm,
        @Schema(description = "사용 M/M = 해당 월 계획공수 합계 (투입시간 ÷ 166)", example = "1.2") double usedMm,
        @Schema(description = "가용률(가동률) = usedMm ÷ availMm (1.0 초과 = 과부하)", example = "1.2") double utilization) {}
