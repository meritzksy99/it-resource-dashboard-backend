package com.meritz.dash.config;

import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class DashTablesIT extends AbstractOracleIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("V004: DASH 4테이블 생성 확인(SELECT 가능 = 존재)")
    void dash_tables_exist() {
        // 싱글톤/재사용(withReuse) 컨테이너는 다른 IT의 집계 결과가 남아 있을 수 있으므로
        // '빈 테이블' 대신 '테이블이 존재해 SELECT COUNT 가 성공(≥0)'하는지로 생성 여부를 검증한다.
        // (존재하지 않으면 queryForObject 가 예외를 던진다.)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DASH_DEV_AGG", Integer.class)).isNotNegative();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DASH_RESOURCE", Integer.class)).isNotNegative();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DASH_SR_PROJECT", Integer.class)).isNotNegative();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_RUN_LOG", Integer.class)).isNotNegative();
    }
}
