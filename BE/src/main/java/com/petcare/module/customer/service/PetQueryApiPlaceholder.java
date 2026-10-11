package com.petcare.module.customer.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.petcare.module.customer.api.PetQueryApi;

/**
 * Giữ chỗ cho {@link PetQueryApi} tới khi module customer (BE-2) cài thật. Theo 06-module-contracts §1: mọi method
 * ném lỗi, không trả giá trị "an toàn". Hệ quả: danh sách lịch bị ảnh hưởng của branch (tên thú) trả 500.
 * BE-2 xóa lớp này (cùng {@code PetQueryApiPlaceholderTest}) khi thêm implementation thật.
 */
@Service
public class PetQueryApiPlaceholder implements PetQueryApi {

    static final String MESSAGE = "PetQueryApi chưa được cài (module customer, BE-2)";

    @Override
    public Optional<PetSummary> findPet(Long petId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
