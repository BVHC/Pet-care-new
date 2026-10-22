package com.petcare.module.identity.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.AccountContactApi;
import com.petcare.module.identity.repository.AccountRepository;

/**
 * Cài đặt {@link AccountContactApi} (docs/adr/0052). Class {@code readOnly} <b>tham gia</b> transaction ghi của bên
 * gọi (appointment hủy lịch do phòng khám trong transaction của branch / customer) và không đổi flush / readOnly của
 * nó — khuôn {@code AppointmentLookupService} (docs/adr/0050).
 */
@Service
@Transactional(readOnly = true)
public class AccountContactService implements AccountContactApi {

    private final AccountRepository accounts;

    public AccountContactService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public List<AccountContact> findContacts(Collection<Long> accountIds) {
        Set<Long> ids = new LinkedHashSet<>(accountIds);
        return ids.isEmpty() ? List.of() : accounts.findContactsByIds(ids);
    }
}
