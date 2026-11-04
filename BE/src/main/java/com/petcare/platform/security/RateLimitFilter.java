package com.petcare.platform.security;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.petcare.platform.exception.TooManyRequestsException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Giới hạn số request theo IP cho endpoint public của identity (docs/adr/0100, nợ D011): đăng nhập, đăng ký, quên /
 * đặt lại mật khẩu. Mỗi nhóm một ngưỡng riêng. Chạy trước {@link JwtAuthenticationFilter} và controller, nên request
 * bị chặn không tới BCrypt, DB hay {@code audit_logs}. Chỉ đếm {@code POST}; {@code OPTIONS} (preflight), {@code GET}
 * và path khác đi thẳng.
 *
 * <p>IP là {@code request.getRemoteAddr()}: sau {@code server.forward-headers-strategy=native} đã là IP client thật
 * (docs/adr/0002), không tự parse header. IPv6 gom theo /64 để một máy không né giới hạn bằng cách đổi đuôi địa chỉ.
 * Lỗi được chuyển cho {@code GlobalExceptionHandler} qua {@link HandlerExceptionResolver} như
 * {@link JwtAuthenticationFilter}, nên có envelope chuẩn, {@code traceId} và {@code Retry-After}. Không đăng ký làm
 * bean (tránh Spring Boot gắn thành filter servlet thứ hai); {@link SecurityConfig} đặt nó sau {@code CorsFilter} để
 * response 429 vẫn mang header CORS.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    enum Group {
        LOGIN, REGISTER, PASSWORD
    }

    private static final RequestMatcher LOGIN = post("/api/auth/login");
    private static final RequestMatcher REGISTER = new OrRequestMatcher(
            post("/api/auth/register"), post("/api/auth/register/**"));
    private static final RequestMatcher PASSWORD = post("/api/auth/password/**");
    private static final RequestMatcher ANY = new OrRequestMatcher(LOGIN, REGISTER, PASSWORD);

    private final RateLimiter limiter;
    private final Map<Group, RateLimitProperties.Rule> rules;
    private final HandlerExceptionResolver resolver;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties, HandlerExceptionResolver resolver) {
        this.limiter = limiter;
        this.rules = Map.of(
                Group.LOGIN, properties.login(),
                Group.REGISTER, properties.register(),
                Group.PASSWORD, properties.password());
        this.resolver = resolver;
    }

    private static RequestMatcher post(String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, pattern);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ANY.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Group group = LOGIN.matches(request) ? Group.LOGIN : REGISTER.matches(request) ? Group.REGISTER : Group.PASSWORD;
        RateLimitProperties.Rule rule = rules.get(group);
        String key = "rl:" + group.name().toLowerCase() + ":" + clientKey(request.getRemoteAddr());
        RateLimiter.Decision decision = limiter.tryAcquire(key, rule.limit(), Duration.ofSeconds(rule.windowSeconds()));
        if (!decision.allowed()) {
            resolver.resolveException(request, response, null, new TooManyRequestsException(decision.retryAfterSeconds()));
            return;
        }
        chain.doFilter(request, response);
    }

    /** IPv4: nguyên địa chỉ. IPv6: 64 bit đầu. Không phân giải được thì dùng nguyên chuỗi. */
    static String clientKey(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "unknown";
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
            if (address instanceof Inet6Address) {
                return HexFormat.of().formatHex(address.getAddress(), 0, 8) + "/64";
            }
        } catch (IOException | RuntimeException ex) {
            // remoteAddr luôn là địa chỉ dạng chữ số nên không có DNS; lỗi thì rơi xuống dùng nguyên chuỗi.
        }
        return remoteAddr;
    }
}
