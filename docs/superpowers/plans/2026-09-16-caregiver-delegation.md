# Caregiver Delegation (M04) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Primary Owner ủy quyền chăm sóc một Pet cho người khác qua lời mời có TTL 7 ngày, người được mời chấp nhận/từ chối, chủ thu hồi tức thì — và Caregiver `ACTIVE` đọc được Pet được ủy quyền (RULE-04-04 → RULE-04-09, FSM-3).

**Architecture:** Tất cả trong `module/pet/` vì `PetCaregiverDelegation` là child entity của aggregate Pet. FSM handler là transition map thuần (precedent `AccountTransitionHandler`), service gọi `validateTransition()` rồi `setStatus()`. Luật phân quyền Pet tách vào `PetAccessGuard` — nơi duy nhất biết "ủy quyền còn hiệu lực", dùng chung bởi `detail()` và JPQL của `list()`. Job hết hạn sao khuôn `OtpExpiryJob`; email lời mời đi qua `NotificationService` với dispatch **sau commit** như `AuthController` + `RegistrationOutcome`.

**Tech Stack:** Spring Boot 3.5.15 / Java 21 / Spring Data JPA + PostgreSQL 17 + Flyway / MapStruct 1.6.3 / springdoc 2.8.11 / spring-boot-starter-validation / Testcontainers Postgres (`@ServiceConnection`).

**Spec:** `docs/superpowers/specs/2026-09-16-caregiver-delegation-design.md` — đọc trước khi bắt đầu. Mọi quyết định D-01→D-10 và assumption A-01→A-04 nằm ở đó.

## Global Constraints

- Boot 3.5.15 / Java 21 authoritative (`BE/pom.xml`) — không thêm dependency mới.
- Package `com.petcare.module.pet.{controller,service,repository,entity,dto,mapper,fsm,job,exception}`; `platform/` không phụ thuộc ngược `module/*`.
- **Cấm import trực tiếp entity/repository của module khác.** Phụ thuộc cross-module duy nhất được phép trong plan này là `AuthService.findUserIdByActiveAccountEmail()` (Task 7).
- Entity `extends platform.model.BaseEntity`; Lombok `@Getter @Setter @NoArgsConstructor`, không `@Data`/`@Builder` trên entity.
- DTO là Java record + Bean Validation; mapping bằng MapStruct `componentModel="spring"`; cấm trả Entity qua Controller; Controller trả `ApiResponse.created/ok`.
- Tên method transition **trùng chính xác** Command trong `docs/01-business-operations.md`: `inviteCaregiver`, `acceptCaregiverInvitation`, `rejectCaregiverInvitation`, `revokeCaregiver`, `processInvitationExpiry`, `processDelegationExpiry`.
- Transition map lấy **đúng** mermaid FSM-3 **sau khi Task 14 cập nhật** — không thừa, không thiếu cạnh.
- `@Transactional` ở Service (write mặc định, read `readOnly=true`); không ở Controller/Repository.
- Không sửa `V1__init_schema.sql`; migration mới là `V5__caregiver_delegations.sql`.
- Hai exception riêng của module được phép (tiêu chí §4.2.2 của `04-exception-handling.md`) — **bắt buộc ghi lý do trong comment class**. Ngoài hai cái đó, chỉ dùng 5 base exception ở `platform/exception/`.
- Guard luôn tự kiểm thời hạn tại thời điểm request (D-08). Không bao giờ chỉ đọc `status`.
- TDD iron law: không viết production code khi chưa có test đỏ.
- YAGNI: không thêm endpoint, trạng thái, cột, hay abstraction nào ngoài spec.

---

### Task 1: Migration `V5__caregiver_delegations.sql`

**Files:**
- Create: `BE/src/main/resources/db/migration/V5__caregiver_delegations.sql`
- Test: không test riêng — xác minh qua Flyway migrate ở Task 2.

**Interfaces:**
- Consumes: `V1__init_schema.sql:305-315` (bảng `pet_caregiver_delegations` hiện có), `V4__pets_audit_columns.sql` (khuôn cột audit), `platform/model/BaseEntity.java`.
- Produces: bảng `pet_caregiver_delegations` khớp `BaseEntity` + 4 index cho Task 2–6.

- [ ] **Step 1: Viết migration**

```sql
-- V5: Caregiver delegation (M04, FSM-3) — bổ sung cột audit khớp platform.model.BaseEntity
-- (bảng V1 thiếu cả created_at), đổi định danh lời mời sang email theo RULE-01-10
-- (xem spec D-01), và thêm valid_until cho RULE-04-07 (spec D-03).
-- Bảng chưa từng có code nào ghi vào nên rỗng trên mọi môi trường — đổi cột an toàn.
ALTER TABLE pet_caregiver_delegations
    ADD COLUMN caregiver_email VARCHAR(100) NOT NULL,
    ALTER COLUMN caregiver_phone DROP NOT NULL,
    ADD COLUMN valid_until TIMESTAMPTZ,
    ADD COLUMN created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by  UUID REFERENCES accounts(id),
    ADD COLUMN updated_by  UUID REFERENCES accounts(id),
    ADD COLUMN deleted_at  TIMESTAMPTZ,
    ADD COLUMN version     BIGINT NOT NULL DEFAULT 0;

-- Tên cột không được nói dối: chỉ lưu SHA-256 hex, không lưu raw token (spec D-07,
-- precedent platform/security/token/RefreshTokenService).
ALTER TABLE pet_caregiver_delegations
    RENAME COLUMN invitation_token TO invitation_token_hash;

-- Chặn mời trùng ở tầng DB thay vì check-then-insert ở service (tránh hở race).
CREATE UNIQUE INDEX uq_pcd_outstanding
    ON pet_caregiver_delegations (pet_id, caregiver_email)
    WHERE status IN ('INVITED', 'ACTIVE');

-- Phục vụ job quét hết hạn (lối V3__refresh_token_cleanup_index.sql).
CREATE INDEX idx_pcd_invitation_expiry
    ON pet_caregiver_delegations (expires_at) WHERE status = 'INVITED';
CREATE INDEX idx_pcd_delegation_expiry
    ON pet_caregiver_delegations (valid_until) WHERE status = 'ACTIVE';

-- Phục vụ PetAccessGuard + query GET /pets.
CREATE INDEX idx_pcd_caregiver
    ON pet_caregiver_delegations (caregiver_user_id, status);
```

- [ ] **Step 2: Kiểm migration chạy được**

Run: `cd BE && mvn -q flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/petcare -Dflyway.user=postgres -Dflyway.password=123456`
Expected: BUILD SUCCESS, log có `Migrating schema "public" to version "5 - caregiver delegations"`.

Nếu chưa có Postgres local: `docker compose up -d postgres` từ thư mục gốc repo trước.

- [ ] **Step 3: Commit**

```bash
git add BE/src/main/resources/db/migration/V5__caregiver_delegations.sql
git commit -m "feat(caregiver): add V5 delegation audit columns + email identity + indexes"
```

---

### Task 2: Entity + Repository

**Files:**
- Create: `BE/src/main/java/com/petcare/module/pet/entity/PetCaregiverDelegation.java`
- Create: `BE/src/main/java/com/petcare/module/pet/repository/PetCaregiverDelegationRepository.java`
- Test: `BE/src/test/java/com/petcare/module/pet/repository/PetCaregiverDelegationRepositoryTest.java`

**Interfaces:**
- Consumes: `V5` (Task 1), `platform/model/BaseEntity`, `platform/enums/CaregiverStatus`.
- Produces:
  - `PetCaregiverDelegation` với getter/setter cho `petId:UUID`, `primaryOwnerId:UUID`, `caregiverUserId:UUID`, `caregiverEmail:String`, `caregiverPhone:String`, `invitationTokenHash:String`, `status:CaregiverStatus`, `expiresAt:LocalDateTime`, `validUntil:LocalDateTime`
  - `PetCaregiverDelegationRepository` với:
    - `Optional<PetCaregiverDelegation> findByInvitationTokenHash(String hash)`
    - `Optional<PetCaregiverDelegation> findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(UUID petId, String caregiverEmail, Collection<CaregiverStatus> statuses)`
    - `boolean existsActiveDelegation(UUID petId, UUID caregiverUserId, LocalDateTime now)`
    - `List<PetCaregiverDelegation> findExpiredInvitations(LocalDateTime now)`
    - `List<PetCaregiverDelegation> findExpiredDelegations(LocalDateTime now)`

- [ ] **Step 1: Viết test đỏ cho repository**

```java
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
```

- [ ] **Step 2: Chạy test, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=PetCaregiverDelegationRepositoryTest`
Expected: FAIL — compile error, `PetCaregiverDelegation` và `PetCaregiverDelegationRepository` chưa tồn tại.

- [ ] **Step 3: Viết entity**

```java
package com.petcare.module.pet.entity;

import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Child entity của aggregate Pet (docs/05-domain-model.md §04) — vòng đời ủy quyền
 * chăm sóc Pet, FSM-3 (docs/03-state-machines.md §3).
 * Định danh lời mời là email, không phải phone — xem spec D-01 (RULE-01-10).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "pet_caregiver_delegations")
public class PetCaregiverDelegation extends BaseEntity {

    @Column(name = "pet_id", nullable = false)
    private UUID petId;

    @Column(name = "primary_owner_id", nullable = false)
    private UUID primaryOwnerId;

    @Column(name = "caregiver_user_id")
    private UUID caregiverUserId;

    @Column(name = "caregiver_email", nullable = false, length = 100)
    private String caregiverEmail;

    @Column(name = "caregiver_phone", length = 20)
    private String caregiverPhone;

    @Column(name = "invitation_token_hash", nullable = false, length = 100)
    private String invitationTokenHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private CaregiverStatus status = CaregiverStatus.INVITED;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;
}
```

> `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` là bắt buộc và khớp `Pet.status` — cột `status` của cả hai bảng là postgres enum type (`caregiver_status_enum` / `pet_status_enum`), không phải varchar.

- [ ] **Step 4: Viết repository**

```java
package com.petcare.module.pet.repository;

import com.petcare.module.pet.entity.PetCaregiverDelegation;
import com.petcare.platform.enums.CaregiverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PetCaregiverDelegationRepository extends JpaRepository<PetCaregiverDelegation, UUID> {

    Optional<PetCaregiverDelegation> findByInvitationTokenHash(String invitationTokenHash);

    Optional<PetCaregiverDelegation> findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(
            UUID petId, String caregiverEmail, Collection<CaregiverStatus> statuses);

    /**
     * "Ủy quyền còn hiệu lực" (spec D-08) — điều kiện này phải giống hệt điều kiện
     * trong PetRepository.findAccessibleBy. Sửa một chỗ thì sửa cả hai.
     */
    @Query("""
            select count(d) > 0 from PetCaregiverDelegation d
            where d.petId = :petId
              and d.caregiverUserId = :caregiverUserId
              and d.status = com.petcare.platform.enums.CaregiverStatus.ACTIVE
              and (d.validUntil is null or d.validUntil > :now)
            """)
    boolean existsActiveDelegation(@Param("petId") UUID petId,
                                   @Param("caregiverUserId") UUID caregiverUserId,
                                   @Param("now") LocalDateTime now);

    @Query("""
            select d from PetCaregiverDelegation d
            where d.status = com.petcare.platform.enums.CaregiverStatus.INVITED
              and d.expiresAt <= :now
            """)
    List<PetCaregiverDelegation> findExpiredInvitations(@Param("now") LocalDateTime now);

    @Query("""
            select d from PetCaregiverDelegation d
            where d.status = com.petcare.platform.enums.CaregiverStatus.ACTIVE
              and d.validUntil is not null
              and d.validUntil <= :now
            """)
    List<PetCaregiverDelegation> findExpiredDelegations(@Param("now") LocalDateTime now);
}
```

- [ ] **Step 5: Chạy test, xác nhận xanh**

Run: `cd BE && mvn test -Dtest=PetCaregiverDelegationRepositoryTest`
Expected: PASS, 4 test.

- [ ] **Step 6: Commit**

```bash
git add BE/src/main/java/com/petcare/module/pet/entity/PetCaregiverDelegation.java \
        BE/src/main/java/com/petcare/module/pet/repository/PetCaregiverDelegationRepository.java \
        BE/src/test/java/com/petcare/module/pet/repository/PetCaregiverDelegationRepositoryTest.java
git commit -m "feat(caregiver): add PetCaregiverDelegation entity and repository"
```

---

### Task 3: FSM transition handler

**Files:**
- Create: `BE/src/main/java/com/petcare/module/pet/fsm/CaregiverDelegationTransitionHandler.java`
- Test: `BE/src/test/java/com/petcare/module/pet/fsm/CaregiverDelegationTransitionHandlerTest.java`

**Interfaces:**
- Consumes: `platform/fsm/StateMachineBase`, `platform/enums/CaregiverStatus`, `FsmTransitionTestBase`.
- Produces: `CaregiverDelegationTransitionHandler` (Spring `@Component`) với `validateTransition(CaregiverStatus from, CaregiverStatus to)` kế thừa từ base.

- [ ] **Step 1: Viết test đỏ — đủ 25 cặp**

`09-testing.md` bắt buộc liệt kê **đầy đủ** cặp hợp lệ/không hợp lệ, không bỏ sót cặp nào. 5 trạng thái × 5 = 25 cặp: 6 hợp lệ, 19 không.

```java
package com.petcare.module.pet.fsm;

import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CaregiverDelegationTransitionHandlerTest extends FsmTransitionTestBase<CaregiverStatus> {

    @Override
    protected StateMachineBase<CaregiverStatus> handler() {
        return new CaregiverDelegationTransitionHandler();
    }

    @ParameterizedTest
    @CsvSource({
            "INVITED,ACTIVE",
            "INVITED,REJECTED",
            "INVITED,EXPIRED",
            "INVITED,REVOKED",   // spec D-04 — cạnh mới: chủ hủy lời mời đang treo
            "ACTIVE,REVOKED",
            "ACTIVE,EXPIRED"
    })
    void validTransitions(CaregiverStatus from, CaregiverStatus to) {
        assertValidTransition(from, to);
    }

    @ParameterizedTest
    @CsvSource({
            "INVITED,INVITED",
            "ACTIVE,INVITED",
            "ACTIVE,ACTIVE",
            "ACTIVE,REJECTED",
            "REJECTED,INVITED",
            "REJECTED,ACTIVE",
            "REJECTED,REJECTED",
            "REJECTED,EXPIRED",
            "REJECTED,REVOKED",
            "EXPIRED,INVITED",
            "EXPIRED,ACTIVE",
            "EXPIRED,REJECTED",
            "EXPIRED,EXPIRED",
            "EXPIRED,REVOKED",
            "REVOKED,INVITED",
            "REVOKED,ACTIVE",
            "REVOKED,REJECTED",
            "REVOKED,EXPIRED",
            "REVOKED,REVOKED"
    })
    void invalidTransitions(CaregiverStatus from, CaregiverStatus to) {
        assertInvalidTransition(from, to);
    }
}
```

- [ ] **Step 2: Chạy test, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=CaregiverDelegationTransitionHandlerTest`
Expected: FAIL — compile error, `CaregiverDelegationTransitionHandler` chưa tồn tại.

- [ ] **Step 3: Viết handler**

```java
package com.petcare.module.pet.fsm;

import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.fsm.StateMachineBase;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.petcare.platform.enums.CaregiverStatus.ACTIVE;
import static com.petcare.platform.enums.CaregiverStatus.EXPIRED;
import static com.petcare.platform.enums.CaregiverStatus.INVITED;
import static com.petcare.platform.enums.CaregiverStatus.REJECTED;
import static com.petcare.platform.enums.CaregiverStatus.REVOKED;

/**
 * FSM-3 (CaregiverStatus) — docs/03-state-machines.md §3.
 * Cạnh INVITED -> REVOKED (chủ hủy lời mời đang treo) là bổ sung đã được duyệt
 * 2026-09-16, xem spec D-04; đặc tả mermaid đã cập nhật cùng đợt.
 * REJECTED / EXPIRED / REVOKED là trạng thái cuối, không có cạnh ra.
 */
@Component
public class CaregiverDelegationTransitionHandler extends StateMachineBase<CaregiverStatus> {

    @Override
    public Map<CaregiverStatus, Set<CaregiverStatus>> allowedTransitions() {
        return Map.of(
                INVITED, Set.of(ACTIVE, REJECTED, EXPIRED, REVOKED),
                ACTIVE, Set.of(REVOKED, EXPIRED)
        );
    }
}
```

- [ ] **Step 4: Chạy test, xác nhận xanh**

Run: `cd BE && mvn test -Dtest=CaregiverDelegationTransitionHandlerTest`
Expected: PASS, 25 test.

- [ ] **Step 5: Commit**

```bash
git add BE/src/main/java/com/petcare/module/pet/fsm/CaregiverDelegationTransitionHandler.java \
        BE/src/test/java/com/petcare/module/pet/fsm/CaregiverDelegationTransitionHandlerTest.java
git commit -m "feat(caregiver): add FSM-3 transition handler with full pair coverage"
```

---

### Task 4: Hai exception riêng của module + wiring `GlobalExceptionHandler`

**Files:**
- Create: `BE/src/main/java/com/petcare/module/pet/exception/UnauthorizedDelegatedActionException.java`
- Create: `BE/src/main/java/com/petcare/module/pet/exception/CaregiverInvitationConflictException.java`
- Modify: `BE/src/main/java/com/petcare/platform/exception/GlobalExceptionHandler.java`
- Test: `BE/src/test/java/com/petcare/module/pet/exception/CaregiverExceptionMappingTest.java`

**Interfaces:**
- Consumes: `AccessDeniedScopeException(String requiredScope, String actualScope)`, `BusinessRuleViolationException(String ruleId, String message)`.
- Produces:
  - `UnauthorizedDelegatedActionException(String requiredScope, String actualScope)` → `UNAUTHORIZED_DELEGATED_ACTION` / 403
  - `CaregiverInvitationConflictException(UUID petId, String caregiverEmail)` → `CAREGIVER_INVITATION_CONFLICT` / 409

- [ ] **Step 1: Viết test đỏ**

```java
package com.petcare.module.pet.exception;

import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.GlobalExceptionHandler;
import com.petcare.platform.model.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CaregiverExceptionMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unauthorizedDelegatedAction_is403WithContractErrorCode() {
        ResponseEntity<ErrorResponse> res = handler.handleUnauthorizedDelegatedAction(
                new UnauthorizedDelegatedActionException("PET_OWNER_OR_ACTIVE_CAREGIVER", "NOT_DELEGATED"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().errorCode()).isEqualTo("UNAUTHORIZED_DELEGATED_ACTION");
    }

    @Test
    void invitationConflict_is409() {
        ResponseEntity<ErrorResponse> res = handler.handleCaregiverInvitationConflict(
                new CaregiverInvitationConflictException(UUID.randomUUID(), "cg@example.com"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().errorCode()).isEqualTo("CAREGIVER_INVITATION_CONFLICT");
    }

    /** Subclass phải giữ nguyên quan hệ kế thừa để test Pet cũ không vỡ. */
    @Test
    void subclassesKeepBaseTypes() {
        assertThat(new UnauthorizedDelegatedActionException("a", "b"))
                .isInstanceOf(AccessDeniedScopeException.class);
        assertThat(new CaregiverInvitationConflictException(UUID.randomUUID(), "cg@example.com"))
                .isInstanceOf(BusinessRuleViolationException.class);
    }
}
```

- [ ] **Step 2: Chạy test, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=CaregiverExceptionMappingTest`
Expected: FAIL — compile error, hai exception và hai handler chưa tồn tại.

- [ ] **Step 3: Viết hai exception**

```java
package com.petcare.module.pet.exception;

import com.petcare.platform.exception.AccessDeniedScopeException;

/**
 * RULE-04-09 — thao tác ngoài phạm vi được ủy quyền.
 *
 * Lý do tạo exception riêng (docs/convention/backend/04-exception-handling.md §4.2,
 * TIÊU CHÍ 2): cần errorCode riêng `UNAUTHORIZED_DELEGATED_ACTION` mà contract
 * docs/api/customer-pet-v1.md chốt CONFIRMED cho RULE-04-09, khác với
 * `ACCESS_DENIED_SCOPE_MISMATCH` mặc định của class cha (cùng HTTP 403).
 */
public class UnauthorizedDelegatedActionException extends AccessDeniedScopeException {

    public UnauthorizedDelegatedActionException(String requiredScope, String actualScope) {
        super(requiredScope, actualScope);
    }
}
```

```java
package com.petcare.module.pet.exception;

import com.petcare.platform.exception.BusinessRuleViolationException;

import java.util.UUID;

/**
 * Đã tồn tại lời mời (INVITED) hoặc ủy quyền (ACTIVE) cho cặp (pet, email).
 *
 * Lý do tạo exception riêng (docs/convention/backend/04-exception-handling.md §4.2,
 * TIÊU CHÍ 2): cần HTTP 409 thay vì 400 mặc định của class cha — contract
 * docs/api/customer-pet-v1.md §C3 ghi 409, và docs/api/00-method.md §1.4 chốt
 * "conflict -> 409".
 *
 * RULE-ID trích dẫn là RULE-04-05 (vòng đời lời mời); ràng buộc "không trùng" là
 * derived từ contract §C3, docs không có câu rule literal cho nó.
 */
public class CaregiverInvitationConflictException extends BusinessRuleViolationException {

    public CaregiverInvitationConflictException(UUID petId, String caregiverEmail) {
        super("RULE-04-05", String.format(
                "Đã tồn tại lời mời hoặc ủy quyền còn hiệu lực cho %s trên pet %s (RULE-04-05)",
                caregiverEmail, petId));
    }
}
```

- [ ] **Step 4: Thêm 2 handler vào `GlobalExceptionHandler`**

Chèn **ngay trước** `handleBusinessRuleViolation` (Spring ưu tiên `@ExceptionHandler` khớp type cụ thể nhất, nhưng đặt trước cho khớp lối viết của 4 handler auth đã có):

```java
    /**
     * 2 handler dưới đây bắt subclass của module pet trước handler cha —
     * cùng lý do với cụm auth ở trên (§4.2.2).
     */
    @ExceptionHandler(UnauthorizedDelegatedActionException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedDelegatedAction(
            UnauthorizedDelegatedActionException ex) {
        return build(HttpStatus.FORBIDDEN, "UNAUTHORIZED_DELEGATED_ACTION", ex.getMessage());
    }

    @ExceptionHandler(CaregiverInvitationConflictException.class)
    public ResponseEntity<ErrorResponse> handleCaregiverInvitationConflict(
            CaregiverInvitationConflictException ex) {
        return build(HttpStatus.CONFLICT, "CAREGIVER_INVITATION_CONFLICT", ex.getMessage());
    }
```

Thêm import: `com.petcare.module.pet.exception.UnauthorizedDelegatedActionException`, `com.petcare.module.pet.exception.CaregiverInvitationConflictException`.

- [ ] **Step 5: Chạy test, xác nhận xanh — và test Pet cũ vẫn xanh**

Run: `cd BE && mvn test -Dtest='CaregiverExceptionMappingTest,PetServiceImplTest,PetControllerTest'`
Expected: PASS toàn bộ. `PetServiceImplTest` và `PetControllerTest` phải **không** cần sửa gì.

- [ ] **Step 6: Commit**

```bash
git add BE/src/main/java/com/petcare/module/pet/exception/ \
        BE/src/main/java/com/petcare/platform/exception/GlobalExceptionHandler.java \
        BE/src/test/java/com/petcare/module/pet/exception/CaregiverExceptionMappingTest.java
git commit -m "feat(caregiver): add delegated-action and invitation-conflict exceptions"
```

---

### Task 5: `PetAccessGuard`

**Files:**
- Create: `BE/src/main/java/com/petcare/module/pet/service/PetAccessGuard.java`
- Test: `BE/src/test/java/com/petcare/module/pet/service/PetAccessGuardTest.java`

**Interfaces:**
- Consumes: `PetCaregiverDelegationRepository.existsActiveDelegation(UUID, UUID, LocalDateTime)` (Task 2), `UnauthorizedDelegatedActionException` (Task 4), `Pet.getOwnerId()`, `Pet.getId()`.
- Produces: `PetAccessGuard` (`@Component`) với `void requireCanViewPet(UUID userId, Pet pet)` và `void requirePrimaryOwner(UUID userId, Pet pet)`.

- [ ] **Step 1: Viết test đỏ**

```java
package com.petcare.module.pet.service;

import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.exception.UnauthorizedDelegatedActionException;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PetAccessGuardTest {

    @Mock PetCaregiverDelegationRepository delegations;
    @InjectMocks PetAccessGuard guard;

    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private Pet pet;

    @BeforeEach
    void setUp() {
        pet = new Pet(owner, "Mun", "DOG");
        pet.setId(UUID.randomUUID());
    }

    @Test
    void requireCanViewPet_ownerPasses_withoutTouchingDelegations() {
        assertThatCode(() -> guard.requireCanViewPet(owner, pet)).doesNotThrowAnyException();
    }

    @Test
    void requireCanViewPet_activeCaregiverPasses() {
        when(delegations.existsActiveDelegation(eq(pet.getId()), eq(other), any(LocalDateTime.class)))
                .thenReturn(true);

        assertThatCode(() -> guard.requireCanViewPet(other, pet)).doesNotThrowAnyException();
    }

    @Test
    void requireCanViewPet_strangerForbidden() {
        when(delegations.existsActiveDelegation(eq(pet.getId()), eq(other), any(LocalDateTime.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> guard.requireCanViewPet(other, pet))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    /**
     * Spec D-08: guard tự kiểm thời hạn. Repository trả false cho delegation ACTIVE
     * đã quá valid_until, nên guard phải chặn — kể cả khi job chưa kịp quét.
     */
    @Test
    void requireCanViewPet_activeButExpiredDelegationForbidden() {
        when(delegations.existsActiveDelegation(eq(pet.getId()), eq(other), any(LocalDateTime.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> guard.requireCanViewPet(other, pet))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    @Test
    void requirePrimaryOwner_ownerPasses() {
        assertThatCode(() -> guard.requirePrimaryOwner(owner, pet)).doesNotThrowAnyException();
    }

    @Test
    void requirePrimaryOwner_activeCaregiverStillForbidden() {
        assertThatThrownBy(() -> guard.requirePrimaryOwner(other, pet))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }
}
```

- [ ] **Step 2: Chạy test, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=PetAccessGuardTest`
Expected: FAIL — compile error, `PetAccessGuard` chưa tồn tại.

- [ ] **Step 3: Viết guard**

```java
package com.petcare.module.pet.service;

import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.exception.UnauthorizedDelegatedActionException;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Nơi DUY NHẤT định nghĩa "ai đọc được Pet nào" (RULE-04-09) và "ai là Primary Owner"
 * (RULE-04-04). Điều kiện "ủy quyền còn hiệu lực" được đánh giá tại thời điểm request,
 * KHÔNG dựa vào job hết hạn (spec D-08) — giữa hai tick cron vẫn tồn tại bản ghi
 * status=ACTIVE nhưng valid_until đã trôi qua.
 *
 * Bản sao thứ hai của cùng điều kiện nằm ở PetRepository.findAccessibleBy (dùng cho
 * GET /pets). Sửa một chỗ thì sửa cả hai.
 */
@Component
@RequiredArgsConstructor
public class PetAccessGuard {

    private final PetCaregiverDelegationRepository delegations;

    /** RULE-04-09 — owner hoặc caregiver đang ACTIVE và còn hạn. */
    public void requireCanViewPet(UUID userId, Pet pet) {
        if (pet.getOwnerId().equals(userId)) {
            return;
        }
        if (delegations.existsActiveDelegation(pet.getId(), userId, LocalDateTime.now())) {
            return;
        }
        throw new UnauthorizedDelegatedActionException("PET_OWNER_OR_ACTIVE_CAREGIVER", "NOT_DELEGATED");
    }

    /** RULE-04-04 — chỉ Primary Owner; caregiver ACTIVE cũng bị chặn. */
    public void requirePrimaryOwner(UUID userId, Pet pet) {
        if (!pet.getOwnerId().equals(userId)) {
            throw new UnauthorizedDelegatedActionException("PET_PRIMARY_OWNER", "NOT_PRIMARY_OWNER");
        }
    }
}
```

- [ ] **Step 4: Chạy test, xác nhận xanh**

Run: `cd BE && mvn test -Dtest=PetAccessGuardTest`
Expected: PASS, 6 test.

- [ ] **Step 5: Commit**

```bash
git add BE/src/main/java/com/petcare/module/pet/service/PetAccessGuard.java \
        BE/src/test/java/com/petcare/module/pet/service/PetAccessGuardTest.java
git commit -m "feat(caregiver): add PetAccessGuard with request-time expiry check"
```

---

### Task 6: Caregiver đọc được Pet — nối guard vào `PetServiceImpl`

**Files:**
- Modify: `BE/src/main/java/com/petcare/module/pet/repository/PetRepository.java`
- Modify: `BE/src/main/java/com/petcare/module/pet/service/PetServiceImpl.java` (`detail()`, `list()`)
- Test: `BE/src/test/java/com/petcare/module/pet/service/PetServiceImplTest.java` (thêm ca)
- Test: `BE/src/test/java/com/petcare/module/pet/repository/PetRepositoryTest.java` (thêm ca)

**Interfaces:**
- Consumes: `PetAccessGuard` (Task 5), `PetCaregiverDelegationRepository` (Task 2).
- Produces: `PetRepository.findAccessibleBy(UUID userId, LocalDateTime now, Pageable pageable) : Page<Pet>`; `PetServiceImpl.detail()`/`list()` nhận caregiver `ACTIVE`.

- [ ] **Step 1: Viết test đỏ cho repository query**

Thêm vào `PetRepositoryTest`:

```java
    @Test
    void findAccessibleBy_returnsOwnedAndActivelyDelegatedPets() {
        UUID owner = newUser("acc-owner@example.com");
        UUID caregiver = newUser("acc-caregiver@example.com");
        Pet owned = pets.saveAndFlush(new Pet(owner, "Mun", "DOG"));
        Pet delegated = pets.saveAndFlush(new Pet(owner, "Bơ", "CAT"));
        Pet unrelated = pets.saveAndFlush(new Pet(owner, "Nâu", "DOG"));

        PetCaregiverDelegation d = new PetCaregiverDelegation();
        d.setPetId(delegated.getId());
        d.setPrimaryOwnerId(owner);
        d.setCaregiverUserId(caregiver);
        d.setCaregiverEmail("acc-caregiver@example.com");
        d.setInvitationTokenHash(UUID.randomUUID().toString());
        d.setStatus(CaregiverStatus.ACTIVE);
        d.setExpiresAt(LocalDateTime.now().plusDays(7));
        delegations.saveAndFlush(d);

        Page<Pet> page = pets.findAccessibleBy(caregiver, LocalDateTime.now(), PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Pet::getId).containsExactly(delegated.getId());
        assertThat(page.getContent()).extracting(Pet::getId)
                .doesNotContain(owned.getId(), unrelated.getId());
    }
```

> `PetRepositoryTest` cần `@Autowired PetCaregiverDelegationRepository delegations;` và helper `newUser(...)` giống Task 2 nếu chưa có.

- [ ] **Step 2: Chạy, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=PetRepositoryTest`
Expected: FAIL — `findAccessibleBy` chưa tồn tại.

- [ ] **Step 3: Thêm query vào `PetRepository`**

```java
    /**
     * Pet mình sở hữu HOẶC pet có ủy quyền còn hiệu lực trỏ về mình (RULE-04-09).
     * Điều kiện hiệu lực phải giống hệt PetCaregiverDelegationRepository.existsActiveDelegation
     * (spec D-08) — sửa một chỗ thì sửa cả hai.
     */
    @Query("""
            select p from Pet p
            where p.ownerId = :userId
               or exists (select 1 from PetCaregiverDelegation d
                          where d.petId = p.id
                            and d.caregiverUserId = :userId
                            and d.status = com.petcare.platform.enums.CaregiverStatus.ACTIVE
                            and (d.validUntil is null or d.validUntil > :now))
            """)
    Page<Pet> findAccessibleBy(@Param("userId") UUID userId,
                               @Param("now") LocalDateTime now,
                               Pageable pageable);
```

Thêm import `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`, `java.time.LocalDateTime`.

- [ ] **Step 4: Chạy lại repository test, xác nhận xanh**

Run: `cd BE && mvn test -Dtest=PetRepositoryTest`
Expected: PASS.

- [ ] **Step 5: Viết test đỏ cho service**

Thêm vào `PetServiceImplTest` (class đã có `@Mock PetRepository pets; @Mock PetMapper mapper; ...` — thêm `@Mock PetAccessGuard accessGuard;`):

```java
    @Test
    void detail_activeCaregiver_allowed() {
        UUID caregiver = UUID.randomUUID();
        Pet pet = new Pet(UUID.randomUUID(), "Mun", "DOG");
        pet.setId(UUID.randomUUID());
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(mapper.toResponse(pet)).thenReturn(mock(PetResponse.class));

        svc.detail(caregiver, pet.getId());

        verify(accessGuard).requireCanViewPet(caregiver, pet);
    }

    @Test
    void detail_guardRejects_propagates403() {
        UUID stranger = UUID.randomUUID();
        Pet pet = new Pet(UUID.randomUUID(), "Mun", "DOG");
        pet.setId(UUID.randomUUID());
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        doThrow(new UnauthorizedDelegatedActionException("PET_OWNER_OR_ACTIVE_CAREGIVER", "NOT_DELEGATED"))
                .when(accessGuard).requireCanViewPet(stranger, pet);

        assertThatThrownBy(() -> svc.detail(stranger, pet.getId()))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    @Test
    void list_usesAccessibleQuery_notOwnerOnly() {
        UUID me = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        when(pets.findAccessibleBy(eq(me), any(LocalDateTime.class), eq(pageable)))
                .thenReturn(Page.empty());

        svc.list(me, pageable);

        verify(pets).findAccessibleBy(eq(me), any(LocalDateTime.class), eq(pageable));
        verify(pets, never()).findByOwnerId(eq(me), any(Pageable.class));
    }
```

- [ ] **Step 6: Chạy, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=PetServiceImplTest`
Expected: FAIL — `PetServiceImpl` chưa có `PetAccessGuard`, `list()` còn gọi `findByOwnerId`.

- [ ] **Step 7: Sửa `PetServiceImpl`**

Thêm `private final PetAccessGuard accessGuard;` vào danh sách field.

`detail()` — thay khối so sánh ownerId hiện tại:

```java
    @Override
    @Transactional(readOnly = true)
    public PetResponse detail(UUID me, UUID id) {
        Pet pet = pets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Pet", id));
        accessGuard.requireCanViewPet(me, pet); // RULE-04-09
        return mapper.toResponse(pet);
    }
```

`list()`:

```java
    @Override
    @Transactional(readOnly = true)
    public Page<PetResponse> list(UUID me, Pageable pageable) {
        // RULE-04-09: pet mình sở hữu + pet được ủy quyền còn hiệu lực.
        return pets.findAccessibleBy(me, LocalDateTime.now(), pageable).map(mapper::toResponse);
    }
```

**Không đổi** `create()` và `update()` — giữ owner-only (spec D-06).

- [ ] **Step 8: Chạy toàn bộ test Pet, xác nhận xanh**

Run: `cd BE && mvn test -Dtest='PetServiceImplTest,PetControllerTest,PetMapperTest' && mvn verify -Dit.test=PetFlowIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS toàn bộ, kể cả `PetFlowIT` cũ (user B vẫn phải bị 403 khi chạm pet của A vì không có delegation nào).

- [ ] **Step 9: Commit**

```bash
git add BE/src/main/java/com/petcare/module/pet/repository/PetRepository.java \
        BE/src/main/java/com/petcare/module/pet/service/PetServiceImpl.java \
        BE/src/test/java/com/petcare/module/pet/service/PetServiceImplTest.java \
        BE/src/test/java/com/petcare/module/pet/repository/PetRepositoryTest.java
git commit -m "feat(caregiver): let active caregivers read delegated pets (RULE-04-09)"
```

---

### Task 7: Tra userId theo email (auth module, thuần đọc)

**Files:**
- Modify: `BE/src/main/java/com/petcare/module/auth/service/AuthService.java`
- Modify: `BE/src/main/java/com/petcare/module/auth/service/AuthServiceImpl.java`
- Test: `BE/src/test/java/com/petcare/module/auth/service/AuthServiceImplTest.java` (thêm ca)

**Interfaces:**
- Consumes: `AccountRepository.findByEmail(String)`, `UserProvisioningService.findByAccountId(UUID)`, `platform/enums/AccountStatus`.
- Produces: `AuthService.findUserIdByActiveAccountEmail(String email) : Optional<UUID>` — trả `userId` (không phải `accountId`), rỗng nếu không có account hoặc account không `ACTIVE`.

**Đây là phụ thuộc cross-module DUY NHẤT của plan.** Thuần đọc, không đổi hành vi nào đang chạy, không đụng `UserPrincipal` hay JWT.

- [ ] **Step 1: Viết test đỏ**

```java
    @Test
    void findUserIdByActiveAccountEmail_returnsUserIdForActiveAccount() {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setEmail("cg@example.com");
        account.setStatus(AccountStatus.ACTIVE);
        User user = new User(account.getId(), "Caregiver");
        user.setId(UUID.randomUUID());
        when(accountRepository.findByEmail("cg@example.com")).thenReturn(Optional.of(account));
        when(userProvisioningService.findByAccountId(account.getId())).thenReturn(user);

        assertThat(authService.findUserIdByActiveAccountEmail("cg@example.com")).contains(user.getId());
    }

    @Test
    void findUserIdByActiveAccountEmail_emptyWhenNoAccount() {
        when(accountRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThat(authService.findUserIdByActiveAccountEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void findUserIdByActiveAccountEmail_emptyWhenAccountNotActive() {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setEmail("pending@example.com");
        account.setStatus(AccountStatus.PENDING_VERIFICATION);
        when(accountRepository.findByEmail("pending@example.com")).thenReturn(Optional.of(account));

        assertThat(authService.findUserIdByActiveAccountEmail("pending@example.com")).isEmpty();
    }
```

- [ ] **Step 2: Chạy, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=AuthServiceImplTest`
Expected: FAIL — method chưa tồn tại.

- [ ] **Step 3: Thêm vào interface**

```java
    /**
     * Tra userId theo email của Account đang ACTIVE. Thuần đọc, mở ra cho module pet
     * dùng khi mời Caregiver (spec D-02) — email thuộc bảng accounts (auth module) nên
     * module khác không được đọc trực tiếp (01-package-structure.md).
     */
    Optional<UUID> findUserIdByActiveAccountEmail(String email);
```

- [ ] **Step 4: Cài đặt trong `AuthServiceImpl`**

```java
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findUserIdByActiveAccountEmail(String email) {
        return accountRepository.findByEmail(email)
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                .map(account -> userProvisioningService.findByAccountId(account.getId()).getId());
    }
```

> `UserProvisioningService.findByAccountId` là `Propagation.MANDATORY` — `@Transactional(readOnly = true)` ở đây là bắt buộc, không được bỏ.

- [ ] **Step 5: Chạy, xác nhận xanh**

Run: `cd BE && mvn test -Dtest=AuthServiceImplTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add BE/src/main/java/com/petcare/module/auth/service/AuthService.java \
        BE/src/main/java/com/petcare/module/auth/service/AuthServiceImpl.java \
        BE/src/test/java/com/petcare/module/auth/service/AuthServiceImplTest.java
git commit -m "feat(auth): add read-only userId lookup by active account email"
```

---

### Task 8: DTO + mapper + `inviteCaregiver`

**Files:**
- Create: `BE/src/main/java/com/petcare/module/pet/dto/InviteCaregiverRequest.java`
- Create: `BE/src/main/java/com/petcare/module/pet/dto/CaregiverInvitationResponse.java`
- Create: `BE/src/main/java/com/petcare/module/pet/dto/RevokeCaregiverRequest.java`
- Create: `BE/src/main/java/com/petcare/module/pet/dto/CaregiverDelegationResponse.java`
- Create: `BE/src/main/java/com/petcare/module/pet/mapper/CaregiverDelegationMapper.java`
- Create: `BE/src/main/java/com/petcare/module/pet/service/CaregiverInvitationOutcome.java`
- Create: `BE/src/main/java/com/petcare/module/pet/service/CaregiverDelegationService.java`
- Create: `BE/src/main/java/com/petcare/module/pet/service/CaregiverDelegationServiceImpl.java`
- Test: `BE/src/test/java/com/petcare/module/pet/service/CaregiverDelegationServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2 (entity/repo), Task 3 (handler), Task 4 (exceptions), Task 5 (guard), Task 7 (`AuthService`), `NotificationService.enqueue(UUID, NotificationChannel, String, String)`, `OutboxEventRepository`, `PetRepository.findById`.
- Produces:
  - `CaregiverInvitationOutcome(CaregiverInvitationResponse response, UUID notificationTaskId, String toAddress)`
  - `CaregiverDelegationService.inviteCaregiver(UUID ownerUserId, UUID petId, InviteCaregiverRequest req) : CaregiverInvitationOutcome`
  - `CaregiverDelegationMapper.toDelegationResponse(PetCaregiverDelegation) : CaregiverDelegationResponse`

- [ ] **Step 1: Viết 4 DTO + outcome record**

```java
// InviteCaregiverRequest.java
package com.petcare.module.pet.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** validUntil tùy chọn: NULL = ủy quyền chạy tới khi bị thu hồi (spec D-03). */
public record InviteCaregiverRequest(
        @NotBlank @Email @Size(max = 100) String caregiverEmail,
        @Size(max = 20) String caregiverPhone,
        @Future LocalDateTime validUntil
) {}
```

```java
// CaregiverInvitationResponse.java
package com.petcare.module.pet.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * invitationToken chỉ khác null khi người được mời CHƯA có tài khoản (spec D-02) —
 * khi đó raw token được trả đúng một lần cho Primary Owner tự chuyển đi.
 */
public record CaregiverInvitationResponse(
        UUID id,
        UUID petId,
        String caregiverEmail,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime validUntil,
        String invitationToken
) {}
```

```java
// RevokeCaregiverRequest.java
package com.petcare.module.pet.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Định danh bằng email vì lời mời đang treo có thể chưa có caregiverUserId (spec D-05). */
public record RevokeCaregiverRequest(
        @NotBlank @Email @Size(max = 100) String caregiverEmail
) {}
```

```java
// CaregiverDelegationResponse.java
package com.petcare.module.pet.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CaregiverDelegationResponse(
        UUID id,
        UUID petId,
        UUID caregiverUserId,
        String caregiverEmail,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime validUntil
) {}
```

```java
// CaregiverInvitationOutcome.java
package com.petcare.module.pet.service;

import com.petcare.module.pet.dto.CaregiverInvitationResponse;

import java.util.UUID;

/**
 * Kết quả nội bộ (không phải DTO API) trả cho Controller để orchestrate bước dispatch
 * notification NGOÀI transaction của inviteCaregiver() — cùng lý do với
 * module/auth/service/RegistrationOutcome (xem javadoc NotificationService).
 * notificationTaskId null nghĩa là không gửi email (người được mời chưa có tài khoản).
 */
public record CaregiverInvitationOutcome(
        CaregiverInvitationResponse response,
        UUID notificationTaskId,
        String toAddress
) {}
```

- [ ] **Step 2: Viết mapper**

```java
package com.petcare.module.pet.mapper;

import com.petcare.module.pet.dto.CaregiverDelegationResponse;
import com.petcare.module.pet.entity.PetCaregiverDelegation;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface CaregiverDelegationMapper {

    CaregiverDelegationResponse toDelegationResponse(PetCaregiverDelegation entity);
}
```

- [ ] **Step 3: Viết test đỏ cho `inviteCaregiver`**

```java
package com.petcare.module.pet.service;

import com.petcare.module.auth.service.AuthService;
import com.petcare.module.pet.dto.InviteCaregiverRequest;
import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.entity.PetCaregiverDelegation;
import com.petcare.module.pet.exception.CaregiverInvitationConflictException;
import com.petcare.module.pet.exception.UnauthorizedDelegatedActionException;
import com.petcare.module.pet.fsm.CaregiverDelegationTransitionHandler;
import com.petcare.module.pet.mapper.CaregiverDelegationMapper;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import com.petcare.module.pet.repository.PetRepository;
import com.petcare.module.notification.service.NotificationService;
import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.enums.NotificationChannel;
import com.petcare.platform.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaregiverDelegationServiceImplTest {

    @Mock PetRepository pets;
    @Mock PetCaregiverDelegationRepository delegations;
    @Mock PetAccessGuard accessGuard;
    @Mock AuthService authService;
    @Mock NotificationService notificationService;
    @Mock OutboxEventRepository outbox;
    @Mock CaregiverDelegationMapper mapper;

    private CaregiverDelegationServiceImpl svc;

    private final UUID owner = UUID.randomUUID();
    private Pet pet;

    @BeforeEach
    void setUp() {
        svc = new CaregiverDelegationServiceImpl(pets, delegations,
                new CaregiverDelegationTransitionHandler(), accessGuard, authService,
                notificationService, outbox, mapper, 7);
        pet = new Pet(owner, "Mun", "DOG");
        pet.setId(UUID.randomUUID());
    }

    @Test
    void inviteCaregiver_existingAccount_enqueuesEmailAndHidesToken() {
        UUID caregiverUserId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(authService.findUserIdByActiveAccountEmail("cg@example.com"))
                .thenReturn(Optional.of(caregiverUserId));
        when(delegations.saveAndFlush(any(PetCaregiverDelegation.class)))
                .thenAnswer(inv -> {
                    PetCaregiverDelegation d = inv.getArgument(0);
                    d.setId(UUID.randomUUID());
                    return d;
                });
        when(notificationService.enqueue(any(), any(NotificationChannel.class), anyString(), anyString()))
                .thenReturn(taskId);

        CaregiverInvitationOutcome outcome = svc.inviteCaregiver(owner, pet.getId(),
                new InviteCaregiverRequest("cg@example.com", null, null));

        assertThat(outcome.response().invitationToken()).isNull();
        assertThat(outcome.notificationTaskId()).isEqualTo(taskId);
        assertThat(outcome.toAddress()).isEqualTo("cg@example.com");
        verify(outbox).save(any());
    }

    @Test
    void inviteCaregiver_noAccount_returnsTokenOnceAndSkipsEmail() {
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(authService.findUserIdByActiveAccountEmail("new@example.com")).thenReturn(Optional.empty());
        when(delegations.saveAndFlush(any(PetCaregiverDelegation.class)))
                .thenAnswer(inv -> {
                    PetCaregiverDelegation d = inv.getArgument(0);
                    d.setId(UUID.randomUUID());
                    return d;
                });

        CaregiverInvitationOutcome outcome = svc.inviteCaregiver(owner, pet.getId(),
                new InviteCaregiverRequest("new@example.com", null, null));

        assertThat(outcome.response().invitationToken()).isNotBlank();
        assertThat(outcome.notificationTaskId()).isNull();
        verify(notificationService, never()).enqueue(any(), any(), anyString(), anyString());
    }

    @Test
    void inviteCaregiver_notPrimaryOwner_forbidden() {
        UUID stranger = UUID.randomUUID();
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        doThrow(new UnauthorizedDelegatedActionException("PET_PRIMARY_OWNER", "NOT_PRIMARY_OWNER"))
                .when(accessGuard).requirePrimaryOwner(stranger, pet);

        assertThatThrownBy(() -> svc.inviteCaregiver(stranger, pet.getId(),
                new InviteCaregiverRequest("cg@example.com", null, null)))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    @Test
    void inviteCaregiver_duplicateOutstanding_conflict409() {
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(authService.findUserIdByActiveAccountEmail("cg@example.com")).thenReturn(Optional.empty());
        when(delegations.saveAndFlush(any(PetCaregiverDelegation.class)))
                .thenThrow(new DataIntegrityViolationException("uq_pcd_outstanding"));

        assertThatThrownBy(() -> svc.inviteCaregiver(owner, pet.getId(),
                new InviteCaregiverRequest("cg@example.com", null, null)))
                .isInstanceOf(CaregiverInvitationConflictException.class);
    }

    @Test
    void inviteCaregiver_storesHashNotRawToken() {
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(authService.findUserIdByActiveAccountEmail("new@example.com")).thenReturn(Optional.empty());
        ArgumentCaptor<PetCaregiverDelegation> captor =
                ArgumentCaptor.forClass(PetCaregiverDelegation.class);
        when(delegations.saveAndFlush(captor.capture()))
                .thenAnswer(inv -> {
                    PetCaregiverDelegation d = inv.getArgument(0);
                    d.setId(UUID.randomUUID());
                    return d;
                });

        CaregiverInvitationOutcome outcome = svc.inviteCaregiver(owner, pet.getId(),
                new InviteCaregiverRequest("new@example.com", null, null));

        assertThat(captor.getValue().getInvitationTokenHash())
                .isNotEqualTo(outcome.response().invitationToken())
                .hasSize(64); // SHA-256 hex
        assertThat(captor.getValue().getStatus()).isEqualTo(CaregiverStatus.INVITED);
    }
}
```

Thêm import `org.mockito.ArgumentCaptor`.

- [ ] **Step 4: Chạy, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=CaregiverDelegationServiceImplTest`
Expected: FAIL — compile error, service chưa tồn tại.

- [ ] **Step 5: Viết interface + impl (chỉ phần invite)**

```java
// CaregiverDelegationService.java
package com.petcare.module.pet.service;

import com.petcare.module.pet.dto.CaregiverDelegationResponse;
import com.petcare.module.pet.dto.InviteCaregiverRequest;
import com.petcare.module.pet.dto.RevokeCaregiverRequest;

import java.util.UUID;

/** Tên method trùng chính xác Command trong docs/01-business-operations.md §4. */
public interface CaregiverDelegationService {

    CaregiverInvitationOutcome inviteCaregiver(UUID ownerUserId, UUID petId, InviteCaregiverRequest req);

    CaregiverDelegationResponse acceptCaregiverInvitation(UUID actorUserId, String rawToken);

    CaregiverDelegationResponse rejectCaregiverInvitation(UUID actorUserId, String rawToken);

    CaregiverDelegationResponse revokeCaregiver(UUID ownerUserId, UUID petId, RevokeCaregiverRequest req);
}
```

```java
// CaregiverDelegationServiceImpl.java — phần invite (accept/reject/revoke ở Task 9-10)
package com.petcare.module.pet.service;

import com.petcare.module.auth.service.AuthService;
import com.petcare.module.notification.service.NotificationService;
import com.petcare.module.pet.dto.CaregiverInvitationResponse;
import com.petcare.module.pet.dto.InviteCaregiverRequest;
import com.petcare.module.pet.entity.Pet;
import com.petcare.module.pet.entity.PetCaregiverDelegation;
import com.petcare.module.pet.exception.CaregiverInvitationConflictException;
import com.petcare.module.pet.fsm.CaregiverDelegationTransitionHandler;
import com.petcare.module.pet.mapper.CaregiverDelegationMapper;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import com.petcare.module.pet.repository.PetRepository;
import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.enums.NotificationChannel;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class CaregiverDelegationServiceImpl implements CaregiverDelegationService {

    private static final int TOKEN_BYTES = 32;

    private final PetRepository pets;
    private final PetCaregiverDelegationRepository delegations;
    private final CaregiverDelegationTransitionHandler transitions;
    private final PetAccessGuard accessGuard;
    private final AuthService authService;
    private final NotificationService notificationService;
    private final OutboxEventRepository outbox;
    private final CaregiverDelegationMapper mapper;
    private final int invitationTtlDays;
    private final SecureRandom secureRandom = new SecureRandom();

    public CaregiverDelegationServiceImpl(PetRepository pets,
                                          PetCaregiverDelegationRepository delegations,
                                          CaregiverDelegationTransitionHandler transitions,
                                          PetAccessGuard accessGuard,
                                          AuthService authService,
                                          NotificationService notificationService,
                                          OutboxEventRepository outbox,
                                          CaregiverDelegationMapper mapper,
                                          @Value("${app.caregiver.invitation-ttl-days:7}") int invitationTtlDays) {
        this.pets = pets;
        this.delegations = delegations;
        this.transitions = transitions;
        this.accessGuard = accessGuard;
        this.authService = authService;
        this.notificationService = notificationService;
        this.outbox = outbox;
        this.mapper = mapper;
        this.invitationTtlDays = invitationTtlDays;
    }

    @Override
    @Transactional
    public CaregiverInvitationOutcome inviteCaregiver(UUID ownerUserId, UUID petId,
                                                      InviteCaregiverRequest req) {
        Pet pet = pets.findById(petId).orElseThrow(() -> new ResourceNotFoundException("Pet", petId));
        accessGuard.requirePrimaryOwner(ownerUserId, pet); // RULE-04-04

        String rawToken = generateRawToken();
        Optional<UUID> caregiverUserId = authService.findUserIdByActiveAccountEmail(req.caregiverEmail());

        PetCaregiverDelegation delegation = new PetCaregiverDelegation();
        delegation.setPetId(petId);
        delegation.setPrimaryOwnerId(ownerUserId);
        delegation.setCaregiverUserId(caregiverUserId.orElse(null));
        delegation.setCaregiverEmail(req.caregiverEmail());
        delegation.setCaregiverPhone(req.caregiverPhone());
        delegation.setInvitationTokenHash(sha256Hex(rawToken));
        delegation.setStatus(CaregiverStatus.INVITED);
        delegation.setExpiresAt(LocalDateTime.now().plusDays(invitationTtlDays)); // RULE-04-05
        delegation.setValidUntil(req.validUntil());

        try {
            delegation = delegations.saveAndFlush(delegation);
        } catch (DataIntegrityViolationException ex) {
            throw new CaregiverInvitationConflictException(petId, req.caregiverEmail());
        }

        writeOutbox(petId, "CaregiverInvited", delegation.getId());

        UUID taskId = null;
        if (caregiverUserId.isPresent()) {
            taskId = notificationService.enqueue(caregiverUserId.get(), NotificationChannel.EMAIL,
                    "CaregiverInvited",
                    "Bạn được mời chăm sóc thú cưng. Mã lời mời: " + rawToken);
        }

        CaregiverInvitationResponse response = new CaregiverInvitationResponse(
                delegation.getId(), petId, delegation.getCaregiverEmail(),
                delegation.getStatus().name(), delegation.getExpiresAt(), delegation.getValidUntil(),
                caregiverUserId.isPresent() ? null : rawToken); // spec D-02

        return new CaregiverInvitationOutcome(response, taskId, req.caregiverEmail());
    }

    void writeOutbox(UUID petId, String eventType, UUID delegationId) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("Pet");
        event.setAggregateId(petId.toString());
        event.setEventType(eventType);
        event.setPayload("{\"petId\":\"" + petId + "\",\"delegationId\":\"" + delegationId + "\"}");
        outbox.save(event);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Chỉ lưu hash, không bao giờ lưu raw token — precedent RefreshTokenService. */
    String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }
}
```

> Tạm thời để `acceptCaregiverInvitation` / `rejectCaregiverInvitation` / `revokeCaregiver` ném `UnsupportedOperationException` để compile — Task 9 và 10 sẽ cài đặt.

- [ ] **Step 6: Chạy, xác nhận xanh**

Run: `cd BE && mvn test -Dtest=CaregiverDelegationServiceImplTest`
Expected: PASS, 5 test.

- [ ] **Step 7: Commit**

```bash
git add BE/src/main/java/com/petcare/module/pet/dto/ \
        BE/src/main/java/com/petcare/module/pet/mapper/CaregiverDelegationMapper.java \
        BE/src/main/java/com/petcare/module/pet/service/CaregiverDelegation*.java \
        BE/src/test/java/com/petcare/module/pet/service/CaregiverDelegationServiceImplTest.java
git commit -m "feat(caregiver): add InviteCaregiver with hashed token and outbox event"
```

---

### Task 9: `acceptCaregiverInvitation` + `rejectCaregiverInvitation`

**Files:**
- Modify: `BE/src/main/java/com/petcare/module/pet/service/CaregiverDelegationServiceImpl.java`
- Test: `BE/src/test/java/com/petcare/module/pet/service/CaregiverDelegationServiceImplTest.java` (thêm ca)

**Interfaces:**
- Consumes: Task 8 (`sha256Hex`, `writeOutbox`), Task 3 (`transitions.validateTransition`).
- Produces: `acceptCaregiverInvitation(UUID actorUserId, String rawToken) : CaregiverDelegationResponse`, `rejectCaregiverInvitation(...)` cùng chữ ký.

- [ ] **Step 1: Viết test đỏ**

```java
    private PetCaregiverDelegation invited(UUID caregiverUserId, LocalDateTime expiresAt) {
        PetCaregiverDelegation d = new PetCaregiverDelegation();
        d.setId(UUID.randomUUID());
        d.setPetId(pet.getId());
        d.setPrimaryOwnerId(owner);
        d.setCaregiverUserId(caregiverUserId);
        d.setCaregiverEmail("cg@example.com");
        d.setInvitationTokenHash(svc.sha256Hex("raw-token"));
        d.setStatus(CaregiverStatus.INVITED);
        d.setExpiresAt(expiresAt);
        return d;
    }

    @Test
    void acceptCaregiverInvitation_boundUserAccepts_becomesActive() {
        UUID caregiver = UUID.randomUUID();
        PetCaregiverDelegation d = invited(caregiver, LocalDateTime.now().plusDays(1));
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        svc.acceptCaregiverInvitation(caregiver, "raw-token");

        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.ACTIVE);
        verify(outbox).save(any());
    }

    @Test
    void acceptCaregiverInvitation_unboundInvitation_bindsAcceptingUser() {
        UUID whoever = UUID.randomUUID();
        PetCaregiverDelegation d = invited(null, LocalDateTime.now().plusDays(1));
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        svc.acceptCaregiverInvitation(whoever, "raw-token");

        assertThat(d.getCaregiverUserId()).isEqualTo(whoever);
        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.ACTIVE);
    }

    @Test
    void acceptCaregiverInvitation_wrongUser_forbidden() {
        PetCaregiverDelegation d = invited(UUID.randomUUID(), LocalDateTime.now().plusDays(1));
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        assertThatThrownBy(() -> svc.acceptCaregiverInvitation(UUID.randomUUID(), "raw-token"))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    @Test
    void acceptCaregiverInvitation_unknownToken_notFound() {
        when(delegations.findByInvitationTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.acceptCaregiverInvitation(UUID.randomUUID(), "nope"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** Spec D-08: không tin cron, tự kiểm expires_at. */
    @Test
    void acceptCaregiverInvitation_pastExpiry_businessRuleViolation() {
        UUID caregiver = UUID.randomUUID();
        PetCaregiverDelegation d = invited(caregiver, LocalDateTime.now().minusMinutes(1));
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        assertThatThrownBy(() -> svc.acceptCaregiverInvitation(caregiver, "raw-token"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("RULE-04-05");
    }

    /** Spec D-10: lặp lại accept bởi đúng người thì 200, không ghi event lần hai. */
    @Test
    void acceptCaregiverInvitation_alreadyActiveSameUser_isIdempotent() {
        UUID caregiver = UUID.randomUUID();
        PetCaregiverDelegation d = invited(caregiver, LocalDateTime.now().plusDays(1));
        d.setStatus(CaregiverStatus.ACTIVE);
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        svc.acceptCaregiverInvitation(caregiver, "raw-token");

        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.ACTIVE);
        verify(outbox, never()).save(any());
    }

    @Test
    void acceptCaregiverInvitation_alreadyRevoked_invalidTransition() {
        UUID caregiver = UUID.randomUUID();
        PetCaregiverDelegation d = invited(caregiver, LocalDateTime.now().plusDays(1));
        d.setStatus(CaregiverStatus.REVOKED);
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        assertThatThrownBy(() -> svc.acceptCaregiverInvitation(caregiver, "raw-token"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectCaregiverInvitation_becomesRejected() {
        UUID caregiver = UUID.randomUUID();
        PetCaregiverDelegation d = invited(caregiver, LocalDateTime.now().plusDays(1));
        when(delegations.findByInvitationTokenHash(svc.sha256Hex("raw-token")))
                .thenReturn(Optional.of(d));

        svc.rejectCaregiverInvitation(caregiver, "raw-token");

        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.REJECTED);
    }
```

- [ ] **Step 2: Chạy, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=CaregiverDelegationServiceImplTest`
Expected: FAIL — `UnsupportedOperationException` từ Task 8.

- [ ] **Step 3: Cài đặt accept/reject**

```java
    @Override
    @Transactional
    public CaregiverDelegationResponse acceptCaregiverInvitation(UUID actorUserId, String rawToken) {
        return respondToInvitation(actorUserId, rawToken, CaregiverStatus.ACTIVE,
                "CaregiverInvitationAccepted");
    }

    @Override
    @Transactional
    public CaregiverDelegationResponse rejectCaregiverInvitation(UUID actorUserId, String rawToken) {
        return respondToInvitation(actorUserId, rawToken, CaregiverStatus.REJECTED,
                "CaregiverInvitationRejected");
    }

    private CaregiverDelegationResponse respondToInvitation(UUID actorUserId, String rawToken,
                                                            CaregiverStatus target, String eventType) {
        PetCaregiverDelegation delegation = delegations.findByInvitationTokenHash(sha256Hex(rawToken))
                .orElseThrow(() -> new ResourceNotFoundException("CaregiverInvitation", "token"));

        // RULE-04-05 — tự kiểm hạn, không tin job (spec D-08).
        if (delegation.getStatus() == CaregiverStatus.INVITED
                && !delegation.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessRuleViolationException("RULE-04-05", "Lời mời đã hết hạn (RULE-04-05)");
        }

        // A-03: đã gán thì bắt buộc khớp; A-02: chưa gán thì người cầm token nhận ủy quyền.
        if (delegation.getCaregiverUserId() != null
                && !delegation.getCaregiverUserId().equals(actorUserId)) {
            throw new UnauthorizedDelegatedActionException("INVITED_CAREGIVER", "OTHER_USER");
        }

        // Spec D-10 — idempotent khi đã ở đúng đích và đúng chủ thể.
        if (delegation.getStatus() == target
                && actorUserId.equals(delegation.getCaregiverUserId())) {
            return mapper.toDelegationResponse(delegation);
        }

        transitions.validateTransition(delegation.getStatus(), target);
        if (delegation.getCaregiverUserId() == null) {
            delegation.setCaregiverUserId(actorUserId);
        }
        delegation.setStatus(target);
        writeOutbox(delegation.getPetId(), eventType, delegation.getId());
        return mapper.toDelegationResponse(delegation);
    }
```

Thêm import `BusinessRuleViolationException`, `UnauthorizedDelegatedActionException`, `CaregiverDelegationResponse`.

- [ ] **Step 4: Chạy, xác nhận xanh**

Run: `cd BE && mvn test -Dtest=CaregiverDelegationServiceImplTest`
Expected: PASS, 13 test.

- [ ] **Step 5: Commit**

```bash
git add BE/src/main/java/com/petcare/module/pet/service/CaregiverDelegationServiceImpl.java \
        BE/src/test/java/com/petcare/module/pet/service/CaregiverDelegationServiceImplTest.java
git commit -m "feat(caregiver): add accept/reject invitation with idempotency"
```

---

### Task 10: `revokeCaregiver` (phủ cả `INVITED` và `ACTIVE`)

**Files:**
- Modify: `BE/src/main/java/com/petcare/module/pet/service/CaregiverDelegationServiceImpl.java`
- Test: `BE/src/test/java/com/petcare/module/pet/service/CaregiverDelegationServiceImplTest.java` (thêm ca)

**Interfaces:**
- Consumes: `findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc` (Task 2), `accessGuard.requirePrimaryOwner` (Task 5).
- Produces: `revokeCaregiver(UUID ownerUserId, UUID petId, RevokeCaregiverRequest req) : CaregiverDelegationResponse`.

- [ ] **Step 1: Viết test đỏ**

```java
    @Test
    void revokeCaregiver_activeDelegation_becomesRevoked() {
        PetCaregiverDelegation d = invited(UUID.randomUUID(), LocalDateTime.now().plusDays(1));
        d.setStatus(CaregiverStatus.ACTIVE);
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(delegations.findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(
                eq(pet.getId()), eq("cg@example.com"), any())).thenReturn(Optional.of(d));

        svc.revokeCaregiver(owner, pet.getId(), new RevokeCaregiverRequest("cg@example.com"));

        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.REVOKED);
        verify(outbox).save(any());
    }

    /** Spec D-04 — chủ hủy lời mời đang treo. */
    @Test
    void revokeCaregiver_pendingInvitation_becomesRevoked() {
        PetCaregiverDelegation d = invited(null, LocalDateTime.now().plusDays(1));
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(delegations.findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(
                eq(pet.getId()), eq("cg@example.com"), any())).thenReturn(Optional.of(d));

        svc.revokeCaregiver(owner, pet.getId(), new RevokeCaregiverRequest("cg@example.com"));

        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.REVOKED);
    }

    /** Spec D-10 — revoke lần hai bởi owner trả 200, không ghi event lần hai. */
    @Test
    void revokeCaregiver_alreadyRevoked_isIdempotent() {
        PetCaregiverDelegation d = invited(UUID.randomUUID(), LocalDateTime.now().plusDays(1));
        d.setStatus(CaregiverStatus.REVOKED);
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(delegations.findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(
                eq(pet.getId()), eq("cg@example.com"), any())).thenReturn(Optional.of(d));

        svc.revokeCaregiver(owner, pet.getId(), new RevokeCaregiverRequest("cg@example.com"));

        verify(outbox, never()).save(any());
    }

    @Test
    void revokeCaregiver_notPrimaryOwner_forbidden() {
        UUID stranger = UUID.randomUUID();
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        doThrow(new UnauthorizedDelegatedActionException("PET_PRIMARY_OWNER", "NOT_PRIMARY_OWNER"))
                .when(accessGuard).requirePrimaryOwner(stranger, pet);

        assertThatThrownBy(() -> svc.revokeCaregiver(stranger, pet.getId(),
                new RevokeCaregiverRequest("cg@example.com")))
                .isInstanceOf(UnauthorizedDelegatedActionException.class);
    }

    @Test
    void revokeCaregiver_noDelegationForEmail_notFound() {
        when(pets.findById(pet.getId())).thenReturn(Optional.of(pet));
        when(delegations.findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(
                eq(pet.getId()), eq("cg@example.com"), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.revokeCaregiver(owner, pet.getId(),
                new RevokeCaregiverRequest("cg@example.com")))
                .isInstanceOf(ResourceNotFoundException.class);
    }
```

- [ ] **Step 2: Chạy, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=CaregiverDelegationServiceImplTest`
Expected: FAIL — `UnsupportedOperationException`.

- [ ] **Step 3: Cài đặt revoke**

```java
    @Override
    @Transactional
    public CaregiverDelegationResponse revokeCaregiver(UUID ownerUserId, UUID petId,
                                                        RevokeCaregiverRequest req) {
        Pet pet = pets.findById(petId).orElseThrow(() -> new ResourceNotFoundException("Pet", petId));
        accessGuard.requirePrimaryOwner(ownerUserId, pet); // RULE-04-04

        // INVITED + ACTIVE là bản ghi "còn sống"; REVOKED để phục vụ idempotency (D-10).
        PetCaregiverDelegation delegation = delegations
                .findFirstByPetIdAndCaregiverEmailAndStatusInOrderByCreatedAtDesc(petId,
                        req.caregiverEmail(),
                        List.of(CaregiverStatus.INVITED, CaregiverStatus.ACTIVE, CaregiverStatus.REVOKED))
                .orElseThrow(() -> new ResourceNotFoundException("CaregiverDelegation", req.caregiverEmail()));

        if (delegation.getStatus() == CaregiverStatus.REVOKED) { // spec D-10
            return mapper.toDelegationResponse(delegation);
        }

        transitions.validateTransition(delegation.getStatus(), CaregiverStatus.REVOKED);
        delegation.setStatus(CaregiverStatus.REVOKED); // RULE-04-08 — hiệu lực tức thì
        writeOutbox(petId, "CaregiverRevoked", delegation.getId());
        return mapper.toDelegationResponse(delegation);
    }
```

Thêm import `java.util.List`, `RevokeCaregiverRequest`.

- [ ] **Step 4: Chạy, xác nhận xanh**

Run: `cd BE && mvn test -Dtest=CaregiverDelegationServiceImplTest`
Expected: PASS, 18 test.

- [ ] **Step 5: Commit**

```bash
git add BE/src/main/java/com/petcare/module/pet/service/CaregiverDelegationServiceImpl.java \
        BE/src/test/java/com/petcare/module/pet/service/CaregiverDelegationServiceImplTest.java
git commit -m "feat(caregiver): add RevokeCaregiver covering pending invitations (D-04)"
```

---

### Task 11: Controller — 4 endpoint

**Files:**
- Create: `BE/src/main/java/com/petcare/module/pet/controller/CaregiverDelegationController.java`
- Test: `BE/src/test/java/com/petcare/module/pet/controller/CaregiverDelegationControllerTest.java`

**Interfaces:**
- Consumes: `CaregiverDelegationService` (Task 8-10), `NotificationService.dispatch(UUID, String)`, `UserPrincipal.getUserId()`, `ApiResponse.created/ok`, `OpenApiConfig.BEARER_SCHEME_NAME`.
- Produces: 4 endpoint HTTP như spec §8.

- [ ] **Step 1: Viết test đỏ**

```java
package com.petcare.module.pet.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.notification.service.NotificationService;
import com.petcare.module.pet.dto.CaregiverDelegationResponse;
import com.petcare.module.pet.dto.CaregiverInvitationResponse;
import com.petcare.module.pet.dto.InviteCaregiverRequest;
import com.petcare.module.pet.dto.RevokeCaregiverRequest;
import com.petcare.module.pet.service.CaregiverDelegationService;
import com.petcare.module.pet.service.CaregiverInvitationOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wiring copy PetControllerTest (@WebMvcTest + principal giả). Nếu PetControllerTest
 * dùng helper/annotation riêng để nạp UserPrincipal, tái dùng đúng helper đó ở đây.
 */
@WebMvcTest(CaregiverDelegationController.class)
class CaregiverDelegationControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean CaregiverDelegationService service;
    @MockitoBean NotificationService notificationService;

    private final UUID petId = UUID.randomUUID();

    @Test
    void invite_dispatchesEmailWhenTaskCreated() throws Exception {
        UUID taskId = UUID.randomUUID();
        CaregiverInvitationResponse body = new CaregiverInvitationResponse(
                UUID.randomUUID(), petId, "cg@example.com", "INVITED",
                LocalDateTime.now().plusDays(7), null, null);
        when(service.inviteCaregiver(any(), eq(petId), any(InviteCaregiverRequest.class)))
                .thenReturn(new CaregiverInvitationOutcome(body, taskId, "cg@example.com"));

        mockMvc.perform(post("/api/pets/{id}/caregiver-invitations", petId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new InviteCaregiverRequest("cg@example.com", null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("INVITED"));

        verify(notificationService).dispatch(taskId, "cg@example.com");
    }

    @Test
    void invite_skipsDispatchWhenNoTask() throws Exception {
        CaregiverInvitationResponse body = new CaregiverInvitationResponse(
                UUID.randomUUID(), petId, "new@example.com", "INVITED",
                LocalDateTime.now().plusDays(7), null, "raw-token");
        when(service.inviteCaregiver(any(), eq(petId), any(InviteCaregiverRequest.class)))
                .thenReturn(new CaregiverInvitationOutcome(body, null, "new@example.com"));

        mockMvc.perform(post("/api/pets/{id}/caregiver-invitations", petId)
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

        mockMvc.perform(post("/api/caregiver-invitations/{token}/accept", "raw-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void revoke_returns200() throws Exception {
        when(service.revokeCaregiver(any(), eq(petId), any(RevokeCaregiverRequest.class)))
                .thenReturn(new CaregiverDelegationResponse(UUID.randomUUID(), petId,
                        UUID.randomUUID(), "cg@example.com", "REVOKED",
                        LocalDateTime.now().plusDays(7), null));

        mockMvc.perform(post("/api/pets/{id}/caregiver-revoke", petId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RevokeCaregiverRequest("cg@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
    }
}
```

- [ ] **Step 2: Chạy, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=CaregiverDelegationControllerTest`
Expected: FAIL — controller chưa tồn tại.

- [ ] **Step 3: Viết controller**

```java
package com.petcare.module.pet.controller;

import com.petcare.module.notification.service.NotificationService;
import com.petcare.module.pet.dto.CaregiverDelegationResponse;
import com.petcare.module.pet.dto.CaregiverInvitationResponse;
import com.petcare.module.pet.dto.InviteCaregiverRequest;
import com.petcare.module.pet.dto.RevokeCaregiverRequest;
import com.petcare.module.pet.service.CaregiverDelegationService;
import com.petcare.module.pet.service.CaregiverInvitationOutcome;
import com.petcare.platform.config.OpenApiConfig;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.security.UserPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME_NAME)
@RestController
@RequiredArgsConstructor
public class CaregiverDelegationController {

    private final CaregiverDelegationService svc;
    private final NotificationService notificationService;

    @PostMapping("/api/pets/{id}/caregiver-invitations")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CaregiverInvitationResponse>> invite(
            @AuthenticationPrincipal UserPrincipal me, @PathVariable UUID id,
            @Valid @RequestBody InviteCaregiverRequest req) {
        CaregiverInvitationOutcome outcome = svc.inviteCaregiver(me.getUserId(), id, req);
        // Dispatch NGOÀI transaction đã commit — lỗi SMTP tạm thời không rollback
        // việc tạo delegation (javadoc NotificationService, khuôn AuthController).
        if (outcome.notificationTaskId() != null) {
            notificationService.dispatch(outcome.notificationTaskId(), outcome.toAddress());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(outcome.response(), "success"));
    }

    @PostMapping("/api/caregiver-invitations/{token}/accept")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CaregiverDelegationResponse>> accept(
            @AuthenticationPrincipal UserPrincipal me, @PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.ok(svc.acceptCaregiverInvitation(me.getUserId(), token)));
    }

    @PostMapping("/api/caregiver-invitations/{token}/reject")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CaregiverDelegationResponse>> reject(
            @AuthenticationPrincipal UserPrincipal me, @PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.ok(svc.rejectCaregiverInvitation(me.getUserId(), token)));
    }

    @PostMapping("/api/pets/{id}/caregiver-revoke")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CaregiverDelegationResponse>> revoke(
            @AuthenticationPrincipal UserPrincipal me, @PathVariable UUID id,
            @Valid @RequestBody RevokeCaregiverRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(svc.revokeCaregiver(me.getUserId(), id, req)));
    }
}
```

- [ ] **Step 4: Chạy, xác nhận xanh**

Run: `cd BE && mvn test -Dtest=CaregiverDelegationControllerTest`
Expected: PASS, 5 test.

- [ ] **Step 5: Commit**

```bash
git add BE/src/main/java/com/petcare/module/pet/controller/CaregiverDelegationController.java \
        BE/src/test/java/com/petcare/module/pet/controller/CaregiverDelegationControllerTest.java
git commit -m "feat(caregiver): add 4 delegation endpoints with post-commit dispatch"
```

---

### Task 12: Job hết hạn

**Files:**
- Create: `BE/src/main/java/com/petcare/module/pet/job/CaregiverExpiryService.java`
- Create: `BE/src/main/java/com/petcare/module/pet/job/CaregiverExpiryJob.java`
- Modify: `BE/src/main/resources/application.yml`
- Modify: `BE/src/test/resources/application-test.yml`
- Test: `BE/src/test/java/com/petcare/module/pet/job/CaregiverExpiryServiceTest.java`

**Interfaces:**
- Consumes: `findExpiredInvitations`/`findExpiredDelegations` (Task 2), `CaregiverDelegationTransitionHandler` (Task 3), `OutboxEventRepository`.
- Produces: `CaregiverExpiryService.processInvitationExpiry() : int`, `processDelegationExpiry() : int`.

- [ ] **Step 1: Viết test đỏ**

```java
package com.petcare.module.pet.job;

import com.petcare.module.pet.entity.PetCaregiverDelegation;
import com.petcare.module.pet.fsm.CaregiverDelegationTransitionHandler;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaregiverExpiryServiceTest {

    @Mock PetCaregiverDelegationRepository delegations;
    @Mock OutboxEventRepository outbox;

    private CaregiverExpiryService svc;

    @BeforeEach
    void setUp() {
        svc = new CaregiverExpiryService(delegations, new CaregiverDelegationTransitionHandler(), outbox);
    }

    private PetCaregiverDelegation delegation(CaregiverStatus status) {
        PetCaregiverDelegation d = new PetCaregiverDelegation();
        d.setId(UUID.randomUUID());
        d.setPetId(UUID.randomUUID());
        d.setStatus(status);
        return d;
    }

    @Test
    void processInvitationExpiry_marksInvitedAsExpiredAndEmitsEvent() {
        PetCaregiverDelegation d = delegation(CaregiverStatus.INVITED);
        when(delegations.findExpiredInvitations(any(LocalDateTime.class))).thenReturn(List.of(d));

        int updated = svc.processInvitationExpiry();

        assertThat(updated).isEqualTo(1);
        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.EXPIRED);
        verify(outbox, times(1)).save(any());
    }

    @Test
    void processDelegationExpiry_marksActiveAsExpired() {
        PetCaregiverDelegation d = delegation(CaregiverStatus.ACTIVE);
        when(delegations.findExpiredDelegations(any(LocalDateTime.class))).thenReturn(List.of(d));

        int updated = svc.processDelegationExpiry();

        assertThat(updated).isEqualTo(1);
        assertThat(d.getStatus()).isEqualTo(CaregiverStatus.EXPIRED);
    }

    /** Spec D-03: validUntil NULL = vô thời hạn, repository không trả về, job không đụng. */
    @Test
    void processDelegationExpiry_noRows_doesNothing() {
        when(delegations.findExpiredDelegations(any(LocalDateTime.class))).thenReturn(List.of());

        assertThat(svc.processDelegationExpiry()).isZero();
        verify(outbox, times(0)).save(any());
    }
}
```

- [ ] **Step 2: Chạy, xác nhận đỏ**

Run: `cd BE && mvn test -Dtest=CaregiverExpiryServiceTest`
Expected: FAIL — `CaregiverExpiryService` chưa tồn tại.

- [ ] **Step 3: Viết service + job**

```java
package com.petcare.module.pet.job;

import com.petcare.module.pet.entity.PetCaregiverDelegation;
import com.petcare.module.pet.fsm.CaregiverDelegationTransitionHandler;
import com.petcare.module.pet.repository.PetCaregiverDelegationRepository;
import com.petcare.platform.enums.CaregiverStatus;
import com.petcare.platform.outbox.OutboxEvent;
import com.petcare.platform.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RULE-04-05 (ProcessInvitationExpiry) + RULE-04-07 (ProcessDelegationExpiry).
 * Đọc từng dòng rồi update thay vì bulk UPDATE, vì mỗi dòng phải sinh một outbox
 * event; volume nhỏ như otps nên không cần batch (xem OtpExpiryJob).
 * Job này chỉ DỌN DẸP — nó không phải cơ chế bảo vệ; guard tự kiểm hạn (spec D-08).
 */
@Service
@RequiredArgsConstructor
public class CaregiverExpiryService {

    private final PetCaregiverDelegationRepository delegations;
    private final CaregiverDelegationTransitionHandler transitions;
    private final OutboxEventRepository outbox;

    @Transactional
    public int processInvitationExpiry() {
        return expire(delegations.findExpiredInvitations(LocalDateTime.now()),
                "CaregiverInvitationExpired");
    }

    @Transactional
    public int processDelegationExpiry() {
        return expire(delegations.findExpiredDelegations(LocalDateTime.now()),
                "CaregiverDelegationExpired");
    }

    private int expire(List<PetCaregiverDelegation> rows, String eventType) {
        for (PetCaregiverDelegation d : rows) {
            transitions.validateTransition(d.getStatus(), CaregiverStatus.EXPIRED);
            d.setStatus(CaregiverStatus.EXPIRED);
            OutboxEvent event = new OutboxEvent();
            event.setAggregateType("Pet");
            event.setAggregateId(d.getPetId().toString());
            event.setEventType(eventType);
            event.setPayload("{\"petId\":\"" + d.getPetId() + "\",\"delegationId\":\"" + d.getId() + "\"}");
            outbox.save(event);
        }
        return rows.size();
    }
}
```

```java
package com.petcare.module.pet.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RULE-04-05 / RULE-04-07 — mirror OtpExpiryJob: @Scheduled đơn giản, không
 * distributed lock (an toàn với đúng 1 backend instance hiện tại).
 */
@Component
@ConditionalOnProperty(prefix = "app.caregiver-expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CaregiverExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CaregiverExpiryJob.class);

    private final CaregiverExpiryService caregiverExpiryService;

    public CaregiverExpiryJob(CaregiverExpiryService caregiverExpiryService) {
        this.caregiverExpiryService = caregiverExpiryService;
    }

    @Scheduled(cron = "${app.caregiver-expiry.cron:0 */15 * * * *}", zone = "Asia/Ho_Chi_Minh")
    public void expireOutstandingDelegations() {
        int invitations = caregiverExpiryService.processInvitationExpiry();
        int delegations = caregiverExpiryService.processDelegationExpiry();
        if (invitations > 0 || delegations > 0) {
            log.info("CaregiverExpiryJob: expired {} invitation(s), {} delegation(s)",
                    invitations, delegations);
        }
    }
}
```

- [ ] **Step 4: Thêm config**

`application.yml`, trong khối `app:` (ngay sau `otp-expiry`):

```yaml
  caregiver:
    invitation-ttl-days: 7
  caregiver-expiry:
    enabled: true
    cron: "0 */15 * * * *"
```

`application-test.yml`, trong khối `app:`:

```yaml
  caregiver:
    invitation-ttl-days: 7
  caregiver-expiry:
    enabled: false
```

- [ ] **Step 5: Chạy, xác nhận xanh**

Run: `cd BE && mvn test -Dtest=CaregiverExpiryServiceTest`
Expected: PASS, 3 test.

- [ ] **Step 6: Commit**

```bash
git add BE/src/main/java/com/petcare/module/pet/job/ \
        BE/src/main/resources/application.yml BE/src/test/resources/application-test.yml \
        BE/src/test/java/com/petcare/module/pet/job/CaregiverExpiryServiceTest.java
git commit -m "feat(caregiver): add expiry job for invitations and delegations"
```

---

### Task 13: `CaregiverFlowIT` — end-to-end

**Files:**
- Test: `BE/src/test/java/com/petcare/module/pet/CaregiverFlowIT.java`
- Modify (tùy chọn, xem ghi chú Step 1): `BE/src/main/java/com/petcare/module/notification/repository/NotificationTaskRepository.java`

**Interfaces:**
- Consumes: toàn bộ Task 1–12; `PetFlowIT:79-92` (`registerAndLogin`, `ownerOf`).
- Produces: bằng chứng hai bất biến — thu hồi hiệu lực tức thì (RULE-04-08) và hủy lời mời treo (D-04).

- [ ] **Step 1: Viết IT**

Copy wiring từ `PetFlowIT`: `@Testcontainers`, `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, `@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"})`, `@MockitoBean EmailGateway emailGateway`, field MockMvc tên **`mvc`**, và copy nguyên hai helper `registerAndLogin(String email)` + `ownerOf(String email)` đã có sẵn ở `PetFlowIT:79-92`. Mỗi test dùng email uniquify bằng `System.nanoTime()` và đánh dấu `@Transactional` — cùng lý do ghi ở `PetFlowIT:93`.

Hai helper riêng của IT này:

```java
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
```

> `NotificationTaskRepository` hiện chưa có `findTopByRecipientUserIdOrderByCreatedAtDesc` — thêm derived query này vào repository của module notification (thuần đọc, dùng cho test). Nếu không muốn đụng module notification, thay bằng `ArgumentCaptor<String>` trên `emailGateway.send(anyString(), anyString(), captor.capture())` và tách token từ body email đã gửi.

Hai test dùng `capturedInvitationToken(ownerOf("caregiver-b@example.com"))` để lấy token.

```java
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
```

- [ ] **Step 2: Chạy IT, xác nhận xanh**

Run: `cd BE && mvn verify -Dit.test=CaregiverFlowIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 2 test.

- [ ] **Step 3: Chạy toàn bộ suite**

Run: `cd BE && mvn verify`
Expected: BUILD SUCCESS. Dán output vào báo cáo task.

- [ ] **Step 4: Commit**

```bash
git add BE/src/test/java/com/petcare/module/pet/CaregiverFlowIT.java \
        BE/src/main/java/com/petcare/module/notification/repository/NotificationTaskRepository.java
git commit -m "test(caregiver): add end-to-end delegation flow IT"
```

(Bỏ file repository khỏi lệnh `git add` nếu bạn chọn phương án `ArgumentCaptor`.)

---

### Task 14: Cập nhật tài liệu đặc tả

**Files:**
- Modify: `docs/03-state-machines.md` (§3 FSM-3)
- Modify: `docs/02-business-rules.md` (RULE-04-08)
- Modify: `docs/api/customer-pet-v1.md` (§C3 + bảng Q)
- Modify: `docs/api/openapi/customer-pet-v1.yaml`
- Modify: `docs/architecture/system-overview.md` (§7)

**Interfaces:**
- Consumes: quyết định D-01, D-02, D-03, D-04, D-05, D-10 của spec.
- Produces: đặc tả khớp code — điều kiện để Task 3 hợp lệ theo `05-fsm-pattern.md`.

> Task này **không phải việc dọn dẹp cuối**. `05-fsm-pattern.md` cấm transition map chứa cạnh không có trong mermaid, nên đến khi `03-state-machines.md` được sửa thì cạnh `INVITED → REVOKED` ở Task 3 vẫn đang vi phạm convention.

- [ ] **Step 1: `docs/03-state-machines.md` §3 — thêm cạnh vào mermaid**

Thêm vào khối `stateDiagram-v2`, ngay sau dòng `INVITED --> REJECTED`:

```
    INVITED --> REVOKED: RevokeCaregiver [Primary Owner]
```

- [ ] **Step 2: `docs/03-state-machines.md` §3 — thêm dòng vào bảng transition**

Chèn sau dòng `INVITED | ProcessInvitationExpiry | ...`:

```
| INVITED | RevokeCaregiver | Customer (Primary Owner) | RULE-04-04, RULE-04-08 | REVOKED | CaregiverRevoked | Chủ sở hữu chính hủy lời mời khi người được mời chưa phản hồi; bổ sung đã duyệt 2026-09-16, xem docs/superpowers/specs/2026-09-16-caregiver-delegation-design.md D-04. |
```

- [ ] **Step 3: `docs/02-business-rules.md` — mở rộng RULE-04-08**

Thêm vào cuối phần mô tả của RULE-04-08 (giữ nguyên câu hiện có):

```
Thu hồi (`RevokeCaregiver`) áp dụng cho cả lời mời đang treo ở trạng thái `INVITED` — Primary Owner không phải đợi hết TTL 7 ngày; lời mời bị hủy chuyển thẳng sang `REVOKED` (bổ sung đã duyệt 2026-09-16).
```

- [ ] **Step 4: `docs/api/customer-pet-v1.md` — cập nhật §C3**

- Xóa cụm `+ C3 (caregiver delegation)` khỏi câu "OUT v1" ở đầu mục C.
- `POST /pets/{id}/caregiver-revoke`: đổi request thành `{caregiverEmail (req)}`, ghi rõ phủ cả `INVITED` và `ACTIVE`.
- `POST /pets/{id}/caregiver-invitations`: đổi request thành `{caregiverEmail (req), caregiverPhone?, validUntil?}`.
- Ghi rõ ngữ nghĩa idempotent: "đã ở trạng thái đích **và đúng chủ thể** → 200, không sinh event lần hai" (spec D-10).
- Bảng Q: Q5 → `ĐÓNG (D-03: validUntil tùy chọn, NULL = vô thời hạn)`; Q12 → `ĐÓNG (D-02: email nếu đã có account, trả token 1 lần nếu chưa)`.
- Thêm assumption mới: `A5 — định danh lời mời bằng email, không phải phone (RULE-01-10 đã đổi danh tính chính; lệch ERD §3.2 có chủ ý, xem spec D-01)`.

- [ ] **Step 5: `docs/api/openapi/customer-pet-v1.yaml` — đồng bộ**

Sửa schema của 4 path caregiver cho khớp Step 4: request của `/pets/{id}/caregiver-invitations` và `/pets/{id}/caregiver-revoke` dùng `caregiverEmail`; bổ sung response `409` với `errorCode` `CAREGIVER_INVITATION_CONFLICT` cho path invitations.

- [ ] **Step 6: Chạy script kiểm contract**

Run: `node docs/api/check-contracts.mjs docs/api/openapi/customer-pet-v1.yaml`
Expected: không có dòng `FAIL`. Dán output vào báo cáo task.

- [ ] **Step 7: `docs/architecture/system-overview.md` §7 — làm mới**

Thay hai gạch đầu dòng đã lỗi thời:
- "module/ hiện rỗng — chưa có module nghiệp vụ nào (0/25)" → liệt kê đúng hiện trạng: `auth`, `iam`, `notification`, `pet` (gồm caregiver delegation) đã triển khai.
- "Migration đã có 3 phiên bản" → `V1..V5`, nêu `V4` (audit pets) và `V5` (caregiver delegation).

Cập nhật dòng ngày phân tích ở cuối file thành `2026-09-16`.

- [ ] **Step 8: Chạy lại toàn bộ suite để chắc không có gì vỡ**

Run: `cd BE && mvn verify`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add docs/03-state-machines.md docs/02-business-rules.md \
        docs/api/customer-pet-v1.md docs/api/openapi/customer-pet-v1.yaml \
        docs/architecture/system-overview.md
git commit -m "docs(caregiver): add INVITED->REVOKED edge, email identity, close Q5/Q12"
```

---

## Tiêu chí hoàn thành (toàn plan)

1. `cd BE && mvn verify` xanh — dán output.
2. `node docs/api/check-contracts.mjs` pass — dán output.
3. Transition map ở `CaregiverDelegationTransitionHandler` khớp **chính xác** mermaid FSM-3 sau Task 14.
4. Năm file tài liệu ở Task 14 đã sửa.
5. Hai exception riêng đều ghi rõ tiêu chí §4.2 trong comment class.
6. `PetFlowIT`, `PetServiceImplTest`, `PetControllerTest` cũ vẫn xanh.
