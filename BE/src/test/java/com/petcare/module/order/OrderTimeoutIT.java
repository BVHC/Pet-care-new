package com.petcare.module.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.repository.ProductRepository;
import com.petcare.module.iam.entity.User;
import com.petcare.module.iam.repository.UserRepository;
import com.petcare.module.order.entity.Order;
import com.petcare.module.order.job.ProcessOrderTimeoutJob;
import com.petcare.module.order.repository.OrderRepository;
import com.petcare.module.organization.entity.Organization;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.OrderStatus;
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
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E Module 14 — ProcessOrderTimeout (RULE-14-04, job nền, không endpoint). Tạo đơn Online, ép
 * {@code reserved_until} về quá khứ thẳng qua repository (không chờ 15 phút thật) -> gọi trực
 * tiếp bean {@link ProcessOrderTimeoutJob} -> đơn chuyển CANCELLED, kho được nhả, outbox có
 * {@code OrderTimedOut}. {@code app.order-timeout.enabled=true} ghi đè profile test (mặc định
 * tắt) để bean job tồn tại trong context của riêng IT này.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "app.order-timeout.enabled=true"})
class OrderTimeoutIT {

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
    private OrderRepository orderRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private ProcessOrderTimeoutJob processOrderTimeoutJob;

    private String token(UserRole role, UUID organizationId, UUID storeId) {
        UUID accountId = accountRepository.save(new Account(role + "-" + System.nanoTime() + "@example.com", null, "hash")).getId();
        UUID userId = userRepository.saveAndFlush(new User(accountId, "Test " + role)).getId();
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
    void expiredHold_cancelledByJob_releasesReservation() throws Exception {
        Organization organization = organizationRepository.save(
                new Organization("ORG-" + System.nanoTime(), "Test Org", null, "123 Test St"));
        Store store = storeRepository.save(new Store(organization.getId(), "ST-" + System.nanoTime(), "Test Store",
                FacilityType.RETAIL_STORE, "456 Test St", "0901234567"));
        Product product = productRepository.save(new Product(organization.getId(), "SKU-" + System.nanoTime(), null,
                "San pham test", ProductCategory.FOOD, ProductUnit.ITEM, new BigDecimal("10000.00"),
                new BigDecimal("8000.00"), true));
        String customerToken = token(UserRole.CUSTOMER, null, null);
        String staffToken = token(UserRole.INVENTORY_STAFF, organization.getId(), store.getId());

        mvc.perform(post("/api/stores/{id}/inventory/receive", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantity\":10,\"batchNumber\":\"B1\"}"))
                .andExpect(status().isOk());

        MvcResult created = mvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\":\"" + store.getId() + "\",\"channel\":\"ONLINE_APP\","
                                + "\"items\":[{\"productId\":\"" + product.getId() + "\",\"quantity\":4}]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = extractJsonField(created, "orderId");

        // Ép hết hạn giữ chỗ (đã quá 15 phút) thẳng qua repository, không chờ TTL thật.
        Order order = orderRepository.findById(UUID.fromString(orderId)).orElseThrow();
        order.setReservedUntil(LocalDateTime.now().minusMinutes(1));
        orderRepository.saveAndFlush(order);

        processOrderTimeoutJob.expireStaleOrders();

        mvc.perform(get("/api/orders/{id}", orderId).header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        mvc.perform(get("/api/stores/{id}/inventory", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .param("productId", product.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].quantityReserved").value(0))
                .andExpect(jsonPath("$.data.content[0].quantityAvailable").value(10));

        var events = outboxEventRepository.findAll().stream()
                .filter(e -> "Order".equals(e.getAggregateType()) && orderId.equals(e.getAggregateId())
                        && "OrderTimedOut".equals(e.getEventType()))
                .toList();
        assertThat(events).hasSize(1);
    }

    private String extractJsonField(MvcResult result, String field) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get(field).asText();
    }
}
