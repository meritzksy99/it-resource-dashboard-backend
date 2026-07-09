package com.meritz.dash.config;

import com.meritz.dash.support.AbstractOracleIT;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceConfigIT extends AbstractOracleIT {

    @Autowired @Qualifier("appDataSource") DataSource appDs;
    @Autowired @Qualifier("legacyDataSource") DataSource legacyDs;
    @Autowired @Qualifier("appSqlSessionFactory") SqlSessionFactory appSsf;
    @Autowired @Qualifier("legacySqlSessionFactory") SqlSessionFactory legacySsf;

    @Test
    @DisplayName("두 DataSource/SqlSessionFactory 빈이 모두 뜨고, 기간계 stmt timeout=5")
    void both_datasources_present() throws java.sql.SQLException {
        assertThat(appDs).isNotNull();
        assertThat(legacyDs).isNotNull();
        assertThat(appSsf).isNotNull();
        assertThat(legacySsf.getConfiguration().getDefaultStatementTimeout()).isEqualTo(5);
        // 기간계 DataSource 커넥션은 read-only 여야 한다 (안전규칙)
        try (var con = legacyDs.getConnection()) {
            assertThat(con.isReadOnly()).isTrue();
        }
    }
}
