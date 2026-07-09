package com.meritz.dash.aggregation;

/**
 * BATCH_RUN_LOG 삽입용 가변 객체.
 * MyBatis useGeneratedKeys + keyProperty="runId" 로 RUN_ID(IDENTITY) 를 회수한다.
 */
public class BatchRunStart {
    private final String periodYm;
    private final String trigger;
    private long runId;

    public BatchRunStart(String periodYm, String trigger) {
        this.periodYm = periodYm;
        this.trigger = trigger;
    }

    public String getPeriodYm() { return periodYm; }
    public String getTrigger()  { return trigger; }
    public long   getRunId()    { return runId; }
    public void   setRunId(long id) { this.runId = id; }
    /** 편의 접근자 (record-style) */
    public long   runId()       { return runId; }
}
