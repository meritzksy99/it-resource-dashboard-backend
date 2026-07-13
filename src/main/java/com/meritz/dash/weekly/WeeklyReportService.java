package com.meritz.dash.weekly;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.common.ConflictException;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.mapper.app.WeeklyReportMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 개인 주간보고(DASH_WKLY_RPT) 등록/조회/수정/삭제 + 업무리더 추가의견.
 * <p>
 * RBAC 스코프/쓰기가드는 {@link com.meritz.dash.dml.DmlSrService} 의
 * resolveWorkScope/assertCanWrite 패턴을 모방한다(02/03 은 dept+part 둘 다 비교 —
 * 파트코드는 부서 간 재사용되므로 교차 부서 권한상승 방지).
 */
@Service
public class WeeklyReportService {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final WeeklyReportMapper mapper;
    private final WeeklySrLegacyReader legacyReader;

    public WeeklyReportService(WeeklyReportMapper mapper, WeeklySrLegacyReader legacyReader) {
        this.mapper = mapper;
        this.legacyReader = legacyReader;
    }

    /** 목록 + 스코프 라벨. */
    public record ListResult(List<WeeklyReport> items, String scope) {}

    /**
     * 주간보고 등록.
     * <ul>
     *   <li>week 는 {@link #normalizeWeek(String)} 로 정규화한 뒤 저장. planDate 도 동일한
     *       STRICT YYYYMMDD 검증(형식 오류 400).</li>
     *   <li>기간계에서 srNo 참조 조회(SR_TITL/SR_PLAN_DATE 스냅샷). 없으면 IllegalArgumentException("SR 없음").</li>
     *   <li>지연사유(delayReason)는 항상 선택 — planDate 가 SR 예정일과 달라도 사유 없이 저장 허용.</li>
     *   <li>(week, srNo, 작성자) 중복 → {@link ConflictException}.</li>
     * </ul>
     */
    @Transactional(transactionManager = "appTxManager")
    public WeeklyReport create(String week, String srNo, String content, String planDate, String delayReason) {
        String weekYmd = normalizeWeek(week);
        if (srNo == null || srNo.isBlank()) {
            throw new IllegalArgumentException("srNo 는 필수입니다");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 는 필수입니다");
        }
        if (planDate == null || planDate.isBlank()) {
            throw new IllegalArgumentException("planDate 는 필수입니다");
        }
        validatePlanDate(planDate);
        SrRef ref = legacyReader.read(srNo);
        if (ref == null) {
            throw new IllegalArgumentException("SR 없음(기간계 미존재): " + srNo);
        }
        String delayRsn = blankToNull(delayReason);                // 지연사유는 항상 선택

        String empno = AuthContext.empno();
        if (mapper.countReportByWeekSrEmp(weekYmd, srNo, empno) > 0) {
            throw new ConflictException("이미 등록된 주간보고입니다: (" + weekYmd + ", " + srNo + ")");
        }
        WeeklyReportInsert insert = new WeeklyReportInsert(
                weekYmd, srNo, ref.srTitl(), empno, AuthContext.deptCd(), AuthContext.partCd(),
                content, planDate, ref.srPlanDate(), delayRsn, empno);
        try {
            mapper.insertReport(insert);
        } catch (DuplicateKeyException e) {                        // count 선검사 사이 레이스 → 500 대신 409
            throw new ConflictException("이미 등록된 주간보고입니다: (" + weekYmd + ", " + srNo + ")");
        }
        return new WeeklyReport(insert.getRptId(), weekYmd, srNo, ref.srTitl(), empno,
                AuthContext.deptCd(), AuthContext.partCd(), content, planDate, ref.srPlanDate(),
                delayRsn, null);
    }

    /**
     * 스코프 조회. 03=본인 / 02=본인 파트 / 01=본인 부서(deptCd/partCd 무시하고 본인 부서, 드릴다운은 partCd) /
     * ADMIN=전체(deptCd/partCd 드릴다운).
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public ListResult list(String week, String deptCd, String partCd) {
        String weekYmd = normalizeWeek(week);
        String role = AuthContext.role();
        String deptParam = blankToNull(deptCd);
        String partParam = blankToNull(partCd);

        if ("ADMIN".equals(role)) {
            String scope = partParam != null ? "part" : (deptParam != null ? "dept" : "all");
            return new ListResult(toReports(mapper.selectReportsByWeek(weekYmd, deptParam, partParam, null)), scope);
        }
        if ("01".equals(role)) {                                   // 팀장: 본인 부서(파트 지정 시 드릴다운)
            String dept = AuthContext.deptCd();
            if (dept == null) {                                    // 부서 미상 → 본인 것만 fail-closed
                return selfList(weekYmd);
            }
            String scope = partParam != null ? "part" : "dept";
            return new ListResult(toReports(mapper.selectReportsByWeek(weekYmd, dept, partParam, null)), scope);
        }
        if ("02".equals(role)) {                                   // 업무리더: 본인 부서+파트만(파라미터 무시)
            String dept = AuthContext.deptCd();
            String part = AuthContext.partCd();
            if (dept == null || part == null) {                    // 소속 미상 → 본인 것만 fail-closed
                return selfList(weekYmd);
            }
            return new ListResult(toReports(mapper.selectReportsByWeek(weekYmd, dept, part, null)), "part");
        }
        // 일반직원(03)/기타: 본인 것만
        return selfList(weekYmd);
    }

    /** 단건 조회. 스코프 밖이면 {@link ForbiddenException}(fail-closed), 미존재 {@link NotFoundException}. */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public WeeklyReport get(Long id) {
        WeeklyReportRow row = requireRow(id);
        if (!canRead(row)) {
            throw new ForbiddenException("조회 권한이 없는 주간보고입니다: " + id);
        }
        return toReport(row);
    }

    /**
     * 부분 수정. 작성자 본인 / 같은 dept+part 02 / ADMIN 만 허용, 그 외 403.
     * planDate 가 바뀌면 기간계 SR 예정일을 재조회(스냅샷 갱신)한다. 지연사유는 항상 선택.
     */
    @Transactional(transactionManager = "appTxManager")
    public WeeklyReport update(Long id, String content, String planDate, String delayReason) {
        WeeklyReportRow row = requireRow(id);
        assertCanEdit(row);

        String newContent = (content != null) ? content : row.rptCntt();
        if (blankToNull(planDate) != null) {
            validatePlanDate(planDate);
        }
        String newPlanDate = (blankToNull(planDate) != null) ? planDate : row.planDate();
        String newSrPlanDate = row.srPlanDate();
        String newDelayRsn = (delayReason != null) ? blankToNull(delayReason) : row.delayRsn();

        if (!newPlanDate.equals(row.planDate())) {                 // planDate 변경 → 스냅샷 갱신 + 재검증
            SrRef ref = legacyReader.read(row.srNo());
            if (ref == null) {
                throw new IllegalArgumentException("SR 없음(기간계 미존재): " + row.srNo());
            }
            newSrPlanDate = ref.srPlanDate();
        }

        mapper.updateReport(id, newContent, newPlanDate, newSrPlanDate, newDelayRsn, AuthContext.empno());
        return new WeeklyReport(row.rptId(), row.weekYmd(), row.srNo(), row.srTitl(), row.regEmpno(),
                row.deptCd(), row.partCd(), newContent, newPlanDate, newSrPlanDate, newDelayRsn, row.leaderCmt());
    }

    /** 업무리더 추가의견. 02=같은 dept+part / 01=같은 dept / ADMIN=전체, 그 외 403. */
    @Transactional(transactionManager = "appTxManager")
    public WeeklyReport leaderComment(Long id, String comment) {
        WeeklyReportRow row = requireRow(id);
        String role = AuthContext.role();
        if ("01".equals(role)) {
            String dept = AuthContext.deptCd();
            if (dept == null || !dept.equals(row.deptCd())) {
                throw new ForbiddenException("본인 부서의 주간보고에만 의견을 남길 수 있습니다");
            }
        } else if ("02".equals(role)) {
            if (!sameDeptAndPart(row)) {
                throw new ForbiddenException("본인 파트의 주간보고에만 의견을 남길 수 있습니다");
            }
        } else if (!"ADMIN".equals(role)) {
            throw new ForbiddenException("업무리더 의견 권한이 없습니다");
        }
        mapper.updateLeaderComment(id, comment, AuthContext.empno());
        return new WeeklyReport(row.rptId(), row.weekYmd(), row.srNo(), row.srTitl(), row.regEmpno(),
                row.deptCd(), row.partCd(), row.rptCntt(), row.planDate(), row.srPlanDate(),
                row.delayRsn(), comment);
    }

    /** 삭제. 작성자 본인 또는 ADMIN 만 허용, 그 외 403. */
    @Transactional(transactionManager = "appTxManager")
    public void delete(Long id) {
        WeeklyReportRow row = requireRow(id);
        if (!"ADMIN".equals(AuthContext.role()) && !AuthContext.empno().equals(row.regEmpno())) {
            throw new ForbiddenException("작성자 또는 ADMIN 만 삭제할 수 있습니다");
        }
        mapper.deleteReport(id);
    }

    /**
     * 임의 날짜(YYYYMMDD) → 그 주(월~일)의 목요일로 정규화. 예) 20260712(일)→20260709, 20260709(목)→20260709.
     * 형식 오류·실존하지 않는 날짜는 {@link IllegalArgumentException}(→400).
     */
    public static String normalizeWeek(String rawDate) {
        if (rawDate == null || !rawDate.matches("\\d{8}")) {
            throw new IllegalArgumentException("week 는 YYYYMMDD 형식이어야 합니다: " + rawDate);
        }
        try {
            LocalDate date = LocalDate.parse(rawDate, YMD);        // ISO STRICT — 실존 날짜만 통과
            return date.with(DayOfWeek.THURSDAY).format(YMD);      // 같은 ISO 주(월~일)의 목요일
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("실존하지 않는 날짜입니다: " + rawDate);
        }
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────

    /**
     * planDate 형식 검증 — {@link #normalizeWeek(String)} 와 동일한 STRICT BASIC_ISO_DATE(YYYYMMDD).
     * 형식 오류·실존하지 않는 날짜는 {@link IllegalArgumentException}(→400, DB 저장 전 차단).
     */
    private static void validatePlanDate(String planDate) {
        if (planDate == null || !planDate.matches("\\d{8}")) {
            throw new IllegalArgumentException("planDate 는 YYYYMMDD 형식이어야 합니다: " + planDate);
        }
        try {
            LocalDate.parse(planDate, YMD);                        // ISO STRICT — 실존 날짜만 통과
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("실존하지 않는 날짜입니다: " + planDate);
        }
    }

    private ListResult selfList(String weekYmd) {
        List<WeeklyReportRow> rows = mapper.selectReportsByWeek(
                weekYmd, AuthContext.deptCd(), AuthContext.partCd(), AuthContext.empno());
        return new ListResult(toReports(rows), "self");
    }

    private WeeklyReportRow requireRow(Long id) {
        WeeklyReportRow row = mapper.selectReportById(id);
        if (row == null) {
            throw new NotFoundException("해당 주간보고를 찾을 수 없습니다: " + id);
        }
        return row;
    }

    /** 조회 스코프(fail-closed): 작성자 본인은 항상, 01=같은 부서, 02=같은 부서+파트, ADMIN=전체. */
    private boolean canRead(WeeklyReportRow row) {
        String role = AuthContext.role();
        if ("ADMIN".equals(role)) {
            return true;
        }
        if (AuthContext.empno().equals(row.regEmpno())) {
            return true;
        }
        if ("01".equals(role)) {
            String dept = AuthContext.deptCd();
            return dept != null && dept.equals(row.deptCd());
        }
        if ("02".equals(role)) {
            return sameDeptAndPart(row);
        }
        return false;                                              // 03/기타: 본인 것만
    }

    /** 수정 가드: 작성자 본인 / 같은 dept+part 02 / ADMIN. */
    private void assertCanEdit(WeeklyReportRow row) {
        String role = AuthContext.role();
        if ("ADMIN".equals(role) || AuthContext.empno().equals(row.regEmpno())) {
            return;
        }
        if ("02".equals(role) && sameDeptAndPart(row)) {
            return;
        }
        throw new ForbiddenException("작성자 본인, 같은 파트 업무리더(02) 또는 ADMIN 만 수정할 수 있습니다");
    }

    /** 파트코드는 부서 간 재사용되므로 부서까지 함께 비교(교차 부서 권한상승 방지). */
    private static boolean sameDeptAndPart(WeeklyReportRow row) {
        String dept = AuthContext.deptCd();
        String part = AuthContext.partCd();
        return dept != null && dept.equals(row.deptCd())
                && part != null && part.equals(row.partCd());
    }

    private static List<WeeklyReport> toReports(List<WeeklyReportRow> rows) {
        return rows.stream().map(WeeklyReportService::toReport).toList();
    }

    private static WeeklyReport toReport(WeeklyReportRow r) {
        return new WeeklyReport(r.rptId(), r.weekYmd(), r.srNo(), r.srTitl(), r.regEmpno(),
                r.deptCd(), r.partCd(), r.rptCntt(), r.planDate(), r.srPlanDate(), r.delayRsn(), r.leaderCmt());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
