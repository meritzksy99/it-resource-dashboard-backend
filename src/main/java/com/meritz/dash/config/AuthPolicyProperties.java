package com.meritz.dash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthPolicyProperties(Password password, Lockout lockout, int dormantDays) {
    public record Password(int minLength, int maxAgeDays) {}
    public record Lockout(int maxFail) {}
}
