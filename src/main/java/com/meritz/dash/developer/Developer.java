package com.meritz.dash.developer;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "개발자 정보")
public record Developer(
        @Schema(description = "사번", example = "7451") String empno,
        @Schema(description = "이름", example = "홍길동") String empNm,
        @Schema(description = "부서코드: 2139 IT개발팀 · 2735 AI솔루션팀 · 2140 IT서비스팀", example = "2139") String deptCd,
        @Schema(description = "파트코드: P01 금융상품 · P02 계좌 · P03 MTS · P04 HTS · P05 출납 · P06 업무공통 · P07 해외주식 · P08 국내주식 · P09 본사후선 · P10 미지정 · P11 외주", example = "P01") String partCd,
        @Schema(description = "직급 (예: 사원 · 대리 · 과장 · 차장 · 부장)", example = "대리") String gradeCd,
        @Schema(description = "역할코드: 01 팀장 · 02 업무리더 · 03 일반직원", example = "03") String roleCd,
        @Schema(description = "개발자 여부 Y/N", example = "Y") String devYn,
        @Schema(description = "재직상태: 01 재직 · 02 휴직", example = "01") String statusCd) {}
