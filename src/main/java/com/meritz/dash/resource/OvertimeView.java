package com.meritz.dash.resource;

import io.swagger.v3.oas.annotations.media.Schema;

/** 개발자별 야근시간 상세 — HR_OVERTIME(엑셀 업로드 실적) 기반. */
@Schema(description = "개발자별 야근시간 상세 (엑셀 업로드 실적 기반)")
public record OvertimeView(
        @Schema(description = "사번", example = "9320") String empno,
        @Schema(description = "이름", example = "김성엽") String empNm,
        @Schema(description = "파트코드: P01 금융상품 · P02 계좌 · P03 MTS · P04 HTS · P05 출납 · P06 업무공통 · P07 해외주식 · P08 국내주식 · P09 본사후선 · P10 미지정 · P11 외주", example = "P01") String partCd,
        @Schema(description = "해당 월 야근 총 분 (평일연장+평일야간+휴일연장+휴일야간, 업로드 원본)", example = "774") int otMinutes,
        @Schema(description = "해당 월 야근시간 = otMinutes ÷ 60 (소수 1자리 반올림)", example = "12.9") double overtimeHours) {}
