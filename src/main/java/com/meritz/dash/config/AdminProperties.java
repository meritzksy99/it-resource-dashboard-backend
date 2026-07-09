package com.meritz.dash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 관리자 계정 설정.
 * <p>
 * 기본값(admin/admin)은 <b>개발 전용</b>입니다.
 * 운영 환경에서는 반드시 환경 변수 ADMIN_USERNAME / ADMIN_PASSWORD 로 교체하십시오.
 */
@ConfigurationProperties("app.admin")
public record AdminProperties(String username, String password) {}
