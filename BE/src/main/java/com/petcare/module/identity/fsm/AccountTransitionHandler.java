package com.petcare.module.identity.fsm;

import static com.petcare.module.identity.entity.AccountStatus.ACTIVE;
import static com.petcare.module.identity.entity.AccountStatus.DISABLED;
import static com.petcare.module.identity.entity.AccountStatus.PENDING;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bảng "1. Tài khoản" của docs/03-state-machines.md, chỉ phần {@code accounts.status}. Không có trong map:
 * #3 (xóa cứng tài khoản {@code PENDING} quá hạn, BR-TK-08) và #7, #8 (khóa/mở khóa là cờ {@code is_locked},
 * kiểm bằng guard — convention 05).
 */
@Component
public class AccountTransitionHandler extends StateMachineBase<AccountStatus> {

    @Override
    public Map<AccountStatus, Set<AccountStatus>> allowedTransitions() {
        return Map.of(
                PENDING, Set.of(ACTIVE),        // #2 xác thực OTP
                ACTIVE, Set.of(DISABLED),       // #5 vô hiệu hóa
                DISABLED, Set.of(ACTIVE));      // #6 kích hoạt lại
    }

    @Override
    public Set<AccountStatus> initialStates() {
        return Set.of(PENDING,                  // #1 đăng ký
                ACTIVE);                        // #4 tạo tài khoản nhân viên
    }
}
