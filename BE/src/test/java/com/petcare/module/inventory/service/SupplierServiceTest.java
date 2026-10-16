package com.petcare.module.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.inventory.dto.SupplierRequest;
import com.petcare.module.inventory.dto.SupplierResponse;
import com.petcare.module.inventory.dto.UpdateSupplierRequest;
import com.petcare.module.inventory.entity.Supplier;
import com.petcare.module.inventory.mapper.SupplierMapperImpl;
import com.petcare.module.inventory.repository.SupplierRepository;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/** BR-KO-02: khóa trước đọc sau, null giữ / rỗng xóa, xóa chỉ khi chưa có phiếu nào. */
class SupplierServiceTest {

    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final SupplierService service = new SupplierService(suppliers, new SupplierMapperImpl());

    private static Supplier supplier() {
        Supplier supplier = Supplier.create("Minh Tâm", "0281234567", "12 Nguyễn Huệ");
        ReflectionTestUtils.setField(supplier, "id", 3L);
        return supplier;
    }

    @Test
    void createStripsAndStartsActive() {
        when(suppliers.saveAndFlush(any())).thenAnswer(call -> {
            Supplier saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 9L);
            return saved;
        });

        SupplierResponse response = service.createSupplier(new SupplierRequest("  Minh Tâm ", "0281234567", "  "));

        assertThat(response.supplierId()).isEqualTo(9L);
        assertThat(response.name()).isEqualTo("Minh Tâm");
        assertThat(response.address()).isNull();
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void updateLocksFirstAndNullKeepsEverything() {
        Supplier supplier = supplier();
        when(suppliers.findByIdForUpdate(3L)).thenReturn(Optional.of(supplier));

        SupplierResponse response = service.updateSupplier(3L, new UpdateSupplierRequest(null, null, null, null));

        assertThat(response.name()).isEqualTo("Minh Tâm");
        assertThat(response.phone()).isEqualTo("0281234567");
        assertThat(response.address()).isEqualTo("12 Nguyễn Huệ");
        assertThat(response.isActive()).isTrue();
        InOrder order = inOrder(suppliers);
        order.verify(suppliers).findByIdForUpdate(3L);
        order.verify(suppliers).flush();
        verify(suppliers, never()).findById(any());
    }

    @Test
    void updateClearsAddressAndCanStopCooperating() {
        Supplier supplier = supplier();
        when(suppliers.findByIdForUpdate(3L)).thenReturn(Optional.of(supplier));

        SupplierResponse response = service.updateSupplier(3L,
                new UpdateSupplierRequest(" Tên mới ", "0912345678", "", false));

        assertThat(response.name()).isEqualTo("Tên mới");
        assertThat(response.phone()).isEqualTo("0912345678");
        assertThat(response.address()).isNull();
        assertThat(response.isActive()).isFalse();
    }

    @Test
    void updateOfAMissingSupplierIs404() {
        when(suppliers.findByIdForUpdate(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSupplier(3L, new UpdateSupplierRequest(null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteLocksThenChecksReceiptsThenDeletes() {
        when(suppliers.lockForDelete(3L)).thenReturn(Optional.of(3L));

        service.deleteSupplier(3L);

        InOrder order = inOrder(suppliers);
        order.verify(suppliers).lockForDelete(3L);
        order.verify(suppliers).hasReceipts(3L);
        order.verify(suppliers).deleteById(3L);
        order.verify(suppliers).flush();
    }

    @Test
    void deleteWithAnyReceiptIsBrKo02() {
        when(suppliers.lockForDelete(3L)).thenReturn(Optional.of(3L));
        when(suppliers.hasReceipts(3L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteSupplier(3L)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("BR-KO-02");
        verify(suppliers, never()).deleteById(any());
    }

    @Test
    void deleteOfAMissingSupplierIs404() {
        when(suppliers.lockForDelete(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteSupplier(3L)).isInstanceOf(ResourceNotFoundException.class);
        verify(suppliers, never()).hasReceipts(any());
    }

    @Test
    void writesAreOneTransactionWithoutNoRollbackFor() {
        for (String name : new String[] {"createSupplier", "updateSupplier", "deleteSupplier"}) {
            Transactional tx = null;
            for (Method method : SupplierService.class.getMethods()) {
                if (method.getName().equals(name)) {
                    tx = method.getAnnotation(Transactional.class);
                }
            }
            assertThat(tx).as(name).isNotNull();
            assertThat(tx.readOnly()).isFalse();
            assertThat(tx.noRollbackFor()).isEmpty();
        }
    }
}
