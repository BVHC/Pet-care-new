package com.petcare.module.identity.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;

/**
 * Giữ chỗ cho {@link StaffDirectoryApi} tới khi QT (BE-1) cài thật. Theo 06-module-contracts §1: mọi method ném
 * lỗi, không trả giá trị "an toàn". Hệ quả: kích hoạt chi nhánh ({@code countBranchManagers}, BR-QT-04) trả 500.
 * BE-1 xóa lớp này (cùng {@code StaffDirectoryApiPlaceholderTest}) khi thêm implementation thật.
 */
@Service
public class StaffDirectoryApiPlaceholder implements StaffDirectoryApi {

    static final String MESSAGE = "StaffDirectoryApi chưa được cài (module identity, BE-1)";

    @Override
    public Optional<StaffSummary> findStaff(Long accountId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<StaffSummary> findAssignableStaff(Long branchId, Role role) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public int countBranchManagers(Long branchId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<Long> findActiveStaffIds(Long branchId, Role role) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<Long> findActiveSuperManagerIds() {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<PublicVetProfile> listPublicVets() {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public Optional<String> findEmail(Long accountId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
