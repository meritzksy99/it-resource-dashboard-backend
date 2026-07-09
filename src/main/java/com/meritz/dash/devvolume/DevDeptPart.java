package com.meritz.dash.devvolume;

/**
 * 사번의 소속 부서/파트 (HR_DEVELOPER 조회 결과).
 * unit=dev 드릴다운에서 업무리더(02)의 "본인 파트원" 여부 판정에 사용한다.
 */
public record DevDeptPart(String deptCd, String partCd) {}
