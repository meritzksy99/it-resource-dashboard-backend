package com.meritz.dash.weekly;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.common.ConflictException;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.mapper.app.WeeklyReportMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WeeklyReportService 단위 테스트 — TDD Red.
 * <p>
 * 스켈레톤 단계에서는 서비스 메서드 본문이 {@link UnsupportedOperationException} 을 던지므로,
 * 아래 테스트는 모두 실패(Red)한다. Green 단계에서 실제 로직을 채우면 통과해야 하는 계약을 고정한다.
 */
class WeeklyReportServiceTest {

    private WeeklyReportMapper mapper;
    private WeeklySrLegacyReader legacyReader;
    private WeeklyReportService service;

    @BeforeEach
    void setup() {
        mapper = mock(WeeklyReportMapper.class);
        legacyReader = mock(WeeklySrLegacyReader.class);
        service = new WeeklyReportService(mapper, legacyReader);
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    private static WeeklyReportRow row(Long id, String week, String srNo, String regEmpno,
            String deptCd, String partCd) {
        return new WeeklyReportRow(id, week, srNo, "제목", regEmpno, deptCd, partCd,
                "내용", "20260710", "20260710", null, null);
    }

    // ── 지연사유(항상 선택) ─────────────────────────────────────────

    @Test
    @DisplayName("지연사유: planDate != srPlanDate 이고 delayReason 공백 → 사유 없이 정상 저장(항상 선택)")
    void create_delay_reason_optional_when_plan_differs_and_blank() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(legacyReader.read("SR1")).thenReturn(new SrRef("SR1", "제목", "20260720"));

        WeeklyReport r = service.create("20260709", "SR1", "내용", "20260710", " ");

        assertThat(r).isNotNull();
        assertThat(r.delayRsn()).isNull();
        assertThat(r.srPlanDate()).isEqualTo("20260720");      // SR_PLAN_DATE 스냅샷은 그대로 저장
        verify(mapper).insertReport(any());
    }

    @Test
    @DisplayName("지연사유: update 로 planDate 가 srPlanDate 와 달라져도 사유 없이 정상 수정(항상 선택)")
    void update_delay_reason_optional_when_plan_differs() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(mapper.selectReportById(1L)).thenReturn(row(1L, "20260709", "SR1", "7451", "2139", "P01"));
        when(legacyReader.read("SR1")).thenReturn(new SrRef("SR1", "제목", "20260710"));

        WeeklyReport r = service.update(1L, null, "20260720", null);

        assertThat(r).isNotNull();
        assertThat(r.planDate()).isEqualTo("20260720");
        assertThat(r.srPlanDate()).isEqualTo("20260710");      // 스냅샷 재조회 유지
        assertThat(r.delayRsn()).isNull();
        verify(mapper).updateReport(eq(1L), any(), eq("20260720"), eq("20260710"), eq(null), eq("7451"));
    }

    @Test
    @DisplayName("지연사유: planDate != srPlanDate 이고 delayReason 있음 → 등록 성공")
    void create_delay_reason_ok_when_plan_differs_and_provided() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(legacyReader.read("SR1")).thenReturn(new SrRef("SR1", "제목", "20260720"));

        WeeklyReport r = service.create("20260709", "SR1", "내용", "20260710", "일정 조율 지연");

        assertThat(r).isNotNull();
        verify(mapper).insertReport(any());
    }

    @Test
    @DisplayName("지연사유: planDate == srPlanDate → delayReason 선택(없어도 OK)")
    void create_delay_reason_optional_when_plan_matches() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(legacyReader.read("SR1")).thenReturn(new SrRef("SR1", "제목", "20260710"));

        WeeklyReport r = service.create("20260709", "SR1", "내용", "20260710", null);

        assertThat(r).isNotNull();
        verify(mapper).insertReport(any());
    }

    @Test
    @DisplayName("지연사유: srPlanDate null(SR 예정일 없음) → delayReason 선택(없어도 OK)")
    void create_delay_reason_optional_when_sr_plan_date_null() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(legacyReader.read("SR1")).thenReturn(new SrRef("SR1", "제목", null));

        WeeklyReport r = service.create("20260709", "SR1", "내용", "20260710", null);

        assertThat(r).isNotNull();
        verify(mapper).insertReport(any());
    }

    // ── SR 미존재 ───────────────────────────────────────────────────

    @Test
    @DisplayName("SR이 기간계에 존재하지 않으면 400(IllegalArgumentException, \"SR 없음\")")
    void create_sr_not_found_returns_400() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(legacyReader.read("SRNONE")).thenReturn(null);

        assertThatThrownBy(() -> service.create("20260709", "SRNONE", "내용", "20260710", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SR");
        verify(mapper, never()).insertReport(any());
    }

    // ── 중복 등록 409 ──────────────────────────────────────────────

    @Test
    @DisplayName("(week, srNo, 작성자) 중복 등록 → 409(ConflictException)")
    void create_duplicate_returns_409() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(legacyReader.read("SR1")).thenReturn(new SrRef("SR1", "제목", "20260710"));
        when(mapper.countReportByWeekSrEmp("20260709", "SR1", "7451")).thenReturn(1);

        assertThatThrownBy(() -> service.create("20260709", "SR1", "내용", "20260710", null))
                .isInstanceOf(ConflictException.class);
        verify(mapper, never()).insertReport(any());
    }

    @Test
    @DisplayName("count 선검사 통과 후 유니크 제약 레이스(DuplicateKeyException) → ConflictException(409) 변환")
    void create_duplicate_race_translated_to_conflict() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(legacyReader.read("SR1")).thenReturn(new SrRef("SR1", "제목", "20260710"));
        when(mapper.countReportByWeekSrEmp("20260709", "SR1", "7451")).thenReturn(0);
        when(mapper.insertReport(any())).thenThrow(new DuplicateKeyException("ORA-00001"));

        assertThatThrownBy(() -> service.create("20260709", "SR1", "내용", "20260710", null))
                .isInstanceOf(ConflictException.class);
    }

    // ── planDate 형식 검증(STRICT YYYYMMDD) ─────────────────────────

    @Test
    @DisplayName("create: planDate 형식 오류(하이픈 포함) → 400(IllegalArgumentException), insertReport 미호출")
    void create_plan_date_invalid_format_throws_400() {
        AuthContext.set("7451", "03", "2139", "P01");

        assertThatThrownBy(() -> service.create("20260709", "SR1", "내용", "2026-07-06", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertReport(any());
    }

    @Test
    @DisplayName("update: planDate 형식 오류(하이픈 포함) → 400(IllegalArgumentException), updateReport 미호출")
    void update_plan_date_invalid_format_throws_400() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(mapper.selectReportById(1L)).thenReturn(row(1L, "20260709", "SR1", "7451", "2139", "P01"));

        assertThatThrownBy(() -> service.update(1L, null, "2026-07-15", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).updateReport(any(), any(), any(), any(), any(), any());
    }

    // ── week 정규화 ─────────────────────────────────────────────────

    @Test
    @DisplayName("week 정규화: 수요일(20260708) → 그 주 목요일(20260709)")
    void normalize_week_wednesday_to_thursday() {
        assertThat(WeeklyReportService.normalizeWeek("20260708")).isEqualTo("20260709");
    }

    @Test
    @DisplayName("week 정규화: 일요일(20260712) → 같은 주 목요일(20260709)")
    void normalize_week_sunday_to_same_week_thursday() {
        assertThat(WeeklyReportService.normalizeWeek("20260712")).isEqualTo("20260709");
    }

    @Test
    @DisplayName("week 정규화: 월요일(20260706) → 같은 주 목요일(20260709)")
    void normalize_week_monday_to_thursday() {
        assertThat(WeeklyReportService.normalizeWeek("20260706")).isEqualTo("20260709");
    }

    @Test
    @DisplayName("week 정규화: 목요일(20260709) → 그대로")
    void normalize_week_thursday_unchanged() {
        assertThat(WeeklyReportService.normalizeWeek("20260709")).isEqualTo("20260709");
    }

    @Test
    @DisplayName("week 정규화: 형식 오류(하이픈 포함) → 400(IllegalArgumentException)")
    void normalize_week_invalid_format_throws_400() {
        assertThatThrownBy(() -> WeeklyReportService.normalizeWeek("2026-07-08"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("week 정규화: 실존하지 않는 날짜(20260231) → 400(IllegalArgumentException)")
    void normalize_week_nonexistent_date_throws_400() {
        assertThatThrownBy(() -> WeeklyReportService.normalizeWeek("20260231"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── RBAC 조회 스코프(4역할) ─────────────────────────────────────

    @Test
    @DisplayName("list 03: 본인만(파라미터 무시)")
    void list_staff_forced_self() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(mapper.selectReportsByWeek(anyString(), any(), any(), any())).thenReturn(java.util.List.of());

        WeeklyReportService.ListResult r = service.list("20260709", "9999", "P99");

        assertThat(r.scope()).isEqualTo("self");
        verify(mapper).selectReportsByWeek("20260709", "2139", "P01", "7451");
    }

    @Test
    @DisplayName("list 02: 본인 파트만(파라미터 무시)")
    void list_leader_forced_own_part() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectReportsByWeek(anyString(), any(), any(), any())).thenReturn(java.util.List.of());

        WeeklyReportService.ListResult r = service.list("20260709", null, null);

        assertThat(r.scope()).isEqualTo("part");
        verify(mapper).selectReportsByWeek("20260709", "2735", "P12", null);
    }

    @Test
    @DisplayName("list 01: 본인 부서(partCd 지정 시 드릴다운)")
    void list_team_lead_dept_scope() {
        AuthContext.set("5355", "01", "2139", "P01");
        when(mapper.selectReportsByWeek(anyString(), any(), any(), any())).thenReturn(java.util.List.of());

        service.list("20260709", null, null);
        verify(mapper).selectReportsByWeek("20260709", "2139", null, null);

        service.list("20260709", null, "P02");
        verify(mapper).selectReportsByWeek("20260709", "2139", "P02", null);
    }

    @Test
    @DisplayName("list ADMIN: 전체(dept/part 파라미터 드릴다운)")
    void list_admin_all_with_drilldown() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectReportsByWeek(anyString(), any(), any(), any())).thenReturn(java.util.List.of());

        WeeklyReportService.ListResult all = service.list("20260709", null, null);
        assertThat(all.scope()).isEqualTo("all");
        verify(mapper).selectReportsByWeek("20260709", null, null, null);

        service.list("20260709", "2139", "P01");
        verify(mapper).selectReportsByWeek("20260709", "2139", "P01", null);
    }

    // ── 단건 조회 스코프 밖 403 / 미존재 404 ─────────────────────────

    @Test
    @DisplayName("get: 스코프 밖 SR → 403(fail-closed)")
    void get_out_of_scope_forbidden() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(mapper.selectReportById(1L)).thenReturn(row(1L, "20260709", "SR1", "9999", "2735", "P12"));

        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("get: 미존재(id) → 404(NotFoundException)")
    void get_missing_not_found() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectReportById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(999L)).isInstanceOf(NotFoundException.class);
    }

    // ── 수정 권한 ────────────────────────────────────────────────────

    @Test
    @DisplayName("update: 작성자 본인 → 허용")
    void update_author_allowed() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(mapper.selectReportById(1L)).thenReturn(row(1L, "20260709", "SR1", "7451", "2139", "P01"));

        WeeklyReport r = service.update(1L, "수정 내용", null, null);

        assertThat(r).isNotNull();
        verify(mapper).updateReport(eq(1L), eq("수정 내용"), any(), any(), any(), eq("7451"));
    }

    @Test
    @DisplayName("update: 같은 dept+part 02 → 허용")
    void update_leader_same_part_allowed() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectReportById(2L)).thenReturn(row(2L, "20260709", "SR2", "6004", "2735", "P12"));

        WeeklyReport r = service.update(2L, "수정", null, null);

        assertThat(r).isNotNull();
    }

    @Test
    @DisplayName("update: ADMIN → 전체 허용")
    void update_admin_allowed() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectReportById(3L)).thenReturn(row(3L, "20260709", "SR3", "7451", "2139", "P01"));

        WeeklyReport r = service.update(3L, "수정", null, null);

        assertThat(r).isNotNull();
    }

    @Test
    @DisplayName("update: 타 파트 02 → 403")
    void update_leader_other_part_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectReportById(9L)).thenReturn(row(9L, "20260709", "SR9", "6007", "2735", "P14"));

        assertThatThrownBy(() -> service.update(9L, "수정", null, null))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).updateReport(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("update: 같은 partCd·다른 deptCd 02(파트코드 부서간 재사용) → 403")
    void update_leader_same_part_code_other_dept_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectReportById(9L)).thenReturn(row(9L, "20260709", "SR9", "7451", "2139", "P12"));

        assertThatThrownBy(() -> service.update(9L, "수정", null, null))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).updateReport(any(), any(), any(), any(), any(), any());
    }

    // ── leader-comment 권한(02 같은 파트만) ──────────────────────────

    @Test
    @DisplayName("leaderComment: 02 같은 dept+part → 허용")
    void leader_comment_same_part_allowed() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectReportById(1L)).thenReturn(row(1L, "20260709", "SR1", "6004", "2735", "P12"));

        WeeklyReport r = service.leaderComment(1L, "고생했습니다");

        assertThat(r).isNotNull();
        verify(mapper).updateLeaderComment(eq(1L), eq("고생했습니다"), eq("6002"));
    }

    @Test
    @DisplayName("leaderComment: 02 타 파트 → 403")
    void leader_comment_other_part_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectReportById(9L)).thenReturn(row(9L, "20260709", "SR9", "6007", "2735", "P14"));

        assertThatThrownBy(() -> service.leaderComment(9L, "의견"))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).updateLeaderComment(any(), any(), any());
    }

    @Test
    @DisplayName("leaderComment: 같은 partCd·다른 deptCd 02(파트코드 부서간 재사용) → 403")
    void leader_comment_same_part_code_other_dept_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectReportById(9L)).thenReturn(row(9L, "20260709", "SR9", "7451", "2139", "P12"));

        assertThatThrownBy(() -> service.leaderComment(9L, "의견"))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).updateLeaderComment(any(), any(), any());
    }

    // ── 삭제(작성자/ADMIN) ────────────────────────────────────────

    @Test
    @DisplayName("delete: 작성자 본인 → 허용")
    void delete_author_allowed() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(mapper.selectReportById(1L)).thenReturn(row(1L, "20260709", "SR1", "7451", "2139", "P01"));

        service.delete(1L);

        verify(mapper).deleteReport(1L);
    }

    @Test
    @DisplayName("delete: ADMIN → 허용(작성자 아니어도)")
    void delete_admin_allowed() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectReportById(1L)).thenReturn(row(1L, "20260709", "SR1", "7451", "2139", "P01"));

        service.delete(1L);

        verify(mapper).deleteReport(1L);
    }

    @Test
    @DisplayName("delete: 작성자도 ADMIN도 아니면 → 403")
    void delete_others_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.selectReportById(1L)).thenReturn(row(1L, "20260709", "SR1", "7451", "2139", "P01"));

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).deleteReport(any());
    }
}
