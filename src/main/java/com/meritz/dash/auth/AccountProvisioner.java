package com.meritz.dash.auth;

import com.meritz.dash.mapper.app.AuthAccountMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class AccountProvisioner implements ApplicationRunner {

    private final AuthAccountMapper mapper;
    private final PasswordEncoder encoder;

    public AccountProvisioner(AuthAccountMapper mapper, PasswordEncoder encoder) {
        this.mapper = mapper;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        provision();
    }

    /** 재직 직원 중 계정 없는 사번에 초기비번=사번으로 계정 생성(멱등). */
    @Transactional("appTxManager")
    public void provision() {
        List<String> empnos = mapper.findEmpnosNeedingAccount();
        for (String empno : empnos) {
            mapper.insertAccount(empno, encoder.encode(empno));
        }
    }
}
