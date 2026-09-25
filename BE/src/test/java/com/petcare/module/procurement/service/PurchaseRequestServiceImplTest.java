package com.petcare.module.procurement.service;

import com.petcare.module.catalog.dto.ProductResponse;
import com.petcare.module.catalog.service.ProductService;
import com.petcare.module.organization.service.StoreService;
import com.petcare.module.procurement.dto.CreatePurchaseRequestRequest;
import com.petcare.module.procurement.dto.PurchaseRequestLineItem;
import com.petcare.module.procurement.dto.RejectPurchaseRequestRequest;
import com.petcare.module.procurement.entity.PurchaseRequest;
import com.petcare.module.procurement.entity.PurchaseRequestLine;
import com.petcare.module.procurement.fsm.PurchaseRequestTransitionHandler;
import com.petcare.module.procurement.mapper.PurchaseRequestMapper;
import com.petcare.module.procurement.mapper.PurchaseRequestMapperImpl;
import com.petcare.module.procurement.repository.PurchaseRequestLineRepository;
import com.petcare.module.procurement.repository.PurchaseRequestRepository;
import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import com.petcare.platform.enums.PurchaseRequestStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** docs/02-business-rules.md RULE-13-01, RULE-13-02 (Maker-Checker), RULE-13-03. */
@ExtendWith(MockitoExtension.class)
class PurchaseRequestServiceImplTest {

    @Mock
    private PurchaseRequestRepository purchaseRequestRepository;
    @Mock
    private PurchaseRequestLineRepository purchaseRequestLineRepository;
    @Mock
    private StoreService storeService;
    @Mock
    private ProductService productService;
    @Mock
    private ProcurementEventRecorder procurementEventRecorder;

    private final PurchaseRequestMapper purchaseRequestMapper = new PurchaseRequestMapperImpl();
    private final PurchaseRequestTransitionHandler transitionHandler = new PurchaseRequestTransitionHandler();

    private PurchaseRequestServiceImpl service;

    private static UserPrincipal principal(UserRole role, UUID organizationId, UUID storeId) {
        return UserPrincipal.builder().userId(UUID.randomUUID()).role(role).organizationId(organizationId)
                .storeId(storeId).build();
    }

    private static ProductResponse product(UUID productId, UUID organizationId, String sku) {
        return new ProductResponse(productId, organizationId, sku, null, "San pham", ProductCategory.FOOD,
                ProductUnit.ITEM, "10000.00", "8000.00", true);
    }

    private static PurchaseRequest pendingRequest(UUID storeId, PurchaseRequestStatus status, UUID createdBy) {
        PurchaseRequest pr = new PurchaseRequest("PR-20260101-abcd1234", storeId, createdBy);
        pr.setId(UUID.randomUUID());
        pr.setStatus(status);
        return pr;
    }

    @BeforeEach
    void setUp() {
        service = new PurchaseRequestServiceImpl(purchaseRequestRepository, purchaseRequestLineRepository,
                purchaseRequestMapper, transitionHandler, storeService, productService, procurementEventRecorder);
    }

    @Test
    void createPurchaseRequest_validLines_savesDraftAndEmitsEvent() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UserPrincipal actor = principal(UserRole.INVENTORY_STAFF, organizationId, storeId);
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, organizationId, "SKU01"));
        when(purchaseRequestRepository.save(any(PurchaseRequest.class))).thenAnswer(inv -> {
            PurchaseRequest pr = inv.getArgument(0);
            pr.setId(UUID.randomUUID());
            return pr;
        });

        var request = new CreatePurchaseRequestRequest(List.of(
                new PurchaseRequestLineItem(productId, 10, "5000.00", "NCC ABC")));
        var response = service.createPurchaseRequest(storeId, request, actor);

        assertThat(response.status()).isEqualTo(PurchaseRequestStatus.DRAFT);
        assertThat(response.storeId()).isEqualTo(storeId);
        assertThat(response.createdBy()).isEqualTo(actor.getUserId());
        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).sku()).isEqualTo("SKU01");
        assertThat(response.lines().get(0).recommendedSupplierName()).isEqualTo("NCC ABC");
    }

    @Test
    void createPurchaseRequest_productFromOtherOrg_throwsBusinessRuleViolation_RULE_13_01() {
        UUID organizationId = UUID.randomUUID();
        UUID otherOrganizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(productService.getProductForCrossModule(productId)).thenReturn(product(productId, otherOrganizationId, "SKU01"));

        var request = new CreatePurchaseRequestRequest(List.of(new PurchaseRequestLineItem(productId, 10, "5000.00", null)));

        assertThatThrownBy(() -> service.createPurchaseRequest(storeId, request,
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-13-01"));
    }

    @Test
    void createPurchaseRequest_wrongStoreScope_throwsAccessDeniedScope() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        var request = new CreatePurchaseRequestRequest(List.of(new PurchaseRequestLineItem(UUID.randomUUID(), 1, "1.00", null)));

        assertThatThrownBy(() -> service.createPurchaseRequest(storeId, request,
                principal(UserRole.INVENTORY_STAFF, organizationId, otherStoreId)))
                .isInstanceOf(AccessDeniedScopeException.class);
    }

    @Test
    void submitPurchaseRequest_fromDraft_transitionsToSubmitted() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        PurchaseRequest pr = pendingRequest(storeId, PurchaseRequestStatus.DRAFT, UUID.randomUUID());
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(purchaseRequestRepository.saveAndFlush(any(PurchaseRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(purchaseRequestLineRepository.findAllByPurchaseRequestId(pr.getId())).thenReturn(List.of());

        var response = service.submitPurchaseRequest(pr.getId(), principal(UserRole.INVENTORY_STAFF, organizationId, storeId));

        assertThat(response.status()).isEqualTo(PurchaseRequestStatus.SUBMITTED);
    }

    @Test
    void submitPurchaseRequest_alreadySubmitted_throwsInvalidStateTransition() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        PurchaseRequest pr = pendingRequest(storeId, PurchaseRequestStatus.SUBMITTED, UUID.randomUUID());
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.submitPurchaseRequest(pr.getId(),
                principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void approvePurchaseRequest_differentApprover_transitionsToApproved() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID makerId = UUID.randomUUID();
        PurchaseRequest pr = pendingRequest(storeId, PurchaseRequestStatus.SUBMITTED, makerId);
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(purchaseRequestRepository.saveAndFlush(any(PurchaseRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(purchaseRequestLineRepository.findAllByPurchaseRequestId(pr.getId())).thenReturn(List.of());

        var response = service.approvePurchaseRequest(pr.getId(), principal(UserRole.STORE_MANAGER, organizationId, storeId));

        assertThat(response.status()).isEqualTo(PurchaseRequestStatus.APPROVED);
        assertThat(response.approvedBy()).isNotEqualTo(makerId);
    }

    @Test
    void approvePurchaseRequest_selfApprove_throwsBusinessRuleViolation_RULE_13_02() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UserPrincipal maker = principal(UserRole.STORE_MANAGER, organizationId, storeId);
        PurchaseRequest pr = pendingRequest(storeId, PurchaseRequestStatus.SUBMITTED, maker.getUserId());
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.approvePurchaseRequest(pr.getId(), maker))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-13-02"));
    }

    @Test
    void approvePurchaseRequest_notSubmitted_throwsInvalidStateTransition() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        PurchaseRequest pr = pendingRequest(storeId, PurchaseRequestStatus.DRAFT, UUID.randomUUID());
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.approvePurchaseRequest(pr.getId(), principal(UserRole.STORE_MANAGER, organizationId, storeId)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectPurchaseRequest_differentApprover_transitionsToRejectedWithReason() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID makerId = UUID.randomUUID();
        PurchaseRequest pr = pendingRequest(storeId, PurchaseRequestStatus.SUBMITTED, makerId);
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(purchaseRequestRepository.saveAndFlush(any(PurchaseRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(purchaseRequestLineRepository.findAllByPurchaseRequestId(pr.getId())).thenReturn(List.of());

        var response = service.rejectPurchaseRequest(pr.getId(), new RejectPurchaseRequestRequest("Gia qua cao"),
                principal(UserRole.STORE_MANAGER, organizationId, storeId));

        assertThat(response.status()).isEqualTo(PurchaseRequestStatus.REJECTED);
        assertThat(response.rejectionReason()).isEqualTo("Gia qua cao");
    }

    @Test
    void rejectPurchaseRequest_selfReject_throwsBusinessRuleViolation_RULE_13_02() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UserPrincipal maker = principal(UserRole.STORE_MANAGER, organizationId, storeId);
        PurchaseRequest pr = pendingRequest(storeId, PurchaseRequestStatus.SUBMITTED, maker.getUserId());
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.rejectPurchaseRequest(pr.getId(), new RejectPurchaseRequestRequest("x"), maker))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).getRuleId()).isEqualTo("RULE-13-02"));
    }

    @Test
    void cancelPurchaseRequest_fromDraft_transitionsToCancelled() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        PurchaseRequest pr = pendingRequest(storeId, PurchaseRequestStatus.DRAFT, UUID.randomUUID());
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(purchaseRequestRepository.saveAndFlush(any(PurchaseRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(purchaseRequestLineRepository.findAllByPurchaseRequestId(pr.getId())).thenReturn(List.of());

        var response = service.cancelPurchaseRequest(pr.getId(), principal(UserRole.INVENTORY_STAFF, organizationId, storeId));

        assertThat(response.status()).isEqualTo(PurchaseRequestStatus.CANCELLED);
    }

    @Test
    void cancelPurchaseRequest_fromSubmitted_transitionsToCancelled() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        PurchaseRequest pr = pendingRequest(storeId, PurchaseRequestStatus.SUBMITTED, UUID.randomUUID());
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);
        when(purchaseRequestRepository.saveAndFlush(any(PurchaseRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(purchaseRequestLineRepository.findAllByPurchaseRequestId(pr.getId())).thenReturn(List.of());

        var response = service.cancelPurchaseRequest(pr.getId(), principal(UserRole.INVENTORY_STAFF, organizationId, storeId));

        assertThat(response.status()).isEqualTo(PurchaseRequestStatus.CANCELLED);
    }

    @Test
    void cancelPurchaseRequest_fromApproved_throwsInvalidStateTransition_RULE_13_03() {
        UUID organizationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        PurchaseRequest pr = pendingRequest(storeId, PurchaseRequestStatus.APPROVED, UUID.randomUUID());
        when(purchaseRequestRepository.findById(pr.getId())).thenReturn(Optional.of(pr));
        when(storeService.getOrganizationIdForStore(storeId)).thenReturn(organizationId);

        assertThatThrownBy(() -> service.cancelPurchaseRequest(pr.getId(), principal(UserRole.INVENTORY_STAFF, organizationId, storeId)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
