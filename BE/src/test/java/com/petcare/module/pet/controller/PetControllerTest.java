package com.petcare.module.pet.controller;

import com.petcare.module.pet.service.PetService;
import com.petcare.platform.config.CorsConfig;
import com.petcare.platform.config.SecurityConfig;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.GlobalExceptionHandler;
import com.petcare.platform.exception.ResourceNotFoundException;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PetController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class,
        TraceIdFilter.class, JwtAuthenticationFilter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class PetControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PetService svc;

    @MockitoBean
    JwtTokenProvider tokenProvider;

    @MockitoBean
    TokenBlacklistService tokenBlacklistService;

    private static UsernamePasswordAuthenticationToken auth(UUID userId) {
        UserPrincipal principal = UserPrincipal.builder()
                .userId(userId)
                .accountId(UUID.randomUUID())
                .phone("0911111111")
                .name("Test User")
                .role(UserRole.CUSTOMER)
                .scope(SecurityScope.CUSTOMER)
                .accountStatus(AccountStatus.ACTIVE)
                .build();
        return new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
    }

    @Test
    void create_invalidName_400() throws Exception {
        mvc.perform(post("/api/pets")
                        .with(authentication(auth(UUID.randomUUID())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"species\":\"DOG\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detail_missingPet_404() throws Exception {
        UUID me = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(svc.detail(eq(me), eq(id)))
                .thenThrow(new ResourceNotFoundException("Pet", id));

        mvc.perform(get("/api/pets/{id}", id)
                        .with(authentication(auth(me))))
                .andExpect(status().isNotFound());
    }

    @Test
    void detail_otherOwnersPet_403() throws Exception {
        UUID me = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(svc.detail(eq(me), eq(id)))
                .thenThrow(new AccessDeniedScopeException("PET", "OWNER"));

        mvc.perform(get("/api/pets/{id}", id)
                        .with(authentication(auth(me))))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_withoutAuth_401() throws Exception {
        when(svc.list(any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

        mvc.perform(get("/api/pets"))
                .andExpect(status().isUnauthorized());
    }
}
