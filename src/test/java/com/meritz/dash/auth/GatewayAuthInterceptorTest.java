package com.meritz.dash.auth;

import com.meritz.dash.config.GatewayAuthProperties;
import com.meritz.dash.developer.Developer;
import com.meritz.dash.mapper.app.DeveloperMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 게이트웨이(X-Access-Token) 인증 인터셉터:
 * 토큰 검증(401) → 게이트웨이 role 허용목록(403) → HR 조회 fail-closed(403)
 * → AuthContext 세팅 → 기존 @Auth 세부권한 검사(403).
 */
class GatewayAuthInterceptorTest {

    private static final String HEADER = "X-Access-Token";

    private final GatewayTokenVerifier verifier = mock(GatewayTokenVerifier.class);
    private final DeveloperMapper developers = mock(DeveloperMapper.class);
    private final GatewayAuthInterceptor interceptor =
        new GatewayAuthInterceptor(props(true), verifier, developers);

    private static GatewayAuthProperties props(boolean enabled) {
        return new GatewayAuthProperties(enabled, HEADER,
            "http://localhost/jwks", "http://localhost/realms/meritz-internal",
            "oauth2-proxy", Set.of("dev-user"));
    }

    @AfterEach
    void clearContext() { AuthContext.clear(); }

    /** 역할 제한 없는 업무 엔드포인트 + @Auth(roles) 제한 엔드포인트 더미 */
    static class DummyController {
        public void endpoint() {}
        @Auth(roles = {"01"})
        public void leaderOnly() {}
    }

    private HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(new DummyController(),
            DummyController.class.getMethod("endpoint"));
    }

    private HandlerMethod leaderOnlyHandler() throws NoSuchMethodException {
        return new HandlerMethod(new DummyController(),
            DummyController.class.getMethod("leaderOnly"));
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/developers");
        if (token != null) req.addHeader(HEADER, token);
        return req;
    }

    private static Developer hrRow(String roleCd) {
        return new Developer("9692", "홍길동", "D101", "P02", "대리", roleCd, "Y", "01");
    }

    @Test
    @DisplayName("유효 토큰(dev-user) + HR 사번 존재 → 통과, AuthContext에 roleCd/dept/part 채워짐")
    void valid_token_and_hr_row_pass() throws Exception {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of("dev-user")));
        when(developers.findByEmpno("9692")).thenReturn(hrRow("03"));

        boolean result = interceptor.preHandle(request("tok"), new MockHttpServletResponse(), handler());

        assertThat(result).isTrue();
        assertThat(AuthContext.empno()).isEqualTo("9692");
        assertThat(AuthContext.role()).isEqualTo("03");
        assertThat(AuthContext.deptCd()).isEqualTo("D101");
        assertThat(AuthContext.partCd()).isEqualTo("P02");
    }

    @Test
    @DisplayName("@Auth(roles={\"01\"}) 엔드포인트 + HR roleCd=01 → 통과")
    void auth_annotation_satisfied_pass() throws Exception {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of("dev-user")));
        when(developers.findByEmpno("9692")).thenReturn(hrRow("01"));

        boolean result = interceptor.preHandle(
            request("tok"), new MockHttpServletResponse(), leaderOnlyHandler());

        assertThat(result).isTrue();
        assertThat(AuthContext.role()).isEqualTo("01");
    }

    @Test
    @DisplayName("토큰 헤더 없음 → 401 UnauthorizedException (verifier 호출 안 함)")
    void missing_header_401() throws Exception {
        HandlerMethod hm = handler();

        assertThatThrownBy(() -> interceptor.preHandle(request(null), new MockHttpServletResponse(), hm))
            .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(verifier);
    }

    @Test
    @DisplayName("토큰 헤더 blank → 401 UnauthorizedException (verifier 호출 안 함)")
    void blank_header_401() throws Exception {
        HandlerMethod hm = handler();

        assertThatThrownBy(() -> interceptor.preHandle(request("  "), new MockHttpServletResponse(), hm))
            .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(verifier);
    }

    @Test
    @DisplayName("토큰 무효(verifier 실패) → 401 그대로 전파, AuthContext 비어 있음")
    void invalid_token_401() throws Exception {
        when(verifier.verify("bad")).thenThrow(new UnauthorizedException("게이트웨이 토큰 검증 실패"));
        HandlerMethod hm = handler();

        assertThatThrownBy(() -> interceptor.preHandle(request("bad"), new MockHttpServletResponse(), hm))
            .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(AuthContext::empno).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("게이트웨이 role이 허용목록에 없음(infra-user) → 403, HR 조회 안 함")
    void gateway_role_not_allowed_403() throws Exception {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of("infra-user")));
        HandlerMethod hm = handler();

        assertThatThrownBy(() -> interceptor.preHandle(request("tok"), new MockHttpServletResponse(), hm))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(developers);
    }

    @Test
    @DisplayName("게이트웨이 role 없음(빈 Set) → 403")
    void gateway_roles_empty_403() throws Exception {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of()));
        HandlerMethod hm = handler();

        assertThatThrownBy(() -> interceptor.preHandle(request("tok"), new MockHttpServletResponse(), hm))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("HR(HR_DEVELOPER)에 사번 없음 → 403 fail-closed")
    void hr_row_missing_403() throws Exception {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of("dev-user")));
        when(developers.findByEmpno("9692")).thenReturn(null);
        HandlerMethod hm = handler();

        assertThatThrownBy(() -> interceptor.preHandle(request("tok"), new MockHttpServletResponse(), hm))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("@Auth(roles={\"01\"}) 엔드포인트 + HR roleCd=03 → 403, AuthContext 비어 있음")
    void auth_annotation_unsatisfied_403() throws Exception {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of("dev-user")));
        when(developers.findByEmpno("9692")).thenReturn(hrRow("03"));
        HandlerMethod hm = leaderOnlyHandler();

        assertThatThrownBy(() -> interceptor.preHandle(request("tok"), new MockHttpServletResponse(), hm))
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(AuthContext::empno).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("afterCompletion → AuthContext clear")
    void after_completion_clears_context() throws Exception {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of("dev-user")));
        when(developers.findByEmpno("9692")).thenReturn(hrRow("03"));
        MockHttpServletRequest req = request("tok");
        interceptor.preHandle(req, new MockHttpServletResponse(), handler());

        interceptor.afterCompletion(req, new MockHttpServletResponse(), handler(), null);

        assertThatThrownBy(AuthContext::empno).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("HandlerMethod가 아니면(정적 리소스 등) 그냥 통과")
    void non_handler_method_passthrough() throws Exception {
        boolean result = interceptor.preHandle(
            request(null), new MockHttpServletResponse(), new Object());

        assertThat(result).isTrue();
        verifyNoInteractions(verifier, developers);
    }

    // ── springdoc 문서 경로(/v3/api-docs, /swagger-ui) — ADMIN 전용 ──────

    private MockHttpServletRequest docsRequest(String uri, String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        if (token != null) req.addHeader(HEADER, token);
        return req;
    }

    @Test
    @DisplayName("문서 경로 + 토큰 없음 → 401 (non-HandlerMethod 통과 예외에서 제외됨)")
    void docs_path_no_token_401() {
        // springdoc 문서는 HandlerMethod가 아닌 핸들러(정적 리소스)로도 서빙된다 — 그래도 401
        assertThatThrownBy(() -> interceptor.preHandle(
                docsRequest("/v3/api-docs", null), new MockHttpServletResponse(), new Object()))
            .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(verifier, developers);
    }

    @Test
    @DisplayName("문서 경로 변형(api-docs.yaml, swagger-config, swagger-ui/*, swagger-ui.html)도 전부 401")
    void docs_path_variants_no_token_401() {
        for (String uri : new String[]{
                "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
                "/swagger-ui/index.html", "/swagger-ui.html", "/swagger-ui"}) {
            assertThatThrownBy(() -> interceptor.preHandle(
                    docsRequest(uri, null), new MockHttpServletResponse(), new Object()))
                .as("무토큰 %s → 401", uri)
                .isInstanceOf(UnauthorizedException.class);
        }
    }

    @Test
    @DisplayName("문서 경로 + 유효 토큰이지만 비ADMIN(팀장 01) → 403")
    void docs_path_non_admin_403() {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of("dev-user")));
        when(developers.findByEmpno("9692")).thenReturn(hrRow("01"));

        assertThatThrownBy(() -> interceptor.preHandle(
                docsRequest("/v3/api-docs", "tok"), new MockHttpServletResponse(), new Object()))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("문서 경로 + 유효 토큰이지만 HR 미등록 → 403 (fail-closed)")
    void docs_path_hr_missing_403() {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of("dev-user")));
        when(developers.findByEmpno("9692")).thenReturn(null);

        assertThatThrownBy(() -> interceptor.preHandle(
                docsRequest("/v3/api-docs", "tok"), new MockHttpServletResponse(), new Object()))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("문서 경로 + 유효 토큰 + HR roleCd=ADMIN → 통과, AuthContext 세팅")
    void docs_path_admin_pass() {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of("dev-user")));
        when(developers.findByEmpno("9692")).thenReturn(hrRow("ADMIN"));

        boolean result = interceptor.preHandle(
                docsRequest("/v3/api-docs", "tok"), new MockHttpServletResponse(), new Object());

        assertThat(result).isTrue();
        assertThat(AuthContext.role()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("문서 경로: enabled=false(dev 우회)여도 HR ADMIN 검사는 동일 적용")
    void docs_path_dev_bypass_still_requires_admin() {
        GatewayAuthInterceptor local = new GatewayAuthInterceptor(props(false), verifier, developers);
        when(developers.findByEmpno("9692")).thenReturn(hrRow("03"));
        when(developers.findByEmpno("9000")).thenReturn(hrRow("ADMIN"));

        assertThatThrownBy(() -> local.preHandle(
                docsRequest("/v3/api-docs", "9692"), new MockHttpServletResponse(), new Object()))
            .isInstanceOf(ForbiddenException.class);
        assertThat(local.preHandle(
                docsRequest("/v3/api-docs", "9000"), new MockHttpServletResponse(), new Object()))
            .isTrue();
        verifyNoInteractions(verifier);
    }

    // ── 문서 경로 × context-path (운영 SERVER_CONTEXT_PATH 배포 대비) ────

    private MockHttpServletRequest ctxDocsRequest(String contextPath, String pathWithinApp, String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", contextPath + pathWithinApp);
        req.setContextPath(contextPath);
        req.setRequestURI(contextPath + pathWithinApp);
        if (token != null) req.addHeader(HEADER, token);
        return req;
    }

    @Test
    @DisplayName("context-path(/dml-grant) 하 문서 경로 + 무토큰 → 401 (requestURI 접두어 불일치로 개방되면 안 됨)")
    void docs_path_with_context_path_no_token_401() {
        for (String path : new String[]{"/swagger-ui/index.html", "/v3/api-docs"}) {
            assertThatThrownBy(() -> interceptor.preHandle(
                    ctxDocsRequest("/dml-grant", path, null),
                    new MockHttpServletResponse(), new Object()))
                .as("context-path 하 무토큰 %s → 401", path)
                .isInstanceOf(UnauthorizedException.class);
        }
        verifyNoInteractions(verifier, developers);
    }

    @Test
    @DisplayName("context-path 하 문서 경로 + 유효 토큰 비ADMIN → 403")
    void docs_path_with_context_path_non_admin_403() {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of("dev-user")));
        when(developers.findByEmpno("9692")).thenReturn(hrRow("03"));

        assertThatThrownBy(() -> interceptor.preHandle(
                ctxDocsRequest("/dml-grant", "/v3/api-docs", "tok"),
                new MockHttpServletResponse(), new Object()))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("context-path 하 문서 경로 + ADMIN → 통과")
    void docs_path_with_context_path_admin_pass() {
        when(verifier.verify("tok")).thenReturn(new GatewayPrincipal("9692", Set.of("dev-user")));
        when(developers.findByEmpno("9692")).thenReturn(hrRow("ADMIN"));

        assertThat(interceptor.preHandle(
                ctxDocsRequest("/dml-grant", "/swagger-ui/index.html", "tok"),
                new MockHttpServletResponse(), new Object()))
            .isTrue();
        assertThat(AuthContext.role()).isEqualTo("ADMIN");
    }

    // ── enabled=false (local 전용 dev 우회 — 운영 절대 금지) ──────────────

    @Test
    @DisplayName("enabled=false: 헤더의 사번만 신뢰(토큰 검증 생략), HR 조회·AuthContext 세팅은 동일")
    void disabled_bypass_trusts_empno_header() throws Exception {
        GatewayAuthInterceptor local = new GatewayAuthInterceptor(props(false), verifier, developers);
        when(developers.findByEmpno("9692")).thenReturn(hrRow("03"));

        boolean result = local.preHandle(request("9692"), new MockHttpServletResponse(), handler());

        assertThat(result).isTrue();
        assertThat(AuthContext.empno()).isEqualTo("9692");
        assertThat(AuthContext.role()).isEqualTo("03");
        verifyNoInteractions(verifier);
    }

    @Test
    @DisplayName("enabled=false: 헤더 없으면 401, HR 미존재면 403 (우회여도 fail-closed)")
    void disabled_bypass_still_fail_closed() throws Exception {
        GatewayAuthInterceptor local = new GatewayAuthInterceptor(props(false), verifier, developers);
        when(developers.findByEmpno("0000")).thenReturn(null);
        HandlerMethod hm = handler();

        assertThatThrownBy(() -> local.preHandle(request(null), new MockHttpServletResponse(), hm))
            .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> local.preHandle(request("0000"), new MockHttpServletResponse(), hm))
            .isInstanceOf(ForbiddenException.class);
    }
}
