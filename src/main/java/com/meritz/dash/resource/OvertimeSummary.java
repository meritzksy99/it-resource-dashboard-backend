package com.meritz.dash.resource;

import java.util.List;

/**
 * 야근 조회 결과 — 스코프 내 사번별 야근시간 목록 + 스코프 인원당 평균 야근시간.
 * avgOvertimeHours = Σ OT_MINUTES ÷ 60 ÷ 스코프 재직 개발자 수 (소수 1자리 반올림, 분모 0 방어).
 */
public record OvertimeSummary(List<OvertimeView> list, double avgOvertimeHours) {}
