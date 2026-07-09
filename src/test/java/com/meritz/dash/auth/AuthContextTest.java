package com.meritz.dash.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AuthContextTest {

    @AfterEach
    void cleanup() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("set(empno,role,deptCd,partCd) → deptCd/partCd 반환")
    void set_and_get_dept_part() {
        AuthContext.set("5355", "01", "2139", "P01");
        assertThat(AuthContext.empno()).isEqualTo("5355");
        assertThat(AuthContext.role()).isEqualTo("01");
        assertThat(AuthContext.deptCd()).isEqualTo("2139");
        assertThat(AuthContext.partCd()).isEqualTo("P01");
    }

    @Test
    @DisplayName("clear() 후 deptCd/partCd → null")
    void clear_removes_dept_part() {
        AuthContext.set("5355", "01", "2139", "P01");
        AuthContext.clear();
        assertThat(AuthContext.deptCd()).isNull();
        assertThat(AuthContext.partCd()).isNull();
    }

    @Test
    @DisplayName("admin: deptCd/partCd null 허용")
    void admin_null_dept_part() {
        AuthContext.set("admin", "ADMIN", null, null);
        assertThat(AuthContext.deptCd()).isNull();
        assertThat(AuthContext.partCd()).isNull();
    }
}
