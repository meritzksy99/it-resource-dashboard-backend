package com.meritz.dash.aggregation;

import com.meritz.dash.config.MmProperties;
import com.meritz.dash.mapper.app.CodeRefMapper;
import com.meritz.dash.mapper.app.DashWriteMapper;
import com.meritz.dash.mapper.legacy.LegacySrMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 기간계 → DASH 집계 배치 서비스.
 * <p>
 * 같은 periodYm 재실행 시 delete+insert 로 멱등성 보장.
 * 쓰기는 appTxManager(app DS). 기간계 매퍼 호출은 legacySqlSessionFactory(legacy DS, 읽기 전용) 로 별도 동작.
 * </p>
 * <p>
 * BATCH_RUN_LOG 기록은 {@link BatchRunLogger}(REQUIRES_NEW)로 분리하여,
 * 메인 데이터 트랜잭션(DASH 적재)이 롤백돼도 실행 이력이 반드시 남도록 한다.
 * </p>
 */
@Service
public class AggregationService {

    private final LegacySrMapper legacy;
    private final DashWriteMapper dash;
    private final CodeRefMapper codeRef;
    private final JdbcTemplate appJdbc;
    private final MmProperties mm;
    private final BatchRunLogger batchRunLogger;

    public AggregationService(LegacySrMapper legacy, DashWriteMapper dash, CodeRefMapper codeRef,
                               JdbcTemplate appJdbc, MmProperties mm, BatchRunLogger batchRunLogger) {
        this.legacy         = legacy;
        this.dash           = dash;
        this.codeRef        = codeRef;
        this.appJdbc        = appJdbc;
        this.mm             = mm;
        this.batchRunLogger = batchRunLogger;
    }

    /**
     * 기간계 read → 계산 → DASH 테이블 delete+insert(멱등) → BATCH_RUN_LOG 기록.
     * <p>
     * 실행 흐름:
     * <ol>
     *   <li>BatchRunLogger.start → STATUS='RUNNING' 즉시 커밋(REQUIRES_NEW)</li>
     *   <li>메인 @Transactional("appTxManager") 내에서 DASH 적재</li>
     *   <li>성공: BatchRunLogger.finish(OK) 즉시 커밋 / 실패: BatchRunLogger.finish(FAIL) 즉시 커밋 후 예외 재던지기</li>
     * </ol>
     * </p>
     *
     * @param periodYm 집계 기준 연월 (6자리, 예: "202605")
     * @param trigger  실행 유형 ("MANUAL" | "SCHEDULED")
     * @return 생성된 RUN_ID
     */
    @Transactional("appTxManager")
    public long run(String periodYm, String trigger) throws Exception {
        // RUNNING 상태로 즉시 커밋 (REQUIRES_NEW — 메인 트랜잭션과 무관)
        long runId = batchRunLogger.start(periodYm, trigger);

        try {
            // 1) 기간계 dev 집계 (legacy DS 읽기)
            List<LegacyDevRow> devRows = legacy.selectDevAgg(periodYm);

            // 2) SR_TPCD → SR_CLS 코드 맵 (app DS)
            Map<String, CodeRefMapper.SrClsRef> clsMap = codeRef.srClsByTpcd();

            // 3) (empno, srCls) 집계: 건수 + 시간
            Map<String, int[]>    cntByKey  = new LinkedHashMap<>();   // key="empno|srCls" → [cnt]
            Map<String, double[]> hourByKey = new LinkedHashMap<>();   // key="empno|srCls" → [hours]
            for (LegacyDevRow r : devRows) {
                CodeRefMapper.SrClsRef ref = clsMap.get(r.srTpcd());
                String cls = (ref != null) ? ref.srCls() : "99";
                String key = r.empno() + "|" + cls;
                cntByKey .computeIfAbsent(key, k -> new int[1])[0]    += r.srCnt();
                hourByKey.computeIfAbsent(key, k -> new double[1])[0] += r.jobHours();
            }

            // 4) DASH_DEV_AGG delete + insert
            dash.deleteDevAgg(periodYm);
            int devCount = 0;
            Map<String, Double> mmByEmp = new HashMap<>();  // empno → 총 MM (야근 계산용)
            for (Map.Entry<String, int[]> e : cntByKey.entrySet()) {
                String[] k    = e.getKey().split("\\|", 2);
                String empno  = k[0];
                String srCls  = k[1];
                double jobMm  = round2(hourByKey.get(e.getKey())[0] / mm.hoursPerMonth());
                dash.insertDevAgg(new DevAgg(periodYm, empno, srCls, e.getValue()[0], jobMm));
                mmByEmp.merge(empno, jobMm, Double::sum);
                devCount++;
            }

            // 5) DASH_RESOURCE delete + insert (PART + DEPT + ALL)
            dash.deleteResource(periodYm);
            writeResourceSnapshots(periodYm, mmByEmp);

            // 6) DASH_SR_PROJECT delete + insert
            dash.deleteSrProject(periodYm);
            List<LegacySrProjectRow> srProjects = legacy.selectSrProjects(periodYm, mm.topMinMm());
            for (LegacySrProjectRow s : srProjects) {
                dash.insertSrProject(new SrProject(
                        periodYm, s.srNo(), s.titlCntt(), s.srTpcd(), s.srTpcdName(),
                        round2(s.totMm()), s.empCnt(), s.prchDpcd(), s.dpcd(),
                        s.regDate(), s.rflcScdlDate()));
            }

            // 성공: 독립 트랜잭션으로 즉시 커밋
            batchRunLogger.finish(runId, "OK", devCount, srProjects.size(), null);
            return runId;

        } catch (Exception ex) {
            // checked/unchecked 예외 모두 FAIL 기록 후 재던지기
            // REQUIRES_NEW 이므로 메인 트랜잭션 롤백과 무관하게 DB에 남는다
            batchRunLogger.finish(runId, "FAIL", 0, 0, truncate(ex.getMessage(), 1000));
            throw ex;
        }
    }

    // -----------------------------------------------------------------------
    // private helpers
    // -----------------------------------------------------------------------

    /**
     * HR_DEVELOPER 스냅샷 기반으로 PART / DEPT / ALL 3계층 ResourceSnapshot 을 삽입한다.
     * <ul>
     *   <li>HEADCOUNT: 소속 재직자(STATUS_CD='01', V003 재코딩)만</li>
     *   <li>AVAIL_HEADCOUNT: DEV_YN='Y' AND STATUS_CD='01'</li>
     *   <li>AVAIL_MM: AVAIL_HEADCOUNT × 1 (단순 비례)</li>
     *   <li>USED_MM: 해당 단위 개발자의 실투입 MM 합산</li>
     *   <li>OVERTIME_MM: Σmax(empMM − overtimeThreshold, 0)</li>
     * </ul>
     * 집계 데이터에 존재하지만 HR에 없는 EMPNO 는 '미분류' DEPT/PART로 처리한다.
     * insert 순서: PART 행들 → DEPT 행들 → ALL 행.
     */
    private void writeResourceSnapshots(String periodYm, Map<String, Double> mmByEmp) {
        // HR 전체 조회 (DEPT_CD 추가)
        List<Map<String, Object>> hr = appJdbc.queryForList(
                "SELECT EMPNO, DEPT_CD, PART_CD, DEV_YN, STATUS_CD FROM HR_DEVELOPER");

        // empno → (deptCd, partCd) 맵 (재직자만)
        Map<String, String> deptByEmp = new HashMap<>();
        Map<String, String> partByEmp = new HashMap<>();

        // PART 단위 headcount 집계: key=deptCd+"-"+partCd, val=[headcount, availHeadcount]
        Map<String, int[]> headByPart = new LinkedHashMap<>();
        // DEPT 단위 headcount 집계: key=deptCd, val=[headcount, availHeadcount]
        Map<String, int[]> headByDept = new LinkedHashMap<>();

        for (Map<String, Object> row : hr) {
            String empno   = (String) row.get("EMPNO");
            String rawDept = (String) row.get("DEPT_CD");
            String rawPart = (String) row.get("PART_CD");
            // null 또는 공백 DEPT_CD/PART_CD → '미분류' 정규화 (UNIT_ID null 방지)
            String deptCd  = (rawDept == null || rawDept.isBlank()) ? "미분류" : rawDept;
            String partCd  = (rawPart == null || rawPart.isBlank()) ? "미분류" : rawPart;
            boolean active = "01".equals(row.get("STATUS_CD"));  // V003에서 '재직' → '01' 재코딩

            if (active) {
                deptByEmp.put(empno, deptCd);
                partByEmp.put(empno, partCd);

                // DEPT 집계
                int[] hd = headByDept.computeIfAbsent(deptCd, k -> new int[2]);
                hd[0]++;
                if ("Y".equals(row.get("DEV_YN"))) hd[1]++;

                // PART 집계 (복합키: deptCd-partCd)
                String partKey = deptCd + "-" + partCd;
                int[] hp = headByPart.computeIfAbsent(partKey, k -> new int[2]);
                hp[0]++;
                if ("Y".equals(row.get("DEV_YN"))) hp[1]++;
            }
        }

        // PART 단위 USED_MM / OVERTIME_MM (미매칭 → '미분류-미분류')
        Map<String, double[]> usedOtByPart = new LinkedHashMap<>();
        // DEPT 단위 USED_MM / OVERTIME_MM (미매칭 → '미분류')
        Map<String, double[]> usedOtByDept = new LinkedHashMap<>();

        for (Map.Entry<String, Double> e : mmByEmp.entrySet()) {
            String empno  = e.getKey();
            double empMm  = e.getValue();
            double ot     = Math.max(empMm - mm.overtimeThreshold(), 0.0);

            String deptCd  = deptByEmp.getOrDefault(empno, "미분류");
            String partCd  = partByEmp.getOrDefault(empno, "미분류");
            String partKey = deptCd + "-" + partCd;

            double[] up = usedOtByPart.computeIfAbsent(partKey, k -> new double[2]);
            up[0] += empMm;
            up[1] += ot;

            double[] ud = usedOtByDept.computeIfAbsent(deptCd, k -> new double[2]);
            ud[0] += empMm;
            ud[1] += ot;
        }

        // PART 행 insert 먼저
        Set<String> allPartKeys = new LinkedHashSet<>();
        allPartKeys.addAll(headByPart.keySet());
        allPartKeys.addAll(usedOtByPart.keySet());

        for (String partKey : allPartKeys) {
            int[]    hArr = headByPart  .getOrDefault(partKey, new int[2]);
            double[] uArr = usedOtByPart.getOrDefault(partKey, new double[2]);
            dash.insertResource(new ResourceSnapshot(
                    periodYm, "PART", partKey,
                    hArr[0], hArr[1], round2(hArr[1] * 1.0), round2(uArr[0]), round2(uArr[1])));
        }

        // DEPT 행 insert
        Set<String> allDeptKeys = new LinkedHashSet<>();
        allDeptKeys.addAll(headByDept.keySet());
        allDeptKeys.addAll(usedOtByDept.keySet());

        int    allHead = 0, allAvail = 0;
        double allUsed = 0.0, allOt = 0.0;

        for (String deptCd : allDeptKeys) {
            int[]    hArr = headByDept  .getOrDefault(deptCd, new int[2]);
            double[] uArr = usedOtByDept.getOrDefault(deptCd, new double[2]);

            double deptUsed  = round2(uArr[0]);
            double deptOt    = round2(uArr[1]);
            double deptAvail = round2(hArr[1] * 1.0);

            dash.insertResource(new ResourceSnapshot(
                    periodYm, "DEPT", deptCd,
                    hArr[0], hArr[1], deptAvail, deptUsed, deptOt));

            allHead  += hArr[0];
            allAvail += hArr[1];
            allUsed  += deptUsed;
            allOt    += deptOt;
        }

        // ALL 행 insert (마지막)
        dash.insertResource(new ResourceSnapshot(
                periodYm, "ALL", "ALL",
                allHead, allAvail,
                round2(allAvail * 1.0), allUsed, allOt));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
