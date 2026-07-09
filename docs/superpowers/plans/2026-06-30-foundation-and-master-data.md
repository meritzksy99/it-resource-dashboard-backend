# 공통기반(A) + 마스터·인력(D) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 2개 Oracle DataSource(기간계 read-only / DB2 CRUD) 위에서 동작하는 Spring Boot 백엔드 골격과, 공통코드·인력 마스터의 조회/CRUD API를 만든다.

**Architecture:** Spring Boot 3.x 단일 모듈. `appDataSource`(DB2, @Primary, CRUD)와 `legacyDataSource`(기간계, read-only)를 `@Bean`으로 직접 구성하고 MyBatis 매퍼를 데이터소스별로 분리 스캔한다. DB2 스키마는 Flyway 버전 마이그레이션으로 관리한다. 이번 계획에는 기간계를 실제로 읽는 코드는 없고(데이터소스 구성만), 대시보드 집계(C)는 후속 계획서에서 다룬다.

**Tech Stack:** Java 21, Spring Boot 3.3.x, Gradle(Groovy), MyBatis(mybatis-spring-boot-starter 3.0.x), Oracle JDBC(ojdbc11), Flyway(core + database-oracle), springdoc-openapi 2.x, JUnit5 + AssertJ + Mockito + Testcontainers(oracle-free).

## Global Constraints

- **Java 21**, **Spring Boot 3.x**, Gradle Groovy DSL. 영속성은 **MyBatis**(JPA 금지).
- **DataSource 2개**: `legacyDataSource`(기간계, **SELECT 전용**, read-only, 풀 ≤8, statement timeout 5초, 시작 실패해도 앱 부팅) / `appDataSource`(DB2, `@Primary`, CRUD). 각자 SqlSessionFactory·TxManager·MapperScan(`com.meritz.dash.mapper.legacy` / `com.meritz.dash.mapper.app`)·XML 폴더(`mapper/legacy` / `mapper/app`) 분리.
- 기간계 매퍼에 **INSERT/UPDATE/DELETE/MERGE/DDL 금지(SELECT만)**. 값 주입은 `#{}` 만, `${}`는 화이트리스트 정렬/컬럼만.
- **기간계가 죽어도 앱은 부팅·동작**해야 한다(Hikari `initializationFailTimeout=-1`).
- DB2 DDL은 `db/migration/VNNN__설명.sql` 버전 파일로만 관리(기존 파일 수정 금지, 변경은 새 파일). **19c 호환 문법만**(예: `BOOLEAN` 컬럼 금지, 플래그는 `CHAR(1) 'Y'/'N'`).
- 응답 envelope `{ "data":…, "meta":… }`. 에러는 RFC 7807 `ProblemDetail`(`application/problem+json`). 베이스 경로 `/api/v1`, 리소스 복수형 kebab-case. 모든 엔드포인트 springdoc 문서화.
- DTO는 `record`, 컨트롤러는 DTO만 반환(row/엔티티 누출 금지). **생성자 주입만**(필드 주입 금지). 매직넘버 금지.
- 접속정보/비밀번호는 소스 커밋 금지(환경변수/외부설정). `server.address=0.0.0.0` 바인딩.
- 기본 패키지: `com.meritz.dash`. 매 작업은 TDD(Red→Green→Refactor) + 작은 단위 커밋.

---

## File Structure

```
build.gradle, settings.gradle, gradle/ (wrapper)
docker-compose.yml                         # 테스트용 Oracle 2개(app:1521, legacy:1522)
src/main/java/com/meritz/dash/
  DashApplication.java
  common/ApiResponse.java                  # record {data, meta}
  common/GlobalExceptionHandler.java       # @RestControllerAdvice → ProblemDetail
  config/AppDataSourceConfig.java          # @Primary DB2: DS·SqlSessionFactory·TxManager·@MapperScan(mapper.app)
  config/LegacyDataSourceConfig.java       # 기간계: read-only DS·SqlSessionFactory·TxManager·@MapperScan(mapper.legacy)
  health/HealthController.java             # GET /api/v1/health
  code/CommonCode.java                     # record (조회 응답)
  code/CodeService.java
  code/CodeController.java                 # GET /api/v1/codes?grpCd=
  developer/Developer.java                 # record (조회 응답)
  developer/DeveloperRequest.java          # record (등록/수정 입력, validation)
  developer/DeveloperService.java
  developer/DeveloperController.java       # GET/POST/PUT/DELETE /api/v1/developers
  mapper/app/CodeMapper.java
  mapper/app/DeveloperMapper.java
src/main/resources/
  application.yml                          # 공통 + local 프로파일
  mapper/app/CodeMapper.xml
  mapper/app/DeveloperMapper.xml
  db/migration/V001__create_master_tables.sql
  db/migration/V002__seed_master_data.sql
src/test/java/com/meritz/dash/
  support/AbstractOracleIT.java            # Testcontainers oracle-free 베이스
  health/HealthControllerTest.java
  code/CodeMapperIT.java
  code/CodeControllerTest.java
  developer/DeveloperMapperIT.java
  developer/DeveloperControllerTest.java
```

---

## Task 1: 프로젝트 부트스트랩 + 헬스 체크

**Files:**
- Create: `build.gradle`, `settings.gradle`
- Create: `src/main/java/com/meritz/dash/DashApplication.java`
- Create: `src/main/java/com/meritz/dash/common/ApiResponse.java`
- Create: `src/main/java/com/meritz/dash/common/GlobalExceptionHandler.java`
- Create: `src/main/java/com/meritz/dash/health/HealthController.java`
- Create: `src/main/resources/application.yml`
- Test: `src/test/java/com/meritz/dash/health/HealthControllerTest.java`

**Interfaces:**
- Produces: `ApiResponse<T>(T data, Object meta)` with static `ApiResponse.of(T)` and `ApiResponse.of(T, Object)`.
- Produces: `GET /api/v1/health` → `200` body `{"data":{"status":"UP","timestamp":"…"},"meta":null}`.

- [ ] **Step 1: build.gradle / settings.gradle 작성**

`settings.gradle`:
```groovy
rootProject.name = 'it-dash'
```

`build.gradle`:
```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.5'
    id 'io.spring.dependency-management' version '1.1.6'
}
group = 'com.meritz'
version = '0.0.1-SNAPSHOT'
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories { mavenCentral() }
dependencyManagement {
    imports { mavenBom 'org.testcontainers:testcontainers-bom:1.20.3' }
}
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.3'
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-oracle'
    runtimeOnly 'com.oracle.database.jdbc:ojdbc11'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.testcontainers:junit-jupiter'
    testImplementation 'org.testcontainers:oracle-free'
}
test { useJUnitPlatform() }
```

- [ ] **Step 2: Gradle wrapper 생성**

Run: `gradle wrapper --gradle-version 8.10`
(시스템에 gradle이 없으면 `brew install gradle` 또는 sdkman. 이후엔 `./gradlew` 사용.)
Expected: `gradlew`, `gradle/wrapper/` 생성.

- [ ] **Step 3: DashApplication 작성 (DB 자동설정 제외)**

```java
package com.meritz.dash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;

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
```

- [ ] **Step 4: ApiResponse envelope 작성**

```java
package com.meritz.dash.common;

public record ApiResponse<T>(T data, Object meta) {
    public static <T> ApiResponse<T> of(T data) { return new ApiResponse<>(data, null); }
    public static <T> ApiResponse<T> of(T data, Object meta) { return new ApiResponse<>(data, meta); }
}
```

- [ ] **Step 5: GlobalExceptionHandler 작성 (ProblemDetail)**

```java
package com.meritz.dash.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("검증 실패");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
```

- [ ] **Step 6: 실패하는 헬스 테스트 작성**

```java
package com.meritz.dash.health;

import com.meritz.dash.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HealthController.class)
@Import(GlobalExceptionHandler.class)
class HealthControllerTest {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("GET /api/v1/health → data.status=UP")
    void health_returns_up() throws Exception {
        mvc.perform(get("/api/v1/health"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.status").value("UP"))
           .andExpect(jsonPath("$.data.timestamp").exists());
    }
}
```

- [ ] **Step 7: 테스트 실패 확인**

Run: `./gradlew test --tests '*HealthControllerTest'`
Expected: 컴파일 에러 또는 FAIL (`HealthController` 미존재).

- [ ] **Step 8: HealthController 구현**

```java
package com.meritz.dash.health;

import com.meritz.dash.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.of(Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString()
        ));
    }
}
```

- [ ] **Step 9: application.yml 작성**

```yaml
spring:
  application:
    name: it-dash
  profiles:
    active: local
server:
  address: 0.0.0.0
  port: 8080
springdoc:
  swagger-ui:
    path: /swagger-ui
```

- [ ] **Step 10: 테스트 통과 확인**

Run: `./gradlew test --tests '*HealthControllerTest'`
Expected: PASS.

- [ ] **Step 11: 커밋**

```bash
git add build.gradle settings.gradle gradlew gradle settings.gradle src/main/java/com/meritz/dash/DashApplication.java src/main/java/com/meritz/dash/common src/main/java/com/meritz/dash/health src/main/resources/application.yml src/test/java/com/meritz/dash/health
git commit -m "feat: 프로젝트 부트스트랩 + 헬스 체크(/api/v1/health) envelope/ProblemDetail"
```

---

## Task 2: 테스트용 Oracle 2개(docker-compose) + 로컬 접속 설정

**Files:**
- Create: `docker-compose.yml`
- Modify: `src/main/resources/application.yml` (datasource.app / datasource.legacy 추가)

**Interfaces:**
- Produces: 로컬 실행 시 `datasource.app`(DB2, 1521) / `datasource.legacy`(기간계, 1522) 접속 프로퍼티. HikariConfig 바인딩 키(`jdbc-url`,`username`,`password`,`maximum-pool-size`,`connection-timeout`,`pool-name`).

- [ ] **Step 1: docker-compose.yml 작성 (app=1521, legacy=1522)**

```yaml
services:
  oracle-app:
    image: gvenzl/oracle-free:slim-faststart
    container_name: dash-oracle-app
    ports: ["1521:1521"]
    environment:
      ORACLE_PASSWORD: oracle
      APP_USER: appuser
      APP_USER_PASSWORD: apppw
  oracle-legacy:
    image: gvenzl/oracle-free:slim-faststart
    container_name: dash-oracle-legacy
    ports: ["1522:1521"]
    environment:
      ORACLE_PASSWORD: oracle
      APP_USER: legacyuser
      APP_USER_PASSWORD: legacypw
```

> 기존 `oracle-test` 컨테이너가 1521을 쓰면 충돌한다. 먼저 `docker stop oracle-test` 하거나 compose 포트를 조정한다.

- [ ] **Step 2: 컨테이너 기동 및 헬스 확인**

Run: `docker compose up -d && sleep 60 && docker compose ps`
Expected: `dash-oracle-app`, `dash-oracle-legacy` 두 컨테이너가 healthy.

- [ ] **Step 3: application.yml에 두 DataSource 프로퍼티 추가**

`application.yml`의 `server:` 블록 아래에 추가:
```yaml
datasource:
  app:
    jdbc-url: jdbc:oracle:thin:@localhost:1521/FREEPDB1
    username: appuser
    password: ${APP_DB_PASSWORD:apppw}
    maximum-pool-size: 10
    pool-name: app-pool
  legacy:
    jdbc-url: jdbc:oracle:thin:@localhost:1522/FREEPDB1
    username: legacyuser
    password: ${LEGACY_DB_PASSWORD:legacypw}
    maximum-pool-size: 8
    connection-timeout: 3000
    pool-name: legacy-pool
```

- [ ] **Step 4: 커밋**

```bash
git add docker-compose.yml src/main/resources/application.yml
git commit -m "chore: 테스트용 Oracle 2개 docker-compose + 두 DataSource 접속 설정"
```

---

## Task 3: 2개 DataSource + MyBatis + Flyway 구성

**Files:**
- Create: `src/main/java/com/meritz/dash/config/AppDataSourceConfig.java`
- Create: `src/main/java/com/meritz/dash/config/LegacyDataSourceConfig.java`
- Create: `src/test/java/com/meritz/dash/support/AbstractOracleIT.java`
- Create: `src/test/java/com/meritz/dash/config/DataSourceConfigIT.java`
- Modify: `src/main/resources/application.yml` (flyway 설정)

**Interfaces:**
- Consumes: `datasource.app.*`, `datasource.legacy.*` (Task 2).
- Produces: 빈 `appDataSource(@Primary)`, `appSqlSessionFactory`, `appTxManager`; `legacyDataSource`, `legacySqlSessionFactory`, `legacyTxManager`. `@MapperScan` 베이스: `com.meritz.dash.mapper.app` / `com.meritz.dash.mapper.legacy`. 기간계 SqlSessionFactory의 `defaultStatementTimeout=5`.
- Produces: `AbstractOracleIT` — Testcontainers `oracle-free` 컨테이너를 띄우고 `datasource.app.*`/`datasource.legacy.*`를 동적 주입하는 통합테스트 베이스. 상속 시 Flyway 마이그레이션이 자동 적용됨.

- [ ] **Step 1: AppDataSourceConfig 작성 (@Primary, mapper.app)**

```java
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
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

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
        return f.getObject();
    }

    @Bean
    @Primary
    public DataSourceTransactionManager appTxManager(@Qualifier("appDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
}
```

- [ ] **Step 2: LegacyDataSourceConfig 작성 (read-only, timeout 5s, mapper.legacy)**

```java
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

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
        cfg.setReadOnly(true);
        cfg.setInitializationFailTimeout(-1); // 기간계가 죽어도 앱은 부팅
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
        return f.getObject();
    }

    @Bean
    public DataSourceTransactionManager legacyTxManager(@Qualifier("legacyDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
}
```

- [ ] **Step 3: application.yml에 Flyway 설정 추가 (app 데이터소스에만)**

`spring:` 블록 아래에 추가:
```yaml
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```
> Flyway는 `@Primary`인 `appDataSource`(DB2)에만 적용된다. 기간계는 절대 마이그레이션하지 않는다.

- [ ] **Step 4: AbstractOracleIT 베이스 작성**

```java
package com.meritz.dash.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

@SpringBootTest
@Testcontainers
public abstract class AbstractOracleIT {

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:slim-faststart")
            .withUsername("appuser")
            .withPassword("apppw")
            .withReuse(true);

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
```

- [ ] **Step 5: 실패하는 컨텍스트 통합테스트 작성**

```java
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
    void both_datasources_present() {
        assertThat(appDs).isNotNull();
        assertThat(legacyDs).isNotNull();
        assertThat(appSsf).isNotNull();
        assertThat(legacySsf.getConfiguration().getDefaultStatementTimeout()).isEqualTo(5);
    }
}
```

- [ ] **Step 6: 테스트 실패 확인**

Run: `./gradlew test --tests '*DataSourceConfigIT'`
Expected: FAIL/컴파일 에러 (config 빈 미존재). 첫 실행은 Testcontainers 이미지 pull로 수 분 소요 가능.

- [ ] **Step 7: 테스트 통과 확인 (구현은 Step 1-2에서 완료)**

Run: `./gradlew test --tests '*DataSourceConfigIT'`
Expected: PASS (빈 4종 주입, timeout=5).

- [ ] **Step 8: 커밋**

```bash
git add src/main/java/com/meritz/dash/config src/test/java/com/meritz/dash/support src/test/java/com/meritz/dash/config src/main/resources/application.yml
git commit -m "feat: 기간계/DB2 2개 DataSource + MyBatis 분리 + Flyway + Testcontainers 베이스"
```

---

## Task 4: DB2 마스터 테이블 마이그레이션 + 시드

**Files:**
- Create: `src/main/resources/db/migration/V001__create_master_tables.sql`
- Create: `src/main/resources/db/migration/V002__seed_master_data.sql`

**Interfaces:**
- Produces: 테이블 `CD_COMMON(GRP_CD, CD_VAL, CD_NM, SORT_NO, USE_YN, 감사…)`, `HR_DEVELOPER(EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD, 감사…)`.
- Produces: 시드 — `CD_COMMON` 그룹 `SR_TPCD`(1,2,3,5,17,18,19), `EMP_ROLE`(팀장/업무리더/일반직원), `EMP_STATUS`(재직/휴직); `HR_DEVELOPER` 팀장 1명 + 일반직원 3명.

- [ ] **Step 1: V001 DDL 작성 (19c 호환)**

```sql
-- V001: 공통코드 / 인력 마스터 (DB2, Oracle 19c 호환)
CREATE TABLE CD_COMMON (
  GRP_CD     VARCHAR2(30)  NOT NULL,
  CD_VAL     VARCHAR2(30)  NOT NULL,
  CD_NM      VARCHAR2(100) NOT NULL,
  SORT_NO    NUMBER(5)     DEFAULT 0 NOT NULL,
  USE_YN     CHAR(1)       DEFAULT 'Y' NOT NULL,
  CREATED_AT TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  CREATED_BY VARCHAR2(30)  DEFAULT 'SYSTEM' NOT NULL,
  UPDATED_AT TIMESTAMP,
  UPDATED_BY VARCHAR2(30),
  CONSTRAINT PK_CD_COMMON PRIMARY KEY (GRP_CD, CD_VAL),
  CONSTRAINT CK_CD_COMMON_USE_YN CHECK (USE_YN IN ('Y','N'))
);

CREATE TABLE HR_DEVELOPER (
  EMPNO      VARCHAR2(20)  NOT NULL,
  EMP_NM     VARCHAR2(50)  NOT NULL,
  DEPT_CD    VARCHAR2(30),
  PART_CD    VARCHAR2(30),
  GRADE_CD   VARCHAR2(30),
  ROLE_CD    VARCHAR2(30),
  DEV_YN     CHAR(1)       DEFAULT 'Y' NOT NULL,
  STATUS_CD  VARCHAR2(10)  DEFAULT '재직' NOT NULL,
  CREATED_AT TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  CREATED_BY VARCHAR2(30)  DEFAULT 'SYSTEM' NOT NULL,
  UPDATED_AT TIMESTAMP,
  UPDATED_BY VARCHAR2(30),
  CONSTRAINT PK_HR_DEVELOPER PRIMARY KEY (EMPNO),
  CONSTRAINT CK_HR_DEV_YN CHECK (DEV_YN IN ('Y','N')),
  CONSTRAINT CK_HR_STATUS CHECK (STATUS_CD IN ('재직','휴직'))
);

CREATE INDEX IX_HR_DEVELOPER_PART ON HR_DEVELOPER (PART_CD);
```

- [ ] **Step 2: V002 시드 작성**

```sql
-- V002: 공통코드 / 인력 시드
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('SR_TPCD','1','개발요청',1);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('SR_TPCD','2','유지보수',2);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('SR_TPCD','3','자료요청',3);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('SR_TPCD','5','인프라SR',4);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('SR_TPCD','17','고객안내 발송',5);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('SR_TPCD','18','데이타변경',6);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('SR_TPCD','19','원장변경',7);

INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('EMP_ROLE','01','팀장',1);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('EMP_ROLE','02','업무리더',2);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('EMP_ROLE','03','일반직원',3);

INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('EMP_STATUS','재직','재직',1);
INSERT INTO CD_COMMON (GRP_CD, CD_VAL, CD_NM, SORT_NO) VALUES ('EMP_STATUS','휴직','휴직',2);

INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('E0001','김팀장','D101','P01','부장','01','N','재직');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('E0002','이개발','D101','P01','과장','03','Y','재직');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('E0003','박개발','D101','P02','대리','03','Y','재직');
INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
  VALUES ('E0004','최개발','D101','P02','사원','03','Y','재직');
```
> ROLE_CD는 `CD_COMMON.EMP_ROLE`의 코드값('01'/'02'/'03')을 저장한다(매직 문자열 금지). 화면 표기명은 조회 시 코드 조인.

- [ ] **Step 3: 마이그레이션 적용 검증 테스트**

```java
package com.meritz.dash.config;

import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationIT extends AbstractOracleIT {

    @Autowired JdbcTemplate jdbc; // @Primary appDataSource 사용

    @Test
    @DisplayName("V001/V002 적용 후 SR_TPCD 7건, 인력 4건")
    void migration_applied() {
        Integer codes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM CD_COMMON WHERE GRP_CD='SR_TPCD'", Integer.class);
        Integer devs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM HR_DEVELOPER", Integer.class);
        assertThat(codes).isEqualTo(7);
        assertThat(devs).isEqualTo(4);
    }
}
```
> `JdbcTemplate`은 `@Primary` 데이터소스(app)로 자동 구성된다. 별도 빈 불필요.

- [ ] **Step 4: 테스트 실행 (실패→통과)**

Run: `./gradlew test --tests '*MigrationIT'`
Expected: 처음엔 테이블/시드 없으면 FAIL → V001/V002 작성 후 PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/resources/db/migration src/test/java/com/meritz/dash/config/MigrationIT.java
git commit -m "feat: DB2 마스터 테이블(CD_COMMON, HR_DEVELOPER) 마이그레이션 + 시드"
```

---

## Task 5: 공통코드 조회 API (`GET /api/v1/codes`)

**Files:**
- Create: `src/main/java/com/meritz/dash/code/CommonCode.java`
- Create: `src/main/java/com/meritz/dash/mapper/app/CodeMapper.java`
- Create: `src/main/resources/mapper/app/CodeMapper.xml`
- Create: `src/main/java/com/meritz/dash/code/CodeService.java`
- Create: `src/main/java/com/meritz/dash/code/CodeController.java`
- Test: `src/test/java/com/meritz/dash/code/CodeMapperIT.java`
- Test: `src/test/java/com/meritz/dash/code/CodeControllerTest.java`

**Interfaces:**
- Produces: `record CommonCode(String grpCd, String cdVal, String cdNm, int sortNo)`.
- Produces: `CodeMapper.findByGroup(String grpCd) : List<CommonCode>` (USE_YN='Y'만, SORT_NO 정렬).
- Produces: `CodeService.getCodes(String grpCd) : List<CommonCode>` (grpCd 공백이면 `IllegalArgumentException`).
- Produces: `GET /api/v1/codes?grpCd=SR_TPCD` → `{"data":[CommonCode…],"meta":{"grpCd":…,"count":…}}`.

- [ ] **Step 1: CommonCode record 작성**

```java
package com.meritz.dash.code;

public record CommonCode(String grpCd, String cdVal, String cdNm, int sortNo) {}
```

- [ ] **Step 2: 실패하는 매퍼 통합테스트 작성**

```java
package com.meritz.dash.code;

import com.meritz.dash.mapper.app.CodeMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeMapperIT extends AbstractOracleIT {

    @Autowired CodeMapper codeMapper;

    @Test
    @DisplayName("findByGroup('SR_TPCD') → 7건, SORT_NO 오름차순, 첫 코드=개발요청")
    void findByGroup_srtpcd() {
        List<CommonCode> codes = codeMapper.findByGroup("SR_TPCD");
        assertThat(codes).hasSize(7);
        assertThat(codes.get(0).cdVal()).isEqualTo("1");
        assertThat(codes.get(0).cdNm()).isEqualTo("개발요청");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests '*CodeMapperIT'`
Expected: FAIL (`CodeMapper` 미존재).

- [ ] **Step 4: CodeMapper 인터페이스 + XML 작성**

`src/main/java/com/meritz/dash/mapper/app/CodeMapper.java`:
```java
package com.meritz.dash.mapper.app;

import com.meritz.dash.code.CommonCode;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CodeMapper {
    List<CommonCode> findByGroup(@Param("grpCd") String grpCd);
}
```

`src/main/resources/mapper/app/CodeMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.meritz.dash.mapper.app.CodeMapper">
  <select id="findByGroup" resultType="com.meritz.dash.code.CommonCode">
    SELECT GRP_CD AS grpCd, CD_VAL AS cdVal, CD_NM AS cdNm, SORT_NO AS sortNo
      FROM CD_COMMON
     WHERE GRP_CD = #{grpCd}
       AND USE_YN = 'Y'
     ORDER BY SORT_NO
  </select>
</mapper>
```

- [ ] **Step 5: 매퍼 테스트 통과 확인**

Run: `./gradlew test --tests '*CodeMapperIT'`
Expected: PASS.

- [ ] **Step 6: CodeService 작성**

```java
package com.meritz.dash.code;

import com.meritz.dash.mapper.app.CodeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CodeService {

    private final CodeMapper codeMapper;

    public CodeService(CodeMapper codeMapper) {
        this.codeMapper = codeMapper;
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<CommonCode> getCodes(String grpCd) {
        if (grpCd == null || grpCd.isBlank()) {
            throw new IllegalArgumentException("grpCd는 필수입니다.");
        }
        return codeMapper.findByGroup(grpCd);
    }
}
```

- [ ] **Step 7: 실패하는 컨트롤러 계약테스트 작성**

```java
package com.meritz.dash.code;

import com.meritz.dash.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CodeController.class)
@Import(GlobalExceptionHandler.class)
class CodeControllerTest {

    @Autowired MockMvc mvc;
    @MockBean CodeService codeService;

    @Test
    @DisplayName("GET /api/v1/codes?grpCd=SR_TPCD → data 배열 + meta.count")
    void get_codes_ok() throws Exception {
        when(codeService.getCodes("SR_TPCD"))
                .thenReturn(List.of(new CommonCode("SR_TPCD", "1", "개발요청", 1)));
        mvc.perform(get("/api/v1/codes").param("grpCd", "SR_TPCD"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].cdNm").value("개발요청"))
           .andExpect(jsonPath("$.meta.count").value(1));
    }

    @Test
    @DisplayName("grpCd 누락 → 400 ProblemDetail")
    void get_codes_missing_grp() throws Exception {
        mvc.perform(get("/api/v1/codes"))
           .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 8: 테스트 실패 확인**

Run: `./gradlew test --tests '*CodeControllerTest'`
Expected: FAIL (`CodeController` 미존재).

- [ ] **Step 9: CodeController 구현**

```java
package com.meritz.dash.code;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/codes")
public class CodeController {

    private final CodeService codeService;

    public CodeController(CodeService codeService) {
        this.codeService = codeService;
    }

    @Operation(summary = "공통코드 그룹 조회")
    @GetMapping
    public ApiResponse<List<CommonCode>> getCodes(@RequestParam(required = false) String grpCd) {
        List<CommonCode> codes = codeService.getCodes(grpCd);
        return ApiResponse.of(codes, Map.of("grpCd", grpCd == null ? "" : grpCd, "count", codes.size()));
    }
}
```

- [ ] **Step 10: 컨트롤러 테스트 통과 확인**

Run: `./gradlew test --tests '*CodeControllerTest'`
Expected: PASS (정상 200, grpCd 누락 시 `IllegalArgumentException`→400).

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/meritz/dash/code src/main/java/com/meritz/dash/mapper/app/CodeMapper.java src/main/resources/mapper/app/CodeMapper.xml src/test/java/com/meritz/dash/code
git commit -m "feat: 공통코드 조회 API GET /api/v1/codes (CD_COMMON)"
```

---

## Task 6: 인력 조회 API (`GET /api/v1/developers`, `/{empno}`)

**Files:**
- Create: `src/main/java/com/meritz/dash/developer/Developer.java`
- Create: `src/main/java/com/meritz/dash/mapper/app/DeveloperMapper.java`
- Create: `src/main/resources/mapper/app/DeveloperMapper.xml`
- Create: `src/main/java/com/meritz/dash/developer/DeveloperService.java`
- Create: `src/main/java/com/meritz/dash/developer/DeveloperController.java`
- Test: `src/test/java/com/meritz/dash/developer/DeveloperMapperIT.java`
- Test: `src/test/java/com/meritz/dash/developer/DeveloperControllerTest.java`

**Interfaces:**
- Produces: `record Developer(String empno, String empNm, String deptCd, String partCd, String gradeCd, String roleCd, String devYn, String statusCd)`.
- Produces: `DeveloperMapper.findAll(String partCd, String devYn, String statusCd) : List<Developer>` (모든 인자 null 허용=무필터); `findByEmpno(String) : Developer`(없으면 null); `insert(Developer)`, `update(Developer) : int`, `deleteByEmpno(String) : int` (Task 7에서 사용).
- Produces: `DeveloperService.list(...)`, `get(String empno)`(없으면 `IllegalArgumentException`).
- Produces: `GET /api/v1/developers` → `{"data":[…],"meta":{"count":…}}`; `GET /api/v1/developers/{empno}` → `{"data":{…}}` 또는 404.

- [ ] **Step 1: Developer record 작성**

```java
package com.meritz.dash.developer;

public record Developer(
        String empno, String empNm, String deptCd, String partCd,
        String gradeCd, String roleCd, String devYn, String statusCd) {}
```

- [ ] **Step 2: 실패하는 매퍼 통합테스트 작성**

```java
package com.meritz.dash.developer;

import com.meritz.dash.mapper.app.DeveloperMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperMapperIT extends AbstractOracleIT {

    @Autowired DeveloperMapper mapper;

    @Test
    @DisplayName("findAll(devYn='Y') → 시드 개발자 3명")
    void findAll_dev_only() {
        List<Developer> devs = mapper.findAll(null, "Y", null);
        assertThat(devs).hasSize(3);
        assertThat(devs).allMatch(d -> "Y".equals(d.devYn()));
    }

    @Test
    @DisplayName("findByEmpno('E0001') → 김팀장")
    void findByEmpno() {
        Developer d = mapper.findByEmpno("E0001");
        assertThat(d).isNotNull();
        assertThat(d.empNm()).isEqualTo("김팀장");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew test --tests '*DeveloperMapperIT'`
Expected: FAIL (`DeveloperMapper` 미존재).

- [ ] **Step 4: DeveloperMapper 인터페이스 + XML 작성 (CRUD 전체 선언)**

`src/main/java/com/meritz/dash/mapper/app/DeveloperMapper.java`:
```java
package com.meritz.dash.mapper.app;

import com.meritz.dash.developer.Developer;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DeveloperMapper {
    List<Developer> findAll(@Param("partCd") String partCd,
                            @Param("devYn") String devYn,
                            @Param("statusCd") String statusCd);
    Developer findByEmpno(@Param("empno") String empno);
    int insert(Developer dev);
    int update(Developer dev);
    int deleteByEmpno(@Param("empno") String empno);
}
```

`src/main/resources/mapper/app/DeveloperMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.meritz.dash.mapper.app.DeveloperMapper">

  <sql id="cols">
    EMPNO AS empno, EMP_NM AS empNm, DEPT_CD AS deptCd, PART_CD AS partCd,
    GRADE_CD AS gradeCd, ROLE_CD AS roleCd, DEV_YN AS devYn, STATUS_CD AS statusCd
  </sql>

  <select id="findAll" resultType="com.meritz.dash.developer.Developer">
    SELECT <include refid="cols"/>
      FROM HR_DEVELOPER
     <where>
       <if test="partCd != null">AND PART_CD = #{partCd}</if>
       <if test="devYn != null">AND DEV_YN = #{devYn}</if>
       <if test="statusCd != null">AND STATUS_CD = #{statusCd}</if>
     </where>
     ORDER BY EMPNO
  </select>

  <select id="findByEmpno" resultType="com.meritz.dash.developer.Developer">
    SELECT <include refid="cols"/>
      FROM HR_DEVELOPER
     WHERE EMPNO = #{empno}
  </select>

  <insert id="insert">
    INSERT INTO HR_DEVELOPER
      (EMPNO, EMP_NM, DEPT_CD, PART_CD, GRADE_CD, ROLE_CD, DEV_YN, STATUS_CD)
    VALUES
      (#{empno}, #{empNm}, #{deptCd}, #{partCd}, #{gradeCd}, #{roleCd}, #{devYn}, #{statusCd})
  </insert>

  <update id="update">
    UPDATE HR_DEVELOPER
       SET EMP_NM = #{empNm}, DEPT_CD = #{deptCd}, PART_CD = #{partCd},
           GRADE_CD = #{gradeCd}, ROLE_CD = #{roleCd}, DEV_YN = #{devYn},
           STATUS_CD = #{statusCd}, UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = 'SYSTEM'
     WHERE EMPNO = #{empno}
  </update>

  <delete id="deleteByEmpno">
    DELETE FROM HR_DEVELOPER WHERE EMPNO = #{empno}
  </delete>
</mapper>
```

- [ ] **Step 5: 매퍼 테스트 통과 확인**

Run: `./gradlew test --tests '*DeveloperMapperIT'`
Expected: PASS.

- [ ] **Step 6: DeveloperService 작성 (조회)**

```java
package com.meritz.dash.developer;

import com.meritz.dash.mapper.app.DeveloperMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DeveloperService {

    private final DeveloperMapper mapper;

    public DeveloperService(DeveloperMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<Developer> list(String partCd, String devYn, String statusCd) {
        return mapper.findAll(partCd, devYn, statusCd);
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public Developer get(String empno) {
        Developer d = mapper.findByEmpno(empno);
        if (d == null) {
            throw new IllegalArgumentException("사번 " + empno + " 인력이 없습니다.");
        }
        return d;
    }
}
```

- [ ] **Step 7: 실패하는 컨트롤러 계약테스트 작성**

```java
package com.meritz.dash.developer;

import com.meritz.dash.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DeveloperController.class)
@Import(GlobalExceptionHandler.class)
class DeveloperControllerTest {

    @Autowired MockMvc mvc;
    @MockBean DeveloperService service;

    @Test
    @DisplayName("GET /api/v1/developers → data 배열 + meta.count")
    void list_ok() throws Exception {
        when(service.list(any(), any(), any())).thenReturn(List.of(
                new Developer("E0002", "이개발", "D101", "P01", "과장", "03", "Y", "재직")));
        mvc.perform(get("/api/v1/developers"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].empno").value("E0002"))
           .andExpect(jsonPath("$.meta.count").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/developers/{empno} 미존재 → 400 ProblemDetail")
    void get_not_found() throws Exception {
        when(service.get("E9999")).thenThrow(new IllegalArgumentException("사번 E9999 인력이 없습니다."));
        mvc.perform(get("/api/v1/developers/E9999"))
           .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 8: 테스트 실패 확인**

Run: `./gradlew test --tests '*DeveloperControllerTest'`
Expected: FAIL (`DeveloperController` 미존재).

- [ ] **Step 9: DeveloperController 구현 (조회 부분)**

```java
package com.meritz.dash.developer;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/developers")
public class DeveloperController {

    private final DeveloperService service;

    public DeveloperController(DeveloperService service) {
        this.service = service;
    }

    @Operation(summary = "인력 목록 조회")
    @GetMapping
    public ApiResponse<List<Developer>> list(@RequestParam(required = false) String part,
                                             @RequestParam(required = false) String devYn,
                                             @RequestParam(required = false) String status) {
        List<Developer> devs = service.list(part, devYn, status);
        return ApiResponse.of(devs, Map.of("count", devs.size()));
    }

    @Operation(summary = "인력 단건 조회")
    @GetMapping("/{empno}")
    public ApiResponse<Developer> get(@PathVariable String empno) {
        return ApiResponse.of(service.get(empno));
    }
}
```

- [ ] **Step 10: 컨트롤러 테스트 통과 확인**

Run: `./gradlew test --tests '*DeveloperControllerTest'`
Expected: PASS.

- [ ] **Step 11: 커밋**

```bash
git add src/main/java/com/meritz/dash/developer src/main/java/com/meritz/dash/mapper/app/DeveloperMapper.java src/main/resources/mapper/app/DeveloperMapper.xml src/test/java/com/meritz/dash/developer
git commit -m "feat: 인력 조회 API GET /api/v1/developers, /{empno} (HR_DEVELOPER)"
```

---

## Task 7: 인력 등록/수정/삭제 (`POST/PUT/DELETE /api/v1/developers`)

**Files:**
- Create: `src/main/java/com/meritz/dash/developer/DeveloperRequest.java`
- Modify: `src/main/java/com/meritz/dash/developer/DeveloperService.java` (create/update/delete 추가)
- Modify: `src/main/java/com/meritz/dash/developer/DeveloperController.java` (POST/PUT/DELETE 추가)
- Modify: `src/test/java/com/meritz/dash/developer/DeveloperMapperIT.java` (insert/update/delete 검증 추가)
- Modify: `src/test/java/com/meritz/dash/developer/DeveloperControllerTest.java` (POST 검증 추가)

**Interfaces:**
- Consumes: `DeveloperMapper.insert/update/deleteByEmpno` (Task 6), `Developer` record.
- Produces: `record DeveloperRequest(@NotBlank empno, @NotBlank empNm, deptCd, partCd, gradeCd, roleCd, @Pattern(Y|N) devYn, statusCd)` + `toDeveloper()`.
- Produces: `DeveloperService.create(DeveloperRequest) : Developer`(중복 사번이면 `IllegalArgumentException`), `update(empno, DeveloperRequest) : Developer`, `delete(empno)`.
- Produces: `POST /api/v1/developers` → 201; `PUT /api/v1/developers/{empno}` → 200; `DELETE /api/v1/developers/{empno}` → 204.

- [ ] **Step 1: DeveloperRequest record 작성 (validation)**

```java
package com.meritz.dash.developer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DeveloperRequest(
        @NotBlank String empno,
        @NotBlank String empNm,
        String deptCd,
        String partCd,
        String gradeCd,
        String roleCd,
        @Pattern(regexp = "Y|N", message = "devYn은 Y 또는 N") String devYn,
        String statusCd) {

    public Developer toDeveloper() {
        return new Developer(empno, empNm, deptCd, partCd, gradeCd, roleCd,
                devYn == null ? "Y" : devYn,
                statusCd == null ? "재직" : statusCd);
    }
}
```

- [ ] **Step 2: 실패하는 매퍼 CRUD 테스트 추가 (DeveloperMapperIT에 메서드 추가)**

```java
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("insert→update→delete 라운드트립")
    void crud_roundtrip() {
        Developer n = new Developer("E9001", "신규자", "D101", "P03", "사원", "03", "Y", "재직");
        assertThat(mapper.insert(n)).isEqualTo(1);
        assertThat(mapper.findByEmpno("E9001").empNm()).isEqualTo("신규자");

        Developer u = new Developer("E9001", "수정자", "D101", "P03", "대리", "03", "Y", "휴직");
        assertThat(mapper.update(u)).isEqualTo(1);
        assertThat(mapper.findByEmpno("E9001").statusCd()).isEqualTo("휴직");

        assertThat(mapper.deleteByEmpno("E9001")).isEqualTo(1);
        assertThat(mapper.findByEmpno("E9001")).isNull();
    }
```

- [ ] **Step 3: 매퍼 CRUD 테스트 통과 확인 (XML은 Task 6에서 작성됨)**

Run: `./gradlew test --tests '*DeveloperMapperIT'`
Expected: PASS (insert/update/delete 모두 1행).

- [ ] **Step 4: DeveloperService에 create/update/delete 추가**

`DeveloperService`에 메서드 추가:
```java
    @org.springframework.transaction.annotation.Transactional("appTxManager")
    public Developer create(DeveloperRequest req) {
        if (mapper.findByEmpno(req.empno()) != null) {
            throw new IllegalArgumentException("이미 존재하는 사번: " + req.empno());
        }
        mapper.insert(req.toDeveloper());
        return mapper.findByEmpno(req.empno());
    }

    @org.springframework.transaction.annotation.Transactional("appTxManager")
    public Developer update(String empno, DeveloperRequest req) {
        get(empno); // 없으면 IllegalArgumentException
        Developer toUpdate = new Developer(empno, req.empNm(), req.deptCd(), req.partCd(),
                req.gradeCd(), req.roleCd(),
                req.devYn() == null ? "Y" : req.devYn(),
                req.statusCd() == null ? "재직" : req.statusCd());
        mapper.update(toUpdate);
        return mapper.findByEmpno(empno);
    }

    @org.springframework.transaction.annotation.Transactional("appTxManager")
    public void delete(String empno) {
        get(empno); // 없으면 IllegalArgumentException
        mapper.deleteByEmpno(empno);
    }
```

- [ ] **Step 5: 실패하는 컨트롤러 POST 테스트 추가 (DeveloperControllerTest)**

```java
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("POST /api/v1/developers 정상 → 201")
    void create_ok() throws Exception {
        org.mockito.Mockito.when(service.create(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new Developer("E9001","신규자","D101","P03","사원","03","Y","재직"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/developers")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"empno\":\"E9001\",\"empNm\":\"신규자\",\"devYn\":\"Y\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.empno").value("E9001"));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("POST empno 누락 → 400")
    void create_invalid() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/developers")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"empNm\":\"이름만\"}"))
           .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 6: 테스트 실패 확인**

Run: `./gradlew test --tests '*DeveloperControllerTest'`
Expected: FAIL (POST 매핑 미존재).

- [ ] **Step 7: DeveloperController에 POST/PUT/DELETE 추가**

```java
    @io.swagger.v3.oas.annotations.Operation(summary = "인력 등록")
    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<Developer> create(@org.springframework.web.bind.annotation.RequestBody
                                         @jakarta.validation.Valid DeveloperRequest req) {
        return ApiResponse.of(service.create(req));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "인력 수정")
    @PutMapping("/{empno}")
    public ApiResponse<Developer> update(@PathVariable String empno,
                                         @org.springframework.web.bind.annotation.RequestBody
                                         @jakarta.validation.Valid DeveloperRequest req) {
        return ApiResponse.of(service.update(empno, req));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "인력 삭제")
    @DeleteMapping("/{empno}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String empno) {
        service.delete(empno);
    }
```

- [ ] **Step 8: 컨트롤러 테스트 통과 확인**

Run: `./gradlew test --tests '*DeveloperControllerTest'`
Expected: PASS (201 / 400).

- [ ] **Step 9: 전체 빌드 + 커밋**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (전 테스트 통과).
```bash
git add src/main/java/com/meritz/dash/developer src/test/java/com/meritz/dash/developer
git commit -m "feat: 인력 등록/수정/삭제 API (POST/PUT/DELETE /api/v1/developers) + 검증"
```

---

## Task 8: 통합 점검 + 다각화 리뷰

**Files:** (코드 변경 없음 — 검증/문서)

- [ ] **Step 1: 로컬 구동 스모크 테스트**

Run: `docker compose up -d && ./gradlew bootRun`
그리고 다른 터미널에서:
```bash
curl -s http://localhost:8080/api/v1/health
curl -s 'http://localhost:8080/api/v1/codes?grpCd=SR_TPCD'
curl -s 'http://localhost:8080/api/v1/developers?devYn=Y'
```
Expected: health=UP, SR_TPCD 7건, 개발자 3건. Swagger UI `http://localhost:8080/swagger-ui` 접속 확인.

- [ ] **Step 2: 다각화 리뷰 실행**

`/review-all` 커맨드 실행(code-reviewer, security-reviewer 병렬). 특히:
- 기간계 매퍼 부재(이번 범위엔 mapper.legacy 없음) 확인
- `${}` 미사용, 시크릿(비밀번호) 하드코딩 부재(환경변수화) 확인
- DTO 경계(컨트롤러가 record만 반환) 확인
Expected: Critical 0건. 1건 이상이면 수정 후 재리뷰.

- [ ] **Step 3: DoD 체크 후 마무리 커밋(필요 시)**

CLAUDE.md 9장 Definition of Done 체크리스트 확인:
TDD로 개발 / 기간계 쓰기 없음 / DTO·ProblemDetail·springdoc / review-all Critical 0 / `./gradlew build` 통과.

---

## Self-Review (작성자 점검 결과)

- **스펙 커버리지**: A(2 DataSource·MyBatis 분리·Hikari·envelope·ProblemDetail·health) = Task 1·3. D(CD_COMMON·HR_DEVELOPER·시드·codes·developers CRUD) = Task 4·5·6·7. C(대시보드·집계·기간계 읽기)는 **후속 계획서**로 분리 — 의도된 범위.
- **플레이스홀더**: 모든 코드/테스트/SQL/명령 실제 내용 포함. TBD/“추후” 없음.
- **타입 일관성**: `ApiResponse.of(...)`, `CommonCode(grpCd,cdVal,cdNm,sortNo)`, `Developer(8필드)`, `DeveloperMapper`(findAll/findByEmpno/insert/update/deleteByEmpno), 트랜잭션 매니저명 `appTxManager` 일관.
- **남은 가정**: 기간계 실제 컬럼/SR_TPCD 자릿수는 C 계획서 착수 시 `/ora-db`로 확인(스펙 10장). `withReuse(true)`는 로컬 `~/.testcontainers.properties`에 `testcontainers.reuse.enable=true` 필요(없어도 동작, 느릴 뿐).
