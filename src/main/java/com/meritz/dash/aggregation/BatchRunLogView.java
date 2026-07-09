package com.meritz.dash.aggregation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * BATCH_RUN_LOG 이력 조회용 뷰 레코드.
 * trigType 필드는 DB 컬럼명 TRIG_TYPE 을 AS trigType 으로 별칭한 결과를 받는다.
 * ("trigger"는 Oracle 예약어이므로 trigType 을 사용한다.)
 */
@Schema(description = "배치 실행 로그")
public record BatchRunLogView(
        @Schema(description = "실행 ID (자동 증가)", example = "42") long runId,
        @Schema(description = "집계 대상 월 (YYYYMM)", example = "202606") String periodYm,
        @Schema(description = "실행 유형: SCHEDULED 자동 · MANUAL 수동", example = "MANUAL") String trigType,
        @Schema(description = "실행 결과: OK · FAIL · RUNNING", example = "OK") String status,
        @Schema(description = "적재된 개발집계(DEV_AGG) 행 수", example = "120") int devRows,
        @Schema(description = "적재된 SR 집계(SR_PROJECT) 행 수", example = "35") int srRows,
        @Schema(description = "배치 시작 일시 (ISO-8601)", example = "2026-06-01T02:00:00") String startedAt,
        @Schema(description = "배치 종료 일시 (ISO-8601). 실행 중이면 null", example = "2026-06-01T02:00:12") String finishedAt,
        @Schema(description = "오류 메시지 (정상 시 null)", example = "null") String msg
) {}
