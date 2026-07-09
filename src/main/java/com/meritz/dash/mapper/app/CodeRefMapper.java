package com.meritz.dash.mapper.app;

import org.apache.ibatis.annotations.MapKey;

import java.util.Map;

public interface CodeRefMapper {

    @MapKey("srTpcd")
    Map<String, SrClsRef> srClsByTpcd();

    record SrClsRef(String srTpcd, String srCls) {}
}
