package com.petcare.module.pet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.auth.dto.LoginRequest;
import com.petcare.module.auth.dto.RegisterRequest;
import com.petcare.module.auth.dto.VerifyOtpRequest;
import com.petcare.module.auth.entity.Otp;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.auth.repository.OtpRepository;
import com.petcare.module.auth.service.AuthService;
import com.petcare.module.iam.service.UserProvisioningService;
import com.petcare.module.notification.entity.NotificationTask;
import com.petcare.module.notification.gateway.EmailGateway;
import com.petcare.module.notification.repository.NotificationTaskRepository;
import com.petcare.platform.enums.OtpPurpose;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E caregiver delegation: A tạo pet → mời B → B accept → B đọc được pet →
 * A revoke → B mất quyền ngay (RULE-04-08); và hủy lời mời treo chặn accept (D-04).
 * Wiring + helper registerAndLogin/ownerOf copy nguyên từ PetFlowIT.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class CaregiverFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserProvisioningService users;

    @Autowired
    private NotificationTaskRepository notificationTasks;

    @MockitoBean
    private EmailGateway emailGateway;

    private String registerAndLogin(String email) {
        when(emailGateway.providerName()).thenReturn("GMAIL_SMTP");
        when(emailGateway.send(anyString(), anyString(), anyString())).thenReturn("msg-id");

        authService.registerAccount(new RegisterRequest(email, null, "password123", "Nguyen Van A"));
        Otp otp = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, OtpPurpose.REGISTRATION).orElseThrow();
        authService.verifyOtp(new VerifyOtpRequest(email, otp.getOtpCode()));
        return authService.login(new LoginRequest(email, "password123"), "junit-agent", "127.0.0.1").accessToken();
    }

    private UUID ownerOf(String email) {
        UUID accountId = accountRepository.findByEmail(email).orElseThrow().getId();
        return users.findByAccountId(accountId).getId();
    }

    private String createPet(String token, String name) throws Exception {
        MvcResult created = mvc.perform(post("/api/pets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"species\":\"DOG\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .get("data").get("id").asText();
    }

    /**
     * Người được mời đã có tài khoản nên raw token đi qua email, không nằm trong
     * response (spec D-02). Đọc lại từ notification_tasks.content — nội dung do
     * CaregiverDelegationServiceImpl ghi dạng "... Mã lời mời: <token>".
     */
    private String capturedInvitationToken(UUID caregiverUserId) {
        NotificationTask task = notificationTasks
                .findTopByRecipientUserIdOrderByCreatedAtDesc(caregiverUserId).orElseThrow();
        String marker = "Mã lời mời: ";
        return task.getContent().substring(task.getContent().indexOf(marker) + marker.length()).trim();
    }

    /**
     * A tạo pet -> mời B (B đã có tài khoản) -> B accept -> B đọc được pet ->
     * A revoke -> B bị 403 UNAUTHORIZED_DELEGATED_ACTION ngay lập tức (RULE-04-08).
     */
    @Test
    @Transactional
    void caregiverCanReadDelegatedPetUntilRevoked() throws Exception {
        String emailA = "cg-owner-" + System.nanoTime() + "@example.com";
        String emailB = "cg-care-" + System.nanoTime() + "@example.com";
        String tokenA = registerAndLogin(emailA);
        String tokenB = registerAndLogin(emailB);
        String petId = createPet(tokenA, "Mun");

        mvc.perform(post("/api/pets/{id}/caregiver-invitations", petId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caregiverEmail\":\"" + emailB + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("INVITED"))
                // B đã có tài khoản nên token đi qua email, KHÔNG nằm trong response (D-02).
                .andExpect(jsonPath("$.data.invitationToken").doesNotExist());

        String rawToken = capturedInvitationToken(ownerOf(emailB));

        mvc.perform(post("/api/caregiver-invitations/{token}/accept", rawToken)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mvc.perform(get("/api/pets/{id}", petId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());

        mvc.perform(get("/api/pets").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(petId));

        mvc.perform(post("/api/pets/{id}/caregiver-revoke", petId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caregiverEmail\":\"" + emailB + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        // RULE-04-08 — mất quyền ngay, không đợi tick cron nào.
        mvc.perform(get("/api/pets/{id}", petId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED_DELEGATED_ACTION"));
    }

    /** Spec D-04 — chủ hủy lời mời đang treo, người được mời không accept được nữa. */
    @Test
    @Transactional
    void revokingPendingInvitationBlocksAccept() throws Exception {
        String emailA = "cg-owner2-" + System.nanoTime() + "@example.com";
        String emailB = "cg-care2-" + System.nanoTime() + "@example.com";
        String tokenA = registerAndLogin(emailA);
        String tokenB = registerAndLogin(emailB);
        String petId = createPet(tokenA, "Bo");

        mvc.perform(post("/api/pets/{id}/caregiver-invitations", petId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caregiverEmail\":\"" + emailB + "\"}"))
                .andExpect(status().isCreated());
        String rawToken = capturedInvitationToken(ownerOf(emailB));

        mvc.perform(post("/api/pets/{id}/caregiver-revoke", petId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caregiverEmail\":\"" + emailB + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        mvc.perform(post("/api/caregiver-invitations/{token}/accept", rawToken)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }
}
