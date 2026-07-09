package com.meritz.dash.worksite;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Work Sites", description = "업무사이트 바로가기 모음")
@RestController
@RequestMapping("/api/v1/work-sites")
public class WorkSiteController {

    private final WorkSiteService workSiteService;

    public WorkSiteController(WorkSiteService workSiteService) {
        this.workSiteService = workSiteService;
    }

    @Operation(
        summary = "업무사이트 목록 조회",
        description = """
                **무엇을 하는 API인가** — 웹사이트의 **'업무사이트 모음' 위젯**용 목록 API. \
                사용중(**USE_YN='Y'**)인 업무 사이트만 **정렬 순서(SORT_NO, 화면명 순)** 로 반환한다. \
                각 항목은 바로가기 링크 `url` · 화면명 `name` · 설명 `description` 으로 구성된다. \
                **인증(JWT) 필수**, 역할 제한 없음(**로그인한 전 직원** 조회 가능).

                **입력** — 없음(파라미터 없음).

                **출력** — 응답 envelope `{ "data": [...], "meta": { "count": N } }`. \
                data는 `{url, name, description}` 배열, meta.count는 반환 건수.

                **비노출 정보** — 내부 관리 컬럼(siteId · useYn · sortNo · 감사컬럼 CREATED_AT/BY 등)은 \
                응답에 **노출되지 않는다**. 정렬은 서버가 이미 적용해 내려주므로 클라이언트 재정렬 불필요.
                """
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공. 활성 업무사이트 배열(정렬 순서 적용됨).",
                    content = @Content(mediaType = "application/json", examples =
                            @ExampleObject(name = "활성 업무사이트 목록", value = EX_SITES)))
    })
    @GetMapping
    public ApiResponse<List<WorkSite>> getWorkSites() {
        List<WorkSite> sites = workSiteService.getActiveSites();
        return ApiResponse.of(sites, Map.of("count", sites.size()));
    }

    // ── Swagger 응답 예시 ────────────────────────────────────────────────
    private static final String EX_SITES = """
            {
              "data": [
                { "url": "https://gw.example.co.kr",   "name": "그룹웨어",      "description": "전자결재·메일·게시판 통합 그룹웨어" },
                { "url": "https://itsm.example.co.kr", "name": "ITSM SR관리",  "description": "SR 접수·진행·이관 관리 시스템" },
                { "url": "https://wiki.example.co.kr", "name": "사내 위키",     "description": "개발 표준·가이드 문서 위키" }
              ],
              "meta": { "count": 3 }
            }""";
}
