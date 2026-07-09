package com.meritz.dash.overtime;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 야근양식 엑셀 파싱 단위 테스트.
 * 포맷: 1행 헤더, 2행부터 데이터. A(0)=사번,
 * J(9)/L(11)/N(13)/P(15) = 평일연장·평일야간·휴일연장·휴일야간 '분' — 야근 총분 = 4개 합.
 */
class OvertimeExcelParserTest {

    private final OvertimeExcelParser parser = new OvertimeExcelParser();

    // ── 헬퍼: 인메모리 야근양식 워크북 생성 ─────────────────────────────

    private static byte[] workbook(RowSpec... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("야근");
            Row header = sheet.createRow(0);
            String[] heads = {"사번","이름","부서","직급","PC 사용시간","PC 사용시간(분)","신청시간","신청시간(분)",
                    "평일 연장근무","평일 연장근무(분)","평일 야간근무","평일 야간근무(분)",
                    "휴일 연장근무","휴일 연장근무(분)","휴일 야간근무","휴일 야간근무(분)"};
            for (int i = 0; i < heads.length; i++) header.createCell(i).setCellValue(heads[i]);
            int r = 1;
            for (RowSpec spec : rows) {
                Row row = sheet.createRow(r++);
                if (spec.empno != null) row.createCell(0).setCellValue(spec.empno);
                // HH:MM 표기 컬럼(I/K/M/O)에는 미끼 문자열 — '분' 컬럼만 써야 한다
                row.createCell(8).setCellValue("99:59");
                row.createCell(10).setCellValue("88:88");
                row.createCell(12).setCellValue("77:77");
                row.createCell(14).setCellValue("66:66");
                if (spec.j != null) row.createCell(9).setCellValue(spec.j);
                if (spec.l != null) row.createCell(11).setCellValue(spec.l);
                if (spec.n != null) row.createCell(13).setCellValue(spec.n);
                if (spec.p != null) row.createCell(15).setCellValue(spec.p);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private record RowSpec(String empno, Double j, Double l, Double n, Double p) {}

    private static RowSpec row(String empno, Double j, Double l, Double n, Double p) {
        return new RowSpec(empno, j, l, n, p);
    }

    // ── 4개 '분' 컬럼 합산 ─────────────────────────────────────────────

    @Test
    @DisplayName("야근 총분 = J+L+N+P 4개 '분' 컬럼 합 (HH:MM 컬럼은 무시)")
    void sums_four_minute_columns() throws Exception {
        byte[] xlsx = workbook(row("9320", 742.0, 0.0, 32.0, 0.0));

        List<OvertimeEntry> entries = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(entries).containsExactly(new OvertimeEntry("9320", 774)); // 742+0+32+0
    }

    @Test
    @DisplayName("빈/누락 '분' 셀은 0으로 처리")
    void missing_minute_cells_are_zero() throws Exception {
        byte[] xlsx = workbook(row("1111", 60.0, null, null, null));

        List<OvertimeEntry> entries = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(entries).containsExactly(new OvertimeEntry("1111", 60));
    }

    @Test
    @DisplayName("모든 분 컬럼이 비면 0분으로 저장 대상에 포함")
    void all_blank_minutes_is_zero_entry() throws Exception {
        byte[] xlsx = workbook(row("2222", null, null, null, null));

        List<OvertimeEntry> entries = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(entries).containsExactly(new OvertimeEntry("2222", 0));
    }

    // ── 스킵 규칙 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("사번 없는 행·합계행은 스킵")
    void skips_blank_empno_and_total_rows() throws Exception {
        byte[] xlsx = workbook(
                row("9320", 100.0, 0.0, 0.0, 0.0),
                row(null,   999.0, 0.0, 0.0, 0.0),      // 사번 없음 → 스킵
                row("합계", 1099.0, 0.0, 0.0, 0.0),     // 합계행 → 스킵
                row("사번", 50.0, 0.0, 0.0, 0.0));      // 중간 헤더행 → 스킵

        List<OvertimeEntry> entries = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(entries).containsExactly(new OvertimeEntry("9320", 100));
    }

    @Test
    @DisplayName("같은 사번 중복 행은 분 합산 (PK 충돌 방지)")
    void merges_duplicate_empno_rows() throws Exception {
        byte[] xlsx = workbook(
                row("3333", 30.0, 0.0, 0.0, 0.0),
                row("3333", 0.0, 15.0, 0.0, 0.0));

        List<OvertimeEntry> entries = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(entries).containsExactly(new OvertimeEntry("3333", 45));
    }

    // ── 실제 양식 파일 ─────────────────────────────────────────────────

    @Test
    @DisplayName("실제 양식(prd/야근양식) — 9320 = 742+0+32+0 = 774분")
    void parses_real_sample_file() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/overtime/overtime_sample.xlsx")) {
            assertThat(in).as("테스트 리소스 overtime_sample.xlsx 존재").isNotNull();

            List<OvertimeEntry> entries = parser.parse(in);

            assertThat(entries).contains(new OvertimeEntry("9320", 774));
        }
    }

    // ── 파싱 실패 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("xlsx 아닌 스트림은 IllegalArgumentException (컨트롤러에서 400)")
    void invalid_stream_throws_illegal_argument() {
        byte[] notXlsx = "this,is,csv".getBytes();

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(notXlsx)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
