package com.meritz.dash.auth;

import java.time.LocalDateTime;

public record AuthAccount(String empno, String passwordHash, String pwdResetYn, Integer failCnt,
                          String statusCd, LocalDateTime passwordChangedAt,
                          String prevPasswordHash, LocalDateTime lastLoginAt) {}
