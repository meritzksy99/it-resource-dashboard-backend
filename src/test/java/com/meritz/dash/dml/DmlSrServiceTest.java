package com.meritz.dash.dml;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.mapper.app.DmlSrMapper;
import com.meritz.dash.mapper.app.DmlSrMapper.ScopeRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DmlSrService RBAC 단위 테스트.
 * 조회: 03=본인 담당건 · 02=본인 파트 · 01=본인 부서(파트 드릴다운) · ADMIN=전체.
 * 쓰기: 대상 SR 의 DEV_DEPT/PART 기준 fail-closed, 03 은 불가, 대상 없으면 404.
 */
class DmlSrServiceTest {

    private static final String YM = "202607";

    private DmlSrMapper mapper;
    private DmlSrService service;

    @BeforeEach
    void setup() {
        mapper = mock(DmlSrMapper.class);
        service = new DmlSrService(mapper);
        when(mapper.selectList(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    private static DmlSrItem item(String srNo, String checkYn, String improveYn) {
        return new DmlSrItem(srNo, YM, "18", "DML요청", "미등록", "04", "제목", "내용", "N",
                "요청자", "1000", null, null, "7451", "이개발", "IT개발팀", "2139", "P01",
                "20260701", null, null, checkYn, improveYn, null, null, "N", null);
    }

    // ── ① overview(전체/스냅샷): 역할 제한 없음, 필터만 ─────────────────

    @Test
    @DisplayName("overview: 필터 없음 → 전체(scope=all), 역할 무관")
    void overview_all() {
        AuthContext.set("7451", "03", "2139", "P01");     // 일반직원도 전체 조회

        DmlSrService.ListResult r = service.overview(YM, null, null);

        assertThat(r.scope()).isEqualTo("all");
        verify(mapper).selectList(YM, null, null, null, null, null);
    }

    @Test
    @DisplayName("overview: deptCd 필터 → scope=dept")
    void overview_dept_filter() {
        AuthContext.set("7451", "03", "2139", "P01");

        DmlSrService.ListResult r = service.overview(YM, "2139", null);

        assertThat(r.scope()).isEqualTo("dept");
        verify(mapper).selectList(YM, "2139", null, null, null, null);
    }

    @Test
    @DisplayName("overview: partCd 필터(빈 baseYm→이번달) → scope=part")
    void overview_part_filter() {
        AuthContext.set("admin", "ADMIN", null, null);

        DmlSrService.ListResult r = service.overview(YM, "2139", "P01");

        assertThat(r.scope()).isEqualTo("part");
        verify(mapper).selectList(YM, "2139", "P01", null, null, null);
    }

    // ── ② inspections(점검 대상): 본인 파트 스코프 ─────────────────────

    @Test
    @DisplayName("inspections 02/03: 본인 부서+파트만(파라미터 무시)")
    void inspections_leader_own_part() {
        AuthContext.set("6002", "02", "2735", "P12");

        DmlSrService.ListResult r = service.inspections(YM, "P14");   // 타파트 요청해도 무시

        assertThat(r.scope()).isEqualTo("part");
        verify(mapper).selectList(YM, "2735", "P12", null, null, null);
    }

    @Test
    @DisplayName("inspections 01: partCd 미지정 → 본인 부서 / 지정 → 드릴다운")
    void inspections_team_lead() {
        AuthContext.set("5355", "01", "2139", "P01");

        service.inspections(YM, null);
        verify(mapper).selectList(YM, "2139", null, null, null, null);

        service.inspections(YM, "P02");
        verify(mapper).selectList(YM, "2139", "P02", null, null, null);
    }

    @Test
    @DisplayName("inspections ADMIN: 미지정 전체 / partCd 지정 해당 파트")
    void inspections_admin() {
        AuthContext.set("admin", "ADMIN", null, null);

        DmlSrService.ListResult all = service.inspections(YM, null);
        assertThat(all.scope()).isEqualTo("all");
        verify(mapper).selectList(YM, null, null, null, null, null);

        service.inspections(YM, "P01");
        verify(mapper).selectList(YM, null, "P01", null, null, null);
    }

    // ── ③ improvements(개선 대상): 스코프 + 개선대상여부('Y') ──────────────

    @Test
    @DisplayName("improvements 02: 본인 부서+파트 중 개선대상(IMPROVE_YN='Y') 만")
    void improvements_leader_checked_only() {
        AuthContext.set("6002", "02", "2735", "P12");

        DmlSrService.ListResult r = service.improvements(YM, null);

        assertThat(r.scope()).isEqualTo("part");
        verify(mapper).selectList(YM, "2735", "P12", null, null, "Y");
    }

    @Test
    @DisplayName("improvements 01: 본인 부서 중 개선대상('Y')")
    void improvements_team_lead_checked() {
        AuthContext.set("5355", "01", "2139", "P01");

        service.improvements(YM, null);

        verify(mapper).selectList(YM, "2139", null, null, null, "Y");
    }

    @Test
    @DisplayName("checkedCount/improveCount: checkYn·improveYn='Y' 건수 집계")
    void counts_from_items() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.selectList(any(), any(), any(), any(), any(), any())).thenReturn(List.of(
                item("SR1", "Y", "Y"),
                item("SR2", "Y", "N"),
                item("SR3", "N", "N")));

        DmlSrService.ListResult r = service.overview(YM, null, null);

        assertThat(r.total()).isEqualTo(3);
        assertThat(r.checkedCount()).isEqualTo(2);
        assertThat(r.improveCount()).isEqualTo(1);
    }

    // ── setCheck 쓰기 RBAC ──────────────────────────────────────────

    @Test
    @DisplayName("setCheck 02: 본인 파트 SR → upsertCheck 호출(actor=본인)")
    void set_check_part_leader_own_part() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.findScopeRef("SR1")).thenReturn(new ScopeRef("SR1", "2735", "P12", "6004"));

        service.setCheck("SR1", "Y");

        verify(mapper).upsertCheck("SR1", "Y", "6002");
    }

    @Test
    @DisplayName("setImproveTarget 02: 본인 파트 SR 개선대상 Y → upsertImproveTarget 호출")
    void set_improve_target_own_part() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.findScopeRef("SR1")).thenReturn(new ScopeRef("SR1", "2735", "P12", "6004"));

        service.setImproveTarget("SR1", "Y");

        verify(mapper).upsertImproveTarget("SR1", "Y", "6002");
    }

    @Test
    @DisplayName("setImproveTarget 02: 타 파트 SR → 403, upsert 미호출")
    void set_improve_target_other_part_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.findScopeRef("SR9")).thenReturn(new ScopeRef("SR9", "2735", "P14", "6007"));

        assertThatThrownBy(() -> service.setImproveTarget("SR9", "Y"))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).upsertImproveTarget(any(), any(), any());
    }

    @Test
    @DisplayName("setImproveTarget: improveYn='X' → IllegalArgumentException (RBAC 판정 전)")
    void set_improve_target_invalid_yn() {
        AuthContext.set("admin", "ADMIN", null, null);

        assertThatThrownBy(() -> service.setImproveTarget("SR1", "X"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).findScopeRef(any());
    }

    @Test
    @DisplayName("setCheck 02: 타 파트 SR → 403, upsert 미호출")
    void set_check_part_leader_other_part_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(mapper.findScopeRef("SR9")).thenReturn(new ScopeRef("SR9", "2735", "P14", "6007"));

        assertThatThrownBy(() -> service.setCheck("SR9", "Y"))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).upsertCheck(any(), any(), any());
    }

    @Test
    @DisplayName("setCheck 02: 같은 파트코드·다른 부서 SR → 403 (파트코드 부서간 재사용 방지)")
    void set_check_part_leader_same_part_other_dept_forbidden() {
        AuthContext.set("6002", "02", "2735", "P01");                 // 리더 부서 2735
        when(mapper.findScopeRef("SR8")).thenReturn(new ScopeRef("SR8", "2139", "P01", "7451")); // 대상 부서 2139

        assertThatThrownBy(() -> service.setCheck("SR8", "Y"))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).upsertCheck(any(), any(), any());
    }

    @Test
    @DisplayName("setCheck 03: 일반직원 → 403")
    void set_check_staff_forbidden() {
        AuthContext.set("7451", "03", "2139", "P01");

        assertThatThrownBy(() -> service.setCheck("SR1", "Y"))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).findScopeRef(any());
        verify(mapper, never()).upsertCheck(any(), any(), any());
    }

    @Test
    @DisplayName("setCheck: 대상 SR 없음(findScopeRef=null) → NotFoundException")
    void set_check_missing_sr_not_found() {
        AuthContext.set("5355", "01", "2139", "P01");
        when(mapper.findScopeRef("SR0")).thenReturn(null);

        assertThatThrownBy(() -> service.setCheck("SR0", "Y"))
                .isInstanceOf(NotFoundException.class);
        verify(mapper, never()).upsertCheck(any(), any(), any());
    }

    @Test
    @DisplayName("setCheck: checkYn='X' → IllegalArgumentException (RBAC 판정 전)")
    void set_check_invalid_yn() {
        AuthContext.set("admin", "ADMIN", null, null);

        assertThatThrownBy(() -> service.setCheck("SR1", "X"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).findScopeRef(any());
        verify(mapper, never()).upsertCheck(any(), any(), any());
    }

    // ── saveImprovement ─────────────────────────────────────────────

    @Test
    @DisplayName("saveImprovement 01: 본인 부서 SR → upsertImprovement 호출(actor=본인)")
    void save_improvement_team_lead_own_dept() {
        AuthContext.set("5355", "01", "2139", "P01");
        when(mapper.findScopeRef("SR1")).thenReturn(new ScopeRef("SR1", "2139", "P01", "7451"));

        service.saveImprovement("SR1", "바인드 변수로 전환", "20260731", null, "정기점검");

        verify(mapper).upsertImprovement("SR1", "바인드 변수로 전환", "20260731", "N", "정기점검", "5355");
    }

    @Test
    @DisplayName("saveImprovement 01: 타 부서 SR → 403, upsert 미호출")
    void save_improvement_team_lead_other_dept_forbidden() {
        AuthContext.set("5355", "01", "2139", "P01");
        when(mapper.findScopeRef("SR9")).thenReturn(new ScopeRef("SR9", "2735", "P12", "6002"));

        assertThatThrownBy(() -> service.saveImprovement("SR9", "계획", null, "Y", null))
                .isInstanceOf(ForbiddenException.class);
        verify(mapper, never()).upsertImprovement(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("saveImprovement: cmptYn='X' → IllegalArgumentException")
    void save_improvement_invalid_cmpt_yn() {
        AuthContext.set("admin", "ADMIN", null, null);

        assertThatThrownBy(() -> service.saveImprovement("SR1", "계획", null, "X", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).upsertImprovement(any(), any(), any(), any(), any(), any());
    }
}
