package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.repository.MedicalRecordRepository;
import com.petcare.module.visit.repository.PrescriptionItemRepository;
import com.petcare.module.visit.repository.PrescriptionItemRepository.LockedItem;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link PrescriptionService#remove} (visit-v1 #17, docs/adr/0078): thứ tự khóa và guard (guard sai thì không xóa gì),
 * thứ tự ghi (dòng đơn → dòng Order → audit), nhánh mua ngoài không gọi sales, lỗi từ callee lan ra không ghi tiếp, giá
 * trị audit {@code before}, thuộc tính transaction. Hành vi trên DB thật ở {@code PrescriptionIT}.
 */
class PrescriptionRemoveServiceTest {

    private static final long BRANCH = 3L;
    private static final long VET = 45L;
    private static final long VISIT = 9001L;
    private static final long PET = 12L;
    private static final long PRODUCT = 77L;
    private static final long LINE = 9101L;
    private static final long ITEM = 501L;

    private final BranchScope scope = mock(BranchScope.class);
    private final SecurityPrincipal principal = mock(SecurityPrincipal.class);
    private final VisitRepository visits = mock(VisitRepository.class);
    private final MedicalRecordRepository records = mock(MedicalRecordRepository.class);
    private final PrescriptionItemRepository items = mock(PrescriptionItemRepository.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final StockQueryApi stockQuery = mock(StockQueryApi.class);
    private final VisitOrderApi visitOrders = mock(VisitOrderApi.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PrescriptionService service = new PrescriptionService(scope, visits, records, items, pets, catalog,
            stockQuery, visitOrders, audit, Mappers.getMapper(MedicalRecordMapper.class));

    @BeforeEach
    void setUp() {
        doReturn(principal).when(scope).current();
        doReturn(VET).when(principal).accountId();
        doReturn("VET").when(principal).role();
        summary(BRANCH, ServiceGroup.MEDICAL);
        loaded(VET, VisitStatus.IN_PROGRESS);
        doReturn(Optional.of(item(LINE, false, null))).when(items).lockItemInVisit(ITEM, VISIT);
        doReturn(1).when(items).deleteItem(ITEM);
    }

    // ================================================================ guard trước khóa

    @ParameterizedTest
    @ValueSource(strings = {"CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void rolesOutsideTheContractNeverReachAnyRead(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.remove(VISIT, ITEM)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, items, visitOrders, audit);
    }

    @Test
    void missingVisitIs404BeforeTheBranchCheck() {
        doReturn(Optional.empty()).when(visits).findSummary(VISIT);

        assertThatThrownBy(() -> service.remove(VISIT, ITEM)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("lượt tiếp nhận");

        verify(scope, never()).check(any());
        verifyNoInteractions(items, visitOrders, audit);
    }

    @Test
    void otherBranchIs403BeforeAnyLock() {
        summary(9L, ServiceGroup.MEDICAL);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.remove(VISIT, ITEM)).isInstanceOf(AccessDeniedScopeException.class);

        verify(visits, never()).lockById(anyLong());
        verifyNoInteractions(items, visitOrders, audit);
    }

    @Test
    void groomingVisitIs404BeforeAnyLock() {
        summary(BRANCH, ServiceGroup.GROOMING);

        assertThatThrownBy(() -> service.remove(VISIT, ITEM)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("lượt khám / tiêm");

        verify(visits, never()).lockById(anyLong());
        verifyNoInteractions(items, visitOrders, audit);
    }

    // ================================================================ khóa và guard dưới khóa

    @Test
    void locksTheVisitThenTheItemAndNeverTheItemPetOrAnyRecordRead() {
        service.remove(VISIT, ITEM);

        InOrder order = inOrder(visits, items);
        order.verify(visits).lockById(VISIT);
        order.verify(visits).findById(VISIT);
        order.verify(items).lockItemInVisit(ITEM, VISIT);
        // không tạo bệnh án, không khóa thú, không đọc thuốc / tồn: #17 chỉ gỡ
        verifyNoInteractions(pets, records, catalog, stockQuery);
    }

    @Test
    void notTheAssigneeIs403UnderTheLockAndTheItemIsNeverLocked() {
        loaded(46L, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.remove(VISIT, ITEM)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNothingRemoved();
    }

    @Test
    void anUnassignedVisitIs403() {
        loaded(null, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.remove(VISIT, ITEM)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNothingRemoved();
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"WAITING", "COMPLETED", "CANCELLED"})
    void aVisitThatIsNotInProgressIs409AndNothingIsRemoved(VisitStatus status) {
        loaded(VET, status);

        assertThatThrownBy(() -> service.remove(VISIT, ITEM)).isInstanceOf(InvalidStateTransitionException.class);

        verifyNothingRemoved();
    }

    @Test
    void theAssigneeCheckComesBeforeTheState() {
        loaded(46L, VisitStatus.COMPLETED);

        assertThatThrownBy(() -> service.remove(VISIT, ITEM)).isInstanceOf(AccessDeniedScopeException.class);

        verifyNothingRemoved();
    }

    @Test
    void anItemThatIsNotInThisVisitIs404AndNothingIsDeleted() {
        doReturn(Optional.empty()).when(items).lockItemInVisit(ITEM, VISIT);

        assertThatThrownBy(() -> service.remove(VISIT, ITEM)).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("dòng đơn thuốc");

        verify(items, never()).deleteItem(anyLong());
        verifyNoInteractions(visitOrders, audit);
    }

    // ================================================================ ghi

    @Test
    void aDrugItemIsDeletedBeforeItsOrderLineAndTheAuditComesLast() {
        service.remove(VISIT, ITEM);

        InOrder order = inOrder(visits, items, visitOrders, audit);
        order.verify(visits).lockById(VISIT);
        order.verify(items).lockItemInVisit(ITEM, VISIT);
        order.verify(items).deleteItem(ITEM);
        order.verify(visitOrders).removeDrugLine(LINE, VET);
        order.verify(audit).record(any(AuditEntry.class));
    }

    @Test
    void anExternalPurchaseDeletesOnlyTheItemAndNeverCallsSales() {
        doReturn(Optional.of(item(null, true, "OUT_OF_STOCK_AT_PRESCRIBE"))).when(items).lockItemInVisit(ITEM, VISIT);

        service.remove(VISIT, ITEM);

        verify(items).deleteItem(ITEM);
        verifyNoInteractions(visitOrders);
        verify(audit).record(any(AuditEntry.class));
    }

    @Test
    void aMissingDeleteCountAfterTheLockIsCorruptDataAndStopsBeforeSalesAndTheAudit() {
        doReturn(0).when(items).deleteItem(ITEM);

        assertThatThrownBy(() -> service.remove(VISIT, ITEM)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visitOrders, audit);
    }

    @Test
    void aFailureRemovingTheOrderLinePropagatesAndTheAuditIsNotWritten() {
        doThrow(new IllegalStateException("order")).when(visitOrders).removeDrugLine(LINE, VET);

        assertThatThrownBy(() -> service.remove(VISIT, ITEM)).isInstanceOf(IllegalStateException.class)
                .hasMessage("order");

        verifyNoInteractions(audit);
    }

    // ================================================================ audit

    @Test
    void theAuditCarriesTheRemovedLineAsBeforeInTheKeyOrderOfTheAddAudit() {
        service.remove(VISIT, ITEM);

        AuditEntry entry = capturedAudit();
        assertThat(entry.action()).isEqualTo("PRESCRIPTION_ITEM_REMOVED");
        assertThat(entry.entityType()).isEqualTo("prescription_items");
        assertThat(entry.entityId()).isEqualTo(ITEM);
        assertThat(entry.after()).isNull();
        Map<?, ?> before = (Map<?, ?>) entry.before();
        assertThat(new ArrayList<Object>(before.keySet())).containsExactly("visitId", "productId", "quantity",
                "dosageInstructions", "isExternalPurchase", "externalReason", "orderLineId");
        assertThat(before.get("visitId")).isEqualTo(VISIT);
        assertThat(before.get("productId")).isEqualTo(PRODUCT);
        assertThat(before.get("quantity")).isEqualTo(2);
        assertThat(before.get("dosageInstructions")).isEqualTo("Ngày 2 lần");
        assertThat(before.get("isExternalPurchase")).isEqualTo(false);
        assertThat(before.get("externalReason")).isNull();
        assertThat(before.get("orderLineId")).isEqualTo(LINE);
    }

    @Test
    void anExternalPurchaseAuditNamesTheReasonAndHasNoOrderLine() {
        doReturn(Optional.of(item(null, true, "OUT_OF_STOCK_AT_PAYMENT"))).when(items).lockItemInVisit(ITEM, VISIT);

        service.remove(VISIT, ITEM);

        Map<?, ?> before = (Map<?, ?>) capturedAudit().before();
        assertThat(before.get("isExternalPurchase")).isEqualTo(true);
        assertThat(before.get("externalReason")).isEqualTo("OUT_OF_STOCK_AT_PAYMENT");
        assertThat(before.get("orderLineId")).isNull();
    }

    // ================================================================ transaction

    @Test
    void removeIsOneReadWriteTransactionWithTheDefaultIsolationAndNoNoRollbackFor() throws Exception {
        Transactional tx = PrescriptionService.class.getMethod("remove", Long.class, Long.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    @Test
    void theRepositoryLockAndDeleteRequireAnExistingTransaction() throws Exception {
        for (String name : new String[] {"lockItemInVisit", "deleteItem"}) {
            Class<?>[] parameters = name.equals("deleteItem") ? new Class<?>[] {Long.class}
                    : new Class<?>[] {Long.class, Long.class};
            Transactional tx = PrescriptionItemRepository.class.getMethod(name, parameters)
                    .getAnnotation(Transactional.class);

            assertThat(tx).as(name).isNotNull();
            assertThat(tx.propagation()).as(name).isEqualTo(Propagation.MANDATORY);
        }
    }

    // ================================================================ helpers

    /** Guard sai: không khóa dòng, không xóa, không đụng Order, không audit. */
    private void verifyNothingRemoved() {
        verify(items, never()).lockItemInVisit(anyLong(), anyLong());
        verify(items, never()).deleteItem(anyLong());
        verifyNoInteractions(visitOrders, audit);
    }

    private AuditEntry capturedAudit() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    private void summary(long branchId, ServiceGroup group) {
        doReturn(Optional.of(new SummaryRow(VISIT, branchId, 88L, PET, group, VisitStatus.IN_PROGRESS, VET)))
                .when(visits).findSummary(VISIT);
    }

    private void loaded(Long assigneeId, VisitStatus status) {
        Instant checkedIn = Instant.parse("2026-10-27T02:00:00Z");
        Visit visit = Visit.checkIn("TN-2610-" + VISIT, BRANCH, 88L, PET, null, ServiceGroup.MEDICAL,
                PriorityClass.WALK_IN, checkedIn, 70L, checkedIn);
        ReflectionTestUtils.setField(visit, "id", VISIT);
        ReflectionTestUtils.setField(visit, "assigneeId", assigneeId);
        ReflectionTestUtils.setField(visit, "status", status);
        doReturn(Optional.of(VISIT)).when(visits).lockById(VISIT);
        doReturn(Optional.of(visit)).when(visits).findById(VISIT);
    }

    private static LockedItem item(Long orderLineId, boolean external, String reason) {
        return new LockedItem() {
            @Override
            public Long getId() {
                return ITEM;
            }

            @Override
            public Long getProductId() {
                return PRODUCT;
            }

            @Override
            public Integer getQuantity() {
                return 2;
            }

            @Override
            public String getDosageInstructions() {
                return "Ngày 2 lần";
            }

            @Override
            public Boolean getExternalPurchase() {
                return external;
            }

            @Override
            public String getExternalReason() {
                return reason;
            }

            @Override
            public Long getOrderLineId() {
                return orderLineId;
            }
        };
    }
}
