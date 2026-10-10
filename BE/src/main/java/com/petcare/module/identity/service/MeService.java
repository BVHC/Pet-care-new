package com.petcare.module.identity.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.MeResponse;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.StaffProfile;
import com.petcare.module.identity.mapper.MeMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.repository.StaffProfileRepository;
import com.petcare.platform.security.BranchScope;

/**
 * UC06 — thông tin người đang đăng nhập ({@code GET /api/me}, identity-v1 #8). Chỉ đọc: một transaction readOnly,
 * vài SELECT theo khóa chính, không khóa dòng, không audit. Người được trả về luôn là chủ token (không có id do client
 * gửi). Được miễn chặn BR-TK-17 ({@code MustChangePasswordInterceptor}) để FE biết phải ép đổi mật khẩu.
 * <ul>
 *   <li>Nhân viên: hồ sơ {@code staff_profiles} + SĐT của tài khoản. Thiếu dòng {@code staff_profiles} là dữ liệu sai
 *       (nhân viên luôn được tạo kèm hồ sơ; như {@code AuthenticationIT.branchStaffWithoutStaffProfileNeverGetsChainWideData})
 *       → {@link IllegalStateException}, 500.</li>
 *   <li>Khách: hồ sơ khách và cờ chờ liên kết qua {@link CustomerQueryApi} (BR-KH-01, BR-TK-19) — cùng cách đọc với
 *       {@code LoginAttemptService.linkDecisionPending}. Thiếu hồ sơ vi phạm BR-KH-01 → 500.</li>
 * </ul>
 * Role đọc từ {@code accounts} trong transaction, không từ token.
 */
@Service
public class MeService {

    private final AccountRepository accounts;
    private final StaffProfileRepository staffProfiles;
    private final CustomerQueryApi customers;
    private final BranchScope branchScope;
    private final MeMapper mapper;

    public MeService(AccountRepository accounts, StaffProfileRepository staffProfiles, CustomerQueryApi customers,
            BranchScope branchScope, MeMapper mapper) {
        this.accounts = accounts;
        this.staffProfiles = staffProfiles;
        this.customers = customers;
        this.branchScope = branchScope;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public MeResponse getMe() {
        Long accountId = branchScope.current().accountId();
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Authenticated account " + accountId + " not found"));

        if (account.getRole() == Role.CUSTOMER) {
            Long customerId = customers.findCustomerIdByAccountId(accountId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Customer account " + accountId + " has no customer profile (BR-KH-01)"));
            boolean linkDecisionPending = customers.findContact(customerId)
                    .map(CustomerContact::linkDecisionPending)
                    .orElseThrow(() -> new IllegalStateException("Customer " + customerId + " not found"));
            return mapper.toMeResponse(account, null, customerId, linkDecisionPending);
        }

        StaffProfile profile = staffProfiles.findById(accountId)
                .orElseThrow(() -> new IllegalStateException(
                        "Staff account " + accountId + " has no staff_profiles row"));
        return mapper.toMeResponse(account, mapper.toStaffProfileResponse(profile, account.getPhone()), null, false);
    }
}
