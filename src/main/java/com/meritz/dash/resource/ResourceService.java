package com.meritz.dash.resource;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.config.MmProperties;
import com.meritz.dash.mapper.app.ResourceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
public class ResourceService {

    private final ResourceMapper mapper;
    private final MmProperties mm;

    public ResourceService(ResourceMapper mapper, MmProperties mm) {
        this.mapper = mapper;
        this.mm = mm;
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public ResourceView unit(String period, String unit, String unitId) {
        if (period == null || !period.matches("\\d{4}(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("period는 YYYYMM 6자리 숫자여야 합니다: " + period);
        }
        String unitType = switch (unit == null ? "all" : unit.toLowerCase()) {
            case "all"  -> "ALL";
            case "dept" -> "DEPT";
            case "part" -> "PART";
            default     -> throw new IllegalArgumentException("unit은 all|dept|part");
        };
        if (("DEPT".equals(unitType) || "PART".equals(unitType)) &&
                (unitId == null || unitId.isBlank())) {
            throw new IllegalArgumentException("dept/part 조회에는 unitId가 필요합니다");
        }
        String id = "ALL".equals(unitType) ? "ALL" : unitId;

        ResourceRow r = mapper.findUnit(period, unitType, id);
        if (r == null) {
            throw new IllegalArgumentException(
                    "해당 기간/단위 집계가 없습니다: " + period + "/" + unitType + "/" + id);
        }

        double util = r.availMm() == 0.0
                ? 0.0
                : Math.round(r.usedMm() / r.availMm() * 1000.0) / 1000.0;

        return new ResourceView(
                r.periodYm(), r.unitType(), r.unitId(),
                r.headcount(), r.availHeadcount(),
                r.availMm(), r.usedMm(), r.overtimeMm(),
                util);
    }

    /**
     * from~to 기간 범위 조회 — DASH_RESOURCE에 존재하는 달들의 ResourceView 목록(periodYm 오름차순).
     * <p>
     * 파라미터 규칙:
     * <ul>
     *   <li>(from &amp; to) 또는 period 중 하나는 있어야 함. 둘 다 없으면 IllegalArgumentException.</li>
     *   <li>period 있고 from/to 없으면 from=to=period (1개월).</li>
     *   <li>from &gt; to 면 IllegalArgumentException.</li>
     *   <li>period/from/to 는 YYYYMM 6자리, 아니면 IllegalArgumentException.</li>
     *   <li>최대 24개월 초과 시 IllegalArgumentException.</li>
     * </ul>
     *
     * @param period  단일 월 (YYYYMM). from/to 없을 때 from=to=period 로 처리.
     * @param from    시작 월 (YYYYMM). period 없을 때 필수.
     * @param to      종료 월 (YYYYMM). period 없을 때 필수.
     * @param unit    all|dept|part
     * @param unitId  dept/part 일 때 필수
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public ResourceRangeResult unitRange(String period, String from, String to, String unit, String unitId) {
        // (1) from/to 결정
        String effectiveFrom;
        String effectiveTo;
        boolean hasPeriod = period != null && !period.isBlank();
        boolean hasFrom   = from   != null && !from.isBlank();
        boolean hasTo     = to     != null && !to.isBlank();

        if (hasFrom && hasTo) {
            effectiveFrom = from;
            effectiveTo   = to;
        } else if (!hasFrom && !hasTo && hasPeriod) {
            effectiveFrom = period;
            effectiveTo   = period;
        } else if (hasFrom != hasTo) {  // one is set, the other isn't
            throw new IllegalArgumentException("from과 to는 함께 지정해야 합니다");
        } else {
            throw new IllegalArgumentException("period 또는 from·to 중 하나는 반드시 입력해야 합니다");
        }

        // (2) 형식 검증 (YYYYMM, 월 01~12)
        if (!effectiveFrom.matches("\\d{4}(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("from은 YYYYMM 6자리 숫자여야 합니다: " + effectiveFrom);
        }
        if (!effectiveTo.matches("\\d{4}(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("to는 YYYYMM 6자리 숫자여야 합니다: " + effectiveTo);
        }

        // (3) from <= to 검증
        if (effectiveFrom.compareTo(effectiveTo) > 0) {
            throw new IllegalArgumentException("from은 to보다 이전이어야 합니다: " + effectiveFrom + " > " + effectiveTo);
        }

        // (4) 최대 24개월 검증
        int fromYear  = Integer.parseInt(effectiveFrom.substring(0, 4));
        int fromMonth = Integer.parseInt(effectiveFrom.substring(4, 6));
        int toYear    = Integer.parseInt(effectiveTo.substring(0, 4));
        int toMonth   = Integer.parseInt(effectiveTo.substring(4, 6));
        int months = (toYear - fromYear) * 12 + (toMonth - fromMonth) + 1;
        if (months > 24) {
            throw new IllegalArgumentException("조회 범위는 최대 24개월입니다: " + months + "개월");
        }

        // (5) unit/unitId 검증 (기존 unit() 로직과 동일)
        String unitType = switch (unit == null ? "all" : unit.toLowerCase()) {
            case "all"  -> "ALL";
            case "dept" -> "DEPT";
            case "part" -> "PART";
            default     -> throw new IllegalArgumentException("unit은 all|dept|part");
        };
        if (("DEPT".equals(unitType) || "PART".equals(unitType)) &&
                (unitId == null || unitId.isBlank())) {
            throw new IllegalArgumentException("dept/part 조회에는 unitId가 필요합니다");
        }
        String id = "ALL".equals(unitType) ? "ALL" : unitId;

        // (6) 단일 BETWEEN 쿼리 조회 후 매핑
        List<ResourceRow> rows = mapper.findUnitRange(effectiveFrom, effectiveTo, unitType, id);
        List<ResourceView> items = rows.stream()
                .sorted(Comparator.comparing(ResourceRow::periodYm))
                .map(r -> {
                    double util = r.availMm() == 0.0
                            ? 0.0
                            : Math.round(r.usedMm() / r.availMm() * 1000.0) / 1000.0;
                    return new ResourceView(
                            r.periodYm(), r.unitType(), r.unitId(),
                            r.headcount(), r.availHeadcount(),
                            r.availMm(), r.usedMm(), r.overtimeMm(),
                            util);
                })
                .toList();
        return new ResourceRangeResult(items, effectiveFrom, effectiveTo, unitType, id);
    }

    /**
     * 개발자별 가용률(가동률) 조회 — 역할별 스코핑(overtimeSummary와 동일 정책).
     * <ul>
     *   <li>ADMIN: empno 지정 시 해당 개발자 1명, null/blank면 전체 재직 개발자.</li>
     *   <li>팀장(01): 본인 부서 개발자 전체(deptCd 있으면 dept, null이면 본인으로 폴백).</li>
     *   <li>업무리더(02): 본인 파트 개발자(deptCd·partCd 둘 다 있으면 part, 하나라도 null이면 본인으로 폴백).</li>
     *   <li>일반직원(03) 및 기타: 본인만. 비ADMIN의 client empno는 무시(fail-closed).</li>
     * </ul>
     * 계산: availMm=개발가능 1인=1.0(DEV_YN='Y' 매퍼 필터), usedMm=해당 월 계획공수 합(SR 없으면 0),
     * utilization=usedMm÷availMm(분모 0 방어), 소수 3자리 반올림.
     *
     * @param period 조회 월 (YYYYMM)
     * @param empno  개발자 사번(nullable, ADMIN만 사용됨). 없으면 전체.
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<DeveloperUtilView> developerUtil(String period, String empno) {
        if (period == null || !period.matches("\\d{4}(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("period는 YYYYMM 6자리 숫자여야 합니다: " + period);
        }
        String emp = (empno == null || empno.isBlank()) ? null : empno;
        DeveloperScope scope = resolveDeveloperScope(AuthContext.role(), emp);

        return mapper.findDeveloperUtil(period, scope.dept(), scope.part(), scope.empno()).stream()
                .map(r -> {
                    double availMm = "Y".equals(r.devYn()) ? 1.0 : 0.0;
                    double util = availMm == 0.0
                            ? 0.0
                            : Math.round(r.usedMm() / availMm * 1000.0) / 1000.0;
                    return new DeveloperUtilView(
                            r.empno(), r.empNm(), r.deptCd(), r.partCd(),
                            availMm, r.usedMm(), util);
                })
                .toList();
    }

    /**
     * 개발자별 가용률 조회의 역할별 스코프(dept/part/empno 필터)를 결정한다.
     * 비ADMIN에서 "필터 없음" 상태가 되는 경우를 본인(empno)으로 강제한다(fail-closed).
     * ADMIN 외 역할은 client empno를 무시한다(타인 임의 조회 차단).
     */
    private DeveloperScope resolveDeveloperScope(String role, String clientEmpno) {
        if ("ADMIN".equals(role)) {
            return new DeveloperScope(null, null, clientEmpno);  // 전체 또는 특정 사번
        }
        if ("01".equals(role)) {                                 // 팀장: 본인 부서
            String d = AuthContext.deptCd();
            return d != null ? new DeveloperScope(d, null, null)
                             : new DeveloperScope(null, null, AuthContext.empno());
        }
        if ("02".equals(role)) {                                 // 업무리더: 본인 파트
            String d = AuthContext.deptCd();
            String p = AuthContext.partCd();
            return (d != null && p != null) ? new DeveloperScope(d, p, null)
                                            : new DeveloperScope(null, null, AuthContext.empno());
        }
        return new DeveloperScope(null, null, AuthContext.empno());  // 일반직원(03) 및 기타: 본인만
    }

    /** 개발자별 가용률 스코프 — 매퍼 dept/part/empno 필터. */
    private record DeveloperScope(String dept, String part, String empno) {}

    /**
     * 역할별 데이터 범위 제한 후 야근 요약 반환 — HR_OVERTIME(엑셀 업로드 실적) 기반.
     * <ul>
     *   <li>ADMIN: 클라이언트 dept/part 파라미터 그대로 사용</li>
     *   <li>팀장(01): 본인 부서 전체 — deptCd 있으면 dept=deptCd; null이면 본인(empno)으로 폴백</li>
     *   <li>업무리더(02): 본인 파트 — deptCd·partCd 둘 다 있으면 dept+part; 하나라도 null이면 본인(empno)으로 폴백</li>
     *   <li>일반직원(03) 및 기타: 본인만 — empno=AuthContext.empno()</li>
     * </ul>
     * fail-closed: 비ADMIN에서 effectiveDept·effectivePart·effectiveEmpno 모두 null이 되는 경우는
     * empno=본인으로 강제하여 "필터 없음"으로 매퍼에 도달하는 경로를 원천 차단한다.
     * <p>
     * 계산:
     * <ul>
     *   <li>사번별 overtimeHours = OT_MINUTES ÷ 60 (소수 1자리 반올림)</li>
     *   <li>avgOvertimeHours = Σ OT_MINUTES ÷ 60 ÷ 스코프 재직 개발자 수 (소수 1자리 반올림, 분모 0 방어)</li>
     * </ul>
     *
     * @param dept  부서코드(nullable, ADMIN만 사용됨)
     * @param part  파트코드(nullable, ADMIN만 사용됨)
     */
    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public OvertimeSummary overtimeSummary(String period, String dept, String part) {
        if (period == null || !period.matches("\\d{4}(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("period는 YYYYMM 6자리 숫자여야 합니다: " + period);
        }

        // RBAC: 역할별 유효 파라미터 결정
        OvertimeScope scope = resolveScope(AuthContext.role(), dept, part);

        List<OvertimeRow> rows = mapper.findOvertimeHoursByScope(
                period, scope.dept(), scope.part(), scope.empno());
        List<OvertimeView> list = rows.stream()
                .map(r -> new OvertimeView(
                        r.empno(), r.empNm(), r.partCd(),
                        r.otMinutes(), roundHours(r.otMinutes())))
                .toList();

        // avgOvertimeHours: 스코프 인원당 평균 야근시간 (분모 = 스코프 재직 개발자 수)
        int headcount = mapper.countDevelopersByScope(scope.dept(), scope.part(), scope.empno());
        long totalMinutes = rows.stream().mapToLong(OvertimeRow::otMinutes).sum();
        double avg = headcount == 0 ? 0.0 : roundHours((double) totalMinutes / headcount);
        return new OvertimeSummary(list, avg);
    }

    /** 분 → 시간 환산 (소수 1자리 반올림). */
    private static double roundHours(double minutes) {
        return Math.round(minutes / 60.0 * 10.0) / 10.0;
    }

    /**
     * 야근 조회의 역할별 스코프(dept/part/empno 필터)를 결정한다.
     * 비ADMIN에서 "필터 없음" 상태가 되는 경우를 본인(empno)으로 강제한다(fail-closed).
     * ADMIN 외 역할은 client dept/part 를 무시한다(타 조직 임의 조회 차단).
     */
    private OvertimeScope resolveScope(String role, String clientDept, String clientPart) {
        if ("ADMIN".equals(role)) {
            if (clientPart != null && !clientPart.isBlank()
                    && (clientDept == null || clientDept.isBlank())) {
                throw new IllegalArgumentException("part 조회에는 dept가 필요합니다");
            }
            return new OvertimeScope(clientDept, clientPart, null);
        }

        if ("01".equals(role)) {
            // 팀장: deptCd 있으면 부서 전체, null이면 본인으로 폴백
            String d = AuthContext.deptCd();
            return d != null ? new OvertimeScope(d, null, null)
                             : new OvertimeScope(null, null, AuthContext.empno());
        }

        if ("02".equals(role)) {
            // 업무리더: deptCd·partCd 둘 다 있으면 파트, 하나라도 null이면 본인으로 폴백
            String d = AuthContext.deptCd();
            String p = AuthContext.partCd();
            return (d != null && p != null) ? new OvertimeScope(d, p, null)
                                            : new OvertimeScope(null, null, AuthContext.empno());
        }

        // 일반직원(03) 및 기타: 본인만.
        // fail-closed 최종 방어: empno()는 인증 없으면 UnauthorizedException을 던지므로
        // 여기까지 오면 empno != null 보장됨(필터 없음 상태 불가).
        return new OvertimeScope(null, null, AuthContext.empno());
    }

    /** 야근 스코프 결정 결과 — 매퍼 dept/part/empno 필터. */
    private record OvertimeScope(String dept, String part, String empno) {}
}
