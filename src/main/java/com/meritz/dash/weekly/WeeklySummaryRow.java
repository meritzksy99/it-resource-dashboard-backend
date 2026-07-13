package com.meritz.dash.weekly;

/** DASH_WKLY_SUM 원본 조회 로우(매퍼 resultType). */
public record WeeklySummaryRow(
        Long sumId,
        String weekYmd,
        String deptCd,
        String partCd,
        String sumCntt,
        String regEmpno,
        String teamCmt,
        String teamCmtEmpno) {
}
