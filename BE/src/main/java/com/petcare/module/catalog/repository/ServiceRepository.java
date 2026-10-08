package com.petcare.module.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.petcare.module.catalog.entity.Service;

public interface ServiceRepository extends JpaRepository<Service, Long>, JpaSpecificationExecutor<Service> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
