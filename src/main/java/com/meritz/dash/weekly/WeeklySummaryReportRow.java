package com.meritz.dash.weekly;

/** DASH_WKLY_SUM_RPT ⨝ DASH_WKLY_RPT 임베드 조회 로우(매퍼 resultType) — sumId 로 그룹핑해 취합본에 임베드. */
public record WeeklySummaryReportRow(
        Long sumId,
        Long rptId,
        String srNo,
        String srTitl,
        String regEmpno,
        String rptCntt,
        String planDate,
        String srPlanDate,
        String delayRsn,
        String leaderCmt) {

    /** 임베드 DTO 로 변환(sumId 제외). */
    public WeeklySummary.Report toReport() {
        return new WeeklySummary.Report(rptId, srNo, srTitl, regEmpno,
                rptCntt, planDate, srPlanDate, delayRsn, leaderCmt);
    }
}
