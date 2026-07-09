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
