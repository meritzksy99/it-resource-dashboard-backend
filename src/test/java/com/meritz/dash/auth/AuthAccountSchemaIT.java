package com.meritz.dash.auth;

import com.meritz.dash.support.AbstractOracleIT;
import com.meritz.dash.mapper.app.AuthAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AuthAccountSchemaIT extends AbstractOracleIT {

    @Autowired AuthAccountMapper mapper;

    @BeforeEach
    void resetAccountState() {
        // 다른 IT(AuthAccountPolicyMapperIT 등)가 E0002의 STATUS_CD를 바꿔도 스스로 복구 — 교차 IT 의존 제거
        mapper.unlockAccount("E0002"); // STATUS_CD='00', FAIL_CNT=0 (PASSWORD_CHANGED_AT은 건드리지 않음)
    }

    @Test
    void findByEmpno_maps_new_policy_columns() {
        AuthAccount acc = mapper.findByEmpno("E0002"); // AccountProvisioner 시드 계정(V002 재직자)
        assertThat(acc).isNotNull();
        assertThat(acc.statusCd()).isEqualTo("00");
        assertThat(acc.passwordChangedAt()).isNotNull(); // V017 백필
    }
}
