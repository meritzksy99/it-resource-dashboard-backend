package com.meritz.dash.mapper.app;

import com.meritz.dash.resource.DeveloperUtilRow;
import com.meritz.dash.resource.OvertimeRow;
import com.meritz.dash.resource.ResourceRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ResourceMapper {
    ResourceRow findUnit(@Param("period") String period, @Param("unitType") String unitType, @Param("unitId") String unitId);
    List<ResourceRow> findUnitRange(@Param("from") String from, @Param("to") String to, @Param("unitType") String unitType, @Param("unitId") String unitId);
    /**
     * 사번별 야근 실적(분) 조회 — HR_OVERTIME(엑셀 업로드) ⨝ HR_DEVELOPER.
     * dept/part/empno 필터(모두 nullable) — 역할 스코핑 결과를 받는다. OT_MINUTES 내림차순.
     */
    List<OvertimeRow> findOvertimeHoursByScope(@Param("period") String period,
                                               @Param("dept") String dept,
                                               @Param("part") String part,
                                               @Param("empno") String empno);

    /**
     * 스코프 내 재직 개발자 수 (STATUS_CD='01' AND DEV_YN='Y') — 평균 야근시간 분모.
     * dept/part/empno 필터(모두 nullable) — findOvertimeHoursByScope 와 동일 스코프.
     */
    int countDevelopersByScope(@Param("dept") String dept,
                               @Param("part") String part,
                               @Param("empno") String empno);

    /**
     * 개발자별 사용 M/M 조회 (재직 개발자 STATUS_CD='01' AND DEV_YN='Y').
     * dept/part/empno 필터(모두 nullable) — 역할 스코핑 결과를 받는다.
     * DASH_DEV_AGG를 LEFT JOIN하여 해당 월 SR이 없는 개발자도 usedMm=0으로 포함.
     */
    List<DeveloperUtilRow> findDeveloperUtil(@Param("period") String period,
                                             @Param("dept") String dept,
                                             @Param("part") String part,
                                             @Param("empno") String empno);
}
