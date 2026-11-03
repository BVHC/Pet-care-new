package com.petcare.module.visit.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.AccountUnlockedEvent;
import com.petcare.module.visit.service.VisitReassignFlagService;

/**
 * BR-QT-12, BR-TN-08 (03 Tài khoản#8): ADMIN mở khóa tài khoản → lượt đã gọi của người đó mà họ lại đủ điều kiện phụ
 * trách thì không còn "cần gán lại" (docs/adr/0088, trả D042). Đồng bộ, trong transaction mở khóa của identity
 * (convention 07 §7.2): lỗi ở đây rollback cả lần mở khóa.
 */
@Component
public class AccountUnlockedListener {

    private final VisitReassignFlagService reassignFlags;

    public AccountUnlockedListener(VisitReassignFlagService reassignFlags) {
        this.reassignFlags = reassignFlags;
    }

    @EventListener
    public void on(AccountUnlockedEvent event) {
        reassignFlags.clearFlagsOf(event.accountId());
    }
}
