package com.petcare.module.catalog.repository;

import com.petcare.module.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsByOrganizationIdAndSku(UUID organizationId, String sku);

    Page<Product> findAllByOrganizationId(UUID organizationId, Pageable pageable);

    List<Product> findAllByOrganizationId(UUID organizationId);
}
