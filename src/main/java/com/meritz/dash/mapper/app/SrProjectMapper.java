package com.meritz.dash.mapper.app;

import com.meritz.dash.srproject.SrProjectView;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface SrProjectMapper {
    List<SrProjectView> findTop(@Param("period") String period, @Param("minMm") double minMm,
        @Param("type") String type, @Param("offset") long offset, @Param("size") int size);
    int countTop(@Param("period") String period, @Param("minMm") double minMm, @Param("type") String type);
}
