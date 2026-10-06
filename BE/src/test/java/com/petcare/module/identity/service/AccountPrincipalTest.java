package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.petcare.module.identity.api.Role;
import com.petcare.platform.security.AccessScope;

/**
 * BR-QT-03, 04 nguyên tắc 8, 01 A02, docs/adr/0006: ADMIN, SUPER_MANAGER toàn chuỗi; A05–A08 một chi nhánh;
 * CUSTOMER chỉ dữ liệu của mình.
 */
class AccountPrincipalTest {

    @ParameterizedTest
    @CsvSource({
            "CUSTOMER,       OWNER,  false",
            "ADMIN,          CHAIN,  false",
            "SUPER_MANAGER,  CHAIN,  false",
            "BRANCH_MANAGER, BRANCH, true",
            "RECEPTIONIST,   BRANCH, true",
            "VET,            BRANCH, true",
            "CARETAKER,      BRANCH, true"})
    void accessScopeFollowsRole(Role role, AccessScope expectedScope, boolean expectedBranchScoped) {
        AccountPrincipal principal = new AccountPrincipal(1L, "a@petcare.test", 2L, role, 3L, false);

        assertThat(principal.accessScope()).isEqualTo(expectedScope);
        assertThat(principal.branchScoped()).isEqualTo(expectedBranchScoped);
        assertThat(principal.role()).isEqualTo(role.name());
    }
}
