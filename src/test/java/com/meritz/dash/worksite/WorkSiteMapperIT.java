package com.meritz.dash.worksite;

import com.meritz.dash.mapper.app.WorkSiteMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V015 시드 기준:
 *  - USE_YN='Y' 4건 (그룹웨어/ITSM SR관리/개발자 포털/사내 위키)
 *  - USE_YN='N' 1건 (구 그룹웨어) → 조회 제외 대상
 *  - 정렬: SORT_NO 오름차순, 동순위(3)는 SITE_NM 순 → 개발자 포털 < 사내 위키
 */
class WorkSiteMapperIT extends AbstractOracleIT {

    @Autowired WorkSiteMapper workSiteMapper;

    @Test
    @DisplayName("findActive → USE_YN='Y' 4건만, USE_YN='N'(구 그룹웨어) 제외")
    void findActive_excludes_use_yn_n() {
        List<WorkSite> sites = workSiteMapper.findActive();

        assertThat(sites).hasSize(4);
        assertThat(sites).extracting(WorkSite::name).doesNotContain("구 그룹웨어");
    }

    @Test
    @DisplayName("findActive → SORT_NO, SITE_NM 순 정렬")
    void findActive_sorted_by_sort_no_then_name() {
        List<WorkSite> sites = workSiteMapper.findActive();

        assertThat(sites).extracting(WorkSite::name)
                .containsExactly("그룹웨어", "ITSM SR관리", "개발자 포털", "사내 위키");
    }

    @Test
    @DisplayName("findActive → url/description 매핑 확인")
    void findActive_maps_fields() {
        WorkSite first = workSiteMapper.findActive().get(0);

        assertThat(first.url()).isEqualTo("https://gw.example.co.kr");
        assertThat(first.name()).isEqualTo("그룹웨어");
        assertThat(first.description()).isEqualTo("전자결재·메일·게시판 통합 그룹웨어");
    }
}
