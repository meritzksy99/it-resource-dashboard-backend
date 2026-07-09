package com.meritz.dash.auth;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "관리자 계정 현황 행")
public record AdminAccountRow(
        @Schema(description = "사번", example = "9320") String empno,
        @Schema(description = "사용자명", example = "홍길동") String name,
        @Schema(description = "상태코드: 00 정상 · 01 잠금 · 02 휴면", example = "00") String statusCd,
        @Schema(description = "상태명", example = "정상") String statusName,
        @Schema(description = "로그인 실패 횟수", example = "0") Integer failCnt,
        @Schema(description = "마지막 로그인 일시") LocalDateTime lastLoginAt,
        @Schema(description = "마지막 비밀번호 변경 일시") LocalDateTime passwordChangedAt,
        @Schema(description = "비밀번호 만료 여부(90일 초과)", example = "false") boolean expired,
        @Schema(description = "휴면 대상 여부(3개월 미로그인)", example = "false") boolean dormant) {}
