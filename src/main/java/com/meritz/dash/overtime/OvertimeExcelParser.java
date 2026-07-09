package com.meritz.dash.overtime;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 야근양식 엑셀(.xlsx) 파서.
 * <p>
 * 포맷(1행 헤더, 2행부터 데이터 — prd/야근양식_김성엽.xlsx 기준, 1-indexed):
 * <ul>
 *   <li>A(1)=사번 · C(3)=부서</li>
 *   <li>J(10)=평일 연장근무(분) · L(12)=평일 야간근무(분) · N(14)=휴일 연장근무(분) · P(16)=휴일 야간근무(분)</li>
 * </ul>
 * 야근 총 분 = J+L+N+P (HH:MM 표기 컬럼 I/K/M/O 는 무시 — '분' 컬럼이 무손실).
 * 빈/누락 분 컬럼은 0. 사번이 빈 행·헤더행·합계행은 스킵. 같은 사번 중복 행은 분 합산.
 */
@Component
public class OvertimeExcelParser {

    /** A=사번 (0-indexed 0) */
    private static final int COL_EMPNO = 0;
    /** J·L·N·P = 4개 '분' 컬럼 (0-indexed 9, 11, 13, 15) */
    private static final int[] MINUTE_COLS = {9, 11, 13, 15};
    /** 사번 형식(영숫자 1~20) — 잘못된 A열 값(라벨 등)이 쓰레기 사번으로 저장되는 것 방지. */
    private static final java.util.regex.Pattern EMPNO_PTN = java.util.regex.Pattern.compile("[A-Za-z0-9]{1,20}");
    /** 처리 행 상한(DoS 방어) — 정상 야근양식은 인원 수 수준. */
    private static final int MAX_DATA_ROWS = 100_000;
    /** 야근 분 상한(월 최대 시간 방어) — 31일 × 24h × 60분. NUMBER(7) 초과/비정상값 차단. */
    private static final int MAX_MINUTES = 31 * 24 * 60;

    private final DataFormatter formatter = new DataFormatter();

    /**
     * 엑셀 스트림을 파싱해 사번별 야근 총 분 목록을 반환한다.
     *
     * @throws IllegalArgumentException 파싱 불가(.xlsx 형식 아님/손상 등)
     */
    public List<OvertimeEntry> parse(InputStream in) {
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            requireOvertimeForm(sheet);                       // 양식 지문 검증(엉뚱한 xlsx로 월 데이터 소거 방지)
            Map<String, Integer> byEmpno = new LinkedHashMap<>();
            int dataRows = 0;
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;          // 1행 헤더 스킵
                if (++dataRows > MAX_DATA_ROWS) {
                    throw new IllegalArgumentException("업로드 행이 너무 많습니다(최대 " + MAX_DATA_ROWS + "행)");
                }
                String empno = cellString(row.getCell(COL_EMPNO));
                if (skipRow(empno)) continue;                // 사번 없는 행/합계행/형식오류 스킵
                int total = 0;
                for (int col : MINUTE_COLS) {
                    total += minutes(row.getCell(col));
                }
                total = Math.max(Math.min(total, MAX_MINUTES), 0);   // [0, MAX_MINUTES] 클램프
                byEmpno.merge(empno, total, Integer::sum);
            }
            return byEmpno.entrySet().stream()
                    .map(e -> new OvertimeEntry(e.getKey(), Math.min(e.getValue(), MAX_MINUTES)))
                    .toList();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "엑셀 파일을 파싱할 수 없습니다(.xlsx 형식인지 확인하세요)", e);
        }
    }

    /** 1행 A열이 '사번' 헤더인지로 야근양식 여부를 검증한다. 아니면 파싱을 거부(삭제 방지). */
    private void requireOvertimeForm(Sheet sheet) {
        Row header = sheet.getRow(0);
        String a1 = header == null ? "" : cellString(header.getCell(COL_EMPNO));
        if (!"사번".equals(a1)) {
            throw new IllegalArgumentException(
                    "야근양식이 아닙니다: 1행 A열에 '사번' 헤더가 있어야 합니다");
        }
    }

    /** 사번 칸이 비었거나 헤더/합계성 라벨이거나 사번 형식이 아니면 스킵(쓰레기 사번 저장 방지). */
    private boolean skipRow(String empno) {
        return empno.isEmpty()
                || "사번".equals(empno)
                || empno.contains("합계")
                || empno.contains("총계")
                || !EMPNO_PTN.matcher(empno).matches();
    }

    /** 숫자/문자 셀 모두 문자열로 정규화(숫자 사번의 '.0' 노출 방지 — DataFormatter). */
    private String cellString(Cell cell) {
        if (cell == null) return "";
        return formatter.formatCellValue(cell).trim();
    }

    /** '분' 셀 → int. 빈/누락/숫자 아님은 0. */
    private int minutes(Cell cell) {
        if (cell == null) return 0;
        if (cell.getCellType() == CellType.NUMERIC) {
            return (int) Math.round(cell.getNumericCellValue());
        }
        String s = cellString(cell);
        if (s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
