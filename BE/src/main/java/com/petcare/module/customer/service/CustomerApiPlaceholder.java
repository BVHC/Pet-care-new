package com.petcare.module.customer.service;

import org.springframework.stereotype.Service;

import com.petcare.module.customer.api.CustomerApi;

/**
 * Giữ chỗ cho {@link CustomerApi} tới khi module customer (BE-2) cài thật — nợ D001 (docs/dept/INDEX.md).
 * Theo 06-module-contracts §1: mọi method ném lỗi, không trả giá trị "an toàn", để không guard nào đi qua sai
 * mà không ai biết. Hệ quả: {@code POST /api/auth/register} trả 500 và rollback toàn bộ.
 * BE-2 xóa lớp này (cùng {@code CustomerApiPlaceholderTest}) khi thêm implementation thật.
 */
@Service
public class CustomerApiPlaceholder implements CustomerApi {

    static final String MESSAGE = "CustomerApi chưa được cài (module customer, BE-2) — nợ D001";

    @Override
    public Long createOnlineProfile(Long accountId, String fullName, String phone) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean flagLinkDecisionIfPhoneMatches(Long accountId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public void deleteOnlineProfileOfUnverifiedAccount(Long accountId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public void linkAccountToCounterProfile(Long accountId, Long counterCustomerId, String accountEmail,
            Long actorId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public void declineLink(Long accountId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
