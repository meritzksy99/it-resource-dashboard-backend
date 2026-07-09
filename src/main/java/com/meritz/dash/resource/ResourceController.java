package com.meritz.dash.resource;

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

@Tag(name = "Resource", description = "가동률 및 야근 현황")
@RestController
@RequestMapping("/api/v1/resource")
public class ResourceController {

    private final ResourceService service;

    public ResourceController(ResourceService service) {
        this.service = service;
    }

    @Operation(
        summary = "가동률(단위별·기간 범위 · 여러 달 조회)",
        description = """
                **무엇을 하는 API인가** — 지정한 **기간 범위(from~to)의 월별 가동률·야근 현황**을 periodYm 오름차순 배열로 반환한다(라인/막대 차트용). \
                사내 집계 테이블(DB2, DASH_RESOURCE)에서 읽는다. 인증(JWT) 필수.

                **입력 방식 2가지**
                - **기간 범위(권장)**: `from`=202601 & `to`=202606 → 6개월치 월별 배열. from/to는 **반드시 함께** 지정.
                - **단일 월(하위호환)**: `period`=202606 → 해당 월 1건 배열. from/to가 있으면 period보다 **우선**한다.
                - period·from·to를 **모두 생략하면 400**.

                **집계 단위** — `unit`(기본 all): all=전사(unitId 불필요) · dept=부서(unitId=부서코드 2139) · part=부서-파트(unitId='부서코드-파트코드' 2139-P01).

                **각 요소** — 인원(headcount·availHeadcount) / 가용·사용·야근 M/M(availMm·usedMm·overtimeMm) / 가동률 `utilization = usedMm ÷ availMm`(1.0 초과 = 과부하). \
                **meta** — `{ from, to, unit, unitId, count }`(서비스가 확정한 유효 구간).
                """
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공. from~to면 월별 배열, period면 단일월 1건.",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "① 기간 범위 조회 (from=202601&to=202606, unit=dept)", value = EX_RANGE),
                            @ExampleObject(name = "② 단일 월 (period=202606, unit=all)", value = EX_SINGLE),
                            @ExampleObject(name = "③ 파트 단위 (unit=part&unitId=2139-P01)", value = EX_PART)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "period·from·to 모두 누락 또는 형식 오류",
                    content = @Content(mediaType = "application/json", examples =
                            @ExampleObject(name = "기간 미입력", value = EX_R400)))
    })
    @GetMapping
    public ApiResponse<List<ResourceView>> resource(
            @Parameter(description = "단일 조회 월 (YYYYMM). from/to 없을 때 사용(하위호환).",
                    examples = @ExampleObject(name = "단일월", value = "202606"))
            @RequestParam(required = false) String period,
            @Parameter(description = "조회 시작 월 (YYYYMM). to와 함께 지정.",
                    examples = @ExampleObject(name = "연초", value = "202601"))
            @RequestParam(required = false) String from,
            @Parameter(description = "조회 종료 월 (YYYYMM). from과 함께 지정.",
                    examples = @ExampleObject(name = "당월", value = "202606"))
            @RequestParam(required = false) String to,
            @Parameter(description = "집계 단위. all=전사, dept=부서, part=부서-파트",
                    examples = {
                            @ExampleObject(name = "전사(기본)", value = "all"),
                            @ExampleObject(name = "부서", value = "dept"),
                            @ExampleObject(name = "부서-파트", value = "part")
                    })
            @RequestParam(defaultValue = "all") String unit,
            @Parameter(description = "단위 식별자. dept이면 부서코드(2139), part이면 '부서코드-파트코드'(2139-P01). all이면 불필요.",
                    examples = {
                            @ExampleObject(name = "부서코드", value = "2139"),
                            @ExampleObject(name = "부서-파트코드", value = "2139-P01")
                    })
            @RequestParam(required = false) String unitId) {

        // period·from·to 모두 없으면 400
        if ((period == null || period.isBlank())
                && (from == null || from.isBlank())
                && (to   == null || to.isBlank())) {
            throw new IllegalArgumentException("period 또는 from·to 중 하나는 반드시 입력해야 합니다");
        }

        ResourceRangeResult result = service.unitRange(period, from, to, unit, unitId);

        // meta 구성 — 서비스가 결정한 유효 구간을 그대로 사용
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("from",   result.from());
        meta.put("to",     result.to());
        meta.put("unit",   result.unitType());
        meta.put("unitId", result.unitId());
        meta.put("count",  result.items().size());
        return ApiResponse.of(result.items(), meta);
    }

    @Operation(
        summary = "개발자별 가용률(가동률) — 전체 또는 특정 개발자",
        description = """
                **무엇을 하는 API인가** — 지정한 월의 **재직 개발자(DEV_YN='Y')별 가용률**을 반환한다. \
                사내 집계 테이블(DASH_DEV_AGG)의 계획공수(JOB_MM)를 개발자별로 합산해 usedMm 로 쓴다. **인증(JWT) 필수**.

                **권한(역할별 자동 스코핑)** — `/overtime`과 동일 정책. ADMIN=전체 · 팀장(01)=본인 부서 · 업무리더(02)=본인 파트 · 일반직원(03)=본인만. \
                `empno` 파라미터는 **ADMIN만** 유효(비ADMIN은 무시되고 본인/소속으로 강제, fail-closed).

                **입력(기본값)**
                - `period`(필수, YYYYMM): 조회 월. 형식 오류/누락 시 **400**.
                - `empno`(선택, ADMIN 전용): **비우면 (권한 범위 내) 전체**, 값을 주면 **해당 개발자 1명**만.

                **출력(각 요소)** — `empno`·`empNm`·`deptCd`·`partCd`(부서/파트 미지정 시 '미분류') / \
                `availMm`(=1.0, 개발가능 1인) / `usedMm`(계획공수 합) / `utilization`(=usedMm÷availMm, **1.0 초과=과부하**). \
                해당 월 SR이 없는 개발자도 `usedMm=0`·`utilization=0` 으로 포함된다. `usedMm` **내림차순 정렬**. \
                **meta** — `{ period, count }`.
                """
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공. 개발자별 가용률 배열(usedMm 내림차순).",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "① 전체 개발자(empno 생략)", value = EX_DEV_ALL),
                            @ExampleObject(name = "② 특정 개발자(empno=7451)", value = EX_DEV_ONE)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "period 누락 또는 YYYYMM 형식 오류",
                    content = @Content(mediaType = "application/json", examples =
                            @ExampleObject(name = "period 형식 오류", value = EX_DEV_400)))
    })
    @GetMapping("/developers")
    public ApiResponse<List<DeveloperUtilView>> developers(
            @Parameter(description = "조회 기간 (YYYYMM). 필수.",
                    examples = @ExampleObject(name = "당월", value = "202606"))
            @RequestParam String period,
            @Parameter(description = "개발자 사번. 비우면 전체 개발자, 값을 주면 해당 개발자만. (기본값 없음=전체)",
                    examples = @ExampleObject(name = "특정 개발자", value = "7451"))
            @RequestParam(required = false) String empno) {
        List<DeveloperUtilView> list = service.developerUtil(period, empno);
        return ApiResponse.of(list, Map.of("period", period, "count", list.size()));
    }

    @Operation(
        summary = "야근 상세(개발자별 야근시간 — 엑셀 업로드 실적)",
        description = """
                **무엇을 하는 API인가** — 지정한 월의 **사번별 야근시간**을 반환한다. \
                원천은 근태 엑셀 업로드 테이블 **HR_OVERTIME**(`POST /api/v1/overtime/uploads` 로 적재)이며, \
                계획 M/M 파생값이 아닌 **실제 야근 실적(분)** 이다. 인증(JWT) 필수.

                **권한(역할별 자동 스코핑, fail-closed)** — 토큰의 역할에 따라 조회 범위가 **자동으로 제한**된다. \
                일반직원(03)=**본인만** · 업무리더(02)=**본인 파트** · 팀장(01)=**본인 부서** · ADMIN=**전체**(+`dept`/`part` 필터 가능). \
                비ADMIN이 보낸 `dept`/`part`는 **무시**되고, 스코프를 못 정하는 경우 본인으로 강제된다(fail-closed).

                **입력**
                - `period`(필수, YYYYMM): 조회 월. 누락/형식 오류 시 **400**.
                - `dept`(선택, **ADMIN 전용**): 부서코드 필터. 비ADMIN은 무시.
                - `part`(선택, **ADMIN 전용**): 파트코드 필터. **반드시 dept와 함께** — dept 없이 단독 사용 시 **400**.

                **출력(각 요소)** — `empno`·`empNm`·`partCd`(미지정 시 '미분류') / \
                `otMinutes`(야근 총 분 = 평일연장+평일야간+휴일연장+휴일야간, 업로드 원본) / \
                `overtimeHours`(= otMinutes ÷ 60, 소수 1자리 반올림). otMinutes **내림차순 정렬**. \
                **meta** — `{ period, count, avgOvertimeHours }` \
                (avgOvertimeHours = Σ otMinutes ÷ 60 ÷ **스코프 재직 개발자 수** — 야근자 수 아님, 분모 0 방어).
                """
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공. 사번별 야근시간 배열(otMinutes 내림차순) + 스코프 평균.",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "① 전체 조회 (ADMIN, 필터 없음)", value = EX_OT_ALL),
                            @ExampleObject(name = "② 부서 필터 (ADMIN, dept=2139)", value = EX_OT),
                            @ExampleObject(name = "③ 본인만 (일반직원 03 — 파라미터 무관 자동 스코핑)", value = EX_OT_SELF)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "period 누락/형식 오류 또는 part 단독(dept 없이) 사용",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "period 형식 오류", value = EX_OT_400),
                            @ExampleObject(name = "part 단독 사용(dept 누락)", value = EX_OT_400_PART)
                    }))
    })
    @GetMapping("/overtime")
    public ApiResponse<List<OvertimeView>> overtime(
            @Parameter(description = "조회 기간 (YYYYMM). 필수 — 누락/형식 오류 시 400.", example = "202606")
            @RequestParam String period,
            @Parameter(description = "부서코드 필터. ADMIN 전용 — 비ADMIN이 보내면 무시됨(fail-closed).", example = "2139")
            @RequestParam(required = false) String dept,
            @Parameter(description = "파트코드 필터. ADMIN 전용 · 반드시 dept와 함께 사용 — dept 없이 단독 사용 시 400. 비ADMIN은 무시됨.", example = "P01")
            @RequestParam(required = false) String part) {
        OvertimeSummary summary = service.overtimeSummary(period, dept, part);
        return ApiResponse.of(summary.list(),
                Map.of("period", period,
                       "count", summary.list().size(),
                       "avgOvertimeHours", summary.avgOvertimeHours()));
    }

    // ── Swagger 응답 예시(테스트 케이스별 인풋/아웃풋) ─────────────────────────────
    private static final String EX_RANGE = """
            {
              "data": [
                { "periodYm": "202601", "unitType": "DEPT", "unitId": "2139", "headcount": 5, "availHeadcount": 4, "availMm": 4.0, "usedMm": 2.16, "overtimeMm": 0.0,  "utilization": 0.54 },
                { "periodYm": "202602", "unitType": "DEPT", "unitId": "2139", "headcount": 5, "availHeadcount": 4, "availMm": 4.0, "usedMm": 2.26, "overtimeMm": 0.0,  "utilization": 0.565 },
                { "periodYm": "202603", "unitType": "DEPT", "unitId": "2139", "headcount": 5, "availHeadcount": 4, "availMm": 4.0, "usedMm": 2.70, "overtimeMm": 0.08, "utilization": 0.675 },
                { "periodYm": "202604", "unitType": "DEPT", "unitId": "2139", "headcount": 5, "availHeadcount": 4, "availMm": 4.0, "usedMm": 3.17, "overtimeMm": 0.27, "utilization": 0.793 },
                { "periodYm": "202605", "unitType": "DEPT", "unitId": "2139", "headcount": 5, "availHeadcount": 4, "availMm": 4.0, "usedMm": 3.50, "overtimeMm": 0.40, "utilization": 0.875 },
                { "periodYm": "202606", "unitType": "DEPT", "unitId": "2139", "headcount": 5, "availHeadcount": 4, "availMm": 4.0, "usedMm": 4.30, "overtimeMm": 0.60, "utilization": 1.075 }
              ],
              "meta": { "from": "202601", "to": "202606", "unit": "DEPT", "unitId": "2139", "count": 6 }
            }""";

    private static final String EX_SINGLE = """
            {
              "data": [
                { "periodYm": "202606", "unitType": "ALL", "unitId": "ALL", "headcount": 6, "availHeadcount": 5, "availMm": 5.0, "usedMm": 5.34, "overtimeMm": 0.60, "utilization": 1.068 }
              ],
              "meta": { "from": "202606", "to": "202606", "unit": "ALL", "unitId": "ALL", "count": 1 }
            }""";

    private static final String EX_PART = """
            {
              "data": [
                { "periodYm": "202606", "unitType": "PART", "unitId": "2139-P01", "headcount": 3, "availHeadcount": 3, "availMm": 3.0, "usedMm": 2.36, "overtimeMm": 0.10, "utilization": 0.787 }
              ],
              "meta": { "from": "202606", "to": "202606", "unit": "PART", "unitId": "2139-P01", "count": 1 }
            }""";

    private static final String EX_R400 = """
            {
              "type": "about:blank", "title": "Bad Request", "status": 400,
              "detail": "period 또는 from·to 중 하나는 반드시 입력해야 합니다",
              "instance": "/api/v1/resource"
            }""";

    // ── /developers 응답 예시 ────────────────────────────────────────────
    private static final String EX_DEV_ALL = """
            {
              "data": [
                { "empno": "7451", "empNm": "홍길동", "deptCd": "2139", "partCd": "P01", "availMm": 1.0, "usedMm": 1.2,  "utilization": 1.2 },
                { "empno": "7452", "empNm": "김철수", "deptCd": "2139", "partCd": "P02", "availMm": 1.0, "usedMm": 0.85, "utilization": 0.85 },
                { "empno": "7453", "empNm": "이영희", "deptCd": "2139", "partCd": "P01", "availMm": 1.0, "usedMm": 0.0,  "utilization": 0.0 }
              ],
              "meta": { "period": "202606", "count": 3 }
            }""";

    private static final String EX_DEV_ONE = """
            {
              "data": [
                { "empno": "7451", "empNm": "홍길동", "deptCd": "2139", "partCd": "P01", "availMm": 1.0, "usedMm": 1.2, "utilization": 1.2 }
              ],
              "meta": { "period": "202606", "count": 1 }
            }""";

    private static final String EX_DEV_400 = """
            {
              "type": "about:blank", "title": "Bad Request", "status": 400,
              "detail": "period는 YYYYMM 6자리 숫자여야 합니다: 2026",
              "instance": "/api/v1/resource/developers"
            }""";

    // ── /overtime 응답 예시 ─────────────────────────────────────────────
    private static final String EX_OT_ALL = """
            {
              "data": [
                { "empno": "9320", "empNm": "김성엽", "partCd": "P01",    "otMinutes": 774, "overtimeHours": 12.9 },
                { "empno": "7454", "empNm": "박민수", "partCd": "P03",    "otMinutes": 420, "overtimeHours": 7.0 },
                { "empno": "7452", "empNm": "김철수", "partCd": "P02",    "otMinutes": 300, "overtimeHours": 5.0 },
                { "empno": "7455", "empNm": "최지원", "partCd": "미분류", "otMinutes": 90,  "overtimeHours": 1.5 },
                { "empno": "7453", "empNm": "이영희", "partCd": "P01",    "otMinutes": 0,   "overtimeHours": 0.0 }
              ],
              "meta": { "period": "202606", "count": 5, "avgOvertimeHours": 5.3 }
            }""";

    private static final String EX_OT = """
            {
              "data": [
                { "empno": "9320", "empNm": "김성엽", "partCd": "P01", "otMinutes": 774, "overtimeHours": 12.9 },
                { "empno": "7452", "empNm": "김철수", "partCd": "P02", "otMinutes": 300, "overtimeHours": 5.0 },
                { "empno": "7453", "empNm": "이영희", "partCd": "P01", "otMinutes": 0,   "overtimeHours": 0.0 }
              ],
              "meta": { "period": "202606", "count": 3, "avgOvertimeHours": 4.5 }
            }""";

    private static final String EX_OT_SELF = """
            {
              "data": [
                { "empno": "7453", "empNm": "이영희", "partCd": "P01", "otMinutes": 180, "overtimeHours": 3.0 }
              ],
              "meta": { "period": "202606", "count": 1, "avgOvertimeHours": 3.0 }
            }""";

    private static final String EX_OT_400 = """
            {
              "type": "about:blank", "title": "Bad Request", "status": 400,
              "detail": "period는 YYYYMM 6자리 숫자여야 합니다: 2026",
              "instance": "/api/v1/resource/overtime"
            }""";

    private static final String EX_OT_400_PART = """
            {
              "type": "about:blank", "title": "Bad Request", "status": 400,
              "detail": "part 조회에는 dept가 필요합니다",
              "instance": "/api/v1/resource/overtime"
            }""";
}
