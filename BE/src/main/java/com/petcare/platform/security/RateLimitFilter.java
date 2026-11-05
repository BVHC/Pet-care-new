package com.petcare.platform.security;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
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
 * đặt lại mật khẩu; và hai endpoint hỏi đáp của chatbot AI (docs/adr/0110, BR-AI-09). Mỗi nhóm một ngưỡng riêng. Chạy
 * trước {@link JwtAuthenticationFilter} và controller, nên request bị chặn không tới BCrypt, DB, {@code audit_logs} hay
 * nhà cung cấp LLM. Chỉ đếm {@code POST}; {@code OPTIONS} (preflight), {@code GET} và path khác đi thẳng.
 *
 * <p>Nhóm AI có hai cửa sổ (phút rồi giờ) trên cùng một IP, kiểm theo thứ tự đó và dừng ở cửa sổ đầu tiên từ chối: request
 * bị chặn vì cửa sổ phút không tiêu hao hạn mức giờ. Ngược lại request qua cửa sổ phút rồi bị chặn vì giờ vẫn đã được tính
 * vào cửa sổ phút ({@link RateLimiter} tính cả lần bị từ chối).
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
        LOGIN, REGISTER, PASSWORD, AI
    }

    /** Một cửa sổ đếm: tên khóa Redis ({@code rl:<name>:<ip>}) và ngưỡng. */
    private record Window(String name, RateLimitProperties.Rule rule) {
    }

    private static final RequestMatcher LOGIN = post("/api/auth/login");
    private static final RequestMatcher REGISTER = new OrRequestMatcher(
            post("/api/auth/register"), post("/api/auth/register/**"));
    private static final RequestMatcher PASSWORD = post("/api/auth/password/**");
    private static final RequestMatcher AI = new OrRequestMatcher(
            post("/api/public/ai/chat"), post("/api/me/ai/chat"));
    private static final RequestMatcher ANY = new OrRequestMatcher(LOGIN, REGISTER, PASSWORD, AI);

    private final RateLimiter limiter;
    private final Map<Group, List<Window>> windows;
    private final HandlerExceptionResolver resolver;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties, HandlerExceptionResolver resolver) {
        this.limiter = limiter;
        this.windows = Map.of(
                Group.LOGIN, List.of(new Window("login", properties.login())),
                Group.REGISTER, List.of(new Window("register", properties.register())),
                Group.PASSWORD, List.of(new Window("password", properties.password())),
                Group.AI, List.of(new Window("ai", properties.ai()), new Window("ai-hour", properties.aiHour())));
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
        Group group = LOGIN.matches(request) ? Group.LOGIN
                : REGISTER.matches(request) ? Group.REGISTER
                : PASSWORD.matches(request) ? Group.PASSWORD : Group.AI;
        String client = clientKey(request.getRemoteAddr());
        for (Window window : windows.get(group)) {
            RateLimitProperties.Rule rule = window.rule();
            RateLimiter.Decision decision = limiter.tryAcquire("rl:" + window.name() + ":" + client, rule.limit(),
                    Duration.ofSeconds(rule.windowSeconds()));
            if (!decision.allowed()) {
                resolver.resolveException(request, response, null,
                        new TooManyRequestsException(decision.retryAfterSeconds()));
                return;
            }
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
