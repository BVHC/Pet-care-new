package com.petcare.module.pet.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.notification.service.NotificationService;
import com.petcare.module.pet.dto.CaregiverDelegationResponse;
import com.petcare.module.pet.dto.CaregiverInvitationResponse;
import com.petcare.module.pet.dto.InviteCaregiverRequest;
import com.petcare.module.pet.dto.RevokeCaregiverRequest;
import com.petcare.module.pet.service.CaregiverDelegationService;
import com.petcare.module.pet.service.CaregiverInvitationOutcome;
import com.petcare.platform.config.CorsConfig;
import com.petcare.platform.config.SecurityConfig;
import com.petcare.platform.enums.AccountStatus;
import com.petcare.platform.enums.SecurityScope;
import com.petcare.platform.enums.UserRole;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wiring copy PetControllerTest (@WebMvcTest + principal giả + cùng @Import
 * SecurityConfig/CorsConfig/GlobalExceptionHandler/filter chain).
 */
@WebMvcTest(CaregiverDelegationController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class,
        TraceIdFilter.class, JwtAuthenticationFilter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class CaregiverDelegationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean CaregiverDelegationService service;
    @MockitoBean NotificationService notificationService;
    @MockitoBean JwtTokenProvider tokenProvider;
    @MockitoBean TokenBlacklistService tokenBlacklistService;

    private final UUID petId = UUID.randomUUID();

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
    void invite_dispatchesEmailWhenTaskCreated() throws Exception {
        UUID me = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        CaregiverInvitationResponse body = new CaregiverInvitationResponse(
                UUID.randomUUID(), petId, "cg@example.com", "INVITED",
                LocalDateTime.now().plusDays(7), null, null);
        when(service.inviteCaregiver(any(), eq(petId), any(InviteCaregiverRequest.class)))
                .thenReturn(new CaregiverInvitationOutcome(body, taskId, "cg@example.com"));

        mockMvc.perform(post("/api/pets/{id}/caregiver-invitations", petId)
                        .with(authentication(auth(me)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new InviteCaregiverRequest("cg@example.com", null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("INVITED"));

        verify(notificationService).dispatch(taskId, "cg@example.com");
    }

    @Test
    void invite_skipsDispatchWhenNoTask() throws Exception {
        UUID me = UUID.randomUUID();
        CaregiverInvitationResponse body = new CaregiverInvitationResponse(
                UUID.randomUUID(), petId, "new@example.com", "INVITED",
                LocalDateTime.now().plusDays(7), null, "raw-token");
        when(service.inviteCaregiver(any(), eq(petId), any(InviteCaregiverRequest.class)))
                .thenReturn(new CaregiverInvitationOutcome(body, null, "new@example.com"));

        mockMvc.perform(post("/api/pets/{id}/caregiver-invitations", petId)
                        .with(authentication(auth(me)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new InviteCaregiverRequest("new@example.com", null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invitationToken").value("raw-token"));

        verify(notificationService, never()).dispatch(any(), any());
    }

    @Test
    void invite_rejectsInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/pets/{id}/caregiver-invitations", petId)
                        .with(authentication(auth(UUID.randomUUID())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caregiverEmail\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void accept_returns200() throws Exception {
        when(service.acceptCaregiverInvitation(any(), eq("raw-token")))
                .thenReturn(new CaregiverDelegationResponse(UUID.randomUUID(), petId,
                        UUID.randomUUID(), "cg@example.com", "ACTIVE",
                        LocalDateTime.now().plusDays(7), null));

        mockMvc.perform(post("/api/caregiver-invitations/{token}/accept", "raw-token")
                        .with(authentication(auth(UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void revoke_returns200() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.revokeCaregiver(any(), eq(petId), any(RevokeCaregiverRequest.class)))
                .thenReturn(new CaregiverDelegationResponse(UUID.randomUUID(), petId,
                        UUID.randomUUID(), "cg@example.com", "REVOKED",
                        LocalDateTime.now().plusDays(7), null));

        mockMvc.perform(post("/api/pets/{id}/caregiver-revoke", petId)
                        .with(authentication(auth(me)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RevokeCaregiverRequest("cg@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
    }
}
