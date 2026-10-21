package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.dto.OrderSummaryResponse;
import com.petcare.module.sales.dto.PaymentPreviewRequest;
import com.petcare.module.sales.dto.PaymentPreviewResponse;
import com.petcare.module.sales.dto.PaymentResponse;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.Payment;
import com.petcare.module.sales.entity.PaymentMethod;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.repository.PaymentRepository;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/** Xem trước phiếu thu, Order chờ thu của khách, chi tiết phiếu thu (sales-v1 #12, #13, #15; docs/adr/0064). */
@ExtendWith(MockitoExtension.class)
class PaymentQueryServiceTest {

    private static final long BRANCH = 3L;
    private static final long CUSTOMER = 5L;

    @Mock
    SalesOrderRepository orders;
    @Mock
    PaymentRepository payments;
    @Mock
    StockQueryApi stockQuery;
    @Mock
    CustomerQueryApi customers;
    @Mock
    PaymentAssembler assembler;
    @Mock
    BranchScope branchScope;

    PaymentQueryService service;

    @BeforeEach
    void setUp() {
        service = new PaymentQueryService(orders, payments, stockQuery, customers, assembler, branchScope);
    }

    private static SalesOrder pending(long id, long customer, long branch, OrderLine... lines) {
        SalesOrder order = SalesOrder.openRetail("DH-X-" + id, branch, customer, 3L);
        ReflectionTestUtils.setField(order, "id", id);
        for (OrderLine line : lines) {
            ReflectionTestUtils.setField(line, "id", id * 100 + 1);
            order.addLine(line);
        }
        order.markPending(Instant.parse("2026-10-20T03:00:00Z"));
        return order;
    }

    private static OrderLine goods(long productId, int quantity, long price) {
        return OrderLine.of(OrderLineType.GOODS, null, productId, "Hàng " + productId, quantity, price, false, 3L, 3L);
    }

    // ---------------------------------------------------------------- #13 xem trước

    @Test
    void previewReportsTotalAndShortagesWithoutWriting() {
        SalesOrder a = pending(10L, CUSTOMER, BRANCH, goods(20L, 3, 100));
        when(orders.findAllByIdOrdered(List.of(10L))).thenReturn(List.of(a));
        when(stockQuery.availableQuantities(BRANCH, Set.of(20L))).thenReturn(Map.of(20L, 2));

        PaymentPreviewResponse preview = service.previewPayment(new PaymentPreviewRequest(List.of(10L)));

        assertThat(preview.customerId()).isEqualTo(CUSTOMER);
        assertThat(preview.branchId()).isEqualTo(BRANCH);
        assertThat(preview.totalAmount()).isEqualTo(300L);
        assertThat(preview.shortages()).hasSize(1);
        assertThat(preview.shortages().get(0).requested()).isEqualTo(3);
        assertThat(preview.shortages().get(0).available()).isEqualTo(2);
        verifyNoInteractions(payments, assembler);
    }

    @Test
    void previewWithoutStockLinesDoesNotAskTheStock() {
        SalesOrder a = pending(10L, CUSTOMER, BRANCH, OrderLine.of(OrderLineType.SERVICE, 9L, null, "Khám", 1, 500,
                false, 3L, 3L));
        when(orders.findAllByIdOrdered(List.of(10L))).thenReturn(List.of(a));

        PaymentPreviewResponse preview = service.previewPayment(new PaymentPreviewRequest(List.of(10L)));

        assertThat(preview.shortages()).isEmpty();
        assertThat(preview.totalAmount()).isEqualTo(500L);
        verifyNoInteractions(stockQuery);
    }

    @Test
    void previewErrorOrderIs404Then403ThenBrTg03ThenBrTg01() {
        when(orders.findAllByIdOrdered(List.of(10L, 11L))).thenReturn(List.of(
                pending(10L, CUSTOMER, BRANCH, goods(20L, 1, 100))));
        assertThatThrownBy(() -> service.previewPayment(new PaymentPreviewRequest(List.of(10L, 11L))))
                .isInstanceOf(ResourceNotFoundException.class);

        SalesOrder foreign = pending(12L, CUSTOMER, BRANCH + 1, goods(20L, 1, 100));
        when(orders.findAllByIdOrdered(List.of(12L))).thenReturn(List.of(foreign));
        doThrow(new AccessDeniedScopeException("branch:4", "branch:3")).when(branchScope).check(BRANCH + 1);
        assertThatThrownBy(() -> service.previewPayment(new PaymentPreviewRequest(List.of(12L))))
                .isInstanceOf(AccessDeniedScopeException.class);

        when(orders.findAllByIdOrdered(List.of(13L, 14L))).thenReturn(List.of(
                pending(13L, CUSTOMER, BRANCH, goods(20L, 1, 100)),
                pending(14L, CUSTOMER + 1, BRANCH, goods(20L, 1, 100))));
        assertThatThrownBy(() -> service.previewPayment(new PaymentPreviewRequest(List.of(13L, 14L))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TG-03");

        SalesOrder open = SalesOrder.openRetail("DH-X-15", BRANCH, CUSTOMER, 3L);
        ReflectionTestUtils.setField(open, "id", 15L);
        when(orders.findAllByIdOrdered(List.of(15L))).thenReturn(List.of(open));
        assertThatThrownBy(() -> service.previewPayment(new PaymentPreviewRequest(List.of(15L))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TG-01");
        assertThat(open.getStatus()).isEqualTo(OrderStatus.OPEN);
        verifyNoInteractions(stockQuery);
    }

    // ---------------------------------------------------------------- #12 Order chờ thu của khách

    @Test
    void payableOrdersAreScopedToTheReceptionistsBranch() {
        when(branchScope.resolve(null)).thenReturn(BRANCH);
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(
                new CustomerContact(CUSTOMER, "An", "0900000000", null, null, false)));
        SalesOrder a = pending(10L, CUSTOMER, BRANCH, goods(20L, 1, 100));
        when(orders.findByCustomerIdAndBranchIdAndStatusOrderByPendingAtAscIdAsc(CUSTOMER, BRANCH,
                OrderStatus.PENDING)).thenReturn(List.of(a));
        OrderSummaryResponse summary = mock(OrderSummaryResponse.class);
        when(assembler.toSummaries(List.of(a))).thenReturn(List.of(summary));

        assertThat(service.listPayableOrders(CUSTOMER)).containsExactly(summary);

        InOrder order = inOrder(branchScope, customers, orders);
        order.verify(branchScope).resolve(null);
        order.verify(customers).findContact(CUSTOMER);
    }

    @Test
    void unknownCustomerIs404() {
        when(branchScope.resolve(null)).thenReturn(BRANCH);
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listPayableOrders(CUSTOMER)).isInstanceOf(ResourceNotFoundException.class);
        verify(orders, never()).findByCustomerIdAndBranchIdAndStatusOrderByPendingAtAscIdAsc(any(), any(), any());
    }

    // ---------------------------------------------------------------- #15 chi tiết phiếu thu

    @Test
    void paymentDetailChecksTheBranchThenLoadsItsOrders() {
        Payment payment = Payment.record("PT-2610-00001", BRANCH, CUSTOMER, 40L, PaymentMethod.CASH, 100, 7L,
                Instant.parse("2026-10-21T03:00:00Z"));
        when(payments.findById(55L)).thenReturn(Optional.of(payment));
        SalesOrder a = pending(10L, CUSTOMER, BRANCH, goods(20L, 1, 100));
        when(orders.findByPaymentIdOrderById(55L)).thenReturn(List.of(a));
        PaymentResponse response = mock(PaymentResponse.class);
        when(assembler.toResponse(payment, List.of(a))).thenReturn(response);

        assertThat(service.getPayment(55L)).isSameAs(response);
        verify(branchScope).check(BRANCH);
    }

    @Test
    void missingPaymentIs404() {
        lenient().when(payments.findById(55L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPayment(55L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
