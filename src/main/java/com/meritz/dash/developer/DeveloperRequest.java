package com.meritz.dash.developer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DeveloperRequest(
        @NotBlank @Schema(description = "사번", example = "9999") String empno,
        @NotBlank @Schema(description = "이름", example = "신입") String empNm,
        @Schema(description = "부서코드 (선택, 비우면 null): 2139 IT개발팀 · 2735 AI솔루션팀 · 2140 IT서비스팀", example = "2139") String deptCd,
        @Schema(description = "파트코드 (선택, 비우면 null): P01 금융상품 · P02 계좌 · P03 MTS · P04 HTS · P05 출납 · P06 업무공통 · P07 해외주식 · P08 국내주식 · P09 본사후선 · P10 미지정 · P11 외주", example = "P01") String partCd,
        @Schema(description = "직급코드 (선택, 비우면 null). 예: 사원 · 대리 · 과장 · 차장 · 부장", example = "사원") String gradeCd,
        @Schema(description = "역할코드 (선택, 비우면 null): 01 팀장 · 02 업무리더 · 03 일반직원", example = "03") String roleCd,
        @Pattern(regexp = "Y|N", message = "devYn은 Y 또는 N")
        @Schema(description = "개발자 여부 Y/N (기본 Y)", example = "Y") String devYn,
        @Pattern(regexp = "01|02", message = "statusCd는 01 또는 02")
        @Schema(description = "재직상태코드 (기본 01): 01 재직 · 02 휴직", example = "01") String statusCd) {

    public Developer toDeveloper() {
        return toDeveloper(this.empno);
    }

    /** update 시 URL 경로 empno로 덮어써서 생성. 바디의 empno 필드는 무시된다. */
    public Developer toDeveloper(String overrideEmpno) {
        return new Developer(overrideEmpno, empNm, deptCd, partCd, gradeCd, roleCd,
                devYn == null ? "Y" : devYn,
                statusCd == null ? "01" : statusCd);
    }
}
