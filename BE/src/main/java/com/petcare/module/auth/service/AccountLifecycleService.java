package com.petcare.module.auth.service;

import com.petcare.platform.enums.AccountStatus;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Ranh giới tối giản module IAM (02) gọi từ module Auth (01) — theo
 * docs/convention/backend/01-package-structure.md: "không import trực tiếp
 * entity/repository của module khác". Phục vụ RULE-02-04/05/07
 * (LockAccount/UnlockAccount/DeactivateAccount/ReactivateAccount) — IAM
 * enforce phần thẩm quyền theo scope (RULE-02-05) rồi gọi qua interface này,
 * FSM-1 (Account) vẫn ở nguyên module Auth (docs/03-state-machines.md §1).
 */
public interface AccountLifecycleService {

    AccountSummary getSummary(UUID accountId);

    /**
     * Batch lookup — tránh N+1 query khi IAM cần render trạng thái Account
     * cho cả 1 trang User (GET /users). Key không tìm thấy sẽ bị bỏ qua
     * (không throw) vì đây chỉ phục vụ hiển thị, không phải guard nghiệp vụ.
     */
    Map<UUID, AccountStatus> getStatuses(Collection<UUID> accountIds);

    /** RULE-02-04 — Admin chủ động khóa, thu hồi tức thì toàn bộ session. */
    AccountSummary lockAccount(UUID accountId);

    /** RULE-02-04 — Admin chủ động mở khóa (mọi lock_reason, không cần chờ locked_until). */
    AccountSummary unlockAccount(UUID accountId);

    /** RULE-02-07 — nhân viên nghỉ việc, thu hồi session + từ chối login vĩnh viễn. */
    AccountSummary deactivateAccount(UUID accountId, String reason);

    /** RULE-02-07 — reason bắt buộc, ghi audit. */
    AccountSummary reactivateAccount(UUID accountId, String reason);
}
