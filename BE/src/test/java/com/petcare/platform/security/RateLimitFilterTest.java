package com.petcare.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.petcare.platform.exception.TooManyRequestsException;

/**
 * {@link RateLimitFilter} (docs/adr/0100): đúng nhóm path / method, khóa theo IP, chặn thì không tới chain và lỗi đi
 * qua {@code HandlerExceptionResolver}. Bộ đếm là giả; phần đếm thật nằm ở {@code RedisRateLimiterIT}.
 */
class RateLimitFilterTest {

    /** Ghi lại mọi lần hỏi để kiểm tra khóa / ngưỡng; trả quyết định đặt sẵn. */
    static final class RecordingLimiter implements RateLimiter {

        record Call(String key, int limit, Duration window) {
        }

        final List<Call> calls = new ArrayList<>();
        Decision next = Decision.ALLOWED;

        @Override
        public Decision tryAcquire(String key, int limit, Duration window) {
            calls.add(new Call(key, limit, window));
            return next;
        }
    }

    private final RecordingLimiter limiter = new RecordingLimiter();
    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final RateLimitProperties properties = new RateLimitProperties(true,
            new RateLimitProperties.Rule(30, 60), new RateLimitProperties.Rule(10, 45),
            new RateLimitProperties.Rule(7, 30), 10);
    private final RateLimitFilter filter = new RateLimitFilter(limiter, properties, resolver);

    private MockFilterChain run(String method, String uri, String remoteAddr) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(remoteAddr);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @ParameterizedTest
    @CsvSource({
            "/api/auth/login,                 login,    30, 60",
            "/api/auth/register,              register, 10, 45",
            "/api/auth/register/verify,       register, 10, 45",
            "/api/auth/register/resend-otp,   register, 10, 45",
            "/api/auth/password/forgot,       password, 7,  30",
            "/api/auth/password/reset,        password, 7,  30"})
    void countsEachPublicPostPathInItsOwnGroup(String uri, String group, int limit, int windowSeconds)
            throws Exception {
        MockFilterChain chain = run("POST", uri, "203.0.113.9");

        assertThat(limiter.calls).containsExactly(new RecordingLimiter.Call(
                "rl:" + group + ":203.0.113.9", limit, Duration.ofSeconds(windowSeconds)));
        assertThat(chain.getRequest()).as("cho qua khi dưới ngưỡng").isNotNull();
    }

    @ParameterizedTest
    @CsvSource({
            "GET,     /api/auth/login",
            "OPTIONS, /api/auth/login",
            "PUT,     /api/auth/login",
            "POST,    /api/auth/refresh",
            "POST,    /api/auth/logout",
            "POST,    /api/public/anything",
            "POST,    /api/visits",
            "GET,     /api/auth/password/forgot"})
    void doesNotCountOtherMethodsOrPaths(String method, String uri) throws Exception {
        MockFilterChain chain = run(method, uri, "203.0.113.9");

        assertThat(limiter.calls).isEmpty();
        assertThat(chain.getRequest()).isNotNull();
        verify(resolver, never()).resolveException(any(), any(), any(), any());
    }

    @Test
    void deniedRequestNeverReachesTheChainAndGoesThroughTheResolver() throws Exception {
        limiter.next = RateLimiter.Decision.denied(42);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("203.0.113.9");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("chain không được gọi").isNull();
        ArgumentCaptor<Exception> error = ArgumentCaptor.forClass(Exception.class);
        verify(resolver).resolveException(any(), any(), isNull(), error.capture());
        assertThat(error.getValue()).isInstanceOf(TooManyRequestsException.class);
        assertThat(((TooManyRequestsException) error.getValue()).retryAfterSeconds()).isEqualTo(42);
    }

    @Test
    void differentIpsUseDifferentKeysAndGroupsAreIndependent() throws Exception {
        run("POST", "/api/auth/login", "198.51.100.1");
        run("POST", "/api/auth/login", "198.51.100.2");
        run("POST", "/api/auth/password/forgot", "198.51.100.1");

        assertThat(limiter.calls).extracting(RecordingLimiter.Call::key)
                .containsExactly("rl:login:198.51.100.1", "rl:login:198.51.100.2", "rl:password:198.51.100.1");
    }

    @Test
    void ipv6AddressesOfTheSame64BitPrefixShareOneKey() {
        assertThat(RateLimitFilter.clientKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
                .isEqualTo(RateLimitFilter.clientKey("2001:0db8:0001:0002:1:2:3:4"))
                .isEqualTo("20010db800010002/64");
        assertThat(RateLimitFilter.clientKey("2001:db8:1:3::1")).isNotEqualTo(RateLimitFilter.clientKey("2001:db8:1:2::1"));
    }

    @Test
    void ipv4MappedIpv6IsKeyedAsIpv4() {
        assertThat(RateLimitFilter.clientKey("::ffff:203.0.113.9")).isEqualTo("203.0.113.9");
    }

    @Test
    void missingRemoteAddressStillGetsAKey() {
        assertThat(RateLimitFilter.clientKey(null)).isEqualTo("unknown");
        assertThat(RateLimitFilter.clientKey(" ")).isEqualTo("unknown");
    }
}
