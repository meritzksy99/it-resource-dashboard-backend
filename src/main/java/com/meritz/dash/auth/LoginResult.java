package com.meritz.dash.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 결과")
public record LoginResult(
        @Schema(description = "JWT 액세스 토큰 (Authorization: Bearer <token> 헤더로 사용)", example = "eyJhbGciOiJIUzI1NiJ9...") String token,
        @Schema(description = "사번", example = "7451") String empno,
        @Schema(description = "역할코드: 01 팀장 · 02 업무리더 · 03 일반직원 · ADMIN 관리자", example = "03") String role,
        @Schema(description = "역할명", example = "일반직원") String roleName,
        @Schema(description = "사용자명", example = "홍길동") String name,
        @Schema(description = "true이면 초기 비밀번호(사번) 상태 → 비밀번호 변경 필요", example = "false") boolean pwdResetRequired) {}
