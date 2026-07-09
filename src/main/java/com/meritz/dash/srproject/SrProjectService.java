package com.meritz.dash.srproject;

import com.meritz.dash.config.MmProperties;
import com.meritz.dash.mapper.app.SrProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class SrProjectService {
    private final SrProjectMapper mapper;
    private final MmProperties mm;

    public SrProjectService(SrProjectMapper mapper, MmProperties mm) {
        this.mapper = mapper;
        this.mm = mm;
    }

    public record Page(List<SrProjectView> items, int totalElements) {}

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public Page top(String period, Double minMm, String type, int page, int size) {
        if (period == null || !period.matches("\\d{6}")) throw new IllegalArgumentException("period는 YYYYMM");
        if (page < 0 || size < 1) throw new IllegalArgumentException("페이징 파라미터 오류");
        double floor = (minMm == null) ? mm.topMinMm() : minMm;
        return new Page(mapper.findTop(period, floor, type, (long) page * size, size),
                        mapper.countTop(period, floor, type));
    }
}
