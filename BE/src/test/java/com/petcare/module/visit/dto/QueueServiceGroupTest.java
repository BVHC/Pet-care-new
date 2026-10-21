package com.petcare.module.visit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.petcare.module.catalog.api.ServiceGroup;

/** Tham số {@code serviceGroup} của hàng đợi chỉ có hai giá trị của hợp đồng và map đúng nhóm (docs/adr/0060 T2). */
class QueueServiceGroupTest {

    @Test
    void onlyTheTwoContractValuesAndEachMapsToItsOwnGroup() {
        assertThat(Arrays.stream(QueueServiceGroup.values()).map(Enum::name)).containsExactly("MEDICAL", "GROOMING");
        assertThat(QueueServiceGroup.MEDICAL.toGroup()).isEqualTo(ServiceGroup.MEDICAL);
        assertThat(QueueServiceGroup.GROOMING.toGroup()).isEqualTo(ServiceGroup.GROOMING);
    }
}
