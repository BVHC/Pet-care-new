package com.petcare.module.pet.repository;

import com.petcare.module.auth.entity.Account;
import com.petcare.module.auth.repository.AccountRepository;
import com.petcare.module.iam.entity.User;
import com.petcare.module.iam.repository.UserRepository;
import com.petcare.module.pet.entity.Pet;
import com.petcare.platform.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
}
