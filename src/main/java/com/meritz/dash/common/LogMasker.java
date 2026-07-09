package com.meritz.dash.common;

import java.util.regex.Pattern;

/**
 * 로그 출력 전 민감 필드를 마스킹하는 유틸리티.
 * JSON body의 password 계열 필드 값을 "***"로 치환한다.
 */
public final class LogMasker {

    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "\"(password|oldPassword|newPassword|passwordHash)\"\\s*:\\s*\"[^\"]*\""
    );

    private LogMasker() {}

    /**
     * JSON 문자열에서 password 계열 필드의 값을 "***"로 마스킹한다.
     * null 또는 빈 문자열이면 빈 문자열을 반환한다.
     */
    public static String maskJson(String body) {
        if (body == null || body.isBlank()) {
            return body == null ? "" : body;
        }
        return PASSWORD_PATTERN.matcher(body).replaceAll("\"$1\":\"***\"");
    }
}
