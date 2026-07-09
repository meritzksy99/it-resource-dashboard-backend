package com.meritz.dash.resource;

/** findDeveloperUtil 매퍼 결과 행 (HR_DEVELOPER ⨝ DASH_DEV_AGG). */
public record DeveloperUtilRow(
        String empno,
        String empNm,
        String deptCd,
        String partCd,
        String devYn,
        double usedMm) {}
