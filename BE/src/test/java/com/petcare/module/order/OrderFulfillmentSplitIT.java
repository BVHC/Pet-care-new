package com.petcare.module.order;

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
 * E2E Module 14 — CreateOrder/CheckoutOrder/ViewOrder/CancelOrder (RULE-14-01/02/03/04/07/08,
 * D-03). Customer tạo đơn Online (2 dòng) -> PENDING_PAYMENT + reservedUntil, kho bị giữ chỗ
 * (quantityReserved tăng, quantityAvailable giảm) -> GET bởi chính chủ OK, bởi Customer khác 403
 * -> checkout refresh TTL -> Customer hủy đơn -> CANCELLED, kho được nhả (quantityReserved về 0,
 * quantityAvailable phục hồi) -> hủy lại -> 409. Receptionist tạo đơn POS cho Customer khác ->
 * PAID, trừ quantityPhysical trực tiếp (không qua giữ chỗ) -> Receptionist thử hủy đơn POS (PAID)
 * -> 409 (RULE-14-07 chỉ cho hủy từ PENDING_PAYMENT). Dùng Testcontainers Postgres thật, schema
 * V1-V17 qua Flyway thật (ddl-auto=validate).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class OrderFulfillmentSplitIT {

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

    private String token(UserRole role, UUID organizationId, UUID storeId, UUID[] userIdOut) {
        UUID accountId = accountRepository.save(new Account(role + "-" + System.nanoTime() + "@example.com", null, "hash")).getId();
        UUID userId = userRepository.saveAndFlush(new User(accountId, "Test " + role)).getId();
        if (userIdOut != null) {
            userIdOut[0] = userId;
        }
        UserPrincipal principal = UserPrincipal.builder()
                .userId(userId)
                .accountId(accountId)
                .phone("0900000000")
                .name("Test " + role)
                .role(role)
                .scope(role == UserRole.CUSTOMER ? SecurityScope.CUSTOMER : SecurityScope.STORE)
                .accountStatus(AccountStatus.ACTIVE)
                .organizationId(organizationId)
                .storeId(storeId)
                .build();
        return jwtTokenProvider.generateAccessToken(principal);
    }

    @Test
    void onlineHoldThenCancel_thenPosInstantDeduct_thenCancelPosRejected() throws Exception {
        Organization organization = organizationRepository.save(
                new Organization("ORG-" + System.nanoTime(), "Test Org", null, "123 Test St"));
        Store store = storeRepository.save(new Store(organization.getId(), "ST-" + System.nanoTime(), "Test Store",
                FacilityType.RETAIL_STORE, "456 Test St", "0901234567"));
        Product product = productRepository.save(new Product(organization.getId(), "SKU-" + System.nanoTime(), null,
                "San pham test", ProductCategory.FOOD, ProductUnit.ITEM, new BigDecimal("10000.00"),
                new BigDecimal("8000.00"), true));

        UUID[] customerUserId = new UUID[1];
        UUID[] otherCustomerUserId = new UUID[1];
        String customerToken = token(UserRole.CUSTOMER, null, null, customerUserId);
        String otherCustomerToken = token(UserRole.CUSTOMER, null, null, otherCustomerUserId);
        String receptionistToken = token(UserRole.RECEPTIONIST, organization.getId(), store.getId(), null);
        String staffToken = token(UserRole.INVENTORY_STAFF, organization.getId(), store.getId(), null);

        // Seed tồn kho 20 qua ReceiveInventory (Module 12) đã có sẵn.
        mvc.perform(post("/api/stores/{id}/inventory/receive", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantity\":20,\"batchNumber\":\"B1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantityAvailable").value(20));

        // CreateOrder Online — RULE-14-04 giữ chỗ 2 đơn vị.
        MvcResult created = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"" + store.getId() + "\",\"channel\":\"ONLINE_APP\","
                                + "\"items\":[{\"productId\":\"" + product.getId() + "\",\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.reservedUntil").exists())
                .andExpect(jsonPath("$.data.subtotal").value("20000.00"))
                .andExpect(jsonPath("$.data.items[0].sku").value(product.getSku()))
                .andReturn();
        String orderId = extractJsonField(created, "orderId");

        mvc.perform(get("/api/stores/{id}/inventory", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .param("productId", product.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].quantityReserved").value(2))
                .andExpect(jsonPath("$.data.content[0].quantityAvailable").value(18))
                .andExpect(jsonPath("$.data.content[0].quantityPhysical").value(20));

        // ViewOrder — chính chủ OK, Customer khác 403.
        mvc.perform(get("/api/orders/{id}", orderId).header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/orders/{id}", orderId).header("Authorization", "Bearer " + otherCustomerToken))
                .andExpect(status().isForbidden());

        // CheckoutOrder — refresh TTL, giữ nguyên PENDING_PAYMENT.
        mvc.perform(post("/api/orders/{id}/checkout", orderId).header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"));

        // CancelOrder bởi chính chủ -> CANCELLED, nhả kho.
        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(get("/api/stores/{id}/inventory", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .param("productId", product.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].quantityReserved").value(0))
                .andExpect(jsonPath("$.data.content[0].quantityAvailable").value(20));

        // Hủy lại -> 409 (RULE-14-08 bất biến).
        mvc.perform(post("/api/orders/{id}/cancel", orderId).header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict());

        var events = outboxEventRepository.findAll().stream()
                .filter(e -> "Order".equals(e.getAggregateType()) && orderId.equals(e.getAggregateId()))
                .toList();
        assertThat(events).extracting("eventType").contains("OrderCreated", "OrderCancelled");

        // CreateOrder POS (Receptionist, đại diện otherCustomer) — trừ physical trực tiếp, không giữ chỗ.
        MvcResult posCreated = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + receptionistToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"" + store.getId() + "\",\"channel\":\"POS_RETAIL\","
                                + "\"customerId\":\"" + otherCustomerUserId[0] + "\","
                                + "\"items\":[{\"productId\":\"" + product.getId() + "\",\"quantity\":3}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.reservedUntil").doesNotExist())
                .andReturn();
        String posOrderId = extractJsonField(posCreated, "orderId");

        mvc.perform(get("/api/stores/{id}/inventory", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .param("productId", product.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].quantityPhysical").value(17))
                .andExpect(jsonPath("$.data.content[0].quantityReserved").value(0))
                .andExpect(jsonPath("$.data.content[0].quantityAvailable").value(17));

        // CancelOrder trên đơn POS (PAID, không phải PENDING_PAYMENT) -> 409.
        mvc.perform(post("/api/orders/{id}/cancel", posOrderId).header("Authorization", "Bearer " + receptionistToken))
                .andExpect(status().isConflict());
    }

    private String extractJsonField(MvcResult result, String field) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get(field).asText();
    }
}
