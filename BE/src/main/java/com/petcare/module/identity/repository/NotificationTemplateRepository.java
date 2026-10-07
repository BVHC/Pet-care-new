package com.petcare.module.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.identity.entity.NotificationTemplate;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, String> {
}
