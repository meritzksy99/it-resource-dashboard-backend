package com.meritz.dash.overtime;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.mapper.app.HrOvertimeMapper;
import com.meritz.dash.mapper.app.ResourceMapper;
import com.meritz.dash.resource.OvertimeRow;
import com.meritz.dash.support.AbstractOracleIT;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HR_OVERTIME 매퍼/업로드 통합 테스트 (Testcontainers Oracle, V014 마이그레이션 적용).
 * 전용 시드(사번 OT9xx, 부서 DOT9)로 다른 시드/병렬 작업과 격리.
 *
 *   OT901 — DOT9/PX1, DEV_YN=Y, 재직('01')
 *   OT902 — DOT9/PX1, DEV_YN=Y, 재직
 *   OT903 — DOT9/PX2, DEV_YN=Y, 재직
 *   OT904 — DOT9/PX1, DEV_YN=N (팀장, 개발인원 아님)
 */
class HrOvertimeMapperIT extends AbstractOracleIT {

    private static final String PERIOD = "209901"; // 다른 테스트와 겹치지 않는 기간

    @Autowired HrOvertimeMapper overtimeMapper;
    @Autowired ResourceMapper resourceMapper;
    @Autowired OvertimeUploadService uploadService;
    @Autowired @Qualifier("appDataSource") DataSource appDs;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(appDs);
    }

    @BeforeEach
    void seed() {
        AuthContext.set("OT904", "01", "DOT9", "PX1");
        JdbcTemplate j = jdbc();
        j.update("DELETE FROM HR_OVERTIME WHERE PERIOD_YM = ?", PERIOD);
        j.update("DELETE FROM HR_DEVELOPER WHERE EMPNO LIKE 'OT9%'");
        j.update("INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, ROLE_CD, DEV_YN, STATUS_CD) " +
                 "VALUES ('OT901','야근일','DOT9','PX1','03','Y','01')");
        j.update("INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, ROLE_CD, DEV_YN, STATUS_CD) " +
                 "VALUES ('OT902','야근이','DOT9','PX1','03','Y','01')");
        j.update("INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, ROLE_CD, DEV_YN, STATUS_CD) " +
                 "VALUES ('OT903','야근삼','DOT9','PX2','03','Y','01')");
        j.update("INSERT INTO HR_DEVELOPER (EMPNO, EMP_NM, DEPT_CD, PART_CD, ROLE_CD, DEV_YN, STATUS_CD) " +
                 "VALUES ('OT904','팀장님','DOT9','PX1','01','N','01')");
    }

    @AfterEach
    void cleanup() {
        JdbcTemplate j = jdbc();
        j.update("DELETE FROM HR_OVERTIME WHERE PERIOD_YM = ?", PERIOD);
        j.update("DELETE FROM HR_DEVELOPER WHERE EMPNO LIKE 'OT9%'");
        AuthContext.clear();
    }

    // ── 헬퍼: 야근양식 xlsx 생성 (A=사번, J/L/N/P=분) ─────────────────────

    private static MockMultipartFile xlsx(Object[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("사번");
            int r = 1;
            for (Object[] spec : rows) {   // [empno, j, l, n, p]
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue((String) spec[0]);
                int[] cols = {9, 11, 13, 15};
                for (int i = 0; i < 4; i++) {
                    row.createCell(cols[i]).setCellValue(((Number) spec[i + 1]).doubleValue());
                }
            }
            wb.write(out);
            return new MockMultipartFile("file", "야근양식.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    // ── 업로드 멱등성 ─────────────────────────────────────────────────

    @Test
    @DisplayName("재업로드 멱등 — 같은 period 두 번 업로드해도 최종 값만 남는다 (DELETE 후 INSERT)")
    void reupload_is_idempotent() throws Exception {
        uploadService.upload(PERIOD, xlsx(
                new Object[]{"OT901", 742, 0, 32, 0},     // 774분
                new Object[]{"OT902", 0, 300, 0, 0}));    // 300분

        // 값을 바꿔 재업로드 — OT902는 빠지고 OT903이 새로 들어옴
        int saved = uploadService.upload(PERIOD, xlsx(
                new Object[]{"OT901", 600, 0, 0, 0},      // 600분으로 변경
                new Object[]{"OT903", 0, 0, 0, 90}));     // 90분 신규

        assertThat(saved).isEqualTo(2);
        List<OvertimeRow> rows = resourceMapper.findOvertimeHoursByScope(PERIOD, null, null, null);
        assertThat(rows).extracting(OvertimeRow::empno).containsExactly("OT901", "OT903"); // 분 내림차순
        assertThat(rows).extracting(OvertimeRow::otMinutes).containsExactly(600, 90);
    }

    // ── 스코프 필터 ───────────────────────────────────────────────────

    @Test
    @DisplayName("findOvertimeHoursByScope — dept/part/empno 필터")
    void scope_filters() {
        overtimeMapper.insert(PERIOD, "OT901", 774, "TEST");
        overtimeMapper.insert(PERIOD, "OT902", 300, "TEST");
        overtimeMapper.insert(PERIOD, "OT903", 90, "TEST");

        // dept 필터 — DOT9 전체 3명
        assertThat(resourceMapper.findOvertimeHoursByScope(PERIOD, "DOT9", null, null))
                .extracting(OvertimeRow::empno).containsExactly("OT901", "OT902", "OT903");

        // part 필터 — PX1만 (OT903 제외)
        assertThat(resourceMapper.findOvertimeHoursByScope(PERIOD, "DOT9", "PX1", null))
                .extracting(OvertimeRow::empno).containsExactly("OT901", "OT902");

        // empno 필터 — 본인만
        List<OvertimeRow> self = resourceMapper.findOvertimeHoursByScope(PERIOD, null, null, "OT903");
        assertThat(self).hasSize(1);
        assertThat(self.get(0).otMinutes()).isEqualTo(90);
        assertThat(self.get(0).partCd()).isEqualTo("PX2");
    }

    @Test
    @DisplayName("HR_DEVELOPER 미등록 사번 — 이름/파트 '미분류'로 노출 (LEFT JOIN)")
    void unknown_empno_shows_unclassified() {
        overtimeMapper.insert(PERIOD, "OT999", 120, "TEST"); // HR_DEVELOPER에 없는 사번

        List<OvertimeRow> rows = resourceMapper.findOvertimeHoursByScope(PERIOD, null, null, "OT999");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).empNm()).isEqualTo("미분류");
        assertThat(rows.get(0).partCd()).isEqualTo("미분류");

        jdbc().update("DELETE FROM HR_OVERTIME WHERE PERIOD_YM = ? AND EMPNO = 'OT999'", PERIOD);
    }

    // ── 평균 분모: 스코프 재직 개발자 수 ─────────────────────────────────

    @Test
    @DisplayName("countDevelopersByScope — DEV_YN='N' 팀장 제외, dept/part/empno 스코프별 인원")
    void count_developers_by_scope() {
        // DOT9 전체: OT901·OT902·OT903 (OT904는 DEV_YN='N' 제외)
        assertThat(resourceMapper.countDevelopersByScope("DOT9", null, null)).isEqualTo(3);
        // DOT9-PX1: OT901·OT902
        assertThat(resourceMapper.countDevelopersByScope("DOT9", "PX1", null)).isEqualTo(2);
        // 본인 1명
        assertThat(resourceMapper.countDevelopersByScope(null, null, "OT903")).isEqualTo(1);
        // 비개발 인원(팀장)은 0
        assertThat(resourceMapper.countDevelopersByScope(null, null, "OT904")).isEqualTo(0);
    }
}
