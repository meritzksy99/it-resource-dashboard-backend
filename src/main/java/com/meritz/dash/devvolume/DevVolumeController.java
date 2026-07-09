package com.meritz.dash.devvolume;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "DevVolume", description = "월별 개발량(SR 건수 + M/M) 추이")
@RestController
@RequestMapping("/api/v1/dev-volume")
public class DevVolumeController {
    private final DevVolumeService service;

    public DevVolumeController(DevVolumeService service) {
        this.service = service;
    }

    @Operation(
        summary = "월별 개발량(SR 건수 + M/M) 추이 + 드릴다운",
        description = """
                **무엇을 하는 API인가** — 월별 개발량 추이를 **SR 분류별 건수(srCnt)와 개발량 M/M(jobMm)** 두 축으로 반환한다(막대/라인 차트용). \
                사내 집계 테이블(DASH_DEV_AGG)에서 읽는다. **인증(JWT) 필수**(login·health 외 /api/v1/** 전체 인증).

                **입력(기본값)**
                - `unit`(기본 `all`): 집계 단위 — all=전사 · dept=부서 · part=부서-파트 · dev=개발자.
                - `period`(기본 `6m`): 조회 기간 — 6m=최근 6개월 · 12m=최근 12개월(당월 포함, 당월 기준 역산).
                - `unitId`(선택): all이면 불필요. dept=부서코드(2139) · part='부서코드-파트코드'(2139-P01) · dev=사번(7451). \
                dept/part/dev인데 비우면 **400**.
                - **권한(unit=dev)**: 개인 드릴다운(unit=dev)은 개인정보라 역할별로 조회 범위가 다르다 — \
                **ADMIN·팀장(01)**: 제한 없음(전체 개발자) · **업무리더(02)**: 본인 파트원만(타파트 사번은 본인 사번으로 강제) · \
                **일반직원(03) 및 기타**: 본인 사번만(타인 사번을 넣어도 무시). \
                all/dept/part 집계 단위는 인증만 되면 조회 가능.

                **출력** — 월×SR분류별 데이터 포인트 배열(periodYm 오름차순): \
                `periodYm`·`monthLabel`(YY.MM)·`srCls`(01 개발요청·02 유지보수·03 자료요청·99 기타)·`srClsName`·`srCnt`(건수)·`jobMm`(개발량 M/M=Σ 투입시간÷166). \
                srCnt·jobMm 모두 **해당 월/SR분류 단위** 집계값이다. \
                **meta** — `{ unit, period, count }`.
                """
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공. 월×SR분류별 시리즈(건수+M/M).",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "① 전사(unit=all, period=6m) — 기본값 호출", value = EX_ALL),
                            @ExampleObject(name = "② 부서 드릴다운(unit=dept&unitId=2139)", value = EX_DEPT)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "unit 값 오류 또는 dept/part/dev인데 unitId 누락",
                    content = @Content(mediaType = "application/json", examples =
                            @ExampleObject(name = "unitId 누락", value = EX_400)))
    })
    @GetMapping
    public ApiResponse<List<DevVolumePoint>> series(
            @Parameter(description = "집계 단위. all=전사, dept=부서, part=부서-파트, dev=개발자 (기본값 all)",
                    examples = {
                            @ExampleObject(name = "전사(기본)", value = "all"),
                            @ExampleObject(name = "부서", value = "dept"),
                            @ExampleObject(name = "부서-파트", value = "part"),
                            @ExampleObject(name = "개발자", value = "dev")
                    })
            @RequestParam(defaultValue = "all") String unit,
            @Parameter(description = "조회 기간. 6m=최근 6개월, 12m=최근 12개월 (기본값 6m)",
                    examples = {
                            @ExampleObject(name = "최근 6개월(기본)", value = "6m"),
                            @ExampleObject(name = "최근 12개월", value = "12m")
                    })
            @RequestParam(defaultValue = "6m")   String period,
            @Parameter(description = "단위 식별자. all이면 불필요. dept=부서코드(2139), part='부서코드-파트코드'(2139-P01), dev=사번(7451)",
                    examples = {
                            @ExampleObject(name = "부서코드", value = "2139"),
                            @ExampleObject(name = "부서-파트코드", value = "2139-P01"),
                            @ExampleObject(name = "사번", value = "7451")
                    })
            @RequestParam(required = false)       String unitId) {
        List<DevVolumePoint> pts = service.series(unit, period, unitId);
        return ApiResponse.of(pts, Map.of("unit", unit, "period", period, "count", pts.size()));
    }

    // ── Swagger 응답 예시 ────────────────────────────────────────────────
    private static final String EX_ALL = """
            {
              "data": [
                { "periodYm": "202601", "monthLabel": "26.01", "srCls": "01", "srClsName": "개발요청", "srCnt": 8,  "jobMm": 2.10 },
                { "periodYm": "202601", "monthLabel": "26.01", "srCls": "02", "srClsName": "유지보수", "srCnt": 15, "jobMm": 1.35 },
                { "periodYm": "202602", "monthLabel": "26.02", "srCls": "01", "srClsName": "개발요청", "srCnt": 10, "jobMm": 2.80 }
              ],
              "meta": { "unit": "all", "period": "6m", "count": 3 }
            }""";

    private static final String EX_DEPT = """
            {
              "data": [
                { "periodYm": "202606", "monthLabel": "26.06", "srCls": "01", "srClsName": "개발요청", "srCnt": 12, "jobMm": 3.75 },
                { "periodYm": "202606", "monthLabel": "26.06", "srCls": "02", "srClsName": "유지보수", "srCnt": 9,  "jobMm": 0.90 }
              ],
              "meta": { "unit": "dept", "period": "6m", "count": 2 }
            }""";

    private static final String EX_400 = """
            {
              "type": "about:blank", "title": "Bad Request", "status": 400,
              "detail": "dept/part/dev 조회에는 unitId가 필요합니다",
              "instance": "/api/v1/dev-volume"
            }""";
}
