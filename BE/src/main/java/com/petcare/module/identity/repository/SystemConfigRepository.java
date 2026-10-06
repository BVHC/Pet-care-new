package com.petcare.module.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.identity.entity.SystemConfig;

public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {
}
