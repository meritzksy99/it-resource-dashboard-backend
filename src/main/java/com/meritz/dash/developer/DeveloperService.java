package com.meritz.dash.developer;

import com.meritz.dash.mapper.app.DeveloperMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DeveloperService {

    private final DeveloperMapper mapper;

    public DeveloperService(DeveloperMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<Developer> list(String deptCd, String partCd, String devYn, String statusCd) {
        return mapper.findAll(deptCd, partCd, devYn, statusCd);
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public Developer get(String empno) {
        Developer d = mapper.findByEmpno(empno);
        if (d == null) {
            throw new IllegalArgumentException("사번 " + empno + " 인력이 없습니다.");
        }
        return d;
    }

    @Transactional(transactionManager = "appTxManager")
    public Developer create(DeveloperRequest req) {
        if (mapper.findByEmpno(req.empno()) != null) {
            throw new IllegalArgumentException("이미 존재하는 사번: " + req.empno());
        }
        mapper.insert(req.toDeveloper());
        return mapper.findByEmpno(req.empno());
    }

    @Transactional(transactionManager = "appTxManager")
    public Developer update(String empno, DeveloperRequest req) {
        get(empno); // 없으면 IllegalArgumentException
        mapper.update(req.toDeveloper(empno)); // 경로 empno 우선, 바디 empno 무시
        return mapper.findByEmpno(empno);
    }

    @Transactional(transactionManager = "appTxManager")
    public void delete(String empno) {
        get(empno); // 없으면 IllegalArgumentException
        mapper.deleteByEmpno(empno);
    }
}
