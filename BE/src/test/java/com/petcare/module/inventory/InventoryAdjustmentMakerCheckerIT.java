package com.petcare.module.inventory;

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
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.outbox.OutboxEventRepository;
import com.petcare.platform.security.JwtTokenProvider;
import com.petcare.platform.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * E2E Module 12 — AdjustInventory/ApproveInventoryAdjustment/RejectInventoryAdjustment
 * (RULE-12-01/02/03 Maker-Checker) + CountInventory sinh phiếu COUNT_VARIANCE. InventoryStaff
 * tạo phiếu -> tự role không được gọi approve (403, @PreAuthorize không cho INVENTORY_STAFF) ->
 * StoreManager khác Store bị chặn scope (403) -> StoreManager đúng Store duyệt thành công (200,
 * cộng sổ InventoryItem, outbox InventoryAdjusted) -> duyệt lại 409 -> StoreManager tự tạo phiếu
 * rồi tự duyệt bị chặn Maker-Checker (400 RULE-12-03) -> OrgAdmin duyệt thay (200) -> CountInventory
 * lệch số sinh phiếu COUNT_VARIANCE PENDING -> duyệt xong rollup khớp đúng số kiểm kê -> Reject
 * flow (200 REJECTED, không đụng rollup, reject lại 409). Dùng Testcontainers Postgres thật, schema
 * V1-V15 qua Flyway thật (spring.jpa.hibernate.ddl-auto=validate), cùng wiring JwtTokenProvider mint
 * token trực tiếp như {@code StoreActivationIT}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class InventoryAdjustmentMakerCheckerIT {

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
    private AccountRepository accountRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private String token(UserRole role, UUID organizationId, UUID storeId) {
        UUID accountId = accountRepository.save(new Account(role + "-" + System.nanoTime() + "@example.com", null, "hash")).getId();
        // inventory_adjustments.created_by/approved_by (RULE-12-03) là FK thật vào users(id) —
        // khác BaseEntity.createdBy/updatedBy (accounts.id, do AuditorAware tự điền) — nên actor
        // dùng ở IT này phải có 1 dòng users thật, không thể random UUID như StoreActivationIT
        // (Store không có FK nào trỏ users.id nên trước giờ không cần).
        UUID userId = userRepository.saveAndFlush(new User(accountId, "Test " + role)).getId();
        UserPrincipal principal = UserPrincipal.builder()
                .userId(userId)
                .accountId(accountId)
                .phone("0900000000")
                .name("Test " + role)
                .role(role)
                .scope(role == UserRole.CUSTOMER ? SecurityScope.CUSTOMER
                        : role == UserRole.ORGANIZATION_ADMIN || role == UserRole.SUPER_ADMIN
                        ? SecurityScope.ORGANIZATION : SecurityScope.STORE)
                .accountStatus(AccountStatus.ACTIVE)
                .organizationId(organizationId)
                .storeId(storeId)
                .build();
        return jwtTokenProvider.generateAccessToken(principal);
    }

    @Test
    void makerCheckerFullFlow_guardsThenSuccessThenIdempotencyThenCountVarianceThenReject() throws Exception {
        Organization organization = organizationRepository.save(
                new Organization("ORG-" + System.nanoTime(), "Test Org", null, "123 Test St"));
        Store store = storeRepository.save(new Store(organization.getId(), "ST-" + System.nanoTime(), "Test Store",
                FacilityType.RETAIL_STORE, "456 Test St", "0901234567"));
        Store otherStore = storeRepository.save(new Store(organization.getId(), "ST2-" + System.nanoTime(), "Other Store",
                FacilityType.RETAIL_STORE, "789 Test St", "0901234568"));
        Product product = productRepository.save(new Product(organization.getId(), "SKU-" + System.nanoTime(), null,
                "San pham test", ProductCategory.FOOD, ProductUnit.ITEM, new BigDecimal("10000.00"),
                new BigDecimal("8000.00"), true));

        String staffToken = token(UserRole.INVENTORY_STAFF, organization.getId(), store.getId());
        String managerToken = token(UserRole.STORE_MANAGER, organization.getId(), store.getId());
        String otherStoreManagerToken = token(UserRole.STORE_MANAGER, organization.getId(), otherStore.getId());
        String orgAdminToken = token(UserRole.ORGANIZATION_ADMIN, organization.getId(), null);

        // ReceiveInventory (InventoryStaff) — thiết lập rollup ban đầu: physical=available=20.
        mvc.perform(post("/api/stores/{id}/inventory/receive", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantity\":20,\"batchNumber\":\"B1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantityAvailable").value(20));

        // AdjustInventory (InventoryStaff) — RULE-12-02, DAMAGE -5.
        MvcResult created = mvc.perform(post("/api/stores/{id}/inventory-adjustments", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantityAdjusted\":-5,\"reason\":\"DAMAGE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        String adjustmentIdA = extractJsonField(created, "adjustmentId");

        // InventoryStaff không được gọi approve (@PreAuthorize chặn ở tầng HTTP, RULE-12-03).
        mvc.perform(post("/api/inventory-adjustments/{id}/approve", adjustmentIdA)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());

        // StoreManager của Store khác bị chặn scope (403) trước cả khi chạm guard status/maker-checker.
        mvc.perform(post("/api/inventory-adjustments/{id}/approve", adjustmentIdA)
                        .header("Authorization", "Bearer " + otherStoreManagerToken))
                .andExpect(status().isForbidden());

        // StoreManager đúng Store duyệt thành công — cộng sổ InventoryItem, outbox InventoryAdjusted.
        mvc.perform(post("/api/inventory-adjustments/{id}/approve", adjustmentIdA)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        mvc.perform(get("/api/stores/{id}/inventory", store.getId())
                        .header("Authorization", "Bearer " + managerToken)
                        .param("productId", product.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].quantityPhysical").value(15))
                .andExpect(jsonPath("$.data.content[0].quantityAvailable").value(15));
        var events = outboxEventRepository.findAll().stream()
                .filter(e -> "InventoryAdjustment".equals(e.getAggregateType()) && adjustmentIdA.equals(e.getAggregateId()))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("InventoryAdjusted");

        // Duyệt lại phiếu đã APPROVED -> 409 (không còn PENDING).
        mvc.perform(post("/api/inventory-adjustments/{id}/approve", adjustmentIdA)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isConflict());

        // Maker-Checker (RULE-12-03) — StoreManager tự tạo rồi tự duyệt bị chặn 400.
        MvcResult createdByManager = mvc.perform(post("/api/stores/{id}/inventory-adjustments", store.getId())
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantityAdjusted\":-2,\"reason\":\"THEFT\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String adjustmentIdB = extractJsonField(createdByManager, "adjustmentId");
        mvc.perform(post("/api/inventory-adjustments/{id}/approve", adjustmentIdB)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));

        // OrgAdmin (checker khác) duyệt thay -> 200, rollup 15-2=13.
        mvc.perform(post("/api/inventory-adjustments/{id}/approve", adjustmentIdB)
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        mvc.perform(get("/api/stores/{id}/inventory", store.getId())
                        .header("Authorization", "Bearer " + managerToken)
                        .param("productId", product.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].quantityPhysical").value(13));

        // CountInventory (RULE-12-02) — kiểm kê lệch số sinh phiếu COUNT_VARIANCE PENDING.
        MvcResult counted = mvc.perform(post("/api/stores/{id}/inventory/count", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"countedQuantity\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variance").value(-3))
                .andReturn();
        String adjustmentIdC = extractJsonField(counted, "adjustmentId");
        assertThat(adjustmentIdC).isNotNull();

        // Duyệt phiếu kiểm kê -> rollup khớp đúng countedQuantity (13 + (-3) = 10).
        mvc.perform(post("/api/inventory-adjustments/{id}/approve", adjustmentIdC)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/stores/{id}/inventory", store.getId())
                        .header("Authorization", "Bearer " + managerToken)
                        .param("productId", product.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].quantityPhysical").value(10))
                .andExpect(jsonPath("$.data.content[0].quantityAvailable").value(10));

        // Kiểm kê khớp số (variance=0) -> không sinh phiếu, không lưu gì thêm.
        mvc.perform(post("/api/stores/{id}/inventory/count", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"countedQuantity\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variance").value(0))
                .andExpect(jsonPath("$.data.adjustmentId").doesNotExist());

        // RejectInventoryAdjustment — 200 REJECTED, không đụng rollup; reject lại -> 409.
        MvcResult createdD = mvc.perform(post("/api/stores/{id}/inventory-adjustments", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantityAdjusted\":100,\"reason\":\"COUNT_VARIANCE\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String adjustmentIdD = extractJsonField(createdD, "adjustmentId");
        mvc.perform(post("/api/inventory-adjustments/{id}/reject", adjustmentIdD)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Khong hop le\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        mvc.perform(get("/api/stores/{id}/inventory", store.getId())
                        .header("Authorization", "Bearer " + managerToken)
                        .param("productId", product.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].quantityPhysical").value(10));
        mvc.perform(post("/api/inventory-adjustments/{id}/reject", adjustmentIdD)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    private String extractJsonField(MvcResult result, String field) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get(field).asText();
    }
}
