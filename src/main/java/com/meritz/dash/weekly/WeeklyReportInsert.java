package com.meritz.dash.weekly;

/**
 * DASH_WKLY_RPT INSERT 용 가변 객체 (DashWriteMapper.BatchRunStart 동형 패턴).
 * MyBatis {@code useGeneratedKeys="true" keyProperty="rptId"} 로 RPT_ID(IDENTITY) 를 회수한다.
 */
public class WeeklyReportInsert {

    private final String weekYmd;
    private final String srNo;
    private final String srTitl;
    private final String regEmpno;
    private final String deptCd;
    private final String partCd;
    private final String rptCntt;
    private final String planDate;
    private final String srPlanDate;
    private final String delayRsn;
    private final String actor;
    private Long rptId;

    public WeeklyReportInsert(String weekYmd, String srNo, String srTitl, String regEmpno, String deptCd,
            String partCd, String rptCntt, String planDate, String srPlanDate, String delayRsn, String actor) {
        this.weekYmd = weekYmd;
        this.srNo = srNo;
        this.srTitl = srTitl;
        this.regEmpno = regEmpno;
        this.deptCd = deptCd;
        this.partCd = partCd;
        this.rptCntt = rptCntt;
        this.planDate = planDate;
        this.srPlanDate = srPlanDate;
        this.delayRsn = delayRsn;
        this.actor = actor;
    }

    public String getWeekYmd() { return weekYmd; }
    public String getSrNo() { return srNo; }
    public String getSrTitl() { return srTitl; }
    public String getRegEmpno() { return regEmpno; }
    public String getDeptCd() { return deptCd; }
    public String getPartCd() { return partCd; }
    public String getRptCntt() { return rptCntt; }
    public String getPlanDate() { return planDate; }
    public String getSrPlanDate() { return srPlanDate; }
    public String getDelayRsn() { return delayRsn; }
    public String getActor() { return actor; }
    public Long getRptId() { return rptId; }
    public void setRptId(Long rptId) { this.rptId = rptId; }
}
