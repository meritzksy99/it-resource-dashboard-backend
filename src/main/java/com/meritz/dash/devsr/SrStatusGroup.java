package com.meritz.dash.devsr;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * SR 상태별 그룹 — "현재 상태별로 어떤 SR 이 있는지" 보여주기 위한 묶음.
 */
@Schema(description = "SR 상태별 묶음")
public record SrStatusGroup(
        @Schema(description = "SR 상태코드", example = "04") String statusCode,
        @Schema(description = "SR 상태명(CD_COMMON 보강)", example = "SR진행") String statusName,
        @Schema(description = "이 상태의 SR 건수", example = "3") int count,
        @Schema(description = "이 상태의 SR 목록") List<DevSrItem> srs) {}
