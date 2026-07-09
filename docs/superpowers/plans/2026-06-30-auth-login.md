# B 로그인/인증/인가 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로컬 사번 로그인 + JWT 발급/검증으로 역할(팀장/업무리더/일반직원) 기반 민감 API 인가를 구현한다.

**Architecture:** A+D+C(2 DataSource·MyBatis·Flyway·envelope·ProblemDetail) 위에 얹는다. `AUTH_ACCOUNT`(비번 해시) + 기존 `HR_DEVELOPER`(역할/이름/파트)로 로그인하고, `JwtService`(jjwt)가 역할 claim을 담은 토큰을 발급한다. `JwtAuthInterceptor`(+`@Auth` 애너테이션)가 `/api/v1/**`를 보호하되 login/health는 공개, 인사 쓰기·집계 트리거는 팀장만 허용한다. 전체 Spring Security는 도입하지 않는다.

**Tech Stack:** Java 21, Spring Boot 3.3.x, MyBatis, Oracle(DB2), Flyway, **jjwt 0.12.x**, **spring-security-crypto(BCrypt)**, JUnit5+AssertJ+Mockito+Testcontainers.

## Global Constraints

- 라이브러리 추가는 **jjwt(api/impl/jackson) + spring-security-crypto만**(전체 spring-boot-starter-security 미도입). 그 외 임의 추가 금지.
- 비번은 **BCrypt 해시만 저장**. 평문/해시를 응답·로그에 노출 금지. 로그인 실패 메시지는 "아이디 또는 비밀번호가 올바르지 않습니다"로 통일(사번 존재 여부 비노출).
- `JWT_SECRET`/DB 접속정보는 환경변수(소스 커밋 금지). JWT HS256, secret 32바이트+.
- DB2 DDL은 `db/migration/VNNN__설명.sql` 새 파일(기존 수정 금지). 19c 호환(CHAR(1) 플래그+CHECK, BOOLEAN 금지). 코드성 값은 CD_COMMON 참조.
- 응답 envelope `{data, meta}`. 에러 RFC7807 `ProblemDetail`. DTO record, 생성자 주입만. springdoc `@Operation`. 기본 패키지 `com.meritz.dash`.
- 역할 출처는 `HR_DEVELOPER.ROLE_CD`(CD_COMMON `EMP_ROLE`: '01'팀장/'02'업무리더/'03'일반직원). 쓰기/관리 = 팀장('01').
- gradle 실행 셸: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; export DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock; export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. IT timeout 넉넉히(500000ms). `gradle.properties`/`.gitignore` 수정·커밋 금지.

---

## File Structure

```
build.gradle (수정: jjwt, spring-security-crypto)
src/main/resources/application.yml (수정: app.jwt)
src/main/resources/db/migration/V007__create_auth_account.sql
src/main/java/com/meritz/dash/
  config/JwtProperties.java            # @ConfigurationProperties("app.jwt")
  config/SecurityBeans.java            # PasswordEncoder(BCrypt) 빈
  config/WebConfig.java                # JwtAuthInterceptor 등록(addPathPatterns/exclude)
  auth/
    AuthAccount.java                   # record (DB row)
    JwtService.java                    # 토큰 발급/검증
    AccountProvisioner.java            # ApplicationRunner: 재직자 계정 생성
    AuthService.java                   # login/changePassword/me
    AuthController.java                # /api/v1/auth/login,password,me
    LoginRequest.java LoginResult.java ChangePasswordRequest.java MeResult.java
    Auth.java                          # @Auth 애너테이션
    AuthContext.java                   # 요청 스코프 사용자 정보 보관
    JwtAuthInterceptor.java            # 토큰 검증 + 역할 체크
    UnauthorizedException.java ForbiddenException.java
  mapper/app/AuthAccountMapper.java + resources/mapper/app/AuthAccountMapper.xml
  common/GlobalExceptionHandler.java (수정: 401/403 핸들러 추가)
  developer/DeveloperController.java (수정: 쓰기에 @Auth(roles="01"))
  aggregation/AggregationController.java (수정: POST에 @Auth(roles="01"))
```

---

## Task 1: 의존성 + JWT 설정 + BCrypt 빈

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/meritz/dash/config/JwtProperties.java`
- Create: `src/main/java/com/meritz/dash/config/SecurityBeans.java`
- Test: `src/test/java/com/meritz/dash/config/JwtPropertiesTest.java`

**Interfaces:**
- Produces: `JwtProperties(String secret, long expiration)` (`@ConfigurationProperties("app.jwt")`).
- Produces: `PasswordEncoder` 빈(BCrypt), `JwtProperties` 빈.

- [ ] **Step 1: build.gradle 의존성 추가**

`dependencies { ... }`에 추가:
```groovy
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
    implementation 'org.springframework.security:spring-security-crypto:6.3.4'
```
> spring-security-crypto는 BCrypt만 쓰는 경량 모듈(전체 시큐리티 아님). BouncyCastle 불필요(BCrypt는 미사용 의존 없음).

- [ ] **Step 2: application.yml에 app.jwt 추가**

`app:` 블록(기존 `app.mm` 옆)에 추가:
```yaml
app:
  jwt:
    secret: ${JWT_SECRET:change-me-in-prod-please-32bytes-minimum!!}
    expiration: ${JWT_EXPIRATION:86400000}
```

- [ ] **Step 3: 실패 테스트 작성**

```java
package com.meritz.dash.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPropertiesTest {
    @Test
    @DisplayName("app.jwt 바인딩: secret + expiration")
    void binds() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.jwt.secret", "0123456789abcdef0123456789abcdef")
            .withProperty("app.jwt.expiration", "86400000");
        JwtProperties p = Binder.get(env).bind("app.jwt", JwtProperties.class).get();
        assertThat(p.secret()).hasSize(32);
        assertThat(p.expiration()).isEqualTo(86400000L);
    }
}
```

- [ ] **Step 4: 실패 확인** — `./gradlew test --tests '*JwtPropertiesTest'` → FAIL.

- [ ] **Step 5: JwtProperties + SecurityBeans 작성**

```java
package com.meritz.dash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, long expiration) {}
```
```java
package com.meritz.dash.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityBeans {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```
(`JwtProperties`는 기존 `@ConfigurationPropertiesScan`(DashApplication)으로 자동 등록됨.)

- [ ] **Step 6: 통과 확인** — `./gradlew test --tests '*JwtPropertiesTest'` → PASS.

- [ ] **Step 7: 커밋**
```bash
git add build.gradle src/main/resources/application.yml src/main/java/com/meritz/dash/config/JwtProperties.java src/main/java/com/meritz/dash/config/SecurityBeans.java src/test/java/com/meritz/dash/config/JwtPropertiesTest.java
git commit -m "feat: jjwt+BCrypt 의존성 + app.jwt 설정 + PasswordEncoder 빈"
```

---

## Task 2: V007 AUTH_ACCOUNT 테이블

**Files:**
- Create: `src/main/resources/db/migration/V007__create_auth_account.sql`
- Test: `src/test/java/com/meritz/dash/config/AuthAccountTableIT.java`

**Interfaces:**
- Produces: 테이블 `AUTH_ACCOUNT(EMPNO PK, PASSWORD_HASH, PWD_RESET_YN, LAST_LOGIN_AT, FAIL_CNT, 감사)`.

- [ ] **Step 1: 실패 테스트 작성**

```java
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
    @DisplayName("V007: AUTH_ACCOUNT 테이블 생성(빈 카운트 0)")
    void table_exists() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUTH_ACCOUNT", Integer.class)).isZero();
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*AuthAccountTableIT'` → FAIL.

- [ ] **Step 3: V007 작성**

```sql
-- V007: 로그인 계정 (19c 호환). 역할/이름/파트는 HR_DEVELOPER에서.
CREATE TABLE AUTH_ACCOUNT (
  EMPNO         VARCHAR2(20)  NOT NULL,
  PASSWORD_HASH VARCHAR2(100) NOT NULL,
  PWD_RESET_YN  CHAR(1)       DEFAULT 'Y' NOT NULL,
  LAST_LOGIN_AT TIMESTAMP,
  FAIL_CNT      NUMBER(4)     DEFAULT 0 NOT NULL,
  CREATED_AT    TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  CREATED_BY    VARCHAR2(30)  DEFAULT 'SYSTEM' NOT NULL,
  UPDATED_AT    TIMESTAMP,
  UPDATED_BY    VARCHAR2(30),
  CONSTRAINT PK_AUTH_ACCOUNT PRIMARY KEY (EMPNO),
  CONSTRAINT CK_AUTH_PWD_RESET CHECK (PWD_RESET_YN IN ('Y','N'))
);
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests '*AuthAccountTableIT'` → PASS.

- [ ] **Step 5: 커밋**
```bash
git add src/main/resources/db/migration/V007__create_auth_account.sql src/test/java/com/meritz/dash/config/AuthAccountTableIT.java
git commit -m "feat: V007 AUTH_ACCOUNT 테이블"
```

---

## Task 3: JwtService (발급/검증)

**Files:**
- Create: `src/main/java/com/meritz/dash/auth/JwtService.java`
- Test: `src/test/java/com/meritz/dash/auth/JwtServiceTest.java`

**Interfaces:**
- Consumes: `JwtProperties`(Task1).
- Produces: `JwtService.generate(String empno, String role, String roleName, String name, String partCd, boolean pwdReset) : String`; `validate(String token) : io.jsonwebtoken.Claims`(만료/위조 시 null). claims: `sub`=empno, `role`, `roleName`, `name`, `partCd`, `pwdReset`.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.meritz.dash.auth;

import com.meritz.dash.config.JwtProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwt = new JwtService(
        new JwtProperties("0123456789abcdef0123456789abcdef", 86400000L));

    @Test
    @DisplayName("generate→validate: claims 보존")
    void roundtrip() {
        String token = jwt.generate("9692", "01", "팀장", "김팀장", "P01", true);
        Claims c = jwt.validate(token);
        assertThat(c).isNotNull();
        assertThat(c.getSubject()).isEqualTo("9692");
        assertThat(c.get("role")).isEqualTo("01");
        assertThat(c.get("pwdReset")).isEqualTo(true);
    }

    @Test
    @DisplayName("위조 토큰 → null")
    void tampered() {
        assertThat(jwt.validate("not.a.jwt")).isNull();
    }

    @Test
    @DisplayName("만료 토큰 → null")
    void expired() {
        JwtService shortLived = new JwtService(
            new JwtProperties("0123456789abcdef0123456789abcdef", -1000L)); // 이미 만료
        String token = shortLived.generate("9692","03","일반직원","홍길동","P02",false);
        assertThat(shortLived.validate(token)).isNull();
    }
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests '*JwtServiceTest'` → FAIL.

- [ ] **Step 3: JwtService 구현**

```java
package com.meritz.dash.auth;

import com.meritz.dash.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(JwtProperties props) {
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMs = props.expiration();
    }

    public String generate(String empno, String role, String roleName,
                           String name, String partCd, boolean pwdReset) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(empno)
                .claim("role", role)
                .claim("roleName", roleName)
                .claim("name", name)
                .claim("partCd", partCd)
                .claim("pwdReset", pwdReset)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMs))
                .signWith(key)
                .compact();
    }

    public Claims validate(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
```
> `System.currentTimeMillis()`는 운영 코드라 사용 가능(테스트 제약과 무관).

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests '*JwtServiceTest'` → PASS.

- [ ] **Step 5: 커밋**
```bash
git add src/main/java/com/meritz/dash/auth/JwtService.java src/test/java/com/meritz/dash/auth/JwtServiceTest.java
git commit -m "feat: JwtService 토큰 발급/검증(jjwt HS256, 역할 claim)"
```

---

## Task 4: AuthAccount 매퍼 + 계정 프로비저닝

**Files:**
- Create: `src/main/java/com/meritz/dash/auth/AuthAccount.java`
- Create: `src/main/java/com/meritz/dash/mapper/app/AuthAccountMapper.java`
- Create: `src/main/resources/mapper/app/AuthAccountMapper.xml`
- Create: `src/main/java/com/meritz/dash/auth/AccountProvisioner.java`
- Test: `src/test/java/com/meritz/dash/auth/AccountProvisionerIT.java`

**Interfaces:**
- Produces: `record AuthAccount(String empno, String passwordHash, String pwdResetYn, Integer failCnt)`.
- Produces: `AuthAccountMapper`: `AuthAccount findByEmpno(@Param empno)`; `List<String> findEmpnosNeedingAccount()`(HR 재직 STATUS_CD='01' 중 AUTH_ACCOUNT 없는 사번); `void insertAccount(@Param empno, @Param hash)`; `int updatePassword(@Param empno, @Param hash)`(PWD_RESET_YN='N'); `void touchLastLogin(@Param empno)`.
- Produces: `AccountProvisioner`(ApplicationRunner) — 기동 시 누락 계정 생성(초기 비번=사번, BCrypt). 멱등.

- [ ] **Step 1: AuthAccount record + 매퍼 인터페이스/XML**

```java
package com.meritz.dash.auth;
public record AuthAccount(String empno, String passwordHash, String pwdResetYn, Integer failCnt) {}
```
```java
package com.meritz.dash.mapper.app;

import com.meritz.dash.auth.AuthAccount;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface AuthAccountMapper {
    AuthAccount findByEmpno(@Param("empno") String empno);
    List<String> findEmpnosNeedingAccount();
    void insertAccount(@Param("empno") String empno, @Param("hash") String hash);
    int updatePassword(@Param("empno") String empno, @Param("hash") String hash);
    void touchLastLogin(@Param("empno") String empno);
}
```
`src/main/resources/mapper/app/AuthAccountMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.meritz.dash.mapper.app.AuthAccountMapper">
  <select id="findByEmpno" resultType="com.meritz.dash.auth.AuthAccount">
    SELECT EMPNO AS empno, PASSWORD_HASH AS passwordHash, PWD_RESET_YN AS pwdResetYn, FAIL_CNT AS failCnt
      FROM AUTH_ACCOUNT WHERE EMPNO = #{empno}
  </select>
  <select id="findEmpnosNeedingAccount" resultType="string">
    SELECT h.EMPNO FROM HR_DEVELOPER h
     WHERE h.STATUS_CD = '01'
       AND NOT EXISTS (SELECT 1 FROM AUTH_ACCOUNT a WHERE a.EMPNO = h.EMPNO)
  </select>
  <insert id="insertAccount">
    INSERT INTO AUTH_ACCOUNT (EMPNO, PASSWORD_HASH, PWD_RESET_YN) VALUES (#{empno}, #{hash}, 'Y')
  </insert>
  <update id="updatePassword">
    UPDATE AUTH_ACCOUNT SET PASSWORD_HASH = #{hash}, PWD_RESET_YN = 'N',
           UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = #{empno} WHERE EMPNO = #{empno}
  </update>
  <update id="touchLastLogin">
    UPDATE AUTH_ACCOUNT SET LAST_LOGIN_AT = SYSTIMESTAMP WHERE EMPNO = #{empno}
  </update>
</mapper>
```

- [ ] **Step 2: 실패하는 프로비저너 IT 작성**

```java
package com.meritz.dash.auth;

import com.meritz.dash.mapper.app.AuthAccountMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class AccountProvisionerIT extends AbstractOracleIT {

    @Autowired AccountProvisioner provisioner;
    @Autowired AuthAccountMapper mapper;
    @Autowired PasswordEncoder encoder;

    @Test
    @DisplayName("프로비저너: 재직자(E0001~E0004) 계정 생성, 초기비번=사번, 멱등")
    void provisions() {
        provisioner.provision();
        AuthAccount a = mapper.findByEmpno("E0002");
        assertThat(a).isNotNull();
        assertThat(a.pwdResetYn()).isEqualTo("Y");
        assertThat(encoder.matches("E0002", a.passwordHash())).isTrue(); // 초기비번=사번

        int needBefore = mapper.findEmpnosNeedingAccount().size();
        provisioner.provision();                 // 재실행 멱등
        assertThat(mapper.findEmpnosNeedingAccount().size()).isEqualTo(needBefore);
    }
}
```
> 시드 HR_DEVELOPER는 E0001~E0004 모두 STATUS_CD='01'(재직)이므로 4계정 생성 대상.

- [ ] **Step 3: 실패 확인** — `./gradlew test --tests '*AccountProvisionerIT'` → FAIL.

- [ ] **Step 4: AccountProvisioner 구현**

```java
package com.meritz.dash.auth;

import com.meritz.dash.mapper.app.AuthAccountMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class AccountProvisioner implements ApplicationRunner {

    private final AuthAccountMapper mapper;
    private final PasswordEncoder encoder;

    public AccountProvisioner(AuthAccountMapper mapper, PasswordEncoder encoder) {
        this.mapper = mapper; this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        provision();
    }

    /** 재직 직원 중 계정 없는 사번에 초기비번=사번으로 계정 생성(멱등). */
    @Transactional("appTxManager")
    public void provision() {
        List<String> empnos = mapper.findEmpnosNeedingAccount();
        for (String empno : empnos) {
            mapper.insertAccount(empno, encoder.encode(empno));
        }
    }
}
```

- [ ] **Step 5: 통과 확인** — `./gradlew test --tests '*AccountProvisionerIT'` → PASS.

- [ ] **Step 6: 커밋**
```bash
git add src/main/java/com/meritz/dash/auth/AuthAccount.java src/main/java/com/meritz/dash/mapper/app/AuthAccountMapper.java src/main/resources/mapper/app/AuthAccountMapper.xml src/main/java/com/meritz/dash/auth/AccountProvisioner.java src/test/java/com/meritz/dash/auth/AccountProvisionerIT.java
git commit -m "feat: AUTH_ACCOUNT 매퍼 + 계정 자동 프로비저닝(초기비번=사번)"
```

---

## Task 5: AuthService + 로그인/비번변경/me API

**Files:**
- Create: `src/main/java/com/meritz/dash/auth/{LoginRequest,LoginResult,ChangePasswordRequest,MeResult}.java`
- Create: `src/main/java/com/meritz/dash/auth/AuthService.java`
- Create: `src/main/java/com/meritz/dash/auth/AuthController.java`
- Test: `src/test/java/com/meritz/dash/auth/AuthServiceIT.java`, `src/test/java/com/meritz/dash/auth/AuthControllerTest.java`

**Interfaces:**
- Consumes: `AuthAccountMapper`(Task4), `JwtService`(Task3), `PasswordEncoder`(Task1), `DeveloperMapper.findByEmpno`(D, 반환 `Developer(empno,empNm,…,roleCd,…)`), `CodeMapper.findByGroup`(D, `CommonCode(cdVal,cdNm,…)`).
- Produces: `record LoginRequest(@NotBlank String empno, @NotBlank String password)`; `record LoginResult(String token, String empno, String role, String roleName, String name, boolean pwdResetRequired)`; `record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword)`; `record MeResult(String empno, String role, String roleName, String name, String partCd, boolean pwdResetRequired)`.
- Produces: `AuthService.login(LoginRequest) : LoginResult`(실패 시 `UnauthorizedException`); `changePassword(String empno, ChangePasswordRequest)`(old 불일치/정책위반 → `IllegalArgumentException`); `me(String empno) : MeResult`.

- [ ] **Step 1: 요청/응답 record 작성**

```java
// LoginRequest.java
package com.meritz.dash.auth;
import jakarta.validation.constraints.NotBlank;
public record LoginRequest(@NotBlank String empno, @NotBlank String password) {}
```
```java
// LoginResult.java
package com.meritz.dash.auth;
public record LoginResult(String token, String empno, String role, String roleName,
        String name, boolean pwdResetRequired) {}
```
```java
// ChangePasswordRequest.java
package com.meritz.dash.auth;
import jakarta.validation.constraints.NotBlank;
public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}
```
```java
// MeResult.java
package com.meritz.dash.auth;
public record MeResult(String empno, String role, String roleName, String name,
        String partCd, boolean pwdResetRequired) {}
```

- [ ] **Step 2: UnauthorizedException/ForbiddenException 작성**

```java
// UnauthorizedException.java
package com.meritz.dash.auth;
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String msg) { super(msg); }
}
```
```java
// ForbiddenException.java
package com.meritz.dash.auth;
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String msg) { super(msg); }
}
```

- [ ] **Step 3: 실패하는 AuthService IT 작성**

```java
package com.meritz.dash.auth;

import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

class AuthServiceIT extends AbstractOracleIT {

    @Autowired AuthService authService;
    @Autowired AccountProvisioner provisioner;

    @BeforeEach void seedAccounts() { provisioner.provision(); }

    @Test
    @DisplayName("로그인 성공: 초기비번=사번 → pwdResetRequired=true, 역할 팀장(E0001)")
    void login_ok_team_lead() {
        LoginResult r = authService.login(new LoginRequest("E0001", "E0001"));
        assertThat(r.token()).isNotBlank();
        assertThat(r.role()).isEqualTo("01");          // E0001 ROLE_CD=01 팀장
        assertThat(r.pwdResetRequired()).isTrue();
    }

    @Test
    @DisplayName("로그인 실패: 틀린 비번 → UnauthorizedException")
    void login_bad_password() {
        assertThatThrownBy(() -> authService.login(new LoginRequest("E0002", "wrong")))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("비번 변경: old 검증 후 변경 + pwdReset 해제")
    void change_password() {
        authService.changePassword("E0002", new ChangePasswordRequest("E0002", "newpass12"));
        LoginResult r = authService.login(new LoginRequest("E0002", "newpass12"));
        assertThat(r.pwdResetRequired()).isFalse();
    }

    @Test
    @DisplayName("비번 정책: 8자 미만 → IllegalArgumentException")
    void change_password_too_short() {
        assertThatThrownBy(() -> authService.changePassword("E0003", new ChangePasswordRequest("E0003", "short")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 4: 실패 확인** — `./gradlew test --tests '*AuthServiceIT'` → FAIL.

- [ ] **Step 5: AuthService 구현**

```java
package com.meritz.dash.auth;

import com.meritz.dash.code.CommonCode;
import com.meritz.dash.developer.Developer;
import com.meritz.dash.mapper.app.AuthAccountMapper;
import com.meritz.dash.mapper.app.CodeMapper;
import com.meritz.dash.mapper.app.DeveloperMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final AuthAccountMapper accounts;
    private final DeveloperMapper developers;
    private final CodeMapper codes;
    private final JwtService jwt;
    private final PasswordEncoder encoder;

    public AuthService(AuthAccountMapper accounts, DeveloperMapper developers, CodeMapper codes,
                       JwtService jwt, PasswordEncoder encoder) {
        this.accounts = accounts; this.developers = developers; this.codes = codes;
        this.jwt = jwt; this.encoder = encoder;
    }

    @Transactional("appTxManager")
    public LoginResult login(LoginRequest req) {
        AuthAccount acc = accounts.findByEmpno(req.empno());
        if (acc == null || !encoder.matches(req.password(), acc.passwordHash())) {
            throw new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다");
        }
        Developer dev = developers.findByEmpno(req.empno());
        if (dev == null) throw new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다");
        String roleName = roleName(dev.roleCd());
        boolean pwdReset = "Y".equals(acc.pwdResetYn());
        accounts.touchLastLogin(req.empno());
        String token = jwt.generate(dev.empno(), dev.roleCd(), roleName, dev.empNm(), dev.partCd(), pwdReset);
        return new LoginResult(token, dev.empno(), dev.roleCd(), roleName, dev.empNm(), pwdReset);
    }

    @Transactional("appTxManager")
    public void changePassword(String empno, ChangePasswordRequest req) {
        AuthAccount acc = accounts.findByEmpno(empno);
        if (acc == null || !encoder.matches(req.oldPassword(), acc.passwordHash())) {
            throw new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다");
        }
        if (req.newPassword() == null || req.newPassword().length() < 8) {
            throw new IllegalArgumentException("새 비밀번호는 8자 이상이어야 합니다");
        }
        if (empno.equals(req.newPassword())) {
            throw new IllegalArgumentException("새 비밀번호는 사번과 같을 수 없습니다");
        }
        accounts.updatePassword(empno, encoder.encode(req.newPassword()));
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public MeResult me(String empno) {
        Developer dev = developers.findByEmpno(empno);
        if (dev == null) throw new UnauthorizedException("사용자 정보가 없습니다");
        AuthAccount acc = accounts.findByEmpno(empno);
        boolean pwdReset = acc != null && "Y".equals(acc.pwdResetYn());
        return new MeResult(dev.empno(), dev.roleCd(), roleName(dev.roleCd()), dev.empNm(), dev.partCd(), pwdReset);
    }

    private String roleName(String roleCd) {
        Map<String,String> m = codes.findByGroup("EMP_ROLE").stream()
                .collect(Collectors.toMap(CommonCode::cdVal, CommonCode::cdNm));
        return m.getOrDefault(roleCd, roleCd);
    }
}
```
> `Developer`(D)의 접근자 `empno()/empNm()/partCd()/roleCd()`와 `CommonCode`(D)의 `cdVal()/cdNm()`를 사용. 실제 시그니처와 다르면 맞춰 조정.

- [ ] **Step 6: AuthController 작성 + 계약테스트(@WebMvcTest)**

`AuthController.java`:
```java
package com.meritz.dash.auth;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    public AuthController(AuthService authService) { this.authService = authService; }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@RequestBody @Valid LoginRequest req) {
        return ApiResponse.of(authService.login(req));
    }

    @Operation(summary = "비밀번호 변경(첫 로그인 강제/자발 공용)")
    @Auth
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody @Valid ChangePasswordRequest req) {
        authService.changePassword(AuthContext.empno(), req);
        return ApiResponse.of(null);
    }

    @Operation(summary = "현재 사용자")
    @Auth
    @GetMapping("/me")
    public ApiResponse<MeResult> me() {
        return ApiResponse.of(authService.me(AuthContext.empno()));
    }
}
```
`AuthControllerTest.java`:
```java
package com.meritz.dash.auth;

import com.meritz.dash.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class,
    excludeAutoConfiguration = {}) // 인터셉터는 WebMvcTest 슬라이스에 미등록(컨트롤러 계약만)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthService authService;

    @Test
    void login_ok() throws Exception {
        when(authService.login(any())).thenReturn(
            new LoginResult("tok","E0001","01","팀장","김팀장",true));
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"empno\":\"E0001\",\"password\":\"E0001\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.token").value("tok"))
           .andExpect(jsonPath("$.data.pwdResetRequired").value(true));
    }

    @Test
    void login_blank_empno_400() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content("{\"password\":\"x\"}"))
           .andExpect(status().isBadRequest());
    }
}
```
> `AuthContext.empno()`는 Task6에서 정의(요청 스코프). 이 컨트롤러 계약테스트는 login만 검증(password/me는 인터셉터 의존이라 Task6 통합에서). `@Auth`/`AuthContext` 참조가 컴파일되려면 Task6 산출물이 필요하므로, 본 태스크 Step 6의 컨트롤러는 Task6와 함께 컴파일된다 — 실행 순서상 Task5에서 `@Auth`/`AuthContext` 스텁이 없으면 Task6에서 추가 후 통과시킨다. (분리 실행 시 Task6 먼저 머지 가능.)

- [ ] **Step 7: 실패 확인 → 통과**

Run: `./gradlew test --tests '*AuthServiceIT' --tests '*AuthControllerTest'`
Expected: AuthServiceIT PASS. AuthControllerTest는 `@Auth`/`AuthContext`(Task6) 도입 후 컴파일·PASS.

> 의존 순서 주의: 본 태스크는 `@Auth`/`AuthContext`(Task6)에 컴파일 의존한다. **권장 실행 순서: Task6의 `Auth`/`AuthContext`/예외 핸들러 스텁을 먼저 만든 뒤 Task5를 마무리**하거나, Task5에서 빈 `@Auth` 애너테이션과 `AuthContext`(empno 반환)만 선 작성한다. 아래 Task6가 인터셉터 본체를 완성한다.

- [ ] **Step 8: 커밋**
```bash
git add src/main/java/com/meritz/dash/auth/LoginRequest.java src/main/java/com/meritz/dash/auth/LoginResult.java src/main/java/com/meritz/dash/auth/ChangePasswordRequest.java src/main/java/com/meritz/dash/auth/MeResult.java src/main/java/com/meritz/dash/auth/UnauthorizedException.java src/main/java/com/meritz/dash/auth/ForbiddenException.java src/main/java/com/meritz/dash/auth/AuthService.java src/main/java/com/meritz/dash/auth/AuthController.java src/test/java/com/meritz/dash/auth/AuthServiceIT.java src/test/java/com/meritz/dash/auth/AuthControllerTest.java
git commit -m "feat: AuthService + 로그인/비번변경/me API"
```

---

## Task 6: @Auth + JwtAuthInterceptor 인가 + ProblemDetail(401/403)

**Files:**
- Create: `src/main/java/com/meritz/dash/auth/Auth.java`
- Create: `src/main/java/com/meritz/dash/auth/AuthContext.java`
- Create: `src/main/java/com/meritz/dash/auth/JwtAuthInterceptor.java`
- Create: `src/main/java/com/meritz/dash/config/WebConfig.java`
- Modify: `src/main/java/com/meritz/dash/common/GlobalExceptionHandler.java` (401/403 핸들러)
- Test: `src/test/java/com/meritz/dash/auth/AuthorizationIT.java`

**Interfaces:**
- Consumes: `JwtService.validate`(Task3), `UnauthorizedException`/`ForbiddenException`(Task5).
- Produces: `@Auth(String[] roles default {})` (메서드/타입). `AuthContext`(요청 스코프, `static String empno()`, `static String role()`). `JwtAuthInterceptor`(HandlerInterceptor). `GlobalExceptionHandler`가 `UnauthorizedException→401`, `ForbiddenException→403` ProblemDetail.

- [ ] **Step 1: @Auth + AuthContext 작성**

```java
// Auth.java
package com.meritz.dash.auth;
import java.lang.annotation.*;
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Auth {
    String[] roles() default {};   // 비면 인증만 요구, 있으면 해당 역할만
}
```
```java
// AuthContext.java — 요청 스코프 사용자 정보(ThreadLocal)
package com.meritz.dash.auth;

public final class AuthContext {
    private record Principal(String empno, String role) {}
    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();
    private AuthContext() {}
    public static void set(String empno, String role) { HOLDER.set(new Principal(empno, role)); }
    public static void clear() { HOLDER.remove(); }
    public static String empno() {
        Principal p = HOLDER.get();
        if (p == null) throw new UnauthorizedException("인증이 필요합니다");
        return p.empno();
    }
    public static String role() {
        Principal p = HOLDER.get();
        return p == null ? null : p.role();
    }
}
```

- [ ] **Step 2: JwtAuthInterceptor 작성**

```java
package com.meritz.dash.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtService jwt;
    public JwtAuthInterceptor(JwtService jwt) { this.jwt = jwt; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;   // 정적 리소스 등

        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new UnauthorizedException("인증 토큰이 필요합니다");
        }
        Claims claims = jwt.validate(header.substring(7).trim());
        if (claims == null) throw new UnauthorizedException("토큰이 유효하지 않습니다");

        String empno = claims.getSubject();
        String role = (String) claims.get("role");
        AuthContext.set(empno, role);

        Auth auth = hm.getMethodAnnotation(Auth.class);
        if (auth == null) auth = hm.getBeanType().getAnnotation(Auth.class);
        if (auth != null && auth.roles().length > 0
                && Arrays.stream(auth.roles()).noneMatch(r -> r.equals(role))) {
            throw new ForbiddenException("권한이 없습니다");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
```

- [ ] **Step 3: WebConfig 등록 (공개 경로 제외)**

```java
package com.meritz.dash.config;

import com.meritz.dash.auth.JwtAuthInterceptor;
import com.meritz.dash.auth.JwtService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtService jwt;
    public WebConfig(JwtService jwt) { this.jwt = jwt; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtAuthInterceptor(jwt))
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/login", "/api/v1/health");
    }
}
```

- [ ] **Step 4: GlobalExceptionHandler에 401/403 추가**

`GlobalExceptionHandler`에 메서드 추가:
```java
    @org.springframework.web.bind.annotation.ExceptionHandler(com.meritz.dash.auth.UnauthorizedException.class)
    public org.springframework.http.ProblemDetail handleUnauthorized(com.meritz.dash.auth.UnauthorizedException ex) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(com.meritz.dash.auth.ForbiddenException.class)
    public org.springframework.http.ProblemDetail handleForbidden(com.meritz.dash.auth.ForbiddenException ex) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatus.FORBIDDEN, ex.getMessage());
    }
```

- [ ] **Step 5: 인가 통합테스트 작성 (실 인터셉터 경유)**

```java
package com.meritz.dash.auth;

import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationIT extends AbstractOracleIT {

    @Autowired TestRestTemplate rest;
    @Autowired AccountProvisioner provisioner;

    @BeforeEach void seed() { provisioner.provision(); }

    private String login(String empno) {
        ResponseEntity<String> r = rest.postForEntity("/api/v1/auth/login",
            Map.of("empno", empno, "password", empno), String.class);
        // data.token 추출(간단 파싱)
        String body = r.getBody();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("무토큰으로 보호 엔드포인트 → 401")
    void no_token_401() {
        ResponseEntity<String> r = rest.getForEntity("/api/v1/developers", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("일반직원(E0002, 역할03) 토큰으로 인사 POST → 403")
    void user_cannot_write() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(login("E0002"));
        h.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> e = new HttpEntity<>("{\"empno\":\"E9001\",\"empNm\":\"신규\"}", h);
        ResponseEntity<String> r = rest.postForEntity("/api/v1/developers", e, String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("팀장(E0001, 역할01) 토큰으로 인사 조회 → 200")
    void team_lead_can_read() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(login("E0001"));
        ResponseEntity<String> r = rest.exchange("/api/v1/developers", HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("공개 경로 health → 토큰 없이 200")
    void health_public() {
        assertThat(rest.getForEntity("/api/v1/health", String.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }
}
```
(상단에 `import java.util.Map;` 추가.)

- [ ] **Step 6: 실패 → 통과 확인**

Run: `./gradlew test --tests '*AuthorizationIT' --tests '*AuthControllerTest'`
Expected: PASS. (이 시점에 Task5의 `AuthController`가 `@Auth`/`AuthContext`로 컴파일됨.)
> 주의: 이 테스트는 `developers` POST가 `@Auth(roles="01")`로 보호된다고 가정한다 — Task7에서 적용. Task7 이전이면 `user_cannot_write`는 403이 아닐 수 있으니, **Task6에서는 인터셉터/401/공개경로**를 먼저 통과시키고 `user_cannot_write`/role 테스트는 Task7 적용 직후 통과로 확인한다(순서 의존 명시).

- [ ] **Step 7: 커밋**
```bash
git add src/main/java/com/meritz/dash/auth/Auth.java src/main/java/com/meritz/dash/auth/AuthContext.java src/main/java/com/meritz/dash/auth/JwtAuthInterceptor.java src/main/java/com/meritz/dash/config/WebConfig.java src/main/java/com/meritz/dash/common/GlobalExceptionHandler.java src/test/java/com/meritz/dash/auth/AuthorizationIT.java
git commit -m "feat: @Auth + JwtAuthInterceptor 인가(401/403 ProblemDetail) + 공개경로"
```

---

## Task 7: 기존 쓰기 엔드포인트에 팀장 권한 적용 + 회귀

**Files:**
- Modify: `src/main/java/com/meritz/dash/developer/DeveloperController.java`
- Modify: `src/main/java/com/meritz/dash/aggregation/AggregationController.java`
- Test: (Task6 `AuthorizationIT`의 role 테스트가 여기서 최종 통과)

**Interfaces:**
- Consumes: `@Auth`(Task6).
- Produces: `POST/PUT/DELETE /developers`, `POST /aggregations` → 팀장('01')만.

- [ ] **Step 1: DeveloperController 쓰기 메서드에 @Auth(roles="01")**

`create`/`update`/`delete` 메서드에 애너테이션 추가(조회 `list`/`get`은 미부착=인증만):
```java
    @com.meritz.dash.auth.Auth(roles = "01")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Developer> create(@RequestBody @Valid DeveloperRequest req) { ... }

    @com.meritz.dash.auth.Auth(roles = "01")
    @PutMapping("/{empno}")
    public ApiResponse<Developer> update(@PathVariable String empno, @RequestBody @Valid DeveloperRequest req) { ... }

    @com.meritz.dash.auth.Auth(roles = "01")
    @DeleteMapping("/{empno}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String empno) { ... }
```

- [ ] **Step 2: AggregationController POST에 @Auth(roles="01")**

```java
    @com.meritz.dash.auth.Auth(roles = "01")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String,Object>> run(@RequestBody AggregationRequest req) { ... }
```
(GET `/aggregations` 이력은 미부착=인증만.)

- [ ] **Step 3: 인가 테스트 최종 통과 확인**

Run: `./gradlew test --tests '*AuthorizationIT'`
Expected: PASS (`user_cannot_write`=403, `team_lead_can_read`=200, `no_token_401`, `health_public`).

- [ ] **Step 4: 전체 회귀**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. 기존 컨트롤러 계약테스트(@WebMvcTest)는 인터셉터 슬라이스 밖이라 영향 없음. 통합테스트는 토큰 흐름 반영.
> 만약 기존 IT 중 보호 엔드포인트를 토큰 없이 호출하던 것이 있으면 401로 깨질 수 있다 — 그 테스트에 로그인 토큰 헤더를 추가하거나 `@WebMvcTest`(슬라이스)로 유지. 깨지는 테스트를 토큰 포함으로 수정한다.

- [ ] **Step 5: 커밋**
```bash
git add src/main/java/com/meritz/dash/developer/DeveloperController.java src/main/java/com/meritz/dash/aggregation/AggregationController.java
git commit -m "feat: 인사 쓰기/집계 트리거에 팀장(01) 권한 적용"
```

---

## Task 8: 통합 점검 + 라이브 스모크 + 다각화 리뷰

**Files:** (검증)

- [ ] **Step 1: 전체 빌드** — `./gradlew build` → BUILD SUCCESSFUL.

- [ ] **Step 2: 라이브 스모크**

`docker start dash-oracle-app` → bootRun(JWT_SECRET 임의 32바이트 설정). 검증:
```bash
# 공개
curl -s -w' [%{http_code}]' http://localhost:8080/api/v1/health
# 로그인(초기비번=사번)
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"empno":"E0001","password":"E0001"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
echo "$TOKEN"
# 인증 필요 조회(팀장 토큰)
curl -s -w' [%{http_code}]' -H "Authorization: Bearer $TOKEN" 'http://localhost:8080/api/v1/developers'
# 무토큰 → 401
curl -s -w' [%{http_code}]' 'http://localhost:8080/api/v1/developers'
# me / 비번변경
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/auth/me
```
Expected: health 200(공개), 로그인 토큰 발급(pwdResetRequired true), 팀장 조회 200, 무토큰 401, me 200.

- [ ] **Step 3: /review-all** — Critical 0 확인(특히 비번 평문/시크릿 노출, `${}`, 인가 우회, 토큰 검증).

- [ ] **Step 4: DoD 확인 후 finishing-a-development-branch.**

---

## Self-Review (작성자 점검)

- **스펙 커버리지**: §2 의존성/설정=Task1, §3 AUTH_ACCOUNT=Task2·프로비저닝=Task4, §4 JWT=Task3·인증 API=Task5, §5 인가=Task6·적용=Task7, §7 테스트=각 태스크, §8 통합=Task8. 누락 없음.
- **플레이스홀더**: 전 스텝 실제 코드/SQL/명령 포함.
- **타입 일관성**: `JwtService.generate(6인자)`/`validate→Claims`(Task3) ↔ AuthService/Interceptor 사용 일치. `AuthAccount(empno,passwordHash,pwdResetYn,failCnt)`/`AuthAccountMapper`(Task4) ↔ AuthService/Provisioner 일치. `@Auth`/`AuthContext`(Task6) ↔ AuthController(Task5)/DeveloperController(Task7). `LoginResult`/`MeResult` 필드 일관.
- **순서 의존(명시)**: Task5 `AuthController`가 `@Auth`/`AuthContext`(Task6)에 컴파일 의존 → 실행 시 **Task6의 `Auth`/`AuthContext` 먼저, 그다음 Task5 컨트롤러 완성** 권장(또는 Task5에서 스텁 선작성). Task6 role 테스트는 Task7 적용 후 최종 green. 서브에이전트 실행 시 이 의존을 디스패치에 명시한다.
- **남은 가정**: `Developer` record 접근자명(empno/empNm/partCd/roleCd), `CommonCode` 접근자(cdVal/cdNm)는 D 구현 기준 — 다르면 맞춘다. BCrypt/`spring-security-crypto` 버전은 Spring Boot BOM과 정합 확인.
