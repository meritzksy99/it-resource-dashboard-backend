package com.meritz.dash.weekly;

import java.util.List;

/**
 * 파트 취합본 응답 DTO(DASH_WKLY_SUM).
 * {@code reports} 는 업무리더가 선택해 취합본을 구성한 개인 보고들(DASH_WKLY_SUM_RPT 링크, RPT_ID 오름차순).
 */
public record WeeklySummary(
        Long sumId,
        String weekYmd,
        String deptCd,
        String partCd,
        String sumCntt,
        String regEmpno,
        String teamCmt,
        String teamCmtEmpno,
        List<Report> reports) {

    public WeeklySummary {
        reports = reports == null ? List.of() : List.copyOf(reports);
    }

    /** 선택 보고 없이 생성하는 편의 생성자(reports = 빈 목록). */
    public WeeklySummary(Long sumId, String weekYmd, String deptCd, String partCd,
            String sumCntt, String regEmpno, String teamCmt, String teamCmtEmpno) {
        this(sumId, weekYmd, deptCd, partCd, sumCntt, regEmpno, teamCmt, teamCmtEmpno, List.of());
    }

    /** 취합본에 임베드되는 개인 보고 요약(DASH_WKLY_RPT). */
    public record Report(
            Long rptId,
            String srNo,
            String srTitl,
            String regEmpno,
            String rptCntt,
            String planDate,
            String srPlanDate,
            String delayRsn,
            String leaderCmt) {
    }
}
