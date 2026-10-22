package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.MedicalType;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;
import com.petcare.module.sales.entity.OrderCancelType;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.exception.OrderLineAccessDeniedException;
import com.petcare.module.sales.fsm.OrderTransitionHandler;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.sequence.DocumentCodeGenerator;

@ExtendWith(MockitoExtension.class)
class VisitOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");
    private static final long VISIT = 50L;
    private static final long VET = 7L;
    private static final long OTHER_VET = 8L;
    private static final long RECEPTIONIST = 3L;

    @Mock
    SalesOrderRepository orders;
    @Mock
    CatalogQueryApi catalog;
    @Mock
    DocumentCodeGenerator codes;

    VisitOrderService service;

    @BeforeEach
    void setUp() {
        service = new VisitOrderService(orders, catalog, codes, new OrderTransitionHandler(),
                Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    private static SalesOrder openOrder() {
        SalesOrder order = SalesOrder.openForVisit("DH-2610-00001", 1L, 2L, VISIT, RECEPTIONIST);
        ReflectionTestUtils.setField(order, "id", 100L);
        return order;
    }

    private static OrderLine withId(OrderLine line, long id) {
        ReflectionTestUtils.setField(line, "id", id);
        return line;
    }

    private static OrderLine auto(long id, long owner) {
        return withId(OrderLine.of(OrderLineType.SERVICE, 10L, null, "Kham", 1, 150_000, true, RECEPTIONIST, owner),
                id);
    }

    private static OrderLine drug(long id, long owner) {
        return withId(OrderLine.of(OrderLineType.DRUG, null, 21L, "Thuoc", 2, 30_000, false, owner, owner), id);
    }

    private static OrderLine vaccine(long id, long owner) {
        return withId(OrderLine.of(OrderLineType.VACCINE, null, 22L, "Vaccine", 1, 200_000, false, owner, owner), id);
    }

    private static ProductInfo product(ProductType type, boolean prescription, boolean active) {
        return new ProductInfo(21L, "Sản phẩm", type, prescription, true, null, 30_000, active);
    }

    // ---------------------------------------------------------------- openForVisit

    @Test
    void openForVisitSnapshotsServiceAndAssignsOwner() {
        when(catalog.findService(10L)).thenReturn(Optional.of(
                new ServiceInfo(10L, "Khám tổng quát", ServiceGroup.MEDICAL, MedicalType.EXAM, 150_000, true)));
        when(codes.next(anyString(), anyString(), any())).thenReturn("DH-2610-00001");
        when(orders.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> {
            SalesOrder saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            ReflectionTestUtils.setField(saved.getLines().get(0), "id", 200L);
            return saved;
        });

        LineRef ref = service.openForVisit(VISIT, 1L, 2L, 10L, VET, RECEPTIONIST);

        assertThat(ref).isEqualTo(new LineRef(100L, 200L));
        org.mockito.ArgumentCaptor<SalesOrder> saved = org.mockito.ArgumentCaptor.forClass(SalesOrder.class);
        verify(orders).saveAndFlush(saved.capture());
        SalesOrder order = saved.getValue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(order.getVisitId()).isEqualTo(VISIT);
        assertThat(order.getCreatedBy()).isEqualTo(RECEPTIONIST);
        assertThat(order.getTotalAmount()).isEqualTo(150_000);
        OrderLine line = order.getLines().get(0);
        assertThat(line.getDescription()).isEqualTo("Khám tổng quát");
        assertThat(line.getUnitPrice()).isEqualTo(150_000);
        assertThat(line.isAutoGenerated()).isTrue();
        assertThat(line.getAddedBy()).isEqualTo(RECEPTIONIST);
        assertThat(line.getOwnerAccountId()).as("owner = người được gán lượt").isEqualTo(VET);
    }

    @Test
    void openForVisitWithoutAssigneeFallsBackToActor() {
        when(catalog.findService(10L)).thenReturn(Optional.of(
                new ServiceInfo(10L, "Tắm", ServiceGroup.GROOMING, null, 90_000, true)));
        when(codes.next(anyString(), anyString(), any())).thenReturn("DH-2610-00002");

        service.openForVisit(VISIT, 1L, 2L, 10L, null, RECEPTIONIST);

        org.mockito.ArgumentCaptor<SalesOrder> saved = org.mockito.ArgumentCaptor.forClass(SalesOrder.class);
        verify(orders).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getLines().get(0).getOwnerAccountId()).isEqualTo(RECEPTIONIST);
    }

    @Test
    void openForVisitRefusesMissingOrInactiveServiceBeforeTakingACode() {
        when(catalog.findService(10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.openForVisit(VISIT, 1L, 2L, 10L, null, RECEPTIONIST))
                .isInstanceOf(IllegalStateException.class);

        when(catalog.findService(11L)).thenReturn(Optional.of(
                new ServiceInfo(11L, "Ngừng", ServiceGroup.GROOMING, null, 90_000, false)));
        assertThatThrownBy(() -> service.openForVisit(VISIT, 1L, 2L, 11L, null, RECEPTIONIST))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(codes);
        verify(orders, never()).saveAndFlush(any());
    }

    // ---------------------------------------------------------------- chủ dòng (Visit#2, #4)

    @Test
    void setAutoLineOwnerMovesOnlyAutoGeneratedLines() {
        SalesOrder order = openOrder();
        OrderLine auto = auto(1, RECEPTIONIST);
        OrderLine drug = drug(2, VET);
        order.addLine(auto);
        order.addLine(drug);
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));

        service.setAutoLineOwner(VISIT, OTHER_VET);

        assertThat(auto.getOwnerAccountId()).isEqualTo(OTHER_VET);
        assertThat(drug.getOwnerAccountId()).as("dòng thuốc của VET không bị đổi").isEqualTo(VET);
    }

    @Test
    void transferLineOwnershipMovesEveryLineOfTheOldOwner() {
        SalesOrder order = openOrder();
        OrderLine auto = auto(1, VET);
        OrderLine drug = drug(2, VET);
        OrderLine retail = withId(OrderLine.of(OrderLineType.GOODS, null, 5L, "Hạt", 1, 10_000, false, RECEPTIONIST,
                RECEPTIONIST), 3);
        order.addLine(auto);
        order.addLine(drug);
        order.addLine(retail);
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));

        service.transferLineOwnership(VISIT, VET, OTHER_VET);

        assertThat(auto.getOwnerAccountId()).isEqualTo(OTHER_VET);
        assertThat(drug.getOwnerAccountId()).isEqualTo(OTHER_VET);
        assertThat(retail.getOwnerAccountId()).isEqualTo(RECEPTIONIST);
    }

    @Test
    void ownerChangesNeedAnOpenOrder() {
        SalesOrder order = openOrder();
        order.markPending(NOW);
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.setAutoLineOwner(VISIT, VET))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> service.transferLineOwnership(VISIT, VET, OTHER_VET))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    // ---------------------------------------------------------------- Order#4, #6

    @Test
    void markPendingMovesOpenToPendingAndStampsTheClock() {
        SalesOrder order = openOrder();
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));

        service.markPendingByVisit(VISIT);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getPendingAt()).isEqualTo(NOW);
    }

    @Test
    void markPendingTwiceIsRefusedAndKeepsTheFirstTimestamp() {
        SalesOrder order = openOrder();
        order.markPending(NOW.minusSeconds(60));
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.markPendingByVisit(VISIT)).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(order.getPendingAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void cancelByVisitRecordsVisitCancelledWithoutReason() {
        SalesOrder order = openOrder();
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));

        service.cancelByVisit(VISIT, RECEPTIONIST);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelType()).isEqualTo(OrderCancelType.VISIT_CANCELLED);
        assertThat(order.getCancelledBy()).isEqualTo(RECEPTIONIST);
        assertThat(order.getCancelledAt()).isEqualTo(NOW);
        assertThat(order.getCancelReason()).isNull();
    }

    @Test
    void cancelByVisitFromPendingOrFinalIsRefused() {
        for (OrderStatus status : List.of(OrderStatus.PENDING, OrderStatus.CANCELLED, OrderStatus.PAID)) {
            SalesOrder order = openOrder();
            ReflectionTestUtils.setField(order, "status", status);
            when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> service.cancelByVisit(VISIT, RECEPTIONIST))
                    .as(status.name()).isInstanceOf(InvalidStateTransitionException.class);
            assertThat(order.getStatus()).isEqualTo(status);
        }
    }

    @Test
    void visitWithoutOrderIsABug() {
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.empty());
        when(orders.findByVisitId(VISIT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markPendingByVisit(VISIT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.cancelByVisit(VISIT, 1L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.findServiceIds(VISIT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void findServiceIdsListsOnlyServiceLines() {
        SalesOrder order = openOrder();
        order.addLine(auto(1, VET));
        order.addLine(drug(2, VET));
        when(orders.findByVisitId(VISIT)).thenReturn(Optional.of(order));

        assertThat(service.findServiceIds(VISIT)).containsExactly(10L);
    }

    // ---------------------------------------------------------------- dòng thuốc / vaccine

    @Test
    void addDrugLineLocksTheOrderBeforeReadingTheProduct() {
        SalesOrder order = openOrder();
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));
        when(catalog.findProductForShare(21L)).thenReturn(Optional.of(product(ProductType.DRUG, true, true)));

        LineRef ref = service.addDrugLine(VISIT, 21L, 3, VET);

        InOrder inOrder = inOrder(orders, catalog);
        inOrder.verify(orders).findByVisitIdForUpdate(VISIT);
        inOrder.verify(catalog).findProductForShare(21L);
        inOrder.verify(orders).flush();
        OrderLine line = order.getLines().get(0);
        assertThat(ref.orderId()).isEqualTo(100L);
        assertThat(line.getLineType()).isEqualTo(OrderLineType.DRUG);
        assertThat(line.getQuantity()).isEqualTo(3);
        assertThat(line.getUnitPrice()).isEqualTo(30_000);
        assertThat(line.getLineTotal()).isEqualTo(90_000);
        assertThat(line.getOwnerAccountId()).isEqualTo(VET);
        assertThat(line.getAddedBy()).isEqualTo(VET);
        assertThat(order.getTotalAmount()).isEqualTo(90_000);
    }

    @Test
    void addDrugLineRefusesNonPrescriptionInactiveOrWrongTypeAndLeavesTheOrderAlone() {
        SalesOrder order = openOrder();
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));
        when(catalog.findProductForShare(21L)).thenReturn(
                Optional.of(product(ProductType.DRUG, false, true)),
                Optional.of(product(ProductType.DRUG, true, false)),
                Optional.of(product(ProductType.GOODS, false, true)));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.addDrugLine(VISIT, 21L, 1, VET))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(order.getLines()).isEmpty();
        assertThat(order.getTotalAmount()).isZero();
    }

    @Test
    void addDrugLineRefusesNonPositiveQuantityBeforeLocking() {
        assertThatThrownBy(() -> service.addDrugLine(VISIT, 21L, 0, VET)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(orders, catalog);
    }

    @Test
    void addingToAnOrderThatIsNotOpenIsRefusedBeforeTheCatalogIsRead() {
        SalesOrder order = openOrder();
        order.markPending(NOW);
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.addDrugLine(VISIT, 21L, 1, VET))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> service.addVaccineLine(VISIT, 22L, VET))
                .isInstanceOf(InvalidStateTransitionException.class);

        verifyNoInteractions(catalog);
    }

    @Test
    void addVaccineLineAlwaysHasQuantityOne() {
        SalesOrder order = openOrder();
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));
        when(catalog.findProductForShare(22L)).thenReturn(Optional.of(
                new ProductInfo(22L, "Vaccine dại", ProductType.VACCINE, false, true, 5L, 200_000, true)));

        service.addVaccineLine(VISIT, 22L, VET);

        OrderLine line = order.getLines().get(0);
        assertThat(line.getLineType()).isEqualTo(OrderLineType.VACCINE);
        assertThat(line.getQuantity()).isEqualTo(1);
        assertThat(line.getLineTotal()).isEqualTo(200_000);
        assertThat(line.getOwnerAccountId()).isEqualTo(VET);
    }

    @Test
    void addVaccineLineRefusesAGoodsProduct() {
        SalesOrder order = openOrder();
        when(orders.findByVisitIdForUpdate(VISIT)).thenReturn(Optional.of(order));
        when(catalog.findProductForShare(21L)).thenReturn(Optional.of(product(ProductType.GOODS, false, true)));

        assertThatThrownBy(() -> service.addVaccineLine(VISIT, 21L, VET)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeDrugLineByItsOwnerDropsTheLineAndTheTotal() {
        SalesOrder order = openOrder();
        OrderLine drug = drug(2, VET);
        order.addLine(auto(1, VET));
        order.addLine(drug);
        when(orders.findIdByLineId(2L)).thenReturn(Optional.of(100L));
        when(orders.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        service.removeDrugLine(2L, VET);

        assertThat(order.getLines()).hasSize(1);
        assertThat(order.getTotalAmount()).isEqualTo(150_000);
        InOrder inOrder = inOrder(orders);
        inOrder.verify(orders).findIdByLineId(2L);
        inOrder.verify(orders).findByIdForUpdate(100L);
        inOrder.verify(orders).flush();
    }

    @Test
    void removeDrugLineByAnotherVetIsForbiddenWithRuleCode() {
        SalesOrder order = openOrder();
        order.addLine(drug(2, VET));
        when(orders.findIdByLineId(2L)).thenReturn(Optional.of(100L));
        when(orders.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.removeDrugLine(2L, OTHER_VET))
                .isInstanceOfSatisfying(OrderLineAccessDeniedException.class,
                        e -> assertThat(e.clientMessage()).contains("BR-BH-02"));
        assertThat(order.getLines()).hasSize(1);
    }

    @Test
    void removeLineChecksStateBeforeOwner() {
        SalesOrder order = openOrder();
        order.addLine(drug(2, VET));
        order.markPending(NOW);
        when(orders.findIdByLineId(2L)).thenReturn(Optional.of(100L));
        when(orders.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.removeDrugLine(2L, OTHER_VET))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void removeLineOfTheWrongTypeIsABugOfTheCaller() {
        SalesOrder order = openOrder();
        order.addLine(vaccine(4, VET));
        when(orders.findIdByLineId(4L)).thenReturn(Optional.of(100L));
        when(orders.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.removeDrugLine(4L, VET)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeVaccineLineDropsItWithoutOwnerCheck() {
        SalesOrder order = openOrder();
        order.addLine(vaccine(4, VET));
        when(orders.findIdByLineId(4L)).thenReturn(Optional.of(100L));
        when(orders.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        service.removeVaccineLine(4L);

        assertThat(order.getLines()).isEmpty();
        assertThat(order.getTotalAmount()).isZero();
    }

    @Test
    void missingLineIsNotFoundBeforeOrAfterTheLock() {
        when(orders.findIdByLineId(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.removeVaccineLine(9L)).isInstanceOf(ResourceNotFoundException.class);

        SalesOrder order = openOrder();
        when(orders.findIdByLineId(8L)).thenReturn(Optional.of(100L));
        when(orders.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        assertThatThrownBy(() -> service.removeVaccineLine(8L))
                .as("dòng vừa bị xóa trong lúc chờ khóa").isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- ranh giới transaction

    @Test
    void everyApiMethodRunsInTheCallersTransaction() {
        for (Method method : VisitOrderApi.class.getDeclaredMethods()) {
            Method impl = org.springframework.util.ReflectionUtils.findMethod(VisitOrderService.class,
                    method.getName(), method.getParameterTypes());
            Transactional tx = impl.getAnnotation(Transactional.class);
            assertThat(tx).as(method.getName()).isNotNull();
            assertThat(tx.propagation()).as(method.getName()).isEqualTo(Propagation.MANDATORY);
            assertThat(tx.noRollbackFor()).as(method.getName()).isEmpty();
        }
    }
}
