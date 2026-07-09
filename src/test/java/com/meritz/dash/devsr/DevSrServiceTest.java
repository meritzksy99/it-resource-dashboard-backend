package com.meritz.dash.devsr;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.code.CommonCode;
import com.meritz.dash.mapper.app.CodeMapper;
import com.meritz.dash.mapper.app.DevSrScopeMapper;
import com.meritz.dash.mapper.app.DevSrScopeMapper.HrRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DevSrService RBAC 스코프 + 상태별 그룹/한글명 보강 단위 테스트.
 * 역할별 조회 범위: 03=본인 · 02=본인 파트 · 01=본인 부서 · ADMIN=전체.
 */
class DevSrServiceTest {

    private DevSrScopeMapper scopeMapper;
    private DevSrLegacyReader legacyReader;
    private CodeMapper codeMapper;
    private DevSrService service;

    @BeforeEach
    void setup() {
        scopeMapper = mock(DevSrScopeMapper.class);
        legacyReader = mock(DevSrLegacyReader.class);
        codeMapper = mock(CodeMapper.class);
        service = new DevSrService(scopeMapper, legacyReader, codeMapper);

        when(codeMapper.findByGroup("SR_REG_STAT_CODE")).thenReturn(List.of(
                new CommonCode("SR_REG_STAT_CODE", "02", "SR등록", 2),
                new CommonCode("SR_REG_STAT_CODE", "03", "SR접수", 3),
                new CommonCode("SR_REG_STAT_CODE", "04", "SR진행", 4)));
        when(codeMapper.findByGroup("SR_TPCD")).thenReturn(List.of(
                new CommonCode("SR_TPCD", "01", "개발요청", 1),
                new CommonCode("SR_TPCD", "02", "유지보수", 2)));
        when(legacyReader.read(anyList())).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    // ── RBAC 스코프 ────────────────────────────────────────────────

    @Test
    @DisplayName("일반직원(03): empno 를 줘도 무시하고 본인만 조회")
    void staff_forced_self() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(scopeMapper.findRef("7451")).thenReturn(new HrRef("7451", "이개발", "2139", "P01"));

        DevSrService.Result r = service.developerSrs("9999");

        assertThat(r.scope()).isEqualTo("self");
        verify(legacyReader).read(List.of("7451"));
        verify(scopeMapper, never()).findRefs(any(), any());
    }

    @Test
    @DisplayName("업무리더(02): 본인 파트 사번 지정 → 허용")
    void part_leader_same_part_allowed() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(scopeMapper.findRef("6004")).thenReturn(new HrRef("6004", "임준호", "2735", "P12"));

        DevSrService.Result r = service.developerSrs("6004");

        assertThat(r.scope()).isEqualTo("part-one");
        verify(legacyReader).read(List.of("6004"));
    }

    @Test
    @DisplayName("업무리더(02): 타 파트 사번 지정 → 403")
    void part_leader_other_part_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(scopeMapper.findRef("6007")).thenReturn(new HrRef("6007", "서예린", "2735", "P14"));

        assertThatThrownBy(() -> service.developerSrs("6007"))
                .isInstanceOf(ForbiddenException.class);
        verify(legacyReader, never()).read(anyList());
    }

    @Test
    @DisplayName("업무리더(02): empno 미지정 → 본인 파트 전체")
    void part_leader_no_empno_whole_part() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(scopeMapper.findRefs("2735", "P12")).thenReturn(List.of(
                new HrRef("6002", "강도윤", "2735", "P12"),
                new HrRef("6004", "임준호", "2735", "P12")));

        DevSrService.Result r = service.developerSrs(null);

        assertThat(r.scope()).isEqualTo("part");
        verify(legacyReader).read(List.of("6002", "6004"));
    }

    @Test
    @DisplayName("팀장(01): 타 부서 사번 지정 → 403")
    void team_lead_other_dept_forbidden() {
        AuthContext.set("5355", "01", "2139", "P01");
        when(scopeMapper.findRef("6001")).thenReturn(new HrRef("6001", "정하늘", "2735", "P12"));

        assertThatThrownBy(() -> service.developerSrs("6001"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("팀장(01): empno 미지정 → 본인 부서 전체")
    void team_lead_no_empno_whole_dept() {
        AuthContext.set("5355", "01", "2139", "P01");
        when(scopeMapper.findRefs("2139", null)).thenReturn(List.of(
                new HrRef("7451", "이개발", "2139", "P01"),
                new HrRef("9320", "김성엽", "2139", "P01")));

        DevSrService.Result r = service.developerSrs(null);

        assertThat(r.scope()).isEqualTo("dept");
        verify(legacyReader).read(List.of("7451", "9320"));
    }

    @Test
    @DisplayName("업무리더(02): 존재하지 않는/퇴직 사번 지정(findRef=null) → 403 (fail-closed)")
    void part_leader_unknown_empno_forbidden() {
        AuthContext.set("6002", "02", "2735", "P12");
        when(scopeMapper.findRef("0000")).thenReturn(null);

        assertThatThrownBy(() -> service.developerSrs("0000"))
                .isInstanceOf(ForbiddenException.class);
        verify(legacyReader, never()).read(anyList());
    }

    @Test
    @DisplayName("ADMIN: 특정 사번 지정 → admin-one")
    void admin_specific_empno() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(scopeMapper.findRef("9320")).thenReturn(new HrRef("9320", "김성엽", "2139", "P01"));

        DevSrService.Result r = service.developerSrs("9320");

        assertThat(r.scope()).isEqualTo("admin-one");
        verify(legacyReader).read(List.of("9320"));
    }

    @Test
    @DisplayName("알 수 없는 상태/유형 코드 → 코드값 그대로 fallback")
    void unknown_code_fallback() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(scopeMapper.findRef("7451")).thenReturn(new HrRef("7451", "이개발", "2139", "P01"));
        when(legacyReader.read(anyList())).thenReturn(List.of(
                new DevSrRow("7451", "SRx", "99", "88", "20260101", "미지의 SR", "내용", 10.0, 0.1, "Y")));

        DevSrService.Result r = service.developerSrs(null);

        SrStatusGroup g = r.groups().get(0);
        assertThat(g.statusName()).isEqualTo("99");           // 코드표에 없으면 코드값 그대로
        assertThat(g.srs().get(0).srTpcdName()).isEqualTo("88");
    }

    @Test
    @DisplayName("ADMIN: empno 미지정 → 전체 재직자")
    void admin_all() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(scopeMapper.findRefs(null, null)).thenReturn(List.of(
                new HrRef("7451", "이개발", "2139", "P01")));

        DevSrService.Result r = service.developerSrs(null);

        assertThat(r.scope()).isEqualTo("all");
        verify(legacyReader).read(List.of("7451"));
    }

    // ── 상태별 그룹 + 한글명 보강 ────────────────────────────────────

    @Test
    @DisplayName("상태코드 오름차순 그룹, 계획 미수립은 jobMm/jobHours null")
    void grouping_and_enrichment() {
        AuthContext.set("7451", "03", "2139", "P01");
        when(scopeMapper.findRef("7451")).thenReturn(new HrRef("7451", "이개발", "2139", "P01"));
        when(legacyReader.read(anyList())).thenReturn(List.of(
                new DevSrRow("7451", "SR1", "04", "01", "20260520", "차세대 계좌개설", "계좌개설 화면 개발", 166.0, 1.0, "Y"),
                new DevSrRow("7451", "SR2", "02", "02", null, "약관 개정", "약관 문구 변경 접수", 0.0, 0.0, "N")));

        DevSrService.Result r = service.developerSrs(null);

        assertThat(r.groups()).extracting(SrStatusGroup::statusCode).containsExactly("02", "04");
        assertThat(r.totalSrs()).isEqualTo(2);

        SrStatusGroup g02 = r.groups().get(0);
        assertThat(g02.statusName()).isEqualTo("SR등록");
        DevSrItem registered = g02.srs().get(0);
        assertThat(registered.planEstablished()).isFalse();
        assertThat(registered.jobMm()).isNull();
        assertThat(registered.jobHours()).isNull();
        assertThat(registered.rflcScdlDate()).isNull();
        assertThat(registered.content()).isEqualTo("약관 문구 변경 접수");

        SrStatusGroup g04 = r.groups().get(1);
        assertThat(g04.statusName()).isEqualTo("SR진행");
        DevSrItem inProgress = g04.srs().get(0);
        assertThat(inProgress.srTpcdName()).isEqualTo("개발요청");
        assertThat(inProgress.empNm()).isEqualTo("이개발");
        assertThat(inProgress.content()).isEqualTo("계좌개설 화면 개발");
        assertThat(inProgress.jobMm()).isEqualTo(1.0);
        assertThat(inProgress.jobHours()).isEqualTo(166.0);
    }

    @Test
    @DisplayName("스코프가 비면 기간계 호출 없이 빈 결과")
    void empty_scope_no_legacy_call() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(scopeMapper.findRefs(null, null)).thenReturn(List.of());

        DevSrService.Result r = service.developerSrs(null);

        assertThat(r.groups()).isEmpty();
        assertThat(r.totalSrs()).isZero();
        verify(legacyReader, never()).read(anyList());
    }
}
