package com.meritz.dash.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;
import java.util.Objects;

@Configuration
@MapperScan(basePackages = "com.meritz.dash.mapper.legacy",
        sqlSessionFactoryRef = "legacySqlSessionFactory")
public class LegacyDataSourceConfig {

    @Bean
    @ConfigurationProperties("datasource.legacy")
    public HikariConfig legacyHikariConfig() {
        return new HikariConfig();
    }

    @Bean
    public DataSource legacyDataSource(@Qualifier("legacyHikariConfig") HikariConfig cfg) {
        // read-only 와 initializationFailTimeout=-1 은 application.yml datasource.legacy 에 선언됨
        return new HikariDataSource(cfg);
    }

    @Bean
    public SqlSessionFactory legacySqlSessionFactory(@Qualifier("legacyDataSource") DataSource ds) throws Exception {
        SqlSessionFactoryBean f = new SqlSessionFactoryBean();
        f.setDataSource(ds);
        f.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/legacy/*.xml"));
        org.apache.ibatis.session.Configuration mybatis = new org.apache.ibatis.session.Configuration();
        mybatis.setDefaultStatementTimeout(5); // statement timeout 5초
        f.setConfiguration(mybatis);
        return Objects.requireNonNull(f.getObject(), "legacySqlSessionFactory must not be null");
    }

    @Bean
    public JdbcTransactionManager legacyTxManager(@Qualifier("legacyDataSource") DataSource ds) {
        return new JdbcTransactionManager(ds);
    }
}
