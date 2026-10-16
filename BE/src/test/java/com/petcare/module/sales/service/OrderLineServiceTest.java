package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.dto.AddOrderLineRequest;
import com.petcare.module.sales.dto.AddOrderLineRequest.AddableLineType;
import com.petcare.module.sales.dto.AddOrderLineResult;
import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.dto.UpdateOrderLineRequest;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.exception.InvalidOrderLineRequestException;
import com.petcare.module.sales.exception.OrderLineAccessDeniedException;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.VisitStatus;
import com.petcare.module.visit.api.VisitQueryApi.VisitSummary;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

@ExtendWith(MockitoExtension.class)
class OrderLineServiceTest {

    private static final long ME = 7L;
    private static final long OTHER = 8L;
    private static final long ORDER_ID = 100L;
    private static final long VISIT = 50L;
    private static final long BRANCH = 1L;

    @Mock
    SalesOrderRepository orders;
    @Mock
    CatalogQueryApi catalog;
    @Mock
    BranchQueryApi branches;
    @Mock
    StockQueryApi stock;
    @Mock
    VisitQueryApi visits;
    @Mock
    OrderAssembler assembler;
    @Mock
    BranchScope branchScope;
    @Mock
    SecurityPrincipal principal;

    OrderLineService service;
    OrderResponse response;

    @BeforeEach
    void setUp() {
        service = new OrderLineService(orders, catalog, branches, stock, visits, assembler, branchScope);
        response = org.mockito.Mockito.mock(OrderResponse.class);
        lenient().when(branchScope.current()).thenReturn(principal);
        lenient().when(principal.accountId()).thenReturn(ME);
        lenient().when(assembler.assemble(any(), any())).thenReturn(response);
    }

    private void actingAs(String role) {
        lenient().when(principal.role()).thenReturn(role);
    }

    private SalesOrder visitOrder(OrderStatus status) {
        SalesOrder order = SalesOrder.openForVisit("DH-2610-00001", BRANCH, 2L, VISIT, 3L);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order, "status", status);
        lenient().when(orders.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private SalesOrder retailOrder(OrderStatus status) {
        SalesOrder order = visitOrder(status);
        ReflectionTestUtils.setField(order, "source", OrderSource.RETAIL);
        ReflectionTestUtils.setField(order, "visitId", null);
        return order;
    }

    private static OrderLine existing(SalesOrder order, long id, OrderLineType type, long owner, boolean auto,
            int quantity, long price) {
        boolean service = type == OrderLineType.SERVICE;
        OrderLine line = OrderLine.of(type, service ? 10L : null, service ? null : 20L, "Dòng", quantity, price, auto,
                owner, owner);
        ReflectionTestUtils.setField(line, "id", id);
        order.addLine(line);
        return line;
    }

    private static ProductInfo product(ProductType type, boolean prescription, boolean active, long price) {
        return new ProductInfo(20L, "Hạt cho chó", type, prescription, false, null, price, active);
    }

    private static ServiceInfo svc(ServiceGroup group, boolean active) {
        return new ServiceInfo(10L, "Dịch vụ", group, group == ServiceGroup.MEDICAL ? MedicalType.EXAM : null, 150_000,
                active);
    }

    private void visitAssignedTo(Long assignee) {
        when(visits.findVisit(VISIT)).thenReturn(Optional.of(new VisitSummary(VISIT, BRANCH, 2L, 3L,
                ServiceGroup.MEDICAL, VisitStatus.IN_PROGRESS, assignee)));
    }

    private static AddOrderLineRequest goods(int quantity) {
        return new AddOrderLineRequest(AddableLineType.GOODS, null, 20L, quantity);
    }

    private static AddOrderLineRequest serviceReq() {
        return new AddOrderLineRequest(AddableLineType.SERVICE, 10L, null, 1);
    }

    // ---------------------------------------------------------------- thêm: hình dạng và vai trò

    @Test
    void shapeMismatchIsRefusedBeforeAnyRead() {
        actingAs("RECEPTIONIST");
        for (AddOrderLineRequest bad : new AddOrderLineRequest[] {
                new AddOrderLineRequest(AddableLineType.SERVICE, null, null, 1),
                new AddOrderLineRequest(AddableLineType.SERVICE, 10L, 20L, 1),
                new AddOrderLineRequest(AddableLineType.GOODS, null, null, 1),
                new AddOrderLineRequest(AddableLineType.GOODS, 10L, 20L, 1)}) {
            assertThatThrownBy(() -> service.addLine(ORDER_ID, bad)).isInstanceOf(InvalidOrderLineRequestException.class);
        }
        verifyNoInteractions(orders, catalog, branchScope);
    }

    @Test
    void wrongRoleForTheLineTypeIsForbiddenBeforeTheOrderIsLocked() {
        for (String role : new String[] {"VET", "CARETAKER", "BRANCH_MANAGER"}) {
            actingAs(role);
            assertThatThrownBy(() -> service.addLine(ORDER_ID, goods(1))).as(role + " + GOODS")
                    .isInstanceOf(OrderLineAccessDeniedException.class);
        }
        for (String role : new String[] {"RECEPTIONIST", "BRANCH_MANAGER"}) {
            actingAs(role);
            assertThatThrownBy(() -> service.addLine(ORDER_ID, serviceReq())).as(role + " + SERVICE")
                    .isInstanceOf(OrderLineAccessDeniedException.class);
        }
        verify(orders, never()).findByIdForUpdate(any());
        verifyNoInteractions(catalog);
    }

    // ---------------------------------------------------------------- thêm hàng bán lẻ

    @Test
    void receptionistAddsGoodsAfterLockingTheOrderAndSnapshotsThePrice() {
        actingAs("RECEPTIONIST");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        when(catalog.findProductForShare(20L)).thenReturn(Optional.of(product(ProductType.GOODS, false, true, 40_000)));
        when(stock.availableQuantity(BRANCH, 20L)).thenReturn(10);

        AddOrderLineResult result = service.addLine(ORDER_ID, goods(3));

        InOrder inOrder = inOrder(orders, branchScope, catalog, stock);
        inOrder.verify(orders).findByIdForUpdate(ORDER_ID);
        inOrder.verify(branchScope).check(BRANCH);
        inOrder.verify(catalog).findProductForShare(20L);
        inOrder.verify(orders).flush();
        inOrder.verify(stock).availableQuantity(BRANCH, 20L);
        OrderLine line = order.getLines().get(0);
        assertThat(line.getLineType()).isEqualTo(OrderLineType.GOODS);
        assertThat(line.getUnitPrice()).isEqualTo(40_000);
        assertThat(line.getLineTotal()).isEqualTo(120_000);
        assertThat(line.getAddedBy()).isEqualTo(ME);
        assertThat(line.getOwnerAccountId()).isEqualTo(ME);
        assertThat(line.isAutoGenerated()).isFalse();
        assertThat(order.getTotalAmount()).isEqualTo(120_000);
        assertThat(result.stockWarning()).as("đủ tồn thì không cảnh báo").isNull();
        assertThat(result.order()).isSameAs(response);
    }

    @Test
    void shortStockWarnsWithTheWholeOrderQuantityButStillAdds() {
        actingAs("RECEPTIONIST");
        SalesOrder order = retailOrder(OrderStatus.OPEN);
        existing(order, 1, OrderLineType.GOODS, OTHER, false, 4, 40_000);
        when(catalog.findProductForShare(20L)).thenReturn(Optional.of(product(ProductType.GOODS, false, true, 40_000)));
        when(stock.availableQuantity(BRANCH, 20L)).thenReturn(5);

        AddOrderLineResult result = service.addLine(ORDER_ID, goods(3));

        assertThat(order.getLines()).hasSize(2);
        assertThat(result.stockWarning()).isNotNull();
        assertThat(result.stockWarning().productId()).isEqualTo(20L);
        assertThat(result.stockWarning().requested()).as("4 đã có + 3 vừa thêm").isEqualTo(7);
        assertThat(result.stockWarning().available()).isEqualTo(5);
    }

    @Test
    void exactlyEnoughStockIsNotAWarning() {
        actingAs("RECEPTIONIST");
        visitOrder(OrderStatus.OPEN);
        when(catalog.findProductForShare(20L)).thenReturn(Optional.of(product(ProductType.GOODS, false, true, 1)));
        when(stock.availableQuantity(BRANCH, 20L)).thenReturn(3);

        assertThat(service.addLine(ORDER_ID, goods(3)).stockWarning()).isNull();
    }

    @Test
    void goodsCanBeAddedWhileThePendingOrderWaitsForPayment() {
        actingAs("RECEPTIONIST");
        SalesOrder order = visitOrder(OrderStatus.PENDING);
        when(catalog.findProductForShare(20L)).thenReturn(Optional.of(product(ProductType.GOODS, false, true, 10)));
        when(stock.availableQuantity(BRANCH, 20L)).thenReturn(9);

        service.addLine(ORDER_ID, goods(1));

        assertThat(order.getLines()).hasSize(1);
    }

    @Test
    void prescriptionInactiveAndVaccineProductsAreRefusedAndNothingIsAdded() {
        actingAs("RECEPTIONIST");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        when(catalog.findProductForShare(20L)).thenReturn(
                Optional.of(product(ProductType.DRUG, true, true, 10)),
                Optional.of(product(ProductType.GOODS, false, false, 10)),
                Optional.of(product(ProductType.VACCINE, false, true, 10)));

        assertThatThrownBy(() -> service.addLine(ORDER_ID, goods(1)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getMessage()).contains("BR-SP-01"));
        assertThatThrownBy(() -> service.addLine(ORDER_ID, goods(1)))
                .isInstanceOf(InvalidOrderLineRequestException.class);
        assertThatThrownBy(() -> service.addLine(ORDER_ID, goods(1)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getMessage()).contains("BR-KB-04"));

        assertThat(order.getLines()).isEmpty();
        verify(orders, never()).flush();
        verifyNoInteractions(stock);
    }

    @Test
    void nonPrescriptionDrugIsSoldAsGoods() {
        actingAs("RECEPTIONIST");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        when(catalog.findProductForShare(20L)).thenReturn(Optional.of(product(ProductType.DRUG, false, true, 10)));
        when(stock.availableQuantity(BRANCH, 20L)).thenReturn(5);

        service.addLine(ORDER_ID, goods(1));

        assertThat(order.getLines().get(0).getLineType()).isEqualTo(OrderLineType.GOODS);
    }

    @Test
    void unknownProductAndUnknownOrderAreNotFound() {
        actingAs("RECEPTIONIST");
        visitOrder(OrderStatus.OPEN);
        when(catalog.findProductForShare(20L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addLine(ORDER_ID, goods(1))).isInstanceOf(ResourceNotFoundException.class);

        when(orders.findByIdForUpdate(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addLine(999L, goods(1))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void ordersThatAreFinishedRefuseEveryNewLineBeforeTheCatalogIsRead() {
        actingAs("RECEPTIONIST");
        for (OrderStatus status : new OrderStatus[] {OrderStatus.PAID, OrderStatus.CANCELLED}) {
            visitOrder(status);
            assertThatThrownBy(() -> service.addLine(ORDER_ID, goods(1))).as(status.name())
                    .isInstanceOf(InvalidStateTransitionException.class);
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void anotherBranchIsForbiddenAndNothingIsWritten() {
        actingAs("RECEPTIONIST");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        doThrow(new AccessDeniedScopeException("branch:1", "branch:2")).when(branchScope).check(BRANCH);

        assertThatThrownBy(() -> service.addLine(ORDER_ID, goods(1))).isInstanceOf(AccessDeniedScopeException.class);

        assertThat(order.getLines()).isEmpty();
        verifyNoInteractions(catalog);
    }

    // ---------------------------------------------------------------- thêm dịch vụ

    @Test
    void assignedVetAddsAMedicalServiceOwnedByThem() {
        actingAs("VET");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        visitAssignedTo(ME);
        when(catalog.findService(10L)).thenReturn(Optional.of(svc(ServiceGroup.MEDICAL, true)));
        when(branches.isServiceEnabled(BRANCH, 10L)).thenReturn(true);

        AddOrderLineResult result = service.addLine(ORDER_ID, serviceReq());

        OrderLine line = order.getLines().get(0);
        assertThat(line.getLineType()).isEqualTo(OrderLineType.SERVICE);
        assertThat(line.getServiceId()).isEqualTo(10L);
        assertThat(line.getUnitPrice()).isEqualTo(150_000);
        assertThat(line.getOwnerAccountId()).isEqualTo(ME);
        assertThat(line.isAutoGenerated()).isFalse();
        assertThat(result.stockWarning()).isNull();
        verifyNoInteractions(stock);
    }

    @Test
    void caretakerAddsGroomingButNotMedical() {
        actingAs("CARETAKER");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        visitAssignedTo(ME);
        when(catalog.findService(10L)).thenReturn(Optional.of(svc(ServiceGroup.GROOMING, true)),
                Optional.of(svc(ServiceGroup.MEDICAL, true)));
        when(branches.isServiceEnabled(BRANCH, 10L)).thenReturn(true);

        service.addLine(ORDER_ID, serviceReq());
        assertThat(order.getLines()).hasSize(1);

        assertThatThrownBy(() -> service.addLine(ORDER_ID, serviceReq()))
                .isInstanceOf(OrderLineAccessDeniedException.class);
        assertThat(order.getLines()).hasSize(1);
    }

    @Test
    void vetNotAssignedToTheVisitOrOnANonVisitOrderIsForbidden() {
        actingAs("VET");
        visitOrder(OrderStatus.OPEN);
        visitAssignedTo(OTHER);
        when(catalog.findService(10L)).thenReturn(Optional.of(svc(ServiceGroup.MEDICAL, true)));
        assertThatThrownBy(() -> service.addLine(ORDER_ID, serviceReq()))
                .isInstanceOf(OrderLineAccessDeniedException.class);

        retailOrder(OrderStatus.OPEN);
        assertThatThrownBy(() -> service.addLine(ORDER_ID, serviceReq()))
                .as("Order bán lẻ không có Visit").isInstanceOf(OrderLineAccessDeniedException.class);
    }

    @Test
    void waitingVisitWithoutAssigneeIsForbiddenToEveryVet() {
        actingAs("VET");
        visitOrder(OrderStatus.OPEN);
        visitAssignedTo(null);
        when(catalog.findService(10L)).thenReturn(Optional.of(svc(ServiceGroup.MEDICAL, true)));

        assertThatThrownBy(() -> service.addLine(ORDER_ID, serviceReq()))
                .isInstanceOf(OrderLineAccessDeniedException.class);
    }

    @Test
    void servicesCannotBeAddedOnceThePendingOrderIsLocked() {
        actingAs("VET");
        visitOrder(OrderStatus.PENDING);
        visitAssignedTo(ME);
        when(catalog.findService(10L)).thenReturn(Optional.of(svc(ServiceGroup.MEDICAL, true)));

        assertThatThrownBy(() -> service.addLine(ORDER_ID, serviceReq()))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void inactiveOrDisabledServiceIsBadRequestAndMissingServiceIsNotFound() {
        actingAs("VET");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        visitAssignedTo(ME);
        when(catalog.findService(10L)).thenReturn(Optional.of(svc(ServiceGroup.MEDICAL, false)),
                Optional.of(svc(ServiceGroup.MEDICAL, true)), Optional.empty());
        when(branches.isServiceEnabled(BRANCH, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.addLine(ORDER_ID, serviceReq()))
                .isInstanceOf(InvalidOrderLineRequestException.class);
        assertThatThrownBy(() -> service.addLine(ORDER_ID, serviceReq()))
                .isInstanceOf(InvalidOrderLineRequestException.class);
        assertThatThrownBy(() -> service.addLine(ORDER_ID, serviceReq()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(order.getLines()).isEmpty();
    }

    // ---------------------------------------------------------------- sửa

    @Test
    void emptyPatchIsRefusedBeforeTheLock() {
        actingAs("VET");

        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 1L, new UpdateOrderLineRequest(null, null)))
                .isInstanceOf(InvalidOrderLineRequestException.class);

        verifyNoInteractions(orders);
    }

    @Test
    void ownerLowersTheQuantityAndTheTotalFollows() {
        actingAs("VET");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        existing(order, 1, OrderLineType.SERVICE, ME, false, 3, 100_000);

        service.updateLine(ORDER_ID, 1L, new UpdateOrderLineRequest(2, null));

        assertThat(order.getLines().get(0).getQuantity()).isEqualTo(2);
        assertThat(order.getTotalAmount()).isEqualTo(200_000);
        InOrder inOrder = inOrder(orders);
        inOrder.verify(orders).findByIdForUpdate(ORDER_ID);
        inOrder.verify(orders).flush();
    }

    @Test
    void increasingTheQuantityIsRefusedAndKeepsTheLine() {
        actingAs("RECEPTIONIST");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        existing(order, 1, OrderLineType.GOODS, ME, false, 2, 10_000);

        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 1L, new UpdateOrderLineRequest(3, null)))
                .isInstanceOf(InvalidOrderLineRequestException.class);

        assertThat(order.getLines().get(0).getQuantity()).isEqualTo(2);
        assertThat(order.getTotalAmount()).isEqualTo(20_000);
    }

    @Test
    void unchangedQuantityIsAccepted() {
        actingAs("RECEPTIONIST");
        SalesOrder order = visitOrder(OrderStatus.PENDING);
        existing(order, 1, OrderLineType.GOODS, OTHER, false, 2, 10_000);

        service.updateLine(ORDER_ID, 1L, new UpdateOrderLineRequest(2, null));

        assertThat(order.getTotalAmount()).isEqualTo(20_000);
    }

    @Test
    void policyDenialsBecomeTheContractErrors() {
        actingAs("VET");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        existing(order, 1, OrderLineType.SERVICE, OTHER, false, 1, 100);
        existing(order, 2, OrderLineType.VACCINE, ME, false, 1, 100);
        existing(order, 3, OrderLineType.DRUG, ME, false, 1, 100);
        UpdateOrderLineRequest patch = new UpdateOrderLineRequest(1, null);

        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 1L, patch))
                .isInstanceOfSatisfying(OrderLineAccessDeniedException.class,
                        e -> assertThat(e.clientMessage()).contains("BR-BH-02"));
        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 2L, patch))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getMessage()).contains("BR-BH-02"));
        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 3L, patch))
                .isInstanceOf(BusinessRuleViolationException.class);

        ReflectionTestUtils.setField(order, "status", OrderStatus.PENDING);
        existing(order, 4, OrderLineType.SERVICE, ME, false, 1, 100);
        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 4L, patch))
                .isInstanceOf(OrderLineAccessDeniedException.class);

        ReflectionTestUtils.setField(order, "status", OrderStatus.PAID);
        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 4L, patch))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void missingLineIsNotFound() {
        actingAs("VET");
        visitOrder(OrderStatus.OPEN);

        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 99L, new UpdateOrderLineRequest(1, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deleteLine(ORDER_ID, 99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assignedVetReplacesTheAutoServiceAndThePriceIsSnapshottedAgain() {
        actingAs("VET");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        OrderLine auto = existing(order, 1, OrderLineType.SERVICE, ME, true, 1, 150_000);
        ServiceInfo tiem = new ServiceInfo(11L, "Tiêm phòng", ServiceGroup.MEDICAL, MedicalType.VACCINE, 220_000, true);
        when(catalog.findService(11L)).thenReturn(Optional.of(tiem));
        when(branches.isServiceEnabled(BRANCH, 11L)).thenReturn(true);

        service.updateLine(ORDER_ID, 1L, new UpdateOrderLineRequest(null, 11L));

        assertThat(auto.getServiceId()).isEqualTo(11L);
        assertThat(auto.getDescription()).isEqualTo("Tiêm phòng");
        assertThat(auto.getUnitPrice()).isEqualTo(220_000);
        assertThat(order.getTotalAmount()).isEqualTo(220_000);
    }

    @Test
    void replaceServiceRefusesManualLinesWrongGroupAndNonVisitOrders() {
        actingAs("VET");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        existing(order, 1, OrderLineType.SERVICE, ME, false, 1, 100);
        existing(order, 2, OrderLineType.SERVICE, ME, true, 1, 100);
        when(catalog.findService(11L)).thenReturn(Optional.of(svc(ServiceGroup.GROOMING, true)));

        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 1L, new UpdateOrderLineRequest(null, 11L)))
                .as("dòng thêm tay").isInstanceOf(InvalidOrderLineRequestException.class);
        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 2L, new UpdateOrderLineRequest(null, 11L)))
                .as("VET đổi sang dịch vụ thẩm mỹ").isInstanceOf(OrderLineAccessDeniedException.class);

        ReflectionTestUtils.setField(order, "source", OrderSource.RETAIL);
        ReflectionTestUtils.setField(order, "visitId", null);
        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 2L, new UpdateOrderLineRequest(null, 11L)))
                .as("Order không phải VISIT").isInstanceOf(InvalidOrderLineRequestException.class);
    }

    @Test
    void receptionistOwnerOfTheUnassignedAutoLineCannotSwitchService() {
        actingAs("RECEPTIONIST");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        existing(order, 1, OrderLineType.SERVICE, ME, true, 1, 100);
        when(catalog.findService(11L)).thenReturn(Optional.of(svc(ServiceGroup.MEDICAL, true)));

        assertThatThrownBy(() -> service.updateLine(ORDER_ID, 1L, new UpdateOrderLineRequest(null, 11L)))
                .isInstanceOf(OrderLineAccessDeniedException.class);
    }

    // ---------------------------------------------------------------- xóa

    @Test
    void ownerDeletesTheServiceLineAndTheTotalDrops() {
        actingAs("VET");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        existing(order, 1, OrderLineType.SERVICE, ME, false, 1, 100_000);
        existing(order, 2, OrderLineType.GOODS, OTHER, false, 2, 5_000);

        service.deleteLine(ORDER_ID, 1L);

        assertThat(order.getLines()).hasSize(1);
        assertThat(order.getTotalAmount()).isEqualTo(10_000);
        verify(orders).flush();
    }

    @Test
    void deleteDenialsWriteNothing() {
        actingAs("VET");
        SalesOrder order = visitOrder(OrderStatus.OPEN);
        existing(order, 1, OrderLineType.SERVICE, OTHER, false, 1, 100);
        existing(order, 2, OrderLineType.VACCINE, ME, false, 1, 100);
        existing(order, 3, OrderLineType.DRUG, ME, false, 1, 100);

        assertThatThrownBy(() -> service.deleteLine(ORDER_ID, 1L)).isInstanceOf(OrderLineAccessDeniedException.class);
        assertThatThrownBy(() -> service.deleteLine(ORDER_ID, 2L)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> service.deleteLine(ORDER_ID, 3L)).isInstanceOf(BusinessRuleViolationException.class);

        assertThat(order.getLines()).hasSize(3);
        assertThat(order.getTotalAmount()).isEqualTo(300);
        verify(orders, never()).flush();
    }

    // ---------------------------------------------------------------- ranh giới transaction

    @Test
    void everyWriteUseCaseIsOneTransactionWithoutNoRollbackFor() {
        for (String name : new String[] {"addLine", "updateLine", "deleteLine"}) {
            Method method = java.util.Arrays.stream(OrderLineService.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as(name).isNotNull();
            assertThat(tx.noRollbackFor()).as(name).isEmpty();
            assertThat(tx.readOnly()).as(name).isFalse();
        }
    }
}
