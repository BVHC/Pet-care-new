package com.petcare.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.petcare.platform.exception.AccessDeniedScopeException;

/**
 * docs/adr/0003 D8, D15, docs/adr/0006; docs/api/00-method.md §3.3; 04 nguyên tắc 8; 01 A02; BR-QT-03, BR-DG-03,
 * BR-BC-01. {@code accessScope} ở đây là giá trị principal khai báo; ánh xạ role → phạm vi kiểm tra ở AccountPrincipalTest.
 */
class BranchScopeTest {

    record Principal(Long accountId, String email, Long sessionId, String role, Long branchId,
            AccessScope accessScope, boolean mustChangePassword) implements SecurityPrincipal {
    }

    private static final long OWN = 10L;
    private static final long OTHER = 20L;

    private final BranchScope scope = new BranchScope();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- A05–A08

    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER"})
    void branchStaffResolveNullToOwnBranch(String role) {
        login(role, OWN, AccessScope.BRANCH);

        assertThat(scope.resolve(null)).isEqualTo(OWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER"})
    void branchStaffResolveOwnBranch(String role) {
        login(role, OWN, AccessScope.BRANCH);

        assertThat(scope.resolve(OWN)).isEqualTo(OWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER"})
    void branchStaffResolveOtherBranchIs403(String role) {
        login(role, OWN, AccessScope.BRANCH);

        assertThatThrownBy(() -> scope.resolve(OTHER)).isInstanceOf(AccessDeniedScopeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER"})
    void branchStaffCheckOwnBranchPasses(String role) {
        login(role, OWN, AccessScope.BRANCH);

        assertThatCode(() -> scope.check(OWN)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER"})
    void branchStaffCheckOtherBranchIs403(String role) {
        login(role, OWN, AccessScope.BRANCH);

        assertThatThrownBy(() -> scope.check(OTHER)).isInstanceOf(AccessDeniedScopeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BRANCH_MANAGER", "RECEPTIONIST", "VET", "CARETAKER"})
    void branchStaffCheckRecordWithoutBranchIs403(String role) {
        login(role, OWN, AccessScope.BRANCH);

        // BR-DG-03: feedback không gắn chi nhánh chỉ SUPER_MANAGER xem
        assertThatThrownBy(() -> scope.check(null)).isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void branchStaffWithoutBranchIsDataErrorNotChainWide() {
        login("VET", null, AccessScope.BRANCH);

        assertThatThrownBy(() -> scope.resolve(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.check(OWN)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scopeExceptionCarriesGenericClientMessage() {
        login("VET", OWN, AccessScope.BRANCH);

        assertThatThrownBy(() -> scope.resolve(OTHER))
                .isInstanceOfSatisfying(AccessDeniedScopeException.class, ex -> {
                    assertThat(ex.clientMessage()).isEqualTo("Không có quyền truy cập dữ liệu này");
                    assertThat(ex.getRequiredScope()).isEqualTo("branch:" + OTHER);
                    assertThat(ex.getActualScope()).isEqualTo("branch:" + OWN);
                });
    }

    // ---------------------------------------------------------------- ADMIN, SUPER_MANAGER

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_MANAGER"})
    void chainResolveKeepsRequestedBranch(String role) {
        login(role, null, AccessScope.CHAIN);

        assertThat(scope.resolve(null)).isNull();
        assertThat(scope.resolve(OTHER)).isEqualTo(OTHER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_MANAGER"})
    void chainCheckPassesAnyBranchAndNull(String role) {
        login(role, null, AccessScope.CHAIN);

        assertThatCode(() -> scope.check(OTHER)).doesNotThrowAnyException();
        assertThatCode(() -> scope.check(null)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- CUSTOMER (docs/adr/0006)

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {OWN, OTHER})
    void customerResolveIs403(Long requested) {
        login("CUSTOMER", null, AccessScope.OWNER);

        // Khách lọc theo chủ sở hữu, không bao giờ nhận dữ liệu theo chi nhánh (kể cả toàn chuỗi khi null)
        assertThatThrownBy(() -> scope.resolve(requested))
                .isInstanceOfSatisfying(AccessDeniedScopeException.class, ex -> {
                    assertThat(ex.getRequiredScope()).isEqualTo("branch:" + requested);
                    assertThat(ex.getActualScope()).isEqualTo("owner:1");
                    assertThat(ex.clientMessage()).isEqualTo("Không có quyền truy cập dữ liệu này");
                });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {OWN, OTHER})
    void customerCheckIs403(Long resourceBranchId) {
        login("CUSTOMER", null, AccessScope.OWNER);

        assertThatThrownBy(() -> scope.check(resourceBranchId))
                .isInstanceOfSatisfying(AccessDeniedScopeException.class,
                        ex -> assertThat(ex.getActualScope()).isEqualTo("owner:1"));
    }

    // ---------------------------------------------------------------- not authenticated

    @Test
    void currentWithoutAuthenticationIsProgrammingError() {
        assertThatThrownBy(scope::current).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> scope.resolve(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentWithAnonymousOrForeignPrincipalIsProgrammingError() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(scope::current).isInstanceOf(IllegalStateException.class);

        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("plain-user", null, List.of()));
        assertThatThrownBy(scope::current).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentReturnsPrincipal() {
        Principal principal = login("VET", OWN, AccessScope.BRANCH);

        assertThat(scope.current()).isSameAs(principal);
    }

    private static Principal login(String role, Long branchId, AccessScope accessScope) {
        Principal principal = new Principal(1L, "u@petcare.test", 2L, role, branchId, accessScope, false);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_" + role)));
        return principal;
    }
}
