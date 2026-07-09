package com.meritz.dash.mapper.app;

import org.apache.ibatis.annotations.Param;

/** HR_OVERTIME(월별 야근 업로드) 쓰기 매퍼 — appDataSource. */
public interface HrOvertimeMapper {

    /** 해당 월 기존 행 전체 삭제 (재업로드 멱등 처리용). @return 삭제 건수 */
    int deleteByPeriod(@Param("period") String period);

    /** 사번별 야근 총 분 저장. CREATED_BY/UPDATED_BY = 업로드한 사용자 사번. */
    void insert(@Param("period") String period,
                @Param("empno") String empno,
                @Param("otMinutes") int otMinutes,
                @Param("by") String by);
}
