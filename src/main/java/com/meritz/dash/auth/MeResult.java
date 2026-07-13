package com.meritz.dash.auth;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "현재 사용자 정보(게이트웨이 인증)")
public record MeResult(
        @Schema(description = "사번", example = "7451") String empno,
        @Schema(description = "역할코드: 01 팀장 · 02 업무리더 · 03 일반직원 · ADMIN 관리자", example = "03") String role,
        @Schema(description = "역할명", example = "일반직원") String roleName,
        @Schema(description = "사용자명", example = "홍길동") String name,
        @Schema(description = "파트코드: P01 금융상품 · P02 계좌 · P03 MTS · P04 HTS · P05 출납 · P06 업무공통 · P07 해외주식 · P08 국내주식 · P09 본사후선 · P10 미지정 · P11 외주", example = "P01") String partCd) {}
