package com.meritz.dash.mapper.app;

import com.meritz.dash.developer.Developer;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DeveloperMapper {
    List<Developer> findAll(@Param("deptCd") String deptCd,
                            @Param("partCd") String partCd,
                            @Param("devYn") String devYn,
                            @Param("statusCd") String statusCd);
    Developer findByEmpno(@Param("empno") String empno);
    int insert(Developer dev);
    int update(Developer dev);
    int deleteByEmpno(@Param("empno") String empno);
}
