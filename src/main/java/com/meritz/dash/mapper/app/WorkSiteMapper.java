package com.meritz.dash.mapper.app;

import com.meritz.dash.worksite.WorkSite;

import java.util.List;

public interface WorkSiteMapper {
    /** USE_YN='Y' 인 업무사이트를 SORT_NO, SITE_NM 순으로 조회 */
    List<WorkSite> findActive();
}
