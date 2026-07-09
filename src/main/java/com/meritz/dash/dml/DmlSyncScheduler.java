package com.meritz.dash.dml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * DML 점검 동기화 주간 배치 스케줄러.
 * 기본 cron: 매주 월요일 새벽 3시 (6-field Spring cron 표현식).
 * {@code app.dml-sync.cron} 프로퍼티(application.yml)로 재정의 가능.
 */
@Component
public class DmlSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DmlSyncScheduler.class);
    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final DmlSyncService service;

    public DmlSyncScheduler(DmlSyncService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.dml-sync.cron:0 0 3 * * MON}")
    public void weekly() {
        String baseYm = LocalDate.now().format(YM_FMT);
        log.info("[DmlSyncScheduler] SCHEDULED 동기화 시작: baseYm={}", baseYm);
        try {
            DmlSyncService.SyncResult result = service.sync(baseYm, "SCHEDULED");
            log.info("[DmlSyncScheduler] 완료: baseYm={}, fetched={}, matched={}",
                    result.baseYm(), result.fetched(), result.matched());
        } catch (Exception e) {
            log.error("[DmlSyncScheduler] 실패: baseYm={}", baseYm, e);
        }
    }
}
