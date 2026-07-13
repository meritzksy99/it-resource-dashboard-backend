package com.meritz.dash.weekly;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.mapper.app.WeeklyReportMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 파트 취합본(DASH_WKLY_SUM) 등록/조회/수정/삭제 + 팀장 최종의견.
 * <p>
 * 등록은 02=본인 dept/part 강제(파라미터 무시), ADMIN=dept/part 필수.
 * 항상 신규 INSERT — 같은 (WEEK_YMD, DEPT_CD, PART_CD) 에 여러 건 허용(V022 에서 UK 제거).
 * 수정/삭제는 같은 dept+part 의 02 또는 ADMIN 만(fail-closed).
 */
@Service
public class WeeklySummaryService {

    private final WeeklyReportMapper mapper;

    public WeeklySummaryService(WeeklyReportMapper mapper) {
        this.mapper = mapper;
    }

    /** 목록 + 스코프 라벨. */
    public record ListResult(List<WeeklySummary> items, String scope) {}

    /**
     * 파트 취합본 등록(항상 신규 INSERT → 201). 02=본인 dept/part 강제(파라미터 무시),
     * ADMIN=dept/part 필수(없으면 400), 그 외 역할은 403(defense in depth — 컨트롤러
     * {@code @Auth} 와 이중 방어). 같은 주·같은 파트에 여러 건 등록 가능.
     * <p>
     * rptIds(선택): 파트원 개인 보고를 골라 취합본을 구성. 각 id 가 ①존재 ②같은 주차
     * ③취합본 대상 dept/part 소속이어야 하며 하나라도 어긋나면 400(전체 거부, INSERT 없음).
     * null/빈 배열이면 링크 없이 기존과 동일.
     */
    @Transactional(transactionManager = "appTxManager")
    public WeeklySummary submit(String week, String content, List<Long> rptIds, String deptCd, String partCd) {
        String weekYmd = WeeklyReportService.normalizeWeek(week);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 는 필수입니다");
        }
        String role = AuthContext.role();
        String dept;
        String part;
        if ("ADMIN".equals(role)) {
            dept = blankToNull(deptCd);
            part = blankToNull(partCd);
            if (dept == null || part == null) {
                throw new IllegalArgumentException("ADMIN 은 deptCd/partCd 를 지정해야 합니다");
            }
        } else if ("02".equals(role)) {                            // 업무리더(02): 본인 소속 강제(파라미터 무시)
            dept = AuthContext.deptCd();
            part = AuthContext.partCd();
            if (dept == null || part == null) {                    // 소속 미상 → fail-closed
                throw new ForbiddenException("소속(dept/part) 정보가 없어 취합본을 제출할 수 없습니다");
            }
        } else {                                                   // 02/ADMIN 외 → fail-closed
            throw new ForbiddenException("파트 취합본 제출 권한이 없습니다(업무리더(02)/ADMIN 전용)");
        }
        boolean hasLinks = rptIds != null && !rptIds.isEmpty();
        if (hasLinks) {                                            // INSERT 전에 전체 검증(하나라도 실패 → 400)
            validateReportSelection(rptIds, weekYmd, dept, part);
        }
        String empno = AuthContext.empno();
        WeeklySummaryInsert insert = new WeeklySummaryInsert(weekYmd, dept, part, content, empno, empno);
        mapper.insertSummary(insert);
        List<WeeklySummary.Report> reports = List.of();
        if (hasLinks) {
            mapper.insertSummaryReports(insert.getSumId(), rptIds);
            reports = fetchReports(insert.getSumId());
        }
        return new WeeklySummary(insert.getSumId(), weekYmd, dept, part, content, empno, null, null, reports);
    }

    /**
     * 스코프 조회. 03/02=본인 파트, 01=본인 부서(파트별 목록), ADMIN=전체(deptCd 드릴다운).
     * 한 파트에 여러 행이 반환될 수 있다(같은 파트 내 최신 SUM_ID 우선).
     * 비ADMIN 인데 소속이 null 이면 매퍼를 호출하지 않고 빈 목록(fail-closed —
     * null 필터가 빠지면 전사 취합본이 노출되므로 {@link WeeklyReportService#list} 와 동일 패턴).
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public ListResult list(String week, String deptCd) {
        String weekYmd = WeeklyReportService.normalizeWeek(week);
        String role = AuthContext.role();
        String deptParam = blankToNull(deptCd);

        if ("ADMIN".equals(role)) {
            String scope = deptParam != null ? "dept" : "all";
            return new ListResult(toSummaries(mapper.selectSummariesByWeek(weekYmd, deptParam, null)), scope);
        }
        if ("01".equals(role)) {                                   // 팀장: 본인 부서(파트별 목록)
            String dept = AuthContext.deptCd();
            if (dept == null) {                                    // 부서 미상 → 빈 목록 fail-closed
                return new ListResult(List.of(), "dept");
            }
            return new ListResult(toSummaries(mapper.selectSummariesByWeek(weekYmd, dept, null)), "dept");
        }
        // 업무리더(02)/일반직원(03)/기타: 본인 부서+파트만(파라미터 무시)
        String dept = AuthContext.deptCd();
        String part = AuthContext.partCd();
        if (dept == null || part == null) {                        // 소속 미상 → 빈 목록 fail-closed
            return new ListResult(List.of(), "part");
        }
        return new ListResult(toSummaries(mapper.selectSummariesByWeek(weekYmd, dept, part)), "part");
    }

    /**
     * 취합본 수정. 같은 dept+part 의 02 또는 ADMIN 만(그 외 403, fail-closed), 미존재 404.
     * TEAM_CMT/TEAM_CMT_EMPNO 는 건드리지 않는다.
     * <ul>
     *   <li>content: null 이면 본문 불변, 값이 오면 기존과 동일하게 SUM_CNTT 수정(blank 400).</li>
     *   <li>rptIds: null 이면 링크 불변, 오면(빈 배열 포함) 기존 링크 전부 삭제 후 교체
     *       (검증은 취합본의 주차/dept/part 기준 — submit 과 동일).</li>
     *   <li>둘 다 null 이면 400.</li>
     * </ul>
     */
    @Transactional(transactionManager = "appTxManager")
    public WeeklySummary update(Long id, String content, List<Long> rptIds) {
        if (content == null && rptIds == null) {
            throw new IllegalArgumentException("content 또는 rptIds 중 최소 하나는 필요합니다");
        }
        if (content != null && content.isBlank()) {
            throw new IllegalArgumentException("content 는 비어 있을 수 없습니다");
        }
        WeeklySummaryRow row = requireRow(id);
        assertSamePartLeaderOrAdmin(row, "본인 파트의 취합본만 수정할 수 있습니다");
        if (rptIds != null) {                                      // 전체 교체(빈 배열 = 전부 해제)
            if (!rptIds.isEmpty()) {                               // 삭제 전에 검증 — 실패 시 기존 링크 보존
                validateReportSelection(rptIds, row.weekYmd(), row.deptCd(), row.partCd());
            }
            mapper.deleteSummaryReports(id);
            if (!rptIds.isEmpty()) {
                mapper.insertSummaryReports(id, rptIds);
            }
        }
        String newContent = content != null ? content : row.sumCntt();
        if (content != null) {
            mapper.updateSummary(id, content, AuthContext.empno());
        }
        return new WeeklySummary(row.sumId(), row.weekYmd(), row.deptCd(), row.partCd(),
                newContent, row.regEmpno(), row.teamCmt(), row.teamCmtEmpno(), fetchReports(id));
    }

    /** 취합본 삭제. 같은 dept+part 의 02 또는 ADMIN 만(그 외 403, fail-closed), 미존재 404. */
    @Transactional(transactionManager = "appTxManager")
    public void delete(Long id) {
        WeeklySummaryRow row = requireRow(id);
        assertSamePartLeaderOrAdmin(row, "본인 파트의 취합본만 삭제할 수 있습니다");
        mapper.deleteSummary(id);
    }

    /** 팀장 최종의견. 01=본인 부서 것만(그 외 403), ADMIN=전체. */
    @Transactional(transactionManager = "appTxManager")
    public WeeklySummary finalComment(Long id, String comment) {
        WeeklySummaryRow row = requireRow(id);
        String role = AuthContext.role();
        if ("01".equals(role)) {
            String dept = AuthContext.deptCd();
            if (dept == null || !dept.equals(row.deptCd())) {
                throw new ForbiddenException("본인 부서의 취합본에만 최종의견을 남길 수 있습니다");
            }
        } else if (!"ADMIN".equals(role)) {
            throw new ForbiddenException("팀장 최종의견 권한이 없습니다");
        }
        String empno = AuthContext.empno();
        mapper.updateFinalComment(id, comment, empno);
        return new WeeklySummary(row.sumId(), row.weekYmd(), row.deptCd(), row.partCd(),
                row.sumCntt(), row.regEmpno(), comment, empno, fetchReports(id));
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────

    private WeeklySummaryRow requireRow(Long id) {
        WeeklySummaryRow row = mapper.selectSummaryById(id);
        if (row == null) {
            throw new NotFoundException("해당 파트 취합본을 찾을 수 없습니다: " + id);
        }
        return row;
    }

    /**
     * 수정/삭제 가드(fail-closed): 같은 dept+part 의 02 또는 ADMIN 만.
     * 파트코드는 부서 간 재사용되므로 부서까지 함께 비교(교차 부서 권한상승 방지).
     */
    private static void assertSamePartLeaderOrAdmin(WeeklySummaryRow row, String message) {
        String role = AuthContext.role();
        if ("ADMIN".equals(role)) {
            return;
        }
        if ("02".equals(role)) {
            String dept = AuthContext.deptCd();
            String part = AuthContext.partCd();
            if (dept != null && dept.equals(row.deptCd())
                    && part != null && part.equals(row.partCd())) {
                return;
            }
        }
        throw new ForbiddenException(message);
    }

    /**
     * 목록 → 응답 DTO 변환 + 선택 보고 임베드. sumId 목록으로 링크⨝보고를 <b>한 방에</b>
     * 조회(N+1 금지)한 뒤 sumId 별로 그룹핑해 붙인다(링크 없으면 빈 배열).
     */
    private List<WeeklySummary> toSummaries(List<WeeklySummaryRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> sumIds = rows.stream().map(WeeklySummaryRow::sumId).toList();
        Map<Long, List<WeeklySummary.Report>> bySumId = mapper.selectSummaryReports(sumIds).stream()
                .collect(Collectors.groupingBy(WeeklySummaryReportRow::sumId, LinkedHashMap::new,
                        Collectors.mapping(WeeklySummaryReportRow::toReport, Collectors.toList())));
        return rows.stream()
                .map(r -> toSummary(r, bySumId.getOrDefault(r.sumId(), List.of())))
                .toList();
    }

    private static WeeklySummary toSummary(WeeklySummaryRow r, List<WeeklySummary.Report> reports) {
        return new WeeklySummary(r.sumId(), r.weekYmd(), r.deptCd(), r.partCd(),
                r.sumCntt(), r.regEmpno(), r.teamCmt(), r.teamCmtEmpno(), reports);
    }

    /** 단건 응답용 선택 보고 임베드 조회. */
    private List<WeeklySummary.Report> fetchReports(Long sumId) {
        return mapper.selectSummaryReports(List.of(sumId)).stream()
                .map(WeeklySummaryReportRow::toReport)
                .toList();
    }

    /**
     * rptIds 전체 검증(submit/update 공통) — 하나라도 실패하면 400 으로 전체 거부.
     * ①존재 ②주차 일치 ③취합본 대상 dept+part 소속(파트코드 부서 간 재사용 주의 — 둘 다 비교).
     */
    private void validateReportSelection(List<Long> rptIds, String weekYmd, String deptCd, String partCd) {
        Map<Long, WeeklyReportKey> byId = mapper.selectReportsForValidation(rptIds).stream()
                .collect(Collectors.toMap(WeeklyReportKey::rptId, k -> k));
        for (Long rptId : rptIds) {
            WeeklyReportKey key = rptId == null ? null : byId.get(rptId);
            if (key == null) {
                throw new IllegalArgumentException("존재하지 않는 개인 보고입니다: " + rptId);
            }
            if (!weekYmd.equals(key.weekYmd())) {
                throw new IllegalArgumentException(
                        "취합본 주차(" + weekYmd + ")와 다른 주차의 개인 보고입니다: " + rptId);
            }
            if (!deptCd.equals(key.deptCd()) || !partCd.equals(key.partCd())) {
                throw new IllegalArgumentException("취합본 대상 파트 소속이 아닌 개인 보고입니다: " + rptId);
            }
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
