package com.petcare.module.inventory;

import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.catalog.entity.Product;
import com.petcare.module.catalog.repository.ProductRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E Module 12 — ReceiveInventory/IssueInventory theo FEFO (RULE-12-11), guard hết tồn khả
 * dụng (RULE-12-05), cách ly tenant giữa Organization (RULE-12-01), và side-effect
 * TriggerLowStockAlert (outbox {@code LowStockAlertTriggered} khi vừa chuyển từ "không thấp"
 * sang "thấp"). 2 lô khác hạn nhập qua ReceiveInventory 2 lần -> IssueInventory tiêu thụ đúng
 * lô hết hạn sớm nhất trước (xác nhận qua GET inventory-batches, order thật từ Postgres, không
 * phải danh sách giả lập như unit test) -> vượt tổng tồn -> 409 không mutate gì -> issue thêm
 * để tồn khả dụng xuống dưới ngưỡng min_stock_level -> outbox LowStockAlertTriggered -> khác
 * Organization cùng gọi Receive với Product không thuộc Org mình -> 400 RULE-12-01.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class InventoryFefoIssueIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mvc;
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
    private OutboxEventRepository outboxEventRepository;

    private String token(UserRole role, UUID organizationId, UUID storeId) {
        UUID accountId = accountRepository.save(new Account(role + "-" + System.nanoTime() + "@example.com", null, "hash")).getId();
        UserPrincipal principal = UserPrincipal.builder()
                .userId(UUID.randomUUID())
                .accountId(accountId)
                .phone("0900000000")
                .name("Test " + role)
                .role(role)
                .scope(SecurityScope.STORE)
                .accountStatus(AccountStatus.ACTIVE)
                .organizationId(organizationId)
                .storeId(storeId)
                .build();
        return jwtTokenProvider.generateAccessToken(principal);
    }

    @Test
    void issueInventory_fefoOrderThenInsufficientThenLowStockAlertThenTenantIsolation() throws Exception {
        Organization organization = organizationRepository.save(
                new Organization("ORG-" + System.nanoTime(), "Test Org", null, "123 Test St"));
        Store store = storeRepository.save(new Store(organization.getId(), "ST-" + System.nanoTime(), "Test Store",
                FacilityType.RETAIL_STORE, "456 Test St", "0901234567"));
        Product product = productRepository.save(new Product(organization.getId(), "SKU-" + System.nanoTime(), null,
                "San pham test", ProductCategory.FOOD, ProductUnit.ITEM, new BigDecimal("10000.00"),
                new BigDecimal("8000.00"), true));
        String staffToken = token(UserRole.INVENTORY_STAFF, organization.getId(), store.getId());

        // 2 lô khác hạn — EARLY hết hạn trước LATE.
        mvc.perform(post("/api/stores/{id}/inventory/receive", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantity\":5,\"batchNumber\":\"EARLY\","
                                + "\"expiryDate\":\"" + LocalDate.now().plusDays(5) + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/stores/{id}/inventory/receive", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantity\":20,\"batchNumber\":\"LATE\","
                                + "\"expiryDate\":\"" + LocalDate.now().plusDays(30) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantityAvailable").value(25));

        // Issue 8 -> tiêu thụ hết EARLY (5) trước, phần dư 3 lấy từ LATE (20 -> 17), RULE-12-11.
        mvc.perform(post("/api/stores/{id}/inventory/issue", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantity\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantityAvailable").value(17));

        mvc.perform(get("/api/stores/{id}/inventory-batches", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .param("productId", product.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].batchNumber").value("EARLY"))
                .andExpect(jsonPath("$.data.content[0].quantity").value(0))
                .andExpect(jsonPath("$.data.content[1].batchNumber").value("LATE"))
                .andExpect(jsonPath("$.data.content[1].quantity").value(17));

        // Vượt tổng tồn -> 400 BUSINESS_RULE_VIOLATION (RULE-12-05, BusinessRuleViolationException
        // theo convention 04 — không phải 409, 409 chỉ dành cho InvalidStateTransition/
        // ConcurrencyConflict), không mutate gì (rollup vẫn 17 ở bước sau).
        mvc.perform(post("/api/stores/{id}/inventory/issue", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantity\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
        mvc.perform(get("/api/stores/{id}/inventory", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .param("productId", product.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].quantityAvailable").value(17))
                .andExpect(jsonPath("$.data.content[0].lowStock").value(false));

        // Issue thêm 13 -> 17-13=4 <= min_stock_level mặc định 5 -> TriggerLowStockAlert (side-effect).
        mvc.perform(post("/api/stores/{id}/inventory/issue", store.getId())
                        .header("Authorization", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantity\":13}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantityAvailable").value(4))
                .andExpect(jsonPath("$.data.lowStock").value(true));
        var lowStockEvents = outboxEventRepository.findAll().stream()
                .filter(e -> "Inventory".equals(e.getAggregateType()) && "LowStockAlertTriggered".equals(e.getEventType()))
                .toList();
        assertThat(lowStockEvents).hasSize(1);
        // Cột payload là jsonb — Postgres chuẩn hóa lại thứ tự key + thêm khoảng trắng sau dấu hai
        // chấm khi đọc ra text (khác chuỗi thô lúc ghi), nên so khớp theo đúng canonical format
        // "key": value thay vì "key":value.
        assertThat(lowStockEvents.get(0).getPayload()).contains("\"currentStock\": 4").contains("\"reorderThreshold\": 5");

        // Cách ly tenant (RULE-12-01) — Store của Organization khác gọi Receive với Product của
        // Organization này -> 400, không phải 404 (Product tồn tại thật, chỉ sai Organization).
        Organization otherOrganization = organizationRepository.save(
                new Organization("ORG2-" + System.nanoTime(), "Other Org", null, "999 Test St"));
        Store otherStore = storeRepository.save(new Store(otherOrganization.getId(), "ST3-" + System.nanoTime(),
                "Other Org Store", FacilityType.RETAIL_STORE, "111 Test St", "0909999999"));
        String otherStaffToken = token(UserRole.INVENTORY_STAFF, otherOrganization.getId(), otherStore.getId());
        mvc.perform(post("/api/stores/{id}/inventory/receive", otherStore.getId())
                        .header("Authorization", "Bearer " + otherStaffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + product.getId() + "\",\"quantity\":1,\"batchNumber\":\"X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
    }
}
