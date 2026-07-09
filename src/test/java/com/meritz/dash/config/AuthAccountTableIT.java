package com.meritz.dash.config;

import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AuthAccountTableIT extends AbstractOracleIT {
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("V007: AUTH_ACCOUNT 테이블 생성 확인")
    void table_exists() {
        // AccountProvisioner(ApplicationRunner)가 컨텍스트 로드 시 재직자 계정을 생성하므로 0 이상이면 OK
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUTH_ACCOUNT", Integer.class))
                .isNotNegative();
    }
}
