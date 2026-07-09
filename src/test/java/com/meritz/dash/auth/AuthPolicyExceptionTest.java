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
    @Test void password_reset_required_is_403_with_code() {
        AuthPolicyException ex = AuthPolicyException.passwordResetRequired();
        assertThat(ex.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.errorCode()).isEqualTo("PASSWORD_RESET_REQUIRED");
        assertThat(ex.getMessage()).isEqualTo("비밀번호를 먼저 변경해야 합니다");
    }
    @Test void reuse_is_400() {
        assertThat(AuthPolicyException.reuse().httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(AuthPolicyException.reuse().errorCode()).isEqualTo("PASSWORD_REUSE");
    }
}
