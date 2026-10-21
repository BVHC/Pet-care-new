package com.petcare.module.visit.dto;

import com.petcare.module.catalog.api.ServiceGroup;

/**
 * Giá trị nhận được của tham số {@code serviceGroup} ở hàng đợi chi nhánh (visit-v1 #2, yaml {@code enum: [MEDICAL,
 * GROOMING]}; docs/adr/0060). Enum riêng thay vì {@link ServiceGroup} của catalog để {@code BOARDING} bị binding từ chối
 * (400 {@code MALFORMED_REQUEST}) như mọi giá trị lạ, không trả mảng rỗng âm thầm.
 */
public enum QueueServiceGroup {
    MEDICAL,
    GROOMING;

    public ServiceGroup toGroup() {
        return switch (this) {
            case MEDICAL -> ServiceGroup.MEDICAL;
            case GROOMING -> ServiceGroup.GROOMING;
        };
    }
}
