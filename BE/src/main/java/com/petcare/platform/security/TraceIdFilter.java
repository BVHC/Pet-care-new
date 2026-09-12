package com.petcare.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gắn traceId vào MDC cho mỗi request (đọc header X-Trace-Id nếu client gửi sẵn,
 * else generate mới) — GlobalExceptionHandler và RestAuthenticationEntryPoint/
 * RestAccessDeniedHandler đều đọc MDC.get("traceId") để đưa vào ErrorResponse.
 * Trước khi có filter này, MDC.get("traceId") luôn null.
 * Đăng ký trước JwtAuthenticationFilter (xem SecurityConfig) để traceId có sẵn
 * kể cả khi request bị 401 ngay ở tầng auth.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
