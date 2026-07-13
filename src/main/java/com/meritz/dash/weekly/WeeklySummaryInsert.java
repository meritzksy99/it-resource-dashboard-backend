package com.meritz.dash.weekly;

/**
 * DASH_WKLY_SUM INSERT 용 가변 객체 ({@link WeeklyReportInsert} 동형 패턴).
 * MyBatis {@code useGeneratedKeys="true" keyProperty="sumId"} 로 SUM_ID(IDENTITY) 를 회수한다.
 * 같은 (WEEK_YMD, DEPT_CD, PART_CD) 에 여러 건 등록 가능(UK 없음 — V022).
 */
public class WeeklySummaryInsert {

    private final String weekYmd;
    private final String deptCd;
    private final String partCd;
    private final String sumCntt;
    private final String regEmpno;
    private final String actor;
    private Long sumId;

    public WeeklySummaryInsert(String weekYmd, String deptCd, String partCd,
            String sumCntt, String regEmpno, String actor) {
        this.weekYmd = weekYmd;
        this.deptCd = deptCd;
        this.partCd = partCd;
        this.sumCntt = sumCntt;
        this.regEmpno = regEmpno;
        this.actor = actor;
    }

    public String getWeekYmd() { return weekYmd; }
    public String getDeptCd() { return deptCd; }
    public String getPartCd() { return partCd; }
    public String getSumCntt() { return sumCntt; }
    public String getRegEmpno() { return regEmpno; }
    public String getActor() { return actor; }
    public Long getSumId() { return sumId; }
    public void setSumId(Long sumId) { this.sumId = sumId; }
}
