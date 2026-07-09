package com.meritz.dash.auth;

import com.meritz.dash.config.JwtProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwt = new JwtService(
        new JwtProperties("0123456789abcdef0123456789abcdef", 86400000L));

    @Test
    @DisplayName("generate→validate: claims 보존")
    void roundtrip() {
        String token = jwt.generate("9692", "01", "팀장", "김팀장", "2139", "P01", true);
        Claims c = jwt.validate(token);
        assertThat(c).isNotNull();
        assertThat(c.getSubject()).isEqualTo("9692");
        assertThat(c.get("role")).isEqualTo("01");
        assertThat(c.get("pwdReset")).isEqualTo(true);
    }

    @Test
    @DisplayName("generate→validate: deptCd claim 보존")
    void deptCd_claim_roundtrip() {
        String token = jwt.generate("5355", "01", "팀장", "김팀장", "2139", "P01", false);
        Claims c = jwt.validate(token);
        assertThat(c).isNotNull();
        assertThat(c.get("deptCd")).isEqualTo("2139");
        assertThat(c.get("partCd")).isEqualTo("P01");
    }

    @Test
    @DisplayName("generate→validate: admin deptCd=null 허용")
    void admin_deptCd_null() {
        String token = jwt.generate("admin", "ADMIN", "관리자", "관리자", null, null, false);
        Claims c = jwt.validate(token);
        assertThat(c).isNotNull();
        assertThat(c.get("deptCd")).isNull();
    }

    @Test
    @DisplayName("위조 토큰 → null")
    void tampered() {
        assertThat(jwt.validate("not.a.jwt")).isNull();
    }

    @Test
    @DisplayName("만료 토큰 → null")
    void expired() {
        JwtService shortLived = new JwtService(
            new JwtProperties("0123456789abcdef0123456789abcdef", -1000L)); // 이미 만료
        String token = shortLived.generate("9692","03","일반직원","홍길동","2139","P02",false);
        assertThat(shortLived.validate(token)).isNull();
    }
}
