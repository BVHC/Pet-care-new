package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.AccountContactApi.AccountContact;
import com.petcare.module.identity.repository.AccountRepository;

/**
 * {@link AccountContactService} (docs/adr/0052): khử trùng đầu vào, tập rỗng không chạm DB, tham gia transaction của
 * bên gọi (readOnly, {@code REQUIRED}). SQL thật (cờ PENDING, mọi trạng thái): {@code ClinicCancellationIT}.
 */
class AccountContactServiceTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountContactService service = new AccountContactService(accounts);

    @Test
    void deduplicatesIdsAndReturnsTheRepositoryRows() {
        List<AccountContact> rows = List.of(new AccountContact(1L, "a@example.com", false));
        when(accounts.findContactsByIds(Set.of(1L, 2L))).thenReturn(rows);

        assertThat(service.findContacts(List.of(1L, 2L, 1L))).isSameAs(rows);
        verify(accounts).findContactsByIds(Set.of(1L, 2L));
    }

    @Test
    void emptyInputTouchesNoDatabase() {
        assertThat(service.findContacts(List.of())).isEmpty();
        verifyNoInteractions(accounts);
    }

    @Test
    void joinsTheCallerTransactionReadOnly() {
        Transactional tx = AccountContactService.class.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
    }
}
