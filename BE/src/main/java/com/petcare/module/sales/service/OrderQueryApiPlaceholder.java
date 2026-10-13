package com.petcare.module.sales.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.petcare.module.sales.api.OrderQueryApi;

/**
 * Giữ chỗ cho {@link OrderQueryApi} tới khi module sales (BE-2) cài thật — nợ D001/D010 phía khách (docs/dept/INDEX.md).
 * BE-1 tạo thay owner (2026-10-12, docs/adr/0035) vì khóa tài khoản khách (identity-v1 #29) là bên gọi đầu tiên: thiếu
 * bean thì app không khởi động. Theo 06-module-contracts §1: mọi method ném lỗi, không trả giá trị "an toàn" (danh sách
 * Order chưa thanh toán rỗng sẽ làm lễ tân không được báo việc dở, 06 §8 Q2). Hệ quả: khóa tài khoản khách trả 500 trên
 * app thật tới khi có bản thật (vốn đã 500 vì {@code CustomerQueryApi} còn placeholder). BE-2 xóa lớp này (cùng
 * {@code OrderQueryApiPlaceholderTest}) khi thêm implementation thật — <b>không</b> tạo placeholder thứ hai (trùng bean).
 */
@Service
public class OrderQueryApiPlaceholder implements OrderQueryApi {

    static final String MESSAGE = "OrderQueryApi chưa được cài (module sales, BE-2)";

    @Override
    public boolean existsByCustomer(Long customerId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean hasPendingOrderForPet(Long petId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<UnpaidOrderRef> findUnpaidOrdersByCustomer(Long customerId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
