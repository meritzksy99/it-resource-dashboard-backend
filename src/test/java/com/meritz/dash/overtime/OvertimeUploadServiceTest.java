package com.meritz.dash.overtime;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.mapper.app.HrOvertimeMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * OvertimeUploadService 단위 테스트 — 검증(period/파일) + 멱등 저장(DELETE 후 INSERT).
 */
class OvertimeUploadServiceTest {

    private HrOvertimeMapper mapper;
    private OvertimeUploadService service;

    @BeforeEach
    void setup() {
        mapper = mock(HrOvertimeMapper.class);
        service = new OvertimeUploadService(mapper, new OvertimeExcelParser());
        AuthContext.set("E0001", "01", "2139", "P01");  // 업로더(팀장)
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    private static byte[] sampleXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("사번"); // 헤더
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("9320");
            r1.createCell(9).setCellValue(742);   // J 평일연장(분)
            r1.createCell(13).setCellValue(32);   // N 휴일연장(분)
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("7452");
            r2.createCell(11).setCellValue(300);  // L 평일야간(분)
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
    }

    @Test
    @DisplayName("업로드 성공 — 해당 period DELETE 후 사번별 INSERT(멱등), 저장 건수 반환")
    void upload_deletes_then_inserts() throws Exception {
        int saved = service.upload("202606", file("야근양식.xlsx", sampleXlsx()));

        assertThat(saved).isEqualTo(2);
        InOrder inOrder = inOrder(mapper);
        inOrder.verify(mapper).deleteByPeriod("202606");
        inOrder.verify(mapper).insert("202606", "9320", 774, "E0001");
        inOrder.verify(mapper).insert("202606", "7452", 300, "E0001");
    }

    @Test
    @DisplayName("period 형식 오류(YYYYMM 아님) → IllegalArgumentException, 매퍼 호출 없음")
    void invalid_period_throws() throws Exception {
        byte[] xlsx = sampleXlsx();

        assertThatThrownBy(() -> service.upload("2026", file("a.xlsx", xlsx)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload("202613", file("a.xlsx", xlsx)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload(null, file("a.xlsx", xlsx)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("파일 없음/빈 파일 → IllegalArgumentException")
    void missing_or_empty_file_throws() {
        assertThatThrownBy(() -> service.upload("202606", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload("202606", file("a.xlsx", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName(".xlsx 아닌 확장자 → IllegalArgumentException")
    void non_xlsx_extension_throws() {
        assertThatThrownBy(() -> service.upload("202606", file("a.xls", new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload("202606", file("a.csv", new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("파싱 실패(.xlsx 확장자지만 내용 손상) → IllegalArgumentException, 저장 없음")
    void corrupt_content_throws_and_no_write() {
        assertThatThrownBy(() -> service.upload("202606", file("a.xlsx", "broken".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }
}
