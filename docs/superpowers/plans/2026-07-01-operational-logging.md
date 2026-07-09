# Operational Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 진단용 로깅 추가 — 민감정보 마스킹, 요청/응답 로깅 필터, 포괄 500 예외 핸들러, 롤링 파일 logback 설정.

**Architecture:** `LogMasker` 유틸이 password 계열 필드를 마스킹하고, `RequestLoggingFilter`가 `ContentCachingRequestWrapper`/`ContentCachingResponseWrapper`로 요청·응답 body를 캡처해 INFO/DEBUG 레벨로 기록한다. `GlobalExceptionHandler`에 warn/error 로그와 포괄 500 핸들러를 추가하고, `logback-spring.xml`로 콘솔+롤링파일 이중 출력을 구성한다.

**Tech Stack:** Java 21, Spring Boot 3.3.5, SLF4J/Logback (Spring Boot 기본 포함), JUnit 5, MockMvc (@WebMvcTest)

## Global Constraints

- Java 21, Spring Boot 3.3.5 (기존 build.gradle 버전 고정)
- `gradle.properties`, `.gitignore` 수정 금지
- 새 외부 의존성 추가 금지 (Spring Boot starter-web에 포함된 것만 사용)
- 모든 파일은 `feat/logging` 브랜치에서 작업
- IT timeout `500000ms`
- 빌드 명령: `./gradlew test` (DOCKER_HOST, TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE 환경변수 필요)
- 커밋 메시지: `feat: 운영 로깅(요청 마스킹 로깅 + 예외/500 로깅 + 롤링 파일 logback)`

---

### Task 1: LogMasker 유틸 + 단위 테스트 (보안 마스킹)

**Files:**
- Create: `src/main/java/com/meritz/dash/common/LogMasker.java`
- Create: `src/test/java/com/meritz/dash/common/LogMaskerTest.java`

**Interfaces:**
- Produces: `LogMasker.maskJson(String body): String` — null/빈문자 안전, password/oldPassword/newPassword/passwordHash 필드 값 → `"***"`

- [ ] **Step 1: 실패 테스트 작성**

`src/test/java/com/meritz/dash/common/LogMaskerTest.java` 생성:

```java
package com.meritz.dash.common;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LogMaskerTest {

    @Test
    void password_field_is_masked() {
        String input = "{\"empno\":\"E1\",\"password\":\"secret\"}";
        String result = LogMasker.maskJson(input);
        assertThat(result).contains("\"empno\":\"E1\"");
        assertThat(result).contains("\"password\":\"***\"");
        assertThat(result).doesNotContain("secret");
    }

    @Test
    void multiple_password_fields_masked_simultaneously() {
        String input = "{\"oldPassword\":\"old\",\"newPassword\":\"new123\",\"passwordHash\":\"abc\"}";
        String result = LogMasker.maskJson(input);
        assertThat(result).contains("\"oldPassword\":\"***\"");
        assertThat(result).contains("\"newPassword\":\"***\"");
        assertThat(result).contains("\"passwordHash\":\"***\"");
        assertThat(result).doesNotContain("old");
        assertThat(result).doesNotContain("new123");
        assertThat(result).doesNotContain("abc");
    }

    @Test
    void non_password_fields_are_preserved() {
        String input = "{\"empno\":\"E999\",\"empNm\":\"홍길동\",\"password\":\"pw\"}";
        String result = LogMasker.maskJson(input);
        assertThat(result).contains("\"empno\":\"E999\"");
        assertThat(result).contains("\"empNm\":\"홍길동\"");
    }

    @Test
    void null_input_returns_empty_string() {
        assertThat(LogMasker.maskJson(null)).isEmpty();
    }

    @Test
    void blank_input_returns_blank() {
        assertThat(LogMasker.maskJson("")).isEmpty();
    }

    @Test
    void non_json_string_passes_through_unchanged() {
        String input = "hello world";
        assertThat(LogMasker.maskJson(input)).isEqualTo("hello world");
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

```bash
cd /Users/user/Desktop/it_web && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && \
  ./gradlew test --tests "com.meritz.dash.common.LogMaskerTest" 2>&1 | tail -20
```
Expected: COMPILATION ERROR (LogMasker 클래스 없음)

- [ ] **Step 3: LogMasker 구현**

`src/main/java/com/meritz/dash/common/LogMasker.java` 생성:

```java
package com.meritz.dash.common;

import java.util.regex.Pattern;

/**
 * 로그 출력 전 민감 필드를 마스킹하는 유틸리티.
 * JSON body의 password 계열 필드 값을 "***"로 치환한다.
 */
public final class LogMasker {

    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "\"(password|oldPassword|newPassword|passwordHash)\"\\s*:\\s*\"[^\"]*\"",
            Pattern.CASE_SENSITIVE
    );

    private LogMasker() {}

    /**
     * JSON 문자열에서 password 계열 필드의 값을 "***"로 마스킹한다.
     * null 또는 빈 문자열이면 빈 문자열을 반환한다.
     */
    public static String maskJson(String body) {
        if (body == null || body.isBlank()) {
            return body == null ? "" : body;
        }
        return PASSWORD_PATTERN.matcher(body).replaceAll("\"$1\":\"***\"");
    }
}
```

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
cd /Users/user/Desktop/it_web && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && \
  ./gradlew test --tests "com.meritz.dash.common.LogMaskerTest" 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL, 6 tests passed

- [ ] **Step 5: 커밋**

```bash
cd /Users/user/Desktop/it_web && \
  git add src/main/java/com/meritz/dash/common/LogMasker.java \
          src/test/java/com/meritz/dash/common/LogMaskerTest.java && \
  git commit -m "feat: LogMasker - password 계열 필드 마스킹 유틸 + 단위 테스트"
```

---

### Task 2: GlobalExceptionHandler 로깅 추가 + 포괄 500 핸들러

**Files:**
- Modify: `src/main/java/com/meritz/dash/common/GlobalExceptionHandler.java`
- Create: `src/test/java/com/meritz/dash/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: 없음 (독립 태스크)
- Produces: `handleUnexpected(Exception ex)` — HTTP 500, `ProblemDetail.forStatusAndDetail(INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다")`, `log.error("Unhandled exception", ex)` (스택 포함)

- [ ] **Step 1: 포괄 500 핸들러 테스트 작성**

`src/test/java/com/meritz/dash/common/GlobalExceptionHandlerTest.java` 생성:

```java
package com.meritz.dash.common;

import com.meritz.dash.config.WebConfig;
import com.meritz.dash.health.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HealthController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mvc;
    @MockBean com.meritz.dash.health.HealthService healthService;

    @Test
    void unhandled_exception_returns_500_with_generic_message() throws Exception {
        when(healthService.check()).thenThrow(new RuntimeException("DB 연결 실패 — 내부 에러"));
        mvc.perform(get("/api/v1/health"))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.detail").value("서버 내부 오류가 발생했습니다"))
           // 내부 에러 메시지가 응답에 노출되지 않아야 한다
           .andExpect(result -> {
               String body = result.getResponse().getContentAsString();
               org.assertj.core.api.Assertions.assertThat(body).doesNotContain("DB 연결 실패");
           });
    }
}
```

> **주의:** HealthController가 HealthService에 의존하는지 확인하고, 아니면 다른 서비스 빈을 @MockBean으로 등록하거나 간단한 @Controller stub을 대신 사용한다. 아래 Step 2에서 실제 HealthController 구조를 확인 후 조정한다.

- [ ] **Step 2: HealthController 구조 확인 후 테스트 조정**

```bash
cat /Users/user/Desktop/it_web/src/main/java/com/meritz/dash/health/HealthController.java
```

HealthController가 서비스 의존성 없이 단독으로 동작한다면, `@MockBean HealthService`는 제거하고 아래처럼 별도 stub 컨트롤러를 inner class로 추가한다:

```java
// HealthController가 서비스 없이 동작 시: @WebMvcTest(controllers = HealthController.class) 대신
// 테스트 전용 컨트롤러 사용
// GlobalExceptionHandlerTest.java를 아래와 같이 교체:

package com.meritz.dash.common;

import com.meritz.dash.config.WebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.BoomController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebConfig.class))
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @RestController
    static class BoomController {
        @GetMapping("/test/boom")
        public String boom() {
            throw new RuntimeException("DB 연결 실패 — 내부 에러");
        }
    }

    @Autowired MockMvc mvc;

    @Test
    void unhandled_exception_returns_500_with_generic_message() throws Exception {
        mvc.perform(get("/test/boom"))
           .andExpect(status().isInternalServerError())
           .andExpect(jsonPath("$.detail").value("서버 내부 오류가 발생했습니다"))
           .andExpect(result -> {
               String body = result.getResponse().getContentAsString();
               assertThat(body).doesNotContain("DB 연결 실패");
               assertThat(body).doesNotContain("내부 에러");
           });
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

```bash
cd /Users/user/Desktop/it_web && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && \
  ./gradlew test --tests "com.meritz.dash.common.GlobalExceptionHandlerTest" 2>&1 | tail -20
```
Expected: FAIL (handleUnexpected 없으므로 500이 아니라 Spring 기본 처리)

- [ ] **Step 4: GlobalExceptionHandler에 로깅 + 포괄 500 핸들러 추가**

`src/main/java/com/meritz/dash/common/GlobalExceptionHandler.java` 전체 교체:

```java
package com.meritz.dash.common;

import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.auth.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        log.warn("400 IllegalArgumentException: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail handleUnauthorized(UnauthorizedException ex) {
        log.warn("401 UnauthorizedException: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException ex) {
        log.warn("403 ForbiddenException: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("검증 실패");
        log.warn("400 MethodArgumentNotValidException: {}", detail);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다");
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
cd /Users/user/Desktop/it_web && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && \
  ./gradlew test --tests "com.meritz.dash.common.GlobalExceptionHandlerTest" 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL, 1 test passed

- [ ] **Step 6: 기존 핸들러 테스트가 여전히 통과하는지 확인**

```bash
cd /Users/user/Desktop/it_web && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && \
  ./gradlew test --tests "com.meritz.dash.auth.AuthControllerTest" \
                 --tests "com.meritz.dash.health.HealthControllerTest" 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
cd /Users/user/Desktop/it_web && \
  git add src/main/java/com/meritz/dash/common/GlobalExceptionHandler.java \
          src/test/java/com/meritz/dash/common/GlobalExceptionHandlerTest.java && \
  git commit -m "feat: GlobalExceptionHandler - warn/error 로깅 + 포괄 500 핸들러"
```

---

### Task 3: RequestLoggingFilter 구현

**Files:**
- Create: `src/main/java/com/meritz/dash/common/RequestLoggingFilter.java`

**Interfaces:**
- Consumes: `LogMasker.maskJson(String): String` (Task 1에서 정의)
- Produces: `RequestLoggingFilter extends OncePerRequestFilter` — `@Component`, `@Order(Ordered.HIGHEST_PRECEDENCE + 10)`, `/api/**` 경로 INFO 로그, `/swagger`, `/v3/api-docs` 제외, `copyBodyToResponse()` 보장

> **사전 주의:** `ContentCachingResponseWrapper`는 체인 완료 후 `copyBodyToResponse()`를 반드시 호출해야 한다. 빠뜨리면 응답 body가 클라이언트에 전달되지 않아 **기존 통합테스트가 전부 깨진다**.

- [ ] **Step 1: RequestLoggingFilter 구현**

`src/main/java/com/meritz/dash/common/RequestLoggingFilter.java` 생성:

```java
package com.meritz.dash.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 운영 진단용 HTTP 요청/응답 로깅 필터.
 * /api/** 경로만 로깅하며 /swagger, /v3/api-docs 경로는 제외한다.
 * Authorization 헤더 값은 절대 로깅하지 않는다.
 * 필터 오류가 정상 응답을 방해하지 않도록 방어 처리한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final int RESPONSE_TRUNCATE_LENGTH = 500;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.startsWith("/swagger")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        ContentCachingRequestWrapper wrappedReq = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedRes = new ContentCachingResponseWrapper(response);

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(wrappedReq, wrappedRes);
        } finally {
            try {
                logRequest(wrappedReq, wrappedRes, start);
            } catch (Exception loggingEx) {
                log.debug("RequestLoggingFilter: 로깅 중 오류 발생(무시)", loggingEx);
            }
            // 반드시 응답 body를 실제 response에 복사 — 누락 시 클라이언트에 body 전달 안 됨
            wrappedRes.copyBodyToResponse();
        }
    }

    private void logRequest(ContentCachingRequestWrapper req,
                            ContentCachingResponseWrapper res,
                            long start) {
        String method = req.getMethod();
        String uri = req.getRequestURI();
        String query = req.getQueryString();
        String queryPart = (query != null && !query.isBlank()) ? "?" + query : "";
        int status = res.getStatus();
        long elapsed = System.currentTimeMillis() - start;

        log.info("HTTP {} {}{} -> {} ({}ms)", method, uri, queryPart, status, elapsed);

        if (log.isDebugEnabled()) {
            byte[] reqBody = req.getContentAsByteArray();
            if (reqBody.length > 0) {
                String bodyStr = new String(reqBody, StandardCharsets.UTF_8);
                String masked = LogMasker.maskJson(bodyStr);
                log.debug("  req body: {}", masked);
            }

            byte[] resBody = res.getContentAsByteArray();
            if (resBody.length > 0) {
                String resStr = new String(resBody, StandardCharsets.UTF_8);
                if (resStr.length() > RESPONSE_TRUNCATE_LENGTH) {
                    resStr = resStr.substring(0, RESPONSE_TRUNCATE_LENGTH) + "...(truncated)";
                }
                log.debug("  res body: {}", resStr);
            }
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인 (테스트 실행 없이)**

```bash
cd /Users/user/Desktop/it_web && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && \
  ./gradlew compileJava 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 기존 WebMvcTest들이 필터 추가 후에도 통과하는지 확인**

```bash
cd /Users/user/Desktop/it_web && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && \
  ./gradlew test --tests "com.meritz.dash.auth.AuthControllerTest" \
                 --tests "com.meritz.dash.health.HealthControllerTest" \
                 --tests "com.meritz.dash.common.GlobalExceptionHandlerTest" \
                 --tests "com.meritz.dash.developer.DeveloperControllerTest" \
                 --tests "com.meritz.dash.code.CodeControllerTest" 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL

> **@WebMvcTest 참고:** `@WebMvcTest`는 기본적으로 `@Component` 필터를 로드하지 않으므로 `RequestLoggingFilter`가 자동 포함되지 않는다. 하지만 `ContentCachingRequestWrapper` 없이도 올바로 동작해야 한다.

- [ ] **Step 4: 커밋**

```bash
cd /Users/user/Desktop/it_web && \
  git add src/main/java/com/meritz/dash/common/RequestLoggingFilter.java && \
  git commit -m "feat: RequestLoggingFilter - /api/** INFO/DEBUG 로깅, password 마스킹, copyBodyToResponse 보장"
```

---

### Task 4: logback-spring.xml — 콘솔 + 롤링 파일 appender

**Files:**
- Create: `src/main/resources/logback-spring.xml`

**Interfaces:**
- Consumes: Spring Boot logback 설정 (`springProperty` 지원)
- Produces: 롤링 파일 `logs/it-dash.log`, root=INFO, `com.meritz.dash`=DEBUG, 환경변수 `LOG_LEVEL`/`APP_LOG_LEVEL` 오버라이드 지원

- [ ] **Step 1: logback-spring.xml 생성**

`src/main/resources/logback-spring.xml` 생성:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <!-- 환경변수 또는 기본값으로 로그 레벨 조정 -->
    <springProperty scope="context" name="LOG_LEVEL"     source="LOG_LEVEL"     defaultValue="INFO"/>
    <springProperty scope="context" name="APP_LOG_LEVEL" source="APP_LOG_LEVEL" defaultValue="DEBUG"/>

    <property name="LOG_PATTERN"
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"/>

    <!-- ── 콘솔 Appender ──────────────────────────────── -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
        </encoder>
    </appender>

    <!-- ── 롤링 파일 Appender ─────────────────────────── -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/it-dash.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <!-- 일자 + 100MB 롤링 -->
            <fileNamePattern>logs/it-dash.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <!-- 14일 보관 -->
            <maxHistory>14</maxHistory>
            <totalSizeCap>2GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
        </encoder>
    </appender>

    <!-- ── 애플리케이션 패키지: DEBUG ─────────────────── -->
    <logger name="com.meritz.dash" level="${APP_LOG_LEVEL}" additivity="false">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </logger>

    <!-- ── Root: INFO ────────────────────────────────── -->
    <root level="${LOG_LEVEL}">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>

</configuration>
```

- [ ] **Step 2: 앱 시작 시 logback 설정 로딩 확인**

```bash
cd /Users/user/Desktop/it_web && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && \
  ./gradlew compileJava 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
cd /Users/user/Desktop/it_web && \
  git add src/main/resources/logback-spring.xml && \
  git commit -m "feat: logback-spring.xml - 콘솔+롤링 파일 appender, 14일 보관"
```

---

### Task 5: 전체 테스트 통과 확인 + 최종 커밋

**Files:**
- Modify: (없음 — 회귀 확인 후 태그 커밋만)

**Interfaces:**
- Consumes: Task 1–4의 모든 결과물
- Produces: `./gradlew test` 전체 GREEN

- [ ] **Step 1: 전체 단위 테스트 (IT 제외) 빠른 실행**

```bash
cd /Users/user/Desktop/it_web && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && \
  ./gradlew test --tests "com.meritz.dash.common.*" \
                 --tests "com.meritz.dash.auth.AuthControllerTest" \
                 --tests "com.meritz.dash.auth.JwtServiceTest" \
                 --tests "com.meritz.dash.health.HealthControllerTest" \
                 --tests "com.meritz.dash.developer.DeveloperControllerTest" \
                 --tests "com.meritz.dash.devvolume.DevVolumeControllerTest" \
                 --tests "com.meritz.dash.resource.ResourceControllerTest" \
                 --tests "com.meritz.dash.srproject.SrProjectControllerTest" \
                 --tests "com.meritz.dash.code.CodeControllerTest" \
                 --tests "com.meritz.dash.aggregation.AggregationRequestTest" \
                 --tests "com.meritz.dash.config.JwtPropertiesTest" \
                 --tests "com.meritz.dash.config.MmPropertiesTest" 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL, 모든 테스트 PASS

- [ ] **Step 2: 전체 테스트 (IT 포함) 실행 — Docker 필요**

```bash
cd /Users/user/Desktop/it_web && \
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && \
  export DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock && \
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock && \
  ./gradlew test 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL (모든 IT 포함 전체 통과)

> **실패 시 확인 포인트:**
> - `copyBodyToResponse()` 누락 여부 (RequestLoggingFilter.java finally 블록)
> - `@WebMvcTest`에 `RequestLoggingFilter`가 의도치 않게 포함된 경우 — `@ComponentScan.Filter`로 제외 필요
> - GlobalExceptionHandler의 `handleUnexpected(Exception.class)` 가 기존 핸들러보다 우선순위를 가로채는지 확인

- [ ] **Step 3: 최종 통합 커밋 (변경 파일 전체 정리)**

```bash
cd /Users/user/Desktop/it_web && \
  git status
```
이미 각 Task에서 커밋했으므로 untracked/unstaged가 없어야 한다. 남은 파일이 있으면 add 후:

```bash
git commit -m "feat: 운영 로깅(요청 마스킹 로깅 + 예외/500 로깅 + 롤링 파일 logback)"
```

- [ ] **Step 4: 보고서 작성**

`/Users/user/Desktop/it_web/.superpowers/sdd/logging-report.md` 생성:

```markdown
# 운영 로깅 구현 보고서

## 구현 파일
| 파일 | 역할 |
|------|------|
| `src/main/java/com/meritz/dash/common/LogMasker.java` | password 계열 필드 마스킹 유틸 |
| `src/main/java/com/meritz/dash/common/RequestLoggingFilter.java` | /api/** 요청/응답 INFO/DEBUG 로깅 필터 |
| `src/main/java/com/meritz/dash/common/GlobalExceptionHandler.java` | warn/error 로깅 + 포괄 500 핸들러 추가 |
| `src/main/resources/logback-spring.xml` | 콘솔+롤링파일 appender, 14일 보관 |
| `src/test/java/com/meritz/dash/common/LogMaskerTest.java` | 마스킹 단위 테스트 (6건) |
| `src/test/java/com/meritz/dash/common/GlobalExceptionHandlerTest.java` | 500 포괄 핸들러 테스트 (1건) |

## 마스킹 테스트 증거
- `password`, `oldPassword`, `newPassword`, `passwordHash` 필드 값 → `"***"` 치환
- `empno`, `empNm` 등 비밀번호 외 필드는 그대로 유지
- null/빈문자 입력 안전 처리

## 500 핸들러 테스트 증거
- `RuntimeException("DB 연결 실패 — 내부 에러")` → HTTP 500
- 응답 body: `{"detail": "서버 내부 오류가 발생했습니다"}` (내부 에러 메시지 미노출)

## 전체 테스트 결과
(실행 후 여기에 ./gradlew test 결과 요약 기재)

## 커밋 목록
(git log --oneline 결과 기재)

## 우려사항
- IT 테스트는 Docker(Colima) 기동 필요. CI 환경에서 DOCKER_HOST 주입 확인 필요.
- `logs/` 디렉터리는 .gitignore에 추가 권장 (현재 gradle.properties/.gitignore 수정 금지 제약으로 미처리).
```
