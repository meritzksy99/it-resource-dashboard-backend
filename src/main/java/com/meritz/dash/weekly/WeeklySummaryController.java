package com.meritz.dash.weekly;

import com.meritz.dash.auth.Auth;
import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 파트 취합본 API. 업무리더(02)가 등록/수정/삭제, 팀장(01)/ADMIN 이 최종의견.
 * 같은 주·같은 파트에 여러 건 등록 가능(다건 허용).
 */
@Tag(name = "WeeklySummaries", description = "파트 취합본 등록/조회/수정/삭제/최종의견 (같은 주·파트 다건 허용)")
@RestController
@RequestMapping("/api/v1/weekly-summaries")
public class WeeklySummaryController {

    private final WeeklySummaryService service;

    public WeeklySummaryController(WeeklySummaryService service) {
        this.service = service;
    }

    /** 등록 요청 바디. rptIds(선택)=취합본을 구성할 개인 보고 ID 목록. */
    public record SubmitRequest(
            @Schema(description = "주 기준 날짜(YYYYMMDD, 필수). 아무 요일이나 주면 그 주 목요일로 정규화. 형식 오류 400.",
                    example = "20260708")
            String week,
            @Schema(description = "취합 내용(필수, 누락/공백 400).", example = "P01 파트 주간 취합 — SR 3건 진행, 1건 지연")
            String content,
            @Schema(description = "취합본을 구성할 개인 보고(RPT_ID) 목록(선택). 각 id 는 존재·같은 주차·대상 파트 소속이어야 하며 하나라도 어긋나면 400.",
                    example = "[1, 2, 3]")
            List<Long> rptIds,
            @Schema(description = "부서코드 — ADMIN 만 필수(없으면 400). 02 는 본인 값 강제(요청값 무시).", example = "2139")
            String deptCd,
            @Schema(description = "파트코드 — ADMIN 만 필수(없으면 400). 02 는 본인 값 강제(요청값 무시).", example = "P01")
            String partCd) {}

    /** 수정 요청 바디. content=null 이면 본문 불변, rptIds=null 이면 링크 불변(빈 배열=전부 해제). */
    public record UpdateRequest(
            @Schema(description = "취합 내용(선택). null=본문 불변.", example = "P01 파트 주간 취합(수정) — SR 3건 진행")
            String content,
            @Schema(description = "개인 보고 링크 전체 교체(선택). null=링크 불변, []=전부 해제, 값이 오면 기존 링크 삭제 후 교체(검증 실패 400·기존 보존).",
                    example = "[1, 3]")
            List<Long> rptIds) {}

    /** 팀장 최종의견 요청 바디. */
    public record CommentRequest(
            @Schema(description = "팀장 최종의견.", example = "취합 확인. 지연건은 차주 우선 처리 바람.")
            String comment) {}

    @Operation(
        summary = "파트 취합본 등록",
        description = """
                파트 취합본을 등록한다(항상 신규, 성공 **201**). **업무리더(02)/ADMIN 전용.** \
                **같은 주·같은 파트에 여러 건 허용**(다건).

                **대상 파트** — 02 는 본인 dept/part 강제(요청의 deptCd/partCd 무시), ADMIN 은 deptCd/partCd \
                필수(없으면 **400**). `week` 는 임의 날짜 → 그 주 목요일로 정규화(형식 오류 400), `content` 필수.

                **rptIds(선택)** — 파트원 개인 보고를 골라 취합본을 구성한다. 각 id 는 **존재·같은 주차·대상 파트 \
                소속**이어야 하며 하나라도 어긋나면 **400**. 응답 `reports` 에 선택된 보고가 임베드된다.
                """
    )
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201", description = "등록 성공.",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EX_SUMMARY))))
    @Auth(roles = {"02", "ADMIN"})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WeeklySummary> submit(@RequestBody SubmitRequest req) {
        return ApiResponse.of(service.submit(req.week(), req.content(), req.rptIds(), req.deptCd(), req.partCd()));
    }

    @Operation(
        summary = "파트 취합본 목록 조회",
        description = """
                주 단위 목록. `week` 필수(임의 날짜 → 그 주 목요일로 정규화, 형식 오류 **400**). **인증(JWT) 필수.**

                **조회 스코프** — 일반직원(03)/업무리더(02)=**본인 파트**, 팀장(01)=**본인 부서**(파트별 목록), \
                ADMIN=**전체**(`deptCd` 드릴다운). 한 파트에 여러 행이 반환될 수 있다(같은 파트 내 최신 SUM_ID 우선). \
                각 취합본의 `reports` 에 선택된 개인 보고를 임베드(RPT_ID 오름차순, 없으면 빈 배열). \
                meta={ week, scope(part·dept·all), total }.

                **팁** — "지난주 취합 불러오기"는 별도 API 가 아니라 `week` 에 지난주 목요일(YYYYMMDD)을 넣어 \
                이 GET 을 호출하면 된다.
                """
    )
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "성공.",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EX_LIST))))
    @GetMapping
    public ApiResponse<List<WeeklySummary>> list(
            @Parameter(description = "주 기준 날짜(YYYYMMDD, 필수). 아무 요일이나 주면 그 주 목요일로 정규화. 지난주 목요일을 주면 지난주 취합 조회.",
                    example = "20260709")
            @RequestParam String week,
            @Parameter(description = "부서코드(ADMIN 드릴다운, 그 외 역할은 무시).", example = "2139")
            @RequestParam(required = false) String deptCd) {
        WeeklySummaryService.ListResult r = service.list(week, deptCd);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("week", week);
        meta.put("scope", r.scope());
        meta.put("total", r.items().size());
        return ApiResponse.of(r.items(), meta);
    }

    @Operation(
        summary = "파트 취합본 수정",
        description = """
                **같은 dept+part 의 업무리더(02) 또는 ADMIN 만**(그 외 **403**), 미존재 **404**. \
                최종의견(TEAM_CMT)은 이 API 로 변경하지 않는다.

                **부분수정 규칙** — `content`=null 이면 본문 불변. `rptIds` 가 오면(빈 배열 포함) 기존 선택 링크를 \
                **전부 삭제 후 교체**(등록과 동일 검증 — 존재·같은 주차·같은 파트, 실패 시 **400**·기존 링크 보존), \
                `rptIds`=null 이면 링크 불변, `[]`=전부 해제. content·rptIds 둘 다 null 이면 **400**.
                """
    )
    @Auth(roles = {"02", "ADMIN"})
    @PutMapping("/{id}")
    public ApiResponse<WeeklySummary> update(
            @Parameter(description = "파트 취합본 ID", example = "1") @PathVariable Long id,
            @RequestBody UpdateRequest req) {
        return ApiResponse.of(service.update(id, req.content(), req.rptIds()));
    }

    @Operation(
        summary = "파트 취합본 삭제",
        description = "**같은 dept+part 의 업무리더(02) 또는 ADMIN 만**(그 외 **403**), 미존재 **404**. 성공 시 **204**(개인 보고 링크는 FK CASCADE 로 함께 정리, 개인 보고 자체는 보존)."
    )
    @Auth(roles = {"02", "ADMIN"})
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "파트 취합본 ID", example = "1") @PathVariable Long id) {
        service.delete(id);
    }

    @Operation(
        summary = "팀장 최종의견",
        description = """
                대상 취합본 행에 최종의견(teamCmt)을 남긴다. **역할 필수(01/ADMIN)** — 팀장(01)=**본인 부서** \
                취합본만(그 외 **403**), ADMIN=전체. 미존재 **404**. 같은 주·파트에 취합본이 여러 건이면 **행 단위**로 남긴다.
                """
    )
    @Auth(roles = {"01", "ADMIN"})
    @PutMapping("/{id}/final-comment")
    public ApiResponse<WeeklySummary> finalComment(
            @Parameter(description = "파트 취합본 ID", example = "1") @PathVariable Long id,
            @RequestBody CommentRequest req) {
        return ApiResponse.of(service.finalComment(id, req.comment()));
    }

    // ── Swagger 응답 예시 ─────────────────────────────────────────────
    private static final String EX_SUMMARY = """
            {
              "data": {
                "sumId": 1, "weekYmd": "20260706", "deptCd": "2139", "partCd": "P01",
                "sumCntt": "P01 파트 주간 취합 — SR 3건 진행, 1건 지연",
                "regEmpno": "9421", "teamCmt": null, "teamCmtEmpno": null,
                "reports": [
                  {
                    "rptId": 1, "srNo": "T2607000001", "srTitl": "증권 잔고 정정",
                    "regEmpno": "9320", "rptCntt": "증권 잔고 정정 로직 개발 70% 진행",
                    "planDate": "20260724", "srPlanDate": "20260720",
                    "delayRsn": "연계 시스템 일정 지연", "leaderCmt": null
                  }
                ]
              },
              "meta": null
            }""";

    private static final String EX_LIST = """
            {
              "data": [
                {
                  "sumId": 1, "weekYmd": "20260706", "deptCd": "2139", "partCd": "P01",
                  "sumCntt": "P01 파트 주간 취합 — SR 3건 진행, 1건 지연",
                  "regEmpno": "9421",
                  "teamCmt": "취합 확인. 지연건은 차주 우선 처리 바람.", "teamCmtEmpno": "9001",
                  "reports": [
                    {
                      "rptId": 1, "srNo": "T2607000001", "srTitl": "증권 잔고 정정",
                      "regEmpno": "9320", "rptCntt": "증권 잔고 정정 로직 개발 70% 진행",
                      "planDate": "20260724", "srPlanDate": "20260720",
                      "delayRsn": "연계 시스템 일정 지연", "leaderCmt": null
                    }
                  ]
                }
              ],
              "meta": { "week": "20260706", "scope": "part", "total": 1 }
            }""";
}
