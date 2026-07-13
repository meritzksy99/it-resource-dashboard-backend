package com.meritz.dash.mapper.app;

import com.meritz.dash.weekly.WeeklyReportInsert;
import com.meritz.dash.weekly.WeeklyReportKey;
import com.meritz.dash.weekly.WeeklyReportRow;
import com.meritz.dash.weekly.WeeklySummaryInsert;
import com.meritz.dash.weekly.WeeklySummaryReportRow;
import com.meritz.dash.weekly.WeeklySummaryRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 주간보고(DASH_WKLY_RPT) + 파트 취합본(DASH_WKLY_SUM) app DB 매퍼.
 * <p>
 * 스켈레톤(Red) 단계 — {@code mapper/app/WeeklyReportMapper.xml} 은 namespace 만 존재하고
 * 아래 각 메서드에 대응하는 SQL statement 는 Green 단계에서 작성한다.
 */
public interface WeeklyReportMapper {

    // ── 개인 보고(DASH_WKLY_RPT) ────────────────────────────────────

    /** INSERT. RPT_ID(IDENTITY) 는 {@code row.setRptId(..)} 로 세팅된다(useGeneratedKeys). */
    int insertReport(WeeklyReportInsert row);

    /** 스코프 조회. deptCd/partCd/regEmpno 는 nullable 필터(하나만 좁혀도 됨). */
    List<WeeklyReportRow> selectReportsByWeek(@Param("weekYmd") String weekYmd, @Param("deptCd") String deptCd,
            @Param("partCd") String partCd, @Param("regEmpno") String regEmpno);

    /** 단건 조회. 없으면 null. */
    WeeklyReportRow selectReportById(@Param("rptId") Long rptId);

    /** (week, srNo, regEmpno) 중복 등록 여부 사전 검사용 건수. */
    int countReportByWeekSrEmp(@Param("weekYmd") String weekYmd, @Param("srNo") String srNo,
            @Param("regEmpno") String regEmpno);

    /** 부분 수정(content/planDate/srPlanDate/delayRsn). */
    int updateReport(@Param("rptId") Long rptId, @Param("rptCntt") String rptCntt,
            @Param("planDate") String planDate, @Param("srPlanDate") String srPlanDate,
            @Param("delayRsn") String delayRsn, @Param("actor") String actor);

    /** 업무리더 추가의견(LEADER_CMT) 갱신. */
    int updateLeaderComment(@Param("rptId") Long rptId, @Param("comment") String comment,
            @Param("actor") String actor);

    int deleteReport(@Param("rptId") Long rptId);

    // ── 파트 취합본(DASH_WKLY_SUM) ──────────────────────────────────

    /**
     * INSERT. SUM_ID(IDENTITY) 는 {@code row.setSumId(..)} 로 세팅된다(useGeneratedKeys).
     * 같은 (week, deptCd, partCd) 에 여러 건 허용(UK 없음 — V022).
     */
    int insertSummary(WeeklySummaryInsert row);

    /** deptCd 지정 시 부서 전체(파트별), 지정 없으면 partCd 로 좁혀 단일 파트만 반환(호출측에서 조합). */
    List<WeeklySummaryRow> selectSummariesByWeek(@Param("weekYmd") String weekYmd, @Param("deptCd") String deptCd,
            @Param("partCd") String partCd);

    /** 단건 조회(update/delete/final-comment 대상 확인용). 없으면 null. */
    WeeklySummaryRow selectSummaryById(@Param("sumId") Long sumId);

    /** 취합본 본문(SUM_CNTT)만 수정 — TEAM_CMT/TEAM_CMT_EMPNO 는 건드리지 않는다. */
    int updateSummary(@Param("sumId") Long sumId, @Param("sumCntt") String sumCntt,
            @Param("actor") String actor);

    int deleteSummary(@Param("sumId") Long sumId);

    /** 팀장 최종의견(TEAM_CMT/TEAM_CMT_EMPNO) 갱신. */
    int updateFinalComment(@Param("sumId") Long sumId, @Param("comment") String comment,
            @Param("actor") String actor);

    // ── 취합본-개인보고 링크(DASH_WKLY_SUM_RPT, V023) ─────────────────

    /** 선택 링크 일괄 INSERT(INSERT SELECT — 중복 rptId 는 자연 dedup). rptIds 비어 있으면 호출 금지. */
    int insertSummaryReports(@Param("sumId") Long sumId, @Param("rptIds") List<Long> rptIds);

    /** 해당 취합본의 링크 전부 삭제(교체용). */
    int deleteSummaryReports(@Param("sumId") Long sumId);

    /** rptIds 선택 검증용 키 조회 — 미존재 id 는 결과에 없음. rptIds 비어 있으면 호출 금지. */
    List<WeeklyReportKey> selectReportsForValidation(@Param("rptIds") List<Long> rptIds);

    /** sumIds 의 선택 보고 임베드 일괄 조회(링크 ⨝ 보고, N+1 금지). SUM_ID, RPT_ID 오름차순. */
    List<WeeklySummaryReportRow> selectSummaryReports(@Param("sumIds") List<Long> sumIds);
}
