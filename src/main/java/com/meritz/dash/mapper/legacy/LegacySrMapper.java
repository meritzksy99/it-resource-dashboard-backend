package com.meritz.dash.mapper.legacy;

import com.meritz.dash.aggregation.LegacyDevRow;
import com.meritz.dash.aggregation.LegacySrProjectRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface LegacySrMapper {
    List<LegacyDevRow> selectDevAgg(@Param("periodYm") String periodYm);
    List<LegacySrProjectRow> selectSrProjects(@Param("periodYm") String periodYm, @Param("minMm") double minMm);
}
