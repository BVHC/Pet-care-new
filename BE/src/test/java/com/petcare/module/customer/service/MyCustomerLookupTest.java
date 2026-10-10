package com.petcare.module.customer.service;

import static com.petcare.module.customer.service.CustomerTestData.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.CustomerChannel;
import com.petcare.module.customer.repository.CustomerRepository;

/**
 * Tìm và khóa hồ sơ của chủ token (docs/adr/0028): khóa thẳng theo {@code account_id}; câu khóa không thấy dòng (UC07
 * vừa thay hồ sơ) thì đọc lại id bằng câu mới rồi khóa theo id và kiểm lại chủ; vẫn không có → dữ liệu sai (500).
 */
class MyCustomerLookupTest {

    private static final long ACCOUNT = 7L;

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final MyCustomerLookup lookup = new MyCustomerLookup(customers);

    @Test
    void locksByAccountWhenRowIsThere() {
        Customer online = customer(11L, ACCOUNT, CustomerChannel.ONLINE, "Khách", null, null, null, false);
        when(customers.findByAccountIdForUpdate(ACCOUNT)).thenReturn(Optional.of(online));

        assertThat(lookup.lockOwner(ACCOUNT)).isSameAs(online);
        verify(customers, never()).findIdByAccountId(ACCOUNT);
        verify(customers, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void rereadsAndLocksByIdWhenProfileWasReplacedWhileWaiting() {
        Customer counter = customer(12L, ACCOUNT, CustomerChannel.COUNTER, "Khách", "0901234567", null, null, false);
        when(customers.findByAccountIdForUpdate(ACCOUNT)).thenReturn(Optional.empty());
        when(customers.findIdByAccountId(ACCOUNT)).thenReturn(Optional.of(12L));
        when(customers.findByIdForUpdate(12L)).thenReturn(Optional.of(counter));

        assertThat(lookup.lockOwner(ACCOUNT)).isSameAs(counter);
        InOrder order = inOrder(customers);
        order.verify(customers).findByAccountIdForUpdate(ACCOUNT);
        order.verify(customers).findIdByAccountId(ACCOUNT);
        order.verify(customers).findByIdForUpdate(12L);
    }

    @Test
    void rereadRowNoLongerOwnedIsBadData() {
        Customer other = customer(12L, 99L, CustomerChannel.COUNTER, "Khác", "0901234567", null, null, false);
        when(customers.findByAccountIdForUpdate(ACCOUNT)).thenReturn(Optional.empty());
        when(customers.findIdByAccountId(ACCOUNT)).thenReturn(Optional.of(12L));
        when(customers.findByIdForUpdate(12L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> lookup.lockOwner(ACCOUNT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BR-KH-01");
    }

    @Test
    void noProfileAtAllIsBadData() {
        when(customers.findByAccountIdForUpdate(ACCOUNT)).thenReturn(Optional.empty());
        when(customers.findIdByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookup.lockOwner(ACCOUNT)).isInstanceOf(IllegalStateException.class);
        verify(customers, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void readWithoutLockForGet() {
        Customer online = customer(11L, ACCOUNT, CustomerChannel.ONLINE, "Khách", null, null, null, false);
        when(customers.findByAccountId(ACCOUNT)).thenReturn(Optional.of(online));

        assertThat(lookup.requireOwner(ACCOUNT)).isSameAs(online);
        verify(customers, never()).findByAccountIdForUpdate(anyLong());
    }

    @Test
    void readWithoutProfileIsBadData() {
        when(customers.findByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookup.requireOwner(ACCOUNT)).isInstanceOf(IllegalStateException.class);
    }
}
