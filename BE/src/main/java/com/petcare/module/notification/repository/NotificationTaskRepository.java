package com.petcare.module.notification.repository;

import com.petcare.module.notification.entity.NotificationTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationTaskRepository extends JpaRepository<NotificationTask, UUID> {
}
