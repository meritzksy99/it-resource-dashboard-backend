package com.meritz.dash.auth;

import com.meritz.dash.code.CommonCode;
import com.meritz.dash.common.ApiResponse;
import com.meritz.dash.developer.Developer;
import com.meritz.dash.mapper.app.CodeMapper;
import com.meritz.dash.mapper.app.DeveloperMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 현재 사용자 조회 경량 컨트롤러 — 게이트웨이(Keycloak/AD) 인증 전환 후 남는 유일한 auth 엔드포인트.
 * 인증은 게이트웨이 인터셉터가 수행하고, 여기서는 {@link AuthContext}의 사번으로 HR 정보를 조회한다.
 */
@Tag(name = "Auth", description = "인증 — 현재 사용자")
@RestController
@RequestMapping("/api/v1/auth")
public class MeController {

    private final DeveloperMapper developers;
    private final CodeMapper codes;

    public MeController(DeveloperMapper developers, CodeMapper codes) {
        this.developers = developers;
        this.codes = codes;
    }

    @Operation(
        summary = "현재 사용자 정보",
        description = "인증 필요(게이트웨이 토큰). 현재 사용자 정보(사번·이름·역할·파트) 조회."
    )
    @Auth
    @GetMapping("/me")
    public ApiResponse<MeResult> me() {
        String empno = AuthContext.empno();
        Developer dev = developers.findByEmpno(empno);
        if (dev == null) {
            // 게이트웨이 인터셉터가 HR 미등록 사번을 403으로 이미 거부하므로 정상 흐름에선 도달 불가.
            // 방어적 이중화 — 인터셉터와 동일하게 403(fail-closed)으로 응답한다.
            throw new ForbiddenException("권한이 없습니다");
        }
        return ApiResponse.of(new MeResult(dev.empno(), dev.roleCd(), resolveRoleName(dev.roleCd()),
                dev.empNm(), dev.partCd()));
    }

    private String resolveRoleName(String roleCd) {
        Map<String, String> roleMap = codes.findByGroup("EMP_ROLE").stream()
                .collect(Collectors.toMap(CommonCode::cdVal, CommonCode::cdNm));
        return roleMap.getOrDefault(roleCd, roleCd);
    }
}
