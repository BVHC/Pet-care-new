package com.petcare.module.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.catalog.service.StoreOverrideService;
import com.petcare.module.organization.entity.Organization;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.FacilityType;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E Module 05 (Catalog): OrgAdmin tạo Product/Service -> StoreManager PUT giá TRƯỚC khi override
 * được khởi tạo (404, RULE-05-07) -> initializeOverridesForStore (đứng thay ActivateStore chưa
 * triển khai, xem plan) -> PUT giá lại thành công -> Customer xem storefront chỉ thấy item ACTIVE ->
 * OrgAdmin vô hiệu hóa Product (RULE-05-02) -> Customer không còn thấy nữa. Dùng
 * {@link JwtTokenProvider} mint token trực tiếp cho actor (bỏ qua chuỗi CreateStaff/CreateStore vì
 * không phải mục tiêu test này) thay vì flow HTTP đăng ký như PetFlowIT — JwtAuthenticationFilter
 * tin tưởng claim JWT, không lookup lại DB mỗi request, NHƯNG {@code created_by}/{@code updated_by}
 * (BaseEntity, ghi bởi AuditorAware#accountId) có FK thật tới {@code accounts.id}
 * (products_created_by_fkey) — actor nào GHI dữ liệu (OrgAdmin, StoreManager) bắt buộc có 1 dòng
 * {@link Account} thật đứng sau accountId của token, Customer (chỉ đọc) thì không cần.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class CatalogFlowIT {

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
    private StoreOverrideService storeOverrideService;
    @Autowired
    private AccountRepository accountRepository;

    private String token(UserRole role, UUID organizationId, UUID storeId) {
        // Customer không ghi dữ liệu trong flow này — không cần accountId trỏ tới Account thật.
        UUID accountId = role == UserRole.CUSTOMER ? UUID.randomUUID()
                : accountRepository.save(new Account(role + "-" + System.nanoTime() + "@example.com", null, "hash")).getId();
        UserPrincipal principal = UserPrincipal.builder()
                .userId(UUID.randomUUID())
                .accountId(accountId)
                .phone("0900000000")
                .name("Test " + role)
                .role(role)
                .scope(role == UserRole.CUSTOMER ? SecurityScope.CUSTOMER
                        : role == UserRole.ORGANIZATION_ADMIN ? SecurityScope.ORGANIZATION : SecurityScope.STORE)
                .accountStatus(AccountStatus.ACTIVE)
                .organizationId(organizationId)
                .storeId(storeId)
                .build();
        return jwtTokenProvider.generateAccessToken(principal);
    }

    @Test
    void catalogFlow_createConfigureAndStorefrontVisibility() throws Exception {
        Organization organization = organizationRepository.save(
                new Organization("ORG-" + System.nanoTime(), "Test Org", null, "123 Test St"));
        Store store = storeRepository.save(new Store(organization.getId(), "ST-" + System.nanoTime(), "Test Store",
                FacilityType.RETAIL_STORE, "456 Test St", "0901234567"));

        String orgAdminToken = token(UserRole.ORGANIZATION_ADMIN, organization.getId(), null);
        String storeManagerToken = token(UserRole.STORE_MANAGER, organization.getId(), store.getId());
        String customerToken = token(UserRole.CUSTOMER, null, null);

        // RULE-05-01/02 — OrgAdmin tạo Product.
        MvcResult productCreated = mvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU01\",\"name\":\"Thuc an cho meo\",\"category\":\"FOOD\",\"unit\":\"BAG\",\"basePrice\":\"199000.00\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String productId = productJsonPath(productCreated, "productId");

        // RULE-05-03 — OrgAdmin tạo Service.
        MvcResult serviceCreated = mvc.perform(post("/api/services")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SV01\",\"name\":\"Kham tong quat\",\"category\":\"CLINICAL\",\"basePrice\":\"150000.00\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String serviceId = productJsonPath(serviceCreated, "serviceId");

        // RULE-05-07 — override chưa được khởi tạo (Store chưa ACTIVATE) => 404.
        mvc.perform(put("/api/stores/{id}/products/{pid}/price", store.getId(), productId)
                        .header("Authorization", "Bearer " + storeManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":\"180000.00\"}"))
                .andExpect(status().isNotFound());

        // Đứng thay ActivateStore (Module 03 chưa triển khai) — gọi thẳng service.
        storeOverrideService.initializeOverridesForStore(organization.getId(), store.getId());

        // RULE-05-05 — sau khi override tồn tại, PUT giá thành công.
        mvc.perform(put("/api/stores/{id}/products/{pid}/price", store.getId(), productId)
                        .header("Authorization", "Bearer " + storeManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":\"180000.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value("180000.00"));

        // RULE-05-04 — mở khả dụng dịch vụ tại Store (mặc định kế thừa is_active=true từ
        // initializeOverridesForStore, nhưng verify tường minh qua PUT availability).
        mvc.perform(put("/api/stores/{id}/services/{sid}/availability", store.getId(), serviceId)
                        .header("Authorization", "Bearer " + storeManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(true));

        // RULE-05-06 — Customer xem storefront: giá hiệu dụng = override, item đang ACTIVE.
        mvc.perform(get("/api/stores/{id}/products", store.getId())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].price").value("180000.00"));

        mvc.perform(get("/api/stores/{id}/services", store.getId())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].isActive").value(true));

        // RULE-05-02 — OrgAdmin vô hiệu hóa Product (không hard-delete).
        mvc.perform(patch("/api/products/{id}", productId)
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));

        // Customer không còn thấy Product đã vô hiệu hóa (RULE-05-06, activeOnly luôn true).
        mvc.perform(get("/api/stores/{id}/products", store.getId())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0));

        // Staff (StoreManager) vẫn thấy Product đã tắt (ASSUMPTION A4 — activeOnly mặc định false).
        mvc.perform(get("/api/stores/{id}/products", store.getId())
                        .header("Authorization", "Bearer " + storeManagerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    private String productJsonPath(MvcResult result, String field) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get(field).asText();
    }
}
