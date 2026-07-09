package com.meritz.dash.developer;

import com.meritz.dash.auth.Auth;
import com.meritz.dash.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Developers", description = "인력(개발자) 관리")
@RestController
@RequestMapping("/api/v1/developers")
public class DeveloperController {

    private final DeveloperService service;

    public DeveloperController(DeveloperService service) {
        this.service = service;
    }

    @Operation(summary = "인력 목록 조회", description = "인증 불필요. dept=부서코드 2139 · part=파트코드 P01 · devYn · status 로 필터링. 파라미터 미입력 시 전체 조회.")
    @GetMapping
    public ApiResponse<List<Developer>> list(
            @Parameter(description = "부서코드 (DEPT_CD). 예: 2139=IT개발팀", example = "2139")
            @RequestParam(name = "dept", required = false) String deptCd,
            @Parameter(description = "파트코드 (PART_CD). 예: P01=앱개발파트", example = "P01")
            @RequestParam(name = "part", required = false) String partCd,
            @Parameter(description = "개발자여부 Y/N. Y=개발자만, N=비개발자만", example = "Y")
            @RequestParam(name = "devYn", required = false) String devYn,
            @Parameter(description = "재직상태코드. 01=재직, 02=휴직", example = "01")
            @RequestParam(name = "status", required = false) String statusCd) {
        List<Developer> devs = service.list(deptCd, partCd, devYn, statusCd);
        return ApiResponse.of(devs, Map.of("count", devs.size()));
    }

    @Operation(summary = "인력 단건 조회", description = "인증 불필요. 사번으로 특정 개발자 정보를 조회한다.")
    @GetMapping("/{empno}")
    public ApiResponse<Developer> get(
            @Parameter(description = "사번", example = "5355") @PathVariable String empno) {
        return ApiResponse.of(service.get(empno));
    }

    @Auth(roles = {"01", "ADMIN"})
    @Operation(summary = "인력 등록", description = "팀장·ADMIN 전용. 새 인력을 등록한다(201). empno는 신규 사번.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Developer> create(@RequestBody @Valid DeveloperRequest req) {
        return ApiResponse.of(service.create(req));
    }

    @Auth(roles = {"01", "ADMIN"})
    @Operation(summary = "인력 수정", description = "팀장·ADMIN 전용. 사번으로 특정 인력 정보를 수정한다. 바디의 empno는 무시되고 경로 empno가 사용된다.")
    @PutMapping("/{empno}")
    public ApiResponse<Developer> update(
            @Parameter(description = "사번", example = "5355") @PathVariable String empno,
            @RequestBody @Valid DeveloperRequest req) {
        return ApiResponse.of(service.update(empno, req));
    }

    @Auth(roles = {"01", "ADMIN"})
    @Operation(summary = "인력 삭제", description = "팀장·ADMIN 전용. 사번으로 인력을 삭제한다(204).")
    @DeleteMapping("/{empno}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "사번", example = "5355") @PathVariable String empno) {
        service.delete(empno);
    }
}
