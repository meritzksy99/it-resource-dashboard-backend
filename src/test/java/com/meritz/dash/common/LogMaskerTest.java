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
        String input = "{\"oldPassword\":\"pw1\",\"newPassword\":\"pw2\",\"passwordHash\":\"pw3\"}";
        String result = LogMasker.maskJson(input);
        assertThat(result).contains("\"oldPassword\":\"***\"");
        assertThat(result).contains("\"newPassword\":\"***\"");
        assertThat(result).contains("\"passwordHash\":\"***\"");
        assertThat(result).doesNotContain("pw1");
        assertThat(result).doesNotContain("pw2");
        assertThat(result).doesNotContain("pw3");
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
