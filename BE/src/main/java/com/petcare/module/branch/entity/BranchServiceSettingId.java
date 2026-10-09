package com.petcare.module.branch.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Khóa ghép {@code (branch_id, service_id)} của {@code branch_services}. */
@Getter
@Embeddable
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchServiceSettingId implements Serializable {

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;
}
