package com.petcare.platform.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Đọc {@code Authorization: Bearer <jwt>}, kiểm tra chữ ký rồi kiểm tra phiên trong DB (docs/adr/0003). Hợp lệ thì
 * đặt {@link SecurityPrincipal} vào {@code SecurityContext}; không có hoặc không hợp lệ thì để request ẩn danh, quy tắc
 * {@code authorizeHttpRequests} quyết định 401 (thông điệp chung, không nói lý do). Lỗi hạ tầng khi kiểm tra phiên
 * (DB...) được chuyển cho {@code GlobalExceptionHandler} qua {@link HandlerExceptionResolver}, như
 * {@link RestAuthenticationEntryPoint}. Không đăng ký làm bean để Spring Boot không gắn nó thành filter servlet thứ hai.
 * Request khớp {@code anonymousOnly} (path public) không bị đọc token: luôn ẩn danh, kể cả khi client gửi kèm token
 * còn hạn (docs/adr/0005).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtTokenService tokens;
    private final SessionAuthenticator authenticator;
    private final HandlerExceptionResolver resolver;
    private final RequestMatcher anonymousOnly;

    public JwtAuthenticationFilter(JwtTokenService tokens, SessionAuthenticator authenticator,
            HandlerExceptionResolver resolver, RequestMatcher anonymousOnly) {
        this.tokens = tokens;
        this.authenticator = authenticator;
        this.resolver = resolver;
        this.anonymousOnly = anonymousOnly;
    }

    /** Path public: không parse token, không tra phiên (docs/adr/0005). */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return anonymousOnly.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = bearerToken(request);
        if (token != null) {
            try {
                Optional<SecurityPrincipal> principal = tokens.parse(token).flatMap(authenticator::authenticate);
                principal.ifPresent(p -> authenticate(p, request));
            } catch (RuntimeException ex) {
                SecurityContextHolder.clearContext();
                resolver.resolveException(request, response, null, ex);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static void authenticate(SecurityPrincipal principal, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
