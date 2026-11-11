package com.petcare.module.ai.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.ai.entity.AiItemProfile;

/** Bảng {@code ai_item_profiles} (docs/adr/0130, 0133). Mỗi dịch vụ / sản phẩm có tối đa một dòng (UNIQUE ở V34). */
public interface AiItemProfileRepository extends JpaRepository<AiItemProfile, Long> {

    Optional<AiItemProfile> findByServiceId(Long serviceId);

    Optional<AiItemProfile> findByProductId(Long productId);

    List<AiItemProfile> findByServiceIdIn(Collection<Long> serviceIds);

    List<AiItemProfile> findByProductIdIn(Collection<Long> productIds);
}
