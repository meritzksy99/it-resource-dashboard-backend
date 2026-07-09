package com.meritz.dash.srproject;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Tag(name = "SrProjects", description = "주요 SR 프로젝트 조회")
@RestController
@RequestMapping("/api/v1/sr-projects")
public class SrProjectController {
    private final SrProjectService service;

    public SrProjectController(SrProjectService service) {
        this.service = service;
    }

    @Operation(
        summary = "주요 SR Top (M/M 기준, 페이지당 기본 5)",
        description = "인증 불필요. 입력: period(YYYYMM) / minMm(최소 M/M) / type(SR유형코드) / page·size(페이징) · 출력: 계획 M/M 상위 SR 내림차순(srNo·titlCntt·srTpcd·totMm·empCnt·부서코드 포함)."
    )
    @GetMapping
    public ApiResponse<List<SrProjectView>> top(
            @Parameter(description = "조회 기간 (YYYYMM)", example = "202606")
            @RequestParam String period,
            @Parameter(description = "최소 M/M 필터 (이상만 포함, 미입력 시 전체)", example = "0.6")
            @RequestParam(required = false) Double minMm,
            @Parameter(description = "SR 유형코드 필터 (SR_TPCD 그룹). 미입력 시 전체", example = "01")
            @RequestParam(required = false) String type,
            @Parameter(description = "페이지 번호 (0부터 시작)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기", example = "5")
            @RequestParam(defaultValue = "5") int size) {
        SrProjectService.Page p = service.top(period, minMm, type, page, size);
        return ApiResponse.of(p.items(), Map.of("page", page, "size", size,
                "totalElements", p.totalElements(), "period", period));
    }
}
