package com.meritz.dash.auth;

import com.meritz.dash.code.CommonCode;
import com.meritz.dash.config.AdminProperties;
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

@Service
public class AuthService {

    private final AuthAccountMapper accounts;
    private final DeveloperMapper developers;
    private final CodeMapper codes;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final AdminProperties admin;

    public AuthService(AuthAccountMapper accounts, DeveloperMapper developers, CodeMapper codes,
                       JwtService jwt, PasswordEncoder encoder, AdminProperties admin) {
        this.accounts = accounts;
        this.developers = developers;
        this.codes = codes;
        this.jwt = jwt;
        this.encoder = encoder;
        this.admin = admin;
    }

    @Transactional("appTxManager")
    public LoginResult login(LoginRequest req) {
        // admin 계정은 HR/AUTH_ACCOUNT 조회 없이 최우선 처리 (상수시간 비교)
        if (MessageDigest.isEqual(admin.username().getBytes(StandardCharsets.UTF_8),
                                  req.empno().getBytes(StandardCharsets.UTF_8))) {
            if (!MessageDigest.isEqual(admin.password().getBytes(StandardCharsets.UTF_8),
                                       req.password().getBytes(StandardCharsets.UTF_8))) {
                throw new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다");
            }
            String token = jwt.generate("admin", "ADMIN", "관리자", "관리자", null, null, false);
            return new LoginResult(token, "admin", "ADMIN", "관리자", "관리자", false);
        }

        AuthAccount acc = accounts.findByEmpno(req.empno());
        if (acc == null || !encoder.matches(req.password(), acc.passwordHash())) {
            throw new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다");
        }
        Developer dev = developers.findByEmpno(req.empno());
        if (dev == null) {
            throw new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다");
        }
        String roleName = resolveRoleName(dev.roleCd());
        boolean pwdReset = "Y".equals(acc.pwdResetYn());
        accounts.touchLastLogin(req.empno());
        String token = jwt.generate(dev.empno(), dev.roleCd(), roleName, dev.empNm(), dev.deptCd(), dev.partCd(), pwdReset);
        return new LoginResult(token, dev.empno(), dev.roleCd(), roleName, dev.empNm(), pwdReset);
    }

    @Transactional("appTxManager")
    public void changePassword(String empno, ChangePasswordRequest req) {
        AuthAccount acc = accounts.findByEmpno(empno);
        if (acc == null || !encoder.matches(req.oldPassword(), acc.passwordHash())) {
            throw new IllegalArgumentException("현재 비밀번호가 올바르지 않습니다");
        }
        if (req.newPassword() == null || req.newPassword().length() < 8) {
            throw new IllegalArgumentException("새 비밀번호는 8자 이상이어야 합니다");
        }
        if (empno.equals(req.newPassword())) {
            throw new IllegalArgumentException("새 비밀번호는 사번과 같을 수 없습니다");
        }
        accounts.updatePassword(empno, encoder.encode(req.newPassword()));
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public MeResult me(String empno) {
        // admin 계정은 HR 조회 없이 즉시 반환
        if (MessageDigest.isEqual(admin.username().getBytes(StandardCharsets.UTF_8),
                                  empno.getBytes(StandardCharsets.UTF_8))) {
            return new MeResult("admin", "ADMIN", "관리자", "관리자", null, false);
        }
        Developer dev = developers.findByEmpno(empno);
        if (dev == null) {
            throw new UnauthorizedException("사용자 정보가 없습니다");
        }
        AuthAccount acc = accounts.findByEmpno(empno);
        boolean pwdReset = acc != null && "Y".equals(acc.pwdResetYn());
        return new MeResult(dev.empno(), dev.roleCd(), resolveRoleName(dev.roleCd()),
                dev.empNm(), dev.partCd(), pwdReset);
    }

    private String resolveRoleName(String roleCd) {
        Map<String, String> roleMap = codes.findByGroup("EMP_ROLE").stream()
                .collect(Collectors.toMap(CommonCode::cdVal, CommonCode::cdNm));
        return roleMap.getOrDefault(roleCd, roleCd);
    }
}
