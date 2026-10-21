package com.petcare.module.customer.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.CustomerChannel;
import com.petcare.module.customer.repository.CustomerRepository;

/**
 * {@link CustomerQueryApi} thật (thay {@code CustomerQueryApiPlaceholder} — trả nợ D010; docs/adr/0041). Chỉ đọc, không
 * khóa; identity và UC07 kiểm lại dưới khóa khi ghi ({@code CustomerApiService}).
 */
@Service
public class CustomerQueryService implements CustomerQueryApi {

    private final CustomerRepository customers;
    private final CustomerDataProbe data;

    public CustomerQueryService(CustomerRepository customers, CustomerDataProbe data) {
        this.customers = customers;
        this.data = data;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerContact> findContact(Long customerId) {
        return customers.findById(customerId).map(CustomerQueryService::toContact);
    }

    private static CustomerContact toContact(Customer c) {
        return new CustomerContact(c.getId(), c.getFullName(), c.getPhone(), c.getEmail(), c.getAccountId(),
                c.isLinkDecisionPending());
    }

    /** Một câu cho cả lô; id không có thì bỏ qua (06 §6). */
    @Override
    @Transactional(readOnly = true)
    public List<CustomerContact> findContacts(Collection<Long> customerIds) {
        if (customerIds.isEmpty()) {
            return List.of();
        }
        return customers.findAllById(customerIds.stream().distinct().toList()).stream().map(CustomerQueryService::toContact)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findCustomerIdByAccountId(Long accountId) {
        return customers.findIdByAccountId(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LinkCandidate> findLinkCandidates(String phone) {
        if (phone == null || phone.isBlank()) {
            return List.of();
        }
        return customers.findUnlinkedCounterProfilesByPhone(phone).stream()
                .map(c -> new LinkCandidate(c.getId(), Maskers.fullName(c.getFullName()), c.getEmail() != null))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OnlineProfileLinkability checkOnlineProfileLinkable(Long accountId) {
        Customer own = customers.findByAccountId(accountId).orElseThrow(() -> new IllegalStateException(
                "Customer account " + accountId + " has no customer profile (BR-KH-01)"));
        if (own.getCreatedChannel() != CustomerChannel.ONLINE) {
            return OnlineProfileLinkability.ALREADY_LINKED;
        }
        return data.hasData(own.getId()) ? OnlineProfileLinkability.HAS_DATA : OnlineProfileLinkability.LINKABLE;
    }
}
