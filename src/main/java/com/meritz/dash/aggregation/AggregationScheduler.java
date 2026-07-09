package com.meritz.dash.aggregation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 집계 일배치 스케줄러.
 * 기본 cron: 매일 새벽 2시 (6-field Spring cron 표현식).
 * {@code app.aggregation.cron} 프로퍼티로 재정의 가능.
 */
@Component
public class AggregationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AggregationScheduler.class);
    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final AggregationService service;

    public AggregationScheduler(AggregationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.aggregation.cron:0 0 2 * * *}")
    public void daily() {
        LocalDate today = LocalDate.now();
        String current = today.format(YM_FMT);
        String prev    = today.minusMonths(1).format(YM_FMT);

        for (String ym : new String[]{current, prev}) {
            log.info("[AggregationScheduler] SCHEDULED 집계 시작: periodYm={}", ym);
            try {
                long runId = service.run(ym, "SCHEDULED");
                log.info("[AggregationScheduler] 완료: periodYm={}, runId={}", ym, runId);
            } catch (Exception e) {
                log.error("[AggregationScheduler] 실패: periodYm={}", ym, e);
            }
        }
    }
}
