package com.petcare.module.pet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.auth.dto.LoginRequest;
import com.petcare.module.auth.dto.RegisterRequest;
import com.petcare.module.auth.dto.VerifyOtpRequest;
import com.petcare.module.auth.entity.Otp;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.auth.repository.OtpRepository;
import com.petcare.module.auth.service.AuthService;
import com.petcare.module.iam.service.UserProvisioningService;
import com.petcare.module.notification.gateway.EmailGateway;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E owner-guard: user A register→verify→login→CRUD pet của mình,
 * user B bị 403 khi chạm vào pet của A. Postgres thật (Testcontainers),
 * JWT thật qua MockMvc — không field ngày sinh nên miễn nhiễm quirk TZ.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class PetFlowIT {

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

    @Test
    @Transactional // OtpRepository.findTop... mang @Lock(PESSIMISTIC_WRITE) nên test cần tx (MockMvc cùng thread join tx này)
    void ownerCrudAndCrossOwnerForbidden() throws Exception {
        String emailA = "peta-" + System.nanoTime() + "@example.com";
        String tokenA = registerAndLogin(emailA);
        String ownerA = ownerOf(emailA).toString();

        MvcResult created = mvc.perform(post("/api/pets")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Milo\",\"species\":\"DOG\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ownerId").value(ownerA))
                .andExpect(jsonPath("$.data.name").value("Milo"))
                .andReturn();
        JsonNode pet = objectMapper.readTree(created.getResponse().getContentAsString()).get("data");
        String petId = pet.get("id").asText();

        mvc.perform(get("/api/pets")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(petId))
                .andExpect(jsonPath("$.data.content[0].ownerId").value(ownerA));

        mvc.perform(get("/api/pets/{id}", petId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(petId))
                .andExpect(jsonPath("$.data.name").value("Milo"));

        mvc.perform(patch("/api/pets/{id}", petId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Milo Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Milo Updated"));

        String emailB = "petb-" + System.nanoTime() + "@example.com";
        String tokenB = registerAndLogin(emailB);
        assertThat(ownerOf(emailB).toString()).isNotEqualTo(ownerA);

        mvc.perform(get("/api/pets/{id}", petId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/pets/{id}", petId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isForbidden());
    }
}
