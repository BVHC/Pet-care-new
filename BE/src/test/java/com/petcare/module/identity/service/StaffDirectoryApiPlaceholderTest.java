package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.petcare.module.identity.api.Role;

/**
 * Mọi method của placeholder phải ném, không được trả giá trị "an toàn" (06-module-contracts §1).
 * BE-1 xóa test này cùng {@link StaffDirectoryApiPlaceholder} khi cài thật.
 */
class StaffDirectoryApiPlaceholderTest {

    private final StaffDirectoryApiPlaceholder placeholder = new StaffDirectoryApiPlaceholder();

    @Test
    void everyMethodThrows() {
        assertThatThrownBy(() -> placeholder.findStaff(1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.findAssignableStaff(1L, Role.VET))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.countBranchManagers(1L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.findActiveStaffIds(1L, Role.BRANCH_MANAGER))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(placeholder::findActiveSuperManagerIds).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(placeholder::listPublicVets).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> placeholder.findEmail(1L)).isInstanceOf(UnsupportedOperationException.class);
    }
}
