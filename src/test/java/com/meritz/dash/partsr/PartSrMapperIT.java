package com.meritz.dash.partsr;

import com.meritz.dash.mapper.app.DeveloperMapper;
import com.meritz.dash.mapper.app.PartSrMapper;
import com.meritz.dash.developer.Developer;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PartSrMapper IT: 픽스처(HR 내부 2명 + 외주 1명, DASH_DEV_AGG SR_CLS 행) 시드 후
 * 매퍼 결과 단언. @AfterEach 로 정리(다른 IT 오염 방지).
 * DASH_DEV_AGG 시드/정리는 JdbcTemplate 직접 사용 (DashWriteMapper 테스트 전용 메서드 제거됨).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PartSrMapperIT extends AbstractOracleIT {

    @Autowired PartSrMapper partSrMapper;
    @Autowired DeveloperMapper developerMapper;
    @Autowired @Qualifier("appDataSource") DataSource appDataSource;

    private JdbcTemplate jdbcTemplate;

    // 픽스처 사번
    private static final String INT_EMPNO_A = "EP001";
    private static final String INT_EMPNO_B = "EP002";
    private static final String OUT_EMPNO   = "EP003";
    private static final String PERIOD      = "299902";   // 고유 미래기간 (PartSrIT=299901 과 완전 격리)
    private static final String PART_CD     = "PX2";      // 고유 파트코드 (PartSrIT=PX1 과 완전 격리)

    @BeforeEach
    void insertFixtures() {
        jdbcTemplate = new JdbcTemplate(appDataSource);

        // 멱등 시드: 직전 실행이 @AfterEach 전에 중단됐어도 중복 없이 시작하도록 먼저 정리.
        cleanupFixtures();

        // 내부 직원 2명 (DEPT_CD='2139', PART_CD=PART_CD)
        insertDevIfAbsent(INT_EMPNO_A, "김성엽픽스처", "2139", PART_CD);
        insertDevIfAbsent(INT_EMPNO_B, "김동현픽스처", "2139", PART_CD);
        // 외주 직원 1명 (DEPT_CD='9000', PART_CD='PX1')
        insertDevIfAbsent(OUT_EMPNO, "외주자픽스처", "9000", PART_CD);

        // DASH_DEV_AGG 시드
        jdbcTemplate.update(
            "INSERT INTO DASH_DEV_AGG (PERIOD_YM, EMPNO, SR_CLS, SR_CNT, JOB_MM) VALUES (?,?,?,?,?)",
            PERIOD, INT_EMPNO_A, "01", 10, 1.0
        );
        jdbcTemplate.update(
            "INSERT INTO DASH_DEV_AGG (PERIOD_YM, EMPNO, SR_CLS, SR_CNT, JOB_MM) VALUES (?,?,?,?,?)",
            PERIOD, INT_EMPNO_B, "01", 10, 1.0
        );
        jdbcTemplate.update(
            "INSERT INTO DASH_DEV_AGG (PERIOD_YM, EMPNO, SR_CLS, SR_CNT, JOB_MM) VALUES (?,?,?,?,?)",
            PERIOD, OUT_EMPNO, "01", 5, 0.5
        );
    }

    @AfterEach
    void cleanup() {
        cleanupFixtures();
    }

    private void cleanupFixtures() {
        // DASH_DEV_AGG 픽스처 삭제
        jdbcTemplate.update(
            "DELETE FROM DASH_DEV_AGG WHERE PERIOD_YM = ? AND EMPNO IN (?,?,?)",
            PERIOD, INT_EMPNO_A, INT_EMPNO_B, OUT_EMPNO
        );
        // HR 픽스처 삭제
        developerMapper.deleteByEmpno(INT_EMPNO_A);
        developerMapper.deleteByEmpno(INT_EMPNO_B);
        developerMapper.deleteByEmpno(OUT_EMPNO);
    }

    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findRoster(null) → 픽스처 3명 포함, STATUS_CD='01' 재직자만")
    void findRoster_includes_fixture_members() {
        List<Map<String, Object>> roster = partSrMapper.findRoster(null);
        assertThat(roster).isNotEmpty();
        // 픽스처 사번 3개 모두 포함
        List<String> empnos = roster.stream()
                .map(r -> r.get("EMPNO").toString()).toList();
        assertThat(empnos).contains(INT_EMPNO_A, INT_EMPNO_B, OUT_EMPNO);
    }

    @Test
    @DisplayName("findRoster('PX1') → PART_CD=PX1 만 반환")
    void findRoster_part_filter() {
        List<Map<String, Object>> roster = partSrMapper.findRoster(PART_CD);
        assertThat(roster).isNotEmpty();
        assertThat(roster).allMatch(r -> PART_CD.equals(r.get("PART_CD")));
        List<String> empnos = roster.stream()
                .map(r -> r.get("EMPNO").toString()).toList();
        assertThat(empnos).contains(INT_EMPNO_A, INT_EMPNO_B, OUT_EMPNO);
    }

    @Test
    @DisplayName("findSrByPartClass(period, null) → 픽스처 SR 행 포함")
    void findSrByPartClass_includes_fixture() {
        List<Map<String, Object>> rows = partSrMapper.findSrByPartClass(PERIOD, null);
        assertThat(rows).isNotEmpty();
        // PX1 파트 내 SR_CLS='01' 행이 있어야 함
        boolean hasPX1 = rows.stream().anyMatch(r ->
                PART_CD.equals(r.get("PART_CD")) && "01".equals(r.get("SR_CLS")));
        assertThat(hasPX1).isTrue();
    }

    @Test
    @DisplayName("findSrByPartClass: 내부(2139) SR_CNT 합계 = 20 정확히 (각 10씩)")
    void findSrByPartClass_internal_sr_cnt_sum() {
        List<Map<String, Object>> rows = partSrMapper.findSrByPartClass(PERIOD, null);
        long internalSrCnt = rows.stream()
                .filter(r -> PART_CD.equals(r.get("PART_CD"))
                          && "2139".equals(r.get("DEPT_CD"))
                          && "01".equals(r.get("SR_CLS")))
                .mapToLong(r -> ((Number) r.get("SR_CNT")).longValue())
                .sum();
        assertThat(internalSrCnt).isEqualTo(20);
    }

    @Test
    @DisplayName("findSrByPartClass: 외주(9000) SR_CNT = 5 정확히")
    void findSrByPartClass_outsourcing_sr_cnt() {
        List<Map<String, Object>> rows = partSrMapper.findSrByPartClass(PERIOD, null);
        long outsourcingSrCnt = rows.stream()
                .filter(r -> PART_CD.equals(r.get("PART_CD"))
                          && "9000".equals(r.get("DEPT_CD"))
                          && "01".equals(r.get("SR_CLS")))
                .mapToLong(r -> ((Number) r.get("SR_CNT")).longValue())
                .sum();
        assertThat(outsourcingSrCnt).isEqualTo(5);
    }

    @Test
    @DisplayName("findSrByPartClass('PX1' 필터) → PART_CD=PX1 만 포함")
    void findSrByPartClass_part_filter() {
        List<Map<String, Object>> rows = partSrMapper.findSrByPartClass(PERIOD, PART_CD);
        assertThat(rows).isNotEmpty();
        assertThat(rows).allMatch(r -> PART_CD.equals(r.get("PART_CD")));
    }

    @Test
    @DisplayName("findCodeMap('PART_CD') → P01 코드 포함")
    void findCodeMap_part_cd() {
        List<Map<String, Object>> codes = partSrMapper.findCodeMap("PART_CD");
        assertThat(codes).isNotEmpty();
        boolean hasP01 = codes.stream().anyMatch(r -> "P01".equals(r.get("CD_VAL")));
        assertThat(hasP01).isTrue();
    }

    @Test
    @DisplayName("findCodeMap('DEPT_CD') → 9000 외주 부서 포함 (V012 마이그레이션)")
    void findCodeMap_dept_cd_includes_outsourcing() {
        List<Map<String, Object>> codes = partSrMapper.findCodeMap("DEPT_CD");
        boolean has9000 = codes.stream().anyMatch(r -> "9000".equals(r.get("CD_VAL")));
        assertThat(has9000).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────────────

    private void insertDevIfAbsent(String empno, String empNm, String deptCd, String partCd) {
        if (developerMapper.findByEmpno(empno) == null) {
            developerMapper.insert(new Developer(empno, empNm, deptCd, partCd, "사원", "03", "Y", "01"));
        }
    }
}
