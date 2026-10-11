package com.petcare.module.customer.service;

import static com.petcare.module.customer.service.CustomerTestData.address;
import static com.petcare.module.customer.service.CustomerTestData.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.dto.AddressRequest;
import com.petcare.module.customer.dto.AddressResponse;
import com.petcare.module.customer.dto.UpdateAddressRequest;
import com.petcare.module.customer.entity.Address;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.CustomerChannel;
import com.petcare.module.customer.mapper.AddressMapper;
import com.petcare.module.customer.mapper.AddressMapperImpl;
import com.petcare.module.customer.repository.AddressRepository;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * UC06 — sổ địa chỉ (BR-TK-18; docs/adr/0028): khóa hồ sơ trước mọi lệnh ghi, giới hạn [CFG] đếm dưới khóa, địa chỉ đầu
 * luôn mặc định, gỡ cờ cũ bằng câu UPDATE chạy trước khi ghi cờ mới, không xóa địa chỉ mặc định khi còn địa chỉ khác,
 * địa chỉ của người khác = 404. Mỗi nhánh từ chối không gọi lệnh ghi nào. Mapper thật.
 */
class AddressBookServiceTest {

    private static final long ACCOUNT = 7L;
    private static final long CUSTOMER = 11L;
    private static final int MAX = 5;
    private static final Instant NOW = Instant.parse("2026-10-10T03:00:00Z");

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final AddressRepository addresses = mock(AddressRepository.class);
    private final MyCustomerLookup lookup = new MyCustomerLookup(customers);
    private final BranchScope branchScope = mock(BranchScope.class);
    private final SystemConfigApi configs = mock(SystemConfigApi.class);
    private final AddressMapper mapper = spy(new AddressMapperImpl());
    private final AddressBookService service = new AddressBookService(addresses, lookup, branchScope, configs, mapper,
            Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));

    @BeforeEach
    void setUp() {
        SecurityPrincipal principal = mock(SecurityPrincipal.class);
        when(principal.accountId()).thenReturn(ACCOUNT);
        when(branchScope.current()).thenReturn(principal);
        Customer owner = customer(CUSTOMER, ACCOUNT, CustomerChannel.ONLINE, "Khách", null, null, null, false);
        when(customers.findByAccountIdForUpdate(ACCOUNT)).thenReturn(Optional.of(owner));
        when(customers.findByAccountId(ACCOUNT)).thenReturn(Optional.of(owner));
        when(configs.getInt(ConfigKey.ADDRESS_MAX_PER_CUSTOMER)).thenReturn(MAX);
        when(addresses.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------------------------------------------------------------- GET

    @Test
    void listMapsEveryFieldWithoutLocking() {
        when(addresses.findOwnedBy(CUSTOMER)).thenReturn(List.of(address(3L, CUSTOMER, true),
                address(1L, CUSTOMER, false)));

        assertThat(service.listMyAddresses()).containsExactly(
                new AddressResponse(3L, "Người nhận 3", "0900000003", "Số 3", "Phường 3", "Tỉnh 3", true),
                new AddressResponse(1L, "Người nhận 1", "0900000001", "Số 1", "Phường 1", "Tỉnh 1", false));
        verify(customers, never()).findByAccountIdForUpdate(anyLong());
    }

    // ---------------------------------------------------------------- POST

    @Test
    void firstAddressIsDefaultEvenIfRequestSaysFalse() {
        when(addresses.countOwnedBy(CUSTOMER)).thenReturn(0L);

        AddressResponse response = service.addAddress(add(Boolean.FALSE));

        assertThat(response.isDefault()).isTrue();
        verify(addresses, never()).clearDefault(anyLong(), any());
    }

    @Test
    void addStripsValuesAndBlankWardBecomesNull() {
        when(addresses.countOwnedBy(CUSTOMER)).thenReturn(1L);

        AddressResponse response = service.addAddress(new AddressRequest("  An  ", "0901234567", "  12 Lê Lợi ", "  ",
                " Hà Nội ", null));

        assertThat(response).isEqualTo(new AddressResponse(null, "An", "0901234567", "12 Lê Lợi", null, "Hà Nội",
                false));
        ArgumentCaptor<Address> saved = ArgumentCaptor.forClass(Address.class);
        verify(addresses).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getCustomerId()).isEqualTo(CUSTOMER);
    }

    @Test
    void addAsDefaultClearsOldDefaultBeforeInsert() {
        when(addresses.countOwnedBy(CUSTOMER)).thenReturn(2L);

        assertThat(service.addAddress(add(Boolean.TRUE)).isDefault()).isTrue();

        InOrder order = inOrder(customers, addresses);
        order.verify(customers).findByAccountIdForUpdate(ACCOUNT);
        order.verify(addresses).countOwnedBy(CUSTOMER);
        order.verify(addresses).clearDefault(CUSTOMER, NOW);
        order.verify(addresses).saveAndFlush(any());
    }

    @Test
    void addAtLimitIs400BrTk18WithoutWrite() {
        when(addresses.countOwnedBy(CUSTOMER)).thenReturn((long) MAX);

        assertThatThrownBy(() -> service.addAddress(add(Boolean.TRUE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Sổ địa chỉ đã đủ 5 địa chỉ (BR-TK-18)");
        verify(addresses, never()).clearDefault(anyLong(), any());
        verify(addresses, never()).saveAndFlush(any());
    }

    @Test
    void limitComesFromConfigAndLowerLimitOnlyBlocksAdding() {
        when(configs.getInt(ConfigKey.ADDRESS_MAX_PER_CUSTOMER)).thenReturn(2);
        when(addresses.countOwnedBy(CUSTOMER)).thenReturn(3L);

        assertThatThrownBy(() -> service.addAddress(add(null)))
                .hasMessage("Sổ địa chỉ đã đủ 2 địa chỉ (BR-TK-18)");

        Address existing = address(4L, CUSTOMER, false);
        when(addresses.findOwned(4L, CUSTOMER)).thenReturn(Optional.of(existing));
        assertThat(service.setDefaultAddress(4L).isDefault()).isTrue();
    }

    // ---------------------------------------------------------------- PATCH

    @Test
    void updateKeepsNullsStripsValuesAndClearsBlankWard() {
        Address existing = address(4L, CUSTOMER, true);
        when(addresses.findOwned(4L, CUSTOMER)).thenReturn(Optional.of(existing));

        AddressResponse response = service.updateAddress(4L,
                new UpdateAddressRequest(" Bình ", null, null, "", null));

        assertThat(response).isEqualTo(new AddressResponse(4L, "Bình", "0900000004", "Số 4", null, "Tỉnh 4", true));
        verify(addresses).flush();
    }

    @Test
    void updateOthersAddressIs404() {
        when(addresses.findOwned(9L, CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateAddress(9L, new UpdateAddressRequest("X", null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy Địa chỉ #9");
        verify(addresses, never()).flush();
    }

    // ---------------------------------------------------------------- DELETE

    @Test
    void deleteNonDefault() {
        Address existing = address(4L, CUSTOMER, false);
        when(addresses.findOwned(4L, CUSTOMER)).thenReturn(Optional.of(existing));

        service.deleteAddress(4L);

        verify(addresses).delete(existing);
        verify(addresses).flush();
    }

    @Test
    void deleteDefaultWithOthersIs400BrTk18WithoutWrite() {
        Address existing = address(4L, CUSTOMER, true);
        when(addresses.findOwned(4L, CUSTOMER)).thenReturn(Optional.of(existing));
        when(addresses.countOwnedBy(CUSTOMER)).thenReturn(2L);

        assertThatThrownBy(() -> service.deleteAddress(4L))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        ex -> assertThat(ex.getRuleId()).isEqualTo("BR-TK-18"))
                .hasMessage("Không thể xóa địa chỉ mặc định khi còn địa chỉ khác; hãy đặt địa chỉ khác làm mặc định "
                        + "trước (BR-TK-18)");
        verify(addresses, never()).delete(any());
    }

    @Test
    void deleteOnlyDefaultEmptiesBook() {
        Address existing = address(4L, CUSTOMER, true);
        when(addresses.findOwned(4L, CUSTOMER)).thenReturn(Optional.of(existing));
        when(addresses.countOwnedBy(CUSTOMER)).thenReturn(1L);

        service.deleteAddress(4L);

        verify(addresses).delete(existing);
    }

    @Test
    void deleteMissingIs404BeforeGuard() {
        when(addresses.findOwned(9L, CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteAddress(9L)).isInstanceOf(ResourceNotFoundException.class);
        verify(addresses, never()).countOwnedBy(anyLong());
        verify(addresses, never()).delete(any());
    }

    // ---------------------------------------------------------------- set-default

    @Test
    void setDefaultClearsOldThenMarksTarget() {
        Address target = address(4L, CUSTOMER, false);
        when(addresses.findOwned(4L, CUSTOMER)).thenReturn(Optional.of(target));

        assertThat(service.setDefaultAddress(4L).isDefault()).isTrue();

        InOrder order = inOrder(customers, addresses);
        order.verify(customers).findByAccountIdForUpdate(ACCOUNT);
        order.verify(addresses).findOwned(4L, CUSTOMER);
        order.verify(addresses).clearDefault(CUSTOMER, NOW);
        order.verify(addresses).flush();
        assertThat(target.isDefaultAddress()).isTrue();
    }

    @Test
    void setDefaultOnDefaultIsNoOp() {
        when(addresses.findOwned(4L, CUSTOMER)).thenReturn(Optional.of(address(4L, CUSTOMER, true)));

        assertThat(service.setDefaultAddress(4L).isDefault()).isTrue();
        verify(addresses, never()).clearDefault(anyLong(), any());
        verify(addresses, never()).flush();
    }

    @Test
    void setDefaultOthersAddressIs404() {
        when(addresses.findOwned(9L, CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setDefaultAddress(9L)).isInstanceOf(ResourceNotFoundException.class);
        verify(addresses, never()).clearDefault(anyLong(), any());
    }

    // ---------------------------------------------------------------- dữ liệu sai, transaction

    @Test
    void customerWithoutProfileIsBadData() {
        when(customers.findByAccountIdForUpdate(ACCOUNT)).thenReturn(Optional.empty());
        when(customers.findIdByAccountId(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addAddress(add(null))).isInstanceOf(IllegalStateException.class);
        verify(addresses, never()).saveAndFlush(any());
    }

    @Test
    void transactionBoundaries() throws NoSuchMethodException {
        assertThat(tx("listMyAddresses").readOnly()).isTrue();
        for (Transactional write : List.of(tx("addAddress", AddressRequest.class),
                tx("updateAddress", Long.class, UpdateAddressRequest.class), tx("deleteAddress", Long.class),
                tx("setDefaultAddress", Long.class))) {
            assertThat(write.readOnly()).isFalse();
            assertThat(write.noRollbackFor()).isEmpty();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Transactional tx(String method, Class<?>... types) throws NoSuchMethodException {
        return AddressBookService.class.getMethod(method, types).getAnnotation(Transactional.class);
    }

    private static AddressRequest add(Boolean isDefault) {
        return new AddressRequest("An", "0901234567", "12 Lê Lợi", "Phường 1", "Hà Nội", isDefault);
    }
}
