package com.meritz.dash.partsr;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "파트별 SR 요약 결과")
public record PartSrResult(
        @Schema(description = "내부 파트 목록 (PART_CD 오름차순)")
        List<PartSrRow> parts,
        @Schema(description = "외주 파트 목록 (PART_CD 오름차순)")
        List<PartSrRow> outsourcing
) {}
