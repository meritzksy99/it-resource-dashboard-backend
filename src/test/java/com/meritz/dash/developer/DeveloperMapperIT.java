package com.meritz.dash.developer;

import com.meritz.dash.mapper.app.DeveloperMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperMapperIT extends AbstractOracleIT {

    @Autowired DeveloperMapper mapper;

    // dept 필터 테스트용 임시 픽스처: 운영 마이그레이션을 오염시키지 않도록
    // @BeforeEach 에서 INSERT, @AfterEach 에서 DELETE — 카운트 기반 IT(MigrationIT/CodeUnifyIT) 오염 방지
    private static final String FIXTURE_DEPT_A = "2139";
    private static final String FIXTURE_DEPT_B = "2140";
    private static final String FIXTURE_EMPNO_A = "E2139";
    private static final String FIXTURE_EMPNO_B = "E2140";

    @BeforeEach
    void insertDeptFixtures() {
        // 이미 존재하면 건너뜀 (공유 컨테이너 재사용 시 이전 테스트 잔여 가능성 방어)
        if (mapper.findByEmpno(FIXTURE_EMPNO_A) == null) {
            mapper.insert(new Developer(FIXTURE_EMPNO_A, "부서2139", FIXTURE_DEPT_A, "P01", "사원", "03", "Y", "01"));
        }
        if (mapper.findByEmpno(FIXTURE_EMPNO_B) == null) {
            mapper.insert(new Developer(FIXTURE_EMPNO_B, "부서2140", FIXTURE_DEPT_B, "P02", "사원", "03", "Y", "01"));
        }
    }

    @AfterEach
    void cleanup() {
        // dept 픽스처 정리 (다른 IT 카운트 단언 보호 — MigrationIT/CodeUnifyIT)
        mapper.deleteByEmpno(FIXTURE_EMPNO_A);
        mapper.deleteByEmpno(FIXTURE_EMPNO_B);
        // crud_roundtrip / insert_with_nullable 중 assertion 실패 시 잔여 레코드 정리
        mapper.deleteByEmpno("E9001");
        mapper.deleteByEmpno("E9002");
    }

    @Test
    @DisplayName("findAll(devYn='Y') → 시드 개발자 3명 이상, 전원 devYn=Y")
    void findAll_dev_only() {
        List<Developer> devs = mapper.findAll(null, null, "Y", null);
        assertThat(devs).hasSizeGreaterThanOrEqualTo(3);
        assertThat(devs).allMatch(d -> "Y".equals(d.devYn()));
    }

    @Test
    @DisplayName("findByEmpno('E0001') → 김팀장")
    void findByEmpno() {
        Developer d = mapper.findByEmpno("E0001");
        assertThat(d).isNotNull();
        assertThat(d.empNm()).isEqualTo("김팀장");
    }

    @Test
    @DisplayName("nullable 컬럼(deptCd/partCd/gradeCd/roleCd) null 로 insert → 예외 없이 성공, 되읽어 null 확인")
    void insert_with_nullable_columns_as_null() {
        // E9002: crud_roundtrip(E9001)과 사번 분리 — 공유 컨테이너 오염 방지
        Developer d = new Developer("E9002", "최소필드", null, null, null, null, "Y", "01");
        assertThat(mapper.insert(d)).isEqualTo(1);

        try {
            Developer found = mapper.findByEmpno("E9002");
            assertThat(found).isNotNull();
            assertThat(found.deptCd()).isNull();
            assertThat(found.partCd()).isNull();
            assertThat(found.gradeCd()).isNull();
            assertThat(found.roleCd()).isNull();
            assertThat(found.devYn()).isEqualTo("Y");
            assertThat(found.statusCd()).isEqualTo("01");
        } finally {
            // assertion 실패·예외 여부 무관하게 반드시 정리 — 다른 IT의 HR 카운트 단언 보호
            mapper.deleteByEmpno("E9002");
        }
    }

    @Test
    @DisplayName("insert→update→delete 라운드트립")
    void crud_roundtrip() {
        Developer n = new Developer("E9001", "신규자", "D101", "P03", "사원", "03", "Y", "01");
        assertThat(mapper.insert(n)).isEqualTo(1);
        assertThat(mapper.findByEmpno("E9001").empNm()).isEqualTo("신규자");

        Developer u = new Developer("E9001", "수정자", "D101", "P03", "대리", "03", "Y", "02");
        assertThat(mapper.update(u)).isEqualTo(1);
        assertThat(mapper.findByEmpno("E9001").statusCd()).isEqualTo("02");

        assertThat(mapper.deleteByEmpno("E9001")).isEqualTo(1);
        assertThat(mapper.findByEmpno("E9001")).isNull();
    }

    // ── dept 필터 테스트: @BeforeEach 픽스처(E2139/E2140)에 의존 ──

    @Test
    @DisplayName("findAll(deptCd='2139') → DEPT_CD=2139 인원만 반환, 전원 deptCd='2139'")
    void findAll_dept_only() {
        List<Developer> devs = mapper.findAll(FIXTURE_DEPT_A, null, null, null);
        assertThat(devs).isNotEmpty();
        assertThat(devs).allMatch(d -> FIXTURE_DEPT_A.equals(d.deptCd()));
    }

    @Test
    @DisplayName("findAll(deptCd='2139', partCd='P01') → DEPT_CD=2139 AND PART_CD=P01 조합 확인")
    void findAll_dept_and_part() {
        // E2139 는 DEPT_CD='2139', PART_CD='P01' 이므로 조합 필터에 포함됨
        List<Developer> devs = mapper.findAll(FIXTURE_DEPT_A, "P01", null, null);
        assertThat(devs).isNotEmpty();
        assertThat(devs).allMatch(d -> FIXTURE_DEPT_A.equals(d.deptCd()) && "P01".equals(d.partCd()));
    }

    @Test
    @DisplayName("findAll(deptCd=null) → 복수 부서 반환 (부서 필터 없음)")
    void findAll_no_dept_filter() {
        // V002 4명(D101 등) + 픽스처 2명(2139, 2140) = 최소 6명. 부서 미지정 시 전체 반환 검증
        List<Developer> devs = mapper.findAll(null, null, null, null);
        assertThat(devs).hasSizeGreaterThanOrEqualTo(4);
        // 여러 부서가 섞여 있어야 함 (D101, 2139, 2140 등)
        assertThat(devs.stream().map(Developer::deptCd).distinct().count())
                .isGreaterThanOrEqualTo(2);
    }
}
