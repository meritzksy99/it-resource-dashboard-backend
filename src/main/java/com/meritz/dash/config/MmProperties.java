package com.meritz.dash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.mm")
public record MmProperties(int hoursPerMonth, double overtimeThreshold, double topMinMm) {}
