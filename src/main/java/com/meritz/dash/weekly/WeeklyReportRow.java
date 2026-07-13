package com.meritz.dash.weekly;

/** DASH_WKLY_RPT 원본 조회 로우(매퍼 resultType). */
public record WeeklyReportRow(
        Long rptId,
        String weekYmd,
        String srNo,
        String srTitl,
        String regEmpno,
        String deptCd,
        String partCd,
        String rptCntt,
        String planDate,
        String srPlanDate,
        String delayRsn,
        String leaderCmt) {
}
