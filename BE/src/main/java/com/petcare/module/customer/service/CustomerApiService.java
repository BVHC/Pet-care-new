package com.petcare.module.customer.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.CustomerChannel;
import com.petcare.module.customer.repository.AddressRepository;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * {@link CustomerApi} thật (thay {@code CustomerApiPlaceholder} — trả nợ D001; docs/adr/0041). Tách khỏi
 * {@code CustomerQueryService}: mỗi interface một bean để IT của module khác {@code @MockitoBean} từng interface mà
 * không đè lên nhau. Lệnh ghi {@code MANDATORY}: chạy trong
 * transaction của identity, không {@code REQUIRES_NEW} / {@code @Async} / {@code AFTER_COMMIT}.
 * <p>
 * Thứ tự khóa {@code accounts → customers (id tăng dần) → pets}; identity đã khóa {@code accounts} trước khi gọi vào đây.
 * Mọi lần từ chối của UC07 là {@link BusinessRuleViolationException} BR-TK-19 và xảy ra trước lệnh ghi đầu tiên.
 */
@Service
public class CustomerApiService implements CustomerApi {

    private final CustomerRepository customers;
    private final AddressRepository addresses;
    private final CustomerDataProbe data;

    public CustomerApiService(CustomerRepository customers, AddressRepository addresses, CustomerDataProbe data) {
        this.customers = customers;
        this.addresses = addresses;
        this.data = data;
    }

    // ---------------------------------------------------------------- CustomerApi

    /**
     * Hồ sơ online không có email: {@code CustomerApi.createOnlineProfile} không nhận email nên {@code customers.email}
     * để null (email của tài khoản vẫn là nguồn đúng cho {@code /api/me}); kênh nhắc email của khách online cần thêm
     * tham số — nợ ghi ở docs/dept.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long createOnlineProfile(Long accountId, String fullName, String phone) {
        return customers.saveAndFlush(Customer.createOnline(accountId, fullName, phone, null)).getId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean flagLinkDecisionIfPhoneMatches(Long accountId) {
        Customer own = lockOwn(accountId).orElseThrow(() -> missingProfile(accountId));
        if (own.getCreatedChannel() != CustomerChannel.ONLINE || own.getPhone() == null) {
            return own.isLinkDecisionPending();
        }
        if (!own.isLinkDecisionPending() && customers.existsUnlinkedCounterProfileWithPhone(own.getPhone())) {
            own.flagLinkDecision();
            customers.flush();
        }
        return own.isLinkDecisionPending();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteOnlineProfileOfUnverifiedAccount(Long accountId) {
        Optional<Customer> own = lockOwn(accountId);
        if (own.isEmpty()) {
            return;
        }
        addresses.deleteByCustomer(own.get().getId());
        customers.delete(own.get());
        customers.flush();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void linkAccountToCounterProfile(Long accountId, Long counterCustomerId, String expectedCounterEmail,
            String accountEmail) {
        Long onlineId = customers.findIdByAccountId(accountId)
                .orElseThrow(() -> rejected("Tài khoản chưa có hồ sơ khách"));
        // Khóa theo id tăng dần (cùng thứ tự với mọi luồng khác khóa hai hồ sơ), rồi kiểm lại dưới khóa.
        Customer online;
        Customer counter;
        if (onlineId < counterCustomerId) {
            online = customers.findByIdForUpdate(onlineId).orElse(null);
            counter = customers.findByIdForUpdate(counterCustomerId).orElse(null);
        } else {
            counter = customers.findByIdForUpdate(counterCustomerId).orElse(null);
            online = customers.findByIdForUpdate(onlineId).orElse(null);
        }
        if (online == null || counter == null || !accountId.equals(online.getAccountId())) {
            throw rejected("Không thể liên kết hồ sơ này");
        }
        if (online.getCreatedChannel() != CustomerChannel.ONLINE || data.hasData(online.getId())) {
            throw rejected("Hồ sơ online đã phát sinh dữ liệu hoặc tài khoản đã liên kết hồ sơ khác");
        }
        if (counter.getCreatedChannel() != CustomerChannel.COUNTER || counter.getAccountId() != null
                || expectedCounterEmail == null || !expectedCounterEmail.equals(counter.getEmail())) {
            throw rejected("Hồ sơ tại quầy không còn liên kết được");
        }
        addresses.deleteByCustomer(online.getId());
        customers.delete(online);
        customers.flush(); // uq_customers_account_id không deferrable: Hibernate flush UPDATE trước DELETE
        counter.linkAccount(accountId, accountEmail);
        customers.flush();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void declineLink(Long accountId) {
        lockOwn(accountId).filter(c -> c.getCreatedChannel() == CustomerChannel.ONLINE).ifPresent(c -> {
            c.clearLinkDecision();
            customers.flush();
        });
    }

    // ---------------------------------------------------------------- nội bộ

    /** Khóa hồ sơ của tài khoản; câu khóa theo tài khoản không thấy dòng thì đọc lại id bằng câu mới (như MyCustomerLookup). */
    private Optional<Customer> lockOwn(Long accountId) {
        Optional<Customer> locked = customers.findByAccountIdForUpdate(accountId);
        if (locked.isPresent()) {
            return locked;
        }
        return customers.findIdByAccountId(accountId).flatMap(customers::findByIdForUpdate)
                .filter(c -> accountId.equals(c.getAccountId()));
    }

    private static BusinessRuleViolationException rejected(String message) {
        return new BusinessRuleViolationException("BR-TK-19", message);
    }

    private static IllegalStateException missingProfile(Long accountId) {
        return new IllegalStateException("Customer account " + accountId + " has no customer profile (BR-KH-01)");
    }
}
