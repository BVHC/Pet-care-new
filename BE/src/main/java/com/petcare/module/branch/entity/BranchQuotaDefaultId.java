package com.petcare.module.branch.entity;

import java.io.Serializable;

import com.petcare.module.catalog.api.ServiceGroup;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Khóa ghép {@code (branch_id, service_group)} của {@code branch_quota_defaults}. */
@Getter
@Embeddable
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchQuotaDefaultId implements Serializable {

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_group", nullable = false)
    private ServiceGroup serviceGroup;
}
