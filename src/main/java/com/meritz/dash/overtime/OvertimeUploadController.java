package com.meritz.dash.overtime;

import com.meritz.dash.auth.Auth;
import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Tag(name = "Overtime", description = "야근시간 엑셀 업로드")
@RestController
@RequestMapping("/api/v1/overtime")
public class OvertimeUploadController {

    private final OvertimeUploadService service;

    public OvertimeUploadController(OvertimeUploadService service) {
        this.service = service;
    }

    @Operation(
        summary = "야근시간 엑셀 업로드 (팀장/ADMIN 전용)",
        description = """
                **무엇을 하는 API인가** — 근태 시스템에서 내려받은 **야근양식 엑셀(.xlsx)** 을 업로드해 \
                해당 월(period)의 사번별 야근시간(분)을 **HR_OVERTIME** 에 **멱등 저장**한다 \
                (같은 period 재업로드 시 그 달 기존 행을 전부 **DELETE 후 재삽입** — 재실행 안전, 단일 트랜잭션). \
                저장된 데이터는 `GET /api/v1/resource/overtime` 야근 조회의 원천이 된다.

                **권한** — 팀장(01) 또는 ADMIN 만 호출 가능(그 외 403, `@Auth`). **인증(JWT) 필수**.

                **입력(multipart/form-data)**
                - `period`(필수): 대상 월 YYYYMM (예: 202606). 형식 오류 시 **400**.
                - `file`(필수): 야근양식 .xlsx. **1행 헤더(A열='사번' 필수 — 아니면 양식 오류로 400)**, 2행부터 데이터. \
                야근 분 = **평일연장(J)+평일야간(L)+휴일연장(N)+휴일야간(P)** 4개 '분' 컬럼의 합. \
                빈/누락 분 컬럼은 0, 사번 없는 행·합계행은 스킵, 같은 사번 중복 행은 합산.

                **400이 나는 경우** — period 형식 오류 / 파일 누락 / **.xlsx 아님** / 파싱 실패 / \
                **유효한 야근 데이터 행 0건**(이때 기존 월 데이터는 삭제되지 않고 그대로 보존). \
                에러는 RFC 7807 ProblemDetail 형식.

                **출력(200)** — data=`{ "saved": 저장된 사번 수 }`, meta=`{ "period": 대상 월 }`.
                """
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "성공. saved=저장된 사번 수(그 달 기존 행 삭제 후 재삽입).",
                    content = @Content(mediaType = "application/json", examples =
                            @ExampleObject(name = "업로드 성공 (period=202606, 20명 저장)", value = EX_OK))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "period 형식 오류 / 파일 누락 / .xlsx 아님 / 파싱 실패 / 유효 데이터 0건 (RFC 7807 ProblemDetail)",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "① .xlsx 아님", value = EX_400_NOT_XLSX),
                            @ExampleObject(name = "② 유효한 야근 데이터 행 없음(양식 불일치/빈 파일)", value = EX_400_NO_ROWS),
                            @ExampleObject(name = "③ period 형식 오류", value = EX_400_PERIOD)
                    })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403", description = "팀장(01)/ADMIN 아님",
                    content = @Content(mediaType = "application/json"))
    })
    @Auth(roles = {"01", "ADMIN"})
    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> upload(
            @Parameter(description = "대상 월 (YYYYMM). 필수. 이 달의 기존 야근 데이터가 삭제 후 재삽입된다.",
                    examples = @ExampleObject(name = "당월", value = "202606"))
            @RequestParam("period") String period,
            @Parameter(description = "야근양식 엑셀(.xlsx). 필수. 1행 헤더(A열='사번'), J·L·N·P='분' 컬럼.")
            @RequestPart("file") MultipartFile file) {
        int saved = service.upload(period, file);
        return ApiResponse.of(Map.of("saved", saved), Map.of("period", period));
    }

    // ── Swagger 예시(테스트 케이스별 인풋/아웃풋) ─────────────────────────────
    private static final String EX_OK = """
            {
              "data": { "saved": 20 },
              "meta": { "period": "202606" }
            }""";

    private static final String EX_400_NOT_XLSX = """
            {
              "type": "about:blank", "title": "Bad Request", "status": 400,
              "detail": ".xlsx 파일만 업로드할 수 있습니다",
              "instance": "/api/v1/overtime/uploads"
            }""";

    private static final String EX_400_NO_ROWS = """
            {
              "type": "about:blank", "title": "Bad Request", "status": 400,
              "detail": "유효한 야근 데이터 행이 없습니다(양식/내용을 확인하세요)",
              "instance": "/api/v1/overtime/uploads"
            }""";

    private static final String EX_400_PERIOD = """
            {
              "type": "about:blank", "title": "Bad Request", "status": 400,
              "detail": "period는 YYYYMM 6자리 숫자여야 합니다: 2026",
              "instance": "/api/v1/overtime/uploads"
            }""";
}
