package com.meritz.dash.issue;

/**
 * DASH_ISSUE_CMT 삽입/수정용 가변 로우(JavaBean). MyBatis {@code useGeneratedKeys} 로
 * CMT_ID(IDENTITY) 를 이 객체의 cmtId 에 회수한다.
 */
public class IssueCommentRow {

    private long cmtId;
    private long issueId;
    private String regEmpno;
    private String cmtCntt;
    private String actor;

    public IssueCommentRow() {}

    public IssueCommentRow(long issueId, String regEmpno, String cmtCntt, String actor) {
        this.issueId = issueId;
        this.regEmpno = regEmpno;
        this.cmtCntt = cmtCntt;
        this.actor = actor;
    }

    public long getCmtId() { return cmtId; }
    public void setCmtId(long cmtId) { this.cmtId = cmtId; }

    public long getIssueId() { return issueId; }
    public void setIssueId(long issueId) { this.issueId = issueId; }

    public String getRegEmpno() { return regEmpno; }
    public void setRegEmpno(String regEmpno) { this.regEmpno = regEmpno; }

    public String getCmtCntt() { return cmtCntt; }
    public void setCmtCntt(String cmtCntt) { this.cmtCntt = cmtCntt; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
}
