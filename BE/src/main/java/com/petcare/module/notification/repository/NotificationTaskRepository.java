package com.petcare.module.notification.repository;

import com.petcare.module.notification.entity.NotificationTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationTaskRepository extends JpaRepository<NotificationTask, UUID> {

    /** Thuần đọc, phục vụ CaregiverFlowIT lấy lại raw invitation token (spec D-02). */
    Optional<NotificationTask> findTopByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId);
}
