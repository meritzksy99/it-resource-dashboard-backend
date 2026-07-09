package com.meritz.dash.aggregation;

import com.meritz.dash.auth.Auth;
import com.meritz.dash.common.ApiResponse;
import com.meritz.dash.mapper.app.BatchLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 수동 집계 실행 및 이력 조회 API.
 * <ul>
 *   <li>POST /api/v1/aggregations — 단일 월 또는 from~to 범위 집계 실행 (201)</li>
 *   <li>GET  /api/v1/aggregations — BATCH_RUN_LOG 최근 이력 조회</li>
 * </ul>
 */
@Tag(name = "Aggregations", description = "수동 집계 실행 및 이력 조회")
@RestController
@RequestMapping("/api/v1/aggregations")
public class AggregationController {

    private final AggregationService service;
    private final BatchLogMapper batchLog;

    public AggregationController(AggregationService service, BatchLogMapper batchLog) {
        this.service = service;
        this.batchLog = batchLog;
    }

    @Auth(roles = {"01", "ADMIN"})
    @Operation(
        summary = "수동 집계 실행(단일 월 또는 from~to 범위)",
        description = "팀장·ADMIN 전용. 특정 월/기간 수동 집계(과거 백필). 기간계→DB2 집계. · 멱등(덮어씀). 단일 월: periodYm 지정. 범위: from~to 지정(둘 중 하나만 사용)."
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> run(@RequestBody AggregationRequest req) {
        // periods() 내부에서 형식 오류 시 IllegalArgumentException → GlobalExceptionHandler → 400
        List<String> periods = req.periods();
        for (String p : periods) {
            try {
                service.run(p, "MANUAL");
            } catch (Exception e) {
                throw new RuntimeException("집계 실행 중 오류 발생: period=" + p, e);
            }
        }
        return ApiResponse.of(Map.of("periods", periods, "count", periods.size()));
    }

    @Operation(
        summary = "집계 실행 이력 조회",
        description = "인증 필요. BATCH_RUN_LOG의 최근 50건 이력(OK/FAIL)을 반환한다."
    )
    @GetMapping
    public ApiResponse<List<BatchRunLogView>> history() {
        return ApiResponse.of(batchLog.findRecent());
    }
}
