package com.meritz.dash.devsr;

import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "DevSr", description = "개발자별 실시간 SR 현황(기간계 조회)")
@RestController
@RequestMapping("/api/v1/dev-srs")
public class DevSrController {

    private final DevSrService service;

    public DevSrController(DevSrService service) {
        this.service = service;
    }

    @Operation(
        summary = "개발자별 실시간 SR 현황 (상태별 그룹)",
        description = """
                **무엇을 하는 API인가** — 개발자의 **현재 진행중 SR** 을 **기간계(TBCPPE091M00/093L00)에서 실시간 조회**해 \
                **상태(SR_REG_STAT_CODE)별로 묶어** 반환한다. 상태·유형의 한글명은 사내 공통코드(CD_COMMON)로 보강한다. **인증(JWT) 필수**.

                **어떤 SR이 나오나** — 두 갈래.
                - **계획 수립 SR**: 담당자(작업이력 093.SPIC_EMPNO, 승인건)의 SR. 계획 M/M·작업시간·반영예정일 포함(planEstablished=true). \
                진행중 상태만(SR등록'02'·배포'07'·종료'08'·취소류 제외).
                - **SR등록('02', 계획 미수립)**: 신청자(091.PRCH_EMPNO)의 SR. 아직 계획이 없어 jobMm·jobHours·반영예정일은 **null**(planEstablished=false).

                **권한(역할별 조회 범위)** — `empno` 파라미터로 특정 개발자를 지정하되, 볼 수 있는 범위는 역할이 정한다.
                - 일반직원(03)·기타: **본인 SR 만**(empno 를 줘도 무시하고 본인).
                - 업무리더(02): **본인 파트 소속** 직원. empno 미지정 시 파트원 전체, 지정 시 그 직원(**파트 밖이면 403**).
                - 팀장(01): **본인 부서 소속** 직원. empno 미지정 시 부서 전체, 지정 시 그 직원(**부서 밖이면 403**).
                - ADMIN: **전 직원**. empno 미지정 시 전체, 지정 시 그 직원.

                **입력** — `empno`(선택): 조회 대상 개발자 사번. 미지정이면 역할 스코프 전체.

                **각 SR 항목** — 소유자(empno·empNm) / SR번호(srNo) / 제목(title) / 내용(content) / 유형(srTpcd·srTpcdName) / \
                상태(statusCode·statusName) / 계획수립여부(planEstablished) / 계획 M/M(jobMm) / 승인 작업시간(jobHours) / 반영예정일(rflcScdlDate). \
                **SR등록('02', 계획 미수립)** SR 은 planEstablished=false 이며 jobMm·jobHours·rflcScdlDate 는 **null**.

                **응답 구조** — data=상태별 그룹 배열(statusCode 오름차순, 각 그룹 { statusCode, statusName, count, srs[] }). \
                **meta** — { scope(self·part·dept·all·*-one), developerCount(조회 대상 개발자 수), totalSrs(총 SR 건수), truncated(스코프 상한 초과 절단 여부) }.
                """
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공. 상태별 그룹 배열.",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "① 본인 SR (일반직원 03, empno 무시)", value = EX_SELF),
                            @ExampleObject(name = "② 특정 개발자 지정 (업무리더 02, empno=9320)", value = EX_ONE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "스코프 밖 사번 지정(업무리더=타파트, 팀장=타부서).",
                    content = @Content(mediaType = "application/json", examples =
                            @ExampleObject(name = "권한 없음", value = EX_403)))
    })
    @GetMapping
    public ApiResponse<List<SrStatusGroup>> devSrs(
            @Parameter(description = "조회 대상 개발자 사번. 미지정 시 역할 스코프 전체(03은 항상 본인).", example = "9320")
            @RequestParam(required = false) String empno) {
        DevSrService.Result r = service.developerSrs(empno);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scope", r.scope());
        meta.put("developerCount", r.developerCount());
        meta.put("totalSrs", r.totalSrs());
        meta.put("truncated", r.truncated());
        return ApiResponse.of(r.groups(), meta);
    }

    private static final String EX_SELF = """
            {
              "data": [
                {
                  "statusCode": "02", "statusName": "SR등록", "count": 1,
                  "srs": [
                    { "empno": "7451", "empNm": "이개발", "srNo": "SR26000010", "title": "약관 개정 반영",
                      "content": "약관 문구 변경 요청 접수", "srTpcd": "02", "srTpcdName": "유지보수",
                      "statusCode": "02", "statusName": "SR등록",
                      "planEstablished": false, "jobMm": null, "jobHours": null, "rflcScdlDate": null }
                  ]
                },
                {
                  "statusCode": "04", "statusName": "SR진행", "count": 1,
                  "srs": [
                    { "empno": "7451", "empNm": "이개발", "srNo": "SR26000001", "title": "차세대 계좌개설",
                      "content": "계좌개설 화면 신규 개발 및 약관 연동", "srTpcd": "01", "srTpcdName": "개발요청",
                      "statusCode": "04", "statusName": "SR진행",
                      "planEstablished": true, "jobMm": 1.0, "jobHours": 166.0, "rflcScdlDate": "20260520" }
                  ]
                }
              ],
              "meta": { "scope": "self", "developerCount": 1, "totalSrs": 2, "truncated": false }
            }""";

    private static final String EX_ONE = """
            {
              "data": [
                {
                  "statusCode": "04", "statusName": "SR진행", "count": 1,
                  "srs": [
                    { "empno": "9320", "empNm": "김성엽", "srNo": "SR26000007", "title": "정산 배치 개선",
                      "content": "일마감 정산 배치 성능 개선", "srTpcd": "01", "srTpcdName": "개발요청",
                      "statusCode": "04", "statusName": "SR진행",
                      "planEstablished": true, "jobMm": 0.96, "jobHours": 160.0, "rflcScdlDate": "20260630" }
                  ]
                }
              ],
              "meta": { "scope": "part-one", "developerCount": 1, "totalSrs": 1, "truncated": false }
            }""";

    private static final String EX_403 = """
            {
              "type": "about:blank",
              "title": "Forbidden",
              "status": 403,
              "detail": "본인 파트 소속 직원만 조회할 수 있습니다"
            }""";
}
