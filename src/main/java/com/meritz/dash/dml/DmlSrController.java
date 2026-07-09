package com.meritz.dash.dml;

import com.meritz.dash.auth.Auth;
import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DML성 SR(유형 18/19) 점검·개선 관리 API. 화면 단계별로 조회를 3개로 분리.
 * <ul>
 *   <li>GET {@code /dml-srs} — ① 전체/스냅샷(개발팀 가시성, 팀·파트 필터)</li>
 *   <li>GET {@code /dml-srs/inspections} — ② 점검 대상(본인 파트)</li>
 *   <li>GET {@code /dml-srs/improvements} — ③ 개선 대상(본인 파트·점검완료)</li>
 * </ul>
 * 쓰기(점검/개선)는 서비스단 RBAC(fail-closed), 수동 동기화(/sync)는 팀장(01)·ADMIN 전용.
 */
@Tag(name = "DmlSr", description = "DML성 SR 점검·개선 관리")
@RestController
@RequestMapping("/api/v1/dml-srs")
public class DmlSrController {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final DmlSrService service;
    private final DmlSyncService syncService;

    public DmlSrController(DmlSrService service, DmlSyncService syncService) {
        this.service = service;
        this.syncService = syncService;
    }

    /** 점검 여부 변경 요청 바디. */
    public record CheckRequest(String checkYn) {}

    /** 개선건 등록/갱신 요청 바디. */
    public record ImprovementRequest(String improvePlan, String planCmptDate, String cmptYn, String remark) {}

    /** 개선대상여부 토글 요청 바디. */
    public record ImproveTargetRequest(String improveYn) {}

    // ── ① 전체/스냅샷 조회 ────────────────────────────────────────────
    @Operation(
        summary = "① DML SR 전체 목록 (스냅샷 — 개발팀 가시성)",
        description = """
                매주 배치가 적재한 **DML성 SR(유형 18/19) 월별 스냅샷**을 반환한다. **인증(JWT)만 필요, 역할 제한 없음** \
                — 개발팀 누구나 전체 현황을 본다(가시성 목적). 점검/개선 상태(DASH_DML_CHECK)도 함께.

                **필터** — `baseYm`(yyyyMM, 미지정 시 이번 달), `deptCd`(부서), `partCd`(파트). 조합 가능.

                **응답** — data=SR 항목 배열, meta={ baseYm, scope(all·dept·part), total, checkedCount, improveCount }.
                """
    )
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "성공.",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EX_LIST))))
    @GetMapping
    public ApiResponse<List<DmlSrItem>> overview(
            @Parameter(description = "기준월(yyyyMM). 미지정 시 이번 달.", example = "202607")
            @RequestParam(required = false) String baseYm,
            @Parameter(description = "부서코드 필터(선택).", example = "2139")
            @RequestParam(required = false) String deptCd,
            @Parameter(description = "파트코드 필터(선택).", example = "P01")
            @RequestParam(required = false) String partCd) {
        String ym = resolveYm(baseYm);
        return wrap(ym, service.overview(ym, deptCd, partCd));
    }

    // ── ② 점검 대상 조회 ──────────────────────────────────────────────
    @Operation(
        summary = "② 점검 대상 목록 (본인 파트)",
        description = """
                **점검 화면 전용.** 로그인 사용자가 점검(점검여부 Y/N)할 수 있는 **본인 파트** SR 만 반환한다. **인증(JWT) 필수**.

                **조회 범위** — 업무리더(02)·일반직원(03): **본인 부서+파트**. 팀장(01): 본인 부서(‧`partCd` 지정 시 해당 파트). ADMIN: 전체(‧`partCd` 지정 시 해당 파트). \
                (파트코드는 부서 간 재사용되므로 항상 부서와 함께 좁힌다.)

                **필터** — `baseYm`(미지정 시 이번 달), `partCd`(팀장/ADMIN 드릴다운용).
                여기서 각 건을 `PATCH /{srNo}/check` 로 점검 처리하고, 개선 필요 건은 `PUT /{srNo}/improvement` 로 넘어간다.
                """
    )
    @GetMapping("/inspections")
    public ApiResponse<List<DmlSrItem>> inspections(
            @Parameter(description = "기준월(yyyyMM). 미지정 시 이번 달.", example = "202607")
            @RequestParam(required = false) String baseYm,
            @Parameter(description = "팀장(01)/ADMIN 이 특정 파트를 볼 때만. 02/03 은 무시.", example = "P01")
            @RequestParam(required = false) String partCd) {
        String ym = resolveYm(baseYm);
        return wrap(ym, service.inspections(ym, partCd));
    }

    // ── ③ 개선 대상 조회 ──────────────────────────────────────────────
    @Operation(
        summary = "③ 개선 대상 목록 (본인 파트 · 개선대상여부 Y 건)",
        description = """
                **개선 화면 전용.** ②와 동일 스코프(본인 파트) 중 **개선대상여부(IMPROVE_YN='Y')** 로 지정된 건만 반환한다. \
                점검 화면에서 `PATCH /{srNo}/improve-target {improveYn:'Y'}` 로 개선건에 올리면 여기서 조회되며, \
                개선방안·완료예정·완료여부·비고는 `PUT /{srNo}/improvement` 로 등록/수정한다. **인증(JWT) 필수**.

                **필터** — `baseYm`(미지정 시 이번 달), `partCd`(팀장/ADMIN).
                """
    )
    @GetMapping("/improvements")
    public ApiResponse<List<DmlSrItem>> improvements(
            @Parameter(description = "기준월(yyyyMM). 미지정 시 이번 달.", example = "202607")
            @RequestParam(required = false) String baseYm,
            @Parameter(description = "팀장(01)/ADMIN 이 특정 파트를 볼 때만. 02/03 은 무시.", example = "P01")
            @RequestParam(required = false) String partCd) {
        String ym = resolveYm(baseYm);
        return wrap(ym, service.improvements(ym, partCd));
    }

    // ── 쓰기: 점검 ────────────────────────────────────────────────────
    @Operation(
        summary = "SR 점검 여부 저장 (Y/N)",
        description = """
                대상 SR 의 점검 여부(checkYn 'Y'/'N')를 저장한다(멱등 upsert). **인증(JWT) 필수**.

                **쓰기 권한(대상 SR 의 DEV_DEPT_CD/DEV_PART_CD 기준, fail-closed)**
                - 업무리더(02): **본인 파트** 건만(타파트 **403**). 팀장(01): **본인 부서** 건만(타부서 **403**).
                - ADMIN: 전체 허용. 일반직원(03)·기타: **403**. 대상 SR 미존재: **404**. checkYn ∉ {Y,N}: **400**.
                """
    )
    @PatchMapping("/{srNo}/check")
    public ApiResponse<Map<String, String>> check(
            @Parameter(description = "SR 번호", example = "T2607000001") @PathVariable String srNo,
            @RequestBody CheckRequest req) {
        service.setCheck(srNo, req.checkYn());
        return ApiResponse.of(Map.of("srNo", srNo, "checkYn", req.checkYn()));
    }

    // ── 쓰기: 개선대상여부 토글(점검 화면 → 개선건으로) ────────────────
    @Operation(
        summary = "SR 개선대상여부 토글 (Y/N)",
        description = """
                점검 화면에서 대상 SR 을 **개선건으로 올림/내림**(IMPROVE_YN 'Y'/'N', 멱등 upsert). \
                Y 로 올리면 ③ 개선 대상 목록에서 조회된다. **인증(JWT) 필수**.

                **쓰기 권한** — 점검(check)과 동일(02 본인 파트·01 본인 부서·ADMIN 전체, 03=403, 미존재=404, improveYn ∉ {Y,N}=400).
                """
    )
    @PatchMapping("/{srNo}/improve-target")
    public ApiResponse<Map<String, String>> improveTarget(
            @Parameter(description = "SR 번호", example = "T2607000001") @PathVariable String srNo,
            @RequestBody ImproveTargetRequest req) {
        service.setImproveTarget(srNo, req.improveYn());
        return ApiResponse.of(Map.of("srNo", srNo, "improveYn", req.improveYn()));
    }

    // ── 쓰기: 개선건 내용 ─────────────────────────────────────────────
    @Operation(
        summary = "SR 개선건 등록/갱신",
        description = """
                대상 SR 의 개선 계획(improvePlan)·완료예정일(planCmptDate, yyyyMMdd)·완료여부(cmptYn 'Y'/'N', 미지정 시 'N')·\
                비고(remark)를 등록/갱신한다(멱등 upsert, IMPROVE_YN='Y' 처리). **인증(JWT) 필수**.

                **쓰기 권한** — ②/점검과 동일(02 본인 파트·01 본인 부서·ADMIN 전체, 03=403, 미존재=404, cmptYn ∉ {Y,N}=400).
                """
    )
    @PutMapping("/{srNo}/improvement")
    public ApiResponse<Map<String, String>> improvement(
            @Parameter(description = "SR 번호", example = "T2607000008") @PathVariable String srNo,
            @RequestBody ImprovementRequest req) {
        service.saveImprovement(srNo, req.improvePlan(), req.planCmptDate(), req.cmptYn(), req.remark());
        return ApiResponse.of(Map.of("srNo", srNo, "improveYn", "Y"));
    }

    // ── 수동 동기화 ───────────────────────────────────────────────────
    @Operation(
        summary = "DML SR 수동 동기화 (팀장·ADMIN 전용)",
        description = """
                **팀장(01)·ADMIN 전용.** 기간계의 DML성 SR(유형 18/19)을 즉시 동기화한다 — 주간 배치를 기다리지 않는 \
                수동 실행/과거월 백필용. `baseYm`(yyyyMM) 미지정 시 이번 달. \
                기간계 read → HR 매칭 → DASH_DML_SR MERGE(멱등, 점검/개선 입력은 보존). \
                응답 data — { baseYm, fetched(기간계 조회 건수), matched(HR 매칭·저장 건수) }.
                """
    )
    @Auth(roles = {"01", "ADMIN"})
    @PostMapping("/sync")
    public ApiResponse<DmlSyncService.SyncResult> sync(
            @Parameter(description = "동기화 기준월(yyyyMM). 미지정 시 이번 달.", example = "202607")
            @RequestParam(required = false) String baseYm) {
        String ym = resolveYm(baseYm);
        return ApiResponse.of(syncService.sync(ym, "MANUAL"));
    }

    // ── 공통 ──────────────────────────────────────────────────────────
    private static String resolveYm(String baseYm) {
        return (baseYm == null || baseYm.isBlank()) ? LocalDate.now().format(YM) : baseYm;
    }

    private static ApiResponse<List<DmlSrItem>> wrap(String ym, DmlSrService.ListResult r) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("baseYm", ym);
        meta.put("scope", r.scope());
        meta.put("total", r.total());
        meta.put("checkedCount", r.checkedCount());
        meta.put("improveCount", r.improveCount());
        return ApiResponse.of(r.items(), meta);
    }

    private static final String EX_LIST = """
            {
              "data": [
                {
                  "srNo": "T2607000001", "baseYm": "202607", "srTpcd": "18", "srTpcdName": "데이타변경",
                  "bswrDetlName": "증권관리", "statusCode": "04", "titlCntt": "증권 잔고 정정",
                  "msgCntt": "계좌별 외국납부세액 잔고 정정 요청", "custInfoYn": "N",
                  "rqsrNm": "강지연", "rqsrDpcd": "5101", "trthRqstNm": "강지연", "trthRqstDpcd": "5101",
                  "picEmpno": "9320", "picNm": "김성엽", "picDpnm": "IT개발팀",
                  "devDeptCd": "2139", "devPartCd": "P01",
                  "regDate": "20260703", "rflcScdlDate": "20260720", "prosCmptDate": null,
                  "checkYn": "N", "improveYn": "N", "improvePlan": null, "planCmptDate": null,
                  "cmptYn": "N", "remark": null
                }
              ],
              "meta": { "baseYm": "202607", "scope": "all", "total": 10, "checkedCount": 0, "improveCount": 0 }
            }""";
}
