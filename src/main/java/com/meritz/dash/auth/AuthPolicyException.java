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
