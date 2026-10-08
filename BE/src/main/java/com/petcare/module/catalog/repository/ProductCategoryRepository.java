package com.petcare.module.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.petcare.module.catalog.entity.ProductCategory;

public interface ProductCategoryRepository
        extends JpaRepository<ProductCategory, Long>, JpaSpecificationExecutor<ProductCategory> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
