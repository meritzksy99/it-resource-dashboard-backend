package com.meritz.dash.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank @Schema(description = "사번 (관리자: admin)", example = "admin") String empno,
    @NotBlank @Schema(description = "비밀번호 (초기값 = 사번)", example = "admin") String password) {}
