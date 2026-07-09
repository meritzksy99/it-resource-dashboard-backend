package com.meritz.dash.srproject;

import com.meritz.dash.aggregation.AggregationService;
import com.meritz.dash.mapper.app.SrProjectMapper;
import com.meritz.dash.support.LegacyFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SrProjectMapperIT extends LegacyFixture {
    @Autowired AggregationService agg;
    @Autowired SrProjectMapper mapper;

    @BeforeEach void seedAgg() throws Exception { agg.run("202605", "MANUAL"); }

    @Test @DisplayName("findTop: minMm 이상, totMm 내림차순, 5개씩")
    void top() {
        List<SrProjectView> list = mapper.findTop("202605", 0.6, null, 0, 5);
        assertThat(list).isNotEmpty();
        assertThat(list).allMatch(v -> v.totMm() >= 0.6);
    }
}
