package com.meritz.dash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// DataSource/TxManager/MyBatis 는 AppDataSourceConfig·LegacyDataSourceConfig 에서 @Bean 직접 구성.
// FlywayAutoConfiguration 은 @Primary DataSource(appDataSource) 에만 자동 적용 — 의도적으로 포함.
// 기간계(legacyDataSource) 에 Flyway 가 적용되지 않음을 보장하려면 FlywayAutoConfiguration 을
// exclude 하고 AppDataSourceConfig 에 Flyway @Bean 을 명시하는 방안도 있으나,
// Spring Boot 3.x 는 @Primary DS 우선 정책으로 현재 구성에서 이미 올바르게 동작함.
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        MybatisAutoConfiguration.class
})
public class DashApplication {
    public static void main(String[] args) {
        SpringApplication.run(DashApplication.class, args);
    }
}
