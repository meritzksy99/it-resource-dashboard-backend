package com.meritz.dash.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.oracle.OracleContainer;
import java.time.Duration;

// 싱글톤 컨테이너 패턴: @Testcontainers/@Container 자동 라이프사이클을 사용하지 않는다.
// static 초기화 블록에서 한 번 start()하고, JVM 종료까지 유지한다(모든 IT 클래스 공유).
// withReuse(true): ~/.testcontainers.properties 의 testcontainers.reuse.enable=true 가 있어야 실제 재사용 활성화
//                  (CI 환경에서는 해당 프로퍼티가 없으면 매번 새 컨테이너 기동)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractOracleIT {

    static final OracleContainer ORACLE;

    static {
        ORACLE = new OracleContainer("gvenzl/oracle-free:latest")
                .withUsername("appuser")
                .withPassword("apppw")
                .withReuse(true)
                // gvenzl/oracle-free 이미지는 "DATABASE IS READY TO USE!" 로그를 출력한 뒤 접속 가능
                // 첫 기동이 느릴 수 있으므로 timeout 넉넉히 설정
                .waitingFor(Wait.forLogMessage(".*DATABASE IS READY TO USE!.*", 1)
                        .withStartupTimeout(Duration.ofMillis(500000)));
        ORACLE.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("datasource.app.jdbc-url", ORACLE::getJdbcUrl);
        r.add("datasource.app.username", ORACLE::getUsername);
        r.add("datasource.app.password", ORACLE::getPassword);
        // 기간계는 이번 계획에서 미사용 — 같은 컨테이너로 가리켜 부팅만 가능하게
        r.add("datasource.legacy.jdbc-url", ORACLE::getJdbcUrl);
        r.add("datasource.legacy.username", ORACLE::getUsername);
        r.add("datasource.legacy.password", ORACLE::getPassword);
    }
}
