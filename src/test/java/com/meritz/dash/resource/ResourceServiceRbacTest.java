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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ResourceService.overtimeSummary() RBAC 범위 제한 단위 테스트 — HR_OVERTIME(야근시간) 기반.
 * AuthContext mock으로 역할별 mapper 호출 인자를 검증한다.
 */
class ResourceServiceRbacTest {

    private ResourceMapper mapper;
    private ResourceService service;

    @BeforeEach
    void setup() {
        mapper = mock(ResourceMapper.class);
        MmProperties mm = new MmProperties(166, 1.0, 0.0);
        service = new ResourceService(mapper, mm);

        // findOvertimeHoursByScope: 빈 리스트 반환
        when(mapper.findOvertimeHoursByScope(any(), any(), any(), any())).thenReturn(List.of());
        // countDevelopersByScope: 0 (avg 분모 0 방어 경로)
        when(mapper.countDevelopersByScope(any(), any(), any())).thenReturn(0);
        // findDeveloperUtil: 빈 리스트 반환
        when(mapper.findDeveloperUtil(any(), any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    // ──────────────────────────────────────────────────────────────────
    // 팀장(role '01') — 부서 전체, 클라이언트 파라미터 무시
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("팀장(01): dept=본인부서(2139), part=null, empno=null — 클라이언트 파라미터 무시")
    void team_lead_forced_to_own_dept() {
        AuthContext.set("5355", "01", "2139", "P01");

        service.overtimeSummary("202606", "9999", "P99"); // 클라이언트가 딴 부서/파트를 넘겨도

        verify(mapper).findOvertimeHoursByScope(
                eq("202606"), eq("2139"), isNull(), isNull());
    }

    @Test
    @DisplayName("팀장(01): deptCd null인 구 토큰 — empno=본인으로 폴백, 전사 노출 없음")
    void team_lead_null_dept_falls_back_to_self() {
        AuthContext.set("5355", "01", null, null);

        OvertimeSummary result = service.overtimeSummary("202606", null, null);

        assertThat(result.list()).isEmpty();
        // fail-closed: dept=null이면 empno=본인("5355")으로 매퍼 호출해야 한다
        // dept=null, empno=null(=전사 노출)이면 안 된다
        verify(mapper).findOvertimeHoursByScope(
                eq("202606"), isNull(), isNull(), eq("5355"));
    }

    @Test
    @DisplayName("업무리더(02): partCd null인 구 토큰 — empno=본인으로 폴백, 전사 노출 없음")
    void biz_leader_null_part_falls_back_to_self() {
        AuthContext.set("7777", "02", "2139", null);  // deptCd 있지만 partCd null

        service.overtimeSummary("202606", null, null);

        // fail-closed: partCd null → empno=본인("7777")으로 폴백
        verify(mapper).findOvertimeHoursByScope(
                eq("202606"), isNull(), isNull(), eq("7777"));
    }

    @Test
    @DisplayName("업무리더(02): deptCd·partCd 모두 null — empno=본인으로 폴백")
    void biz_leader_null_dept_and_part_falls_back_to_self() {
        AuthContext.set("7777", "02", null, null);

        service.overtimeSummary("202606", null, null);

        verify(mapper).findOvertimeHoursByScope(
                eq("202606"), isNull(), isNull(), eq("7777"));
    }

    // ──────────────────────────────────────────────────────────────────
    // 업무리더(role '02') — 본인 파트, 클라이언트 파라미터 무시
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("업무리더(02): dept=본인부서, part=본인파트, empno=null")
    void biz_leader_forced_to_own_part() {
        AuthContext.set("7777", "02", "2139", "P03");

        service.overtimeSummary("202606", "9999", "P99");

        verify(mapper).findOvertimeHoursByScope(
                eq("202606"), eq("2139"), eq("P03"), isNull());
    }

    // ──────────────────────────────────────────────────────────────────
    // 일반직원(role '03') — 본인만
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("일반직원(03): empno=본인, dept/part=null")
    void regular_user_forced_to_self() {
        AuthContext.set("9320", "03", "2139", "P01");

        service.overtimeSummary("202606", "9999", "P99");

        verify(mapper).findOvertimeHoursByScope(
                eq("202606"), isNull(), isNull(), eq("9320"));
    }

    // ──────────────────────────────────────────────────────────────────
    // ADMIN — 클라이언트 파라미터 그대로
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: 파라미터 dept/part 그대로 전달")
    void admin_passes_params_through() {
        AuthContext.set("admin", "ADMIN", null, null);

        service.overtimeSummary("202606", "2139", "P01");

        verify(mapper).findOvertimeHoursByScope(
                eq("202606"), eq("2139"), eq("P01"), isNull());
    }

    @Test
    @DisplayName("ADMIN: dept/part null이면 전체")
    void admin_null_params_all() {
        AuthContext.set("admin", "ADMIN", null, null);

        service.overtimeSummary("202606", null, null);

        verify(mapper).findOvertimeHoursByScope(
                eq("202606"), isNull(), isNull(), isNull());
    }

    // ──────────────────────────────────────────────────────────────────
    // 야근시간 환산 — otMinutes → overtimeHours (분÷60, 소수 1자리 반올림)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("사번별 overtimeHours = otMinutes÷60 소수 1자리 반올림 (774분 → 12.9h)")
    void view_converts_minutes_to_hours() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.findOvertimeHoursByScope(eq("202606"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(
                        new OvertimeRow("9320", "김성엽", "P01", 774),   // 12.9h 정확
                        new OvertimeRow("7452", "김철수", "P02", 100))); // 1.666… → 1.7h

        OvertimeSummary result = service.overtimeSummary("202606", null, null);

        assertThat(result.list()).hasSize(2);
        assertThat(result.list().get(0).otMinutes()).isEqualTo(774);
        assertThat(result.list().get(0).overtimeHours()).isEqualTo(12.9);
        assertThat(result.list().get(1).overtimeHours()).isEqualTo(1.7);
    }

    // ──────────────────────────────────────────────────────────────────
    // avgOvertimeHours — Σ분÷60 ÷ 스코프 재직 개발자 수 (역할별 스코프 분모)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("팀장: avg 분모는 본인 부서(dept) 재직 개발자 수 — Σ960분/60/4명 = 4.0h")
    void team_lead_avg_denominator_is_dept_headcount() {
        AuthContext.set("5355", "01", "2139", "P01");
        when(mapper.findOvertimeHoursByScope(eq("202606"), eq("2139"), isNull(), isNull()))
                .thenReturn(List.of(
                        new OvertimeRow("7451", "홍길동", "P01", 480),
                        new OvertimeRow("7452", "김철수", "P02", 480)));
        when(mapper.countDevelopersByScope(eq("2139"), isNull(), isNull())).thenReturn(4);

        OvertimeSummary result = service.overtimeSummary("202606", null, null);

        // (480+480)/60/4 = 4.0
        assertThat(result.avgOvertimeHours()).isEqualTo(4.0);
        verify(mapper).countDevelopersByScope(eq("2139"), isNull(), isNull());
    }

    @Test
    @DisplayName("업무리더: avg 분모는 본인 파트(dept+part) 재직 개발자 수 — Σ90분/60/3명 = 0.5h")
    void biz_leader_avg_denominator_is_part_headcount() {
        AuthContext.set("7777", "02", "2139", "P03");
        when(mapper.findOvertimeHoursByScope(eq("202606"), eq("2139"), eq("P03"), isNull()))
                .thenReturn(List.of(new OvertimeRow("7451", "홍길동", "P03", 90)));
        when(mapper.countDevelopersByScope(eq("2139"), eq("P03"), isNull())).thenReturn(3);

        OvertimeSummary result = service.overtimeSummary("202606", null, null);

        // 90/60/3 = 0.5
        assertThat(result.avgOvertimeHours()).isEqualTo(0.5);
        verify(mapper).countDevelopersByScope(eq("2139"), eq("P03"), isNull());
    }

    @Test
    @DisplayName("일반직원: avg 분모는 본인 1명 — 774분/60/1 = 12.9h")
    void regular_user_avg_denominator_is_self() {
        AuthContext.set("9320", "03", "2139", "P01");
        when(mapper.findOvertimeHoursByScope(eq("202606"), isNull(), isNull(), eq("9320")))
                .thenReturn(List.of(new OvertimeRow("9320", "김성엽", "P01", 774)));
        when(mapper.countDevelopersByScope(isNull(), isNull(), eq("9320"))).thenReturn(1);

        OvertimeSummary result = service.overtimeSummary("202606", null, null);

        assertThat(result.avgOvertimeHours()).isEqualTo(12.9);
        verify(mapper).countDevelopersByScope(isNull(), isNull(), eq("9320"));
    }

    @Test
    @DisplayName("ADMIN 전체: avg 분모는 전체 재직 개발자 수 — Σ120분/60/4명 = 0.5h")
    void admin_avg_denominator_is_all_headcount() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.findOvertimeHoursByScope(eq("202606"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(new OvertimeRow("7451", "홍길동", "P01", 120)));
        when(mapper.countDevelopersByScope(isNull(), isNull(), isNull())).thenReturn(4);

        OvertimeSummary result = service.overtimeSummary("202606", null, null);

        assertThat(result.avgOvertimeHours()).isEqualTo(0.5);
        verify(mapper).countDevelopersByScope(isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("스코프 인원 0명(분모 0)이면 avg=0.0 (방어)")
    void avg_zero_when_headcount_zero() {
        AuthContext.set("admin", "ADMIN", null, null);
        when(mapper.findOvertimeHoursByScope(eq("202606"), isNull(), isNull(), isNull()))
                .thenReturn(List.of(new OvertimeRow("7451", "홍길동", "P01", 600)));
        when(mapper.countDevelopersByScope(isNull(), isNull(), isNull())).thenReturn(0);

        OvertimeSummary result = service.overtimeSummary("202606", null, null);

        assertThat(result.avgOvertimeHours()).isEqualTo(0.0);
    }

    // ──────────────────────────────────────────────────────────────────
    // developerUtil — /resource/developers RBAC (overtime와 동일 정책)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: dept=null,part=null, empno=client 그대로 (전체 또는 특정)")
    void dev_admin_passes_empno_through() {
        AuthContext.set("admin", "ADMIN", null, null);

        service.developerUtil("202606", "7451");

        verify(mapper).findDeveloperUtil(eq("202606"), isNull(), isNull(), eq("7451"));
    }

    @Test
    @DisplayName("ADMIN: empno 없으면 전체(dept/part/empno 모두 null)")
    void dev_admin_null_empno_all() {
        AuthContext.set("admin", "ADMIN", null, null);

        service.developerUtil("202606", null);

        verify(mapper).findDeveloperUtil(eq("202606"), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("팀장(01): 본인 부서 전체 — dept=본인부서, client empno 무시")
    void dev_team_lead_scoped_to_own_dept() {
        AuthContext.set("5355", "01", "2139", "P01");

        service.developerUtil("202606", "9999");  // 타인 사번 넘겨도 무시

        verify(mapper).findDeveloperUtil(eq("202606"), eq("2139"), isNull(), isNull());
    }

    @Test
    @DisplayName("팀장(01): deptCd null 구 토큰 — empno=본인 폴백(전사 노출 없음)")
    void dev_team_lead_null_dept_falls_back_to_self() {
        AuthContext.set("5355", "01", null, null);

        service.developerUtil("202606", null);

        verify(mapper).findDeveloperUtil(eq("202606"), isNull(), isNull(), eq("5355"));
    }

    @Test
    @DisplayName("업무리더(02): 본인 파트 — dept+part=본인, client empno 무시")
    void dev_biz_leader_scoped_to_own_part() {
        AuthContext.set("7777", "02", "2139", "P03");

        service.developerUtil("202606", "9999");

        verify(mapper).findDeveloperUtil(eq("202606"), eq("2139"), eq("P03"), isNull());
    }

    @Test
    @DisplayName("업무리더(02): partCd null 폴백 — empno=본인")
    void dev_biz_leader_null_part_falls_back_to_self() {
        AuthContext.set("7777", "02", "2139", null);

        service.developerUtil("202606", null);

        verify(mapper).findDeveloperUtil(eq("202606"), isNull(), isNull(), eq("7777"));
    }

    @Test
    @DisplayName("일반직원(03): 본인만 — empno=본인, client empno 무시")
    void dev_regular_user_scoped_to_self() {
        AuthContext.set("9320", "03", "2139", "P01");

        service.developerUtil("202606", "9999");  // 타인 사번 넘겨도 본인으로 강제

        verify(mapper).findDeveloperUtil(eq("202606"), isNull(), isNull(), eq("9320"));
    }
}
