package com.meritz.dash.aggregation;

import com.meritz.dash.mapper.app.BatchLogMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * BatchLogMapper.findRecent() 실SQL 통합테스트.
 * <p>
 * @MockBean 없이 실제 DB 를 타서, TRIG_TYPE AS trigType 별칭이 Oracle 예약어 충돌 없이
 * 파싱되고 결과 매핑까지 정상 동작하는지 검증한다.
 * (AS trigger 를 쓰면 이 테스트가 ORA-xxxxx 로 실패 — 회귀 방지용.)
 * </p>
 */
class BatchLogMapperIT extends AbstractOracleIT {

    @Autowired BatchLogMapper batchLogMapper;
    @Autowired JdbcTemplate jdbc;

    private static final String PERIOD = "202699";

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM BATCH_RUN_LOG WHERE PERIOD_YM='" + PERIOD + "'");
        jdbc.execute(
            "INSERT INTO BATCH_RUN_LOG (PERIOD_YM, TRIG_TYPE, STATUS, STARTED_AT, FINISHED_AT, DEV_ROWS, SR_ROWS) " +
            "VALUES ('" + PERIOD + "', 'MANUAL', 'OK', SYSTIMESTAMP, SYSTIMESTAMP, 10, 3)"
        );
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DELETE FROM BATCH_RUN_LOG WHERE PERIOD_YM='" + PERIOD + "'");
    }

    @Test
    @DisplayName("findRecent(): TRIG_TYPE AS trigType 별칭이 Oracle 예약어 충돌 없이 실행된다")
    void findRecent_noException() {
        assertThatCode(() -> batchLogMapper.findRecent())
                .as("findRecent() 가 예약어 오류 없이 실행돼야 한다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("findRecent(): 삽입한 행이 반환되고 trigType 필드가 채워진다")
    void findRecent_trigTypePopulated() {
        List<BatchRunLogView> rows = batchLogMapper.findRecent();

        assertThat(rows).isNotEmpty();

        BatchRunLogView inserted = rows.stream()
                .filter(r -> PERIOD.equals(r.periodYm()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PERIOD_YM=" + PERIOD + " 행을 찾을 수 없다"));

        assertThat(inserted.trigType())
                .as("trigType 은 null 이 아니어야 한다 (TRIG_TYPE 컬럼 매핑 확인)")
                .isNotNull()
                .isEqualTo("MANUAL");
    }
}
