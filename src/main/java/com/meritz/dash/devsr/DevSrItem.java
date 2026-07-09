package com.meritz.dash.devsr;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 개발자 SR 한 건(상태 한글명·유형 한글명 보강, 계획 미수립이면 M/M·반영예정일 null).
 */
@Schema(description = "개발자별 실시간 SR 항목")
public record DevSrItem(
        @Schema(description = "담당 개발자 사번", example = "9320") String empno,
        @Schema(description = "담당 개발자 이름", example = "김성엽") String empNm,
        @Schema(description = "SR 번호", example = "SR26000001") String srNo,
        @Schema(description = "SR 제목", example = "차세대 계좌개설") String title,
        @Schema(description = "SR 내용", example = "계좌개설 화면 신규 개발 및 약관 연동") String content,
        @Schema(description = "SR 유형코드(SR_TPCD)", example = "01") String srTpcd,
        @Schema(description = "SR 유형명(CD_COMMON 보강)", example = "개발요청") String srTpcdName,
        @Schema(description = "SR 상태코드(SR_REG_STAT_CODE)", example = "04") String statusCode,
        @Schema(description = "SR 상태명(CD_COMMON 보강)", example = "SR진행") String statusName,
        @Schema(description = "계획 수립 여부(접수 등 미수립이면 false)", example = "true") boolean planEstablished,
        @Schema(description = "계획 M/M(투입공수). 계획 미수립이면 null", example = "1.0", nullable = true) Double jobMm,
        @Schema(description = "승인 작업시간 합(시간). 계획 미수립이면 null", example = "160.0", nullable = true) Double jobHours,
        @Schema(description = "반영예정일자(YYYYMMDD). 미정이면 null", example = "20260520", nullable = true) String rflcScdlDate) {}
