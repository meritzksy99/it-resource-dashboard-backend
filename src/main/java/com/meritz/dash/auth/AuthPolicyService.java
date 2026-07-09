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

    /**
     * 계정 열거(enumeration) 방지용 더미 BCrypt 해시.
     * 존재하지 않는 사번이어도 실제 계정과 동일한 BCrypt 연산을 수행해 응답 시간을 균일화한다.
     * (문법상 유효한 60자 해시면 충분 — 어떤 비밀번호와도 매칭될 필요 없음)
     */
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

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
            // 타이밍 균일화: 실제 계정과 동일하게 BCrypt 비교 수행(결과 무시)
            encoder.matches(req.password(), DUMMY_HASH);
            // 실제 계정의 첫 실패와 동일한 remainingAttempts(max-1) — 계정 존재 여부 노출 방지
            throw AuthPolicyException.invalidCredentials(props.lockout().maxFail() - 1);
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
            // 비밀번호 비교는 이미 수행됨 — remainingAttempts만 균일화(max-1)
            throw AuthPolicyException.invalidCredentials(props.lockout().maxFail() - 1);
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
