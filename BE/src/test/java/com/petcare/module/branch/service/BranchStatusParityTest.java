package com.petcare.module.branch.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.entity.BranchStatus;

/**
 * Enum trạng thái của entity và của {@code branch.api} phải cùng tập hằng số: {@code BranchQueryService.toSummary}
 * và {@code BranchStaffLockService} (docs/adr/0029) map bằng {@code valueOf(name())}, lệch nhau thì 500 lúc chạy.
 */
class BranchStatusParityTest {

    @Test
    void entityAndApiEnumsHaveSameConstants() {
        assertThat(Arrays.stream(BranchStatus.values()).map(Enum::name))
                .containsExactlyElementsOf(Arrays.stream(BranchQueryApi.BranchStatus.values()).map(Enum::name).toList());
    }
}
