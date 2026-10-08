package com.petcare.module.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.catalog.entity.KennelType;

public interface KennelTypeRepository extends JpaRepository<KennelType, Long> {
}
