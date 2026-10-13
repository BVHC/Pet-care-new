package com.petcare.module.branch.service;

import java.util.Collection;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.module.branch.api.BranchApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.repository.BranchRepository;

/**
 * Cài đặt {@link BranchApi} (docs/adr/0029, nợ D014). Thứ tự id tăng dần do lớp này giữ, bên gọi không tự sắp nên
 * không thể quên: hai luồng khóa cùng hai chi nhánh theo hai hướng (điều chuyển A→B và B→A) không deadlock.
 * <p>
 * Chặn transaction {@code readOnly} trước mọi câu SQL: PostgreSQL không cho {@code SELECT … FOR NO KEY UPDATE} trong
 * transaction chỉ đọc, lỗi SQL đó khó hiểu hơn lỗi lập trình nêu rõ ở đây.
 */
@Service
public class BranchStaffLockService implements BranchApi {

    private final BranchRepository branches;

    public BranchStaffLockService(BranchRepository branches) {
        this.branches = branches;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SortedMap<Long, BranchStatus> lockForStaffChange(Collection<Long> branchIds) {
        Objects.requireNonNull(branchIds, "branchIds");
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("BranchApi.lockForStaffChange cần transaction ghi, không được readOnly");
        }
        SortedMap<Long, BranchStatus> locked = new TreeMap<>();
        for (Long branchId : new TreeSet<>(branchIds)) {
            branches.lockStatusForStaffChange(branchId)
                    .ifPresent(status -> locked.put(branchId, BranchStatus.valueOf(status)));
        }
        return locked;
    }
}
