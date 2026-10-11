package com.petcare.module.customer.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.repository.CustomerRepository;

/**
 * Tìm hồ sơ khách của tài khoản đang đăng nhập (BR-KH-01: tài khoản khách luôn có đúng một hồ sơ). Không có hồ sơ là
 * dữ liệu sai → {@link IllegalStateException}, 500 (như {@code MeService}). Chỉ gọi bên trong transaction của service.
 */
@Component
class MyCustomerLookup {

    private final CustomerRepository customers;

    MyCustomerLookup(CustomerRepository customers) {
        this.customers = customers;
    }

    /** Đọc không khóa (GET). */
    Customer requireOwner(Long accountId) {
        return customers.findByAccountId(accountId).orElseThrow(() -> missing(accountId));
    }

    /**
     * Khóa dòng hồ sơ của chủ ({@code FOR NO KEY UPDATE}) rồi mới trả entity, để mọi lệnh ghi của cùng một khách chạy
     * tuần tự và không ghi đè thay đổi đã commit của luồng khác (docs/adr/0028).
     * <p>
     * Câu khóa đầu không trả dòng nào khi UC07 vừa xóa hồ sơ online và gán tài khoản vào hồ sơ tại quầy trong lúc câu này
     * chờ khóa: snapshot của câu đó không thấy hồ sơ tại quầy. Khi đó đọc lại id bằng câu mới (snapshot mới, READ
     * COMMITTED), khóa theo id và kiểm lại chủ dưới khóa.
     */
    Customer lockOwner(Long accountId) {
        Optional<Customer> locked = customers.findByAccountIdForUpdate(accountId);
        if (locked.isPresent()) {
            return locked.get();
        }
        Long customerId = customers.findIdByAccountId(accountId).orElseThrow(() -> missing(accountId));
        return customers.findByIdForUpdate(customerId)
                .filter(customer -> accountId.equals(customer.getAccountId()))
                .orElseThrow(() -> missing(accountId));
    }

    private static IllegalStateException missing(Long accountId) {
        return new IllegalStateException("Customer account " + accountId + " has no customer profile (BR-KH-01)");
    }
}
