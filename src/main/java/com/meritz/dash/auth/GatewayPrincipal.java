package com.meritz.dash.auth;

import java.util.Set;

/** 게이트웨이 토큰 검증 결과 — 사번(preferred_username)과 role: 접두를 제거한 게이트웨이 롤 집합. */
public record GatewayPrincipal(String empno, Set<String> gatewayRoles) {}
