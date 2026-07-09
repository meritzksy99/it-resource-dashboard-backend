package com.meritz.dash.mapper.app;

import com.meritz.dash.devvolume.DevDeptPart;
import com.meritz.dash.devvolume.DevVolumePoint;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DevVolumeMapper {
    List<DevVolumePoint> findSeries(@Param("unitType") String unitType,
                                    @Param("unitId") String unitId,
                                    @Param("fromYm") String fromYm);

    /** 사번의 소속 부서/파트 조회(재직 여부 무관). 없으면 null. 업무리더(02) 파트원 판정용. */
    DevDeptPart findDeptPartByEmpno(@Param("empno") String empno);
}
