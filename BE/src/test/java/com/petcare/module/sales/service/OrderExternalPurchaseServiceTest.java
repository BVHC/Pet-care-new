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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.module.visit.api.PrescriptionApi;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

@ExtendWith(MockitoExtension.class)
class OrderExternalPurchaseServiceTest {

    private static final long ME = 9L;
    private static final long BRANCH = 1L;
    private static final long ORDER_ID = 100L;
    private static final long DRUG_LINE = 201L;
    private static final long OTHER_LINE = 202L;
    private static final long DRUG = 20L;

    @Mock
    SalesOrderRepository orders;
    @Mock
    StockQueryApi stock;
    @Mock
    PrescriptionApi prescriptions;
    @Mock
    OrderAssembler assembler;
    @Mock
    AuditRecorder audit;
    @Mock
    BranchScope branchScope;
    @Mock
    SecurityPrincipal principal;

    OrderExternalPurchaseService service;
    OrderResponse response;

    @BeforeEach
    void setUp() {
        service = new OrderExternalPurchaseService(orders, stock, prescriptions, assembler, audit, branchScope);
        response = mock(OrderResponse.class);
        lenient().when(branchScope.current()).thenReturn(principal);
        lenient().when(principal.accountId()).thenReturn(ME);
        lenient().when(assembler.assemble(any(), any())).thenReturn(response);
        lenient().when(orders.findBranchIdById(ORDER_ID)).thenReturn(Optional.of(BRANCH));
    }

    /** Order VISIT {@code PENDING} có một dòng thuốc 3 x 50.000 (id 201) và một dòng dịch vụ 100.000 (id 202). */
    private SalesOrder pendingVisitOrder(int drugQuantity) {
        SalesOrder order = SalesOrder.openRetail("DH-2610-00001", BRANCH, 5L, 3L);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order, "source", OrderSource.VISIT);
        ReflectionTestUtils.setField(order, "visitId", 50L);
        order.addLine(line(DRUG_LINE, OrderLineType.DRUG, DRUG, drugQuantity, 50_000));
        order.addLine(line(OTHER_LINE, OrderLineType.SERVICE, null, 1, 100_000));
        ReflectionTestUtils.setField(order, "status", OrderStatus.PENDING);
        lenient().when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private static OrderLine line(long id, OrderLineType type, Long productId, int quantity, long price) {
        OrderLine line = OrderLine.of(type, type == OrderLineType.SERVICE ? 7L : null, productId, "Dòng " + id,
                quantity, price, false, 3L, 3L);
        ReflectionTestUtils.setField(line, "id", id);
        return line;
    }

    private void available(int quantity) {
        when(stock.availableQuantities(BRANCH, Set.of(DRUG))).thenReturn(Map.of(DRUG, quantity));
    }

    @Test
    void movesTheLineInLockThenMarkThenRemoveOrderWithAudit() {
        SalesOrder order = pendingVisitOrder(3);
        available(1);

        assertThat(service.moveDrugLineToExternalPurchase(ORDER_ID, DRUG_LINE)).isSameAs(response);

        assertThat(order.getLines()).extracting(OrderLine::getId).containsExactly(OTHER_LINE);
        assertThat(order.getTotalAmount()).isEqualTo(100_000);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        InOrder inOrder = inOrder(orders, branchScope, stock, prescriptions, audit, assembler);
        inOrder.verify(orders).findBranchIdById(ORDER_ID);
        inOrder.verify(branchScope).check(BRANCH);
        inOrder.verify(orders).findByIdForUpdate(ORDER_ID);
        inOrder.verify(stock).availableQuantities(BRANCH, Set.of(DRUG));
        inOrder.verify(prescriptions).markExternalPurchaseAtPayment(DRUG_LINE, ME);
        inOrder.verify(orders).flush();
        inOrder.verify(audit).record(entry.capture());
        inOrder.verify(assembler).assemble(order, principal);
        AuditEntry recorded = entry.getValue();
        assertThat(recorded.action()).isEqualTo("ORDER_LINE_MOVED_EXTERNAL");
        assertThat(recorded.entityType()).isEqualTo("orders");
        assertThat(recorded.entityId()).isEqualTo(ORDER_ID);
        assertThat(recorded.before()).isEqualTo(Map.of("lineId", DRUG_LINE, "productId", DRUG, "quantity", 3,
                "lineTotal", 150_000L, "totalAmount", 250_000L));
        assertThat(recorded.after()).isEqualTo(Map.of("totalAmount", 100_000L, "externalReason",
                "OUT_OF_STOCK_AT_PAYMENT"));
    }

    @Test
    void otherBranchIs403BeforeAnyLock() {
        doThrow(new AccessDeniedScopeException("branch:1", "branch:2")).when(branchScope).check(BRANCH);

        assertThatThrownBy(() -> service.moveDrugLineToExternalPurchase(ORDER_ID, DRUG_LINE))
                .isInstanceOf(AccessDeniedScopeException.class);

        verify(orders, never()).findByIdForUpdate(any());
        verifyNoInteractions(prescriptions, audit);
    }

    @Test
    void missingOrderIs404BeforeAnyLock() {
        when(orders.findBranchIdById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moveDrugLineToExternalPurchase(ORDER_ID, DRUG_LINE))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(orders, never()).findByIdForUpdate(any());
        verifyNoInteractions(prescriptions, audit);
    }

    @Test
    void lineOfAnotherOrderIs404AndWritesNothing() {
        pendingVisitOrder(3);

        assertThatThrownBy(() -> service.moveDrugLineToExternalPurchase(ORDER_ID, 999L))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(prescriptions, audit, stock);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"OPEN", "PAID", "CANCELLED"})
    void anOrderThatIsNotPendingIs409AndWritesNothing(OrderStatus status) {
        SalesOrder order = pendingVisitOrder(3);
        ReflectionTestUtils.setField(order, "status", status);

        assertThatThrownBy(() -> service.moveDrugLineToExternalPurchase(ORDER_ID, DRUG_LINE))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertThat(order.getLines()).hasSize(2);
        verifyNoInteractions(prescriptions, audit, stock);
    }

    @Test
    void aServiceLineIs400BrBh04() {
        pendingVisitOrder(3);

        assertThatThrownBy(() -> service.moveDrugLineToExternalPurchase(ORDER_ID, OTHER_LINE))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getMessage()).contains("BR-BH-04"));

        verifyNoInteractions(prescriptions, audit, stock);
    }

    @Test
    void aDrugLineOfARetailOrderIs400BrBh04() {
        SalesOrder order = pendingVisitOrder(3);
        ReflectionTestUtils.setField(order, "source", OrderSource.RETAIL);

        assertThatThrownBy(() -> service.moveDrugLineToExternalPurchase(ORDER_ID, DRUG_LINE))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getMessage()).contains("BR-BH-04"));

        verifyNoInteractions(prescriptions, audit, stock);
    }

    @Test
    void enoughStockIs400BrBh04AndWritesNothing() {
        SalesOrder order = pendingVisitOrder(3);
        available(3);

        assertThatThrownBy(() -> service.moveDrugLineToExternalPurchase(ORDER_ID, DRUG_LINE))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getMessage()).contains("BR-BH-04"));

        assertThat(order.getLines()).hasSize(2);
        verifyNoInteractions(prescriptions, audit);
    }

    @Test
    void stockThatCoversThisOrderAloneIsRefusedAndAdvisesPayingOrdersSeparately() {
        pendingVisitOrder(6);
        available(10);

        assertThatThrownBy(() -> service.moveDrugLineToExternalPurchase(ORDER_ID, DRUG_LINE))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getMessage()).contains("thu riêng từng Order"));

        verifyNoInteractions(prescriptions, audit);
    }

    @Test
    void twoLinesOfTheSameDrugAreShortTogetherSoEitherMayMove() {
        SalesOrder order = pendingVisitOrder(3);
        order.addLine(line(203L, OrderLineType.DRUG, DRUG, 3, 50_000));
        available(5);

        service.moveDrugLineToExternalPurchase(ORDER_ID, DRUG_LINE);

        assertThat(order.getLines()).extracting(OrderLine::getId).containsExactly(OTHER_LINE, 203L);
        verify(prescriptions).markExternalPurchaseAtPayment(DRUG_LINE, ME);
        assertThat(List.of(order.getTotalAmount())).containsExactly(250_000L);
    }
}
