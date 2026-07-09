package com.meritz.dash.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 기간계 모사 테이블(091/093/097)을 싱글톤 Testcontainers 컨테이너에 한 번 로드한다.
 * <p>
 * TBCPPE091M00 존재 여부로 이미 로드됐는지 확인 → 중복 로드 방지.
 * app 테이블(HR_DEVELOPER, CD_COMMON 등)과 테이블명이 겹치지 않으므로 충돌 없음.
 */
public abstract class LegacyFixture extends AbstractOracleIT {

    @Autowired
    @Qualifier("legacyDataSource")
    DataSource legacyDs;

    @BeforeEach
    void loadFixtureOnce() throws Exception {
        try (Connection c = legacyDs.getConnection()) {
            try {
                c.createStatement().execute("SELECT 1 FROM TBCPPE091M00 WHERE ROWNUM = 1");
                return; // 이미 로드됨
            } catch (Exception notLoaded) {
                // 테이블 없음 → 로드 진행
            }
            ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy-fixture/ddl.sql"));
            ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy-fixture/seed.sql"));
        }
    }
}
