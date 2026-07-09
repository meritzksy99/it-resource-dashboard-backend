package com.meritz.dash.mapper.app;

import com.meritz.dash.aggregation.BatchRunStart;
import com.meritz.dash.aggregation.DevAgg;
import com.meritz.dash.aggregation.ResourceSnapshot;
import com.meritz.dash.aggregation.SrProject;
import org.apache.ibatis.annotations.Param;

public interface DashWriteMapper {

    void deleteDevAgg(@Param("periodYm") String periodYm);
    void insertDevAgg(DevAgg row);

    void deleteResource(@Param("periodYm") String periodYm);
    void insertResource(ResourceSnapshot row);

    void deleteSrProject(@Param("periodYm") String periodYm);
    void insertSrProject(SrProject row);

    /**
     * BATCH_RUN_LOG 행 삽입. RUN_ID(IDENTITY) 는 s.runId 에 세팅된다.
     * (useGeneratedKeys="true" keyProperty="runId" keyColumn="RUN_ID")
     */
    void insertRunStart(BatchRunStart s);

    void updateRunFinish(@Param("runId") long runId, @Param("status") String status,
                         @Param("devRows") int devRows, @Param("srRows") int srRows,
                         @Param("msg") String msg);
}
