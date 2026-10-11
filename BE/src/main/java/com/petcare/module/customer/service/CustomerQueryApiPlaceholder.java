package com.petcare.module.customer.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.petcare.module.customer.api.CustomerQueryApi;

/**
 * Giữ chỗ cho {@link CustomerQueryApi} tới khi module customer (BE-2) cài thật — nợ D010 (docs/dept/INDEX.md).
 * Theo 06-module-contracts §1: mọi method ném lỗi, không trả giá trị "an toàn", để không guard nào đi qua sai
 * mà không ai biết. Đăng nhập (identity, docs/adr/0019) đọc cờ {@code linkDecisionPending} qua
 * {@link #findCustomerIdByAccountId} → {@link #findContact} chỉ với tài khoản {@code CUSTOMER}: nhân viên đăng nhập
 * bình thường, khách nhận 500 và rollback (không mở phiên). BE-2 xóa lớp này (cùng
 * {@code CustomerQueryApiPlaceholderTest}) khi thêm implementation thật.
 */
@Service
public class CustomerQueryApiPlaceholder implements CustomerQueryApi {

    static final String MESSAGE = "CustomerQueryApi chưa được cài (module customer, BE-2) — nợ D010";

    @Override
    public Optional<CustomerContact> findContact(Long customerId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public Optional<Long> findCustomerIdByAccountId(Long accountId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<LinkCandidate> findLinkCandidates(String phone) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public OnlineProfileLinkability checkOnlineProfileLinkable(Long accountId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
