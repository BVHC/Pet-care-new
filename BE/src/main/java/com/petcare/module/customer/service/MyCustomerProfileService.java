package com.petcare.module.customer.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.dto.CustomerProfileResponse;
import com.petcare.module.customer.dto.UpdateMyCustomerProfileRequest;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.mapper.CustomerProfileMapper;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * UC06 — hồ sơ khách của tôi ({@code GET/PATCH /api/me/customer-profile}, customer-v1 #1–2; BR-TK-15, BR-KH-01;
 * docs/adr/0028). Chủ hồ sơ luôn là chủ token. Không audit (convention 08 §8.3 không liệt kê).
 * <ol>
 *   <li>Có {@code email} → BR-TK-15, trước mọi đọc / ghi.</li>
 *   <li>Khóa dòng {@code customers} <b>rồi mới</b> đọc entity: Hibernate ghi đủ mọi cột, nên đọc dưới khóa thì không
 *       ghi đè thay đổi đã commit của luồng khác.</li>
 *   <li>{@code null} = giữ; rỗng sau {@code strip} = xóa ({@code phone}, {@code avatarUrl}); hồ sơ tại quầy xóa SĐT →
 *       BR-KH-01 ({@link Customer#updateSelfProfile}).</li>
 * </ol>
 * Không đụng {@code link_decision_pending} (cờ chỉ đặt lúc xác thực, gỡ qua UC07 — BR-TK-19) và không trả danh sách
 * nghi trùng SĐT cho khách (customer-v1 A1). {@code email} trả về là email tài khoản (BR-KH-01, A5).
 */
@Service
public class MyCustomerProfileService {

    private final CustomerRepository customers;
    private final MyCustomerLookup lookup;
    private final BranchScope branchScope;
    private final CustomerProfileMapper mapper;

    public MyCustomerProfileService(CustomerRepository customers, MyCustomerLookup lookup, BranchScope branchScope,
            CustomerProfileMapper mapper) {
        this.customers = customers;
        this.lookup = lookup;
        this.branchScope = branchScope;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public CustomerProfileResponse getMyProfile() {
        SecurityPrincipal principal = branchScope.current();
        return mapper.toResponse(lookup.requireOwner(principal.accountId()), principal.email());
    }

    @Transactional
    public CustomerProfileResponse updateMyProfile(UpdateMyCustomerProfileRequest request) {
        if (request.email() != null) {
            throw new BusinessRuleViolationException("BR-TK-15",
                    "Không thể tự sửa email; liên hệ lễ tân để được sửa hộ");
        }

        SecurityPrincipal principal = branchScope.current();
        Customer customer = lookup.lockOwner(principal.accountId());
        customer.updateSelfProfile(
                Values.required(request.fullName(), customer.getFullName()),
                Values.optional(request.phone(), customer.getPhone()),
                Values.optional(request.avatarUrl(), customer.getAvatarUrl()));
        customers.flush();
        return mapper.toResponse(customer, principal.email());
    }
}
