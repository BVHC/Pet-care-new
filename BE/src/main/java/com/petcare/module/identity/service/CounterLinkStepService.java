package com.petcare.module.identity.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.CustomerQueryApi.OnlineProfileLinkability;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.dto.LinkResult;
import com.petcare.module.identity.entity.Account;
import com.petcare.module.identity.entity.AccountStatus;
import com.petcare.module.identity.exception.LinkAccountNotFoundException;
import com.petcare.module.identity.mapper.LinkProfileMapper;
import com.petcare.module.identity.repository.AccountRepository;
import com.petcare.module.identity.service.LinkProfileAttemptService.LinkSnapshot;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * Phần có transaction của UC22 "lễ tân liên kết hộ" (identity-v1 #27; BR-TK-11, 16, 19; khuôn
 * {@link LinkProfileAttemptService} của UC07, nhưng không OTP). Chỉ được gọi từ {@link CounterLinkService} — facade
 * không transaction, để hai method ở đây chạy ở hai transaction <b>riêng</b>.
 * <ul>
 *   <li>{@link #findCounterEmail}: đọc hồ sơ tại quầy ({@code readOnly}, không khóa), kiểm 404 / BR-TK-19. Phải kết thúc
 *       trước {@link #applyLink}: {@code CustomerQueryApi.findContact} nạp entity hồ sơ vào persistence context; nếu cùng
 *       transaction với bước ghi thì "kiểm lại dưới khóa" của {@code CustomerApi.linkAccountToCounterProfile} có thể đọc
 *       lại chính bản đã nạp (cũ) thay vì dòng vừa bị người khác liên kết.</li>
 *   <li>{@link #applyLink}: khóa dòng {@code accounts} của tài khoản được liên kết <b>trước</b> mọi lời gọi sang customer
 *       (thứ tự khóa {@code accounts → otp_tokens → customers}, docs/adr/0027 mục 6), kiểm lại dưới khóa, rồi gắn và
 *       audit cùng transaction. Không bắt exception bên trong: mọi lỗi rollback, không để lại dấu vết nào.</li>
 * </ul>
 * Tài khoản {@code PENDING} bị chặn dưới khóa ({@code 404}): ST02 xóa hồ sơ gắn tài khoản {@code PENDING} quá hạn mà không
 * lọc kênh tạo (docs/adr/0013), nên hồ sơ tại quầy gắn nhầm vào đó sẽ bị xóa theo (hoặc làm job lặp lỗi vô hạn nếu hồ sơ có
 * dữ liệu).
 */
@Slf4j
@Service
public class CounterLinkStepService {

    static final String NOT_FOUND_TYPE = "hồ sơ khách";
    static final String MSG_PROFILE_HAS_ACCOUNT = "Hồ sơ này đã gắn tài khoản (hồ sơ online hoặc đã liên kết)";
    static final String MSG_NO_EMAIL = "Hồ sơ tại quầy chưa có email. Hãy bổ sung email vào hồ sơ (sửa hồ sơ khách) rồi"
            + " liên kết lại";
    static final String MSG_ACCOUNT_ALREADY_LINKED = "Tài khoản đã được liên kết với một hồ sơ tại quầy khác";
    static final String MSG_ONLINE_HAS_DATA = "Hồ sơ online của tài khoản đã có thú cưng, lịch hẹn, đặt chỗ, đơn hàng"
            + " hoặc góp ý nên không liên kết được";

    private final AccountRepository accounts;
    private final CustomerQueryApi customerQueries;
    private final CustomerApi customers;
    private final AuditRecorder audit;
    private final LinkProfileMapper mapper;

    public CounterLinkStepService(AccountRepository accounts, CustomerQueryApi customerQueries, CustomerApi customers,
            AuditRecorder audit, LinkProfileMapper mapper) {
        this.accounts = accounts;
        this.customerQueries = customerQueries;
        this.customers = customers;
        this.audit = audit;
        this.mapper = mapper;
    }

    /**
     * Bước đọc không khóa: hồ sơ không tồn tại → 404; đã gắn tài khoản (hồ sơ online hoặc hồ sơ quầy đã liên kết —
     * {@code CustomerContact} không cho biết kênh) → BR-TK-19; chưa có email → BR-TK-19 kèm hướng dẫn (customer so
     * {@code expectedCounterEmail} với email hồ sơ nên email rỗng chỉ cho ra câu mơ hồ). Trả email hồ sơ nguyên văn để
     * {@link #applyLink} truyền làm {@code expectedCounterEmail}.
     */
    @Transactional(readOnly = true)
    public String findCounterEmail(Long customerId) {
        CustomerContact counter = customerQueries.findContact(customerId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_TYPE, customerId));
        if (counter.accountId() != null) {
            throw new BusinessRuleViolationException("BR-TK-19", MSG_PROFILE_HAS_ACCOUNT);
        }
        if (counter.email() == null || counter.email().isBlank()) {
            throw new BusinessRuleViolationException("BR-TK-19", MSG_NO_EMAIL);
        }
        return counter.email();
    }

    /**
     * Ghi dưới khóa dòng {@code accounts}. Thứ tự: khóa tài khoản theo {@code accountEmail} (đã chuẩn hóa) → phải là tài
     * khoản khách {@code ACTIVE} có hồ sơ (không thì 404 chung) → bị khóa ({@code is_locked}) → BR-TK-11 → hồ sơ của
     * tài khoản chính là hồ sơ đang liên kết (bấm đúp: lần trước đã xong) → BR-TK-19 → {@code checkOnlineProfileLinkable}
     * (đã liên kết hồ sơ quầy khác / hồ sơ online có dữ liệu → BR-TK-19) → {@link CustomerApi#linkAccountToCounterProfile}
     * (kiểm lại dưới khóa {@code customers}; BR-TK-19 nếu hồ sơ vừa bị liên kết hoặc đổi email) → audit.
     */
    @Transactional
    public LinkResult applyLink(Long customerId, String accountEmail, String counterEmail) {
        Account account = accounts.findByEmailForUpdate(accountEmail)
                .filter(candidate -> candidate.getRole() == Role.CUSTOMER
                        && candidate.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(LinkAccountNotFoundException::new);
        Long accountId = account.getId();
        // Projection, không nạp entity: BR-KH-01 bảo đảm có hồ sơ, nhưng dữ liệu hỏng phải là 404 chứ không phải 500.
        Long onlineCustomerId = customerQueries.findCustomerIdByAccountId(accountId)
                .orElseThrow(LinkAccountNotFoundException::new);
        if (account.isLocked()) {
            throw new BusinessRuleViolationException("BR-TK-11", LoginAttemptService.MSG_LOCKED);
        }
        if (onlineCustomerId.equals(customerId)) {
            throw new BusinessRuleViolationException("BR-TK-19", MSG_PROFILE_HAS_ACCOUNT);
        }
        requireLinkable(accountId);

        customers.linkAccountToCounterProfile(accountId, customerId, counterEmail, account.getEmail());
        audit.record(AuditEntry.of(IdentityAuditActions.CUSTOMER_PROFILE_LINKED).entity("customers", customerId)
                .before(new LinkSnapshot(customerId, onlineCustomerId, null, null))
                .after(new LinkSnapshot(customerId, null, accountId, IdentityAuditActions.VERIFICATION_ID_CARD)));
        log.info("PROFILE_LINKED_AT_COUNTER customerId={} accountId={}", customerId, accountId);
        return mapper.toLinkResult(customerId);
    }

    private void requireLinkable(Long accountId) {
        OnlineProfileLinkability linkability = customerQueries.checkOnlineProfileLinkable(accountId);
        switch (linkability) {
            case LINKABLE -> {
                // hồ sơ online chưa có dữ liệu: còn liên kết được
            }
            case ALREADY_LINKED -> throw new BusinessRuleViolationException("BR-TK-19", MSG_ACCOUNT_ALREADY_LINKED);
            case HAS_DATA -> throw new BusinessRuleViolationException("BR-TK-19", MSG_ONLINE_HAS_DATA);
        }
    }
}
