package com.meritz.dash.code;

import com.meritz.dash.mapper.app.CodeMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeMapperIT extends AbstractOracleIT {

    @Autowired CodeMapper codeMapper;

    @Test
    @DisplayName("findByGroup('SR_TPCD') → 7건, SORT_NO 오름차순, 첫 코드=개발요청")
    void findByGroup_srtpcd() {
        List<CommonCode> codes = codeMapper.findByGroup("SR_TPCD");
        assertThat(codes).hasSize(7);
        assertThat(codes.get(0).cdVal()).isEqualTo("01"); // V005: '1' → '01' 패딩
        assertThat(codes.get(0).cdNm()).isEqualTo("개발요청");
    }
}
