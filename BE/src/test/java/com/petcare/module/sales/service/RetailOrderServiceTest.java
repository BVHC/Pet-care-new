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

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.dto.CreateRetailOrderRequest;
import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.entity.OrderCancelType;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.fsm.OrderTransitionHandler;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;
import com.petcare.platform.sequence.DocumentCodeGenerator;

@ExtendWith(MockitoExtension.class)
class RetailOrderServiceTest {

    private static final long ME = 7L;
    private static final long BRANCH = 1L;
    private static final long CUSTOMER = 5L;
    private static final long ORDER_ID = 100L;
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");

    @Mock
    SalesOrderRepository orders;
    @Mock
    CustomerQueryApi customers;
    @Mock
    DocumentCodeGenerator codes;
    @Mock
    OrderAssembler assembler;
    @Mock
    BranchScope branchScope;
    @Mock
    SecurityPrincipal principal;

    RetailOrderService service;
    OrderResponse response;

    @BeforeEach
    void setUp() {
        service = new RetailOrderService(orders, customers, codes, new OrderTransitionHandler(), assembler,
                branchScope, Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));
        response = mock(OrderResponse.class);
        lenient().when(branchScope.current()).thenReturn(principal);
        lenient().when(principal.accountId()).thenReturn(ME);
        lenient().when(principal.branchId()).thenReturn(BRANCH);
        lenient().when(assembler.assemble(any(), any())).thenReturn(response);
        lenient().when(orders.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private SalesOrder order(OrderSource source, OrderStatus status) {
        SalesOrder order = SalesOrder.openRetail("DH-2610-00001", BRANCH, CUSTOMER, 3L);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order, "source", source);
        ReflectionTestUtils.setField(order, "status", status);
        if (source == OrderSource.VISIT) {
            ReflectionTestUtils.setField(order, "visitId", 50L);
        }
        lenient().when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private static void withLine(SalesOrder order) {
        OrderLine line = OrderLine.of(OrderLineType.GOODS, null, 20L, "Hạt cho chó", 2, 40_000, false, ME, ME);
        order.addLine(line);
    }

    // ---------------------------------------------------------------- tạo

    @Test
    void createOpensRetailOrderAtTheReceptionistsBranchWithCodeGeneratedLast() {
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.of(new CustomerContact(CUSTOMER, "An", null, null,
                null, false)));
        when(codes.next("ORDER", "DH", LocalDate.of(2026, 10, 19))).thenReturn("DH-2610-00007");

        OrderResponse result = service.createRetailOrder(new CreateRetailOrderRequest(CUSTOMER));

        assertThat(result).isSameAs(response);
        ArgumentCaptor<SalesOrder> saved = ArgumentCaptor.forClass(SalesOrder.class);
        InOrder order = inOrder(customers, codes, orders, assembler);
        order.verify(customers).findContact(CUSTOMER);
        order.verify(codes).next("ORDER", "DH", LocalDate.of(2026, 10, 19));
        order.verify(orders).saveAndFlush(saved.capture());
        order.verify(assembler).assemble(saved.getValue(), principal);
        SalesOrder created = saved.getValue();
        assertThat(created.getSource()).isEqualTo(OrderSource.RETAIL);
        assertThat(created.getStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(created.getCode()).isEqualTo("DH-2610-00007");
        assertThat(created.getBranchId()).isEqualTo(BRANCH);
        assertThat(created.getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(created.getCreatedBy()).isEqualTo(ME);
        assertThat(created.getVisitId()).isNull();
        assertThat(created.getBoardingBookingId()).isNull();
        assertThat(created.getLines()).isEmpty();
        assertThat(created.getTotalAmount()).isZero();
    }

    @Test
    void createForMissingCustomerIs404AndWritesNothing() {
        when(customers.findContact(CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRetailOrder(new CreateRetailOrderRequest(CUSTOMER)))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Không tìm thấy khách hàng #5");
        verifyNoInteractions(codes, orders);
    }

    @Test
    void createWithoutBranchIsABugNotARule() {
        when(principal.branchId()).thenReturn(null);

        assertThatThrownBy(() -> service.createRetailOrder(new CreateRetailOrderRequest(CUSTOMER)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(customers, codes, orders);
    }

    // ---------------------------------------------------------------- chốt

    @Test
    void closeMovesOpenRetailWithLinesToPending() {
        SalesOrder order = order(OrderSource.RETAIL, OrderStatus.OPEN);
        withLine(order);

        assertThat(service.closeRetailOrder(ORDER_ID)).isSameAs(response);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getPendingAt()).isEqualTo(NOW);
        InOrder inOrder = inOrder(orders, branchScope, assembler);
        inOrder.verify(orders).findByIdForUpdate(ORDER_ID);
        inOrder.verify(branchScope).check(BRANCH);
        inOrder.verify(orders).flush();
        inOrder.verify(assembler).assemble(order, principal);
    }

    @Test
    void closeWithoutLinesIsBrBh01() {
        SalesOrder order = order(OrderSource.RETAIL, OrderStatus.OPEN);

        assertThatThrownBy(() -> service.closeRetailOrder(ORDER_ID))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-BH-01");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.OPEN);
        verify(orders, never()).flush();
    }

    @Test
    void closeOrCancelAnythingButOpenRetailIs409EvenWithoutLines() {
        for (OrderSource source : OrderSource.values()) {
            for (OrderStatus status : OrderStatus.values()) {
                if (source == OrderSource.RETAIL && status == OrderStatus.OPEN) {
                    continue;
                }
                SalesOrder order = order(source, status);
                assertThatThrownBy(() -> service.closeRetailOrder(ORDER_ID)).as("%s %s", source, status)
                        .isInstanceOf(InvalidStateTransitionException.class);
                assertThatThrownBy(() -> service.cancelRetailOrder(ORDER_ID)).as("%s %s", source, status)
                        .isInstanceOf(InvalidStateTransitionException.class);
                assertThat(order.getStatus()).isEqualTo(status);
            }
        }
        verify(orders, never()).flush();
    }

    @Test
    void otherBranchIs403BeforeAnyStateCheck() {
        order(OrderSource.VISIT, OrderStatus.PAID);
        doThrow(new AccessDeniedScopeException("branch:1", "branch:2")).when(branchScope).check(BRANCH);

        assertThatThrownBy(() -> service.closeRetailOrder(ORDER_ID)).isInstanceOf(AccessDeniedScopeException.class);
        assertThatThrownBy(() -> service.cancelRetailOrder(ORDER_ID)).isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void missingOrderIs404() {
        when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.closeRetailOrder(ORDER_ID)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Không tìm thấy đơn hàng #100");
        verify(branchScope, never()).check(any());
    }

    // ---------------------------------------------------------------- hủy

    @Test
    void cancelOpenRetailIsRetailUnclosedWithoutReasonAndKeepsLines() {
        SalesOrder order = order(OrderSource.RETAIL, OrderStatus.OPEN);
        withLine(order);

        assertThat(service.cancelRetailOrder(ORDER_ID)).isSameAs(response);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.RETAIL_UNCLOSED);
        assertThat(order.getCancelReason()).isNull();
        assertThat(order.getCancelledBy()).isEqualTo(ME);
        assertThat(order.getCancelledAt()).isEqualTo(NOW);
        assertThat(order.getLines()).hasSize(1);
        assertThat(order.getTotalAmount()).isEqualTo(80_000);
        verify(orders).flush();
    }

    @Test
    void emptyOpenRetailCanBeCancelled() {
        SalesOrder order = order(OrderSource.RETAIL, OrderStatus.OPEN);

        service.cancelRetailOrder(ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void everyUseCaseIsOneTransactionWithoutNoRollbackFor() throws Exception {
        for (String name : new String[] {"createRetailOrder", "closeRetailOrder", "cancelRetailOrder"}) {
            Method method = Arrays.stream(RetailOrderService.class.getMethods())
                    .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(name).isNotNull();
            assertThat(tx.noRollbackFor()).as(name).isEmpty();
            assertThat(tx.readOnly()).as(name).isFalse();
        }
    }
}
