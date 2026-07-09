package com.meritz.dash.aggregation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AggregationRequestTest {

    @Test
    @DisplayName("단일 periodYm → 해당 월만 반환")
    void single() {
        assertThat(new AggregationRequest("202605", null, null).periods())
                .containsExactly("202605");
    }

    @Test
    @DisplayName("from~to 정상 범위 전개 (연도 넘김 포함)")
    void range_normal() {
        assertThat(new AggregationRequest(null, "202611", "202702").periods())
                .isEqualTo(List.of("202611", "202612", "202701", "202702"));
    }

    @Test
    @DisplayName("from~to 동일 월 → 단일 월")
    void range_same() {
        assertThat(new AggregationRequest(null, "202605", "202605").periods())
                .containsExactly("202605");
    }

    @Test
    @DisplayName("from > to → IllegalArgumentException")
    void range_from_greater_than_to() {
        assertThatThrownBy(() -> new AggregationRequest(null, "202606", "202605").periods())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("to보다 클 수 없습니다");
    }

    @Test
    @DisplayName("잘못된 형식(하이픈 포함) → IllegalArgumentException")
    void invalid_format_hyphen() {
        assertThatThrownBy(() -> new AggregationRequest("2026-05", null, null).periods())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잘못된 형식(13월) → IllegalArgumentException")
    void invalid_month_out_of_range() {
        assertThatThrownBy(() -> new AggregationRequest("202613", null, null).periods())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잘못된 형식(null periodYm, from/to 모두 null) → IllegalArgumentException")
    void all_null() {
        assertThatThrownBy(() -> new AggregationRequest(null, null, null).periods())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
