package com.petcare.module.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.service.AccountPrincipal;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * BR-TK-17, identity-v1 A4, docs/adr/0003 D9, docs/adr/0005: chỉ cho qua 3 endpoint của A4. Path public không tới được
 * đây với principal (filter bỏ qua token, xem SecurityConfigTest và AuthenticationIT), nên interceptor không miễn.
 */
class MustChangePasswordInterceptorTest {

    private final MustChangePasswordInterceptor interceptor = new MustChangePasswordInterceptor();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({
            "GET,  /api/me",
            "POST, /api/me/password",
            "POST, /api/auth/logout"})
    void allowedEndpointsPassWhileMustChangePassword(String method, String path) throws Exception {
        login(true);

        assertThat(interceptor.preHandle(request(method, path), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "POST,  /api/me",
            "PATCH, /api/me/staff-profile",
            "GET,   /api/me/password",
            "GET,   /api/staff",
            "POST,  /api/visits",
            "GET,   /api/public",
            "GET,   /api/me/link-candidates",
            "GET,   /api/public/vets"})
    void otherEndpointsAreBlockedWithBrTk17(String method, String path) {
        login(true);

        assertThatThrownBy(() -> interceptor.preHandle(request(method, path), new MockHttpServletResponse(),
                new Object()))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, ex -> {
                    assertThat(ex.getRuleId()).isEqualTo("BR-TK-17");
                    assertThat(ex.getMessage()).endsWith("(BR-TK-17)");
                });
    }

    @Test
    void accountWithoutFlagPasses() throws Exception {
        login(false);

        assertThat(interceptor.preHandle(request("GET", "/api/staff"), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    void anonymousRequestPasses() throws Exception {
        assertThat(interceptor.preHandle(request("GET", "/api/staff"), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    void contextPathIsIgnored() throws Exception {
        login(true);
        MockHttpServletRequest request = request("GET", "/app/api/me");
        request.setContextPath("/app");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private static void login(boolean mustChangePassword) {
        AccountPrincipal principal = new AccountPrincipal(1L, "staff@petcare.test", 2L, Role.RECEPTIONIST, 3L,
                mustChangePassword);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_RECEPTIONIST")));
    }
}
