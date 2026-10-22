package com.petcare.module.customer.service;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.dto.CreateCounterCustomerRequest;
import com.petcare.module.customer.dto.CustomerWriteResultResponse;
import com.petcare.module.customer.dto.UpdateCustomerRequest;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.mapper.CustomerStaffMapper;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC22 — lễ tân tạo / sửa hồ sơ khách tại quầy (customer-v1 #9, #11; BR-KH-01, 10, BR-TK-16; docs/adr/0041). Không audit
 * (convention 08 §8.3 không liệt kê hồ sơ khách). Mỗi use case một {@code @Transactional}, không {@code noRollbackFor}.
 * <ul>
 *   <li>Tạo: {@code COUNTER}, chưa gắn tài khoản, {@code created_by} = lễ tân; SĐT trùng hồ sơ khác vẫn tạo và trả danh
 *       sách nghi trùng (BR-KH-10, cảnh báo không chặn).</li>
 *   <li>Sửa: khóa dòng {@code customers} <b>trước</b> khi đọc entity (Hibernate ghi đủ cột — đọc dưới khóa thì không ghi
 *       đè thay đổi đã commit của lễ tân khác hay của khách tự sửa) → 404 → email gửi lên khác email hiện tại khi hồ sơ
 *       đã gắn tài khoản → 400 BR-TK-16 → {@code null} giữ / rỗng xóa → hồ sơ tại quầy xóa SĐT → 400 BR-KH-01. Chỉ khi
 *       SĐT <b>thực sự đổi</b> sang giá trị khác thì mới tính nghi trùng (BR-KH-10).</li>
 * </ul>
 */
@Service
public class CounterCustomerService {

    private final CustomerRepository customers;
    private final CustomerSearchService search;
    private final CustomerStaffMapper mapper;
    private final BranchScope branchScope;

    public CounterCustomerService(CustomerRepository customers, CustomerSearchService search,
            CustomerStaffMapper mapper, BranchScope branchScope) {
        this.customers = customers;
        this.search = search;
        this.mapper = mapper;
        this.branchScope = branchScope;
    }

    @Transactional
    public CustomerWriteResultResponse createCounterCustomer(CreateCounterCustomerRequest request) {
        Long actor = branchScope.current().accountId();
        Customer customer = customers.saveAndFlush(Customer.createAtCounter(request.fullName().strip(),
                request.phone(), normalizeEmail(request.email()), actor));
        return result(customer, true);
    }

    @Transactional
    public CustomerWriteResultResponse updateCustomer(Long customerId, UpdateCustomerRequest request) {
        Customer customer = customers.findByIdForUpdate(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("khách hàng", customerId));

        String newEmail = emailFor(customer, request.email());
        String oldPhone = customer.getPhone();
        String newPhone = Values.optional(request.phone(), oldPhone);
        customer.updateByStaff(Values.required(request.fullName(), customer.getFullName()), newPhone, newEmail);
        customers.flush();
        return result(customer, newPhone != null && !newPhone.equals(oldPhone));
    }

    /**
     * Email hồ sơ có tài khoản theo email tài khoản (BR-TK-19, A5): chỉ sửa ở identity. Gửi lại đúng email hiện tại thì coi
     * như không đổi (form đầy đủ của FE), không báo lỗi.
     */
    private static String emailFor(Customer customer, String requested) {
        String current = customer.getEmail();
        if (requested == null) {
            return current;
        }
        String normalized = normalizeEmail(requested);
        if (customer.getAccountId() != null && !java.util.Objects.equals(normalized, current)) {
            throw new BusinessRuleViolationException("BR-TK-16",
                    "Hồ sơ đã gắn tài khoản: email theo tài khoản, chỉ sửa qua chức năng sửa email tài khoản");
        }
        return normalized;
    }

    private CustomerWriteResultResponse result(Customer customer, boolean checkDuplicates) {
        return new CustomerWriteResultResponse(mapper.toProfile(customer),
                checkDuplicates ? search.findDuplicatePhoneProfiles(customer.getPhone(), customer.getId(),
                        CustomerWriteResultResponse.MAX_DUPLICATES) : java.util.List.of());
    }

    /** Email hồ sơ luôn lưu chữ thường (khớp khi tra cứu bằng index); trắng → không có email. */
    static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
