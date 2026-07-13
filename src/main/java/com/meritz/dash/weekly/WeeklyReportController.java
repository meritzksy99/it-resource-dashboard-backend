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
 * 개인 주간보고 API. 등록/조회/수정/삭제 + 업무리더 추가의견.
 * RBAC/지연사유 규칙은 {@link WeeklyReportService} 참조.
 */
@Tag(name = "WeeklyReports", description = "개인 주간보고 등록/조회/수정/삭제 + 업무리더 추가의견")
@RestController
@RequestMapping("/api/v1/weekly-reports")
public class WeeklyReportController {

    private final WeeklyReportService service;

    public WeeklyReportController(WeeklyReportService service) {
        this.service = service;
    }

    /** 등록 요청 바디. */
    public record CreateRequest(
            @Schema(description = "주 기준 날짜(YYYYMMDD, 필수). 아무 요일이나 주면 서버가 그 주 목요일로 정규화해 저장. 형식 오류 400.",
                    example = "20260708")
            String week,
            @Schema(description = "SR 번호(필수). 기간계에서 실시간 검증하고 제목·반영예정일을 스냅샷 저장. 미존재 400.",
                    example = "T2607000001")
            String srNo,
            @Schema(description = "진행 내용(필수, 누락/공백 400).", example = "증권 잔고 정정 로직 개발 70% 진행")
            String content,
            @Schema(description = "본인 계획 반영예정일(YYYYMMDD, 선택). 형식 오류 400.", example = "20260724")
            String planDate,
            @Schema(description = "지연사유(항상 선택 — planDate 가 SR 예정일과 달라도 필수 아님).",
                    example = "연계 시스템 일정 지연")
            String delayReason) {}

    /** 부분 수정 요청 바디. */
    public record UpdateRequest(
            @Schema(description = "진행 내용(선택, null=미변경).", example = "증권 잔고 정정 로직 개발 90% 진행")
            String content,
            @Schema(description = "본인 계획 반영예정일(YYYYMMDD, 선택, null=미변경). 변경 시 기간계에서 SR 예정일 스냅샷 갱신.",
                    example = "20260731")
            String planDate,
            @Schema(description = "지연사유(항상 선택).", example = "테스트 일정 연기")
            String delayReason) {}

    /** 코멘트(업무리더 추가의견) 요청 바디. */
    public record CommentRequest(
            @Schema(description = "업무리더 추가의견.", example = "일정 리스크 있음 — 다음 주 중간 점검 필요")
            String comment) {}

    @Operation(
        summary = "주간보고 등록",
        description = """
                본인 주간보고를 등록한다(성공 **201**, 작성자=인증 사용자). **인증자 전원 호출 가능.**

                **week 정규화** — `week` 는 임의 날짜(YYYYMMDD)를 주면 서버가 **그 주(월~일) 목요일로 정규화**해 \
                저장한다(예: 20260712(일)→20260706). 형식 오류/실존하지 않는 날짜 **400**.

                **SR 검증·스냅샷** — `srNo` 는 **기간계에서 실시간 검증**하고 SR 제목·반영예정일을 스냅샷으로 함께 \
                저장한다(SR 미존재 **400**). `content` 필수(누락/공백 400). **지연사유(delayReason)는 항상 선택** \
                — planDate 가 SR 예정일과 달라도 사유 없이 저장된다.

                **중복** — 같은 (주, SR, 작성자) 조합이 이미 있으면 **409**.
                """
    )
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201", description = "등록 성공.",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EX_REPORT))))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WeeklyReport> create(@RequestBody CreateRequest req) {
        WeeklyReport created = service.create(req.week(), req.srNo(), req.content(), req.planDate(), req.delayReason());
        return ApiResponse.of(created);
    }

    @Operation(
        summary = "주간보고 목록 조회",
        description = """
                주 단위 목록. `week` 필수(임의 날짜 → 그 주 목요일로 정규화, 형식 오류 **400**). **인증(JWT) 필수.**

                **조회 스코프(역할별, fail-closed)** — 일반직원(03)=**본인 것만**, 업무리더(02)=**본인 파트**, \
                팀장(01)=**본인 부서**(`partCd` 로 파트 드릴다운), ADMIN=**전체**(`deptCd`/`partCd` 드릴다운). \
                meta={ week, scope(self·part·dept·all), total }.
                """
    )
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "성공.",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EX_LIST))))
    @GetMapping
    public ApiResponse<List<WeeklyReport>> list(
            @Parameter(description = "주 기준 날짜(YYYYMMDD, 필수). 아무 요일이나 주면 그 주 목요일로 정규화.", example = "20260709")
            @RequestParam String week,
            @Parameter(description = "부서코드(ADMIN 드릴다운, 그 외 역할은 무시).", example = "2139")
            @RequestParam(required = false) String deptCd,
            @Parameter(description = "파트코드(팀장(01)/ADMIN 드릴다운, 02/03 은 무시).", example = "P01")
            @RequestParam(required = false) String partCd) {
        WeeklyReportService.ListResult r = service.list(week, deptCd, partCd);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("week", week);
        meta.put("scope", r.scope());
        meta.put("total", r.items().size());
        return ApiResponse.of(r.items(), meta);
    }

    @Operation(
        summary = "주간보고 단건 조회",
        description = "목록과 동일 스코프(03=본인·02=파트·01=부서·ADMIN=전체). 스코프 밖이면 **403**(fail-closed), 미존재 **404**."
    )
    @GetMapping("/{id}")
    public ApiResponse<WeeklyReport> get(
            @Parameter(description = "주간보고 ID", example = "1") @PathVariable Long id) {
        return ApiResponse.of(service.get(id));
    }

    @Operation(
        summary = "주간보고 수정",
        description = """
                부분수정(전 필드 선택적, null=미변경). **작성자 본인 / 같은 dept+part 업무리더(02) / ADMIN** 만 \
                허용(그 외 **403**), 미존재 **404**. `planDate` 변경 시 기간계에서 SR 예정일 스냅샷을 갱신한다. \
                지연사유는 항상 선택.
                """
    )
    @PutMapping("/{id}")
    public ApiResponse<WeeklyReport> update(
            @Parameter(description = "주간보고 ID", example = "1") @PathVariable Long id,
            @RequestBody UpdateRequest req) {
        return ApiResponse.of(service.update(id, req.content(), req.planDate(), req.delayReason()));
    }

    @Operation(
        summary = "업무리더 추가의견",
        description = """
                대상 보고에 리더 코멘트(leaderCmt)를 남긴다. **역할 필수(02/01/ADMIN)** — \
                업무리더(02)=**같은 dept+part** 보고만, 팀장(01)=**같은 dept** 보고만, ADMIN=전체. \
                스코프 밖 **403**(fail-closed), 미존재 **404**.
                """
    )
    @Auth(roles = {"02", "01", "ADMIN"})
    @PutMapping("/{id}/leader-comment")
    public ApiResponse<WeeklyReport> leaderComment(
            @Parameter(description = "주간보고 ID", example = "1") @PathVariable Long id,
            @RequestBody CommentRequest req) {
        return ApiResponse.of(service.leaderComment(id, req.comment()));
    }

    @Operation(
        summary = "주간보고 삭제",
        description = "**작성자 본인 또는 ADMIN 만**(그 외 **403**), 미존재 **404**. 성공 시 **204**."
    )
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "주간보고 ID", example = "1") @PathVariable Long id) {
        service.delete(id);
    }

    // ── Swagger 응답 예시 ─────────────────────────────────────────────
    private static final String EX_REPORT = """
            {
              "data": {
                "rptId": 1, "weekYmd": "20260706", "srNo": "T2607000001",
                "srTitl": "증권 잔고 정정", "regEmpno": "9320", "deptCd": "2139", "partCd": "P01",
                "rptCntt": "증권 잔고 정정 로직 개발 70% 진행",
                "planDate": "20260724", "srPlanDate": "20260720",
                "delayRsn": "연계 시스템 일정 지연", "leaderCmt": null
              },
              "meta": null
            }""";

    private static final String EX_LIST = """
            {
              "data": [
                {
                  "rptId": 1, "weekYmd": "20260706", "srNo": "T2607000001",
                  "srTitl": "증권 잔고 정정", "regEmpno": "9320", "deptCd": "2139", "partCd": "P01",
                  "rptCntt": "증권 잔고 정정 로직 개발 70% 진행",
                  "planDate": "20260724", "srPlanDate": "20260720",
                  "delayRsn": "연계 시스템 일정 지연",
                  "leaderCmt": "일정 리스크 있음 — 다음 주 중간 점검 필요"
                }
              ],
              "meta": { "week": "20260706", "scope": "part", "total": 1 }
            }""";
}
