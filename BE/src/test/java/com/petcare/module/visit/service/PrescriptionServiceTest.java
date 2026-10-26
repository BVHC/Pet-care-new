package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
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
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.VisitOrderApi;
import com.petcare.module.sales.api.VisitOrderApi.LineRef;
import com.petcare.module.visit.dto.AddPrescriptionRequest;
import com.petcare.module.visit.dto.PrescriptionItemResponse;
import com.petcare.module.visit.dto.ShortageResolution;
import com.petcare.module.visit.entity.ExternalPurchaseReason;
import com.petcare.module.visit.entity.MedicalRecord;
import com.petcare.module.visit.entity.PrescriptionItem;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.module.visit.mapper.MedicalRecordMapper;
import com.petcare.module.visit.repository.MedicalRecordRepository;
import com.petcare.module.visit.repository.PrescriptionItemRepository;
import com.petcare.module.visit.repository.VisitRepository;
import com.petcare.module.visit.repository.VisitRepository.SummaryRow;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * {@link PrescriptionService} (visit-v1 #16, docs/adr/0077): thứ tự khóa và guard (guard sai thì không ghi gì), bảng
 * quyết định BR-KB-03 kèm biên, thứ tự ghi (bệnh án → dòng Order → dòng đơn thuốc → audit), giá trị ghi và response.
 * Hành vi trên DB thật ở {@code PrescriptionIT}.
 */
class PrescriptionServiceTest {

    private static final long BRANCH = 3L;
    private static final long VET = 45L;
    private static final long VISIT = 9001L;
    private static final long PET = 12L;
    private static final long PRODUCT = 77L;
    private static final long LINE = 9101L;
    private static final long ITEM_ID = 501L;
    private static final String DOSAGE = "Ngày 2 lần";

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
        doReturn(Optional.of(new PetSummary(PET, 88L, "Mực", Species.DOG, null, null, null))).when(pets)
                .findPetForShare(PET);
        loaded(VET, VisitStatus.IN_PROGRESS);
        doReturn(Optional.of(drug(true, ProductType.DRUG, true))).when(catalog).findProductForShare(PRODUCT);
        doReturn(10).when(stockQuery).availableQuantity(BRANCH, PRODUCT);
        doReturn(Optional.empty()).when(records).findById(VISIT);
        doReturn(new LineRef(500L, LINE)).when(visitOrders).addDrugLine(anyLong(), anyLong(), anyInt(), anyLong());
        doAnswer(invocation -> {
            PrescriptionItem row = invocation.getArgument(0);
            ReflectionTestUtils.setField(row, "id", ITEM_ID);
            return row;
        }).when(items).saveAndFlush(any(PrescriptionItem.class));
    }

    // ================================================================ guard trước khóa

    @ParameterizedTest
    @ValueSource(strings = {"CARETAKER", "RECEPTIONIST", "BRANCH_MANAGER", "SUPER_MANAGER", "ADMIN", "CUSTOMER"})
    void rolesOutsideTheContractNeverReachAnyRead(String role) {
        doReturn(role).when(principal).role();

        assertThatThrownBy(() -> service.add(VISIT, request(3, null))).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(visits, records, items, pets, catalog, stockQuery, visitOrders, audit);
    }

    @Test
    void missingVisitIs404BeforeTheBranchCheck() {
        doReturn(Optional.empty()).when(visits).findSummary(VISIT);

        assertThatThrownBy(() -> service.add(VISIT, request(3, null))).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("lượt tiếp nhận");

        verify(scope, never()).check(any());
        verifyNoInteractions(pets, records, items, catalog, stockQuery, visitOrders, audit);
    }

    @Test
    void otherBranchIs403BeforeAnyLock() {
        summary(9L, ServiceGroup.MEDICAL);
        doThrow(new AccessDeniedScopeException("branch:9", "branch:3")).when(scope).check(9L);

        assertThatThrownBy(() -> service.add(VISIT, request(3, null))).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoInteractions(pets, records, items, catalog, stockQuery, visitOrders, audit);
        verify(visits, never()).lockById(anyLong());
    }

    @Test
    void groomingVisitIs404BeforeAnyLock() {
        summary(BRANCH, ServiceGroup.GROOMING);

        assertThatThrownBy(() -> service.add(VISIT, request(3, null))).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("lượt khám / tiêm");

        verifyNoInteractions(pets, records, items, catalog, stockQuery, visitOrders, audit);
        verify(visits, never()).lockById(anyLong());
    }

    // ================================================================ khóa và guard dưới khóa

    @Test
    void locksPetThenVisitBeforeReadingTheProductAndTheStock() {
        service.add(VISIT, request(3, null));

        InOrder order = inOrder(pets, visits, catalog, stockQuery);
        order.verify(pets).findPetForShare(PET);
        order.verify(visits).lockById(VISIT);
        order.verify(visits).findById(VISIT);
        order.verify(catalog).findProductForShare(PRODUCT);
        order.verify(stockQuery).availableQuantity(BRANCH, PRODUCT);
        verify(catalog, never()).findProduct(anyLong());
    }

    @Test
    void aMissingPetIsCorruptData() {
        doReturn(Optional.empty()).when(pets).findPetForShare(PET);

        assertThatThrownBy(() -> service.add(VISIT, request(3, null))).isInstanceOf(IllegalStateException.class);

        verify(visits, never()).lockById(anyLong());
        verifyNoWrite();
    }

    @Test
    void notTheAssigneeIs403UnderTheLockAndNothingElseIsRead() {
        loaded(46L, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.add(VISIT, request(3, null))).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoWrite();
        verifyNoInteractions(catalog, stockQuery);
    }

    @Test
    void anUnassignedVisitIs403() {
        loaded(null, VisitStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.add(VISIT, request(3, null))).isInstanceOf(AccessDeniedScopeException.class);

        verifyNoWrite();
    }

    @ParameterizedTest
    @EnumSource(value = VisitStatus.class, names = {"WAITING", "COMPLETED", "CANCELLED"})
    void aVisitThatIsNotInProgressIs409(VisitStatus status) {
        loaded(VET, status);

        assertThatThrownBy(() -> service.add(VISIT, request(3, null)))
                .isInstanceOf(InvalidStateTransitionException.class);

        verifyNoWrite();
        verifyNoInteractions(catalog, stockQuery);
    }

    @Test
    void theAssigneeCheckComesBeforeTheStatusCheck() {
        loaded(46L, VisitStatus.COMPLETED);

        assertThatThrownBy(() -> service.add(VISIT, request(3, null))).isInstanceOf(AccessDeniedScopeException.class);
    }

    // ================================================================ thuốc

    @Test
    void anUnknownProductIsBrKb03() {
        doReturn(Optional.empty()).when(catalog).findProductForShare(PRODUCT);

        assertProductRejected(request(3, null));
    }

    @Test
    void aDrugWithoutThePrescriptionFlagIsBrKb03() {
        doReturn(Optional.of(drug(false, ProductType.DRUG, true))).when(catalog).findProductForShare(PRODUCT);

        assertProductRejected(request(3, null));
    }

    @ParameterizedTest
    @EnumSource(value = ProductType.class, names = {"GOODS", "VACCINE"})
    void aProductThatIsNotADrugIsBrKb03(ProductType type) {
        doReturn(Optional.of(drug(true, type, true))).when(catalog).findProductForShare(PRODUCT);

        assertProductRejected(request(3, null));
    }

    @Test
    void aDiscontinuedDrugIsBrKb03EvenForAnExternalPurchase() {
        doReturn(Optional.of(drug(true, ProductType.DRUG, false))).when(catalog).findProductForShare(PRODUCT);

        assertProductRejected(request(3, ShortageResolution.EXTERNAL_PURCHASE));
    }

    // ================================================================ bảng quyết định (docs/adr/0077 §3)

    @Test
    void enoughStockAddsADrugLineWithTheRequestedQuantity() {
        doReturn(3).when(stockQuery).availableQuantity(BRANCH, PRODUCT);

        PrescriptionItemResponse result = service.add(VISIT, request(3, null));

        verify(visitOrders).addDrugLine(VISIT, PRODUCT, 3, VET);
        PrescriptionItem row = savedItem();
        assertThat(row.getQuantity()).isEqualTo(3);
        assertThat(row.isExternalPurchase()).isFalse();
        assertThat(row.getOrderLineId()).isEqualTo(LINE);
        assertThat(row.getExternalReason()).isNull();
        assertThat(result.orderLineId()).isEqualTo(LINE);
    }

    @ParameterizedTest
    @EnumSource(ShortageResolution.class)
    void aResolutionIsIgnoredWhenTheStockIsEnoughAtWriteTime(ShortageResolution resolution) {
        doReturn(3).when(stockQuery).availableQuantity(BRANCH, PRODUCT);

        PrescriptionItemResponse result = service.add(VISIT, request(3, resolution));

        verify(visitOrders).addDrugLine(VISIT, PRODUCT, 3, VET);
        assertThat(result.isExternalPurchase()).isFalse();
        assertThat(result.quantity()).isEqualTo(3);
    }

    @Test
    void aShortageWithoutAResolutionIsBrKb03WithTheAvailableQuantityAndWritesNothing() {
        doReturn(2).when(stockQuery).availableQuantity(BRANCH, PRODUCT);

        assertThatThrownBy(() -> service.add(VISIT, request(3, null))).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("chỉ còn 2").hasMessageEndingWith("(BR-KB-03)");

        verifyNoWrite();
    }

    @Test
    void reduceToAvailableAddsADrugLineWithTheAvailableQuantity() {
        doReturn(2).when(stockQuery).availableQuantity(BRANCH, PRODUCT);

        PrescriptionItemResponse result = service.add(VISIT, request(3, ShortageResolution.REDUCE_TO_AVAILABLE));

        verify(visitOrders).addDrugLine(VISIT, PRODUCT, 2, VET);
        assertThat(savedItem().getQuantity()).isEqualTo(2);
        assertThat(result.quantity()).as("response trả số lượng thật, không phải số gửi lên").isEqualTo(2);
    }

    @Test
    void reduceToAvailableWithNothingLeftIsBrKb03AndWritesNothing() {
        doReturn(0).when(stockQuery).availableQuantity(BRANCH, PRODUCT);

        assertThatThrownBy(() -> service.add(VISIT, request(3, ShortageResolution.REDUCE_TO_AVAILABLE)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("chỉ chọn được mua ngoài")
                .hasMessageEndingWith("(BR-KB-03)");

        verifyNoWrite();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void externalPurchaseKeepsTheQuantityAndAddsNoOrderLine(int available) {
        doReturn(available).when(stockQuery).availableQuantity(BRANCH, PRODUCT);

        PrescriptionItemResponse result = service.add(VISIT, request(3, ShortageResolution.EXTERNAL_PURCHASE));

        verifyNoInteractions(visitOrders);
        PrescriptionItem row = savedItem();
        assertThat(row.getQuantity()).isEqualTo(3);
        assertThat(row.isExternalPurchase()).isTrue();
        assertThat(row.getOrderLineId()).isNull();
        assertThat(row.getExternalReason()).isEqualTo(ExternalPurchaseReason.OUT_OF_STOCK_AT_PRESCRIBE);
        assertThat(result.orderLineId()).isNull();
    }

    @Test
    void theDecisionBoundaryIsAvailableEqualToRequested() {
        assertThat(PrescriptionService.decideQuantity(3, 3, null)).isEqualTo(3);
        assertThat(PrescriptionService.decideQuantity(3, 4, ShortageResolution.EXTERNAL_PURCHASE)).isEqualTo(3);
        assertThatThrownBy(() -> PrescriptionService.decideQuantity(3, 2, null))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(PrescriptionService.decideQuantity(3, 2, ShortageResolution.REDUCE_TO_AVAILABLE)).isEqualTo(2);
        assertThat(PrescriptionService.decideQuantity(3, 1, ShortageResolution.REDUCE_TO_AVAILABLE)).isEqualTo(1);
        assertThat(PrescriptionService.decideQuantity(3, 2, ShortageResolution.EXTERNAL_PURCHASE)).isNull();
    }

    // ================================================================ ghi

    @Test
    void writesTheRecordThenTheOrderLineThenTheItemThenTheAudit() {
        PrescriptionItemResponse result = service.add(VISIT, request(3, null));

        InOrder order = inOrder(stockQuery, records, visitOrders, items, audit);
        order.verify(stockQuery).availableQuantity(BRANCH, PRODUCT);
        order.verify(records).findById(VISIT);
        order.verify(records).saveAndFlush(any(MedicalRecord.class));
        order.verify(visitOrders).addDrugLine(VISIT, PRODUCT, 3, VET);
        order.verify(items).saveAndFlush(any(PrescriptionItem.class));
        order.verify(audit).record(any(AuditEntry.class));

        ArgumentCaptor<MedicalRecord> record = ArgumentCaptor.forClass(MedicalRecord.class);
        verify(records).saveAndFlush(record.capture());
        assertThat(record.getValue().getVisitId()).isEqualTo(VISIT);
        assertThat(record.getValue().getDiagnosis()).isNull();
        assertThat(record.getValue().getLastEditedBy()).as("bệnh án rỗng chưa có người ghi").isNull();

        PrescriptionItem row = savedItem();
        assertThat(row.getMedicalRecordId()).isEqualTo(VISIT);
        assertThat(row.getProductId()).isEqualTo(PRODUCT);
        assertThat(row.getDosageInstructions()).isEqualTo(DOSAGE);

        assertThat(result.itemId()).isEqualTo(ITEM_ID);
        assertThat(result.productId()).isEqualTo(PRODUCT);
        assertThat(result.productName()).isEqualTo("Amoxicillin");
        assertThat(result.quantity()).isEqualTo(3);
        assertThat(result.dosageInstructions()).isEqualTo(DOSAGE);
        assertThat(result.isExternalPurchase()).isFalse();
        assertThat(result.externalReason()).isNull();
    }

    @Test
    void anExistingRecordIsNeitherCreatedNorTouched() {
        doReturn(Optional.of(MedicalRecord.of(VISIT))).when(records).findById(VISIT);

        service.add(VISIT, request(3, null));

        verify(records, never()).saveAndFlush(any());
        verify(records, never()).save(any());
    }

    @Test
    void theAuditCarriesTheItemAndTheRequestedQuantity() {
        doReturn(2).when(stockQuery).availableQuantity(BRANCH, PRODUCT);

        service.add(VISIT, request(3, ShortageResolution.REDUCE_TO_AVAILABLE));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        AuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo("PRESCRIPTION_ITEM_ADDED");
        assertThat(entry.entityType()).isEqualTo("prescription_items");
        assertThat(entry.entityId()).isEqualTo(ITEM_ID);
        assertThat(entry.before()).isNull();
        Map<?, ?> after = (Map<?, ?>) entry.after();
        assertThat(new ArrayList<Object>(after.keySet())).containsExactly("visitId", "productId", "quantity",
                "requestedQuantity", "dosageInstructions", "isExternalPurchase", "externalReason", "orderLineId");
        assertThat(after.get("visitId")).isEqualTo(VISIT);
        assertThat(after.get("quantity")).isEqualTo(2);
        assertThat(after.get("requestedQuantity")).isEqualTo(3);
        assertThat(after.get("isExternalPurchase")).isEqualTo(false);
        assertThat(after.get("externalReason")).isNull();
        assertThat(after.get("orderLineId")).isEqualTo(LINE);
    }

    @Test
    void anExternalPurchaseAuditNamesTheReason() {
        doReturn(0).when(stockQuery).availableQuantity(BRANCH, PRODUCT);

        service.add(VISIT, request(3, ShortageResolution.EXTERNAL_PURCHASE));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).record(captor.capture());
        Map<?, ?> after = (Map<?, ?>) captor.getValue().after();
        assertThat(after.get("isExternalPurchase")).isEqualTo(true);
        assertThat(after.get("externalReason")).isEqualTo("OUT_OF_STOCK_AT_PRESCRIBE");
        assertThat(after.get("orderLineId")).isNull();
    }

    @Test
    void aFailureAddingTheOrderLineStopsBeforeTheItemAndTheAudit() {
        doThrow(new IllegalStateException("order")).when(visitOrders).addDrugLine(VISIT, PRODUCT, 3, VET);

        assertThatThrownBy(() -> service.add(VISIT, request(3, null))).isInstanceOf(IllegalStateException.class);

        verify(items, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void addIsOneReadWriteTransactionWithTheDefaultIsolationAndNoNoRollbackFor() throws Exception {
        Transactional tx = PrescriptionService.class.getMethod("add", Long.class, AddPrescriptionRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.noRollbackFor()).isEmpty();
        assertThat(tx.noRollbackForClassName()).isEmpty();
    }

    // ================================================================ helpers

    private static AddPrescriptionRequest request(int quantity, ShortageResolution resolution) {
        return new AddPrescriptionRequest(PRODUCT, quantity, DOSAGE, resolution);
    }

    private PrescriptionItem savedItem() {
        ArgumentCaptor<PrescriptionItem> captor = ArgumentCaptor.forClass(PrescriptionItem.class);
        verify(items).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private void assertProductRejected(AddPrescriptionRequest request) {
        assertThatThrownBy(() -> service.add(VISIT, request)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageEndingWith("(BR-KB-03)");

        verifyNoInteractions(stockQuery);
        verifyNoWrite();
    }

    /** Guard sai: không tạo bệnh án, không đụng Order, không ghi dòng đơn thuốc, không audit. */
    private void verifyNoWrite() {
        verify(records, never()).saveAndFlush(any());
        verifyNoInteractions(visitOrders, audit);
        verify(items, never()).saveAndFlush(any());
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

    private static ProductInfo drug(boolean prescription, ProductType type, boolean active) {
        return new ProductInfo(PRODUCT, "Amoxicillin", type, prescription, true, null, 30_000, active);
    }
}
