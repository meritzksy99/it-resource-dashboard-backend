package com.meritz.dash.health;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@Tag(name = "Health", description = "서버 상태 확인")
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @Operation(
        summary = "서버 상태 확인",
        description = "공개 엔드포인트. 서버 구동 상태(UP)와 현재 타임스탬프를 반환한다."
    )
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.of(Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString()
        ));
    }
}
