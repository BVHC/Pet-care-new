package com.petcare.module.organization.mapper;

import com.petcare.module.organization.dto.OrganizationPolicyResponse;
import com.petcare.module.organization.entity.OrganizationPolicy;
import com.petcare.platform.enums.SecurityFrameworkLevel;
import org.mapstruct.Mapper;

import java.util.UUID;

@Mapper(componentModel = "spring")
public interface OrganizationPolicyMapper {

    OrganizationPolicyResponse toResponse(OrganizationPolicy entity);

    /**
     * RULE-03-09 — response mặc định khi Organization chưa từng cấu hình (GET) hoặc để seed lúc
     * lazy-create (PATCH lần đầu, version=0). Định nghĩa 1 chỗ duy nhất, dùng chung 2 nơi để
     * tránh lặp giá trị mặc định.
     */
    default OrganizationPolicyResponse toDefaultResponse(UUID organizationId) {
        return new OrganizationPolicyResponse(organizationId, 7, true, 730,
                SecurityFrameworkLevel.STANDARD, 0L, null);
    }
}
