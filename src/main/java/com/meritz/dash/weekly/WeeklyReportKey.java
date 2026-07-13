package com.meritz.dash.weekly;

/** rptIds 선택 검증용 개인 보고 키(존재/주차/소속 파트 확인 — 매퍼 resultType). */
public record WeeklyReportKey(
        Long rptId,
        String weekYmd,
        String deptCd,
        String partCd) {
}
