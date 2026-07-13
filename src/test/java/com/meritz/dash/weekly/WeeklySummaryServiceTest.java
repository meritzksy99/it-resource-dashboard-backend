package com.meritz.dash.weekly;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.mapper.app.WeeklyReportMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WeeklySummaryService 단위 테스트 — TDD Red.
 * <p>
 * 스켈레톤 단계에서는 서비스 메서드 본문이 {@link UnsupportedOperationException} 을 던지므로,
 * 아래 테스트는 모두 실패(Red)한다. Green 단계에서 실제 로직을 채우면 통과해야 하는 계약을 고정한다.
 */
class WeeklySummaryServiceTest {

    private WeeklyReportMapper mapper;
    private WeeklySummaryService service;

    @BeforeEach
    void setup() {
        mapper = mock(WeeklyReportMapper.class);
        service = new WeeklySummaryService(mapper);
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    private static WeeklySummaryRow row(Long id, String week, String deptCd, String partCd) {
        return new WeeklySummaryRow(id, week, deptCd, partCd, "취합 내용", "6002", null, null);
    }

    // ── 제출(항상 신규 INSERT) RBAC ──────────────────────────────────

    /** insertSummary 목이 IDENTITY 회수처럼 sumId 를 세팅하도록 스텁. */
    private void stubInsertAssignsId(long startId) {
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(startId);
        when(mapper.insertSummary(any())).thenAnswer(inv -> {
            inv.getArgument(0, WeeklySummaryInsert.class).setSumId(seq.getAndIncrement());
            return 1;
        });
    }

    @Test
    @DisplayName("submit 02: 본인 dept/part 강제(파라미터 무시), insertSummary 로 신규 등록")
    void submit_leader_forces_own_dept_part() {
        AuthContext.set("6002", "02", "2735", "P12");
        stubInsertAssignsId(1L);

        WeeklySummary r = service.submit("20260709", "취합 내용", null, "9999", "P99");

        assertThat(r.sumId()).isEqualTo(1L);
        assertThat(r.reports()).isEmpty();                         // rptIds 미지정 → 링크 없음
        verify(mapper).insertSummary(argThat(u -> u.getDeptCd().equals("2735") && u.getPartCd().equals("P12")));
        verify(mapper, never()).insertSummaryReports(any(), any());
    }

    @Test
    @DisplayName("submit ADMIN: dept/part 미지정 → 400(IllegalArgumentException)")
    void submit_admin_requires_dept_part() {
        AuthContext.set("admin", "ADMIN", null, null);

        assertThatThrownBy(() -> service.submit("20260709", "취합 내용", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertSummary(any());
    }

    @Test
    @DisplayName("submit ADMIN: dept/part 지정 시 정상 등록")
    void submit_admin_with_dept_part_ok() {
        AuthContext.set("admin", "ADMIN", null, null);
        stubInsertAssignsId(1L);

        WeeklySummary r = service.submit("20260709", "취합 내용", null, "2139", "P01");

        assertThat(r.sumId()).isEqualTo(1L);
        verify(mapper).insertSummary(argThat(u -> u.getDeptCd().equals("2139") && u.getPartCd().equals("P01")));
    }

    @Test
    @DisplayName("submit: 같은 주·같은 파트 2회 제출 → 둘 다 신규 INSERT(다건 허용)")
    void submit_same_week_part_twice_inserts_two_rows() {
        AuthContext.set("6002", "02", "2735", "P12");
        stubInsertAssignsId(5L);

        WeeklySummary first = service.submit("20260709", "1차 취합", null, null, null);
        WeeklySummary second = service.submit("20260709", "2차 취합", null, null, null);

        assertThat(first.sumId()).isEqualTo(5L);
        assertThat(second.sumId()).isEqualTo(6L);
        verify(mapper, times(2)).insertSummary(any());
    }

    @Test
    @DisplayName("submit: 02 도 ADMIN 도 아닌 역할(03) → 403(ForbiddenException), insertSummary 미호출")
    void submit_staff_role_forbidden() {
        AuthContext.set("7451", "03", "2139", "P01");

        assertThatThrownBy(() -> service.submit("20260709", "취합 내용", null, null, null))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).insertSummary(any());
    }

    // ── 제출: 개인 보고 선택(rptIds) ──────────────────────────────────

    private static WeeklyReportKey key(long rptId, String week, String deptCd, String partCd) {
        return new WeeklyReportKey(rptId, week, deptCd, partCd);
    }

    private static WeeklySummaryReportRow linked(long sumId, long rptId, String srNo) {
        return new WeeklySummaryReportRow(sumId, rptId, srNo, "SR 제목", "7451",
                "개인 보고 내용", "20260710", "20260710", null, null);
    }

    @Test
    @DisplayName("submit rptIds: 검증 통과 → summary INSERT 후 링크 일괄 INSERT, 선택 보고 임베드")
    void submit_with_rpt_ids_links_reports() {
        AuthContext.set("6002", "02", "2735", "P12");
        stubInsertAssignsId(1L);
        when(mapper.selectReportsForValidation(List.of(11L, 12L))).thenReturn(List.of(
                key(11L, "20260709", "2735", "P12"), key(12L, "20260709", "2735", "P12")));
        when(mapper.selectSummaryReports(List.of(1L))).thenReturn(List.of(
                linked(1L, 11L, "SR26000101"), linked(1L, 12L, "SR26000102")));

        WeeklySummary r = service.submit("20260709", "취합 내용", List.of(11L, 12L), null, null);

        verify(mapper).insertSummaryReports(1L, List.of(11L, 12L));
        assertThat(r.reports()).extracting(WeeklySummary.Report::rptId).containsExactly(11L, 12L);
        assertThat(r.reports().get(0).srNo()).isEqualTo("SR26000101");
    }

    @Test
    @DisplayName("submit rptIds: 미존재 rptId 포함 → 400, summary/링크 INSERT 미호출")
    void submit_with_unknown_rpt_id_rejected() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectReportsForValidation(List.of(11L, 99L)))
                .thenReturn(List.of(key(11L, "20260709", "2735", "P12")));   // 99 는 미존재

        assertThatThrownBy(() -> service.submit("20260709", "취합 내용", List.of(11L, 99L), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertSummary(any());
        verify(mapper, never()).insertSummaryReports(any(), any());
    }

    @Test
    @DisplayName("submit rptIds: 다른 주차 보고 → 400")
    void submit_with_other_week_report_rejected() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectReportsForValidation(List.of(11L)))
                .thenReturn(List.of(key(11L, "20260713", "2735", "P12")));   // 주차 불일치

        assertThatThrownBy(() -> service.submit("20260709", "취합 내용", List.of(11L), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertSummary(any());
    }

    @Test
    @DisplayName("submit rptIds: 타 파트 보고 → 400 (같은 partCd·다른 deptCd 포함)")
    void submit_with_other_part_report_rejected() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectReportsForValidation(List.of(11L)))
                .thenReturn(List.of(key(11L, "20260709", "2139", "P12")));   // 파트코드 같아도 타 부서

        assertThatThrownBy(() -> service.submit("20260709", "취합 내용", List.of(11L), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertSummary(any());
    }

    @Test
    @DisplayName("submit rptIds 빈 배열: 검증/링크 INSERT 없이 기존과 동일")
    void submit_with_empty_rpt_ids_behaves_as_before() {
        AuthContext.set("6002", "02", "2735", "P12");
        stubInsertAssignsId(1L);

        WeeklySummary r = service.submit("20260709", "취합 내용", List.of(), null, null);

        assertThat(r.sumId()).isEqualTo(1L);
        assertThat(r.reports()).isEmpty();
        verify(mapper, never()).selectReportsForValidation(any());
        verify(mapper, never()).insertSummaryReports(any(), any());
    }

    // ── 수정(같은 dept+part 02 / ADMIN) ───────────────────────────────

    @Test
    @DisplayName("update: 같은 dept+part 02 → 허용, TEAM_CMT 는 보존")
    void update_leader_same_part_allowed() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectSummaryById(1L)).thenReturn(
                new WeeklySummaryRow(1L, "20260709", "2735", "P12", "구본문", "6002", "팀장의견", "5355"));

        WeeklySummary r = service.update(1L, "수정 본문", null);

        assertThat(r.sumCntt()).isEqualTo("수정 본문");
        assertThat(r.teamCmt()).isEqualTo("팀장의견");          // TEAM_CMT 미변경
        verify(mapper).updateSummary(eq(1L), eq("수정 본문"), eq("6002"));
        verify(mapper, never()).deleteSummaryReports(any());   // rptIds null → 링크 불변
        verify(mapper, never()).insertSummaryReports(any(), any());
    }

    @Test
    @DisplayName("update: 타 파트 02 → 403, updateSummary 미호출")
    void update_leader_other_part_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectSummaryById(9L)).thenReturn(row(9L, "20260709", "2735", "P14"));

        assertThatThrownBy(() -> service.update(9L, "수정", null))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).updateSummary(any(), any(), any());
    }

    @Test
    @DisplayName("update: 같은 partCd·다른 deptCd 02(파트코드 부서간 재사용) → 403")
    void update_leader_same_part_code_other_dept_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectSummaryById(9L)).thenReturn(row(9L, "20260709", "2139", "P12"));

        assertThatThrownBy(() -> service.update(9L, "수정", null))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).updateSummary(any(), any(), any());
    }

    @Test
    @DisplayName("update: ADMIN → 전체 허용")
    void update_admin_allowed() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectSummaryById(9L)).thenReturn(row(9L, "20260709", "2735", "P12"));

        WeeklySummary r = service.update(9L, "수정", null);

        assertThat(r).isNotNull();
        verify(mapper).updateSummary(eq(9L), eq("수정"), eq("admin"));
    }

    @Test
    @DisplayName("update: 03 → 403(fail-closed)")
    void update_staff_forbidden() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(mapper.selectSummaryById(1L)).thenReturn(row(1L, "20260709", "2139", "P01"));

        assertThatThrownBy(() -> service.update(1L, "수정", null))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).updateSummary(any(), any(), any());
    }

    @Test
    @DisplayName("update: 미존재 → 404(NotFoundException)")
    void update_missing_not_found() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectSummaryById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(999L, "수정", null))
                .isInstanceOf(NotFoundException.class);
    }

    // ── 수정: 개인 보고 선택 교체(rptIds) ─────────────────────────────

    @Test
    @DisplayName("update rptIds: 기존 링크 전부 삭제 후 교체(삭제→INSERT 순서), 검증은 취합본 주차/파트 기준")
    void update_with_rpt_ids_replaces_links() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectSummaryById(1L)).thenReturn(row(1L, "20260709", "2735", "P12"));
        when(mapper.selectReportsForValidation(List.of(21L)))
                .thenReturn(List.of(key(21L, "20260709", "2735", "P12")));
        when(mapper.selectSummaryReports(List.of(1L))).thenReturn(List.of(linked(1L, 21L, "SR26000103")));

        WeeklySummary r = service.update(1L, "수정 본문", List.of(21L));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(mapper);
        inOrder.verify(mapper).deleteSummaryReports(1L);
        inOrder.verify(mapper).insertSummaryReports(1L, List.of(21L));
        verify(mapper).updateSummary(eq(1L), eq("수정 본문"), eq("6002"));
        assertThat(r.reports()).extracting(WeeklySummary.Report::rptId).containsExactly(21L);
    }

    @Test
    @DisplayName("update rptIds 빈 배열: 링크 전부 해제(INSERT 없음), content null 이면 본문 불변")
    void update_with_empty_rpt_ids_clears_links() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectSummaryById(1L)).thenReturn(row(1L, "20260709", "2735", "P12"));

        WeeklySummary r = service.update(1L, null, List.of());

        verify(mapper).deleteSummaryReports(1L);
        verify(mapper, never()).insertSummaryReports(any(), any());
        verify(mapper, never()).updateSummary(any(), any(), any());   // content null → 본문 불변
        assertThat(r.sumCntt()).isEqualTo("취합 내용");
    }

    @Test
    @DisplayName("update rptIds: 취합본 주차와 다른 보고 → 400, 기존 링크 미삭제")
    void update_with_mismatched_week_report_rejected() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectSummaryById(1L)).thenReturn(row(1L, "20260709", "2735", "P12"));
        when(mapper.selectReportsForValidation(List.of(21L)))
                .thenReturn(List.of(key(21L, "20260713", "2735", "P12")));

        assertThatThrownBy(() -> service.update(1L, null, List.of(21L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).deleteSummaryReports(any());
        verify(mapper, never()).insertSummaryReports(any(), any());
    }

    @Test
    @DisplayName("update: content 도 rptIds 도 null → 400")
    void update_without_content_and_rpt_ids_rejected() {
        AuthContext.set("6002", "02", "2735", "P12");

        assertThatThrownBy(() -> service.update(1L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).updateSummary(any(), any(), any());
        verify(mapper, never()).deleteSummaryReports(any());
    }

    // ── 삭제(같은 dept+part 02 / ADMIN) ───────────────────────────────

    @Test
    @DisplayName("delete: 같은 dept+part 02 → 허용")
    void delete_leader_same_part_allowed() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectSummaryById(1L)).thenReturn(row(1L, "20260709", "2735", "P12"));

        service.delete(1L);

        verify(mapper).deleteSummary(1L);
    }

    @Test
    @DisplayName("delete: 타 파트 02 → 403, deleteSummary 미호출")
    void delete_leader_other_part_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectSummaryById(9L)).thenReturn(row(9L, "20260709", "2735", "P14"));

        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).deleteSummary(any());
    }

    @Test
    @DisplayName("delete: ADMIN → 전체 허용")
    void delete_admin_allowed() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectSummaryById(9L)).thenReturn(row(9L, "20260709", "2735", "P12"));

        service.delete(9L);

        verify(mapper).deleteSummary(9L);
    }

    @Test
    @DisplayName("delete: 미존재 → 404(NotFoundException)")
    void delete_missing_not_found() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectSummaryById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(999L))
                .isInstanceOf(NotFoundException.class);
        verify(mapper, never()).deleteSummary(any());
    }

    // ── 조회 스코프 ───────────────────────────────────────────────────

    @Test
    @DisplayName("list 03/02: 본인 파트만")
    void list_staff_and_leader_own_part_only() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(mapper.selectSummariesByWeek(anyString(), any(), any())).thenReturn(List.of());

        WeeklySummaryService.ListResult r = service.list("20260709", "9999");

        assertThat(r.scope()).isEqualTo("part");
        verify(mapper).selectSummariesByWeek("20260709", "2139", "P01");
    }

    @Test
    @DisplayName("list 01: 본인 부서(파트별 목록)")
    void list_team_lead_whole_dept() {
        AuthContext.set("5355", "01", "2139", "P01");
        when(mapper.selectSummariesByWeek(anyString(), any(), any())).thenReturn(List.of());

        WeeklySummaryService.ListResult r = service.list("20260709", null);

        assertThat(r.scope()).isEqualTo("dept");
        verify(mapper).selectSummariesByWeek("20260709", "2139", null);
    }

    @Test
    @DisplayName("list ADMIN: 전체(deptCd 드릴다운)")
    void list_admin_all_with_drilldown() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectSummariesByWeek(anyString(), any(), any())).thenReturn(List.of());

        WeeklySummaryService.ListResult all = service.list("20260709", null);
        assertThat(all.scope()).isEqualTo("all");
        verify(mapper).selectSummariesByWeek("20260709", null, null);

        service.list("20260709", "2139");
        verify(mapper).selectSummariesByWeek("20260709", "2139", null);
    }

    @Test
    @DisplayName("list 02: 소속(dept/part) null → 빈 목록(fail-closed), 매퍼 미호출")
    void list_leader_null_affiliation_returns_empty() {
        AuthContext.set("6002", "02", null, null);

        WeeklySummaryService.ListResult r = service.list("20260709", null);

        assertThat(r.items()).isEmpty();
        assertThat(r.scope()).isEqualTo("part");
        verify(mapper, never()).selectSummariesByWeek(any(), any(), any());
    }

    @Test
    @DisplayName("list 03: partCd null → 빈 목록(fail-closed), 매퍼 미호출")
    void list_staff_null_part_returns_empty() {
        AuthContext.set("7451", "03", "2139", null);

        WeeklySummaryService.ListResult r = service.list("20260709", null);

        assertThat(r.items()).isEmpty();
        verify(mapper, never()).selectSummariesByWeek(any(), any(), any());
    }

    @Test
    @DisplayName("list 01: deptCd null → 빈 목록(fail-closed), 매퍼 미호출")
    void list_team_lead_null_dept_returns_empty() {
        AuthContext.set("5355", "01", null, "P01");

        WeeklySummaryService.ListResult r = service.list("20260709", null);

        assertThat(r.items()).isEmpty();
        assertThat(r.scope()).isEqualTo("dept");
        verify(mapper, never()).selectSummariesByWeek(any(), any(), any());
    }

    // ── 조회: 선택 보고 임베드(sumId 일괄 조회 후 그룹핑, N+1 금지) ──

    @Test
    @DisplayName("list: sumId 목록 한 방 조회 후 취합본별 그룹핑 — 링크 없는 취합본은 빈 배열")
    void list_embeds_selected_reports_grouped_by_summary() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(mapper.selectSummariesByWeek("20260709", "2139", "P01")).thenReturn(List.of(
                row(2L, "20260709", "2139", "P01"),                 // 최신(SUM_ID DESC) 우선
                row(1L, "20260709", "2139", "P01")));
        when(mapper.selectSummaryReports(List.of(2L, 1L))).thenReturn(List.of(
                linked(2L, 10L, "SR26000101"), linked(2L, 11L, "SR26000102")));   // sumId=1 은 링크 없음

        WeeklySummaryService.ListResult r = service.list("20260709", null);

        verify(mapper, times(1)).selectSummaryReports(List.of(2L, 1L));   // 한 방 쿼리(N+1 금지)
        assertThat(r.items()).hasSize(2);
        assertThat(r.items().get(0).reports()).extracting(WeeklySummary.Report::rptId)
                .containsExactly(10L, 11L);
        assertThat(r.items().get(1).reports()).isEmpty();
    }

    @Test
    @DisplayName("list: 취합본이 없으면 링크 조회(selectSummaryReports) 자체를 호출하지 않는다")
    void list_empty_summaries_skips_report_lookup() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(mapper.selectSummariesByWeek("20260709", "2139", "P01")).thenReturn(List.of());

        WeeklySummaryService.ListResult r = service.list("20260709", null);

        assertThat(r.items()).isEmpty();
        verify(mapper, never()).selectSummaryReports(any());
    }

    // ── 최종의견(01 본인 부서만/ADMIN 전체) ───────────────────────────

    @Test
    @DisplayName("finalComment 01: 본인 부서 것 → 허용")
    void final_comment_team_lead_own_dept_allowed() {
        AuthContext.set("5355", "01", "2139", "P01");
        when(mapper.selectSummaryById(1L)).thenReturn(row(1L, "20260709", "2139", "P01"));

        WeeklySummary r = service.finalComment(1L, "수고했습니다");

        assertThat(r).isNotNull();
        verify(mapper).updateFinalComment(eq(1L), eq("수고했습니다"), eq("5355"));
    }

    @Test
    @DisplayName("finalComment 01: 타 부서 것 → 403")
    void final_comment_team_lead_other_dept_forbidden() {
        AuthContext.set("5355", "01", "2139", "P01");
        when(mapper.selectSummaryById(9L)).thenReturn(row(9L, "20260709", "2735", "P12"));

        assertThatThrownBy(() -> service.finalComment(9L, "의견"))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).updateFinalComment(any(), any(), any());
    }

    @Test
    @DisplayName("finalComment ADMIN: 전체 허용")
    void final_comment_admin_allowed() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectSummaryById(9L)).thenReturn(row(9L, "20260709", "2735", "P12"));

        WeeklySummary r = service.finalComment(9L, "의견");

        assertThat(r).isNotNull();
        verify(mapper).updateFinalComment(eq(9L), eq("의견"), eq("admin"));
    }
}
