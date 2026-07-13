package com.meritz.dash.auth;

import com.meritz.dash.config.GatewayAuthProperties;
import com.meritz.dash.developer.Developer;
import com.meritz.dash.mapper.app.DeveloperMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;

/**
 * 게이트웨이(Nginx+Keycloak/AD) 인증 인터셉터 — {@code /api/v1/**} 유일한 인증 입구(자체 로그인 없음).
 *
 * <p>2단계 권한 모델:
 * <ol>
 *   <li>게이트웨이 토큰({@code X-Access-Token}) 검증 + 게이트웨이 role 허용목록(들어올 자격) — 실패 401/403</li>
 *   <li>사번(preferred_username) → HR_DEVELOPER 조회로 roleCd/deptCd/partCd 도출(세부 권한).
 *       HR 미등록 사번은 403(fail-closed)</li>
 * </ol>
 * 이후 {@link Auth} 애노테이션 기반 세부 권한 검사와 {@link AuthContext} 세팅으로 이어진다.
 */
public class GatewayAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthInterceptor.class);

    /**
     * springdoc 문서 경로 접두어 — 인증 + HR roleCd=ADMIN 전용(보안팀 지적 대응).
     * {@code /v3/api-docs}(+{@code /**}, {@code .yaml}, {@code /swagger-config})와
     * {@code /swagger-ui/**}(+{@code /swagger-ui.html} 진입점)를 접두어 매칭으로 전부 커버한다.
     *
     * <p>주의: springdoc 경로는 {@code springdoc.api-docs.path} / {@code springdoc.swagger-ui.path}
     * 설정으로 바뀔 수 있다(현 프로젝트는 기본 경로 사용). 설정을 바꾸면 이 목록과
     * {@code WebConfig.addInterceptors}의 등록 패턴을 함께 수정해야 한다.
     */
    private static final List<String> DOC_PATH_PREFIXES = List.of("/v3/api-docs", "/swagger-ui");

    /** HR_DEVELOPER.ROLE_CD 관리자 코드(V019 EMP_ROLE) — 문서 경로 접근 허용 역할 */
    private static final String ADMIN_ROLE_CD = "ADMIN";

    private final GatewayAuthProperties props;
    private final GatewayTokenVerifier verifier;
    private final DeveloperMapper developers;

    public GatewayAuthInterceptor(GatewayAuthProperties props,
                                  GatewayTokenVerifier verifier,
                                  DeveloperMapper developers) {
        this.props = props;
        this.verifier = verifier;
        this.developers = developers;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        // 문서 경로는 정적 리소스(non-HandlerMethod)로도 서빙되므로 통과 분기보다 먼저 검사한다.
        if (isDocsPath(req)) {
            requireAdminForDocs(req);
            return true;
        }
        if (!(handler instanceof HandlerMethod hm)) return true;

        Developer dev = resolveDeveloper(req);

        Auth auth = hm.getMethodAnnotation(Auth.class);
        if (auth == null) auth = hm.getBeanType().getAnnotation(Auth.class);
        if (auth != null && auth.roles().length > 0
                && Arrays.stream(auth.roles()).noneMatch(r -> r.equals(dev.roleCd()))) {
            log.debug("게이트웨이 인증 거부 — @Auth 역할 불충족: empno={}, roleCd={}, 필요={}",
                dev.empno(), dev.roleCd(), Arrays.toString(auth.roles()));
            throw new ForbiddenException("권한이 없습니다");
        }

        AuthContext.set(dev.empno(), dev.roleCd(), dev.deptCd(), dev.partCd());
        return true;
    }

    /**
     * springdoc 문서 경로 여부(접두어 매칭 — {@link #DOC_PATH_PREFIXES} 주석 참고).
     *
     * <p>운영은 {@code SERVER_CONTEXT_PATH}(예: /dml-grant)가 붙을 수 있으므로
     * requestURI에서 contextPath를 벗겨 <b>context-relative 경로</b>로 판별한다 —
     * WebConfig의 addPathPatterns 매칭(context-relative)과 소스를 일치시켜,
     * context-path 배포에서 접두어 불일치로 문서 경로가 무인증 개방되는 것을 막는다.
     */
    private boolean isDocsPath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        if (uri == null) return false;
        String ctx = req.getContextPath();
        String path = (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx))
                ? uri.substring(ctx.length()) : uri;
        return DOC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /**
     * 문서 경로 접근 제어: 인증(401) → HR 조회(미등록 403) → roleCd=ADMIN 검사(403).
     * dev 우회(enabled=false)에서도 HR ADMIN 검사는 동일하게 적용된다(fail-closed).
     */
    private void requireAdminForDocs(HttpServletRequest req) {
        Developer dev = resolveDeveloper(req);
        if (!ADMIN_ROLE_CD.equals(dev.roleCd())) {
            log.debug("문서 경로 접근 거부 — ADMIN 아님: empno={}, roleCd={}, uri={}",
                dev.empno(), dev.roleCd(), req.getRequestURI());
            throw new ForbiddenException("권한이 없습니다");
        }
        AuthContext.set(dev.empno(), dev.roleCd(), dev.deptCd(), dev.partCd());
    }

    /** 토큰 검증(401) → 게이트웨이 role 검사(403) → HR_DEVELOPER 조회(미등록 403 fail-closed). */
    private Developer resolveDeveloper(HttpServletRequest req) {
        String empno = props.enabled() ? authenticate(req) : devBypassEmpno(req);

        Developer dev = developers.findByEmpno(empno);
        if (dev == null) {
            // fail-closed: 게이트웨이는 통과했어도 HR_DEVELOPER에 없는 사번은 거부
            log.debug("게이트웨이 인증 거부 — HR_DEVELOPER 미등록 사번: {}", empno);
            throw new ForbiddenException("권한이 없습니다");
        }
        return dev;
    }

    /** 게이트웨이 토큰 검증(401) + 게이트웨이 role 허용목록 검사(403) 후 사번을 반환한다. */
    private String authenticate(HttpServletRequest req) {
        String token = req.getHeader(props.tokenHeader());
        if (token == null || token.isBlank()) {
            log.debug("게이트웨이 인증 거부 — {} 헤더 없음: {}", props.tokenHeader(), req.getRequestURI());
            throw new UnauthorizedException("인증 토큰이 필요합니다");
        }
        GatewayPrincipal principal = verifier.verify(token.trim());

        if (principal.gatewayRoles().stream().noneMatch(props.allowedRoles()::contains)) {
            log.debug("게이트웨이 인증 거부 — 허용되지 않은 게이트웨이 role: empno={}, roles={}, 허용목록={}",
                principal.empno(), principal.gatewayRoles(), props.allowedRoles());
            throw new ForbiddenException("권한이 없습니다");
        }
        return principal.empno();
    }

    /**
     * enabled=false: 토큰 검증 없이 헤더 값(사번)만 신뢰하는 dev 우회.
     * local 전용 — 게이트웨이 없이 실행할 때만 쓴다. **운영 절대 금지(운영은 application-prod.yml에서 enabled=true 고정 — env로 끌 수 없음).**
     * HR 조회 → AuthContext 세팅 → @Auth 검사는 정상 경로와 동일하게 적용된다(fail-closed 유지).
     */
    private String devBypassEmpno(HttpServletRequest req) {
        String empno = req.getHeader(props.tokenHeader());
        if (empno == null || empno.isBlank()) {
            log.debug("dev 우회 인증 거부 — {} 헤더(사번) 없음: {}", props.tokenHeader(), req.getRequestURI());
            throw new UnauthorizedException("인증 토큰이 필요합니다");
        }
        log.warn("게이트웨이 인증 우회(enabled=false) — 헤더 사번 신뢰: {} (local 전용, 운영 금지)", empno.trim());
        return empno.trim();
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
