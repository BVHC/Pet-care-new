package com.petcare.module.visit.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.petcare.module.identity.api.AccountLockedEvent;
import com.petcare.module.visit.service.VisitReassignFlagService;

/**
 * BR-QT-11, BR-TN-08 (03 Tài khoản#7): ADMIN khóa tài khoản → lượt đã gọi của người đó cần gán lại (06 §3; docs/adr/0062,
 * trả D017). Đồng bộ, trong transaction khóa của identity (convention 07 §7.2).
 */
@Component
public class AccountLockedListener {

    private final VisitReassignFlagService reassignFlags;

    public AccountLockedListener(VisitReassignFlagService reassignFlags) {
        this.reassignFlags = reassignFlags;
    }

    @EventListener
    public void on(AccountLockedEvent event) {
        reassignFlags.flagCalledVisitsOf(event.accountId());
    }
}
