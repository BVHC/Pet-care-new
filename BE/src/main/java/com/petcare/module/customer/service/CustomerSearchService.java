package com.petcare.module.customer.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.dto.CustomerSearchFilter;
import com.petcare.module.customer.dto.CustomerSearchItemResponse;
import com.petcare.module.customer.dto.PetBriefResponse;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.mapper.CustomerStaffMapper;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.module.customer.repository.CustomerSearchRepository;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.platform.model.PageResponse;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

/**
 * UC23 — tra cứu khách & thú cưng cho nhân viên (customer-v1 #8; BR-KH-01, 10; docs/adr/0041). Hồ sơ khách dùng chung
 * toàn chuỗi (04 nguyên tắc 8) nên không lọc theo chi nhánh. Email luôn che. Thú cưng của cả trang đọc bằng một câu
 * {@code customer_id IN (…)} (không N+1). Đọc ở READ COMMITTED: số đếm và trang có thể lệch một dòng nếu hồ sơ được tạo
 * giữa hai câu — chấp nhận ở danh sách tra cứu (khác nhật ký audit, ADR-0038).
 */
@Service
public class CustomerSearchService {

    static final int MAX_PAGE_SIZE = 100;

    private final CustomerSearchRepository search;
    private final CustomerRepository customers;
    private final PetRepository pets;
    private final CustomerStaffMapper mapper;
    private final Validator validator;

    public CustomerSearchService(CustomerSearchRepository search, CustomerRepository customers, PetRepository pets,
            CustomerStaffMapper mapper, Validator validator) {
        this.search = search;
        this.customers = customers;
        this.pets = pets;
        this.mapper = mapper;
        this.validator = validator;
    }

    /**
     * Sắp {@code full_name, id}; {@code page} ≥ 0, {@code size} 1–100 (kẹp). Không có điều kiện nào →
     * {@link ConstraintViolationException} (400 {@code VALIDATION_FAILED}) trước mọi câu SQL.
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerSearchItemResponse> search(CustomerSearchFilter filter, int page, int size) {
        Set<ConstraintViolation<CustomerSearchFilter>> violations = validator.validate(filter);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        long total = search.count(filter);
        List<CustomerSearchRepository.Row> rows = search.findPage(filter, pageable.getOffset(),
                pageable.getPageSize());
        Map<Long, List<PetBriefResponse>> petsByCustomer = petsOf(rows.stream().map(r -> r.id()).toList());
        List<CustomerSearchItemResponse> content = rows.stream()
                .map(r -> item(r.id(), r.fullName(), r.phone(), r.email(), r.createdChannel(), r.hasAccount(),
                        petsByCustomer))
                .toList();
        return PageResponse.of(new PageImpl<>(content, pageable, total));
    }

    /**
     * BR-KH-10: hồ sơ <b>khác</b> (mọi nguồn) cùng SĐT, tối đa {@code limit}, theo mã tăng dần. Chạy trong transaction
     * của bên gọi (tạo / sửa hồ sơ).
     */
    @Transactional(readOnly = true)
    public List<CustomerSearchItemResponse> findDuplicatePhoneProfiles(String phone, Long excludeCustomerId,
            int limit) {
        if (phone == null) {
            return List.of();
        }
        List<Customer> others = customers.findOtherProfilesByPhone(phone, excludeCustomerId, limit);
        Map<Long, List<PetBriefResponse>> petsByCustomer = petsOf(others.stream().map(Customer::getId).toList());
        return others.stream()
                .map(c -> item(c.getId(), c.getFullName(), c.getPhone(), c.getEmail(), c.getCreatedChannel(),
                        c.getAccountId() != null, petsByCustomer))
                .toList();
    }

    private Map<Long, List<PetBriefResponse>> petsOf(List<Long> customerIds) {
        Map<Long, List<PetBriefResponse>> byCustomer = new HashMap<>();
        if (customerIds.isEmpty()) {
            return byCustomer;
        }
        for (Pet pet : pets.findByCustomers(customerIds)) {
            byCustomer.computeIfAbsent(pet.getCustomerId(), id -> new java.util.ArrayList<>())
                    .add(mapper.toBrief(pet));
        }
        return byCustomer;
    }

    private static CustomerSearchItemResponse item(Long id, String fullName, String phone, String email,
            com.petcare.module.customer.entity.CustomerChannel channel, boolean hasAccount,
            Map<Long, List<PetBriefResponse>> petsByCustomer) {
        return new CustomerSearchItemResponse(id, fullName, phone, Maskers.email(email), channel, hasAccount,
                petsByCustomer.getOrDefault(id, List.of()));
    }
}
