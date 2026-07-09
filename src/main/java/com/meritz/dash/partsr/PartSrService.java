package com.meritz.dash.partsr;

import com.meritz.dash.mapper.app.PartSrMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PartSrService {

    static final String NULL_PART_KEY = "$$NULL$$";

    private final PartSrMapper mapper;
    private final PartSrProperties partSrProperties;

    public PartSrService(PartSrMapper mapper, PartSrProperties partSrProperties) {
        this.mapper = mapper;
        this.partSrProperties = partSrProperties;
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public PartSrResult summary(String period, String part) {
        if (period == null || !period.matches("\\d{4}(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("period는 YYYYMM 6자리 실제 월이어야 합니다: " + period);
        }
        // part는 선택 파라미터. 화이트리스트(영문·숫자 1~20자)로 형식·길이 제한.
        // 값 주입은 #{} 바인드라 injection은 이미 차단되나, 과도한 길이/이상 문자로 인한 DB 오류·로그오염을 방지한다.
        if (part != null && !part.matches("[A-Za-z0-9]{1,20}")) {
            throw new IllegalArgumentException("part는 영문·숫자 1~20자리여야 합니다.");
        }

        String outsourcingDeptCd = partSrProperties.outsourcingDeptCd();

        // 1. 코드명 맵 로드
        Map<String, String> partNmMap  = loadCodeMap("PART_CD");
        Map<String, String> srClsNmMap = loadCodeMap("SR_CLS");
        Map<String, String> deptNmMap  = loadCodeMap("DEPT_CD");

        // 2. 명부 — 재직자(STATUS_CD='01'), part 필터 선택적
        List<Map<String, Object>> roster = mapper.findRoster(part);

        // 3. SR 집계 — (PART_CD, DEPT_CD, SR_CLS) 별
        List<Map<String, Object>> srRows = mapper.findSrByPartClass(period, part);

        // 4. 명부를 PART_CD 기준으로 그룹화 (null → NULL_KEY sentinel)
        //    내부(DEPT_CD != outsourcingDeptCd) / 외주(DEPT_CD == outsourcingDeptCd) 분리
        Map<String, List<Map<String, Object>>> internalByPart = roster.stream()
                .filter(m -> !outsourcingDeptCd.equals(strVal(m, "DEPT_CD", "")))
                .collect(Collectors.groupingBy(
                        m -> { String p = strVal(m, "PART_CD", null); return p != null ? p : NULL_PART_KEY; },
                        TreeMap::new,
                        Collectors.toList()
                ));

        Map<String, List<Map<String, Object>>> outsourcingByPart = roster.stream()
                .filter(m -> outsourcingDeptCd.equals(strVal(m, "DEPT_CD", "")))
                .collect(Collectors.groupingBy(
                        m -> { String p = strVal(m, "PART_CD", null); return p != null ? p : NULL_PART_KEY; },
                        TreeMap::new,
                        Collectors.toList()
                ));

        // 5. SR 집계를 내부/외주 별로 (partKey, srCls) → {srCnt, mmX1000} 인덱싱
        Map<String, Map<String, long[]>> srInternal    = new TreeMap<>();
        Map<String, Map<String, long[]>> srOutsourcing = new TreeMap<>();

        for (Map<String, Object> row : srRows) {
            String rawPartCd = strVal(row, "PART_CD", null);
            String partKey   = rawPartCd != null ? rawPartCd : NULL_PART_KEY;
            String deptCd    = strVal(row, "DEPT_CD", "");
            String srCls     = strVal(row, "SR_CLS", "99");
            boolean isOut    = outsourcingDeptCd.equals(deptCd);
            long srCnt       = toLong(row, "SR_CNT");
            long mmX1000     = toMmX1000(row, "JOB_MM");

            Map<String, Map<String, long[]>> target = isOut ? srOutsourcing : srInternal;
            target.computeIfAbsent(partKey, k -> new TreeMap<>())
                  .merge(srCls, new long[]{srCnt, mmX1000}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]});
        }

        // 6. 내부 파트 목록 조립 (명부 + SR 합집합)
        Set<String> allInternalKeys = new TreeSet<>(internalByPart.keySet());
        allInternalKeys.addAll(srInternal.keySet());

        List<PartSrRow> parts = new ArrayList<>();
        for (String groupKey : allInternalKeys) {
            List<Map<String, Object>> members = internalByPart.getOrDefault(groupKey, List.of());

            // 내부 파트의 deptCd/deptNm: 해당 파트 첫 내부 멤버의 DEPT_CD
            String deptCd = members.stream()
                    .map(m -> strVal(m, "DEPT_CD", null))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            String deptNm = deptCd != null ? deptNmMap.getOrDefault(deptCd, deptCd) : null;

            PartSrRow row = buildRow(groupKey, members,
                    srInternal.getOrDefault(groupKey, Map.of()),
                    srClsNmMap, partNmMap, deptCd, deptNm);
            parts.add(row);
        }

        // 7. 외주 파트 목록 조립
        Set<String> allOutsourcingKeys = new TreeSet<>(outsourcingByPart.keySet());
        allOutsourcingKeys.addAll(srOutsourcing.keySet());

        List<PartSrRow> outsourcing = new ArrayList<>();
        for (String groupKey : allOutsourcingKeys) {
            List<Map<String, Object>> members = outsourcingByPart.getOrDefault(groupKey, List.of());

            PartSrRow row = buildRow(groupKey, members,
                    srOutsourcing.getOrDefault(groupKey, Map.of()),
                    srClsNmMap, partNmMap, outsourcingDeptCd, "외주");
            outsourcing.add(row);
        }

        return new PartSrResult(parts, outsourcing);
    }

    private PartSrRow buildRow(String groupKey,
                                List<Map<String, Object>> members,
                                Map<String, long[]> srClsMap,
                                Map<String, String> srClsNmMap,
                                Map<String, String> partNmMap,
                                String fixedDeptCd, String fixedDeptNm) {
        String partCd = NULL_PART_KEY.equals(groupKey) ? null : groupKey;
        String partNm = partCd != null ? partNmMap.getOrDefault(partCd, partCd) : "미지정";

        List<String> names = members.stream()
                .map(m -> strVal(m, "EMP_NM", ""))
                .sorted()
                .toList();

        List<SrClassCount> srByClass = srClsMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new SrClassCount(
                        e.getKey(),
                        srClsNmMap.getOrDefault(e.getKey(), e.getKey()),
                        (int) e.getValue()[0],
                        e.getValue()[1] / 1000.0))
                .toList();

        long totMmX1000 = srClsMap.values().stream().mapToLong(a -> a[1]).sum();
        double totMm = totMmX1000 / 1000.0;

        return new PartSrRow(fixedDeptCd, fixedDeptNm, partCd, partNm,
                names.size(), names, totMm, srByClass);
    }

    private Map<String, String> loadCodeMap(String grpCd) {
        List<Map<String, Object>> rows = mapper.findCodeMap(grpCd);
        Map<String, String> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String k = strVal(row, "CD_VAL", null);
            String v = strVal(row, "CD_NM", null);
            if (k != null) map.put(k, v != null ? v : k);
        }
        return map;
    }

    private static String strVal(Map<String, Object> row, String key, String defaultVal) {
        Object v = row.get(key);
        if (v == null) return defaultVal;
        return v.toString();
    }

    private static long toLong(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return 0L; }
    }

    private static long toMmX1000(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return 0L;
        if (v instanceof Number n) return Math.round(n.doubleValue() * 1000.0);
        try { return Math.round(Double.parseDouble(v.toString()) * 1000.0); } catch (NumberFormatException e) { return 0L; }
    }
}
