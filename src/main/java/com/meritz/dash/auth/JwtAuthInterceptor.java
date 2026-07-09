package com.meritz.dash.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtService jwt;
    public JwtAuthInterceptor(JwtService jwt) { this.jwt = jwt; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;

        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new UnauthorizedException("인증 토큰이 필요합니다");
        }
        Claims claims = jwt.validate(header.substring(7).trim());
        if (claims == null) throw new UnauthorizedException("토큰이 유효하지 않습니다");

        String empno = claims.getSubject();
        String role = (String) claims.get("role");
        String deptCd = (String) claims.get("deptCd");
        String partCd = (String) claims.get("partCd");

        Auth auth = hm.getMethodAnnotation(Auth.class);
        if (auth == null) auth = hm.getBeanType().getAnnotation(Auth.class);
        if (auth != null && auth.roles().length > 0
                && Arrays.stream(auth.roles()).noneMatch(r -> r.equals(role))) {
            throw new ForbiddenException("권한이 없습니다");
        }

        AuthContext.set(empno, role, deptCd, partCd);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
