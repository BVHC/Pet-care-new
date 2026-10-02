package com.petcare.platform.config;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filter đầu tiên của mỗi request (docs/convention/backend/08-logging-and-audit.md §8.1): gắn traceId vào MDC
 * và header response. Chạy trước {@code springSecurityFilterChain} nên cả lỗi 401/403 cũng có traceId.
 * Chỉ nhận traceId client gửi khi đúng định dạng, tránh chèn ký tự lạ vào log.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TraceContext.HEADER);
        if (traceId == null || !VALID_TRACE_ID.matcher(traceId).matches()) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(TraceContext.MDC_KEY, traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }
}
