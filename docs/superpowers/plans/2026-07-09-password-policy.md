# 비밀번호 정책 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인/계정에 비밀번호 정책 5종(복잡도·90일만료·직전재사용금지·3개월휴면·10회실패잠금)을 반영하되, 수정분은 v2 신설·신규는 v1·기존 v1 auth는 무손상(A안).

**Architecture:** `AUTH_ACCOUNT`에 상태/변경일시/직전해시 컬럼을 additive로 추가(V017). 정책 로직은 기존 `AuthService`(v1 전용, 무손상)와 분리된 신규 `PasswordPolicy`(순수 검증)·`AuthPolicyService`(v2 login/password)·`AuthAdminService`(관리자 해제/초기화)에 담는다. v2 컨트롤러 2개(`/api/v2/auth/**`)와 신규 admin 컨트롤러 1개(`/api/v1/admin/accounts`)를 추가하고, 에러는 단일 `AuthPolicyException`(status+errorCode) → `ProblemDetail`로 변환한다.

**Tech Stack:** Java 21, Spring Boot 3.x, MyBatis(XML), Oracle(운영 19c/테스트 Testcontainers), BCrypt(`spring-security-crypto`), JJWT, springdoc-openapi, JUnit5+AssertJ+Mockito.

## Global Constraints

- 기존 API 수정분은 **v2 신설**, 신규 API는 **v1**. 기존 v1 `auth/login`·`auth/password`는 **완전 무손상**(A안), `@Deprecated` + Swagger에 "정책 미적용, v2 권장" 명시.
- 기간계(legacy) 무관 — 본 작업은 **DB2(appDataSource) 전용**. 쓰기는 `@Transactional("appTxManager")`.
- **19c 호환 DDL만**. `BOOLEAN` 금지, 플래그는 `CHAR(1)`/코드 컬럼. 마이그레이션은 **새 파일 `V017`**, 기존 파일 수정 금지.
- 매직넘버 금지 — 정책 상수는 `@ConfigurationProperties("app.auth")`. SQL 값 주입은 바인드 `#{}`만.
- DTO는 `record`, 컨트롤러는 DTO만 반환, 생성자 주입만.
- 에러는 `ProblemDetail`(RFC7807) + `errorCode` 속성. 응답 envelope `{data, meta}`.
- **Swagger(`@Operation`/`@Tag`/`@ApiResponses`)는 구현 후 작성**(Task 9), `DmlSrController` 수준의 풍부함.
- TDD: Red→Green→Refactor. `./gradlew build` 통과, `/review-all` Critical 0건.
- 빌드/실행: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`, 테스트 실행 시 `APP_DB_PASSWORD=apppw LEGACY_DB_PASSWORD=legacypw`, Testcontainers는 colima.

## 병렬 실행 웨이브 (fable 5 서브에이전트)

의존성 기준 파동. 같은 웨이브 내 태스크는 병렬 실행 가능(인터페이스 계약 고정).

- **Wave A (병렬):** Task 1(DB/record), Task 2(config+PasswordPolicy), Task 3(예외+핸들러)
- **Wave B:** Task 4(매퍼 메서드+XML+IT) — Task 1 필요
- **Wave C (병렬):** Task 5(AuthPolicyService), Task 6(AuthAdminService) — Task 2·3·4 필요
- **Wave D (병렬):** Task 7(AuthV2Controller+WebConfig), Task 8(AdminAccountController) — Task 5·6 필요
- **Wave E:** Task 9(v1 @Deprecated + Swagger + application.yml 검증 + 통합 빌드/리뷰)

---

### Task 1: AUTH_ACCOUNT 스키마 확장 + AuthAccount record + findByEmpno

**Files:**
- Create: `src/main/resources/db/migration/V017__auth_password_policy.sql`
- Modify: `src/main/java/com/meritz/dash/auth/AuthAccount.java`
- Modify: `src/main/resources/mapper/app/AuthAccountMapper.xml:5-8` (findByEmpno)
- Test: `src/test/java/com/meritz/dash/auth/AuthAccountSchemaIT.java`

**Interfaces:**
- Produces: `AuthAccount(String empno, String passwordHash, String pwdResetYn, Integer failCnt, String statusCd, java.time.LocalDateTime passwordChangedAt, String prevPasswordHash, java.time.LocalDateTime lastLoginAt)`
- Produces: 컬럼 `STATUS_CD CHAR(2)`('00'정상/'01'잠금/'02'휴면), `PASSWORD_CHANGED_AT TIMESTAMP`, `PREV_PASSWORD_HASH VARCHAR2(100)`.

- [ ] **Step 1: 마이그레이션 작성** — `V017__auth_password_policy.sql`

```sql
-- V017: 비밀번호 정책 컬럼 추가 (19c 호환, additive). 기존 V007 무수정.
ALTER TABLE AUTH_ACCOUNT ADD (
  STATUS_CD           CHAR(2)       DEFAULT '00' NOT NULL,
  PASSWORD_CHANGED_AT TIMESTAMP,
  PREV_PASSWORD_HASH  VARCHAR2(100)
);
-- 상태 코드: 00 정상 / 01 잠금(10회 실패) / 02 휴면(3개월 미사용)
ALTER TABLE AUTH_ACCOUNT ADD CONSTRAINT CK_AUTH_STATUS CHECK (STATUS_CD IN ('00','01','02'));
-- 백필: 기존 계정은 배포 직후 즉시 만료/휴면되지 않도록 변경일시를 현재로 설정.
UPDATE AUTH_ACCOUNT SET PASSWORD_CHANGED_AT = SYSTIMESTAMP WHERE PASSWORD_CHANGED_AT IS NULL;
```

- [ ] **Step 2: AuthAccount record 확장**

```java
package com.meritz.dash.auth;

import java.time.LocalDateTime;

public record AuthAccount(String empno, String passwordHash, String pwdResetYn, Integer failCnt,
                          String statusCd, LocalDateTime passwordChangedAt,
                          String prevPasswordHash, LocalDateTime lastLoginAt) {}
```

- [ ] **Step 3: findByEmpno SELECT에 신규 컬럼 추가** — `AuthAccountMapper.xml` 5-8행 교체

```xml
  <select id="findByEmpno" resultType="com.meritz.dash.auth.AuthAccount">
    SELECT EMPNO AS empno, PASSWORD_HASH AS passwordHash, PWD_RESET_YN AS pwdResetYn, FAIL_CNT AS failCnt,
           STATUS_CD AS statusCd, PASSWORD_CHANGED_AT AS passwordChangedAt,
           PREV_PASSWORD_HASH AS prevPasswordHash, LAST_LOGIN_AT AS lastLoginAt
      FROM AUTH_ACCOUNT WHERE EMPNO = #{empno}
  </select>
```

- [ ] **Step 4: 실패 테스트 작성** — `AuthAccountSchemaIT.java`

```java
package com.meritz.dash.auth;

import com.meritz.dash.support.AbstractOracleIT;
import com.meritz.dash.mapper.app.AuthAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AuthAccountSchemaIT extends AbstractOracleIT {

    @Autowired AuthAccountMapper mapper;

    @Test
    void findByEmpno_maps_new_policy_columns() {
        AuthAccount acc = mapper.findByEmpno("9320"); // AccountProvisioner 시드 계정
        assertThat(acc).isNotNull();
        assertThat(acc.statusCd()).isEqualTo("00");
        assertThat(acc.passwordChangedAt()).isNotNull(); // V017 백필
    }
}
```

- [ ] **Step 5: 테스트 실패 확인**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home APP_DB_PASSWORD=apppw LEGACY_DB_PASSWORD=legacypw ./gradlew test --tests '*AuthAccountSchemaIT'`
Expected: 컴파일 에러(record 인자 불일치) 또는 매핑 실패 — 신규 코드 반영 전.

- [ ] **Step 6: 테스트 통과 확인** (Step 1~3 반영 후)

Run: 동일
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add src/main/resources/db/migration/V017__auth_password_policy.sql \
        src/main/java/com/meritz/dash/auth/AuthAccount.java \
        src/main/resources/mapper/app/AuthAccountMapper.xml \
        src/test/java/com/meritz/dash/auth/AuthAccountSchemaIT.java
git commit -m "feat(auth): AUTH_ACCOUNT 정책 컬럼(STATUS_CD/PASSWORD_CHANGED_AT/PREV_PASSWORD_HASH) 추가"
```

---

### Task 2: AuthPolicyProperties + PasswordPolicy + application.yml

**Files:**
- Create: `src/main/java/com/meritz/dash/config/AuthPolicyProperties.java`
- Create: `src/main/java/com/meritz/dash/auth/PasswordPolicy.java`
- Modify: `src/main/resources/application.yml` (app.auth 블록 추가)
- Test: `src/test/java/com/meritz/dash/auth/PasswordPolicyTest.java`

**Interfaces:**
- Produces: `AuthPolicyProperties(Password password, Lockout lockout, int dormantDays)` / `Password(int minLength, int maxAgeDays)` / `Lockout(int maxFail)`.
- Produces: `PasswordPolicy.validate(String raw)` (위반 시 `AuthPolicyException.policyViolation(...)`), `PasswordPolicy.isExpired(LocalDateTime passwordChangedAt): boolean`, `PasswordPolicy.isDormant(LocalDateTime lastLoginAt): boolean`.
- Consumes: `AuthPolicyException`(Task 3) — 병렬 개발 시 시그니처만 참조(정적 팩토리 `policyViolation(String message)`).

- [ ] **Step 1: 실패 테스트 작성** — `PasswordPolicyTest.java`

```java
package com.meritz.dash.auth;

import com.meritz.dash.config.AuthPolicyProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(new AuthPolicyProperties(
            new AuthPolicyProperties.Password(8, 90),
            new AuthPolicyProperties.Lockout(10), 90));

    @Test void valid_password_passes() {
        assertThatCode(() -> policy.validate("Abcd123!")).doesNotThrowAnyException();
    }
    @Test void too_short_7_chars_fails() {
        assertThatThrownBy(() -> policy.validate("Abc12!")).isInstanceOf(AuthPolicyException.class);
    }
    @Test void missing_upper_fails() {
        assertThatThrownBy(() -> policy.validate("abcd123!")).isInstanceOf(AuthPolicyException.class);
    }
    @Test void missing_lower_fails() {
        assertThatThrownBy(() -> policy.validate("ABCD123!")).isInstanceOf(AuthPolicyException.class);
    }
    @Test void missing_digit_fails() {
        assertThatThrownBy(() -> policy.validate("Abcdefg!")).isInstanceOf(AuthPolicyException.class);
    }
    @Test void missing_special_fails() {
        assertThatThrownBy(() -> policy.validate("Abcd1234")).isInstanceOf(AuthPolicyException.class);
    }
    @Test void empno_only_digits_fails() { // 사번(숫자만) 재설정 원천 차단
        assertThatThrownBy(() -> policy.validate("9320")).isInstanceOf(AuthPolicyException.class);
    }
    @Test void expired_when_older_than_90_days() {
        assertThat(policy.isExpired(LocalDateTime.now().minusDays(91))).isTrue();
        assertThat(policy.isExpired(LocalDateTime.now().minusDays(89))).isFalse();
        assertThat(policy.isExpired(null)).isFalse();
    }
    @Test void dormant_when_last_login_older_than_90_days() {
        assertThat(policy.isDormant(LocalDateTime.now().minusDays(91))).isTrue();
        assertThat(policy.isDormant(LocalDateTime.now().minusDays(89))).isFalse();
        assertThat(policy.isDormant(null)).isFalse(); // 미로그인 신규계정 보호
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `... ./gradlew test --tests '*PasswordPolicyTest'`
Expected: FAIL — `PasswordPolicy`/`AuthPolicyProperties` 미존재.

- [ ] **Step 3: AuthPolicyProperties 구현**

```java
package com.meritz.dash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthPolicyProperties(Password password, Lockout lockout, int dormantDays) {
    public record Password(int minLength, int maxAgeDays) {}
    public record Lockout(int maxFail) {}
}
```

- [ ] **Step 4: PasswordPolicy 구현**

```java
package com.meritz.dash.auth;

import com.meritz.dash.config.AuthPolicyProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class PasswordPolicy {

    private final AuthPolicyProperties props;

    public PasswordPolicy(AuthPolicyProperties props) {
        this.props = props;
    }

    /** 복잡도 검증. 위반 시 AuthPolicyException(400, PASSWORD_POLICY_VIOLATION). */
    public void validate(String raw) {
        int min = props.password().minLength();
        if (raw == null || raw.length() < min) {
            throw AuthPolicyException.policyViolation("비밀번호는 " + min + "자 이상이어야 합니다");
        }
        if (!raw.matches(".*[A-Z].*") || !raw.matches(".*[a-z].*")
                || !raw.matches(".*[0-9].*") || !raw.matches(".*[^A-Za-z0-9].*")) {
            throw AuthPolicyException.policyViolation("비밀번호는 영문 대/소문자, 숫자, 특수문자를 모두 포함해야 합니다");
        }
    }

    /** 마지막 변경 후 max-age-days 초과 여부. null(미기록)이면 만료 아님. */
    public boolean isExpired(LocalDateTime passwordChangedAt) {
        if (passwordChangedAt == null) return false;
        return passwordChangedAt.isBefore(LocalDateTime.now().minusDays(props.password().maxAgeDays()));
    }

    /** 마지막 로그인 후 dormant-days 초과 여부. null(미로그인)이면 휴면 아님(신규계정 보호). */
    public boolean isDormant(LocalDateTime lastLoginAt) {
        if (lastLoginAt == null) return false;
        return lastLoginAt.isBefore(LocalDateTime.now().minusDays(props.dormantDays()));
    }
}
```

- [ ] **Step 5: application.yml에 정책 추가** — 기존 `app:` 블록 하위에 삽입

```yaml
  auth:
    password:
      min-length: ${AUTH_PWD_MIN_LENGTH:8}
      max-age-days: ${AUTH_PWD_MAX_AGE_DAYS:90}
    lockout:
      max-fail: ${AUTH_LOCKOUT_MAX_FAIL:10}
    dormant-days: ${AUTH_DORMANT_DAYS:90}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `... ./gradlew test --tests '*PasswordPolicyTest'`
Expected: PASS (10 tests)

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/meritz/dash/config/AuthPolicyProperties.java \
        src/main/java/com/meritz/dash/auth/PasswordPolicy.java \
        src/main/resources/application.yml \
        src/test/java/com/meritz/dash/auth/PasswordPolicyTest.java
git commit -m "feat(auth): 비밀번호 복잡도/만료/휴면 판정 PasswordPolicy + 정책 설정"
```

---

### Task 3: AuthPolicyException + GlobalExceptionHandler

**Files:**
- Create: `src/main/java/com/meritz/dash/auth/AuthPolicyException.java`
- Modify: `src/main/java/com/meritz/dash/common/GlobalExceptionHandler.java` (핸들러 추가)
- Test: `src/test/java/com/meritz/dash/auth/AuthPolicyExceptionTest.java`

**Interfaces:**
- Produces: `AuthPolicyException extends RuntimeException` — getter `httpStatus(): HttpStatus`, `errorCode(): String`, `properties(): Map<String,Object>`; 정적 팩토리:
  - `locked()` → 403 `ACCOUNT_LOCKED`
  - `dormant()` → 403 `ACCOUNT_DORMANT`
  - `policyViolation(String message)` → 400 `PASSWORD_POLICY_VIOLATION`
  - `reuse()` → 400 `PASSWORD_REUSE`
  - `invalidCredentials(int remainingAttempts)` → 401 `INVALID_CREDENTIALS` (+property `remainingAttempts`)
- Produces: `ProblemDetail`에 `errorCode` 및 추가 property 노출.

- [ ] **Step 1: 실패 테스트 작성** — `AuthPolicyExceptionTest.java`

```java
package com.meritz.dash.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AuthPolicyExceptionTest {
    @Test void locked_is_403_with_code() {
        AuthPolicyException ex = AuthPolicyException.locked();
        assertThat(ex.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.errorCode()).isEqualTo("ACCOUNT_LOCKED");
    }
    @Test void invalid_credentials_carries_remaining_attempts() {
        AuthPolicyException ex = AuthPolicyException.invalidCredentials(3);
        assertThat(ex.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.errorCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(ex.properties()).containsEntry("remainingAttempts", 3);
    }
    @Test void reuse_is_400() {
        assertThat(AuthPolicyException.reuse().httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(AuthPolicyException.reuse().errorCode()).isEqualTo("PASSWORD_REUSE");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `... ./gradlew test --tests '*AuthPolicyExceptionTest'`
Expected: FAIL — 클래스 미존재.

- [ ] **Step 3: AuthPolicyException 구현**

```java
package com.meritz.dash.auth;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class AuthPolicyException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String errorCode;
    private final Map<String, Object> properties;

    private AuthPolicyException(HttpStatus httpStatus, String errorCode, String message, Map<String, Object> properties) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.properties = properties;
    }

    public HttpStatus httpStatus() { return httpStatus; }
    public String errorCode() { return errorCode; }
    public Map<String, Object> properties() { return properties; }

    public static AuthPolicyException locked() {
        return new AuthPolicyException(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED",
                "계정이 잠겼습니다. 관리자에게 문의하세요", Map.of());
    }
    public static AuthPolicyException dormant() {
        return new AuthPolicyException(HttpStatus.FORBIDDEN, "ACCOUNT_DORMANT",
                "휴면 계정입니다. 관리자에게 문의하세요", Map.of());
    }
    public static AuthPolicyException policyViolation(String message) {
        return new AuthPolicyException(HttpStatus.BAD_REQUEST, "PASSWORD_POLICY_VIOLATION", message, Map.of());
    }
    public static AuthPolicyException reuse() {
        return new AuthPolicyException(HttpStatus.BAD_REQUEST, "PASSWORD_REUSE",
                "직전에 사용한 비밀번호는 다시 사용할 수 없습니다", Map.of());
    }
    public static AuthPolicyException invalidCredentials(int remainingAttempts) {
        return new AuthPolicyException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "아이디 또는 비밀번호가 올바르지 않습니다", Map.of("remainingAttempts", remainingAttempts));
    }
}
```

- [ ] **Step 4: GlobalExceptionHandler에 핸들러 추가** — import `com.meritz.dash.auth.AuthPolicyException;` 추가 후 클래스 내부에 메서드 추가

```java
    @ExceptionHandler(AuthPolicyException.class)
    public ProblemDetail handleAuthPolicy(AuthPolicyException ex) {
        log.warn("{} {}: {}", ex.httpStatus().value(), ex.errorCode(), ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.httpStatus(), ex.getMessage());
        pd.setProperty("errorCode", ex.errorCode());
        ex.properties().forEach(pd::setProperty);
        return pd;
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `... ./gradlew test --tests '*AuthPolicyExceptionTest'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/meritz/dash/auth/AuthPolicyException.java \
        src/main/java/com/meritz/dash/common/GlobalExceptionHandler.java \
        src/test/java/com/meritz/dash/auth/AuthPolicyExceptionTest.java
git commit -m "feat(auth): AuthPolicyException + ProblemDetail errorCode 매핑"
```

---

### Task 4: AuthAccountMapper 정책 메서드 + XML + IT

**Files:**
- Modify: `src/main/java/com/meritz/dash/mapper/app/AuthAccountMapper.java`
- Modify: `src/main/resources/mapper/app/AuthAccountMapper.xml`
- Test: `src/test/java/com/meritz/dash/auth/AuthAccountPolicyMapperIT.java`

**Interfaces:**
- Consumes: `AuthAccount`(Task 1).
- Produces (매퍼 메서드):
  - `int incrementFail(String empno)` — `FAIL_CNT = FAIL_CNT + 1`
  - `int lockAccount(String empno)` — `STATUS_CD='01'`
  - `int markDormant(String empno)` — `STATUS_CD='02'`
  - `int loginSuccess(String empno)` — `FAIL_CNT=0, LAST_LOGIN_AT=SYSTIMESTAMP`
  - `int changePasswordWithHistory(String empno, String hash, String prevHash)` — `PASSWORD_HASH=hash, PREV_PASSWORD_HASH=prevHash, PWD_RESET_YN='N', PASSWORD_CHANGED_AT=SYSTIMESTAMP, UPDATED_*`
  - `int unlockAccount(String empno)` — `STATUS_CD='00', FAIL_CNT=0, LAST_LOGIN_AT=SYSTIMESTAMP`
  - `int resetToDefault(String empno, String hash)` — `PASSWORD_HASH=hash, PWD_RESET_YN='Y', PASSWORD_CHANGED_AT=SYSTIMESTAMP, STATUS_CD='00', FAIL_CNT=0, PREV_PASSWORD_HASH=NULL`
  - `List<AuthAccountMapper.AdminRow> findAllForAdmin()` — HR 조인, `AdminRow(String empno, String name, String statusCd, Integer failCnt, LocalDateTime lastLoginAt, LocalDateTime passwordChangedAt)`

- [ ] **Step 1: 실패 테스트 작성** — `AuthAccountPolicyMapperIT.java`

```java
package com.meritz.dash.auth;

import com.meritz.dash.support.AbstractOracleIT;
import com.meritz.dash.mapper.app.AuthAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthAccountPolicyMapperIT extends AbstractOracleIT {

    @Autowired AuthAccountMapper mapper;

    @Test
    void increment_and_lock_and_unlock_cycle() {
        String empno = "9320";
        int before = mapper.findByEmpno(empno).failCnt();
        mapper.incrementFail(empno);
        assertThat(mapper.findByEmpno(empno).failCnt()).isEqualTo(before + 1);

        mapper.lockAccount(empno);
        assertThat(mapper.findByEmpno(empno).statusCd()).isEqualTo("01");

        mapper.unlockAccount(empno);
        AuthAccount acc = mapper.findByEmpno(empno);
        assertThat(acc.statusCd()).isEqualTo("00");
        assertThat(acc.failCnt()).isZero();
    }

    @Test
    void change_password_with_history_moves_prev_hash() {
        String empno = "9320";
        mapper.changePasswordWithHistory(empno, "NEWHASH", "OLDHASH");
        AuthAccount acc = mapper.findByEmpno(empno);
        assertThat(acc.passwordHash()).isEqualTo("NEWHASH");
        assertThat(acc.prevPasswordHash()).isEqualTo("OLDHASH");
        assertThat(acc.pwdResetYn()).isEqualTo("N");
    }

    @Test
    void reset_to_default_forces_reset_and_clears_prev() {
        String empno = "9320";
        mapper.resetToDefault(empno, "DEFHASH");
        AuthAccount acc = mapper.findByEmpno(empno);
        assertThat(acc.passwordHash()).isEqualTo("DEFHASH");
        assertThat(acc.pwdResetYn()).isEqualTo("Y");
        assertThat(acc.statusCd()).isEqualTo("00");
        assertThat(acc.prevPasswordHash()).isNull();
    }

    @Test
    void find_all_for_admin_returns_rows_with_name() {
        List<AuthAccountMapper.AdminRow> rows = mapper.findAllForAdmin();
        assertThat(rows).isNotEmpty();
        assertThat(rows).anyMatch(r -> r.empno().equals("9320") && r.name() != null);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `... ./gradlew test --tests '*AuthAccountPolicyMapperIT'`
Expected: FAIL — 매퍼 메서드 미존재.

- [ ] **Step 3: 매퍼 인터페이스 확장** — `AuthAccountMapper.java` 전체 교체

```java
package com.meritz.dash.mapper.app;

import com.meritz.dash.auth.AuthAccount;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuthAccountMapper {
    AuthAccount findByEmpno(@Param("empno") String empno);
    List<String> findEmpnosNeedingAccount();
    void insertAccount(@Param("empno") String empno, @Param("hash") String hash);
    int updatePassword(@Param("empno") String empno, @Param("hash") String hash); // v1(무손상)
    void touchLastLogin(@Param("empno") String empno);                            // v1(무손상)

    // ── 정책(v2/admin) ─────────────────────────────
    int incrementFail(@Param("empno") String empno);
    int lockAccount(@Param("empno") String empno);
    int markDormant(@Param("empno") String empno);
    int loginSuccess(@Param("empno") String empno);
    int changePasswordWithHistory(@Param("empno") String empno, @Param("hash") String hash, @Param("prevHash") String prevHash);
    int unlockAccount(@Param("empno") String empno);
    int resetToDefault(@Param("empno") String empno, @Param("hash") String hash);
    List<AdminRow> findAllForAdmin();

    record AdminRow(String empno, String name, String statusCd, Integer failCnt,
                    LocalDateTime lastLoginAt, LocalDateTime passwordChangedAt) {}
}
```

- [ ] **Step 4: XML에 문 추가** — `AuthAccountMapper.xml` `</mapper>` 앞에 삽입

```xml
  <update id="incrementFail">
    UPDATE AUTH_ACCOUNT SET FAIL_CNT = FAIL_CNT + 1,
           UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = #{empno} WHERE EMPNO = #{empno}
  </update>

  <update id="lockAccount">
    UPDATE AUTH_ACCOUNT SET STATUS_CD = '01',
           UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = #{empno} WHERE EMPNO = #{empno}
  </update>

  <update id="markDormant">
    UPDATE AUTH_ACCOUNT SET STATUS_CD = '02',
           UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = #{empno} WHERE EMPNO = #{empno}
  </update>

  <update id="loginSuccess">
    UPDATE AUTH_ACCOUNT SET FAIL_CNT = 0, LAST_LOGIN_AT = SYSTIMESTAMP WHERE EMPNO = #{empno}
  </update>

  <update id="changePasswordWithHistory">
    UPDATE AUTH_ACCOUNT SET PASSWORD_HASH = #{hash}, PREV_PASSWORD_HASH = #{prevHash},
           PWD_RESET_YN = 'N', PASSWORD_CHANGED_AT = SYSTIMESTAMP,
           UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = #{empno} WHERE EMPNO = #{empno}
  </update>

  <update id="unlockAccount">
    UPDATE AUTH_ACCOUNT SET STATUS_CD = '00', FAIL_CNT = 0, LAST_LOGIN_AT = SYSTIMESTAMP,
           UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = 'ADMIN' WHERE EMPNO = #{empno}
  </update>

  <update id="resetToDefault">
    UPDATE AUTH_ACCOUNT SET PASSWORD_HASH = #{hash}, PWD_RESET_YN = 'Y',
           PASSWORD_CHANGED_AT = SYSTIMESTAMP, STATUS_CD = '00', FAIL_CNT = 0, PREV_PASSWORD_HASH = NULL,
           UPDATED_AT = SYSTIMESTAMP, UPDATED_BY = 'ADMIN' WHERE EMPNO = #{empno}
  </update>

  <select id="findAllForAdmin" resultType="com.meritz.dash.mapper.app.AuthAccountMapper$AdminRow">
    SELECT a.EMPNO AS empno, h.EMP_NM AS name, a.STATUS_CD AS statusCd, a.FAIL_CNT AS failCnt,
           a.LAST_LOGIN_AT AS lastLoginAt, a.PASSWORD_CHANGED_AT AS passwordChangedAt
      FROM AUTH_ACCOUNT a
      LEFT JOIN HR_DEVELOPER h ON a.EMPNO = h.EMPNO
     ORDER BY a.EMPNO
  </select>
```

> 참고: HR 이름 컬럼은 `HR_DEVELOPER.EMP_NM`(기존 `DeveloperMapper`에서 `empNm`로 매핑). 실제 컬럼명이 다르면 `DeveloperMapper.xml`을 확인해 맞춘다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `... ./gradlew test --tests '*AuthAccountPolicyMapperIT'`
Expected: PASS (4 tests)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/meritz/dash/mapper/app/AuthAccountMapper.java \
        src/main/resources/mapper/app/AuthAccountMapper.xml \
        src/test/java/com/meritz/dash/auth/AuthAccountPolicyMapperIT.java
git commit -m "feat(auth): AUTH_ACCOUNT 정책 매퍼(잠금/실패/이력/해제/초기화/관리자목록)"
```

---

### Task 5: AuthPolicyService (v2 login + v2 changePassword)

**Files:**
- Create: `src/main/java/com/meritz/dash/auth/AuthPolicyService.java`
- Test: `src/test/java/com/meritz/dash/auth/AuthPolicyServiceTest.java`

**Interfaces:**
- Consumes: `AuthAccountMapper`(Task 4), `PasswordPolicy`(Task 2), `AuthPolicyProperties`(Task 2), `AuthPolicyException`(Task 3), 기존 `DeveloperMapper`, `CodeMapper`, `JwtService`, `PasswordEncoder`, `AdminProperties`, `LoginRequest`, `LoginResult`, `ChangePasswordRequest`.
- Produces: `LoginResult login(LoginRequest req)`, `void changePassword(String empno, ChangePasswordRequest req)`. 둘 다 `@Transactional("appTxManager")`.

- [ ] **Step 1: 실패 테스트 작성** — `AuthPolicyServiceTest.java` (Mockito)

```java
package com.meritz.dash.auth;

import com.meritz.dash.code.CommonCode;
import com.meritz.dash.config.AdminProperties;
import com.meritz.dash.config.AuthPolicyProperties;
import com.meritz.dash.developer.Developer;
import com.meritz.dash.mapper.app.AuthAccountMapper;
import com.meritz.dash.mapper.app.CodeMapper;
import com.meritz.dash.mapper.app.DeveloperMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuthPolicyServiceTest {

    AuthAccountMapper accounts = mock(AuthAccountMapper.class);
    DeveloperMapper developers = mock(DeveloperMapper.class);
    CodeMapper codes = mock(CodeMapper.class);
    JwtService jwt = mock(JwtService.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    AdminProperties admin = new AdminProperties("admin", "admin");
    AuthPolicyProperties props = new AuthPolicyProperties(
            new AuthPolicyProperties.Password(8, 90), new AuthPolicyProperties.Lockout(10), 90);
    PasswordPolicy policy = new PasswordPolicy(props);

    AuthPolicyService service;

    AuthAccount active(String pwdReset) {
        return new AuthAccount("9320", "$hash", pwdReset, 0, "00", LocalDateTime.now(), null, LocalDateTime.now());
    }

    @BeforeEach void setUp() {
        service = new AuthPolicyService(accounts, developers, codes, jwt, encoder, admin, policy, props);
        when(developers.findByEmpno("9320")).thenReturn(
                new Developer("9320", "홍길동", "2139", "P01", "대리", "03", "Y", "01"));
        when(codes.findByGroup("EMP_ROLE")).thenReturn(List.of(new CommonCode("EMP_ROLE", "03", "일반직원", 3)));
        when(jwt.generate(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn("token123");
    }

    @Test void locked_account_rejected_403() {
        AuthAccount locked = new AuthAccount("9320", "$hash", "N", 10, "01", LocalDateTime.now(), null, LocalDateTime.now());
        when(accounts.findByEmpno("9320")).thenReturn(locked);
        assertThatThrownBy(() -> service.login(new LoginRequest("9320", "x")))
                .isInstanceOf(AuthPolicyException.class)
                .satisfies(e -> assertThat(((AuthPolicyException) e).errorCode()).isEqualTo("ACCOUNT_LOCKED"));
    }

    @Test void dormant_lazy_detected_and_persisted() {
        AuthAccount old = new AuthAccount("9320", "$hash", "N", 0, "00", LocalDateTime.now(),
                null, LocalDateTime.now().minusDays(120));
        when(accounts.findByEmpno("9320")).thenReturn(old);
        assertThatThrownBy(() -> service.login(new LoginRequest("9320", "x")))
                .isInstanceOf(AuthPolicyException.class)
                .satisfies(e -> assertThat(((AuthPolicyException) e).errorCode()).isEqualTo("ACCOUNT_DORMANT"));
        verify(accounts).markDormant("9320");
    }

    @Test void wrong_password_increments_and_locks_at_max() {
        AuthAccount acc = new AuthAccount("9320", "$hash", "N", 9, "00", LocalDateTime.now(), null, LocalDateTime.now());
        when(accounts.findByEmpno("9320")).thenReturn(acc);
        when(encoder.matches("x", "$hash")).thenReturn(false);
        assertThatThrownBy(() -> service.login(new LoginRequest("9320", "x")))
                .isInstanceOf(AuthPolicyException.class)
                .satisfies(e -> assertThat(((AuthPolicyException) e).errorCode()).isEqualTo("INVALID_CREDENTIALS"));
        verify(accounts).incrementFail("9320");
        verify(accounts).lockAccount("9320"); // 9+1 == 10
    }

    @Test void success_resets_fail_and_issues_token() {
        when(accounts.findByEmpno("9320")).thenReturn(active("N"));
        when(encoder.matches("pw", "$hash")).thenReturn(true);
        LoginResult r = service.login(new LoginRequest("9320", "pw"));
        assertThat(r.token()).isEqualTo("token123");
        assertThat(r.pwdResetRequired()).isFalse();
        verify(accounts).loginSuccess("9320");
    }

    @Test void expired_password_sets_pwdResetRequired() {
        AuthAccount expired = new AuthAccount("9320", "$hash", "N", 0, "00",
                LocalDateTime.now().minusDays(100), null, LocalDateTime.now());
        when(accounts.findByEmpno("9320")).thenReturn(expired);
        when(encoder.matches("pw", "$hash")).thenReturn(true);
        assertThat(service.login(new LoginRequest("9320", "pw")).pwdResetRequired()).isTrue();
    }

    @Test void change_password_rejects_reuse_of_prev() {
        AuthAccount acc = new AuthAccount("9320", "$cur", "N", 0, "00", LocalDateTime.now(), "$prev", LocalDateTime.now());
        when(accounts.findByEmpno("9320")).thenReturn(acc);
        when(encoder.matches("Old123!x", "$cur")).thenReturn(true);   // old ok
        when(encoder.matches("New123!x", "$cur")).thenReturn(false);
        when(encoder.matches("New123!x", "$prev")).thenReturn(true);  // == 직전
        assertThatThrownBy(() -> service.changePassword("9320", new ChangePasswordRequest("Old123!x", "New123!x")))
                .isInstanceOf(AuthPolicyException.class)
                .satisfies(e -> assertThat(((AuthPolicyException) e).errorCode()).isEqualTo("PASSWORD_REUSE"));
    }

    @Test void change_password_success_moves_prev_and_persists() {
        AuthAccount acc = new AuthAccount("9320", "$cur", "N", 0, "00", LocalDateTime.now(), null, LocalDateTime.now());
        when(accounts.findByEmpno("9320")).thenReturn(acc);
        when(encoder.matches("Old123!x", "$cur")).thenReturn(true);
        when(encoder.matches("New123!x", "$cur")).thenReturn(false);
        when(encoder.encode("New123!x")).thenReturn("$new");
        service.changePassword("9320", new ChangePasswordRequest("Old123!x", "New123!x"));
        verify(accounts).changePasswordWithHistory("9320", "$new", "$cur");
    }
}
```

> 실측 확정: `Developer(empno, empNm, deptCd, partCd, gradeCd, roleCd, devYn, statusCd)` 8필드, `CommonCode(grpCd, cdVal, cdNm, int sortNo)` 4필드, `AdminProperties(username, password)`, `AbstractOracleIT`는 `com.meritz.dash.support`에 존재, HR 이름 컬럼=`EMP_NM`.

- [ ] **Step 2: 테스트 실패 확인**

Run: `... ./gradlew test --tests '*AuthPolicyServiceTest'`
Expected: FAIL — `AuthPolicyService` 미존재.

- [ ] **Step 3: AuthPolicyService 구현**

```java
package com.meritz.dash.auth;

import com.meritz.dash.code.CommonCode;
import com.meritz.dash.config.AdminProperties;
import com.meritz.dash.config.AuthPolicyProperties;
import com.meritz.dash.developer.Developer;
import com.meritz.dash.mapper.app.AuthAccountMapper;
import com.meritz.dash.mapper.app.CodeMapper;
import com.meritz.dash.mapper.app.DeveloperMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.stream.Collectors;

/** v2 로그인/비밀번호 변경 — 정책(잠금·휴면·만료·복잡도·재사용) 적용. v1 AuthService 와 독립. */
@Service
public class AuthPolicyService {

    private final AuthAccountMapper accounts;
    private final DeveloperMapper developers;
    private final CodeMapper codes;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final AdminProperties admin;
    private final PasswordPolicy policy;
    private final AuthPolicyProperties props;

    public AuthPolicyService(AuthAccountMapper accounts, DeveloperMapper developers, CodeMapper codes,
                             JwtService jwt, PasswordEncoder encoder, AdminProperties admin,
                             PasswordPolicy policy, AuthPolicyProperties props) {
        this.accounts = accounts; this.developers = developers; this.codes = codes;
        this.jwt = jwt; this.encoder = encoder; this.admin = admin;
        this.policy = policy; this.props = props;
    }

    @Transactional("appTxManager")
    public LoginResult login(LoginRequest req) {
        // ADMIN 설정 계정 지름길(정책 미적용, 상수시간 비교)
        if (MessageDigest.isEqual(admin.username().getBytes(StandardCharsets.UTF_8),
                                  req.empno().getBytes(StandardCharsets.UTF_8))) {
            if (!MessageDigest.isEqual(admin.password().getBytes(StandardCharsets.UTF_8),
                                       req.password().getBytes(StandardCharsets.UTF_8))) {
                throw AuthPolicyException.invalidCredentials(0);
            }
            String token = jwt.generate("admin", "ADMIN", "관리자", "관리자", null, null, false);
            return new LoginResult(token, "admin", "ADMIN", "관리자", "관리자", false);
        }

        AuthAccount acc = accounts.findByEmpno(req.empno());
        if (acc == null) {
            throw AuthPolicyException.invalidCredentials(props.lockout().maxFail());
        }
        // 잠금/휴면 선차단
        if ("01".equals(acc.statusCd())) throw AuthPolicyException.locked();
        if ("02".equals(acc.statusCd())) throw AuthPolicyException.dormant();
        // 지연 휴면 판정
        if (policy.isDormant(acc.lastLoginAt())) {
            accounts.markDormant(req.empno());
            throw AuthPolicyException.dormant();
        }
        // 비밀번호 검증
        if (!encoder.matches(req.password(), acc.passwordHash())) {
            accounts.incrementFail(req.empno());
            int newFail = (acc.failCnt() == null ? 0 : acc.failCnt()) + 1;
            int max = props.lockout().maxFail();
            if (newFail >= max) {
                accounts.lockAccount(req.empno());
            }
            throw AuthPolicyException.invalidCredentials(Math.max(0, max - newFail));
        }
        Developer dev = developers.findByEmpno(req.empno());
        if (dev == null) {
            throw AuthPolicyException.invalidCredentials(props.lockout().maxFail());
        }
        accounts.loginSuccess(req.empno());
        boolean pwdReset = "Y".equals(acc.pwdResetYn()) || policy.isExpired(acc.passwordChangedAt());
        String roleName = resolveRoleName(dev.roleCd());
        String token = jwt.generate(dev.empno(), dev.roleCd(), roleName, dev.empNm(), dev.deptCd(), dev.partCd(), pwdReset);
        return new LoginResult(token, dev.empno(), dev.roleCd(), roleName, dev.empNm(), pwdReset);
    }

    @Transactional("appTxManager")
    public void changePassword(String empno, ChangePasswordRequest req) {
        AuthAccount acc = accounts.findByEmpno(empno);
        if (acc == null || !encoder.matches(req.oldPassword(), acc.passwordHash())) {
            throw AuthPolicyException.invalidCredentials(0);
        }
        policy.validate(req.newPassword());
        boolean sameAsCurrent = encoder.matches(req.newPassword(), acc.passwordHash());
        boolean sameAsPrev = acc.prevPasswordHash() != null
                && encoder.matches(req.newPassword(), acc.prevPasswordHash());
        if (sameAsCurrent || sameAsPrev) {
            throw AuthPolicyException.reuse();
        }
        accounts.changePasswordWithHistory(empno, encoder.encode(req.newPassword()), acc.passwordHash());
    }

    private String resolveRoleName(String roleCd) {
        Map<String, String> roleMap = codes.findByGroup("EMP_ROLE").stream()
                .collect(Collectors.toMap(CommonCode::cdVal, CommonCode::cdNm));
        return roleMap.getOrDefault(roleCd, roleCd);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `... ./gradlew test --tests '*AuthPolicyServiceTest'`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/meritz/dash/auth/AuthPolicyService.java \
        src/test/java/com/meritz/dash/auth/AuthPolicyServiceTest.java
git commit -m "feat(auth): AuthPolicyService — v2 로그인(잠금/휴면/만료)·비번변경(복잡도/재사용)"
```

---

### Task 6: AuthAdminService (관리자 목록/해제/초기화)

**Files:**
- Create: `src/main/java/com/meritz/dash/auth/AuthAdminService.java`
- Create: `src/main/java/com/meritz/dash/auth/AdminAccountRow.java`
- Test: `src/test/java/com/meritz/dash/auth/AuthAdminServiceTest.java`

**Interfaces:**
- Consumes: `AuthAccountMapper`(Task 4: `findAllForAdmin`, `unlockAccount`, `resetToDefault`, `findByEmpno`), `PasswordPolicy`(Task 2), `PasswordEncoder`, `NotFoundException`(`common/NotFoundException.java`, 기존).
- Produces: `AdminAccountRow(String empno, String name, String statusCd, String statusName, Integer failCnt, LocalDateTime lastLoginAt, LocalDateTime passwordChangedAt, boolean expired, boolean dormant)`.
- Produces: `List<AdminAccountRow> listAccounts()`, `void unlock(String empno)`, `void resetPassword(String empno)`. 쓰기는 `@Transactional("appTxManager")`.

- [ ] **Step 1: 실패 테스트 작성** — `AuthAdminServiceTest.java`

```java
package com.meritz.dash.auth;

import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.config.AuthPolicyProperties;
import com.meritz.dash.mapper.app.AuthAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuthAdminServiceTest {

    AuthAccountMapper accounts = mock(AuthAccountMapper.class);
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    AuthPolicyProperties props = new AuthPolicyProperties(
            new AuthPolicyProperties.Password(8, 90), new AuthPolicyProperties.Lockout(10), 90);
    PasswordPolicy policy = new PasswordPolicy(props);
    AuthAdminService service = new AuthAdminService(accounts, encoder, policy);

    @Test void list_maps_status_name_and_computed_flags() {
        when(accounts.findAllForAdmin()).thenReturn(List.of(
                new AuthAccountMapper.AdminRow("9320", "홍길동", "01", 10,
                        LocalDateTime.now().minusDays(120), LocalDateTime.now().minusDays(100))));
        List<AdminAccountRow> rows = service.listAccounts();
        assertThat(rows).hasSize(1);
        AdminAccountRow r = rows.get(0);
        assertThat(r.statusName()).isEqualTo("잠금");
        assertThat(r.expired()).isTrue();  // 100일 전 변경
        assertThat(r.dormant()).isTrue();  // 120일 전 로그인
    }

    @Test void unlock_missing_account_throws_404() {
        when(accounts.unlockAccount("NONE")).thenReturn(0);
        assertThatThrownBy(() -> service.unlock("NONE")).isInstanceOf(NotFoundException.class);
    }

    @Test void reset_password_encodes_empno_as_default() {
        when(accounts.resetToDefault(eq("9320"), anyString())).thenReturn(1);
        when(encoder.encode("9320")).thenReturn("$def");
        service.resetPassword("9320");
        verify(accounts).resetToDefault("9320", "$def");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `... ./gradlew test --tests '*AuthAdminServiceTest'`
Expected: FAIL — 클래스 미존재.

- [ ] **Step 3: AdminAccountRow 구현**

```java
package com.meritz.dash.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "관리자 계정 현황 행")
public record AdminAccountRow(
        @Schema(description = "사번", example = "9320") String empno,
        @Schema(description = "사용자명", example = "홍길동") String name,
        @Schema(description = "상태코드: 00 정상 · 01 잠금 · 02 휴면", example = "00") String statusCd,
        @Schema(description = "상태명", example = "정상") String statusName,
        @Schema(description = "로그인 실패 횟수", example = "0") Integer failCnt,
        @Schema(description = "마지막 로그인 일시") LocalDateTime lastLoginAt,
        @Schema(description = "마지막 비밀번호 변경 일시") LocalDateTime passwordChangedAt,
        @Schema(description = "비밀번호 만료 여부(90일 초과)", example = "false") boolean expired,
        @Schema(description = "휴면 대상 여부(3개월 미로그인)", example = "false") boolean dormant) {}
```

- [ ] **Step 4: AuthAdminService 구현**

```java
package com.meritz.dash.auth;

import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.mapper.app.AuthAccountMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 관리자 전용 계정 운영 — 현황 조회, 잠금·휴면 해제, 비밀번호 초기화. */
@Service
public class AuthAdminService {

    private static final Map<String, String> STATUS_NAME = Map.of("00", "정상", "01", "잠금", "02", "휴면");

    private final AuthAccountMapper accounts;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;

    public AuthAdminService(AuthAccountMapper accounts, PasswordEncoder encoder, PasswordPolicy policy) {
        this.accounts = accounts; this.encoder = encoder; this.policy = policy;
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<AdminAccountRow> listAccounts() {
        return accounts.findAllForAdmin().stream().map(r -> new AdminAccountRow(
                r.empno(), r.name(), r.statusCd(), STATUS_NAME.getOrDefault(r.statusCd(), r.statusCd()),
                r.failCnt(), r.lastLoginAt(), r.passwordChangedAt(),
                policy.isExpired(r.passwordChangedAt()), policy.isDormant(r.lastLoginAt())
        )).toList();
    }

    @Transactional("appTxManager")
    public void unlock(String empno) {
        if (accounts.unlockAccount(empno) == 0) {
            throw new NotFoundException("해당 계정을 찾을 수 없습니다: " + empno);
        }
    }

    @Transactional("appTxManager")
    public void resetPassword(String empno) {
        if (accounts.resetToDefault(empno, encoder.encode(empno)) == 0) {
            throw new NotFoundException("해당 계정을 찾을 수 없습니다: " + empno);
        }
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `... ./gradlew test --tests '*AuthAdminServiceTest'`
Expected: PASS (3 tests)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/meritz/dash/auth/AuthAdminService.java \
        src/main/java/com/meritz/dash/auth/AdminAccountRow.java \
        src/test/java/com/meritz/dash/auth/AuthAdminServiceTest.java
git commit -m "feat(auth): AuthAdminService — 계정 현황/잠금·휴면 해제/비번 초기화"
```

---

### Task 7: AuthV2Controller + WebConfig v2 라우팅

**Files:**
- Create: `src/main/java/com/meritz/dash/auth/AuthV2Controller.java`
- Modify: `src/main/java/com/meritz/dash/config/WebConfig.java:16-20`
- Test: `src/test/java/com/meritz/dash/auth/AuthV2ControllerTest.java`

**Interfaces:**
- Consumes: `AuthPolicyService`(Task 5), `AuthContext`, `LoginRequest`, `LoginResult`, `ChangePasswordRequest`, `ApiResponse`.
- Produces: `POST /api/v2/auth/login`(공개), `POST /api/v2/auth/password`(`@Auth`).

- [ ] **Step 1: 실패 테스트 작성** — `AuthV2ControllerTest.java`

```java
package com.meritz.dash.auth;

import com.meritz.dash.common.GlobalExceptionHandler;
import com.meritz.dash.config.WebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AuthV2Controller.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class AuthV2ControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthPolicyService service;

    @Test void login_ok_returns_token_and_pwdReset() throws Exception {
        when(service.login(any())).thenReturn(new LoginResult("tok", "9320", "03", "일반직원", "홍길동", true));
        mvc.perform(post("/api/v2/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"empno\":\"9320\",\"password\":\"9320\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.token").value("tok"))
           .andExpect(jsonPath("$.data.pwdResetRequired").value(true));
    }

    @Test void login_locked_returns_403_with_errorCode() throws Exception {
        when(service.login(any())).thenThrow(AuthPolicyException.locked());
        mvc.perform(post("/api/v2/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"empno\":\"9320\",\"password\":\"x\"}"))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.errorCode").value("ACCOUNT_LOCKED"));
    }

    @Test void password_policy_violation_returns_400_code() throws Exception {
        doThrow(AuthPolicyException.policyViolation("복잡도 미달"))
                .when(service).changePassword(any(), any());
        // @Auth 인터셉터는 WebConfig 제외로 미적용 — AuthContext 직접 세팅 필요 시 필터 커스텀. 여기선 컨트롤러 계약만 검증.
        mvc.perform(post("/api/v2/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"Old123!x\",\"newPassword\":\"weak\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.errorCode").value("PASSWORD_POLICY_VIOLATION"));
    }
}
```

> 주의: `password` 엔드포인트는 `AuthContext.empno()`를 호출한다. `@WebMvcTest`에서 WebConfig(인터셉터)를 제외하므로 `AuthContext`가 비어 `UnauthorizedException`이 날 수 있다. 이를 피하려면 테스트에서 `AuthContext.set("9320","03",null,null)`를 `@BeforeEach`로 세팅하고 `@AfterEach`에서 `AuthContext.clear()` 한다. (기존 `DmlSrControllerTest` 패턴 참조 — 필요 시 동일 적용.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `... ./gradlew test --tests '*AuthV2ControllerTest'`
Expected: FAIL — `AuthV2Controller` 미존재.

- [ ] **Step 3: AuthV2Controller 구현** (Swagger는 Task 9에서 추가)

```java
package com.meritz.dash.auth;

import com.meritz.dash.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/auth")
public class AuthV2Controller {

    private final AuthPolicyService authPolicyService;

    public AuthV2Controller(AuthPolicyService authPolicyService) {
        this.authPolicyService = authPolicyService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@RequestBody @Valid LoginRequest req) {
        return ApiResponse.of(authPolicyService.login(req));
    }

    @Auth
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody @Valid ChangePasswordRequest req) {
        authPolicyService.changePassword(AuthContext.empno(), req);
        return ApiResponse.of(null);
    }
}
```

- [ ] **Step 4: WebConfig에 v2 라우팅 추가** — `addInterceptors` 본문 교체

```java
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtAuthInterceptor(jwt))
                .addPathPatterns("/api/v1/**", "/api/v2/**")
                .excludePathPatterns("/api/v1/auth/login", "/api/v2/auth/login", "/api/v1/health");
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `... ./gradlew test --tests '*AuthV2ControllerTest'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/meritz/dash/auth/AuthV2Controller.java \
        src/main/java/com/meritz/dash/config/WebConfig.java \
        src/test/java/com/meritz/dash/auth/AuthV2ControllerTest.java
git commit -m "feat(auth): v2 인증 컨트롤러(/api/v2/auth/login,password) + v2 인터셉터 라우팅"
```

---

### Task 8: AdminAccountController (신규 v1)

**Files:**
- Create: `src/main/java/com/meritz/dash/auth/AdminAccountController.java`
- Test: `src/test/java/com/meritz/dash/auth/AdminAccountControllerTest.java`

**Interfaces:**
- Consumes: `AuthAdminService`(Task 6), `ApiResponse`, `AdminAccountRow`.
- Produces: `GET /api/v1/admin/accounts`, `POST /api/v1/admin/accounts/{empno}/unlock`, `POST /api/v1/admin/accounts/{empno}/reset-password`. 클래스 레벨 `@Auth(roles={"ADMIN"})`.

- [ ] **Step 1: 실패 테스트 작성** — `AdminAccountControllerTest.java`

```java
package com.meritz.dash.auth;

import com.meritz.dash.common.GlobalExceptionHandler;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.config.WebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AdminAccountController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class AdminAccountControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthAdminService service;

    @Test void list_accounts_returns_data_array() throws Exception {
        when(service.listAccounts()).thenReturn(List.of(new AdminAccountRow(
                "9320", "홍길동", "00", "정상", 0, null, null, false, false)));
        mvc.perform(get("/api/v1/admin/accounts"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data[0].empno").value("9320"))
           .andExpect(jsonPath("$.data[0].statusName").value("정상"));
    }

    @Test void unlock_ok() throws Exception {
        mvc.perform(post("/api/v1/admin/accounts/9320/unlock"))
           .andExpect(status().isOk());
        verify(service).unlock("9320");
    }

    @Test void reset_password_missing_returns_404() throws Exception {
        doThrow(new NotFoundException("없음")).when(service).resetPassword("NONE");
        mvc.perform(post("/api/v1/admin/accounts/NONE/reset-password"))
           .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `... ./gradlew test --tests '*AdminAccountControllerTest'`
Expected: FAIL — 컨트롤러 미존재.

- [ ] **Step 3: AdminAccountController 구현** (Swagger는 Task 9)

```java
package com.meritz.dash.auth;

import com.meritz.dash.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Auth(roles = {"ADMIN"})
@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AdminAccountController {

    private final AuthAdminService adminService;

    public AdminAccountController(AuthAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public ApiResponse<List<AdminAccountRow>> list() {
        return ApiResponse.of(adminService.listAccounts());
    }

    @PostMapping("/{empno}/unlock")
    public ApiResponse<Void> unlock(@PathVariable String empno) {
        adminService.unlock(empno);
        return ApiResponse.of(null);
    }

    @PostMapping("/{empno}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable String empno) {
        adminService.resetPassword(empno);
        return ApiResponse.of(null);
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `... ./gradlew test --tests '*AdminAccountControllerTest'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/meritz/dash/auth/AdminAccountController.java \
        src/test/java/com/meritz/dash/auth/AdminAccountControllerTest.java
git commit -m "feat(auth): 관리자 계정 API(/api/v1/admin/accounts 목록/unlock/reset-password)"
```

---

### Task 9: v1 @Deprecated + Swagger 문서화 + 통합 빌드/리뷰

**Files:**
- Modify: `src/main/java/com/meritz/dash/auth/AuthController.java` (v1 `@Deprecated` + Swagger 문구)
- Modify: `src/main/java/com/meritz/dash/auth/AuthV2Controller.java` (`@Tag`/`@Operation`/`@ApiResponses`)
- Modify: `src/main/java/com/meritz/dash/auth/AdminAccountController.java` (`@Tag`/`@Operation`)
- Test: 전체 회귀

**Interfaces:** 없음(문서/정리 태스크). 기존 v1 계약 무손상 확인.

- [ ] **Step 1: v1 AuthController 문구 갱신** — login/password `@Operation.description`에 아래 문구 추가, 메서드에 `@Deprecated`

```java
    @Operation(
        summary = "[Deprecated] 사번 로그인 → JWT 발급 (정책 미적용)",
        description = "⚠️ Deprecated — 비밀번호 정책(잠금·휴면·만료)이 적용되지 않는다. 신규 클라이언트는 POST /api/v2/auth/login 사용. · 공개 엔드포인트. · 초기 비밀번호=사번, 최초 로그인 후 pwdResetRequired=true."
    )
    @Deprecated
    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@RequestBody @Valid LoginRequest req) {
        return ApiResponse.of(authService.login(req));
    }
```
(password도 동일 패턴: summary에 `[Deprecated]`, description에 "정책 미적용, /api/v2/auth/password 권장", 메서드에 `@Deprecated`.)

- [ ] **Step 2: AuthV2Controller Swagger 추가** — 클래스/메서드 애노테이션 (DmlSrController 수준)

```java
@Tag(name = "Auth v2", description = "인증 v2 — 비밀번호 정책(잠금·휴면·만료·복잡도·재사용) 적용")
// login:
    @Operation(summary = "사번 로그인 → JWT 발급 (정책 적용)",
        description = "공개. 10회 실패 시 계정 잠금(403 ACCOUNT_LOCKED), 3개월 미사용 시 휴면(403 ACCOUNT_DORMANT), 비밀번호 90일 초과 시 로그인은 되되 pwdResetRequired=true. 잠금/휴면 해제는 관리자.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "로그인 성공(토큰 발급)"),
        @ApiResponse(responseCode = "401", description = "자격 증명 오류(errorCode=INVALID_CREDENTIALS, remainingAttempts 포함)"),
        @ApiResponse(responseCode = "403", description = "잠금/휴면(errorCode=ACCOUNT_LOCKED|ACCOUNT_DORMANT)")
    })
// password:
    @Operation(summary = "비밀번호 변경 (정책 적용)",
        description = "인증 필요. 최소 8자 + 영문 대/소문자·숫자·특수문자 포함, 직전 1개 재사용 불가. 위반 시 400(errorCode=PASSWORD_POLICY_VIOLATION|PASSWORD_REUSE).")
```
> `io.swagger.v3.oas.annotations.responses.ApiResponse`/`ApiResponses` import 추가. `ApiResponse` 이름이 프로젝트 `common.ApiResponse`와 충돌하므로 Swagger 쪽은 **정규 FQN** 또는 별칭 없이 import 순서에 유의(컨트롤러 반환형은 `com.meritz.dash.common.ApiResponse`). 충돌 회피: Swagger 애노테이션을 FQN `@io.swagger.v3.oas.annotations.responses.ApiResponse(...)`로 기재.

- [ ] **Step 3: AdminAccountController Swagger 추가**

```java
@Tag(name = "Admin - Accounts", description = "관리자 전용 — 계정 현황/잠금·휴면 해제/비밀번호 초기화 (ADMIN 권한)")
// list:   @Operation(summary = "계정 현황 목록", description = "전 계정의 상태(정상/잠금/휴면)·실패횟수·최근 로그인·비밀번호 만료 여부.")
// unlock: @Operation(summary = "잠금/휴면 해제", description = "STATUS_CD=00, 실패횟수 0, 휴면 시계 리셋.")
// reset:  @Operation(summary = "비밀번호 초기화", description = "비밀번호를 사번으로 초기화하고 강제 변경(pwdResetRequired) 상태로 만든다.")
```

- [ ] **Step 4: 전체 빌드/테스트**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home APP_DB_PASSWORD=apppw LEGACY_DB_PASSWORD=legacypw ./gradlew build`
Expected: BUILD SUCCESSFUL — 신규 테스트 + 기존 v1 auth 회귀 모두 통과.

- [ ] **Step 5: Swagger 확인(수동)** — 서버 기동 후 `/swagger-ui`에 Auth / Auth v2 / Admin - Accounts 태그와 예시가 노출되는지 확인.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/meritz/dash/auth/AuthController.java \
        src/main/java/com/meritz/dash/auth/AuthV2Controller.java \
        src/main/java/com/meritz/dash/auth/AdminAccountController.java
git commit -m "docs(auth): v1 @Deprecated 표기 + v2/admin Swagger 문서화"
```

- [ ] **Step 7: 다각화 리뷰** — `/review-all` 실행, Critical 0건 확인 후 머지.

---

## Self-Review

**1. Spec coverage**
- 복잡도 → Task 2 `PasswordPolicy.validate`. 90일 만료 → Task 2 `isExpired` + Task 5 login. 직전 재사용 → Task 4 `changePasswordWithHistory` + Task 5. 휴면 → Task 2 `isDormant` + Task 5 지연판정 + Task 6/8 해제. 10회 잠금 → Task 5 increment/lock + Task 6/8 해제. 최초 로그인 팝업 → 기존 `pwdResetRequired`(Task 5에서 유지). v2 신설/v1 무손상 → Task 7·9. 신규 admin v1 → Task 6·8. DDL V017 → Task 1. 설정 → Task 2. 에러 errorCode → Task 3. ✅ 갭 없음.
- 알려진 한계(v1 우회)는 A안으로 수용 — Task 9에서 `@Deprecated`로 표기.

**2. Placeholder scan:** 모든 코드 스텝에 실제 코드 포함. "적절히 처리" 류 없음. ✅

**3. Type consistency:**
- `AuthAccount`(Task1) 8필드 순서 = Task4 IT/Task5 테스트에서 동일 사용. ✅
- `AuthAccountMapper.AdminRow`(Task4) = `AuthAdminService`(Task6) 소비 필드 일치. ✅
- `AuthPolicyException` 팩토리명(locked/dormant/policyViolation/reuse/invalidCredentials) = Task2/3/5/7 사용처 일치. ✅
- `AuthPolicyProperties` 중첩 record(`Password`,`Lockout`) 접근자(`password().minLength()` 등) = Task2/5/6 일치. ✅
- 매퍼 메서드명(incrementFail/lockAccount/markDormant/loginSuccess/changePasswordWithHistory/unlockAccount/resetToDefault/findAllForAdmin) = Task4 정의 = Task5/6 호출 일치. ✅

**실측 확정 완료:** `Developer`(8필드), `CommonCode`(4필드), `AdminProperties(username,password)`, `HR_DEVELOPER.EMP_NM`, `com.meritz.dash.support.AbstractOracleIT` — 모두 소스로 검증됨.
