package com.meritz.dash.dml;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.mapper.app.DmlSrMapper;
import com.meritz.dash.mapper.app.DmlSrMapper.ScopeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * DML SR 점검·개선 조회/쓰기 서비스(RBAC). 화면 단계별로 조회를 3개로 분리한다.
 * <ol>
 *   <li><b>전체(스냅샷)</b> {@link #overview}: 개발팀 전체 가시성. 팀/파트 필터만, 역할 제한 없음.</li>
 *   <li><b>점검 대상</b> {@link #inspections}: 로그인 사용자의 <b>본인 파트</b>(팀장/ADMIN은 파트 지정) — 점검여부 토글용.</li>
 *   <li><b>개선 대상</b> {@link #improvements}: 위 스코프 중 <b>점검완료(CHECK_YN='Y')</b> 건 — 개선 내용 등록용.</li>
 * </ol>
 * 쓰기(점검/개선)는 대상 SR 의 DEV_DEPT_CD/DEV_PART_CD 기준 fail-closed 판정
 * — 02/03 은 본인 부서+파트 건만, 01 은 본인 부서 건만, ADMIN 은 전체. 수동 동기화만 01·ADMIN 전용.
 */
@Service
public class DmlSrService {

    private static final Logger log = LoggerFactory.getLogger(DmlSrService.class);

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Set<String> YN = Set.of("Y", "N");

    private final DmlSrMapper mapper;

    public DmlSrService(DmlSrMapper mapper) {
        this.mapper = mapper;
    }

    /** 목록 + 스코프 라벨 + 점검/개선 집계. */
    public record ListResult(List<DmlSrItem> items, String scope, int total, long checkedCount, long improveCount) {}

    // ── ① 전체/스냅샷 조회 (개발팀 전체 가시성, 역할 제한 없음) ──────────
    /**
     * @param baseYm 기준월(yyyyMM). null/blank 면 이번 달.
     * @param deptCd 부서 필터(선택). @param partCd 파트 필터(선택).
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public ListResult overview(String baseYm, String deptCd, String partCd) {
        String ym = resolveYm(baseYm);
        deptCd = blankToNull(deptCd);
        partCd = blankToNull(partCd);
        List<DmlSrItem> items = mapper.selectList(ym, deptCd, partCd, null, null, null);
        String scope = partCd != null ? "part" : (deptCd != null ? "dept" : "all");
        return result(items, scope);
    }

    // ── ② 점검 대상 조회 (본인 파트 스코프) ───────────────────────────
    /**
     * @param partCd 팀장(01)/ADMIN 이 특정 파트를 지정할 때만 사용. 02/03 은 무시하고 본인 파트.
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public ListResult inspections(String baseYm, String partCd) {
        String ym = resolveYm(baseYm);
        Scope s = resolveWorkScope(blankToNull(partCd));
        List<DmlSrItem> items = mapper.selectList(ym, s.deptCd(), s.partCd(), null, null, null);
        return result(items, s.label());
    }

    // ── ③ 개선 대상 조회 (스코프 중 개선대상여부 'Y' 건) ──────────────
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public ListResult improvements(String baseYm, String partCd) {
        String ym = resolveYm(baseYm);
        Scope s = resolveWorkScope(blankToNull(partCd));
        List<DmlSrItem> items = mapper.selectList(ym, s.deptCd(), s.partCd(), null, null, "Y");
        return result(items, s.label());
    }

    // ── 쓰기: 점검 여부 ───────────────────────────────────────────────
    /** 점검 여부 저장. checkYn ∈ {Y,N}. 쓰기 RBAC 는 대상 SR 의 dev dept/part 기준. */
    @Transactional(transactionManager = "appTxManager")
    public void setCheck(String srNo, String checkYn) {
        if (checkYn == null || !YN.contains(checkYn)) {
            throw new IllegalArgumentException("checkYn 은 'Y' 또는 'N' 이어야 합니다");
        }
        assertCanWrite(srNo);
        mapper.upsertCheck(srNo, checkYn, AuthContext.empno());
    }

    /** 개선대상여부(Y/N) 토글 — 점검 화면에서 개선건으로 올림/내림. 쓰기 RBAC 는 setCheck 와 동일. */
    @Transactional(transactionManager = "appTxManager")
    public void setImproveTarget(String srNo, String improveYn) {
        if (improveYn == null || !YN.contains(improveYn)) {
            throw new IllegalArgumentException("improveYn 은 'Y' 또는 'N' 이어야 합니다");
        }
        assertCanWrite(srNo);
        mapper.upsertImproveTarget(srNo, improveYn, AuthContext.empno());
    }

    /** 개선건 등록/갱신. cmptYn 미지정 시 'N'. */
    @Transactional(transactionManager = "appTxManager")
    public void saveImprovement(String srNo, String improvePlan, String planCmptDate, String cmptYn, String remark) {
        String cmpt = (cmptYn == null) ? "N" : cmptYn;
        if (!YN.contains(cmpt)) {
            throw new IllegalArgumentException("cmptYn 은 'Y' 또는 'N' 이어야 합니다");
        }
        assertCanWrite(srNo);
        mapper.upsertImprovement(srNo, improvePlan, planCmptDate, cmpt, remark, AuthContext.empno());
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────

    private ListResult result(List<DmlSrItem> items, String scope) {
        long checked = items.stream().filter(i -> "Y".equals(i.checkYn())).count();
        long improve = items.stream().filter(i -> "Y".equals(i.improveYn())).count();
        log.debug("dml-srs scope={} total={} checked={} improve={}", scope, items.size(), checked, improve);
        return new ListResult(items, scope, items.size(), checked, improve);
    }

    private String resolveYm(String baseYm) {
        return (baseYm == null || baseYm.isBlank()) ? LocalDate.now().format(YM) : baseYm;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 점검/개선 화면의 조회 스코프(dept+part). 파트코드는 부서 간 재사용되므로 항상 부서와 함께 좁힌다. */
    private record Scope(String deptCd, String partCd, String label) {}

    private Scope resolveWorkScope(String partCdParam) {
        String role = AuthContext.role();
        if ("ADMIN".equals(role)) {
            return partCdParam != null ? new Scope(null, partCdParam, "part") : new Scope(null, null, "all");
        }
        if ("01".equals(role)) {                                   // 팀장: 본인 부서(파트 지정 시 드릴다운)
            String dept = AuthContext.deptCd();
            if (dept == null) {                                    // 부서 미상(구토큰) → 본인 파트로 fail-closed
                return new Scope(null, AuthContext.partCd(), "part");
            }
            return partCdParam != null ? new Scope(dept, partCdParam, "part") : new Scope(dept, null, "dept");
        }
        // 업무리더(02)/일반직원(03)/기타: 본인 부서+파트만(파라미터 무시)
        return new Scope(AuthContext.deptCd(), AuthContext.partCd(), "part");
    }

    // ── 쓰기 RBAC (fail-closed) ─────────────────────────────────────
    private void assertCanWrite(String srNo) {
        String role = AuthContext.role();
        ScopeRef ref = mapper.findScopeRef(srNo);
        if (ref == null) {
            throw new NotFoundException("해당 SR을 찾을 수 없습니다: " + srNo);
        }
        if ("ADMIN".equals(role)) {
            return;
        }
        if ("01".equals(role)) {                                   // 팀장: 본인 부서 건만
            String dept = AuthContext.deptCd();
            if (dept == null || !dept.equals(ref.devDeptCd())) {
                throw new ForbiddenException("본인 부서의 SR 만 입력할 수 있습니다");
            }
            return;
        }
        if ("02".equals(role) || "03".equals(role)) {              // 업무리더/일반직원: 본인 부서+파트 건만
            // 파트코드(P01 등)는 부서 간 재사용되므로 부서까지 함께 비교(교차 부서 권한상승 방지).
            String dept = AuthContext.deptCd();
            String part = AuthContext.partCd();
            if (dept == null || !dept.equals(ref.devDeptCd())
                    || part == null || !part.equals(ref.devPartCd())) {
                throw new ForbiddenException("본인 파트의 SR 만 입력할 수 있습니다");
            }
            return;
        }
        throw new ForbiddenException("점검/개선 입력 권한이 없습니다");   // 기타/구토큰: fail-closed
    }
}
