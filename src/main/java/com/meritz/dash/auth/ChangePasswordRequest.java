package com.meritz.dash.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
    @NotBlank @Schema(description = "현재 비밀번호", example = "admin") String oldPassword,
    @NotBlank @Schema(description = "새 비밀번호 (8자 이상, 사번과 달라야 함)", example = "newpass123") String newPassword) {}
