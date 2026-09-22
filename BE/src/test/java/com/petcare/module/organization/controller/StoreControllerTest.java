package com.petcare.module.organization.controller;

import com.petcare.module.organization.dto.StoreResponse;
import com.petcare.module.organization.service.StoreService;
import com.petcare.platform.config.CorsConfig;
import com.petcare.platform.config.SecurityConfig;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.FacilityType;
import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.StoreStatus;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.GlobalExceptionHandler;
import com.petcare.platform.security.JwtAuthenticationFilter;
import com.petcare.platform.security.JwtTokenProvider;
import com.petcare.platform.security.RestAccessDeniedHandler;
import com.petcare.platform.security.RestAuthenticationEntryPoint;
import com.petcare.platform.security.TraceIdFilter;
import com.petcare.platform.security.UserPrincipal;
import com.petcare.platform.security.token.TokenBlacklistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 03 — xác nhận @PreAuthorize trên POST /api/stores/{id}/activate,
 * POST /api/stores/{id}/suspend và POST /api/stores/{id}/deactivate thật sự chặn đúng role
 * ở tầng Spring Security (@WebMvcTest + SecurityConfig thật), ĐỘC LẬP với mock ở Service-layer
 * (StoreServiceImplTest) — nếu annotation bị gõ nhầm (VD lỡ thêm STORE_MANAGER), test này đỏ
 * ngay mà không cần đợi IT. Wiring copy PetControllerTest/CaregiverDelegationControllerTest —
 * module organization trước đây chưa có controller test nào (file đầu tiên).
 */
@WebMvcTest(StoreController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class,
        TraceIdFilter.class, JwtAuthenticationFilter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class StoreControllerTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private StoreService storeService;
    @MockitoBean
    private JwtTokenProvider tokenProvider;
    @MockitoBean
    private TokenBlacklistService tokenBlacklistService;

    private static UsernamePasswordAuthenticationToken auth(UserRole role, UUID organizationId, UUID storeId) {
        UserPrincipal principal = UserPrincipal.builder()
                .userId(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .phone("0911111111")
                .name("Test " + role)
                .role(role)
                .scope(role == UserRole.CUSTOMER ? SecurityScope.CUSTOMER
                        : role == UserRole.ORGANIZATION_ADMIN || role == UserRole.SUPER_ADMIN
                        ? SecurityScope.ORGANIZATION : SecurityScope.STORE)
                .accountStatus(AccountStatus.ACTIVE)
                .organizationId(organizationId)
                .storeId(storeId)
                .build();
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void activateStore_superAdmin_delegatesToService_200() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeService.activateStore(eq(storeId), any())).thenReturn(
                new StoreResponse(storeId, organizationId, "HN01", "Chi nhanh Q1", FacilityType.RETAIL_STORE,
                        "123 Le Loi", "0901234567", StoreStatus.ACTIVE));

        mvc.perform(post("/api/stores/{id}/activate", storeId)
                        .with(authentication(auth(UserRole.SUPER_ADMIN, null, null))))
                .andExpect(status().isOk());
    }

    @Test
    void activateStore_organizationAdmin_delegatesToService_200() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeService.activateStore(eq(storeId), any())).thenReturn(
                new StoreResponse(storeId, organizationId, "HN01", "Chi nhanh Q1", FacilityType.RETAIL_STORE,
                        "123 Le Loi", "0901234567", StoreStatus.ACTIVE));

        mvc.perform(post("/api/stores/{id}/activate", storeId)
                        .with(authentication(auth(UserRole.ORGANIZATION_ADMIN, organizationId, null))))
                .andExpect(status().isOk());
    }

    @Test
    void activateStore_storeManager_deniedAtHttpLayer_403() throws Exception {
        // RULE-03-02/FSM-2 — @PreAuthorize không có STORE_MANAGER (khác UpdateStore). Nếu bị gõ
        // nhầm thêm role này vào annotation, test này đỏ trước khi tới Service-layer.
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        mvc.perform(post("/api/stores/{id}/activate", storeId)
                        .with(authentication(auth(UserRole.STORE_MANAGER, organizationId, storeId))))
                .andExpect(status().isForbidden());
    }

    @Test
    void activateStore_customer_deniedAtHttpLayer_403() throws Exception {
        UUID storeId = UUID.randomUUID();

        mvc.perform(post("/api/stores/{id}/activate", storeId)
                        .with(authentication(auth(UserRole.CUSTOMER, null, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void activateStore_withoutAuth_401() throws Exception {
        UUID storeId = UUID.randomUUID();

        mvc.perform(post("/api/stores/{id}/activate", storeId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void activateStore_serviceThrowsScopeException_403() throws Exception {
        // Actor có role hợp lệ ở tầng @PreAuthorize (ORGANIZATION_ADMIN) nhưng Service-layer
        // (RoleScopeGuard.assertCanManageOrganization) từ chối vì khác Org — GlobalExceptionHandler
        // phải map đúng 403, không phải 500.
        UUID storeId = UUID.randomUUID();
        when(storeService.activateStore(eq(storeId), any()))
                .thenThrow(new AccessDeniedScopeException("ORGANIZATION:" + storeId, "ORGANIZATION"));

        mvc.perform(post("/api/stores/{id}/activate", storeId)
                        .with(authentication(auth(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendStore_superAdmin_delegatesToService_200() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeService.suspendStore(eq(storeId), any())).thenReturn(
                new StoreResponse(storeId, organizationId, "HN01", "Chi nhanh Q1", FacilityType.RETAIL_STORE,
                        "123 Le Loi", "0901234567", StoreStatus.SUSPENDED));

        mvc.perform(post("/api/stores/{id}/suspend", storeId)
                        .with(authentication(auth(UserRole.SUPER_ADMIN, null, null))))
                .andExpect(status().isOk());
    }

    @Test
    void suspendStore_organizationAdmin_delegatesToService_200() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeService.suspendStore(eq(storeId), any())).thenReturn(
                new StoreResponse(storeId, organizationId, "HN01", "Chi nhanh Q1", FacilityType.RETAIL_STORE,
                        "123 Le Loi", "0901234567", StoreStatus.SUSPENDED));

        mvc.perform(post("/api/stores/{id}/suspend", storeId)
                        .with(authentication(auth(UserRole.ORGANIZATION_ADMIN, organizationId, null))))
                .andExpect(status().isOk());
    }

    @Test
    void suspendStore_storeManager_deniedAtHttpLayer_403() throws Exception {
        // RULE-03-04/FSM-2 — @PreAuthorize không có STORE_MANAGER (cùng activateStore).
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        mvc.perform(post("/api/stores/{id}/suspend", storeId)
                        .with(authentication(auth(UserRole.STORE_MANAGER, organizationId, storeId))))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendStore_customer_deniedAtHttpLayer_403() throws Exception {
        UUID storeId = UUID.randomUUID();

        mvc.perform(post("/api/stores/{id}/suspend", storeId)
                        .with(authentication(auth(UserRole.CUSTOMER, null, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendStore_withoutAuth_401() throws Exception {
        UUID storeId = UUID.randomUUID();

        mvc.perform(post("/api/stores/{id}/suspend", storeId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void suspendStore_serviceThrowsScopeException_403() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(storeService.suspendStore(eq(storeId), any()))
                .thenThrow(new AccessDeniedScopeException("ORGANIZATION:" + storeId, "ORGANIZATION"));

        mvc.perform(post("/api/stores/{id}/suspend", storeId)
                        .with(authentication(auth(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivateStore_superAdmin_delegatesToService_200() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeService.deactivateStore(eq(storeId), any())).thenReturn(
                new StoreResponse(storeId, organizationId, "HN01", "Chi nhanh Q1", FacilityType.RETAIL_STORE,
                        "123 Le Loi", "0901234567", StoreStatus.DEACTIVATED));

        mvc.perform(post("/api/stores/{id}/deactivate", storeId)
                        .with(authentication(auth(UserRole.SUPER_ADMIN, null, null))))
                .andExpect(status().isOk());
    }

    @Test
    void deactivateStore_organizationAdmin_delegatesToService_200() throws Exception {
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        when(storeService.deactivateStore(eq(storeId), any())).thenReturn(
                new StoreResponse(storeId, organizationId, "HN01", "Chi nhanh Q1", FacilityType.RETAIL_STORE,
                        "123 Le Loi", "0901234567", StoreStatus.DEACTIVATED));

        mvc.perform(post("/api/stores/{id}/deactivate", storeId)
                        .with(authentication(auth(UserRole.ORGANIZATION_ADMIN, organizationId, null))))
                .andExpect(status().isOk());
    }

    @Test
    void deactivateStore_storeManager_deniedAtHttpLayer_403() throws Exception {
        // RULE-03-04/FSM-2 — @PreAuthorize không có STORE_MANAGER (cùng activateStore/suspendStore).
        UUID storeId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        mvc.perform(post("/api/stores/{id}/deactivate", storeId)
                        .with(authentication(auth(UserRole.STORE_MANAGER, organizationId, storeId))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivateStore_customer_deniedAtHttpLayer_403() throws Exception {
        UUID storeId = UUID.randomUUID();

        mvc.perform(post("/api/stores/{id}/deactivate", storeId)
                        .with(authentication(auth(UserRole.CUSTOMER, null, null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivateStore_withoutAuth_401() throws Exception {
        UUID storeId = UUID.randomUUID();

        mvc.perform(post("/api/stores/{id}/deactivate", storeId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deactivateStore_serviceThrowsScopeException_403() throws Exception {
        UUID storeId = UUID.randomUUID();
        when(storeService.deactivateStore(eq(storeId), any()))
                .thenThrow(new AccessDeniedScopeException("ORGANIZATION:" + storeId, "ORGANIZATION"));

        mvc.perform(post("/api/stores/{id}/deactivate", storeId)
                        .with(authentication(auth(UserRole.ORGANIZATION_ADMIN, UUID.randomUUID(), null))))
                .andExpect(status().isForbidden());
    }
}
