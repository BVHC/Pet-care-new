package com.petcare.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * docs/adr/0003: token hợp lệ + phiên hợp lệ → principal; mọi trường hợp khác → ẩn danh; lỗi hạ tầng → resolver.
 * docs/adr/0005: request khớp matcher path public → ẩn danh, không đọc token.
 */
class JwtAuthenticationFilterTest {

    record Principal(Long accountId, String email, Long sessionId, String role, Long branchId,
            AccessScope accessScope, boolean mustChangePassword) implements SecurityPrincipal {
    }

    private static final TokenClaims CLAIMS = new TokenClaims(5L, 9L, "jti");
    private static final Principal VET = new Principal(5L, "vet@petcare.test", 9L, "VET", 3L, AccessScope.BRANCH,
            false);
    /** Matcher path public của test; request mẫu {@code /api/test} không khớp. */
    private static final RequestMatcher PUBLIC = request -> request.getRequestURI().startsWith("/api/public/");

    private final JwtTokenService tokens = mock(JwtTokenService.class);
    private final SessionAuthenticator authenticator = mock(SessionAuthenticator.class);
    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokens, authenticator, resolver,
            PUBLIC);

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        request = new MockHttpServletRequest("GET", "/api/test");
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthorizationHeaderStaysAnonymous() throws Exception {
        filter.doFilter(request, response, chain);

        assertAnonymousAndContinued();
        verifyNoInteractions(tokens, authenticator);
    }

    @Test
    void emptyBearerStaysAnonymous() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer   ");

        filter.doFilter(request, response, chain);

        assertAnonymousAndContinued();
        verifyNoInteractions(tokens);
    }

    @Test
    void otherSchemeStaysAnonymous() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, chain);

        assertAnonymousAndContinued();
        verifyNoInteractions(tokens);
    }

    @Test
    void invalidTokenStaysAnonymousWithoutSessionLookup() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer bad");
        when(tokens.parse("bad")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertAnonymousAndContinued();
        verify(authenticator, never()).authenticate(any());
    }

    @Test
    void invalidSessionStaysAnonymous() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer good");
        when(tokens.parse("good")).thenReturn(Optional.of(CLAIMS));
        when(authenticator.authenticate(CLAIMS)).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertAnonymousAndContinued();
    }

    @Test
    void validSessionSetsPrincipalAndRoleAuthority() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "bearer good");
        when(tokens.parse("good")).thenReturn(Optional.of(CLAIMS));
        when(authenticator.authenticate(CLAIMS)).thenReturn(Optional.of(VET));
        Authentication[] seen = new Authentication[1];
        chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                    jakarta.servlet.http.HttpServletResponse res) {
                seen[0] = SecurityContextHolder.getContext().getAuthentication();
            }
        });

        filter.doFilter(request, response, chain);

        assertThat(seen[0]).isNotNull();
        assertThat(seen[0].isAuthenticated()).isTrue();
        assertThat(seen[0].getPrincipal()).isSameAs(VET);
        assertThat(seen[0].getCredentials()).isNull();
        assertThat(seen[0].getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_VET");
    }

    @Test
    void publicPathIgnoresTokenAndStaysAnonymous() throws Exception {
        request = new MockHttpServletRequest("POST", "/api/public/test");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer good");
        when(tokens.parse("good")).thenReturn(Optional.of(CLAIMS));
        when(authenticator.authenticate(CLAIMS)).thenReturn(Optional.of(VET));

        filter.doFilter(request, response, chain);

        assertAnonymousAndContinued();
        verifyNoInteractions(tokens, authenticator);
    }

    @Test
    void infrastructureFailureGoesToExceptionResolverAndStopsChain() throws Exception {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer good");
        when(tokens.parse(anyString())).thenReturn(Optional.of(CLAIMS));
        RuntimeException dbDown = new IllegalStateException("db down");
        when(authenticator.authenticate(CLAIMS)).thenThrow(dbDown);

        filter.doFilter(request, response, chain);

        verify(resolver).resolveException(same(request), same(response), eq(null), same(dbDown));
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void assertAnonymousAndContinued() {
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(resolver);
    }
}
