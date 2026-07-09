package com.meritz.dash.auth;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth v2", description = "인증 v2 — 비밀번호 정책(잠금·휴면·만료·복잡도·재사용) 적용")
@RestController
@RequestMapping("/api/v2/auth")
public class AuthV2Controller {

    private final AuthPolicyService authPolicyService;

    public AuthV2Controller(AuthPolicyService authPolicyService) {
        this.authPolicyService = authPolicyService;
    }

    @Operation(
        summary = "사번 로그인 → JWT 발급 (정책 적용)",
        description = """
                **공개(인증 불필요).** 사번+비밀번호로 로그인하고 JWT를 발급한다.

                **정책** — 10회 연속 실패 시 계정 잠금(403 ACCOUNT_LOCKED), 3개월 미사용 시 휴면(403 ACCOUNT_DORMANT), \
                비밀번호 90일 초과 시 로그인은 허용되되 응답에 `pwdResetRequired=true` 로 표시(클라이언트는 비밀번호 변경 화면으로 유도). \
                잠금/휴면 해제는 관리자(`/api/v1/admin/accounts/{empno}/unlock`)만 가능.
                """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공(토큰 발급). pwdResetRequired=true 면 비밀번호 만료 상태."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "자격 증명 오류(errorCode=INVALID_CREDENTIALS). remainingAttempts(잠금까지 남은 횟수) 포함."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "계정 잠금/휴면(errorCode=ACCOUNT_LOCKED|ACCOUNT_DORMANT). 해제는 관리자 전용 API 필요.")
    })
    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@RequestBody @Valid LoginRequest req) {
        return ApiResponse.of(authPolicyService.login(req));
    }

    @Operation(
        summary = "비밀번호 변경 (정책 적용)",
        description = """
                **인증(JWT) 필수.** 로그인 사용자 본인의 비밀번호를 변경한다(oldPassword 검증 후 newPassword 로 교체).

                **복잡도 정책** — 최소 8자 + 영문 대/소문자·숫자·특수문자 포함.
                **재사용 정책** — 직전(바로 전) 비밀번호 및 현재 비밀번호와 동일한 값으로는 변경 불가(1개 이력만 검사).
                """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "비밀번호 변경 성공."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "정책 위반(errorCode=PASSWORD_POLICY_VIOLATION 복잡도 미충족 | PASSWORD_REUSE 직전/현재 비밀번호와 동일)."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "oldPassword 불일치 또는 계정 없음(errorCode=INVALID_CREDENTIALS).")
    })
    @Auth
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody @Valid ChangePasswordRequest req) {
        authPolicyService.changePassword(AuthContext.empno(), req);
        return ApiResponse.of(null);
    }
}
