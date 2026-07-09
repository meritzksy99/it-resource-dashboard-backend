package com.meritz.dash.resource;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.config.MmProperties;
import com.meritz.dash.mapper.app.ResourceMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ResourceService.unitRange() 단위 테스트.
 */
class ResourceServiceTest {

    private ResourceMapper mapper;
    private ResourceService service;

    @BeforeEach
    void setup() {
        mapper = mock(ResourceMapper.class);
        MmProperties mm = new MmProperties(166, 1.0, 0.0);
        service = new ResourceService(mapper, mm);
        AuthContext.set("admin", "ADMIN", null, null);
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    // ──────────────────────────────────────────────────────────────────
    // 1. from/to 범위 조회 → findUnitRange 호출 및 ResourceView 매핑
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("from=202601·to=202603 범위 조회 시 findUnitRange(202601,202603,ALL,ALL) 호출 및 ResourceView 목록 반환")
    void from_to_range_calls_findUnitRange() {
        List<ResourceRow> rows = List.of(
                new ResourceRow("202601", "ALL", "ALL", 5, 5, 5.0, 4.0, 0.0),
                new ResourceRow("202602", "ALL", "ALL", 5, 5, 5.0, 4.5, 0.5),
                new ResourceRow("202603", "ALL", "ALL", 5, 5, 5.0, 3.8, 0.0)
        );
        when(mapper.findUnitRange("202601", "202603", "ALL", "ALL")).thenReturn(rows);

        ResourceRangeResult result = service.unitRange(null, "202601", "202603", "all", null);

        verify(mapper).findUnitRange("202601", "202603", "ALL", "ALL");
        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).periodYm()).isEqualTo("202601");
        assertThat(result.items().get(1).periodYm()).isEqualTo("202602");
        assertThat(result.items().get(2).periodYm()).isEqualTo("202603");
        assertThat(result.from()).isEqualTo("202601");
        assertThat(result.to()).isEqualTo("202603");
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. period만 입력 시 from=to=period 로 findUnitRange 호출
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("period=202605만 있고 from/to 없으면 findUnitRange(202605,202605,...) 호출")
    void period_only_uses_from_equals_to() {
        when(mapper.findUnitRange("202605", "202605", "ALL", "ALL"))
                .thenReturn(List.of(new ResourceRow("202605", "ALL", "ALL", 3, 3, 3.0, 2.4, 0.0)));

        ResourceRangeResult result = service.unitRange("202605", null, null, "all", null);

        verify(mapper).findUnitRange("202605", "202605", "ALL", "ALL");
        assertThat(result.from()).isEqualTo("202605");
        assertThat(result.to()).isEqualTo("202605");
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. from/to/period 모두 null → IllegalArgumentException
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("from·to·period 모두 null이면 IllegalArgumentException")
    void both_missing_throws() {
        assertThatThrownBy(() -> service.unitRange(null, null, null, "all", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. from > to → IllegalArgumentException
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("from=202603이 to=202601보다 늦으면 IllegalArgumentException")
    void from_after_to_throws() {
        assertThatThrownBy(() -> service.unitRange(null, "202603", "202601", "all", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. 24개월 초과 → IllegalArgumentException
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("from=202401·to=202612 은 24개월 초과이므로 IllegalArgumentException")
    void range_exceeds_24_months_throws() {
        // 202401 ~ 202612 = 24개월 이상 초과
        assertThatThrownBy(() -> service.unitRange(null, "202401", "202612", "all", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. YYYYMM 형식이 아닌 값 → IllegalArgumentException
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("from=2026(4자리)이면 YYYYMM 6자리 아님 → IllegalArgumentException")
    void invalid_format_throws() {
        assertThatThrownBy(() -> service.unitRange(null, "2026", "202606", "all", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // 6b. 월이 유효하지 않은 값 → IllegalArgumentException (W2)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("from=202613(13월)이면 유효하지 않은 월 → IllegalArgumentException")
    void invalid_month_throws() {
        assertThatThrownBy(() -> service.unitRange(null, "202613", "202613", "all", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("from=202600(0월)이면 유효하지 않은 월 → IllegalArgumentException")
    void zero_month_throws() {
        assertThatThrownBy(() -> service.unitRange(null, "202600", "202600", "all", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // 7. 각 행별 utilization = usedMm / availMm 계산
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("각 행 availMm=4.0·usedMm=3.2 → utilization=0.8 계산")
    void util_calculated_per_row() {
        ResourceRow row = new ResourceRow("202601", "ALL", "ALL", 4, 4, 4.0, 3.2, 0.0);
        when(mapper.findUnitRange("202601", "202601", "ALL", "ALL")).thenReturn(List.of(row));

        ResourceRangeResult result = service.unitRange("202601", null, null, "all", null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).utilization()).isEqualTo(0.8);
    }

    // ──────────────────────────────────────────────────────────────────
    // 8. availMm=0.0 → utilization=0.0 (분모 0 방어)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("availMm=0.0인 행은 utilization=0.0 (분모 0 방어)")
    void zero_avail_per_row_returns_zero_util() {
        ResourceRow row = new ResourceRow("202601", "ALL", "ALL", 0, 0, 0.0, 0.0, 0.0);
        when(mapper.findUnitRange("202601", "202601", "ALL", "ALL")).thenReturn(List.of(row));

        ResourceRangeResult result = service.unitRange("202601", null, null, "all", null);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).utilization()).isEqualTo(0.0);
    }

    // ──────────────────────────────────────────────────────────────────
    // 9. mapper 빈 리스트 반환 → 서비스도 빈 리스트 (에러 아님)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mapper가 빈 리스트 반환하면 서비스도 빈 리스트 반환 (예외 없음)")
    void empty_result_when_no_data() {
        when(mapper.findUnitRange(any(), any(), any(), any())).thenReturn(List.of());

        ResourceRangeResult result = service.unitRange(null, "202601", "202603", "all", null);

        assertThat(result.items()).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // 10. unit=dept 이고 unitId=null → IllegalArgumentException
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unit=dept이고 unitId=null이면 IllegalArgumentException")
    void dept_without_unitId_throws() {
        assertThatThrownBy(() -> service.unitRange(null, "202601", "202603", "dept", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // 11. 결과가 periodYm 오름차순 정렬 보장
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mapper가 순서 섞인 결과 반환해도 periodYm 오름차순 정렬 보장")
    void results_sorted_by_periodYm_ascending() {
        List<ResourceRow> unordered = List.of(
                new ResourceRow("202603", "ALL", "ALL", 5, 5, 5.0, 4.0, 0.0),
                new ResourceRow("202601", "ALL", "ALL", 5, 5, 5.0, 4.0, 0.0),
                new ResourceRow("202602", "ALL", "ALL", 5, 5, 5.0, 4.0, 0.0)
        );
        when(mapper.findUnitRange("202601", "202603", "ALL", "ALL")).thenReturn(unordered);

        ResourceRangeResult result = service.unitRange(null, "202601", "202603", "all", null);

        assertThat(result.items()).extracting(ResourceView::periodYm)
                .containsExactly("202601", "202602", "202603");
    }

    // ──────────────────────────────────────────────────────────────────
    // W3. 24개월 경계 테스트
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("202401~202512 정확히 24개월 — 경계 통과(IAE 없음)")
    void exactly_24_months_allowed() {
        when(mapper.findUnitRange("202401", "202512", "ALL", "ALL")).thenReturn(List.of());
        // 예외 없이 완료되어야 함
        assertThatCode(() -> service.unitRange(null, "202401", "202512", "all", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("202401~202601 25개월 — 경계 초과 IllegalArgumentException")
    void twenty_five_months_throws() {
        assertThatThrownBy(() -> service.unitRange(null, "202401", "202601", "all", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("24개월");
    }

    // ──────────────────────────────────────────────────────────────────
    // W4b. period와 from/to 동시 지정 시 from/to 우선
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("period와 from/to 동시 지정 시 from/to가 우선 적용됨")
    void from_to_takes_priority_over_period() {
        when(mapper.findUnitRange("202601", "202603", "ALL", "ALL"))
                .thenReturn(List.of(new ResourceRow("202601", "ALL", "ALL", 3, 3, 3.0, 2.4, 0.0)));

        ResourceRangeResult result = service.unitRange("202612", "202601", "202603", "all", null);

        verify(mapper).findUnitRange("202601", "202603", "ALL", "ALL");
        assertThat(result.from()).isEqualTo("202601");
        assertThat(result.to()).isEqualTo("202603");
    }

    // ──────────────────────────────────────────────────────────────────
    // W4c. from 단독(to 없음) 에러 메시지
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("from만 있고 to 없으면 '함께 지정' 에러")
    void from_without_to_throws_together_message() {
        assertThatThrownBy(() -> service.unitRange(null, "202601", null, "all", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("함께 지정");
    }

    // ──────────────────────────────────────────────────────────────────
    // developerUtil — 개발자별 가용률
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: empno 없으면 전체 개발자 조회 + utilization = usedMm/1.0 계산")
    void developerUtil_all_developers() {
        when(mapper.findDeveloperUtil("202606", null, null, null)).thenReturn(List.of(
                new DeveloperUtilRow("7451", "홍길동", "2139", "P01", "Y", 1.2),
                new DeveloperUtilRow("7452", "김철수", "2139", "P02", "Y", 0.0)
        ));

        List<DeveloperUtilView> list = service.developerUtil("202606", null);

        verify(mapper).findDeveloperUtil("202606", null, null, null);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).availMm()).isEqualTo(1.0);
        assertThat(list.get(0).utilization()).isEqualTo(1.2);   // 1.2 / 1.0
        assertThat(list.get(1).usedMm()).isEqualTo(0.0);
        assertThat(list.get(1).utilization()).isEqualTo(0.0);   // SR 없는 개발자
    }

    @Test
    @DisplayName("ADMIN: empno 지정 시 해당 개발자만 매퍼에 전달")
    void developerUtil_specific_developer() {
        when(mapper.findDeveloperUtil("202606", null, null, "7451")).thenReturn(List.of(
                new DeveloperUtilRow("7451", "홍길동", "2139", "P01", "Y", 0.8)
        ));

        List<DeveloperUtilView> list = service.developerUtil("202606", "7451");

        verify(mapper).findDeveloperUtil("202606", null, null, "7451");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).empno()).isEqualTo("7451");
        assertThat(list.get(0).utilization()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("ADMIN: empno 공백이면 전체(null)로 정규화되어 매퍼 호출")
    void developerUtil_blank_empno_normalized_to_null() {
        when(mapper.findDeveloperUtil("202606", null, null, null)).thenReturn(List.of());

        service.developerUtil("202606", "   ");

        verify(mapper).findDeveloperUtil("202606", null, null, null);
    }

    @Test
    @DisplayName("period 형식 오류(YYYYMM 아님) → IllegalArgumentException")
    void developerUtil_invalid_period_throws() {
        assertThatThrownBy(() -> service.developerUtil("2026", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.developerUtil("202613", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
