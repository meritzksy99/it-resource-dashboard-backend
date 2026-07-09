package com.meritz.dash.srproject;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "SR 프로젝트 요약 (계획 M/M 기준 내림차순)")
public record SrProjectView(
        @Schema(description = "SR 번호", example = "SR-2026-0042") String srNo,
        @Schema(description = "SR 제목", example = "MTS 신규 화면 개발") String titlCntt,
        @Schema(description = "SR 유형코드", example = "01") String srTpcd,
        @Schema(description = "SR 유형명", example = "개발요청") String srTpcdName,
        @Schema(description = "총 계획 M/M (공수 합계, 투입시간 ÷ 166)", example = "3.5") double totMm,
        @Schema(description = "투입 인원수", example = "4") int empCnt,
        @Schema(description = "처리(IT) 부서코드: 2139 IT개발팀 · 2735 AI솔루션팀 · 2140 IT서비스팀", example = "2139") String prchDpcd,
        @Schema(description = "요청 부서코드", example = "1001") String dpcd) {}
