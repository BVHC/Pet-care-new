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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice test cho {@code Pet} + {@code PetRepository} trên Postgres thật
 * (Testcontainers, không H2) — copy wiring {@code AuthFlowIT}:
 * {@code @ServiceConnection} + Flyway migrate schema thật (V1..V4) +
 * {@code ddl-auto=validate}. {@code @Import(JpaAuditingConfig)} để
 * {@code BaseEntity.createdAt/updatedAt NOT NULL} được fill trong slice.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})
@Import(JpaAuditingConfig.class)
class PetRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private PetRepository repo;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private UserRepository users;

    @Autowired
    private PetCaregiverDelegationRepository delegations;

    private UUID newUser(String email) {
        Account account = accounts.saveAndFlush(new Account(email, null, "hash"));
        return users.saveAndFlush(new User(account.getId(), "N " + email)).getId();
    }

    @Test
    void saveAndFindByOwnerId() {
        Account account = accounts.save(
                new Account("pet-owner-" + System.nanoTime() + "@example.com", null, "hash"));
        User owner = users.save(new User(account.getId(), "Pet Owner"));

        Pet pet = new Pet(owner.getId(), "Milo", "DOG");
        repo.save(pet);

        assertThat(repo.findByOwnerId(owner.getId())).hasSize(1);
        assertThat(repo.findByOwnerId(owner.getId(), PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
    }

    @Test
    void findAccessibleBy_returnsOwnedAndActivelyDelegatedPets() {
        UUID owner = newUser("acc-owner@example.com");
        UUID caregiver = newUser("acc-caregiver@example.com");
        Pet owned = repo.saveAndFlush(new Pet(owner, "Mun", "DOG"));
        Pet delegated = repo.saveAndFlush(new Pet(owner, "Bơ", "CAT"));
        Pet unrelated = repo.saveAndFlush(new Pet(owner, "Nâu", "DOG"));

        PetCaregiverDelegation d = new PetCaregiverDelegation();
        d.setPetId(delegated.getId());
        d.setPrimaryOwnerId(owner);
        d.setCaregiverUserId(caregiver);
        d.setCaregiverEmail("acc-caregiver@example.com");
        d.setInvitationTokenHash(UUID.randomUUID().toString());
        d.setStatus(CaregiverStatus.ACTIVE);
        d.setExpiresAt(LocalDateTime.now().plusDays(7));
        delegations.saveAndFlush(d);

        Page<Pet> page = repo.findAccessibleBy(caregiver, LocalDateTime.now(), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Pet::getId).containsExactly(delegated.getId());
        assertThat(page.getContent()).extracting(Pet::getId)
                .doesNotContain(owned.getId(), unrelated.getId());
    }
}
