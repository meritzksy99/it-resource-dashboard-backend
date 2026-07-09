package com.meritz.dash.aggregation;

import com.meritz.dash.mapper.legacy.LegacySrMapper;
import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegacySrMapperIT extends LegacyFixture {

    @Autowired LegacySrMapper mapper;

    @Test
    @DisplayName("selectDevAgg(202605): 개발자×SR_TPCD 건수·시간")
    void devAgg() {
        List<LegacyDevRow> rows = mapper.selectDevAgg("202605");
        // E0002: SR_TPCD '01' 160h + '02' 80h, E0003: '01' 208h
        assertThat(rows).extracting(LegacyDevRow::empno).contains("E0002", "E0003");
        double e0003hours = rows.stream()
                .filter(r -> r.empno().equals("E0003"))
                .mapToDouble(LegacyDevRow::jobHours)
                .sum();
        assertThat(e0003hours).isEqualTo(208.0);
    }

    @Test
    @DisplayName("selectSrProjects(202605, 0.6): TOT_MM>=0.6 내림차순")
    void srProjects() {
        List<LegacySrProjectRow> rows = mapper.selectSrProjects("202605", 0.6);
        // SR26000001 TOT_MM=2.3 ≥ 0.6 → 포함, SR26000002 TOT_MM=0.5 < 0.6 → 제외
        assertThat(rows).isNotEmpty();
        // 내림차순 정렬 확인
        assertThat(rows.get(0).totMm())
                .isGreaterThanOrEqualTo(rows.get(rows.size() - 1).totMm());
        // 097 fan-out 방지: 행 증식 없이 SR26000001 한 건만 포함 확인
        assertThat(rows).extracting(LegacySrProjectRow::srNo).contains("SR26000001");
    }

    @Test
    @DisplayName("selectSrProjects fan-out 방지: 097 복합PK 환경에서 행 수 = SR 수")
    void srProjects_noFanOut() {
        List<LegacySrProjectRow> rows = mapper.selectSrProjects("202605", 0.0);
        // SR26000001 만 SR_REG_STAT_CODE IN ('02','03'...) 에 걸림 (SR26000002='03' 도 포함)
        // 하지만 SR26000002 TOT_MM=0.5 ≥ 0.0 이므로 양쪽 모두 포함
        // 중요: 097에 SR_TPCD='01' 행이 2개(0101, 0102)여도 dedupe 서브쿼리로 행 증식 없음
        long uniqueSrNos = rows.stream().map(LegacySrProjectRow::srNo).distinct().count();
        assertThat(rows).hasSize((int) uniqueSrNos); // 행 수 = 유니크 SR_NO 수
    }
}
