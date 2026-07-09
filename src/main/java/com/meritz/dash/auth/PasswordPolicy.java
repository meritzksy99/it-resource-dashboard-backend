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
