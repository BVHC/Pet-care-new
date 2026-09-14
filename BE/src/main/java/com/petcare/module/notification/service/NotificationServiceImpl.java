package com.petcare.module.notification.service;

import com.petcare.module.notification.entity.NotificationDeliveryLog;
import com.petcare.module.notification.entity.NotificationTask;
import com.petcare.module.notification.gateway.EmailGateway;
import com.petcare.module.notification.repository.NotificationDeliveryLogRepository;
import com.petcare.module.notification.repository.NotificationTaskRepository;
import com.petcare.platform.enums.NotificationChannel;
import com.petcare.platform.enums.NotificationStatus;
import com.petcare.platform.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationTaskRepository notificationTaskRepository;
    private final NotificationDeliveryLogRepository notificationDeliveryLogRepository;
    private final EmailGateway emailGateway;

    @Override
    @Transactional
    public UUID enqueue(UUID recipientUserId, NotificationChannel channel, String eventType, String content) {
        NotificationTask task = notificationTaskRepository.save(
                new NotificationTask(recipientUserId, channel, eventType, content));
        return task.getId();
    }

    /**
     * Gọi SAU KHI transaction của {@link #enqueue} đã commit (lời gọi ngoài
     * qua Spring proxy — xem javadoc {@link NotificationService}). Lỗi
     * gateway (SMTP timeout/auth fail...) chỉ set status=FAILED, KHÔNG throw
     * lên caller — RegisterAccount/ResendOTP vẫn coi là thành công, người
     * dùng có thể gọi lại ResendOTP để thử gửi lại (RULE-23-03 mô tả retry
     * đầy đủ bằng exponential backoff — chưa triển khai ở bản tối giản này).
     */
    @Override
    @Transactional
    public void dispatch(UUID taskId, String toAddress) {
        NotificationTask task = notificationTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("NotificationTask", taskId));

        if (task.getChannel() != NotificationChannel.EMAIL) {
            log.warn("NotificationServiceImpl.dispatch: channel={} chưa có gateway impl, chỉ EMAIL được hỗ trợ ở bản tối giản này, taskId={}",
                    task.getChannel(), taskId);
            return;
        }

        try {
            String messageId = emailGateway.send(toAddress, "Pet Care — Mã xác thực OTP", task.getContent());
            task.setStatus(NotificationStatus.SENT);
            notificationTaskRepository.save(task);
            notificationDeliveryLogRepository.save(new NotificationDeliveryLog(
                    task, emailGateway.providerName(), "SUCCESS", toJsonPayload(messageId)));
            log.info("NotificationServiceImpl.dispatch SUCCESS taskId={} provider={}", taskId, emailGateway.providerName());
        } catch (Exception ex) {
            task.setStatus(NotificationStatus.FAILED);
            notificationTaskRepository.save(task);
            notificationDeliveryLogRepository.save(new NotificationDeliveryLog(
                    task, emailGateway.providerName(), "FAILED", toJsonPayload(ex.getMessage())));
            log.warn("NotificationServiceImpl.dispatch FAILED taskId={} provider={} error={}",
                    taskId, emailGateway.providerName(), ex.getMessage());
        }
    }

    private String toJsonPayload(String message) {
        String escaped = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"message\":\"" + escaped + "\"}";
    }
}
