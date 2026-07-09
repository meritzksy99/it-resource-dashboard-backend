package com.meritz.dash.resource;

/** HR_OVERTIME ⨝ HR_DEVELOPER 매퍼 결과 행 — 사번별 야근 총 분(업로드 원본). */
public record OvertimeRow(String empno, String empNm, String partCd, int otMinutes) {}
