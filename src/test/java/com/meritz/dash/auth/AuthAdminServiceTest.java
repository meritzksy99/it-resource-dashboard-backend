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

    @Test void reset_password_missing_account_throws_404() {
        when(encoder.encode("NONE")).thenReturn("$def");
        when(accounts.resetToDefault("NONE", "$def")).thenReturn(0);
        assertThatThrownBy(() -> service.resetPassword("NONE")).isInstanceOf(NotFoundException.class);
    }

    @Test void reset_password_encodes_empno_as_default() {
        when(accounts.resetToDefault(eq("9320"), anyString())).thenReturn(1);
        when(encoder.encode("9320")).thenReturn("$def");
        service.resetPassword("9320");
        verify(accounts).resetToDefault("9320", "$def");
    }
}
