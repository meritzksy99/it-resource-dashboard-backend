package com.meritz.dash.auth;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin - Accounts", description = "관리자 전용 — 계정 현황/잠금·휴면 해제/비밀번호 초기화 (ADMIN 권한)")
@Auth(roles = {"ADMIN"})
@RestController
@RequestMapping("/api/v1/admin/accounts")
public class AdminAccountController {

    private final AuthAdminService adminService;

    public AdminAccountController(AuthAdminService adminService) {
        this.adminService = adminService;
    }

    @Operation(
        summary = "계정 현황 목록",
        description = """
                **ADMIN 전용.** 전 계정의 상태(정상 00/잠금 01/휴면 02)·연속 실패횟수·최근 로그인 시각· \
                비밀번호 변경 시각·만료 여부(90일 초과)·휴면 여부(3개월 미접속)를 한 번에 조회한다. \
                계정 잠금/휴면 처리·해제는 여기서 하지 않고 `/{empno}/unlock`, 초기화는 `/{empno}/reset-password` 를 쓴다.
                """
    )
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "성공."))
    @GetMapping
    public ApiResponse<List<AdminAccountRow>> list() {
        return ApiResponse.of(adminService.listAccounts());
    }

    @Operation(
        summary = "잠금/휴면 해제",
        description = """
                **ADMIN 전용.** 대상 사번의 계정을 정상 상태로 되돌린다 — STATUS_CD='00', 연속 실패횟수 0으로 초기화, \
                휴면 판정 기준이 되는 최근 로그인 시계도 리셋한다. 비밀번호 자체는 변경하지 않는다(초기화는 별도 API).
                """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해제 성공."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "해당 사번의 계정을 찾을 수 없음.")
    })
    @PostMapping("/{empno}/unlock")
    public ApiResponse<Void> unlock(
            @Parameter(description = "사번", example = "E0002") @PathVariable String empno) {
        adminService.unlock(empno);
        return ApiResponse.of(null);
    }

    @Operation(
        summary = "비밀번호 초기화",
        description = """
                **ADMIN 전용.** 대상 사번의 비밀번호를 **사번 자신**의 값으로 초기화하고, 다음 로그인 시 강제 변경(pwdResetRequired) \
                상태로 만든다. 잠금/휴면 상태는 별도(`/{empno}/unlock`)로 해제해야 한다.
                """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "초기화 성공."),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "해당 사번의 계정을 찾을 수 없음.")
    })
    @PostMapping("/{empno}/reset-password")
    public ApiResponse<Void> resetPassword(
            @Parameter(description = "사번", example = "E0002") @PathVariable String empno) {
        adminService.resetPassword(empno);
        return ApiResponse.of(null);
    }
}
