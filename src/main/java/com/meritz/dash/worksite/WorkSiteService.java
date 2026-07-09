package com.meritz.dash.worksite;

import com.meritz.dash.mapper.app.WorkSiteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorkSiteService {

    private final WorkSiteMapper workSiteMapper;

    public WorkSiteService(WorkSiteMapper workSiteMapper) {
        this.workSiteMapper = workSiteMapper;
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<WorkSite> getActiveSites() {
        return workSiteMapper.findActive();
    }
}
