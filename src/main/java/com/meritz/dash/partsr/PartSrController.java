package com.meritz.dash.partsr;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Dashboard", description = "대시보드 요약 API")
@RestController
@RequestMapping("/api/v1/dashboard")
public class PartSrController {

    private final PartSrService service;

    public PartSrController(PartSrService service) {
        this.service = service;
    }

    @Operation(
            summary = "파트별 SR 요약 (내부/외주 분리)",
            description = """
                    **무엇을 하는 API인가** — 지정한 월(period)의 **파트별 SR 처리 현황 요약**을 반환한다. \
                    사내 집계 테이블(DB2, DASH_DEV_AGG)에서 읽으므로 빠르며 기간계(운영)에 부하를 주지 않는다. 인증(JWT) 필수.

                    **응답 구조** — `data.parts[]`(내부 파트) / `data.outsourcing[]`(외주 파트, DEPT_CD=9000) 두 최상위 목록으로 분리된다. \
                    각 행(PartSrRow)은 부서(deptCd·deptNm)·파트(partCd·partNm)·인원(headcount)·구성원 이름목록(memberNames)·총 M/M(totMm)·SR종류별 집계(srByClass[])를 담는다. \
                    `srByClass`는 srCls 오름차순, `totMm`은 SR종류별 mm의 합(부동소수점 오차 없이 정수 누적 후 환산).

                    **기본 동작(디폴트)** — `part`를 생략하면 **전체 파트를 요약**해 모든 파트가 parts[]에 담겨 나온다(권장 기본 호출). \
                    `part`를 주면 해당 파트만 필터링한다.

                    **파라미터별 케이스**
                    - `period`(필수, YYYYMM): 조회 월. 형식 오류(예: 2026, 202613, 202600) 시 400.
                    - `part`(선택, 영문·숫자 1~20자): 생략=전체 파트, 지정=단일 파트. 이상 문자/과도한 길이 시 400.

                    **meta** — `{ period, partCount(내부 파트 수), outsourcingCount(외주 파트 수) }`.
                    """
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "성공. part 생략 시 전체 파트 요약, part 지정 시 단일 파트.",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "① 전체 파트 요약 (기본 · part 생략)", value = EX_ALL),
                            @ExampleObject(name = "② 단일 파트 (part=P01)", value = EX_SINGLE),
                            @ExampleObject(name = "③ 외주 파트 포함 (outsourcing[])", value = EX_OUTSOURCING)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "period 형식 오류 또는 part 형식 오류",
                    content = @Content(mediaType = "application/json", examples =
                            @ExampleObject(name = "period 형식 오류", value = EX_400))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "인증 토큰 없음/만료",
                    content = @Content(mediaType = "application/json", examples =
                            @ExampleObject(name = "인증 필요", value = EX_401)))
    })
    @GetMapping("/part-sr")
    public ApiResponse<PartSrResult> partSr(
            @Parameter(description = "조회 년월 (YYYYMM 6자리 실제 월 · 필수)", example = "202606", required = true,
                    examples = {
                            @ExampleObject(name = "당월", value = "202606"),
                            @ExampleObject(name = "연초", value = "202601")
                    })
            @RequestParam String period,
            @Parameter(description = "파트 코드 (선택 · 생략 시 전체 파트 요약). 영문·숫자 1~20자.",
                    examples = {
                            @ExampleObject(name = "전체 파트(기본, 생략)", value = ""),
                            @ExampleObject(name = "금융상품 파트", value = "P01"),
                            @ExampleObject(name = "계좌 파트", value = "P02")
                    })
            @RequestParam(required = false) String part) {
        PartSrResult result = service.summary(period, part);
        return ApiResponse.of(result, Map.of(
                "period", period,
                "partCount", result.parts().size(),
                "outsourcingCount", result.outsourcing().size()
        ));
    }

    // ── Swagger 응답 예시(테스트 케이스별 인풋/아웃풋) ─────────────────────────────
    private static final String EX_ALL = """
            {
              "data": {
                "parts": [
                  { "deptCd": "2139", "deptNm": "IT개발팀", "partCd": "P01", "partNm": "금융상품",
                    "headcount": 3, "memberNames": ["김성엽","김팀장","이개발"], "totMm": 2.36,
                    "srByClass": [ { "srCls": "01", "srClsNm": "개발요청", "srCnt": 2, "mm": 2.36 } ] },
                  { "deptCd": "2139", "deptNm": "IT개발팀", "partCd": "P02", "partNm": "계좌",
                    "headcount": 2, "memberNames": ["박개발","최개발"], "totMm": 1.84,
                    "srByClass": [ { "srCls": "01", "srClsNm": "개발요청", "srCnt": 2, "mm": 1.84 } ] }
                ],
                "outsourcing": []
              },
              "meta": { "period": "202606", "partCount": 2, "outsourcingCount": 0 }
            }""";

    private static final String EX_SINGLE = """
            {
              "data": {
                "parts": [
                  { "deptCd": "2139", "deptNm": "IT개발팀", "partCd": "P01", "partNm": "금융상품",
                    "headcount": 3, "memberNames": ["김성엽","김팀장","이개발"], "totMm": 2.36,
                    "srByClass": [ { "srCls": "01", "srClsNm": "개발요청", "srCnt": 2, "mm": 2.36 } ] }
                ],
                "outsourcing": []
              },
              "meta": { "period": "202606", "partCount": 1, "outsourcingCount": 0 }
            }""";

    private static final String EX_OUTSOURCING = """
            {
              "data": {
                "parts": [
                  { "deptCd": "2139", "deptNm": "IT개발팀", "partCd": "P01", "partNm": "금융상품",
                    "headcount": 3, "memberNames": ["김성엽","김팀장","이개발"], "totMm": 2.36,
                    "srByClass": [ { "srCls": "01", "srClsNm": "개발요청", "srCnt": 2, "mm": 2.36 } ] }
                ],
                "outsourcing": [
                  { "deptCd": "9000", "deptNm": "외주", "partCd": "P01", "partNm": "금융상품",
                    "headcount": 1, "memberNames": ["외주개발자"], "totMm": 0.50,
                    "srByClass": [ { "srCls": "02", "srClsNm": "유지보수", "srCnt": 3, "mm": 0.50 } ] }
                ]
              },
              "meta": { "period": "202606", "partCount": 1, "outsourcingCount": 1 }
            }""";

    private static final String EX_400 = """
            {
              "type": "about:blank", "title": "Bad Request", "status": 400,
              "detail": "period는 YYYYMM 6자리 실제 월이어야 합니다: 2026",
              "instance": "/api/v1/dashboard/part-sr"
            }""";

    private static final String EX_401 = """
            {
              "type": "about:blank", "title": "Unauthorized", "status": 401,
              "detail": "인증 토큰이 필요합니다",
              "instance": "/api/v1/dashboard/part-sr"
            }""";
}
