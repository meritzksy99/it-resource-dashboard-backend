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
