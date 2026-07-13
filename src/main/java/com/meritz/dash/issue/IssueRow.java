package com.meritz.dash.issue;

/**
 * DASH_ISSUE 삽입/수정용 가변 로우(JavaBean). MyBatis {@code useGeneratedKeys} 로
 * ISSUE_ID(IDENTITY) 를 이 객체의 issueId 에 회수한다(record 는 불가 — BatchRunStart 패턴과 동일).
 */
public class IssueRow {

    private long issueId;
    private String screenId;
    private String regEmpno;
    private String errCntt;
    private String fileNm;
    private String fileCtype;
    private byte[] fileData;
    private String statCd;
    private String prirCd;
    private String rslvCntt;
    private String actor;

    public IssueRow() {}

    public IssueRow(String screenId, String regEmpno, String errCntt, String fileNm, String fileCtype,
                     byte[] fileData, String statCd, String prirCd, String rslvCntt, String actor) {
        this.screenId = screenId;
        this.regEmpno = regEmpno;
        this.errCntt = errCntt;
        this.fileNm = fileNm;
        this.fileCtype = fileCtype;
        this.fileData = fileData;
        this.statCd = statCd;
        this.prirCd = prirCd;
        this.rslvCntt = rslvCntt;
        this.actor = actor;
    }

    public long getIssueId() { return issueId; }
    public void setIssueId(long issueId) { this.issueId = issueId; }

    public String getScreenId() { return screenId; }
    public void setScreenId(String screenId) { this.screenId = screenId; }

    public String getRegEmpno() { return regEmpno; }
    public void setRegEmpno(String regEmpno) { this.regEmpno = regEmpno; }

    public String getErrCntt() { return errCntt; }
    public void setErrCntt(String errCntt) { this.errCntt = errCntt; }

    public String getFileNm() { return fileNm; }
    public void setFileNm(String fileNm) { this.fileNm = fileNm; }

    public String getFileCtype() { return fileCtype; }
    public void setFileCtype(String fileCtype) { this.fileCtype = fileCtype; }

    public byte[] getFileData() { return fileData; }
    public void setFileData(byte[] fileData) { this.fileData = fileData; }

    public String getStatCd() { return statCd; }
    public void setStatCd(String statCd) { this.statCd = statCd; }

    public String getPrirCd() { return prirCd; }
    public void setPrirCd(String prirCd) { this.prirCd = prirCd; }

    public String getRslvCntt() { return rslvCntt; }
    public void setRslvCntt(String rslvCntt) { this.rslvCntt = rslvCntt; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
}
