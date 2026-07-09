package com.meritz.dash.partsr;

import com.meritz.dash.mapper.app.PartSrMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PartSrServiceTest {

    private PartSrMapper mapper;
    private PartSrService service;

    // 공통 코드맵: PART_CD, SR_CLS, DEPT_CD
    private static final List<Map<String, Object>> PART_CODES = List.of(
            Map.of("CD_VAL", "P01", "CD_NM", "금융상품"),
            Map.of("CD_VAL", "P02", "CD_NM", "계좌")
    );
    private static final List<Map<String, Object>> SR_CLS_CODES = List.of(
            Map.of("CD_VAL", "01", "CD_NM", "개발요청"),
            Map.of("CD_VAL", "02", "CD_NM", "유지보수"),
            Map.of("CD_VAL", "03", "CD_NM", "자료요청"),
            Map.of("CD_VAL", "99", "CD_NM", "기타")
    );
    private static final List<Map<String, Object>> DEPT_CODES = List.of(
            Map.of("CD_VAL", "2139", "CD_NM", "IT개발팀"),
            Map.of("CD_VAL", "9000", "CD_NM", "외주")
    );

    @BeforeEach
    void setUp() {
        mapper = mock(PartSrMapper.class);
        PartSrProperties props = new PartSrProperties("9000");
        service = new PartSrService(mapper, props);

        // 기본 코드맵 셋업
        when(mapper.findCodeMap("PART_CD")).thenReturn(PART_CODES);
        when(mapper.findCodeMap("SR_CLS")).thenReturn(SR_CLS_CODES);
        when(mapper.findCodeMap("DEPT_CD")).thenReturn(DEPT_CODES);
    }

    // ──────────────────────────────────────────────
    // period 형식 검증
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("period=null → IllegalArgumentException 400")
    void period_null_throws() {
        assertThatThrownBy(() -> service.summary(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("period='2026' (4자리) → IllegalArgumentException")
    void period_4digits_throws() {
        assertThatThrownBy(() -> service.summary("2026", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("period='202613' (월=13) → IllegalArgumentException")
    void period_month13_throws() {
        assertThatThrownBy(() -> service.summary("202613", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("period='202600' (월=00) → IllegalArgumentException")
    void period_month00_throws() {
        assertThatThrownBy(() -> service.summary("202600", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("period='202606' (유효) → 예외 없음")
    void period_valid_no_throw() {
        when(mapper.findRoster(null)).thenReturn(List.of());
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of());
        assertThatCode(() -> service.summary("202606", null)).doesNotThrowAnyException();
    }

    // ──────────────────────────────────────────────
    // part 형식 검증 (화이트리스트)
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("part에 이상 문자(따옴표/공백) → IllegalArgumentException")
    void part_invalid_chars_throws() {
        assertThatThrownBy(() -> service.summary("202606", "P01' OR '1'='1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summary("202606", "P 1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("part 21자 초과 → IllegalArgumentException")
    void part_too_long_throws() {
        assertThatThrownBy(() -> service.summary("202606", "A".repeat(21)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("part=P01 (유효) → 예외 없음")
    void part_valid_no_throw() {
        when(mapper.findRoster("P01")).thenReturn(List.of());
        when(mapper.findSrByPartClass(eq("202606"), eq("P01"))).thenReturn(List.of());
        assertThatCode(() -> service.summary("202606", "P01")).doesNotThrowAnyException();
    }

    // ──────────────────────────────────────────────
    // 빈 결과
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("명부·SR 모두 없으면 parts/outsourcing 모두 빈 리스트")
    void empty_roster_returns_empty_lists() {
        when(mapper.findRoster(null)).thenReturn(List.of());
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of());

        PartSrResult result = service.summary("202606", null);
        assertThat(result.parts()).isEmpty();
        assertThat(result.outsourcing()).isEmpty();
    }

    // ──────────────────────────────────────────────
    // 내부+외주 분리
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("P01 파트: 내부 2명(2139) + 외주 1명(9000) → parts P01 headcount=2, outsourcing P01 headcount=1")
    void internal_outsourcing_split() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01"),
                Map.of("EMPNO", "E002", "EMP_NM", "김동현", "DEPT_CD", "2139", "PART_CD", "P01"),
                Map.of("EMPNO", "E003", "EMP_NM", "외주자", "DEPT_CD", "9000", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of());

        PartSrResult result = service.summary("202606", null);
        PartSrRow internalP01 = result.parts().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();
        PartSrRow outsourcingP01 = result.outsourcing().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();

        assertThat(internalP01.headcount()).isEqualTo(2);
        assertThat(outsourcingP01.headcount()).isEqualTo(1);
    }

    @Test
    @DisplayName("내부 멤버 명에 외주자 포함되지 않음")
    void internal_memberNames_excludes_outsourcing() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01"),
                Map.of("EMPNO", "E003", "EMP_NM", "외주자", "DEPT_CD", "9000", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of());

        PartSrResult result = service.summary("202606", null);
        PartSrRow internalP01 = result.parts().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();
        PartSrRow outsourcingP01 = result.outsourcing().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();

        assertThat(internalP01.memberNames()).containsExactly("김성엽");
        assertThat(outsourcingP01.memberNames()).containsExactly("외주자");
    }

    @Test
    @DisplayName("외주만 있는 파트 → parts에 P01 없음, outsourcing에 P01 있음")
    void only_outsourcing_appears_in_outsourcing_list() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E003", "EMP_NM", "외주자", "DEPT_CD", "9000", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of());

        PartSrResult result = service.summary("202606", null);
        assertThat(result.parts().stream().anyMatch(r -> "P01".equals(r.partCd()))).isFalse();
        PartSrRow outsourcingP01 = result.outsourcing().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();
        assertThat(outsourcingP01.headcount()).isEqualTo(1);
    }

    @Test
    @DisplayName("내부만 있는 파트 → parts에 P01, outsourcing에 P01 없음")
    void only_internal_outsourcing_list_is_empty() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of());

        PartSrResult result = service.summary("202606", null);
        PartSrRow internalP01 = result.parts().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();
        assertThat(internalP01.headcount()).isEqualTo(1);
        assertThat(result.outsourcing()).isEmpty();
    }

    // ──────────────────────────────────────────────
    // SR_CLS별 합산
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("같은 파트 내부 2명 × SR_CLS='01' 각 srCnt=10 → srCnt 합계=20")
    void sr_cnt_sum_for_same_class() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01"),
                Map.of("EMPNO", "E002", "EMP_NM", "김동현", "DEPT_CD", "2139", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of(
                Map.of("PART_CD", "P01", "DEPT_CD", "2139", "SR_CLS", "01", "SR_CNT", 20L, "JOB_MM", 2.0)
        ));

        PartSrRow internalP01 = service.summary("202606", null).parts().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();
        assertThat(internalP01.srByClass()).hasSize(1);
        SrClassCount cls = internalP01.srByClass().get(0);
        assertThat(cls.srCls()).isEqualTo("01");
        assertThat(cls.srCnt()).isEqualTo(20);
    }

    @Test
    @DisplayName("totMm = srByClass mm 합계")
    void tot_mm_equals_sum_of_sr_class_mm() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of(
                Map.of("PART_CD", "P01", "DEPT_CD", "2139", "SR_CLS", "01", "SR_CNT", 5L, "JOB_MM", 1.5),
                Map.of("PART_CD", "P01", "DEPT_CD", "2139", "SR_CLS", "03", "SR_CNT", 3L, "JOB_MM", 0.5)
        ));

        PartSrRow internalP01 = service.summary("202606", null).parts().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();
        assertThat(internalP01.totMm()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("srByClass는 srCls 오름차순 정렬")
    void sr_by_class_sorted_by_sr_cls() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of(
                Map.of("PART_CD", "P01", "DEPT_CD", "2139", "SR_CLS", "99", "SR_CNT", 1L, "JOB_MM", 0.1),
                Map.of("PART_CD", "P01", "DEPT_CD", "2139", "SR_CLS", "01", "SR_CNT", 2L, "JOB_MM", 0.2),
                Map.of("PART_CD", "P01", "DEPT_CD", "2139", "SR_CLS", "03", "SR_CNT", 1L, "JOB_MM", 0.1)
        ));

        List<SrClassCount> cls = service.summary("202606", null).parts().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow()
                .srByClass();
        assertThat(cls).extracting(SrClassCount::srCls)
                .containsExactly("01", "03", "99");
    }

    @Test
    @DisplayName("srClsNm 코드명 올바르게 매핑")
    void sr_cls_name_mapped() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of(
                Map.of("PART_CD", "P01", "DEPT_CD", "2139", "SR_CLS", "01", "SR_CNT", 3L, "JOB_MM", 1.0)
        ));

        SrClassCount cls = service.summary("202606", null).parts().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow()
                .srByClass().get(0);
        assertThat(cls.srClsNm()).isEqualTo("개발요청");
    }

    // ──────────────────────────────────────────────
    // part 필터
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("part=P01 필터 → mapper.findRoster('P01'), mapper.findSrByPartClass('202606','P01') 호출")
    void part_filter_delegated_to_mapper() {
        when(mapper.findRoster("P01")).thenReturn(List.of());
        when(mapper.findSrByPartClass(eq("202606"), eq("P01"))).thenReturn(List.of());

        service.summary("202606", "P01");

        verify(mapper).findRoster("P01");
        verify(mapper).findSrByPartClass("202606", "P01");
    }

    // ──────────────────────────────────────────────
    // 파트 정렬
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("두 파트 P01, P02 → parts PART_CD 오름차순 반환")
    void parts_sorted_by_part_cd() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E003", "EMP_NM", "박개발", "DEPT_CD", "2139", "PART_CD", "P02"),
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of());

        PartSrResult result = service.summary("202606", null);
        assertThat(result.parts()).extracting(PartSrRow::partCd)
                .containsExactly("P01", "P02");
    }

    // ──────────────────────────────────────────────
    // 멤버 있는데 SR agg 없는 경우
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("멤버 있는데 SR agg 없으면 srByClass 빈 배열, totMm=0, headcount 채움")
    void member_without_sr_agg_returns_empty_sr() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of());

        PartSrRow internalP01 = service.summary("202606", null).parts().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();
        assertThat(internalP01.headcount()).isEqualTo(1);
        assertThat(internalP01.memberNames()).containsExactly("김성엽");
        assertThat(internalP01.totMm()).isEqualTo(0.0);
        assertThat(internalP01.srByClass()).isEmpty();
    }

    // ──────────────────────────────────────────────
    // 외주 SR 분리
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("외주(9000) SR → outsourcing srByClass에만 집계, parts에 없음")
    void outsourcing_sr_goes_to_outsourcing_group() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01"),
                Map.of("EMPNO", "E003", "EMP_NM", "외주자", "DEPT_CD", "9000", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of(
                Map.of("PART_CD", "P01", "DEPT_CD", "2139", "SR_CLS", "01", "SR_CNT", 5L, "JOB_MM", 1.0),
                Map.of("PART_CD", "P01", "DEPT_CD", "9000", "SR_CLS", "01", "SR_CNT", 3L, "JOB_MM", 0.5)
        ));

        PartSrResult result = service.summary("202606", null);
        PartSrRow internalP01 = result.parts().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();
        PartSrRow outsourcingP01 = result.outsourcing().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();

        assertThat(internalP01.srByClass()).hasSize(1);
        assertThat(internalP01.srByClass().get(0).srCnt()).isEqualTo(5);
        assertThat(outsourcingP01.srByClass()).hasSize(1);
        assertThat(outsourcingP01.srByClass().get(0).srCnt()).isEqualTo(3);
    }

    // ──────────────────────────────────────────────
    // partNm / deptNm 코드명 매핑
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("partNm 코드명(P01→금융상품) 및 deptNm(2139→IT개발팀) 올바르게 매핑")
    void part_name_mapped() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of());

        PartSrRow internalP01 = service.summary("202606", null).parts().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();
        assertThat(internalP01.partNm()).isEqualTo("금융상품");
        assertThat(internalP01.deptNm()).isEqualTo("IT개발팀");
    }

    // ──────────────────────────────────────────────
    // 외주코드 설정값 변경 테스트
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("외주코드 설정값 변경(9999)이면 9999 부서가 outsourcing에 포함")
    void outsourcing_dept_cd_configurable() {
        PartSrService svcWith9999 = new PartSrService(mapper, new PartSrProperties("9999"));
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "내부자", "DEPT_CD", "2139", "PART_CD", "P01"),
                Map.of("EMPNO", "E002", "EMP_NM", "외주자", "DEPT_CD", "9999", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of());

        PartSrResult result = svcWith9999.summary("202606", null);
        assertThat(result.outsourcing()).hasSize(1);
        assertThat(result.outsourcing().get(0).memberNames()).containsExactly("외주자");
        assertThat(result.parts()).hasSize(1);
    }

    // ──────────────────────────────────────────────
    // totMm FP 정밀도 테스트 (0.1 + 0.2 = 0.3)
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("0.1+0.2 FP — totMm = 0.3 정확히 (long 누적 보장)")
    void tot_mm_fp_precision() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of(
                Map.of("PART_CD", "P01", "DEPT_CD", "2139", "SR_CLS", "01", "SR_CNT", 1L, "JOB_MM", 0.1),
                Map.of("PART_CD", "P01", "DEPT_CD", "2139", "SR_CLS", "02", "SR_CNT", 1L, "JOB_MM", 0.2)
        ));

        PartSrRow row = service.summary("202606", null).parts().stream()
                .filter(r -> "P01".equals(r.partCd())).findFirst().orElseThrow();
        assertThat(row.totMm()).isEqualTo(0.3);
    }

    // ──────────────────────────────────────────────
    // PART_CD null 처리
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("PART_CD null 멤버 → partCd=null, partNm='미지정' 그룹")
    void null_part_cd_becomes_mijidjeong() {
        when(mapper.findRoster(null)).thenReturn(List.of(
                Map.of("EMPNO", "E001", "EMP_NM", "김성엽", "DEPT_CD", "2139", "PART_CD", "P01"),
                buildNoPartMember("E002", "미지정자", "2139")
        ));
        when(mapper.findSrByPartClass(eq("202606"), isNull())).thenReturn(List.of());

        PartSrResult result = service.summary("202606", null);
        boolean hasMijidjeong = result.parts().stream()
                .anyMatch(r -> r.partCd() == null && "미지정".equals(r.partNm()));
        assertThat(hasMijidjeong).isTrue();
    }

    // ──────────────────────────────────────────────
    // PartSrProperties 설정 누락 방어
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("outsourcing-dept-cd 설정 null/blank → IllegalStateException (기동 실패)")
    void properties_null_or_blank_throws() {
        assertThatThrownBy(() -> new PartSrProperties(null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PartSrProperties("  "))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Map.of() 는 null value 불허 — PART_CD 키 없이 HashMap으로 생성 */
    private static Map<String, Object> buildNoPartMember(String empno, String empNm, String deptCd) {
        Map<String, Object> m = new HashMap<>();
        m.put("EMPNO", empno);
        m.put("EMP_NM", empNm);
        m.put("DEPT_CD", deptCd);
        // PART_CD 키 없음 → strVal(m, "PART_CD", null) = null
        return m;
    }
}
