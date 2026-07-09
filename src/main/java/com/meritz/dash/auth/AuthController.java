package com.meritz.dash.auth;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "인증 — 로그인·비밀번호 변경·현재 사용자")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(
        summary = "[Deprecated] 사번 로그인 → JWT 발급 (정책 미적용)",
        description = "⚠️ Deprecated — 비밀번호 정책(잠금·휴면·만료)이 적용되지 않는다. 신규 클라이언트는 POST /api/v2/auth/login 사용. · 공개 엔드포인트. · 초기 비밀번호=사번, 최초 로그인 후 pwdResetRequired=true."
    )
    @Deprecated
    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@RequestBody @Valid LoginRequest req) {
        return ApiResponse.of(authService.login(req));
    }

    @Operation(
        summary = "[Deprecated] 비밀번호 변경 (정책 미적용)",
        description = "⚠️ Deprecated — 비밀번호 정책(복잡도·재사용)이 적용되지 않는다. 신규 클라이언트는 POST /api/v2/auth/password 권장. · 인증 필요. 본인 비밀번호 변경. · 첫 로그인(pwdResetRequired=true) 시 강제 변경 용도. · 새 비밀번호 8자 이상, 사번과 달라야 한다."
    )
    @Deprecated
    @Auth
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody @Valid ChangePasswordRequest req) {
        authService.changePassword(AuthContext.empno(), req);
        return ApiResponse.of(null);
    }

    @Operation(
        summary = "현재 사용자 정보",
        description = "인증 필요. 토큰 기반 현재 사용자 정보(사번·이름·역할·비번초기화여부) 조회."
    )
    @Auth
    @GetMapping("/me")
    public ApiResponse<MeResult> me() {
        return ApiResponse.of(authService.me(AuthContext.empno()));
    }
}
