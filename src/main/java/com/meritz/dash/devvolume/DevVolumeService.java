package com.meritz.dash.devvolume;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.mapper.app.DevVolumeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class DevVolumeService {
    private final DevVolumeMapper mapper;

    public DevVolumeService(DevVolumeMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<DevVolumePoint> series(String unit, String period, String unitId) {
        String unitType = switch (unit == null ? "all" : unit.toLowerCase()) {
            case "all"  -> "ALL";
            case "dept" -> "DEPT";
            case "part" -> "PART";
            case "dev"  -> "DEV";
            default     -> throw new IllegalArgumentException("unit은 all|dept|part|dev");
        };
        int months = switch (period == null ? "6m" : period) {
            case "6m"  -> 6;
            case "12m" -> 12;
            default    -> throw new IllegalArgumentException("period는 6m|12m");
        };
        if (!unitType.equals("ALL") && (unitId == null || unitId.isBlank())) {
            throw new IllegalArgumentException("dept/part/dev 조회에는 unitId가 필요합니다");
        }
        // 개인 단위(dev) 드릴다운은 개인정보(개별 계획공수) — 역할별 접근 범위 적용.
        // dept/part 등 집계 단위는 제한하지 않는다(/resource 집계와 동일 정책).
        if (unitType.equals("DEV")) {
            unitId = resolveDevUnitId(unitId);
        }
        String fromYm = LocalDate.now()
                .minusMonths(months - 1L)
                .format(DateTimeFormatter.ofPattern("yyyyMM"));
        return mapper.findSeries(unitType, unitId, fromYm);
    }

    /**
     * unit=dev 개인 드릴다운 접근 범위(fail-closed):
     * - ADMIN·팀장(01): 제한 없음 — 요청 사번 그대로.
     * - 업무리더(02): 본인 파트원만. 요청 사번의 부서/파트(HR_DEVELOPER)가
     *   본인(AuthContext.deptCd()+partCd())과 일치하면 허용, 아니면 본인 사번으로 폴백.
     *   deptCd/partCd 없는 구토큰도 본인 사번으로 폴백.
     * - 일반직원(03)·기타(role null 포함): 본인 사번으로 강제.
     */
    private String resolveDevUnitId(String requested) {
        String role = AuthContext.role();
        if ("ADMIN".equals(role) || "01".equals(role)) {
            return requested;
        }
        String self = AuthContext.empno();
        if ("02".equals(role)) {
            String myDeptCd = AuthContext.deptCd();
            String myPartCd = AuthContext.partCd();
            if (myDeptCd == null || myPartCd == null) {
                return self; // 구토큰 — 파트 판정 불가, 본인으로 폴백
            }
            if (self.equals(requested)) {
                return self; // 본인 조회는 HR 확인 불필요
            }
            DevDeptPart target = mapper.findDeptPartByEmpno(requested);
            if (target != null
                    && myDeptCd.equals(target.deptCd())
                    && myPartCd.equals(target.partCd())) {
                return requested; // 본인 파트원 — 허용
            }
            return self; // 타파트/미확인 사번 — 차단(본인으로 폴백)
        }
        return self;
    }
}
