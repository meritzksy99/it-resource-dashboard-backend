package com.meritz.dash.partsr;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "파트별 SR 요약 행")
public record PartSrRow(
        String deptCd,
        String deptNm,
        String partCd,
        String partNm,
        int headcount,
        List<String> memberNames,
        double totMm,
        List<SrClassCount> srByClass
) {}
