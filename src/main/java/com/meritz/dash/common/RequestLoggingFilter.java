package com.meritz.dash.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 운영 진단용 HTTP 요청/응답 로깅 필터.
 * /api/** 경로만 로깅하며 /swagger, /v3/api-docs 경로는 제외한다.
 * Authorization 헤더 값은 절대 로깅하지 않는다.
 * 필터 오류가 정상 응답을 방해하지 않도록 방어 처리한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final int RESPONSE_TRUNCATE_LENGTH = 500;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.startsWith("/swagger")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        ContentCachingRequestWrapper wrappedReq = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedRes = new ContentCachingResponseWrapper(response);

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(wrappedReq, wrappedRes);
        } finally {
            try {
                logRequest(wrappedReq, wrappedRes, start);
            } catch (Exception loggingEx) {
                log.debug("RequestLoggingFilter: 로깅 중 오류 발생(무시)", loggingEx);
            }
            // 반드시 응답 body를 실제 response에 복사 — 누락 시 클라이언트에 body 전달 안 됨
            try {
                wrappedRes.copyBodyToResponse();
            } catch (IOException copyEx) {
                log.warn("copyBodyToResponse 실패 — 클라이언트에 응답 body가 전달되지 않을 수 있음", copyEx);
            }
        }
    }

    private void logRequest(ContentCachingRequestWrapper req,
                            ContentCachingResponseWrapper res,
                            long start) {
        String method = req.getMethod();
        String uri = req.getRequestURI();
        String query = req.getQueryString();
        String queryPart = (query != null && !query.isBlank()) ? "?" + query : "";
        int status = res.getStatus();
        long elapsed = System.currentTimeMillis() - start;

        log.info("HTTP {} {}{} -> {} ({}ms)", method, uri, queryPart, status, elapsed);

        if (log.isDebugEnabled()) {
            byte[] reqBody = req.getContentAsByteArray();
            if (reqBody.length > 0) {
                // 이 앱의 요청/응답 body는 전부 JSON(UTF-8). 서블릿 기본 charset(ISO-8859-1)로
                // 디코딩하면 한글이 깨지므로 UTF-8로 고정한다.
                String bodyStr = new String(reqBody, StandardCharsets.UTF_8);
                String masked = LogMasker.maskJson(bodyStr);
                log.debug("  req body: {}", masked);
            }

            byte[] resBody = res.getContentAsByteArray();
            if (resBody.length > 0) {
                String resStr = new String(resBody, StandardCharsets.UTF_8);
                if (resStr.length() > RESPONSE_TRUNCATE_LENGTH) {
                    resStr = resStr.substring(0, RESPONSE_TRUNCATE_LENGTH) + "...(truncated)";
                }
                log.debug("  res body: {}", resStr);
            }
        }
    }
}
