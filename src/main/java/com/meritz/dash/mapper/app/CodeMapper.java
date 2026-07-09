package com.meritz.dash.mapper.app;

import com.meritz.dash.code.CommonCode;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CodeMapper {
    List<CommonCode> findByGroup(@Param("grpCd") String grpCd);

    CommonCode findOne(@Param("grpCd") String grpCd, @Param("cdVal") String cdVal);

    void insert(@Param("grpCd") String grpCd,
                @Param("cdVal") String cdVal,
                @Param("cdNm") String cdNm,
                @Param("sortNo") int sortNo,
                @Param("useYn") String useYn,
                @Param("attr1") String attr1);

    int update(@Param("grpCd") String grpCd,
               @Param("cdVal") String cdVal,
               @Param("cdNm") String cdNm,
               @Param("sortNo") int sortNo,
               @Param("useYn") String useYn,
               @Param("attr1") String attr1,
               @Param("updatedBy") String updatedBy);

    String findUseYn(@Param("grpCd") String grpCd, @Param("cdVal") String cdVal);

    int softDelete(@Param("grpCd") String grpCd, @Param("cdVal") String cdVal,
                   @Param("updatedBy") String updatedBy);
}
