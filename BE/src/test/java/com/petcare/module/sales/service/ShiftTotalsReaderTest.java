package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.petcare.module.sales.entity.PaymentMethod;
import com.petcare.module.sales.entity.ShiftTotals;
import com.petcare.module.sales.repository.PaymentRepository;
import com.petcare.module.sales.repository.PaymentRepository.MethodTotal;

class ShiftTotalsReaderTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ShiftTotalsReader reader = new ShiftTotalsReader(payments);

    private static MethodTotal total(PaymentMethod method, long amount) {
        MethodTotal row = mock(MethodTotal.class);
        when(row.getMethod()).thenReturn(method);
        when(row.getTotal()).thenReturn(amount);
        return row;
    }

    @Test
    void sumsEachMethodSeparately() {
        List<MethodTotal> rows = List.of(total(PaymentMethod.CASH, 500_000), total(PaymentMethod.TRANSFER, 200_000));
        when(payments.totalsByMethod(5L)).thenReturn(rows);

        assertThat(reader.read(5L)).isEqualTo(new ShiftTotals(500_000, 200_000));
    }

    @Test
    void missingMethodCountsAsZero() {
        List<MethodTotal> onlyTransfer = List.of(total(PaymentMethod.TRANSFER, 70_000));
        when(payments.totalsByMethod(5L)).thenReturn(onlyTransfer);
        assertThat(reader.read(5L)).isEqualTo(new ShiftTotals(0, 70_000));

        when(payments.totalsByMethod(6L)).thenReturn(List.of());
        assertThat(reader.read(6L)).isEqualTo(new ShiftTotals(0, 0));
    }
}
