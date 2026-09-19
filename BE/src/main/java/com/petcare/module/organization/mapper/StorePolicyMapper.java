package com.petcare.module.organization.mapper;

import com.petcare.module.organization.dto.StorePolicyResponse;
import com.petcare.module.organization.entity.StorePolicy;
import org.mapstruct.Mapper;

import java.util.UUID;

@Mapper(componentModel = "spring")
public interface StorePolicyMapper {

    default StorePolicyResponse toResponse(StorePolicy entity, UUID organizationId) {
        // entity.getVersion() có thể null trong test (mock save() không mô phỏng Hibernate @Version
        // tự gán 0 lúc insert đầu tiên) — MapStruct generate code null-safe cho unboxing Long->long,
        // hand-written default method này phải tự làm tương tự để không NPE.
        long version = entity.getVersion() != null ? entity.getVersion() : 0L;
        return new StorePolicyResponse(entity.getStoreId(), organizationId, entity.isSurchargeEnabled(),
                entity.getSurchargeType(), entity.getSurchargeValue(), version, entity.getUpdatedAt());
    }

    /**
     * RULE-03-10 — response mặc định khi Store chưa từng cấu hình (GET) hoặc để seed lúc
     * lazy-create (PATCH lần đầu, version=0). Định nghĩa 1 chỗ duy nhất, dùng chung 2 nơi để
     * tránh lặp giá trị mặc định.
     */
    default StorePolicyResponse toDefaultResponse(UUID storeId, UUID organizationId) {
        return new StorePolicyResponse(storeId, organizationId, false, null, null, 0L, null);
    }
}
