package com.petcare.module.notification.repository;

import com.petcare.module.notification.entity.NotificationDeliveryLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationDeliveryLogRepository extends JpaRepository<NotificationDeliveryLog, UUID> {
}
