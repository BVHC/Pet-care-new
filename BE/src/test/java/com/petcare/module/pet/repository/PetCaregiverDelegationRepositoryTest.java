package com.petcare.module.pet.repository;

import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.iam.entity.User;
import com.petcare.module.iam.repository.UserRepository;
import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.entity.PetCaregiverDelegation;
import com.petcare.platform.config.JpaAuditingConfig;
import com.petcare.platform.enums.CaregiverStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test trên Postgres thật (Testcontainers, không H2) — copy wiring
 * PetRepositoryTest: @ServiceConnection + Flyway migrate schema thật (V1..V5)
 * + ddl-auto=validate.
 */
@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
class PetCaregiverDelegationRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired PetCaregiverDelegationRepository delegations;
    @Autowired PetRepository pets;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;

    private UUID newUser(String email) {
        Account account = accounts.saveAndFlush(new Account(email, null, "hash"));
        return users.saveAndFlush(new User(account.getId(), "N " + email)).getId();
    }

    private PetCaregiverDelegation newDelegation(UUID petId, UUID ownerId, UUID caregiverId,
                                                 CaregiverStatus status, LocalDateTime expiresAt,
                                                 LocalDateTime validUntil) {
        PetCaregiverDelegation d = new PetCaregiverDelegation();
        d.setPetId(petId);
        d.setPrimaryOwnerId(ownerId);
        d.setCaregiverUserId(caregiverId);
        d.setCaregiverEmail("cg-" + UUID.randomUUID() + "@example.com");
        d.setInvitationTokenHash(UUID.randomUUID().toString());
        d.setStatus(status);
        d.setExpiresAt(expiresAt);
        d.setValidUntil(validUntil);
        return delegations.saveAndFlush(d);
    }

    @Test
    void existsActiveDelegation_trueForActiveWithoutValidUntil() {
        UUID owner = newUser("o1@example.com");
        UUID caregiver = newUser("c1@example.com");
        UUID petId = pets.saveAndFlush(new Pet(owner, "Mun", "DOG")).getId();
        newDelegation(petId, owner, caregiver, CaregiverStatus.ACTIVE, LocalDateTime.now().plusDays(7), null);

        assertThat(delegations.existsActiveDelegation(petId, caregiver, LocalDateTime.now())).isTrue();
    }

    @Test
    void existsActiveDelegation_falseWhenValidUntilAlreadyPassed() {
        UUID owner = newUser("o2@example.com");
        UUID caregiver = newUser("c2@example.com");
        UUID petId = pets.saveAndFlush(new Pet(owner, "Mun", "DOG")).getId();
        newDelegation(petId, owner, caregiver, CaregiverStatus.ACTIVE,
                LocalDateTime.now().plusDays(7), LocalDateTime.now().minusMinutes(1));

        assertThat(delegations.existsActiveDelegation(petId, caregiver, LocalDateTime.now())).isFalse();
    }

    @Test
    void findExpiredInvitations_returnsOnlyInvitedPastExpiry() {
        UUID owner = newUser("o3@example.com");
        UUID petId = pets.saveAndFlush(new Pet(owner, "Mun", "DOG")).getId();
        PetCaregiverDelegation stale = newDelegation(petId, owner, null, CaregiverStatus.INVITED,
                LocalDateTime.now().minusMinutes(1), null);
        newDelegation(petId, owner, null, CaregiverStatus.INVITED, LocalDateTime.now().plusDays(7), null);

        List<PetCaregiverDelegation> found = delegations.findExpiredInvitations(LocalDateTime.now());

        assertThat(found).extracting(PetCaregiverDelegation::getId).containsExactly(stale.getId());
    }

    @Test
    void findExpiredDelegations_ignoresNullValidUntil() {
        UUID owner = newUser("o4@example.com");
        UUID caregiver = newUser("c4@example.com");
        UUID petId = pets.saveAndFlush(new Pet(owner, "Mun", "DOG")).getId();
        newDelegation(petId, owner, caregiver, CaregiverStatus.ACTIVE, LocalDateTime.now().plusDays(7), null);

        assertThat(delegations.findExpiredDelegations(LocalDateTime.now())).isEmpty();
    }
}
