package com.meritz.dash.auth;

import com.meritz.dash.code.CommonCode;
import com.meritz.dash.config.AdminProperties;
import com.meritz.dash.config.AuthPolicyProperties;
import com.meritz.dash.developer.Developer;
import com.meritz.dash.mapper.app.AuthAccountMapper;
import com.meritz.dash.mapper.app.CodeMapper;
import com.meritz.dash.mapper.app.DeveloperMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.stream.Collectors;

/** v2 로그인/비밀번호 변경 — 정책(잠금·휴면·만료·복잡도·재사용) 적용. v1 AuthService 와 독립. */
@Service
public class AuthPolicyService {

    private final AuthAccountMapper accounts;
    private final DeveloperMapper developers;
    private final CodeMapper codes;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final AdminProperties admin;
    private final PasswordPolicy policy;
    private final AuthPolicyProperties props;

    public AuthPolicyService(AuthAccountMapper accounts, DeveloperMapper developers, CodeMapper codes,
                             JwtService jwt, PasswordEncoder encoder, AdminProperties admin,
                             PasswordPolicy policy, AuthPolicyProperties props) {
        this.accounts = accounts; this.developers = developers; this.codes = codes;
        this.jwt = jwt; this.encoder = encoder; this.admin = admin;
        this.policy = policy; this.props = props;
    }

    @Transactional("appTxManager")
    public LoginResult login(LoginRequest req) {
        // ADMIN 설정 계정 지름길(정책 미적용, 상수시간 비교)
        if (MessageDigest.isEqual(admin.username().getBytes(StandardCharsets.UTF_8),
                                  req.empno().getBytes(StandardCharsets.UTF_8))) {
            if (!MessageDigest.isEqual(admin.password().getBytes(StandardCharsets.UTF_8),
                                       req.password().getBytes(StandardCharsets.UTF_8))) {
                throw AuthPolicyException.invalidCredentials(0);
            }
            String token = jwt.generate("admin", "ADMIN", "관리자", "관리자", null, null, false);
            return new LoginResult(token, "admin", "ADMIN", "관리자", "관리자", false);
        }

        AuthAccount acc = accounts.findByEmpno(req.empno());
        if (acc == null) {
            throw AuthPolicyException.invalidCredentials(props.lockout().maxFail());
        }
        // 잠금/휴면 선차단
        if ("01".equals(acc.statusCd())) throw AuthPolicyException.locked();
        if ("02".equals(acc.statusCd())) throw AuthPolicyException.dormant();
        // 지연 휴면 판정
        if (policy.isDormant(acc.lastLoginAt())) {
            accounts.markDormant(req.empno());
            throw AuthPolicyException.dormant();
        }
        // 비밀번호 검증
        if (!encoder.matches(req.password(), acc.passwordHash())) {
            accounts.incrementFail(req.empno());
            int newFail = (acc.failCnt() == null ? 0 : acc.failCnt()) + 1;
            int max = props.lockout().maxFail();
            if (newFail >= max) {
                accounts.lockAccount(req.empno());
            }
            throw AuthPolicyException.invalidCredentials(Math.max(0, max - newFail));
        }
        Developer dev = developers.findByEmpno(req.empno());
        if (dev == null) {
            throw AuthPolicyException.invalidCredentials(props.lockout().maxFail());
        }
        accounts.loginSuccess(req.empno());
        boolean pwdReset = "Y".equals(acc.pwdResetYn()) || policy.isExpired(acc.passwordChangedAt());
        String roleName = resolveRoleName(dev.roleCd());
        String token = jwt.generate(dev.empno(), dev.roleCd(), roleName, dev.empNm(), dev.deptCd(), dev.partCd(), pwdReset);
        return new LoginResult(token, dev.empno(), dev.roleCd(), roleName, dev.empNm(), pwdReset);
    }

    @Transactional("appTxManager")
    public void changePassword(String empno, ChangePasswordRequest req) {
        AuthAccount acc = accounts.findByEmpno(empno);
        if (acc == null || !encoder.matches(req.oldPassword(), acc.passwordHash())) {
            throw AuthPolicyException.invalidCredentials(0);
        }
        policy.validate(req.newPassword());
        boolean sameAsCurrent = encoder.matches(req.newPassword(), acc.passwordHash());
        boolean sameAsPrev = acc.prevPasswordHash() != null
                && encoder.matches(req.newPassword(), acc.prevPasswordHash());
        if (sameAsCurrent || sameAsPrev) {
            throw AuthPolicyException.reuse();
        }
        accounts.changePasswordWithHistory(empno, encoder.encode(req.newPassword()), acc.passwordHash());
    }

    private String resolveRoleName(String roleCd) {
        Map<String, String> roleMap = codes.findByGroup("EMP_ROLE").stream()
                .collect(Collectors.toMap(CommonCode::cdVal, CommonCode::cdNm));
        return roleMap.getOrDefault(roleCd, roleCd);
    }
}
