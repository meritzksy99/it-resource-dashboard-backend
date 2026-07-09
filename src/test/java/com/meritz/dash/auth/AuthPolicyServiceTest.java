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

    /** AuthPolicyService.DUMMY_HASH 와 동일해야 함 — 계정 열거 방지용 더미 BCrypt */
    static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

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

    @Test void nonexistent_account_does_dummy_bcrypt_and_uniform_remaining() {
        when(accounts.findByEmpno("0000")).thenReturn(null);
        assertThatThrownBy(() -> service.login(new LoginRequest("0000", "x")))
                .isInstanceOf(AuthPolicyException.class)
                .satisfies(e -> {
                    AuthPolicyException ex = (AuthPolicyException) e;
                    assertThat(ex.errorCode()).isEqualTo("INVALID_CREDENTIALS");
                    // 실제 계정의 첫 실패(max-1 = 9)와 동일 값 — 계정 존재 여부 구분 불가
                    assertThat(ex.properties().get("remainingAttempts"))
                            .isEqualTo(props.lockout().maxFail() - 1);
                });
        // 더미 BCrypt 수행으로 타이밍 균일화
        verify(encoder).matches(eq("x"), eq(DUMMY_HASH));
        verify(accounts, never()).incrementFail(any());
    }

    @Test void password_ok_but_no_hr_row_returns_uniform_remaining() {
        when(accounts.findByEmpno("7777")).thenReturn(
                new AuthAccount("7777", "$hash", "N", 0, "00", LocalDateTime.now(), null, LocalDateTime.now()));
        when(encoder.matches("pw", "$hash")).thenReturn(true);
        when(developers.findByEmpno("7777")).thenReturn(null);
        assertThatThrownBy(() -> service.login(new LoginRequest("7777", "pw")))
                .isInstanceOf(AuthPolicyException.class)
                .satisfies(e -> assertThat(((AuthPolicyException) e).properties().get("remainingAttempts"))
                        .isEqualTo(props.lockout().maxFail() - 1));
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
