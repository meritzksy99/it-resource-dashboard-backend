package com.meritz.dash.weekly;

/** 기간계 SR 참조 스냅샷(TBCPPE091M00 단건: SR_NO/TITL_CNTT/RFLC_SCDL_DATE). 없으면 null. */
public record SrRef(String srNo, String srTitl, String srPlanDate) {
}
