package com.meritz.dash.overtime;

/** 야근 엑셀 한 행 파싱 결과 — 사번 + 그 달 야근 총 분(평일연장+평일야간+휴일연장+휴일야간). */
public record OvertimeEntry(String empno, int otMinutes) {}
