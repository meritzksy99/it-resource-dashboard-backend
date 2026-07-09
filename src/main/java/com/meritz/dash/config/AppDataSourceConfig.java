package com.meritz.dash.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.JdbcType;
import org.springframework.context.annotation.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;
import java.util.Objects;

@Configuration
@MapperScan(basePackages = "com.meritz.dash.mapper.app",
        sqlSessionFactoryRef = "appSqlSessionFactory")
public class AppDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("datasource.app")
    public HikariConfig appHikariConfig() {
        return new HikariConfig();
    }

    @Bean
    @Primary
    public DataSource appDataSource(@Qualifier("appHikariConfig") HikariConfig cfg) {
        return new HikariDataSource(cfg);
    }

    @Bean
    @Primary
    public SqlSessionFactory appSqlSessionFactory(@Qualifier("appDataSource") DataSource ds) throws Exception {
        SqlSessionFactoryBean f = new SqlSessionFactoryBean();
        f.setDataSource(ds);
        f.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/app/*.xml"));
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
        cfg.setJdbcTypeForNull(JdbcType.NULL);
        f.setConfiguration(cfg);
        return Objects.requireNonNull(f.getObject(), "appSqlSessionFactory must not be null");
    }

    @Bean
    @Primary
    public JdbcTransactionManager appTxManager(@Qualifier("appDataSource") DataSource ds) {
        return new JdbcTransactionManager(ds);
    }

    /**
     * 재사용 컨테이너(withReuse=true) 환경에서 이전 실행의 failed migration 상태가
     * flyway_schema_history 에 남아 있으면 migrate() 가 거부된다.
     * repair() 를 먼저 실행해 failed 행을 제거한 뒤 migrate() 한다.
     */
    @Bean
    public FlywayMigrationStrategy flywayRepairBeforeMigrate() {
        return flyway -> {
            flyway.repair();
            flyway.migrate();
        };
    }
}
