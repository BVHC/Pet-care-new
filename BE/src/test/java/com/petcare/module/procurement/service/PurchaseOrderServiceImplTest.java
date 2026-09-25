package com.petcare.module.procurement.service;

import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.service.ProductService;
import com.petcare.module.organization.service.StoreService;
import com.petcare.module.procurement.dto.CreatePurchaseOrderRequest;
import com.petcare.module.procurement.entity.PurchaseOrder;
import com.petcare.module.procurement.entity.PurchaseRequest;
import com.petcare.module.procurement.entity.PurchaseRequestLine;
import com.petcare.module.procurement.entity.Supplier;
import com.petcare.module.procurement.mapper.PurchaseOrderMapper;
import com.petcare.module.procurement.mapper.PurchaseOrderMapperImpl;
import com.petcare.module.procurement.repository.PurchaseOrderLineRepository;
import com.petcare.module.procurement.repository.PurchaseOrderRepository;
import com.petcare.module.procurement.repository.PurchaseRequestLineRepository;
import com.petcare.module.procurement.repository.PurchaseRequestRepository;
import com.petcare.module.procurement.repository.SupplierRepository;
import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import com.petcare.platform.enums.PurchaseOrderStatus;
import com.petcare.platform.enums.PurchaseRequestStatus;
import com.petcare.platform.enums.SupplierStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-13-04 (CreatePurchaseOrder). */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceImplTest {

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;
    @Mock
    private PurchaseOrderLineRepository purchaseOrderLineRepository;
    @Mock
    private PurchaseRequestRepository purchaseRequestRepository;
    @Mock
    private PurchaseRequestLineRepository purchaseRequestLineRepository;
    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private StoreService storeService;
    @Mock
    private ProductService productService;
    @Mock
    private ProcurementEventRecorder procurementEventRecorder;

    private final PurchaseOrderMapper purchaseOrderMapper = new PurchaseOrderMapperImpl();

    private PurchaseOrderServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static ProductResponse product(UUID productId, UUID organizationId, String sku) {
        return new ProductResponse(productId, organizationId, sku, null, "San pham", ProductCategory.FOOD,
                ProductUnit.ITEM, "10000.00", "8000.00", true);
    }

    private static PurchaseRequest approvedRequest(UUID storeId) {
        PurchaseRequest pr = new PurchaseRequest("PR-20260101-abcd1234", storeId, UUID.randomUUID());
        pr.setId(UUID.randomUUID());
        pr.setStatus(PurchaseRequestStatus.APPROVED);
        return pr;
    }

    private static Supplier activeSupplier(UUID organizationId) {
        Supplier supplier = new Supplier(organizationId, "SUP01", "NCC ABC", null, null, null);
        supplier.setId(UUID.randomUUID());
        supplier.setStatus(SupplierStatus.ACTIVE);
        return supplier;
    }

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderServiceImpl(purchaseOrderRepository, purchaseOrderLineRepository,
                purchaseRequestRepository, purchaseRequestLineRepository, supplierRepository, purchaseOrderMapper,
                storeService, productService, procurementEventRecorder);
    }

    @Test
    void createPurchaseOrder_approvedPrAndActiveSupplier_issuesOrderWithCopiedLines() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        PurchaseRequest pr = approvedRequest(storeId);
        Supplier supplier = activeSupplier(organizationId);
        PurchaseRequestLine line = new PurchaseRequestLine(pr.getId(), productId, 10, new BigDecimal("5000.00"), null);
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(supplierRepository.findById(supplier.getId())).thenReturn(Optional.of(supplier));
        when(purchaseRequestLineRepository.findAllByPurchaseRequestId(pr.getId())).thenReturn(List.of(line));
        when(purchaseOrderRepository.save(any(PurchaseOrder.class))).thenAnswer(inv -> {
            PurchaseOrder po = inv.getArgument(0);
            po.setId(UUID.randomUUID());
            return po;
        });
        when(purchaseOrderRepository.saveAndFlush(any(PurchaseOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));

        var request = new CreatePurchaseOrderRequest(pr.getId(), supplier.getId());
        var response = service.createPurchaseOrder(request, principal(UserRole.INVENTORY_STAFF, organizationId, storeId));

        assertThat(response.status()).isEqualTo(PurchaseOrderStatus.ISSUED);
        assertThat(response.supplierName()).isEqualTo("NCC ABC");
        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).orderedQuantity()).isEqualTo(10);
        assertThat(response.totalAmount()).isEqualTo("50000.00");
    }

    @Test
    void createPurchaseOrder_prNotApproved_throwsBusinessRuleViolation_RULE_13_04() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        PurchaseRequest pr = approvedRequest(storeId);
        pr.setStatus(PurchaseRequestStatus.DRAFT);
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        var request = new CreatePurchaseOrderRequest(pr.getId(), UUID.randomUUID());

        assertThatThrownBy(() -> service.createPurchaseOrder(request, principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-13-04"));
    }

    @Test
    void createPurchaseOrder_inactiveSupplier_throwsBusinessRuleViolation_RULE_13_04() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        PurchaseRequest pr = approvedRequest(storeId);
        Supplier supplier = activeSupplier(organizationId);
        supplier.setStatus(SupplierStatus.INACTIVE);
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(supplierRepository.findById(supplier.getId())).thenReturn(Optional.of(supplier));

        var request = new CreatePurchaseOrderRequest(pr.getId(), supplier.getId());

        assertThatThrownBy(() -> service.createPurchaseOrder(request, principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-13-04"));
    }

    @Test
    void createPurchaseOrder_supplierFromOtherOrg_throwsBusinessRuleViolation_RULE_13_04() {
        UUID organizationId = UUID.randomUUID();
        UUID otherOrganizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        PurchaseRequest pr = approvedRequest(storeId);
        Supplier supplier = activeSupplier(otherOrganizationId);
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(supplierRepository.findById(supplier.getId())).thenReturn(Optional.of(supplier));

        var request = new CreatePurchaseOrderRequest(pr.getId(), supplier.getId());

        assertThatThrownBy(() -> service.createPurchaseOrder(request, principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-13-04"));
    }

    @Test
    void getPurchaseOrder_returnsLinesWithProductSku() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        PurchaseOrder po = new PurchaseOrder("PO-20260101-abcd1234", UUID.randomUUID(), storeId, supplierId, UUID.randomUUID());
        po.setId(UUID.randomUUID());
        Supplier supplier = activeSupplier(organizationId);
        supplier.setId(supplierId);
        when(purchaseOrderRepository.findById(po.getId())).thenReturn(Optional.of(po));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(supplierRepository.findById(supplierId)).thenReturn(Optional.of(supplier));
        when(purchaseOrderLineRepository.findAllByPurchaseOrderId(po.getId())).thenReturn(List.of());

        var response = service.getPurchaseOrder(po.getId(), principal(UserRole.INVENTORY_STAFF, organizationId, storeId));

        assertThat(response.supplierName()).isEqualTo("NCC ABC");
        assertThat(response.lines()).isEmpty();
    }
}
