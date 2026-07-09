package com.meritz.dash.partsr;

import com.meritz.dash.developer.Developer;
import com.meritz.dash.mapper.app.DeveloperMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PartSr 서비스 레이어 통합 테스트.
 * 고유 PERIOD(299901), PART_CD(PX1)로 실 데이터와 충돌 방지.
 * DASH_DEV_AGG 시드/정리는 JdbcTemplate 직접 사용 (DashWriteMapper 테스트 전용 메서드 제거됨).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PartSrIT extends AbstractOracleIT {

    @Autowired PartSrService partSrService;
    @Autowired DeveloperMapper developerMapper;
    @Autowired @Qualifier("appDataSource") DataSource appDataSource;

    private JdbcTemplate jdbcTemplate;

    private static final String PERIOD      = "299901";   // 고유 미래기간
    private static final String PART_CD     = "PX1";      // 고유 파트코드
    private static final String INT_EMPNO_A = "EIT01";
    private static final String INT_EMPNO_B = "EIT02";
    private static final String OUT_EMPNO   = "EIT03";

    @BeforeEach
    void seed() {
        jdbcTemplate = new JdbcTemplate(appDataSource);

        // 멱등 시드: 직전 실행이 @AfterEach 전에 중단됐어도 중복 없이 시작하도록 먼저 정리.
        cleanupFixtures();

        insertDevIfAbsent(INT_EMPNO_A, "김IT내부", "2139", PART_CD);
        insertDevIfAbsent(INT_EMPNO_B, "이IT내부", "2139", PART_CD);
        insertDevIfAbsent(OUT_EMPNO,   "외주IT자", "9000", PART_CD);

        jdbcTemplate.update(
            "INSERT INTO DASH_DEV_AGG (PERIOD_YM, EMPNO, SR_CLS, SR_CNT, JOB_MM) VALUES (?,?,?,?,?)",
            PERIOD, INT_EMPNO_A, "01", 8, 0.8
        );
        jdbcTemplate.update(
            "INSERT INTO DASH_DEV_AGG (PERIOD_YM, EMPNO, SR_CLS, SR_CNT, JOB_MM) VALUES (?,?,?,?,?)",
            PERIOD, INT_EMPNO_B, "01", 7, 0.7
        );
        jdbcTemplate.update(
            "INSERT INTO DASH_DEV_AGG (PERIOD_YM, EMPNO, SR_CLS, SR_CNT, JOB_MM) VALUES (?,?,?,?,?)",
            PERIOD, OUT_EMPNO, "01", 3, 0.3
        );
    }

    @AfterEach
    void cleanup() {
        cleanupFixtures();
    }

    private void cleanupFixtures() {
        jdbcTemplate.update(
            "DELETE FROM DASH_DEV_AGG WHERE PERIOD_YM = ? AND EMPNO IN (?,?,?)",
            PERIOD, INT_EMPNO_A, INT_EMPNO_B, OUT_EMPNO
        );
        developerMapper.deleteByEmpno(INT_EMPNO_A);
        developerMapper.deleteByEmpno(INT_EMPNO_B);
        developerMapper.deleteByEmpno(OUT_EMPNO);
    }

    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("전체 조회 → PX1 파트 포함, internal headcount=2 정확히, outsourcing headcount=1 정확히")
    void summary_all_parts_includes_fixture() {
        PartSrResult result = partSrService.summary(PERIOD, null);

        PartSrRow internalP = result.parts().stream()
                .filter(r -> PART_CD.equals(r.partCd()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PX1 내부 파트 없음"));
        PartSrRow outsourcingP = result.outsourcing().stream()
                .filter(r -> PART_CD.equals(r.partCd()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PX1 외주 파트 없음"));

        assertThat(internalP.headcount()).isEqualTo(2);
        assertThat(outsourcingP.headcount()).isEqualTo(1);
    }

    @Test
    @DisplayName("내부 SR srCnt 합계 = 15 정확히 (8+7)")
    void internal_sr_cnt_exact() {
        PartSrResult result = partSrService.summary(PERIOD, null);
        PartSrRow internalP = result.parts().stream()
                .filter(r -> PART_CD.equals(r.partCd()))
                .findFirst().orElseThrow();

        int internalSrCnt = internalP.srByClass().stream()
                .filter(c -> "01".equals(c.srCls()))
                .mapToInt(SrClassCount::srCnt)
                .sum();
        assertThat(internalSrCnt).isEqualTo(15);
    }

    @Test
    @DisplayName("외주 SR srCnt = 3 정확히")
    void outsourcing_sr_cnt_exact() {
        PartSrResult result = partSrService.summary(PERIOD, null);
        PartSrRow outsourcingP = result.outsourcing().stream()
                .filter(r -> PART_CD.equals(r.partCd()))
                .findFirst().orElseThrow();

        int outSrCnt = outsourcingP.srByClass().stream()
                .filter(c -> "01".equals(c.srCls()))
                .mapToInt(SrClassCount::srCnt)
                .sum();
        assertThat(outSrCnt).isEqualTo(3);
    }

    @Test
    @DisplayName("part=PX1 필터 → parts 모두 PART_CD=PX1")
    void part_filter_returns_only_px1() {
        PartSrResult result = partSrService.summary(PERIOD, PART_CD);
        assertThat(result.parts()).isNotEmpty();
        assertThat(result.parts()).allMatch(r -> PART_CD.equals(r.partCd()));
    }

    @Test
    @DisplayName("내부 멤버 이름 목록에 픽스처 이름 포함")
    void internal_member_names_include_fixture() {
        PartSrResult result = partSrService.summary(PERIOD, PART_CD);
        PartSrRow internalP = result.parts().stream()
                .filter(r -> PART_CD.equals(r.partCd()))
                .findFirst().orElseThrow();
        assertThat(internalP.memberNames()).contains("김IT내부", "이IT내부");
    }

    @Test
    @DisplayName("내부·외주 멤버가 서로 상대 그룹에 없음")
    void internal_outsourcing_strictly_separated() {
        PartSrResult result = partSrService.summary(PERIOD, PART_CD);
        PartSrRow internalP = result.parts().stream()
                .filter(r -> PART_CD.equals(r.partCd()))
                .findFirst().orElseThrow();
        PartSrRow outsourcingP = result.outsourcing().stream()
                .filter(r -> PART_CD.equals(r.partCd()))
                .findFirst().orElseThrow();

        assertThat(internalP.memberNames()).doesNotContain("외주IT자");
        assertThat(outsourcingP.memberNames()).doesNotContain("김IT내부", "이IT내부");
    }

    @Test
    @DisplayName("deptCd=2139, deptNm=IT개발팀 매핑")
    void dept_nm_mapped() {
        PartSrResult result = partSrService.summary(PERIOD, PART_CD);
        PartSrRow internalP = result.parts().stream()
                .filter(r -> PART_CD.equals(r.partCd()))
                .findFirst().orElseThrow();
        assertThat(internalP.deptCd()).isEqualTo("2139");
        assertThat(internalP.deptNm()).isEqualTo("IT개발팀");
    }

    @Test
    @DisplayName("totMm = srByClass mm 합계 (내부)")
    void tot_mm_equals_sum_of_class_mm() {
        PartSrResult result = partSrService.summary(PERIOD, PART_CD);
        PartSrRow internalP = result.parts().stream()
                .filter(r -> PART_CD.equals(r.partCd()))
                .findFirst().orElseThrow();
        double sumFromClass = internalP.srByClass().stream()
                .mapToDouble(SrClassCount::mm).sum();
        assertThat(internalP.totMm()).isEqualTo(sumFromClass);
    }

    // ──────────────────────────────────────────────────────────────────────

    private void insertDevIfAbsent(String empno, String empNm, String deptCd, String partCd) {
        if (developerMapper.findByEmpno(empno) == null) {
            developerMapper.insert(new Developer(empno, empNm, deptCd, partCd, "사원", "03", "Y", "01"));
        }
    }
}
