package com.meritz.dash.auth;

import com.meritz.dash.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(JwtProperties props) {
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.expirationMs = props.expiration();
    }

    public String generate(String empno, String role, String roleName,
                           String name, String deptCd, String partCd, boolean pwdReset) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(empno)
                .claim("role", role)
                .claim("roleName", roleName)
                .claim("name", name)
                .claim("deptCd", deptCd)
                .claim("partCd", partCd)
                .claim("pwdReset", pwdReset)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMs))
                .signWith(key)
                .compact();
    }

    public Claims validate(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
