package com.meritz.dash.mapper.legacy;

import com.meritz.dash.weekly.SrRef;
import org.apache.ibatis.annotations.Param;

/**
 * 주간보고용 기간계 SR 참조 조회(SELECT-only). TBCPPE091M00 단건 —
 * SR_NO, TITL_CNTT(srTitl), RFLC_SCDL_DATE(srPlanDate). 바인드는 {@code #{}} 만 사용.
 * <p>
 * 스켈레톤(Red) 단계 — {@code mapper/legacy/WeeklySrMapper.xml} 은 namespace 만 존재.
 */
public interface WeeklySrMapper {

    /** SR 없으면 null. */
    SrRef selectSrRef(@Param("srNo") String srNo);
}
