package com.meritz.dash.devsr;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.auth.ForbiddenException;
import com.meritz.dash.code.CommonCode;
import com.meritz.dash.mapper.app.CodeMapper;
import com.meritz.dash.mapper.app.DevSrScopeMapper;
import com.meritz.dash.mapper.app.DevSrScopeMapper.HrRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 개발자별 실시간 SR 현황 조회 서비스.
 * <p>
 * ① RBAC 로 "볼 수 있는 개발자 사번"을 사내 인사(HR_DEVELOPER)에서 확정하고,
 * ② 기간계(TBCPPE091/093)에서 그 사번들의 진행중 SR 을 실시간 조회한 뒤,
 * ③ 상태·유형 한글명을 CD_COMMON 으로 보강해 상태별로 묶어 반환한다.
 * <p>
 * 역할별 범위: 일반직원(03)=본인만 · 업무리더(02)=본인 파트원 · 팀장(01)=본인 부서 · ADMIN=전체.
 * empno 미지정 시 스코프 전체, 지정 시 그 사번(스코프 밖이면 403).
 */
@Service
public class DevSrService {

    private static final Logger log = LoggerFactory.getLogger(DevSrService.class);

    /** Oracle IN 목록 상한(ORA-01795 방지). 사내 규모상 초과는 사실상 없음 — 초과 시 로깅 후 절단. */
    private static final int MAX_EMPNOS = 1000;

    private static final String GRP_STATUS = "SR_REG_STAT_CODE";
    private static final String GRP_TPCD = "SR_TPCD";

    private final DevSrScopeMapper scopeMapper;
    private final DevSrLegacyReader legacyReader;
    private final CodeMapper codeMapper;

    public DevSrService(DevSrScopeMapper scopeMapper, DevSrLegacyReader legacyReader, CodeMapper codeMapper) {
        this.scopeMapper = scopeMapper;
        this.legacyReader = legacyReader;
        this.codeMapper = codeMapper;
    }

    /** 상태별 그룹 + 스코프 메타. truncated=스코프 사번이 상한을 넘어 일부 누락됐는지. */
    public record Result(List<SrStatusGroup> groups, String scope, int developerCount, int totalSrs, boolean truncated) {}

    /**
     * @param requestedEmpno 조회 대상 사번(선택). null 이면 역할 스코프 전체.
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public Result developerSrs(String requestedEmpno) {
        Scope scope = resolveScope(requestedEmpno);
        List<String> empnos = scope.refs().stream().map(HrRef::empno).distinct().toList();
        boolean truncated = false;
        if (empnos.size() > MAX_EMPNOS) {
            log.warn("dev-srs 스코프 사번 {}건 → 상한 {}건으로 절단", empnos.size(), MAX_EMPNOS);
            empnos = empnos.subList(0, MAX_EMPNOS);
            truncated = true;
        }
        if (empnos.isEmpty()) {
            return new Result(List.of(), scope.label(), 0, 0, false);
        }

        Map<String, String> nameByEmpno = scope.refs().stream()
                .collect(Collectors.toMap(HrRef::empno, r -> r.empNm() == null ? "" : r.empNm(), (a, b) -> a));
        Map<String, String> statusNames = codeNames(GRP_STATUS);
        Map<String, String> tpcdNames = codeNames(GRP_TPCD);

        // 기간계 실시간 조회(SELECT-only, legacyTxManager readOnly). 장애 시 예외 전파 → 실시간 드릴다운이라 허용.
        List<DevSrRow> rows = legacyReader.read(empnos);

        List<DevSrItem> items = rows.stream()
                .map(r -> toItem(r, nameByEmpno, statusNames, tpcdNames))
                .toList();

        // 상태코드별 그룹(상태코드 오름차순), 각 그룹 내 mapper ORDER BY(상태·SR번호) 유지.
        Map<String, List<DevSrItem>> byStatus = items.stream()
                .collect(Collectors.groupingBy(DevSrItem::statusCode, LinkedHashMap::new, Collectors.toList()));
        List<SrStatusGroup> groups = byStatus.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new SrStatusGroup(
                        e.getKey(),
                        statusNames.getOrDefault(e.getKey(), e.getKey()),
                        e.getValue().size(),
                        e.getValue()))
                .toList();

        return new Result(groups, scope.label(), empnos.size(), items.size(), truncated);
    }

    private DevSrItem toItem(DevSrRow r, Map<String, String> names,
                             Map<String, String> statusNames, Map<String, String> tpcdNames) {
        boolean planned = "Y".equals(r.planYn());
        String rflc = (r.rflcScdlDate() == null || r.rflcScdlDate().isBlank()) ? null : r.rflcScdlDate();
        return new DevSrItem(
                r.empno(),
                names.getOrDefault(r.empno(), ""),
                r.srNo(),
                r.titlCntt(),
                r.msgCntt(),
                r.srTpcd(),
                tpcdNames.getOrDefault(r.srTpcd(), r.srTpcd()),
                r.statusCode(),
                statusNames.getOrDefault(r.statusCode(), r.statusCode()),
                planned,
                planned ? r.jobMm() : null,
                planned ? r.jobHours() : null,
                rflc);
    }

    private Map<String, String> codeNames(String grpCd) {
        return codeMapper.findByGroup(grpCd).stream()
                .collect(Collectors.toMap(CommonCode::cdVal, CommonCode::cdNm, (a, b) -> a, LinkedHashMap::new));
    }

    // ── RBAC 스코프 확정 ──────────────────────────────────────────────
    private record Scope(List<HrRef> refs, String label) {}

    private Scope resolveScope(String requested) {
        String role = AuthContext.role();
        String self = AuthContext.empno();
        String dept = AuthContext.deptCd();
        String part = AuthContext.partCd();
        String req = (requested == null || requested.isBlank()) ? null : requested.trim();

        if ("ADMIN".equals(role)) {
            return req != null ? new Scope(refsOf(req), "admin-one")
                               : new Scope(scopeMapper.findRefs(null, null), "all");
        }
        if ("01".equals(role)) {                                  // 팀장: 본인 부서
            if (dept == null) return new Scope(selfOnly(self), "self");
            if (req != null) return new Scope(List.of(requireInDept(req, dept)), "dept-one");
            return new Scope(scopeMapper.findRefs(dept, null), "dept");
        }
        if ("02".equals(role)) {                                  // 업무리더: 본인 파트
            if (dept == null || part == null) return new Scope(selfOnly(self), "self");
            if (req != null) return new Scope(List.of(requireInPart(req, dept, part)), "part-one");
            return new Scope(scopeMapper.findRefs(dept, part), "part");
        }
        return new Scope(selfOnly(self), "self");                 // 일반직원(03) 및 기타/구토큰: 본인만
    }

    private HrRef requireInDept(String req, String dept) {
        HrRef ref = scopeMapper.findRef(req);
        if (ref == null || !dept.equals(ref.deptCd())) {
            throw new ForbiddenException("본인 부서 소속 직원만 조회할 수 있습니다");
        }
        return ref;
    }

    private HrRef requireInPart(String req, String dept, String part) {
        HrRef ref = scopeMapper.findRef(req);
        if (ref == null || !dept.equals(ref.deptCd()) || !part.equals(ref.partCd())) {
            throw new ForbiddenException("본인 파트 소속 직원만 조회할 수 있습니다");
        }
        return ref;
    }

    /** ADMIN 이 특정 사번 지정: 존재하면 그 사번, 없으면 이름만 비운 참조(기간계엔 사번으로 조회). */
    private List<HrRef> refsOf(String empno) {
        HrRef ref = scopeMapper.findRef(empno);
        return List.of(ref != null ? ref : new HrRef(empno, "", null, null));
    }

    private List<HrRef> selfOnly(String self) {
        HrRef ref = scopeMapper.findRef(self);
        return List.of(ref != null ? ref : new HrRef(self, "", null, null));
    }
}
