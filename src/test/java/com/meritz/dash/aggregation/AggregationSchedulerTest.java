package com.meritz.dash.aggregation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AggregationSchedulerTest {

    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    @Test
    @DisplayName("daily() 호출 시 현재월 + 직전월 두 번 run() 호출")
    void daily_runs_current_and_previous_month() throws Exception {
        AggregationService service = mock(AggregationService.class);
        when(service.run(any(), eq("SCHEDULED"))).thenReturn(1L);

        AggregationScheduler scheduler = new AggregationScheduler(service);
        scheduler.daily();

        String current = LocalDate.now().format(YM_FMT);
        String prev    = LocalDate.now().minusMonths(1).format(YM_FMT);

        verify(service, times(1)).run(eq(current), eq("SCHEDULED"));
        verify(service, times(1)).run(eq(prev),    eq("SCHEDULED"));
        verify(service, times(2)).run(any(), eq("SCHEDULED"));
    }
}
