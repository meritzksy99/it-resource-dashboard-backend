package com.meritz.dash.weekly;

import com.meritz.dash.mapper.legacy.WeeklySrMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기간계 SR 참조 실시간 조회를 legacyTxManager readOnly 트랜잭션으로 감싸는 얇은 리더
 * ({@link com.meritz.dash.devsr.DevSrLegacyReader} 동형 — CLAUDE.md 3.2).
 */
@Service
public class WeeklySrLegacyReader {

    private final WeeklySrMapper mapper;

    public WeeklySrLegacyReader(WeeklySrMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(transactionManager = "legacyTxManager", readOnly = true)
    public SrRef read(String srNo) {
        return mapper.selectSrRef(srNo);
    }
}
