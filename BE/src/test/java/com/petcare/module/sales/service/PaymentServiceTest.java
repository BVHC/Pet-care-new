package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.inventory.api.StockApi;
import com.petcare.module.inventory.api.StockApi.OrderSale;
import com.petcare.module.inventory.api.StockApi.SaleLine;
import com.petcare.module.inventory.api.StockApi.Shortage;
import com.petcare.module.sales.dto.CreatePaymentRequest;
import com.petcare.module.sales.dto.PaymentResponse;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.Payment;
import com.petcare.module.sales.entity.PaymentMethod;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.fsm.OrderTransitionHandler;
import com.petcare.module.sales.repository.CashierShiftRepository;
import com.petcare.module.sales.repository.CashierShiftRepository.OpenShiftRow;
import com.petcare.module.sales.repository.PaymentRepository;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;
import com.petcare.platform.sequence.DocumentCodeGenerator;

/** Thu tiền (sales-v1 #14; Order#8; BR-TG-01…04, BR-BH-04, 06; docs/adr/0064). */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final long ME = 7L;
    private static final long BRANCH = 3L;
    private static final long CUSTOMER = 5L;
    private static final long SHIFT_ID = 40L;
    private static final long PAYMENT_ID = 900L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant NOW = Instant.parse("2026-10-21T03:00:00.123456789Z");

    @Mock
    CashierShiftRepository shifts;
    @Mock
    SalesOrderRepository orders;
    @Mock
    PaymentRepository payments;
    @Mock
    StockApi stock;
    @Mock
    DocumentCodeGenerator codes;
    @Mock
    PaymentAssembler assembler;
    @Mock
    AuditRecorder audit;
    @Mock
    BranchScope branchScope;
    @Mock
    SecurityPrincipal principal;
    @Mock
    OpenShiftRow shift;

    PaymentService service;
    PaymentResponse response;

    @BeforeEach
    void setUp() {
        service = new PaymentService(shifts, orders, payments, stock, codes, new OrderTransitionHandler(), assembler,
                audit, branchScope, Clock.fixed(NOW, ZONE));
        lenient().when(branchScope.current()).thenReturn(principal);
        lenient().when(principal.accountId()).thenReturn(ME);
        lenient().when(shift.getId()).thenReturn(SHIFT_ID);
        lenient().when(shifts.lockOpenShiftForShare(ME)).thenReturn(Optional.of(shift));
        lenient().when(stock.issueForPayment(anyLong(), anyList(), anyLong())).thenReturn(List.of());
        lenient().when(codes.next(anyString(), anyString(), any(LocalDate.class))).thenReturn("PT-2610-00001");
        lenient().when(payments.saveAndFlush(any(Payment.class))).thenAnswer(call -> {
            Payment payment = call.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
            return payment;
        });
        response = mock(PaymentResponse.class);
        lenient().when(assembler.toResponse(any(Payment.class), anyList())).thenReturn(response);
    }

    private static SalesOrder pending(long id, long customer, long branch, OrderLine... lines) {
        SalesOrder order = SalesOrder.openRetail("DH-X-" + id, branch, customer, 3L);
        ReflectionTestUtils.setField(order, "id", id);
        for (OrderLine line : lines) {
            order.addLine(line);
        }
        order.markPending(Instant.parse("2026-10-20T03:00:00Z"));
        return order;
    }

    private static OrderLine goods(long productId, int quantity, long price) {
        return OrderLine.of(OrderLineType.GOODS, null, productId, "Hàng " + productId, quantity, price, false, 3L, 3L);
    }

    private static CreatePaymentRequest request(long amount, Long... ids) {
        return new CreatePaymentRequest(List.of(ids), PaymentMethod.CASH, amount);
    }

    @Test
    void collectsCombinedOrdersInTheLockedOrder() {
        SalesOrder a = pending(10L, CUSTOMER, BRANCH, goods(20L, 2, 100));
        SalesOrder b = pending(11L, CUSTOMER, BRANCH, goods(21L, 1, 300));
        when(orders.findAllByIdForUpdate(List.of(10L, 11L))).thenReturn(List.of(a, b));

        assertThat(service.collectPayment(request(500, 10L, 11L))).isSameAs(response);

        InOrder order = inOrder(shifts, orders, stock, codes, payments, audit);
        order.verify(shifts).lockOpenShiftForShare(ME);
        order.verify(orders).findAllByIdForUpdate(List.of(10L, 11L));
        order.verify(stock).issueForPayment(eq(BRANCH), anyList(), eq(ME));
        order.verify(codes).next("payments", "PT", LocalDate.parse("2026-10-21"));
        order.verify(payments).saveAndFlush(any(Payment.class));
        order.verify(audit, times(2)).record(any(AuditEntry.class));
        assertThat(a.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(b.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(a.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(a.getPaidAt()).isEqualTo(Instant.parse("2026-10-21T03:00:00.123456Z"));
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(payments).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getAmount()).isEqualTo(500L);
        assertThat(saved.getValue().getCashierShiftId()).isEqualTo(SHIFT_ID);
        assertThat(saved.getValue().getReceivedBy()).isEqualTo(ME);
        assertThat(saved.getValue().getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(saved.getValue().getBranchId()).isEqualTo(BRANCH);
    }

    @Test
    void passesOnlyStockLinesToTheStock() {
        SalesOrder a = pending(10L, CUSTOMER, BRANCH, goods(20L, 2, 100),
                OrderLine.of(OrderLineType.SERVICE, 9L, null, "Khám", 1, 500, false, 3L, 3L));
        when(orders.findAllByIdForUpdate(List.of(10L))).thenReturn(List.of(a));

        service.collectPayment(request(700, 10L));

        verify(stock).issueForPayment(BRANCH, List.of(new OrderSale(10L, List.of(new SaleLine(20L, 2)))), ME);
    }

    @Test
    void noOpenShiftIsBrTg01BeforeTouchingOrders() {
        when(shifts.lockOpenShiftForShare(ME)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.collectPayment(request(1, 10L)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TG-01");

        verifyNoInteractions(orders, stock, codes, payments, audit);
    }

    @Test
    void missingOrderIs404() {
        when(orders.findAllByIdForUpdate(List.of(10L, 11L))).thenReturn(List.of(
                pending(10L, CUSTOMER, BRANCH, goods(20L, 1, 100))));

        assertThatThrownBy(() -> service.collectPayment(request(100, 10L, 11L)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(stock, codes, payments, audit);
    }

    @Test
    void orderOfAnotherBranchIs403() {
        SalesOrder a = pending(10L, CUSTOMER, BRANCH + 1, goods(20L, 1, 100));
        when(orders.findAllByIdForUpdate(List.of(10L))).thenReturn(List.of(a));
        doThrow(new AccessDeniedScopeException("branch:4", "branch:3")).when(branchScope).check(BRANCH + 1);

        assertThatThrownBy(() -> service.collectPayment(request(100, 10L)))
                .isInstanceOf(AccessDeniedScopeException.class);
        verifyNoInteractions(stock, codes, payments, audit);
    }

    @Test
    void orderThatIsNotPendingIs409() {
        SalesOrder open = SalesOrder.openRetail("DH-X-10", BRANCH, CUSTOMER, 3L);
        ReflectionTestUtils.setField(open, "id", 10L);
        when(orders.findAllByIdForUpdate(List.of(10L))).thenReturn(List.of(open));

        assertThatThrownBy(() -> service.collectPayment(request(0, 10L)))
                .isInstanceOf(InvalidStateTransitionException.class);
        verifyNoInteractions(stock, codes, payments, audit);
    }

    @Test
    void alreadyPaidOrderIs409() {
        SalesOrder paid = pending(10L, CUSTOMER, BRANCH, goods(20L, 1, 100));
        paid.markPaid(1L, NOW);
        when(orders.findAllByIdForUpdate(List.of(10L))).thenReturn(List.of(paid));

        assertThatThrownBy(() -> service.collectPayment(request(100, 10L)))
                .isInstanceOf(InvalidStateTransitionException.class);
        verifyNoInteractions(stock, codes, payments, audit);
    }

    @Test
    void ordersOfDifferentCustomersAreBrTg03() {
        when(orders.findAllByIdForUpdate(List.of(10L, 11L))).thenReturn(List.of(
                pending(10L, CUSTOMER, BRANCH, goods(20L, 1, 100)),
                pending(11L, CUSTOMER + 1, BRANCH, goods(20L, 1, 100))));

        assertThatThrownBy(() -> service.collectPayment(request(200, 10L, 11L)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TG-03");
        verifyNoInteractions(stock, codes, payments, audit);
    }

    @Test
    void amountThatDiffersFromTheTotalIsBrTg02() {
        when(orders.findAllByIdForUpdate(List.of(10L))).thenReturn(List.of(
                pending(10L, CUSTOMER, BRANCH, goods(20L, 1, 100))));

        assertThatThrownBy(() -> service.collectPayment(request(99, 10L)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-TG-02");
        verifyNoInteractions(stock, codes, payments, audit);
    }

    @Test
    void shortageIsBrBh04AndNothingIsWritten() {
        SalesOrder a = pending(10L, CUSTOMER, BRANCH, goods(20L, 5, 100));
        when(orders.findAllByIdForUpdate(List.of(10L))).thenReturn(List.of(a));
        when(stock.issueForPayment(eq(BRANCH), anyList(), eq(ME))).thenReturn(List.of(new Shortage(20L, 5, 2)));

        assertThatThrownBy(() -> service.collectPayment(request(500, 10L)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-BH-04")
                .hasMessageContaining("Hàng 20");

        verify(codes, never()).next(anyString(), anyString(), any());
        verify(payments, never()).saveAndFlush(any());
        verify(audit, never()).record(any());
        assertThat(a.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void zeroAmountOrderIsCollected() {
        SalesOrder free = pending(10L, CUSTOMER, BRANCH, OrderLine.of(OrderLineType.SERVICE, 9L, null,
                "Khám miễn phí", 1, 0, false, 3L, 3L));
        when(orders.findAllByIdForUpdate(List.of(10L))).thenReturn(List.of(free));

        service.collectPayment(request(0, 10L));

        assertThat(free.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(payments).saveAndFlush(any(Payment.class));
        verify(stock, never()).issueForPayment(anyLong(), org.mockito.ArgumentMatchers.argThat(
                lines -> !lines.isEmpty()), anyLong());
    }

    @Test
    void auditsEachPaidOrderWithTheSharedPayment() {
        SalesOrder a = pending(10L, CUSTOMER, BRANCH, goods(20L, 1, 100));
        SalesOrder b = pending(11L, CUSTOMER, BRANCH, goods(20L, 1, 100));
        when(orders.findAllByIdForUpdate(List.of(10L, 11L))).thenReturn(List.of(a, b));

        service.collectPayment(request(200, 10L, 11L));

        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, times(2)).record(entries.capture());
        assertThat(entries.getAllValues()).extracting(AuditEntry::action).containsOnly("ORDER_PAID");
        assertThat(entries.getAllValues()).extracting(AuditEntry::entityId).containsExactly(10L, 11L);
        assertThat(entries.getAllValues().get(0).after().toString()).contains("paymentId=" + PAYMENT_ID)
                .contains("paymentCode=PT-2610-00001").contains("method=CASH").contains("amount=100");
    }
}
