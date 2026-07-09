package com.meritz.dash.devvolume;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.mapper.app.DevVolumeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DevVolumeService.series() 의 unit=dev 개인 드릴다운 RBAC 단위 테스트.
 * 개별 개발자의 월별 계획공수(jobMm)는 개인정보 — 역할별 접근 범위:
 * - ADMIN·팀장(01): 제한 없음(전체 개발자 조회 가능)
 * - 업무리더(02): 본인 파트원만. 타파트/미확인 사번은 본인 사번으로 강제(fail-closed)
 * - 일반직원(03)·기타: 본인 사번만
 * dept/part 등 집계 단위는 제한하지 않는다.
 */
class DevVolumeServiceRbacTest {

    private DevVolumeMapper mapper;
    private DevVolumeService service;

    @BeforeEach
    void setup() {
        mapper = mock(DevVolumeMapper.class);
        service = new DevVolumeService(mapper);
        when(mapper.findSeries(any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    // ── ADMIN / 팀장(01): 제한 없음 ─────────────────────────────────────

    @Test
    @DisplayName("ADMIN: unit=dev unitId=7451 그대로 조회")
    void admin_dev_passes_unitId_through() {
        AuthContext.set("admin", "ADMIN", null, null);

        service.series("dev", "6m", "7451");

        verify(mapper).findSeries(eq("DEV"), eq("7451"), anyString());
    }

    @Test
    @DisplayName("팀장(01): unit=dev 제한 없음 — 타인 사번 그대로 조회")
    void team_lead_dev_unrestricted() {
        AuthContext.set("5355", "01", "2139", "P01");

        service.series("dev", "6m", "9999");

        verify(mapper).findSeries(eq("DEV"), eq("9999"), anyString());
        verify(mapper, never()).findDeptPartByEmpno(any());
    }

    // ── 업무리더(02): 본인 파트원만 ─────────────────────────────────────

    @Test
    @DisplayName("업무리더(02): 요청 사번이 본인 파트원이면 그대로 조회")
    void part_leader_dev_same_part_allowed() {
        AuthContext.set("5355", "02", "2139", "P01");
        when(mapper.findDeptPartByEmpno("9999")).thenReturn(new DevDeptPart("2139", "P01"));

        service.series("dev", "6m", "9999");

        verify(mapper).findSeries(eq("DEV"), eq("9999"), anyString());
    }

    @Test
    @DisplayName("업무리더(02): 타파트 사번이면 본인 사번으로 강제(fail-closed)")
    void part_leader_dev_other_part_forced_to_self() {
        AuthContext.set("5355", "02", "2139", "P01");
        when(mapper.findDeptPartByEmpno("9999")).thenReturn(new DevDeptPart("2139", "P02"));

        service.series("dev", "6m", "9999");

        verify(mapper).findSeries(eq("DEV"), eq("5355"), anyString());
    }

    @Test
    @DisplayName("업무리더(02): 타부서 사번이면 본인 사번으로 강제(fail-closed)")
    void part_leader_dev_other_dept_forced_to_self() {
        AuthContext.set("5355", "02", "2139", "P01");
        when(mapper.findDeptPartByEmpno("9999")).thenReturn(new DevDeptPart("3000", "P01"));

        service.series("dev", "6m", "9999");

        verify(mapper).findSeries(eq("DEV"), eq("5355"), anyString());
    }

    @Test
    @DisplayName("업무리더(02): HR에 없는 사번이면 본인 사번으로 강제(fail-closed)")
    void part_leader_dev_unknown_empno_forced_to_self() {
        AuthContext.set("5355", "02", "2139", "P01");
        when(mapper.findDeptPartByEmpno("0000")).thenReturn(null);

        service.series("dev", "6m", "0000");

        verify(mapper).findSeries(eq("DEV"), eq("5355"), anyString());
    }

    @Test
    @DisplayName("업무리더(02): deptCd/partCd 없는 구토큰이면 본인 사번으로 폴백(HR 조회 안 함)")
    void part_leader_old_token_forced_to_self() {
        AuthContext.set("5355", "02", null, null);

        service.series("dev", "6m", "9999");

        verify(mapper).findSeries(eq("DEV"), eq("5355"), anyString());
        verify(mapper, never()).findDeptPartByEmpno(any());
    }

    @Test
    @DisplayName("업무리더(02): 본인 사번 요청은 HR 조회 없이 그대로 조회")
    void part_leader_self_request_no_lookup() {
        AuthContext.set("5355", "02", "2139", "P01");

        service.series("dev", "6m", "5355");

        verify(mapper).findSeries(eq("DEV"), eq("5355"), anyString());
        verify(mapper, never()).findDeptPartByEmpno(any());
    }

    // ── 일반직원(03)·기타: 본인만 ───────────────────────────────────────

    @Test
    @DisplayName("일반직원(03): unit=dev 에 타인 사번을 넣어도 본인 사번으로 강제")
    void regular_user_dev_forced_to_self() {
        AuthContext.set("9320", "03", "2139", "P01");

        service.series("dev", "6m", "9999");  // 타인 사번

        verify(mapper).findSeries(eq("DEV"), eq("9320"), anyString());
    }

    @Test
    @DisplayName("role null(구토큰): unit=dev 본인 사번으로 강제")
    void null_role_dev_forced_to_self() {
        AuthContext.set("9320", null, null, null);

        service.series("dev", "6m", "9999");

        verify(mapper).findSeries(eq("DEV"), eq("9320"), anyString());
    }

    // ── 집계 단위는 제한 없음 ───────────────────────────────────────────

    @Test
    @DisplayName("일반직원(03): unit=dept 집계는 제한 없음(클라이언트 unitId 그대로)")
    void regular_user_dept_not_scoped() {
        AuthContext.set("9320", "03", "2139", "P01");

        service.series("dept", "6m", "D101");

        verify(mapper).findSeries(eq("DEPT"), eq("D101"), anyString());
    }
}
