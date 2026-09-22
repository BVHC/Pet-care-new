package com.petcare.module.catalog.repository;

import com.petcare.module.catalog.entity.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ServiceRepository extends JpaRepository<Service, UUID> {

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    Page<Service> findAllByOrganizationId(UUID organizationId, Pageable pageable);

    List<Service> findAllByOrganizationId(UUID organizationId);

    // RULE-03-02 (ActivateStore, điều kiện 3) — Organization phải có ít nhất 1 Service active.
    // Tên method PHẢI khớp field JPA thật "isActive" (Hibernate field-access resolve theo tên
    // field Java, không bóc tiền tố "is" như JavaBean/MapStruct property-name convention —
    // "existsByOrganizationIdAndActiveTrue" từng bị Hibernate ném PathElementException lúc khởi
    // động context vì field thật sự tên "isActive", không phải "active"; xác nhận qua IT chạy
    // thật, không chỉ suy luận từ comment ProductMapper/ServiceMapper — comment đó nói về vấn đề
    // khác, JavaBean getter-property-name cho MapStruct, không áp dụng cho Spring Data/Hibernate).
    boolean existsByOrganizationIdAndIsActiveTrue(UUID organizationId);
}
