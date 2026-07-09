package com.meritz.dash.aggregation;

import com.meritz.dash.mapper.app.DashWriteMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BATCH_RUN_LOG 기록을 독립 트랜잭션(REQUIRES_NEW)으로 처리한다.
 * <p>
 * 자가호출(self-invocation)은 Spring 트랜잭션 프록시를 우회하므로
 * AggregationService 와 별도 빈으로 분리하여 주입한다.
 * </p>
 * <ul>
 *   <li>{@link #start}: RUN_ID 행을 STATUS='RUNNING' 으로 즉시 커밋</li>
 *   <li>{@link #finish}: STATUS='OK'|'FAIL' 로 갱신 후 즉시 커밋</li>
 * </ul>
 * 메인 데이터 트랜잭션(DASH 적재)이 롤백돼도 로그 행은 DB에 남는다.
 */
@Component
public class BatchRunLogger {

    private final DashWriteMapper dash;

    public BatchRunLogger(DashWriteMapper dash) {
        this.dash = dash;
    }

    /**
     * BATCH_RUN_LOG 에 STATUS='RUNNING' 행을 삽입하고 즉시 커밋한다.
     *
     * @return 생성된 RUN_ID
     */
    @Transactional(transactionManager = "appTxManager", propagation = Propagation.REQUIRES_NEW)
    public long start(String periodYm, String trigger) {
        BatchRunStart s = new BatchRunStart(periodYm, trigger);
        dash.insertRunStart(s);
        return s.runId();
    }

    /**
     * BATCH_RUN_LOG 행을 STATUS='OK'|'FAIL' 로 갱신하고 즉시 커밋한다.
     */
    @Transactional(transactionManager = "appTxManager", propagation = Propagation.REQUIRES_NEW)
    public void finish(long runId, String status, int devRows, int srRows, String msg) {
        dash.updateRunFinish(runId, status, devRows, srRows, msg);
    }
}
