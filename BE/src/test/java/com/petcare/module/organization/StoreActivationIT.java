package com.petcare.module.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.catalog.repository.StoreProductOverrideRepository;
import com.petcare.module.catalog.repository.StoreServiceOverrideRepository;
import com.petcare.module.organization.entity.Organization;
import com.petcare.module.organization.entity.Store;
import com.petcare.module.organization.repository.OrganizationRepository;
import com.petcare.module.organization.repository.StoreRepository;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import com.petcare.platform.security.JwtTokenProvider;
import com.petcare.platform.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E Module 03 — ActivateStore + SuspendStore + DeactivateStore (RULE-03-02/03-04, FSM-2):
 * OrgAdmin activate Store DRAFT thất bại (409/400) trước khi đủ 3 điều kiện -> StoreManager cấu
 * hình OperatingHours + StoreResource, OrgAdmin tạo Service -> activate thành công (200, ACTIVE)
 * -> override catalog được khởi tạo (StoreOverrideService#initializeOverridesForStore) ->
 * outbox_events có StoreActivated -> gọi lại lần 2 vẫn 200 idempotent, không tạo trùng
 * override/outbox -> role sai (STORE_MANAGER/CUSTOMER) bị 403 ở tầng HTTP thật -> nối tiếp
 * SuspendStore trên chính Store đã ACTIVE đó: 200 -> SUSPENDED, outbox có thêm StoreSuspended
 * (cộng dồn 2 event), gọi lại lần 2 vẫn idempotent không tạo trùng, role sai vẫn 403 -> nối tiếp
 * reactivate (SUSPENDED -> ACTIVE, event thứ 3) rồi DeactivateStore: 200 -> DEACTIVATED, outbox
 * có thêm StoreDeactivated (cộng dồn 4 event), gọi lại lần 2 vẫn idempotent không tạo trùng, role
 * sai vẫn 403. Dùng Testcontainers Postgres thật, schema V1-V14 qua Flyway thật
 * (spring.jpa.hibernate.ddl-auto=validate), cùng wiring JwtTokenProvider mint token trực tiếp
 * như CatalogFlowIT (bỏ qua chuỗi CreateStaff/CreateStore HTTP, không phải mục tiêu test này).
 * Có thêm 1 test riêng ({@link #deactivateStore_staleVersion_saveAndFlushThrowsImmediately_notDeferredToCommit})
 * chứng minh saveAndFlush ép flush ngay tại call site (không hoãn tới lúc @Transactional commit)
 * — property cốt lõi khiến ObjectOptimisticLockingFailureException bắt được đúng chỗ, map thành
 * 409 CONCURRENCY_CONFLICT thay vì rơi xuống handleGeneric -> 500.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class StoreActivationIT {

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
    private AccountRepository accountRepository;
    @Autowired
    private StoreServiceOverrideRepository storeServiceOverrideRepository;
    @Autowired
    private StoreProductOverrideRepository storeProductOverrideRepository;
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
    void activateStore_fullFlow_guardsThenSuccessThenIdempotentThenRoleDenied() throws Exception {
        Organization organization = organizationRepository.save(
                new Organization("ORG-" + System.nanoTime(), "Test Org", null, "123 Test St"));
        Store store = storeRepository.save(new Store(organization.getId(), "ST-" + System.nanoTime(), "Test Store",
                FacilityType.RETAIL_STORE, "456 Test St", "0901234567"));

        String orgAdminToken = token(UserRole.ORGANIZATION_ADMIN, organization.getId(), null);
        String storeManagerToken = token(UserRole.STORE_MANAGER, organization.getId(), store.getId());
        String customerToken = token(UserRole.CUSTOMER, null, null);

        // RULE-03-02 — Store DRAFT chưa cấu hình gì -> 400 BUSINESS_RULE_VIOLATION, không đổi state.
        mvc.perform(post("/api/stores/{id}/activate", store.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
        assertThat(storeRepository.findById(store.getId()).orElseThrow().getStatus()).isEqualTo(StoreStatus.DRAFT);

        // Điều kiện 1 — OperatingHours (StoreManager, replace-all).
        mvc.perform(put("/api/stores/{id}/operating-hours", store.getId())
                        .header("Authorization", "Bearer " + storeManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":[{\"dayOfWeek\":2,\"openTime\":\"08:00:00\",\"closeTime\":\"18:00:00\",\"isClosed\":false}]}"))
                .andExpect(status().isOk());

        // Vẫn thiếu điều kiện 2/3 -> vẫn 400.
        mvc.perform(post("/api/stores/{id}/activate", store.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isBadRequest());

        // Điều kiện 2 — StoreResource active (StoreManager).
        mvc.perform(post("/api/stores/{id}/resources", store.getId())
                        .header("Authorization", "Bearer " + storeManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceCode\":\"RES01\",\"resourceName\":\"Phong kham 1\",\"resourceType\":\"CLINIC_ROOM\",\"isActive\":true}"))
                .andExpect(status().isCreated());

        // Vẫn thiếu điều kiện 3 -> vẫn 400.
        mvc.perform(post("/api/stores/{id}/activate", store.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isBadRequest());

        // Điều kiện 3 — Service active trong danh mục Organization (OrgAdmin).
        MvcResult serviceCreated = mvc.perform(post("/api/services")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SV01\",\"name\":\"Kham tong quat\",\"category\":\"CLINICAL\",\"basePrice\":\"150000.00\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String serviceId = extractJsonField(serviceCreated, "serviceId");

        // Đủ 3 điều kiện -> 200, ACTIVE.
        mvc.perform(post("/api/stores/{id}/activate", store.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(storeRepository.findById(store.getId()).orElseThrow().getStatus()).isEqualTo(StoreStatus.ACTIVE);

        // Override catalog đã được khởi tạo (StoreOverrideService#initializeOverridesForStore).
        assertThat(storeServiceOverrideRepository.findAllByStoreId(store.getId())).hasSize(1);
        assertThat(storeServiceOverrideRepository.existsByStoreIdAndServiceId(store.getId(), UUID.fromString(serviceId))).isTrue();

        // Outbox event StoreActivated với payload đúng.
        var storeEvents = outboxEventRepository.findAll().stream()
                .filter(e -> "Store".equals(e.getAggregateType()) && store.getId().toString().equals(e.getAggregateId()))
                .toList();
        assertThat(storeEvents).hasSize(1);
        OutboxEvent activatedEvent = storeEvents.get(0);
        assertThat(activatedEvent.getEventType()).isEqualTo("StoreActivated");
        assertThat(activatedEvent.getPayload()).contains(store.getId().toString())
                .contains(organization.getId().toString())
                .contains("ACTIVE");

        // Idempotent — gọi lại lần 2 vẫn 200, không tạo trùng override/outbox.
        mvc.perform(post("/api/stores/{id}/activate", store.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(storeServiceOverrideRepository.findAllByStoreId(store.getId())).hasSize(1);
        assertThat(outboxEventRepository.findAll().stream()
                .filter(e -> "Store".equals(e.getAggregateType()) && store.getId().toString().equals(e.getAggregateId()))
                .count()).isEqualTo(1);

        // Role sai bị chặn ở tầng HTTP thật.
        mvc.perform(post("/api/stores/{id}/activate", store.getId())
                        .header("Authorization", "Bearer " + storeManagerToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/stores/{id}/activate", store.getId())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        // ---- SuspendStore (RULE-03-04, FSM-2) — nối tiếp trên Store đã ACTIVE ở trên ----

        // Thành công: ACTIVE -> SUSPENDED, 200.
        mvc.perform(post("/api/stores/{id}/suspend", store.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        assertThat(storeRepository.findById(store.getId()).orElseThrow().getStatus()).isEqualTo(StoreStatus.SUSPENDED);

        // Outbox: cộng dồn với StoreActivated đã có từ trên -> đúng 2 event cho Store này,
        // event mới nhất là StoreSuspended với payload đúng.
        var storeEventsAfterSuspend = outboxEventRepository.findAll().stream()
                .filter(e -> "Store".equals(e.getAggregateType()) && store.getId().toString().equals(e.getAggregateId()))
                .toList();
        assertThat(storeEventsAfterSuspend).hasSize(2);
        var suspendedEvent = storeEventsAfterSuspend.stream()
                .filter(e -> "StoreSuspended".equals(e.getEventType())).findFirst().orElseThrow();
        assertThat(suspendedEvent.getPayload()).contains(store.getId().toString())
                .contains(organization.getId().toString())
                .contains("SUSPENDED");

        // Idempotent — gọi lại lần 2 vẫn 200, không tạo trùng outbox event.
        mvc.perform(post("/api/stores/{id}/suspend", store.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        assertThat(outboxEventRepository.findAll().stream()
                .filter(e -> "Store".equals(e.getAggregateType()) && store.getId().toString().equals(e.getAggregateId()))
                .count()).isEqualTo(2);

        // Role sai bị chặn ở tầng HTTP thật.
        mvc.perform(post("/api/stores/{id}/suspend", store.getId())
                        .header("Authorization", "Bearer " + storeManagerToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/stores/{id}/suspend", store.getId())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());

        // ---- DeactivateStore (RULE-03-04, FSM-2) — nối tiếp trên chính Store trên (đang SUSPENDED)
        // ----

        // Reactivate trước — DeactivateStore chỉ nhận nguồn ACTIVE (FSM-2); cấu hình OperatingHours/
        // StoreResource/Service vẫn còn nguyên từ trên nên không cần lặp lại guard RULE-03-02.
        mvc.perform(post("/api/stores/{id}/activate", store.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // Thành công: ACTIVE -> DEACTIVATED, 200.
        mvc.perform(post("/api/stores/{id}/deactivate", store.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEACTIVATED"));
        assertThat(storeRepository.findById(store.getId()).orElseThrow().getStatus()).isEqualTo(StoreStatus.DEACTIVATED);

        // Outbox: cộng dồn 4 event cho Store này (StoreActivated x2 + StoreSuspended + StoreDeactivated).
        var storeEventsAfterDeactivate = outboxEventRepository.findAll().stream()
                .filter(e -> "Store".equals(e.getAggregateType()) && store.getId().toString().equals(e.getAggregateId()))
                .toList();
        assertThat(storeEventsAfterDeactivate).hasSize(4);
        var deactivatedEvent = storeEventsAfterDeactivate.stream()
                .filter(e -> "StoreDeactivated".equals(e.getEventType())).findFirst().orElseThrow();
        assertThat(deactivatedEvent.getPayload()).contains(store.getId().toString())
                .contains(organization.getId().toString())
                .contains("DEACTIVATED");

        // Idempotent — gọi lại lần 2 vẫn 200, không tạo trùng outbox event.
        mvc.perform(post("/api/stores/{id}/deactivate", store.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEACTIVATED"));
        assertThat(outboxEventRepository.findAll().stream()
                .filter(e -> "Store".equals(e.getAggregateType()) && store.getId().toString().equals(e.getAggregateId()))
                .count()).isEqualTo(4);

        // Role sai bị chặn ở tầng HTTP thật.
        mvc.perform(post("/api/stores/{id}/deactivate", store.getId())
                        .header("Authorization", "Bearer " + storeManagerToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/stores/{id}/deactivate", store.getId())
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }

    /**
     * Chứng minh cụ thể (không phải giả định) rằng saveAndFlush ép flush NGAY tại call site — khác
     * save() thường (hoãn tới lúc @Transactional commit, NGOÀI try/catch nội bộ Service, khiến
     * ObjectOptimisticLockingFailureException rơi xuống handleGeneric -> 500 thay vì 409, xem plan
     * "Transactional-integrity finding"). Dùng kỹ thuật detached-entity chuẩn của Hibernate/Spring
     * Data (đọc 2 lần trên 2 transaction ngắn tách biệt — findById() của Spring Data tự mở/đóng
     * transaction riêng khi không có @Transactional bao ngoài test method) thay vì 2 thread thật:
     * xác định (deterministic), không flaky, vẫn dùng Postgres thật + Hibernate flush timing thật
     * (Mockito không mô phỏng được điều này — lý do StoreServiceImplTest chỉ có thể stub exception,
     * không thể chứng minh THỜI ĐIỂM nó được ném).
     */
    @Test
    void deactivateStore_staleVersion_saveAndFlushThrowsImmediately_notDeferredToCommit() throws Exception {
        Organization organization = organizationRepository.save(
                new Organization("ORG-" + System.nanoTime(), "Test Org", null, "123 Test St"));
        Store store = new Store(organization.getId(), "ST-" + System.nanoTime(), "Test Store",
                FacilityType.RETAIL_STORE, "456 Test St", "0901234567");
        store.setStatus(StoreStatus.ACTIVE);
        store = storeRepository.saveAndFlush(store);

        String orgAdminToken = token(UserRole.ORGANIZATION_ADMIN, organization.getId(), null);

        // "Reader cũ" — bản copy đã detach ngay khi findById() trả về (transaction riêng của nó đã
        // đóng), giữ nguyên version tại thời điểm đọc.
        Store staleCopy = storeRepository.findById(store.getId()).orElseThrow();

        // "Request thắng cuộc" — 1 request HTTP thật, tự commit, bump version.
        mvc.perform(post("/api/stores/{id}/deactivate", store.getId())
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());

        // "Request thua cuộc" — ghi tiếp bằng bản copy đã cũ version qua ĐÚNG method saveAndFlush
        // mà StoreServiceImpl dùng: phải ném ObjectOptimisticLockingFailureException NGAY tại đây
        // (không phải lúc nào đó về sau ở transaction khác) — đây chính là điều save() thường
        // KHÔNG làm được.
        staleCopy.setStatus(StoreStatus.SUSPENDED);
        assertThatThrownBy(() -> storeRepository.saveAndFlush(staleCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private String extractJsonField(MvcResult result, String field) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get(field).asText();
    }
}
