package com.meritz.dash.aggregation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/**
 * 집계 실행 요청 DTO.
 * <ul>
 *   <li>periodYm 단일 지정: {@code { "periodYm":"202605" }}</li>
 *   <li>from~to 범위 지정: {@code { "from":"202601","to":"202605" }}</li>
 * </ul>
 * {@link #periods()} 호출 시 YYYYMM 형식 검증 및 범위 전개를 수행한다.
 */
public record AggregationRequest(
    @Schema(description = "단일 월 지정 (YYYYMM). periodYm 또는 from~to 중 하나 사용.", example = "202606") String periodYm,
    @Schema(description = "범위 시작 월 (YYYYMM). from~to 방식 사용 시.", example = "202601") String from,
    @Schema(description = "범위 종료 월 (YYYYMM). from~to 방식 사용 시.", example = "202606") String to) {

    private static void checkFmt(String ym) {
        if (ym == null || !ym.matches("\\d{6}")) {
            throw new IllegalArgumentException("periodYm은 YYYYMM 형식이어야 합니다: " + ym);
        }
        int mm = Integer.parseInt(ym.substring(4));
        if (mm < 1 || mm > 12) throw new IllegalArgumentException("월 범위 오류: " + ym);
    }

    public List<String> periods() {
        if (periodYm != null) {
            checkFmt(periodYm);
            return List.of(periodYm);
        }
        checkFmt(from);
        checkFmt(to);
        if (from.compareTo(to) > 0)
            throw new IllegalArgumentException("from은 to보다 클 수 없습니다.");
        List<String> out = new ArrayList<>();
        int y = Integer.parseInt(from.substring(0, 4)), m = Integer.parseInt(from.substring(4));
        int ey = Integer.parseInt(to.substring(0, 4)), em = Integer.parseInt(to.substring(4));
        while (y < ey || (y == ey && m <= em)) {
            out.add(String.format("%04d%02d", y, m));
            if (++m > 12) { m = 1; y++; }
        }
        return out;
    }
}
