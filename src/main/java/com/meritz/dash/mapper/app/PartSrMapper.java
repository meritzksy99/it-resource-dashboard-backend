package com.meritz.dash.mapper.app;

import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface PartSrMapper {

    /**
     * HR_DEVELOPER 재직자(STATUS_CD='01') 명부 조회.
     * part 가 null 이면 전 파트, 있으면 해당 PART_CD 만.
     * 반환: EMPNO, EMP_NM, DEPT_CD, PART_CD
     */
    List<Map<String, Object>> findRoster(@Param("part") String part);

    /**
     * DASH_DEV_AGG ⨝ HR_DEVELOPER INNER JOIN 으로 (PART_CD, DEPT_CD, SR_CLS) 별 집계.
     * HR 에 없는 EMPNO 는 INNER JOIN 으로 제외.
     * part 가 null 이면 전 파트.
     * 반환: PART_CD, DEPT_CD, SR_CLS, SR_CNT, JOB_MM
     */
    List<Map<String, Object>> findSrByPartClass(@Param("period") String period,
                                                @Param("part") String part);

    /**
     * CD_COMMON 에서 GRP_CD 로 코드명 맵 조회.
     * 반환: CD_VAL → CD_NM
     */
    List<Map<String, Object>> findCodeMap(@Param("grpCd") String grpCd);
}
