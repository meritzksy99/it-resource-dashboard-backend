package com.meritz.dash.aggregation;

import com.meritz.dash.mapper.legacy.LegacySrMapper;
import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * BATCH_RUN_LOG 실패 이력 영속 검증.
 * <p>
 * LegacySrMapper 를 MockBean 으로 대체해 selectDevAgg 가 RuntimeException 을 던지게 한다.
 * AggregationService.run() 이 예외를 전파하더라도, BatchRunLogger(REQUIRES_NEW) 가
 * STATUS='FAIL' 행을 독립 트랜잭션으로 커밋했는지 확인한다.
 * </p>
 * <p>
 * 주의: REQUIRES_NEW 커밋이 실제 DB 에 반영되는지 확인해야 하므로
 * 이 테스트 메서드에는 {@code @Transactional} 을 붙이지 않는다.
 * (테스트 메서드에 @Transactional 을 붙이면 테스트 트랜잭션이 롤백되어
 * REQUIRES_NEW 커밋도 함께 사라져 검증이 불가능해진다.)
 * </p>
 */
class AggregationServiceFailIT extends LegacyFixture {

    @MockBean
    LegacySrMapper legacySrMapper;

    @Autowired AggregationService service;
    @Autowired JdbcTemplate jdbc;

    private static final String PERIOD = "202699";

    @BeforeEach
    void cleanUp() {
        jdbc.execute("DELETE FROM BATCH_RUN_LOG WHERE PERIOD_YM='" + PERIOD + "'");
    }

    @AfterEach
    void cleanUpAfter() {
        jdbc.execute("DELETE FROM BATCH_RUN_LOG WHERE PERIOD_YM='" + PERIOD + "'");
    }

    @Test
    @DisplayName("집계 중 예외 발생 시 BATCH_RUN_LOG에 STATUS='FAIL' 행이 남는다(REQUIRES_NEW 영속)")
    void run_exception_leaves_fail_log() {
        // given: selectDevAgg 가 RuntimeException 을 던지도록 설정
        when(legacySrMapper.selectDevAgg(anyString()))
                .thenThrow(new RuntimeException("테스트용 강제 예외"));

        // when: run() 이 예외를 전파해야 함
        assertThatThrownBy(() -> service.run(PERIOD, "MANUAL"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("테스트용 강제 예외");

        // then: 메인 트랜잭션이 롤백돼도 BATCH_RUN_LOG에 FAIL 행이 남아 있어야 한다
        Integer failCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM BATCH_RUN_LOG WHERE PERIOD_YM='" + PERIOD + "' AND STATUS='FAIL'",
                Integer.class);
        assertThat(failCount)
                .as("BATCH_RUN_LOG에 STATUS='FAIL' 행이 1건 이상 있어야 한다")
                .isGreaterThanOrEqualTo(1);
    }
}
