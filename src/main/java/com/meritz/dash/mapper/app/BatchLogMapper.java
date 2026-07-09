package com.meritz.dash.mapper.app;

import com.meritz.dash.aggregation.BatchRunLogView;

import java.util.List;

/**
 * BATCH_RUN_LOG 이력 조회 매퍼.
 */
public interface BatchLogMapper {
    List<BatchRunLogView> findRecent();
}
