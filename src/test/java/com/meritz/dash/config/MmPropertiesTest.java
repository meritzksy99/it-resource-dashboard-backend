package com.meritz.dash.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class MmPropertiesTest {

    @Test
    @DisplayName("app.mm 바인딩: 166h / 1.0 / 0.6")
    void binds() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.mm.hours-per-month", "166")
            .withProperty("app.mm.overtime-threshold", "1.0")
            .withProperty("app.mm.top-min-mm", "0.6");
        MmProperties p = Binder.get(env).bind("app.mm", MmProperties.class).get();
        assertThat(p.hoursPerMonth()).isEqualTo(166);
        assertThat(p.overtimeThreshold()).isEqualTo(1.0);
        assertThat(p.topMinMm()).isEqualTo(0.6);
    }
}
