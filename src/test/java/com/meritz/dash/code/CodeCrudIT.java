package com.meritz.dash.code;

import com.meritz.dash.mapper.app.CodeMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeCrudIT extends AbstractOracleIT {

    @Autowired CodeMapper codeMapper;
    @Autowired CodeService codeService;

    @Autowired
    @Qualifier("appDataSource")
    DataSource appDataSource;

    @AfterEach
    void cleanup() {
        JdbcTemplate jdbc = new JdbcTemplate(appDataSource);
        jdbc.update("DELETE FROM CD_COMMON WHERE GRP_CD = 'TEST_GRP'");
    }

    @Test
    @DisplayName("insert 후 findByGroup으로 조회 시 해당 코드가 보여야 한다(USE_YN='Y')")
    void insert_then_visible_in_findByGroup() {
        // given
        CodeRequest req = new CodeRequest("TEST_GRP", "T01", "테스트코드", 1, "Y", null);

        // when
        codeService.create(req);
        List<CommonCode> codes = codeMapper.findByGroup("TEST_GRP");

        // then
        assertThat(codes).anyMatch(c -> "T01".equals(c.cdVal()));
    }

    @Test
    @DisplayName("insert 후 cdNm을 수정하면 findByGroup에서 수정된 이름이 반영된다")
    void update_cdNm_reflected() {
        // given
        CodeRequest req = new CodeRequest("TEST_GRP", "T02", "원본명", 1, "Y", null);
        codeService.create(req);

        // when
        CodeRequest updateReq = new CodeRequest("TEST_GRP", "T02", "수정명", 1, "Y", null);
        codeService.update("TEST_GRP", "T02", updateReq);
        List<CommonCode> codes = codeMapper.findByGroup("TEST_GRP");

        // then
        assertThat(codes)
                .filteredOn(c -> "T02".equals(c.cdVal()))
                .singleElement()
                .extracting(CommonCode::cdNm)
                .isEqualTo("수정명");
    }

    @Test
    @DisplayName("softDelete 후 findByGroup에서 해당 코드가 사라진다(USE_YN='N'이므로 목록 미노출)")
    void softDelete_hides_from_findByGroup() {
        // given
        CodeRequest req = new CodeRequest("TEST_GRP", "T03", "삭제대상", 1, "Y", null);
        codeService.create(req);
        int sizeBefore = codeMapper.findByGroup("TEST_GRP").size();

        // when
        codeService.delete("TEST_GRP", "T03");
        int sizeAfter = codeMapper.findByGroup("TEST_GRP").size();

        // then
        assertThat(sizeAfter).isEqualTo(sizeBefore - 1);
        assertThat(codeMapper.findByGroup("TEST_GRP"))
                .noneMatch(c -> "T03".equals(c.cdVal()));
    }

    @Test
    @DisplayName("동일한 (grpCd, cdVal)로 두 번 create하면 두 번째 호출에서 IllegalArgumentException이 발생한다")
    void duplicate_insert_throws_IllegalArgumentException() {
        // given
        CodeRequest req = new CodeRequest("TEST_GRP", "T04", "중복코드", 1, "Y", null);
        codeService.create(req);

        // when / then
        assertThatThrownBy(() -> codeService.create(req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("softDelete 후 동일 (grpCd,cdVal)로 재create → 예외 없이 재활성화되어 findByGroup에 다시 보인다")
    void softDelete_then_recreate_reactivates() {
        // given
        CodeRequest req = new CodeRequest("TEST_GRP", "T05", "재활성화테스트", 1, "Y", null);
        codeService.create(req);
        codeService.delete("TEST_GRP", "T05");
        // verify it's gone
        assertThat(codeMapper.findByGroup("TEST_GRP"))
                .noneMatch(c -> "T05".equals(c.cdVal()));

        // when — recreate the same code after soft-delete
        CodeRequest reactivateReq = new CodeRequest("TEST_GRP", "T05", "재활성화된이름", 2, "Y", null);
        CommonCode reactivated = codeService.create(reactivateReq);

        // then — visible again with new name
        assertThat(reactivated).isNotNull();
        assertThat(reactivated.cdNm()).isEqualTo("재활성화된이름");
        assertThat(codeMapper.findByGroup("TEST_GRP"))
                .anyMatch(c -> "T05".equals(c.cdVal()));
    }

    @Test
    @DisplayName("활성(USE_YN='Y') 코드를 중복 create하면 IllegalArgumentException 발생")
    void active_duplicate_create_throws() {
        // given
        CodeRequest req = new CodeRequest("TEST_GRP", "T06", "활성중복테스트", 1, "Y", null);
        codeService.create(req);

        // when / then
        assertThatThrownBy(() -> codeService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 존재하는 코드");
    }
}
