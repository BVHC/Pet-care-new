package com.petcare.module.identity.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.SecurityPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * BR-TK-17: tài khoản còn phải đổi mật khẩu lần đầu bị chặn mọi chức năng khác (identity-v1 A4, docs/adr/0003).
 * Chỉ cho qua {@code GET /api/me}, {@code POST /api/me/password}, {@code POST /api/auth/logout}. Path public
 * (đăng nhập, quên mật khẩu, đăng ký, {@code /api/public/**}) không cần miễn ở đây vì không bao giờ có principal:
 * {@code JwtAuthenticationFilter} bỏ qua token ở các path đó (docs/adr/0005). Ném exception để lỗi đi qua
 * {@code GlobalExceptionHandler}.
 */
@Component
public class MustChangePasswordInterceptor implements HandlerInterceptor {

    static final String MESSAGE = "Bạn cần đổi mật khẩu trước khi sử dụng chức năng khác";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SecurityPrincipal principal
                && principal.mustChangePassword() && !isAllowed(request)) {
            throw new BusinessRuleViolationException("BR-TK-17", MESSAGE);
        }
        return true;
    }

    static boolean isAllowed(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        return ("GET".equals(method) && "/api/me".equals(path))
                || ("POST".equals(method) && "/api/me/password".equals(path))
                || ("POST".equals(method) && "/api/auth/logout".equals(path));
    }
}
