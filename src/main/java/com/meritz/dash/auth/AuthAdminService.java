package com.meritz.dash.auth;

import com.meritz.dash.common.NotFoundException;
import com.meritz.dash.mapper.app.AuthAccountMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 관리자 전용 계정 운영 — 현황 조회, 잠금·휴면 해제, 비밀번호 초기화. */
@Service
public class AuthAdminService {

    private static final Map<String, String> STATUS_NAME = Map.of("00", "정상", "01", "잠금", "02", "휴면");

    private final AuthAccountMapper accounts;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;

    public AuthAdminService(AuthAccountMapper accounts, PasswordEncoder encoder, PasswordPolicy policy) {
        this.accounts = accounts; this.encoder = encoder; this.policy = policy;
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<AdminAccountRow> listAccounts() {
        return accounts.findAllForAdmin().stream().map(r -> new AdminAccountRow(
                r.empno(), r.name(), r.statusCd(), STATUS_NAME.getOrDefault(r.statusCd(), r.statusCd()),
                r.failCnt(), r.lastLoginAt(), r.passwordChangedAt(),
                policy.isExpired(r.passwordChangedAt()), policy.isDormant(r.lastLoginAt())
        )).toList();
    }

    @Transactional("appTxManager")
    public void unlock(String empno) {
        if (accounts.unlockAccount(empno) == 0) {
            throw new NotFoundException("해당 계정을 찾을 수 없습니다: " + empno);
        }
    }

    @Transactional("appTxManager")
    public void resetPassword(String empno) {
        if (accounts.resetToDefault(empno, encoder.encode(empno)) == 0) {
            throw new NotFoundException("해당 계정을 찾을 수 없습니다: " + empno);
        }
    }
}
