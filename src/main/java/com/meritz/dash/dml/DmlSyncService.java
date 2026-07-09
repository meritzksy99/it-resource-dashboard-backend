package com.meritz.dash.dml;

import com.meritz.dash.mapper.app.DevSrScopeMapper;
import com.meritz.dash.mapper.app.DmlSrMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DML성 SR(유형 18/19) 주간 동기화 서비스.
 * <p>
 * 기간계 조회(SELECT-only, {@link DmlSrLegacyReader} 내부에서 legacyTxManager readOnly)
 * → HR_DEVELOPER 재직자와 담당자(PIC_EMPNO) 매칭으로 개발부서/파트 확정
 * → {@code DASH_DML_SR} MERGE upsert (appTxManager).
 * 점검/개선 입력(DASH_DML_CHECK)은 건드리지 않아 재실행에 안전하다(멱등·보존).
 */
@Service
public class DmlSyncService {

    private static final Logger log = LoggerFactory.getLogger(DmlSyncService.class);

    private final DmlSrLegacyReader legacyReader;
    private final DmlSrMapper mapper;
    private final DevSrScopeMapper scopeMapper;

    public DmlSyncService(DmlSrLegacyReader legacyReader, DmlSrMapper mapper, DevSrScopeMapper scopeMapper) {
        this.legacyReader = legacyReader;
        this.mapper = mapper;
        this.scopeMapper = scopeMapper;
    }

    /** 동기화 결과 — 기간계 조회 건수(fetched) / HR 매칭·저장 건수(matched). */
    public record SyncResult(String baseYm, int fetched, int matched) {}

    @Transactional(transactionManager = "appTxManager")
    public SyncResult sync(String baseYm, String trigger) {
        // 1. 기간계 조회 (legacyTxManager readOnly 는 reader 내부)
        List<DmlSrLegacyRow> rows = legacyReader.read(baseYm);

        // 2. HR_DEVELOPER 재직자(STATUS_CD='01') map<empno, HrRef>
        Map<String, DevSrScopeMapper.HrRef> hr = scopeMapper.findRefs(null, null).stream()
                .collect(Collectors.toMap(DevSrScopeMapper.HrRef::empno, r -> r, (a, b) -> a));

        // 3. 담당자(picEmpno)가 개발팀(HR)인 건만 부서/파트 매핑해서 MERGE
        int matched = 0;
        for (DmlSrLegacyRow r : rows) {
            DevSrScopeMapper.HrRef ref = r.picEmpno() == null ? null : hr.get(r.picEmpno());
            if (ref == null) {
                continue; // 개발팀 외 담당 SR 은 스냅샷 대상 아님
            }
            DmlSr sr = new DmlSr(r.srNo(), baseYm, r.srTpcd(), r.srTpcdName(), r.bswrDetlName(),
                    r.statusCode(), r.titlCntt(), r.msgCntt(), r.custInfoYn(),
                    r.rqsrEmpno(), r.rqsrNm(), r.rqsrDpcd(), r.trthRqstNm(), r.trthRqstDpcd(),
                    r.picEmpno(), r.picNm(), r.picDpcd(), r.picDpnm(),
                    ref.deptCd(), ref.partCd(),
                    r.regDate(), r.rflcScdlDate(), r.prosCmptDate());
            mapper.mergeSnapshot(sr);
            matched++;
        }
        log.info("[DmlSync] baseYm={} trigger={} fetched={} matched={}", baseYm, trigger, rows.size(), matched);
        return new SyncResult(baseYm, rows.size(), matched);
    }
}
