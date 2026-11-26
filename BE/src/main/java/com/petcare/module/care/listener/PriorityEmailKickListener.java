package com.petcare.module.care.listener;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.petcare.module.care.job.MailKickExecutorConfig;
import com.petcare.module.care.job.NotificationOutboxRunner;
import com.petcare.module.care.service.PriorityEmailQueued;

/**
 * Gửi ngay email luồng HIGH (OTP, mật khẩu tạm) sau khi transaction ghi outbox commit (docs/adr/0154), trên luồng nền
 * {@link MailKickExecutorConfig#EXECUTOR}; người dùng không phải chờ job HIGH (5 s).
 * <p>
 * <b>Ngoại lệ có chủ đích</b> của quy ước "không {@code @TransactionalEventListener} / {@code @Async}" (convention 07
 * §7.2): đây không phải hệ quả nghiệp vụ phải cùng transaction mà là tối ưu độ trễ của một tác vụ mạng; độ bền nằm ở
 * dòng {@code notification_outbox} và job HIGH quét bù (như {@code ai/listener/KnowledgeIndexTrigger}). Rollback thì
 * không có sự kiện; hàng đợi đầy thì lần kích bị bỏ ({@code NOTIFICATION_KICK_DROPPED}) và job HIGH gửi sau.
 * Tắt bằng {@code app.jobs.notification-outbox.kick-enabled=false} (profile test).
 */
@Component
@ConditionalOnProperty(name = "app.jobs.notification-outbox.kick-enabled", havingValue = "true")
public class PriorityEmailKickListener {

    private final NotificationOutboxRunner runner;

    public PriorityEmailKickListener(NotificationOutboxRunner runner) {
        this.runner = runner;
    }

    @Async(MailKickExecutorConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPriorityEmailQueued(PriorityEmailQueued event) {
        runner.sendNow(event.outboxId());
    }
}
