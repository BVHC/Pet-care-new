package com.petcare.module.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.iam.entity.User;
import com.petcare.module.iam.repository.UserRepository;
import com.petcare.module.organization.entity.Organization;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.module.procurement.entity.Supplier;
import com.petcare.module.procurement.repository.SupplierRepository;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.outbox.OutboxEventRepository;
import com.petcare.platform.security.JwtTokenProvider;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E Module 13 — CreatePurchaseRequest/SubmitPurchaseRequest/Approve-RejectPurchaseRequest/
 * CancelPurchaseRequest (RULE-13-01→03, FSM-12) + CreatePurchaseOrder (RULE-13-04). InventoryStaff
 * tạo PR -> submit -> tự role không được approve (403) -> StoreManager Store khác bị chặn scope
 * (403) -> StoreManager tạo PR khác tự duyệt bị chặn Maker-Checker (400 RULE-13-02) -> OrgAdmin
 * duyệt PR đầu (200 APPROVED) -> duyệt lại (409) -> CreatePurchaseOrder từ PR APPROVED + Supplier
 * ACTIVE (201 ISSUED, totalAmount đúng) -> PR reject kèm lý do (200 REJECTED) -> CreatePurchaseOrder
 * từ PR REJECTED (400 RULE-13-04) -> Cancel từ DRAFT/SUBMITTED (200) và từ APPROVED (409). Dùng
 * Testcontainers Postgres thật, schema V1-V16 qua Flyway thật (ddl-auto=validate).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class PurchaseRequestMakerCheckerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private String token(UserRole role, UUID organizationId, UUID storeId) {
        UUID accountId = accountRepository.save(new Account(role + "-" + System.nanoTime() + "@example.com", null, "hash")).getId();
        UUID userId = userRepository.saveAndFlush(new User(accountId, "Test " + role)).getId();
        UserPrincipal principal = UserPrincipal.builder()
                .userId(userId)
                .accountId(accountId)
                .phone("0900000000")
                .name("Test " + role)
                .role(role)
                .scope(role == UserRole.ORGANIZATION_ADMIN || role == UserRole.SUPER_ADMIN
                        ? SecurityScope.ORGANIZATION : SecurityScope.STORE)
                .accountStatus(AccountStatus.ACTIVE)
                .organizationId(organizationId)
                .storeId(storeId)
                .build();
        return jwtTokenProvider.generateAccessToken(principal);
    }

    @Test
    void makerCheckerFullFlow_guardsThenSuccessThenPurchaseOrderThenRejectThenCancel() throws Exception {
        Organization organization = organizationRepository.save(
                new Organization("ORG-" + System.nanoTime(), "Test Org", null, "123 Test St"));
        Store store = storeRepository.save(new Store(organization.getId(), "ST-" + System.nanoTime(), "Test Store",
                FacilityType.RETAIL_STORE, "456 Test St", "0901234567"));
        Store otherStore = storeRepository.save(new Store(organization.getId(), "ST2-" + System.nanoTime(), "Other Store",
                FacilityType.RETAIL_STORE, "789 Test St", "0901234568"));
        Product product = productRepository.save(new Product(organization.getId(), "SKU-" + System.nanoTime(), null,
                "San pham test", ProductCategory.FOOD, ProductUnit.ITEM, new BigDecimal("10000.00"),
                new BigDecimal("8000.00"), true));
        Supplier supplier = supplierRepository.save(new Supplier(organization.getId(), "SUP-" + System.nanoTime(),
                "NCC Test", "0909999999", "supplier@example.com", "999 Supplier St"));

        String staffToken = token(UserRole.INVENTORY_STAFF, organization.getId(), store.getId());
        String managerToken = token(UserRole.STORE_MANAGER, organization.getId(), store.getId());
        String otherStoreManagerToken = token(UserRole.STORE_MANAGER, organization.getId(), otherStore.getId());
        String orgAdminToken = token(UserRole.ORGANIZATION_ADMIN, organization.getId(), null);

        // CreatePurchaseRequest (InventoryStaff) — RULE-13-01.
        String lineJson = "{\"productId\":\"" + product.getId() + "\",\"requestedQuantity\":10,"
                + "\"estimatedUnitPrice\":\"5000.00\",\"recommendedSupplierName\":\"NCC Goi Y\"}";
        MvcResult created = mvc.perform(post("/api/stores/{id}/purchase-requests", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[" + lineJson + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        String requestIdA = extractJsonField(created, "requestId");

        // SubmitPurchaseRequest.
        mvc.perform(post("/api/purchase-requests/{id}/submit", requestIdA)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        // InventoryStaff không được gọi approve (@PreAuthorize chặn, RULE-13-02).
        mvc.perform(post("/api/purchase-requests/{id}/approve", requestIdA)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());

        // StoreManager Store khác bị chặn scope trước khi chạm guard status/maker-checker.
        mvc.perform(post("/api/purchase-requests/{id}/approve", requestIdA)
                        .header("Authorization", "Bearer " + otherStoreManagerToken))
                .andExpect(status().isForbidden());

        // StoreManager đúng Store duyệt thành công.
        mvc.perform(post("/api/purchase-requests/{id}/approve", requestIdA)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        var events = outboxEventRepository.findAll().stream()
                .filter(e -> "PurchaseRequest".equals(e.getAggregateType()) && requestIdA.equals(e.getAggregateId()))
                .toList();
        assertThat(events).extracting("eventType").contains("PurchaseRequestCreated", "PurchaseRequestSubmitted", "PurchaseRequestApproved");

        // Duyệt lại -> 409 (không còn SUBMITTED).
        mvc.perform(post("/api/purchase-requests/{id}/approve", requestIdA)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isConflict());

        // Maker-Checker (RULE-13-02) — StoreManager tự tạo rồi tự duyệt bị chặn 400.
        MvcResult createdByManager = mvc.perform(post("/api/stores/{id}/purchase-requests", store.getId())
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[" + lineJson + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String requestIdB = extractJsonField(createdByManager, "requestId");
        mvc.perform(post("/api/purchase-requests/{id}/submit", requestIdB)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
        mvc.perform(post("/api/purchase-requests/{id}/approve", requestIdB)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));

        // OrgAdmin (checker khác) duyệt thay -> 200.
        mvc.perform(post("/api/purchase-requests/{id}/approve", requestIdB)
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // CreatePurchaseOrder từ PR A (APPROVED) + Supplier ACTIVE -> 201 ISSUED.
        MvcResult poCreated = mvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchaseRequestId\":\"" + requestIdA + "\",\"supplierId\":\"" + supplier.getId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.totalAmount").value("50000.00"))
                .andExpect(jsonPath("$.data.supplierName").value("NCC Test"))
                .andReturn();
        String orderId = extractJsonField(poCreated, "orderId");

        mvc.perform(get("/api/purchase-orders/{id}", orderId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].sku").value(product.getSku()))
                .andExpect(jsonPath("$.data.lines[0].orderedQuantity").value(10));

        // Reject flow — PR thứ 3, submit rồi reject kèm lý do (actor khác), sau đó tạo PO từ PR
        // REJECTED -> 400 RULE-13-04.
        MvcResult createdC = mvc.perform(post("/api/stores/{id}/purchase-requests", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[" + lineJson + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String requestIdC = extractJsonField(createdC, "requestId");
        mvc.perform(post("/api/purchase-requests/{id}/submit", requestIdC)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());
        mvc.perform(post("/api/purchase-requests/{id}/reject", requestIdC)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Gia qua cao\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value("Gia qua cao"));
        mvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchaseRequestId\":\"" + requestIdC + "\",\"supplierId\":\"" + supplier.getId() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));

        // Cancel — PR thứ 4 hủy từ DRAFT (200), PR thứ 5 submit rồi hủy từ SUBMITTED (200), PR B đã
        // APPROVED hủy -> 409 (RULE-13-03).
        MvcResult createdD = mvc.perform(post("/api/stores/{id}/purchase-requests", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[" + lineJson + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String requestIdD = extractJsonField(createdD, "requestId");
        mvc.perform(post("/api/purchase-requests/{id}/cancel", requestIdD)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        MvcResult createdE = mvc.perform(post("/api/stores/{id}/purchase-requests", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[" + lineJson + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String requestIdE = extractJsonField(createdE, "requestId");
        mvc.perform(post("/api/purchase-requests/{id}/submit", requestIdE)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());
        mvc.perform(post("/api/purchase-requests/{id}/cancel", requestIdE)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        mvc.perform(post("/api/purchase-requests/{id}/cancel", requestIdB)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isConflict());
    }

    private String extractJsonField(MvcResult result, String field) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get(field).asText();
    }
}
