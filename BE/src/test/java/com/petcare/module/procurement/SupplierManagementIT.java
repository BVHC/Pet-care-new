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
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.ProductCategory;
import com.petcare.platform.enums.ProductUnit;
import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.UserRole;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E Module 13 — ManageSupplier (RULE-13-04) + gating ACTIVE/INACTIVE trên CreatePurchaseOrder.
 * OrgAdmin tạo Supplier ACTIVE -> CreatePurchaseOrder từ PR đã APPROVED thành công -> OrgAdmin PATCH
 * Supplier sang INACTIVE -> CreatePurchaseOrder lần 2 với cùng Supplier đó -> 400 RULE-13-04 ->
 * non-OrgAdmin tạo Supplier -> 403.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class SupplierManagementIT {

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
    void manageSupplier_activeGatesCreatePurchaseOrder_thenInactiveBlocksIt() throws Exception {
        Organization organization = organizationRepository.save(
                new Organization("ORG-" + System.nanoTime(), "Test Org", null, "123 Test St"));
        Store store = storeRepository.save(new Store(organization.getId(), "ST-" + System.nanoTime(), "Test Store",
                FacilityType.RETAIL_STORE, "456 Test St", "0901234567"));
        Product product = productRepository.save(new Product(organization.getId(), "SKU-" + System.nanoTime(), null,
                "San pham test", ProductCategory.FOOD, ProductUnit.ITEM, new BigDecimal("10000.00"),
                new BigDecimal("8000.00"), true));

        String staffToken = token(UserRole.INVENTORY_STAFF, organization.getId(), store.getId());
        String managerToken = token(UserRole.STORE_MANAGER, organization.getId(), store.getId());
        String orgAdminToken = token(UserRole.ORGANIZATION_ADMIN, organization.getId(), null);

        // non-OrgAdmin tạo Supplier -> 403.
        mvc.perform(post("/api/suppliers")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SUP01\",\"name\":\"NCC Test\"}"))
                .andExpect(status().isForbidden());

        // OrgAdmin tạo Supplier ACTIVE.
        MvcResult supplierCreated = mvc.perform(post("/api/suppliers")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SUP01\",\"name\":\"NCC Test\",\"contactPhone\":\"0909999999\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();
        String supplierId = extractJsonField(supplierCreated, "supplierId");

        // PurchaseRequest APPROVED sẵn để tạo PO.
        String lineJson = "{\"productId\":\"" + product.getId() + "\",\"requestedQuantity\":5,"
                + "\"estimatedUnitPrice\":\"2000.00\"}";
        MvcResult prCreated = mvc.perform(post("/api/stores/{id}/purchase-requests", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[" + lineJson + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String requestId = extractJsonField(prCreated, "requestId");
        mvc.perform(post("/api/purchase-requests/{id}/submit", requestId)
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isOk());
        mvc.perform(post("/api/purchase-requests/{id}/approve", requestId)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());

        // Supplier ACTIVE -> CreatePurchaseOrder thành công.
        mvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchaseRequestId\":\"" + requestId + "\",\"supplierId\":\"" + supplierId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ISSUED"));

        // OrgAdmin chuyển Supplier sang INACTIVE.
        mvc.perform(patch("/api/suppliers/{id}", supplierId)
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        // Supplier INACTIVE -> CreatePurchaseOrder mới (cùng PR APPROVED) -> 400 RULE-13-04.
        mvc.perform(post("/api/purchase-orders")
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchaseRequestId\":\"" + requestId + "\",\"supplierId\":\"" + supplierId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
    }

    private String extractJsonField(MvcResult result, String field) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get(field).asText();
    }
}
