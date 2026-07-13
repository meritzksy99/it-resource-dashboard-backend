package com.meritz.dash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties("app.gateway")
public record GatewayAuthProperties(boolean enabled, String tokenHeader, String jwksUrl,
                                    String issuer, String audience, Set<String> allowedRoles) {}
