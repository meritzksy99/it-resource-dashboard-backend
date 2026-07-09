package com.meritz.dash.auth;

import com.meritz.dash.config.JwtProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * pwdReset=true 토큰은 본인 계정 경로(/auth/password, /auth/me)만 허용하고
 * 나머지 업무 API는 서버에서 강제 차단(403 PASSWORD_RESET_REQUIRED)한다.
 */
class JwtAuthInterceptorTest {

    private final JwtService jwt = new JwtService(
        new JwtProperties("0123456789abcdef0123456789abcdef", 86400000L));
    private final JwtAuthInterceptor interceptor = new JwtAuthInterceptor(jwt);

    @AfterEach
    void clearContext() { AuthContext.clear(); }

    /** HandlerMethod 용 더미 컨트롤러 (역할 제한 없는 업무 엔드포인트) */
    static class DummyController {
        public void endpoint() {}
    }

    private HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(new DummyController(),
            DummyController.class.getMethod("endpoint"));
    }

    private MockHttpServletRequest request(String uri, String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }

    private String token(boolean pwdReset) {
        return jwt.generate("9692", "03", "일반직원", "홍길동", "2139", "P02", pwdReset);
    }

    @Test
    @DisplayName("pwdReset=true 토큰으로 업무 API → 403 PASSWORD_RESET_REQUIRED")
    void pwdReset_token_blocked_on_business_endpoint() throws Exception {
        MockHttpServletRequest req = request("/api/v1/developers", token(true));

        assertThatThrownBy(() ->
                interceptor.preHandle(req, new MockHttpServletResponse(), handler()))
            .isInstanceOf(AuthPolicyException.class)
            .satisfies(ex -> {
                AuthPolicyException ape = (AuthPolicyException) ex;
                assertThat(ape.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ape.errorCode()).isEqualTo("PASSWORD_RESET_REQUIRED");
            });
    }

    @Test
    @DisplayName("pwdReset=true 토큰으로 /api/v2/auth/password → 통과")
    void pwdReset_token_allowed_on_password_change() throws Exception {
        MockHttpServletRequest req = request("/api/v2/auth/password", token(true));

        assertThatCode(() ->
                interceptor.preHandle(req, new MockHttpServletResponse(), handler()))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("pwdReset=true 토큰으로 /api/v1/auth/me → 통과")
    void pwdReset_token_allowed_on_me() throws Exception {
        MockHttpServletRequest req = request("/api/v1/auth/me", token(true));

        assertThatCode(() ->
                interceptor.preHandle(req, new MockHttpServletResponse(), handler()))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("pwdReset=false 토큰으로 업무 API → 정상 통과(회귀)")
    void normal_token_unaffected() throws Exception {
        MockHttpServletRequest req = request("/api/v1/developers", token(false));

        boolean result = interceptor.preHandle(req, new MockHttpServletResponse(), handler());

        assertThat(result).isTrue();
        assertThat(AuthContext.empno()).isEqualTo("9692");
    }
}
