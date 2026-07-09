package com.meritz.dash.code;

import com.meritz.dash.auth.Auth;
import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Codes", description = "공통코드 관리")
@RestController
@RequestMapping("/api/v1/codes")
public class CodeController {

    private final CodeService codeService;

    public CodeController(CodeService codeService) {
        this.codeService = codeService;
    }

    @Operation(summary = "공통코드 그룹 조회", description = "인증 불필요. grpCd 그룹의 유효 코드 목록을 반환한다.")
    @GetMapping
    public ApiResponse<List<CommonCode>> getCodes(
            @Parameter(description = "공통코드 그룹. SR_TPCD/SR_CLS/EMP_ROLE/EMP_STATUS/DEPT_CD/PART_CD", example = "DEPT_CD")
            @RequestParam String grpCd) {
        List<CommonCode> codes = codeService.getCodes(grpCd);
        return ApiResponse.of(codes, Map.of("grpCd", grpCd, "count", codes.size()));
    }

    @Auth(roles = {"01", "ADMIN"})
    @Operation(summary = "공통코드 등록", description = "팀장·ADMIN 전용. 새 공통코드를 등록한다(201 반환).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommonCode> create(@RequestBody @Valid CodeRequest req) {
        return ApiResponse.of(codeService.create(req));
    }

    @Auth(roles = {"01", "ADMIN"})
    @Operation(summary = "공통코드 수정", description = "팀장·ADMIN 전용. 기존 공통코드 항목을 수정한다.")
    @PutMapping("/{grpCd}/{cdVal}")
    public ApiResponse<CommonCode> update(
            @Parameter(description = "코드 그룹", example = "TESTGRP") @PathVariable String grpCd,
            @Parameter(description = "코드값", example = "01") @PathVariable String cdVal,
            @RequestBody @Valid CodeRequest req) {
        return ApiResponse.of(codeService.update(grpCd, cdVal, req));
    }

    @Auth(roles = {"01", "ADMIN"})
    @Operation(summary = "공통코드 비활성화(소프트 삭제)", description = "팀장·ADMIN 전용. USE_YN=N 으로 비활성화(물리 삭제 아님).")
    @DeleteMapping("/{grpCd}/{cdVal}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "코드 그룹", example = "TESTGRP") @PathVariable String grpCd,
            @Parameter(description = "코드값", example = "01") @PathVariable String cdVal) {
        codeService.delete(grpCd, cdVal);
    }
}
