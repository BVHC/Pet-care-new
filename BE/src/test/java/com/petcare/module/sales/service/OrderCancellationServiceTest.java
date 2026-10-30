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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi.StaySummary;
import com.petcare.module.boarding.api.BoardingQueryApi.StayStatus;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.dto.OrderReasonRequest;
import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.entity.OrderCancelType;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.fsm.OrderTransitionHandler;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

@ExtendWith(MockitoExtension.class)
class OrderCancellationServiceTest {

    private static final long ME = 9L;
    private static final long BRANCH = 1L;
    private static final long ORDER_ID = 100L;
    private static final long BOOKING = 70L;
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final OrderReasonRequest REASON = new OrderReasonRequest("  Khách bỏ về  ");

    @Mock
    SalesOrderRepository orders;
    @Mock
    BoardingQueryApi boarding;
    @Mock
    OrderAssembler assembler;
    @Mock
    AuditRecorder audit;
    @Mock
    BranchScope branchScope;
    @Mock
    SecurityPrincipal principal;

    OrderCancellationService service;
    OrderResponse response;

    @BeforeEach
    void setUp() {
        service = new OrderCancellationService(orders, boarding, new OrderTransitionHandler(), assembler, audit,
                branchScope, Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));
        response = mock(OrderResponse.class);
        lenient().when(branchScope.current()).thenReturn(principal);
        lenient().when(principal.accountId()).thenReturn(ME);
        lenient().when(assembler.assemble(any(), any())).thenReturn(response);
    }

    private SalesOrder order(OrderSource source, OrderStatus status) {
        SalesOrder order = SalesOrder.openRetail("DH-2610-00001", BRANCH, 5L, 3L);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order, "source", source);
        ReflectionTestUtils.setField(order, "status", status);
        if (source == OrderSource.VISIT) {
            ReflectionTestUtils.setField(order, "visitId", 50L);
        }
        if (source == OrderSource.BOARDING) {
            ReflectionTestUtils.setField(order, "boardingBookingId", BOOKING);
        }
        order.addLine(OrderLine.of(OrderLineType.GOODS, null, 20L, "Hạt", 3, 50_000, false, 3L, 3L));
        lenient().when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private void stay(StayStatus status) {
        when(boarding.findBooking(BOOKING)).thenReturn(Optional.of(new StaySummary(BOOKING, BRANCH, 5L, 6L, status,
                null)));
    }

    @ParameterizedTest
    @EnumSource(value = OrderSource.class, names = {"VISIT", "RETAIL"})
    void pendingVisitOrRetailBecomesUnpaidWithStrippedReasonAndAudit(OrderSource source) {
        SalesOrder order = order(source, OrderStatus.PENDING);

        assertThat(service.cancelUnpaidOrder(ORDER_ID, REASON)).isSameAs(response);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.UNPAID);
        assertThat(order.getCancelReason()).isEqualTo("Khách bỏ về");
        assertThat(order.getCancelledBy()).isEqualTo(ME);
        assertThat(order.getCancelledAt()).isEqualTo(NOW);
        assertThat(order.getLines()).as("không đổi dòng, không đổi kho").hasSize(1);
        verifyNoInteractions(boarding);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        InOrder inOrder = inOrder(orders, branchScope, audit, assembler);
        inOrder.verify(orders).findByIdForUpdate(ORDER_ID);
        inOrder.verify(branchScope).check(BRANCH);
        inOrder.verify(orders).flush();
        inOrder.verify(audit).record(entry.capture());
        inOrder.verify(assembler).assemble(order, principal);
        AuditEntry recorded = entry.getValue();
        assertThat(recorded.action()).isEqualTo("ORDER_CANCELLED_UNPAID");
        assertThat(recorded.entityType()).isEqualTo("orders");
        assertThat(recorded.entityId()).isEqualTo(ORDER_ID);
        assertThat(recorded.before()).isEqualTo(Map.of("status", "PENDING"));
        assertThat(recorded.after()).isEqualTo(Map.of("status", "CANCELLED", "cancelType", "UNPAID",
                "totalAmount", 150_000L));
        assertThat(recorded.reason()).isEqualTo("Khách bỏ về");
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"OPEN", "PAID", "CANCELLED"})
    void notPendingIs409BeforeTheReasonCheck(OrderStatus status) {
        SalesOrder order = order(OrderSource.RETAIL, status);

        assertThatThrownBy(() -> service.cancelUnpaidOrder(ORDER_ID, new OrderReasonRequest(null)))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(order.getStatus()).isEqualTo(status);
        verifyNoInteractions(audit);
        verify(orders, never()).flush();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n"})
    void missingReasonIsBrBh05(String reason) {
        SalesOrder order = order(OrderSource.VISIT, OrderStatus.PENDING);

        assertThatThrownBy(() -> service.cancelUnpaidOrder(ORDER_ID, new OrderReasonRequest(reason)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-BH-05");
        assertThatThrownBy(() -> service.cancelUnpaidOrder(ORDER_ID, null))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-BH-05");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @EnumSource(value = StayStatus.class, names = {"CHECKED_IN", "OVERDUE"})
    void boardingOrderWhilePetInKennelIsBrBh05(StayStatus status) {
        SalesOrder order = order(OrderSource.BOARDING, OrderStatus.PENDING);
        stay(status);

        assertThatThrownBy(() -> service.cancelUnpaidOrder(ORDER_ID, REASON))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-BH-05")
                .hasMessageContaining("BR-LT-09");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @EnumSource(value = StayStatus.class, names = {"CHECKED_OUT", "BOOKED", "CANCELLED", "NO_SHOW"})
    void boardingOrderAfterThePetLeftCanBeCancelled(StayStatus status) {
        SalesOrder order = order(OrderSource.BOARDING, OrderStatus.PENDING);
        stay(status);

        service.cancelUnpaidOrder(ORDER_ID, REASON);

        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.UNPAID);
    }

    @Test
    void boardingOrderPointingNowhereIsABug() {
        order(OrderSource.BOARDING, OrderStatus.PENDING);
        when(boarding.findBooking(BOOKING)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelUnpaidOrder(ORDER_ID, REASON)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void otherBranchIs403AndMissingIs404() {
        order(OrderSource.RETAIL, OrderStatus.OPEN);
        doThrow(new AccessDeniedScopeException("branch:1", "branch:2")).when(branchScope).check(BRANCH);
        assertThatThrownBy(() -> service.cancelUnpaidOrder(ORDER_ID, REASON))
                .isInstanceOf(AccessDeniedScopeException.class);

        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelUnpaidOrder(ORDER_ID, REASON))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void oneTransactionWithoutNoRollbackFor() throws Exception {
        Transactional tx = OrderCancellationService.class
                .getMethod("cancelUnpaidOrder", Long.class, OrderReasonRequest.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.readOnly()).isFalse();
    }

    @Test
    void abortCheckoutCancelsBoardingPendingAsCheckoutAbortedAndAudits() {
        SalesOrder order = order(OrderSource.BOARDING, OrderStatus.PENDING);
        stay(StayStatus.CHECKED_IN);

        assertThat(service.abortCheckout(ORDER_ID, REASON)).isSameAs(response);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.CHECKOUT_ABORTED);
        assertThat(order.getCancelReason()).isEqualTo("Khách bỏ về");
        assertThat(order.getCancelledBy()).isEqualTo(ME);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        InOrder inOrder = inOrder(orders, branchScope, boarding, audit);
        inOrder.verify(orders).findByIdForUpdate(ORDER_ID);
        inOrder.verify(branchScope).check(BRANCH);
        inOrder.verify(boarding).findBooking(BOOKING);
        inOrder.verify(orders).flush();
        inOrder.verify(audit).record(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("ORDER_CHECKOUT_ABORTED");
        assertThat(entry.getValue().before()).isEqualTo(Map.of("status", "PENDING"));
        assertThat(((Map<?, ?>) entry.getValue().after()).get("cancelType")).isEqualTo("CHECKOUT_ABORTED");
    }

    @ParameterizedTest
    @EnumSource(value = OrderSource.class, names = {"VISIT", "RETAIL"})
    void abortCheckoutOfNonBoardingOrderIs409AndWritesNothing(OrderSource source) {
        order(source, OrderStatus.PENDING);

        assertThatThrownBy(() -> service.abortCheckout(ORDER_ID, REASON))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(orders, never()).flush();
        verifyNoInteractions(audit);
    }

    @Test
    void abortCheckoutOfPaidBoardingOrderIs409() {
        order(OrderSource.BOARDING, OrderStatus.PAID);

        assertThatThrownBy(() -> service.abortCheckout(ORDER_ID, REASON))
                .isInstanceOf(InvalidStateTransitionException.class);

        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void abortCheckoutWithoutReasonIsBrLt09AndWritesNothing(String reason) {
        SalesOrder order = order(OrderSource.BOARDING, OrderStatus.PENDING);

        assertThatThrownBy(() -> service.abortCheckout(ORDER_ID, new OrderReasonRequest(reason)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-LT-09"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(audit);
        verifyNoInteractions(boarding);
    }

    @Test
    void abortCheckoutWhenPetLeftTheKennelIsBrLt09() {
        SalesOrder order = order(OrderSource.BOARDING, OrderStatus.PENDING);
        stay(StayStatus.CHECKED_OUT);

        assertThatThrownBy(() -> service.abortCheckout(ORDER_ID, REASON))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getRuleId()).isEqualTo("BR-LT-09"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verifyNoInteractions(audit);
    }
}
