package com.meritz.dash.mapper.app;

import com.meritz.dash.auth.AuthAccount;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AuthAccountMapper {
    AuthAccount findByEmpno(@Param("empno") String empno);
    List<String> findEmpnosNeedingAccount();
    void insertAccount(@Param("empno") String empno, @Param("hash") String hash);
    int updatePassword(@Param("empno") String empno, @Param("hash") String hash); // v1(무손상)
    void touchLastLogin(@Param("empno") String empno);                            // v1(무손상)

    // ── 정책(v2/admin) ─────────────────────────────
    int incrementFail(@Param("empno") String empno);
    int lockAccount(@Param("empno") String empno);
    int markDormant(@Param("empno") String empno);
    int loginSuccess(@Param("empno") String empno);
    int changePasswordWithHistory(@Param("empno") String empno, @Param("hash") String hash, @Param("prevHash") String prevHash);
    int unlockAccount(@Param("empno") String empno);
    int resetToDefault(@Param("empno") String empno, @Param("hash") String hash);
    List<AdminRow> findAllForAdmin();

    record AdminRow(String empno, String name, String statusCd, Integer failCnt,
                    LocalDateTime lastLoginAt, LocalDateTime passwordChangedAt) {}
}
