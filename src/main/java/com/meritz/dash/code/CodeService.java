package com.meritz.dash.code;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.mapper.app.CodeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CodeService {

    private final CodeMapper codeMapper;

    public CodeService(CodeMapper codeMapper) {
        this.codeMapper = codeMapper;
    }

    @Transactional(transactionManager = "appTxManager", readOnly = true)
    public List<CommonCode> getCodes(String grpCd) {
        if (grpCd == null || grpCd.isBlank()) {
            throw new IllegalArgumentException("grpCd는 필수입니다.");
        }
        return codeMapper.findByGroup(grpCd);
    }

    @Transactional(transactionManager = "appTxManager")
    public CommonCode create(CodeRequest req) {
        String grpCd = req.grpCd();
        String cdVal = req.cdVal();
        String useYn = codeMapper.findUseYn(grpCd, cdVal);
        if (useYn == null) {
            // 신규 등록 — USE_YN 항상 'Y'
            codeMapper.insert(grpCd, cdVal, req.cdNm(),
                    req.effectiveSortNo(), "Y", req.attr1());
        } else if ("N".equals(useYn)) {
            // 비활성 코드 재활성화
            codeMapper.update(grpCd, cdVal, req.cdNm(),
                    req.effectiveSortNo(), "Y", req.attr1(), safeEmpno());
        } else {
            // 활성 코드 중복
            throw new IllegalArgumentException("이미 존재하는 코드: " + grpCd + "/" + cdVal);
        }
        return codeMapper.findOne(grpCd, cdVal);
    }

    @Transactional(transactionManager = "appTxManager")
    public CommonCode update(String grpCd, String cdVal, CodeRequest req) {
        if (codeMapper.findOne(grpCd, cdVal) == null) {
            throw new IllegalArgumentException("코드 없음: " + grpCd + "/" + cdVal);
        }
        String updatedBy = safeEmpno();
        codeMapper.update(grpCd, cdVal, req.cdNm(),
                req.effectiveSortNo(), req.effectiveUseYn(), req.attr1(), updatedBy);
        return codeMapper.findOne(grpCd, cdVal);
    }

    @Transactional(transactionManager = "appTxManager")
    public void delete(String grpCd, String cdVal) {
        if (codeMapper.findOne(grpCd, cdVal) == null) {
            throw new IllegalArgumentException("코드 없음: " + grpCd + "/" + cdVal);
        }
        codeMapper.softDelete(grpCd, cdVal, safeEmpno());
    }

    private String safeEmpno() {
        try {
            return AuthContext.empno();
        } catch (Exception e) {
            return "SYSTEM";
        }
    }
}
