package com.meritz.dash.srproject;

import com.meritz.dash.config.MmProperties;
import com.meritz.dash.mapper.app.SrProjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SrProjectServiceTest {

    private SrProjectService serviceWithMocks(SrProjectMapper mapper) {
        MmProperties mm = mock(MmProperties.class);
        when(mm.topMinMm()).thenReturn(0.0);
        return new SrProjectService(mapper, mm);
    }

    @Test
    @DisplayName("page=Integer.MAX_VALUE → 오버플로 없이 빈 목록 반환 (offset은 long 범위)")
    void large_page_no_integer_overflow() {
        SrProjectMapper mapper = mock(SrProjectMapper.class);
        // After fix: mapper.findTop takes long offset
        when(mapper.findTop(anyString(), anyDouble(), any(), anyLong(), anyInt()))
            .thenReturn(List.of());
        when(mapper.countTop(anyString(), anyDouble(), any()))
            .thenReturn(1);

        SrProjectService service = serviceWithMocks(mapper);
        SrProjectService.Page page = service.top("202605", null, null, Integer.MAX_VALUE, 5);

        assertThat(page.items()).isEmpty();
        // Verify offset is non-negative (proves no int overflow: Integer.MAX_VALUE * 5 = 10737418235 long)
        verify(mapper).findTop(eq("202605"), anyDouble(), any(),
                longThat(offset -> offset > 0), eq(5));
    }
}
