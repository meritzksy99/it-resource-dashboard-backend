package com.meritz.dash.dml;

import com.meritz.dash.mapper.legacy.DmlSrLegacyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 기간계 DML성 SR 조회를 <b>legacyTxManager readOnly 트랜잭션</b>으로 감싸는 얇은 리더
 * (CLAUDE.md 3.2 — 기간계 조회는 legacy 트랜잭션 매니저 + read-only).
 * <p>
 * DmlSyncService(appTxManager) 내부에서 호출되며, 별도 트랜잭션 매니저라 커넥션 단위
 * read-only·statement timeout 이 기간계 커넥션에 확실히 적용된다(이중 방어).
 */
@Service
public class DmlSrLegacyReader {

    private final DmlSrLegacyMapper mapper;

    public DmlSrLegacyReader(DmlSrLegacyMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(transactionManager = "legacyTxManager", readOnly = true)
    public List<DmlSrLegacyRow> read(String baseYm) {
        return mapper.selectDmlSrs(baseYm);
    }
}
