package com.meritz.dash.weekly;

/** 개인 주간보고 응답 DTO(DASH_WKLY_RPT). */
public record WeeklyReport(
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
